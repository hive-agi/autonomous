(ns autonomous.bridge.rpc
  "Client side of the MCP JSON-RPC dialogue a bridge holds with a remote
   app's own /mcp endpoint: the messages it sends, and the reading of what
   comes back into Answers and a Manifest. Pure; the transport is
   autonomous.bridge.http, and nothing here opens a socket.

   A remote refusal is an Answer of type :channel/*, never an exception, so
   the pipeline treats a badly-behaved remote like any other transport
   failure."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.mcp.json :as json]
            [clojure.string :as str]))

(def protocol-version "2025-06-18")

(def client-info {:name "autonomous-bridge" :version "0.1.0"})

(def list-changed "notifications/tools/list_changed")

(def sse-accept "text/event-stream")

(def post-accept
  "What a POST accepts: Streamable HTTP lets the remote answer JSON or SSE."
  "application/json, text/event-stream")

(defn request
  "A JSON-RPC request map of `method` with `params`."
  [id method params]
  (cond-> {:jsonrpc "2.0" :id id :method method} params (assoc :params params)))

(defn notification
  "A JSON-RPC notification (no id) of `method` with `params`."
  [method params]
  (cond-> {:jsonrpc "2.0" :method method} params (assoc :params params)))

(defn initialize-msg
  "The opening message: protocol version, no capabilities, who we are."
  [id]
  (request id "initialize" {:protocolVersion protocol-version
                            :capabilities {}
                            :clientInfo client-info}))

(defn initialized-msg [] (notification "notifications/initialized" nil))

(defn list-msg [id] (request id "tools/list" nil))

(defn call-msg [id tool args]
  (request id "tools/call" {:name tool :arguments (or args {})}))

(defn read-msg [id uri] (request id "resources/read" {:uri uri}))

(def ^:private seq-id (atom 0))

(defn next-id
  "The next JSON-RPC request id of this process."
  []
  (swap! seq-id inc))

(def remote-tools
  "Request ops that are one call of the remote's own built-in tool. A remote
   bridge names its tools with `_`, as it does for every app tool."
  {:poll "autonomous_poll" :journal "autonomous_journal" :lease "autonomous_lease"
   :release "autonomous_release"})

(defn remote-args
  "Arguments of the built-in call request op `op` makes from `req`."
  [op req]
  (case op
    :poll    (select-keys req [:id])
    :journal (select-keys req [:since :limit])
    :lease   (select-keys req [:ttl-ms])
    {}))

;; =============================================================================
;; Server-Sent Events, incoming
;; =============================================================================

(defn sse-payloads
  "JSON text of every `data:` line of SSE `text`. MCP writes one message per
   line, so a line is a message (a `data:` split over several lines is not
   joined)."
  [text]
  (into [] (comp (map str/trim)
                 (filter #(str/starts-with? % "data:"))
                 (map #(str/trim (subs % (min 5 (count %)))))
                 (remove str/blank?))
        (str/split-lines (str text))))

(defn read-messages
  "The JSON-RPC messages in SSE `text`; a line that is not JSON is skipped,
   as a keepalive comment or a partial frame is."
  [text]
  (into [] (keep (fn [p] (try (json/read-json p) (catch Exception _not-json nil))))
        (sse-payloads text)))

(defn json-text
  "JSON text of message `msg` (what an adapter puts on the wire)."
  [msg]
  (json/write msg))

(defn list-changed?
  "True when `msg` says the remote's tool list moved."
  [msg]
  (= list-changed (:method msg)))

;; =============================================================================
;; Answers
;; =============================================================================

(defn- content-text
  "The text content of tool result `result` joined, or nil."
  [result]
  (when-let [parts (seq (keep :text (:content result)))] (str/join "\n" parts)))

(defn- error-code
  "The JSON-RPC error code of `err` when it is an integer, else nil: a
   remote's free-form value is never carried."
  [err]
  (let [c (:code err)] (when (int? c) c)))

(defn answer-of
  "Answer of the tools/call response `msg`: the remote's value, a Pending id
   it reported, or a refusal. A call the remote marks failed is a refusal,
   so the bridge never reports an app error as a value. A refusal carries a
   fixed message and at most the integer error code: the remote's text may
   echo a credential, so it never enters a refusal."
  [msg]
  (let [err  (:error msg)
        res  (:result msg)
        body (when (map? res) (:structuredContent res))]
    (cond
      err                            (answer/refusal :channel/rpc-error "the remote refused the call"
                                                     {:code (error-code err)})
      (not (map? res))               (answer/refusal :channel/bad-answer "the remote sent neither result nor error")
      (:isError res)                 (answer/refusal :channel/tool-failed "the remote tool failed")
      (contains? body :pending)      (answer/pending (get body :pending))
      (contains? res :structuredContent) (answer/ok body)
      :else                          (answer/ok (or (content-text res) {})))))

(defn sse-type?
  "Pure: true when Content-Type `content-type` names an SSE stream
   (text/event-stream, any parameters and case)."
  [content-type]
  (= sse-accept (str/trim (str/lower-case (first (str/split (str content-type) #";"))))))

(defn- messages-of
  "Every JSON-RPC message in reply body `body`, read as SSE when `sse?`,
   else as one JSON object or batch. Throws when JSON does not parse."
  [body sse?]
  (if sse?
    (read-messages body)
    (let [v (json/read-json (str body))]
      (if (map? v) [v] (filterv map? v)))))

(defn reply-of
  "Pure: Ok of the message in reply body `body` of Content-Type
   `content-type` that answers request `id`, skipping notifications and
   other replies, or a :channel/bad-answer refusal when none does or the
   body is not JSON-RPC. The header decides the framing, never the body."
  [body content-type id]
  (let [msgs (try (messages-of body (sse-type? content-type)) (catch Exception _not-json nil))]
    (if-let [m (first (filter #(and (contains? % :id) (= id (:id %))) msgs))]
      (answer/ok m)
      (answer/refusal :channel/bad-answer "the remote sent no reply to this request"))))

;; =============================================================================
;; Manifest
;; =============================================================================

(defn- tool-of
  "Manifest tool of MCP tool `t`."
  [t]
  (cond-> {:name         (:name t)
           :title        (:title t)
           :description  (:description t)
           :input-schema (:inputSchema t)
           :annotations  (:annotations t)}
    (:outputSchema t) (assoc :output-schema (:outputSchema t))))

(defn- resource-of
  "Manifest resource of MCP resource `r`."
  [r]
  (select-keys {:uri (:uri r) :name (:name r) :description (:description r) :mime (:mimeType r)}
               [:uri :name :description :mime]))

(defn version-of
  "A number that moves when the remote's tools do, so a changed remote moves
   the fleet's fingerprint and its agents are told."
  [tools]
  (hash (mapv (juxt :name :description) tools)))

(defn manifest-of
  "The Manifest of app `target-id` at `url` whose MCP tools are `tools` and
   resources `resources`. A tool with no name is dropped."
  [target-id url tools resources]
  {:protocol  1
   :version   (version-of tools)
   :app       {:name (str target-id) :description (str "remote app at " url)}
   :tools     (into [] (comp (remove #(str/blank? (str (:name %)))) (map tool-of)) tools)
   :resources (mapv resource-of resources)})
