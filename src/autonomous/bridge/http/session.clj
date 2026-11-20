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

(defn protocol-headers
  "Pure: the headers every request to a remote carries: `ident`, the MCP
   protocol version, `accept`, and session `sid` when there is one."
  [ident accept sid]
  (cond-> (assoc ident "accept" accept "mcp-protocol-version" rpc/protocol-version)
    sid (assoc "mcp-session-id" (str sid))))

(defn- post
  "Answer of JSON-RPC message `msg` to `target`'s endpoint with `ident`
   headers, in session `sid` (none when nil)."
  [client target msg ident sid timeout-ms]
  (port/-request! client {:method :post
                          :url (http/endpoint target)
                          :headers (assoc (protocol-headers ident rpc/post-accept sid)
                                          "content-type" "application/json")
                          :body (rpc/json-text msg)
                          :timeout-ms timeout-ms}))

(def ^:private gone-status 404)

(defn- decode
  "Answer of `resp` read as the JSON-RPC reply to request `id`: that
   message, or a refusal. A 404 is :channel/session-gone (the remote forgot
   the session and it must be renewed); any other error status is
   :channel/remote-refused, carrying the status only: an error body is the
   remote's text and may echo the request's credential back."
  [{:keys [status body]} id]
  (cond
    (= gone-status status) (answer/refusal :channel/session-gone "the remote forgot this session")
    (<= 400 status)        (answer/refusal :channel/remote-refused
                                           (str "the remote answered " status)
                                           {:status status})
    :else                  (rpc/reply-of body id)))

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
                             :headers (protocol-headers ident rpc/post-accept (:id ses))
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
     (decode resp (:id msg)))))

;; =============================================================================
;; The event stream
;; =============================================================================

(def max-event-chars
  "The most characters one SSE event may gather before its blank line."
  (* 1024 1024))

(def max-event-lines
  "The most lines one SSE event may gather before its blank line."
  4096)

(def empty-event
  "An SSE event being gathered: its lines, their size, and whether it
   passed a cap and is being dropped."
  {:lines [] :chars 0 :dropped? false})

(defn- over-cap?
  "True when adding `line` would take event `ev` past a cap."
  [ev line]
  (or (<= max-event-lines (count (:lines ev)))
      (< max-event-chars (+ (:chars ev) (count line)))))

(defn add-line
  "Pure: event `ev` with `line` appended in O(1), or emptied and marked
   :dropped? once it would pass max-event-chars or max-event-lines. A
   dropped event stays dropped until its blank line."
  [ev line]
  (cond
    (:dropped? ev)      ev
    (over-cap? ev line) (assoc empty-event :dropped? true)
    :else               (-> ev (update :lines conj line) (update :chars + (count line)))))

(defn- lines-of
  "The lines of chunk `t`, which holds whole lines each ended by a newline."
  [t]
  (let [ls (str/split (str t) #"\n" -1)]
    (if (= "" (peek ls)) (pop ls) ls)))

(def ^:private overflow
  (answer/refusal :channel/event-overflow
                  (str "a remote event passed " max-event-chars " characters or "
                       max-event-lines " lines: it is dropped")))

(defn- feed-line!
  "Boundary: take `line` into event cell `ev`: a blank line dispatches the
   gathered event's messages to `on-message` and starts the next one; an
   event that just passed a cap is reported to `on-failed`."
  [ev on-message on-failed line]
  (if (str/blank? line)
    (let [done @ev]
      (reset! ev empty-event)
      (when-not (:dropped? done)
        (run! on-message (rpc/read-messages (str/join "\n" (:lines done))))))
    (let [[before after] (swap-vals! ev add-line line)]
      (when (and (:dropped? after) (not (:dropped? before))) (on-failed overflow)))))

(defn- feed!
  "Feed chunk `t` of whole lines into event cell `ev`, line by line."
  [ev on-message on-failed t]
  (run! #(feed-line! ev on-message on-failed %) (lines-of t)))

(defn- report-failed!
  "Boundary: report refusal `a` that closed a remote stream on stderr. Its
   message is the adapter's own text, never the remote's."
  [a]
  (binding [*out* *err*]
    (println "[autonomous] remote stream closed:" (get-in a [:error :message]))))

(defn sink
  "Boundary: a sink for port/-open-stream!. `on-message` gets each JSON-RPC
   message of an event that completes, `on-close` runs once at the end, and
   `on-failed` (default: stderr) gets each refusal: an event dropped past
   its cap, or a line that closed the stream."
  ([on-message on-close] (sink on-message on-close report-failed!))
  ([on-message on-close on-failed]
   (let [ev (atom empty-event)]
     {:text!   (partial feed! ev on-message on-failed)
      :failed! on-failed
      :close!  (fn [] (on-close))})))

(defn open-stream!
  "Boundary: hold the caller's event stream and dispatch its messages to
   `on-message`, with `on-close` notified when it ends."
  [client source target grant ses on-message on-close]
  (answer/let-answer
   [ident (http/identity target source grant)]
   (port/-open-stream! client {:url (http/endpoint target)
                               :headers (protocol-headers ident rpc/sse-accept (:id ses))
                               :stream-timeout-ms (:timeout-ms target http/default-timeout-ms)}
                       (sink on-message on-close))))
