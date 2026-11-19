(ns autonomous.mcp.http.kit
  "HTTP socket adapter on http-kit, which babashka carries built in.
   Boundary only: every decision is autonomous.mcp.http/handle-request. The
   server is autonomous.mcp.http/ring-handler; a stream is an http-kit async
   channel (`stream-response`), which a host serving the Ring handler from
   its own http-kit server passes in the same way."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.mcp.http :as http]
            [org.httpkit.server :as kit]))

(defn- channel-sink
  "Sink {:send! :close!} over http-kit channel `ch`: the first send carries
   the response's status and headers. A send to a closed channel throws, so
   the pusher drops the stream."
  [ch status headers]
  (let [started? (atom false)]
    {:send!  (fn [text]
               (let [msg (if (compare-and-set! started? false true)
                           {:status status :headers headers :body text}
                           text)]
                 (when-not (kit/send! ch msg false)
                   (throw (ex-info "stream closed" {})))))
     :close! (fn [] (kit/close ch))}))

(defn stream-response
  "A `:stream-response` for autonomous.mcp.http/ring-handler on http-kit:
   holds the stream on an async channel attached to `state`."
  [state]
  (fn [ring-req {:keys [status headers stream]}]
    (let [id (atom nil)]
      (kit/as-channel ring-req
                      {:on-open  (fn [ch]
                                   (reset! id (http/attach-stream! state stream (channel-sink ch status headers))))
                       :on-close (fn [_ch _status]
                                   (when-let [i @id] (http/detach-stream! state (:session stream) i)))}))))

(def worker-threads
  "Request threads. A call blocks its thread for up to the call timeout
   while the runtime answers, so this matches the JDK adapter's pool. An
   open stream holds no thread."
  16)

(defn start!
  "Listen on `bind`:`port` (port 0 picks one). Answers Ok {:port :state
   :stop!} or the exposure refusal. http-kit itself refuses a body larger
   than one byte past the limit, so an oversized request is never buffered
   whole; handle-request answers 413 for the rest. Runs the stream pusher
   while it listens."
  [srv {:keys [bind port tokens] :or {bind "127.0.0.1"} :as cfg}]
  (if-let [r (http/exposure-refusal bind tokens)]
    r
    (try
      (let [state   (http/new-state)
            handler (http/ring-handler srv state cfg {:stream-response (stream-response state)})
            server  (kit/run-server handler
                                    {:ip bind :port port :legacy-return-value? false
                                     :thread worker-threads
                                     :max-body (inc http/max-body-bytes)})
            stop-pusher! (http/start-pusher! srv state cfg)]
        (answer/ok {:port  (kit/server-port server)
                    :state state
                    :stop! (fn []
                             (stop-pusher!)
                             (http/close-streams! state)
                             @(kit/server-stop! server {:timeout 5000}))}))
      (catch Exception bind-failure
        (http/bind-refusal bind port bind-failure)))))
