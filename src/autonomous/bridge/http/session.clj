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
   renewed); any other error status is :channel/remote-refused, carrying
   the status only: an error body is the remote's text and may echo the
   request's credential back."
  [{:keys [status body]}]
  (cond
    (= gone-status status) (answer/refusal :channel/session-gone "the remote forgot this session")
    (<= 400 status)        (answer/refusal :channel/remote-refused
                                           (str "the remote answered " status)
                                           {:status status})
    :else                  (try (answer/ok (rpc/message-of body))
                                (catch Exception _not-json
                                  (answer/refusal :channel/bad-answer "the remote did not answer JSON-RPC")))))

(defn open-session!
  "Ok of a session {:id :actor} opened for `grant` on `target`, or a
   refusal. Resolves `source`'s credential reference before each exchange."
  [client source target grant timeout-ms]
  (answer/let-answer
   [ident (http/identity target source grant)
    init  (post client target (rpc/initialize-msg (rpc/next-id)) ident nil timeout-ms)
    sid   (let [id (get-in init [:headers "mcp-session-id"])]
            (if (str/blank? (str id))
              (answer/refusal :channel/remote-refused "the remote opened no MCP session")
              (answer/ok (str id))))]
   (post client target (rpc/initialized-msg) ident sid timeout-ms)
   (answer/ok {:id sid :actor (:actor grant)})))

(defn close-session!
  "Release session `ses` with the same caller's credential; cleanup proceeds
   even when the remote has already forgotten it."
  [client source target grant ses]
  (when ses
    (answer/let-answer
     [ident (http/identity target source grant)]
     (port/-request! client {:method :delete :url (http/endpoint target)
                             :headers (assoc ident "mcp-session-id" (str (:id ses)))
                             :timeout-ms 5000}))))

(defn round
  "Answer of `msg` sent in session `ses` as `grant`: the JSON-RPC reply
   unwrapped. A session is never lent to another actor."
  [client source target grant ses msg timeout-ms]
  (let [ses (if (map? ses) ses {:id ses})]
    (answer/let-answer
     [_     (if (and (:actor ses) (not= (:actor ses) (:actor grant)))
              (answer/refusal :channel/session-owner
                              "this remote session belongs to another agent")
              (answer/ok nil))
      ident (http/identity target source grant)
      resp  (post client target msg ident (:id ses) timeout-ms)]
     (decode resp))))

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
  "Boundary: hold the caller's event stream and dispatch its messages to
   `on-message`, with `on-close` notified when it ends."
  [client source target grant ses on-message on-close]
  (answer/let-answer
   [ident (http/identity target source grant)]
   (port/-open-stream! client {:url (http/endpoint target)
                               :headers (merge ident {"accept" rpc/sse-accept
                                                      "mcp-session-id" (str (:id ses))})
                               :stream-timeout-ms (:timeout-ms target http/default-timeout-ms)}
                       (sink on-message on-close))))
