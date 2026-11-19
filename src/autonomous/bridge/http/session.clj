(ns autonomous.bridge.http.session
  "MCP client session against one remote /mcp: open (initialize), one
   request/response round, and the event stream that keeps it and reports
   list_changed.

   A session belongs to ONE actor. The remote answers 404 to any other, so
   the actor is part of the session's identity here too and a session is
   never lent across agents.

   Every function answers an Answer; a session is opened with
   `open-session!` and released with `close-session!`."
  (:require [autonomous.bridge.http :as http]
            [autonomous.bridge.port :as port]
            [autonomous.bridge.rpc :as rpc]
            [autonomous.domain.answer :as answer]
            [clojure.string :as str]))

(defn- post
  "Answer of JSON-RPC message `msg` to `target`'s endpoint with `ident`
   headers, in session `sid` (none when nil)."
  [client target msg ident sid timeout-ms]
  (let [req {:method :post
             :url (http/endpoint target)
             :headers (cond-> (assoc ident "content-type" "application/json")
                        sid (assoc "mcp-session-id" (str sid)))
             :body (rpc/json-text msg)
             :timeout-ms timeout-ms}]
    (port/-request! client req)))

(def ^:private gone-status 404)

(defn- decode
  "Answer of `resp` read as JSON-RPC: its message, or a refusal. A 404 is
   :channel/session-gone (the remote forgot the session and it must be
   renewed); any other error status is :channel/remote-refused."
  [{:keys [status body]}]
  (cond
    (= gone-status status) (answer/refusal :channel/session-gone "the remote forgot this session")
    (<= 400 status)        (answer/refusal :channel/remote-refused
                                           (str "the remote answered " status)
                                           {:status status :body (subs (str body) 0 (min 200 (count (str body))))})
    :else                  (try (answer/ok (rpc/message-of body))
                                (catch Exception _not-json
                                  (answer/refusal :channel/bad-answer "the remote did not answer JSON-RPC")))))

(defn open-session!
  "Ok of a session {:id :actor} opened for `grant` on `target`, or a
   refusal. Runs initialize and the initialized notification."
  [client target grant timeout-ms]
  (let [ident (http/identity target)
        init  (post client target (rpc/initialize-msg (rpc/next-id)) (ident grant) nil timeout-ms)]
    (if (answer/refused? init)
      init
      (let [sid (get-in init [:ok :headers "mcp-session-id"])]
        (if (str/blank? (str sid))
          (answer/refusal :channel/remote-refused "the remote opened no MCP session")
          (do (post client target (rpc/initialized-msg) (ident grant) sid timeout-ms)
              (answer/ok {:id (str sid) :actor (:actor grant)})))))))

(defn close-session!
  "Release session `ses` (a DELETE); a refusal is ignored, the stream and
   the idle sweep clean up anyway."
  [client target grant ses]
  (when ses
    (port/-request! client {:method :delete :url (http/endpoint target)
                            :headers (merge ((http/identity target) grant) {"mcp-session-id" (str (:id ses))})
                            :timeout-ms 5000})))

(defn round
  "Answer of `msg` sent in session `ses` as `grant`: the JSON-RPC reply
   unwrapped. A refusal is a transport or protocol failure, never thrown."
  [client target grant ses msg timeout-ms]
  (let [ses (if (map? ses) ses {:id ses})]
    (if-let [why (when (and (:actor ses) (not= (:actor ses) (:actor grant)))
                   "this remote session belongs to another agent")]
      (answer/refusal :channel/session-owner why)
      (let [r (post client target msg ((http/identity target) grant) (:id ses) timeout-ms)]
        (if (answer/refused? r) r (decode (:ok r)))))))

;; =============================================================================
;; The event stream
;; =============================================================================

(def ^:private event-gap "\n\n")

(defn- cut
  "Ok [complete-events remainder] of SSE buffer `text` at its last blank
   line, or nil while no event is complete."
  [text]
  (when-let [i (str/last-index-of text event-gap)]
    (answer/ok [(subs text 0 i) (subs text (+ i (count event-gap))) ])))

(defn- feed!
  "Feed chunk `t` into buffer `buf`, invoking `on-message` for each message
   that completes."
  [buf on-message t]
  (when-let [{:keys [ok]} (cut (swap! buf str t))]
    (let [[events rest-] ok]
      (swap! buf (constantly rest-))
      (run! on-message (rpc/read-messages events)))))

(defn- sink
  "Sink for port/-open-stream!: `on-message` gets each completed JSON-RPC
   message, `on-close` runs once at the end."
  [on-message on-close]
  (let [buf (atom "")]
    {:text! (partial feed! buf on-message)
     :close! (fn [] (on-close))}))

(defn open-stream!
  "Transport: hold the session's event stream and dispatch messages to its callbacks."
  [client target grant ses on-message on-close]
  (port/-open-stream! client {:url (http/endpoint target)
                              :headers (merge ((http/identity target) grant)
                                              {"accept" rpc/sse-accept
                                               "mcp-session-id" (str (:id ses))})
                              :stream-timeout-ms (:timeout-ms target http/default-timeout-ms)}
                      (sink on-message on-close)))
