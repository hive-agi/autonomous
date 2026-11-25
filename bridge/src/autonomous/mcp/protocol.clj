(ns autonomous.mcp.protocol
  "MCP over JSON-RPC 2.0, as pure functions from Answers and Manifests to
   protocol messages. No transport, no state: stdio and HTTP share this."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.mcp.json :as json]))

(def supported-versions
  "Newest first. A client asking for another version gets the newest."
  ["2025-06-18" "2025-03-26" "2024-11-05"])

(defn negotiate
  "L1 Promote: the protocol version to speak: `requested` when supported,
   else the newest."
  [requested]
  (if (some #{requested} supported-versions) requested (first supported-versions)))

(def error-codes
  {:parse            -32700
   :invalid-request  -32600
   :method-not-found -32601
   :invalid-params   -32602
   :internal         -32603})

(defn result
  "L1 Promote: the JSON-RPC success response to request `id`."
  [id r]
  {:jsonrpc "2.0" :id id :result r})

(defn error
  "L1 Promote: the JSON-RPC error response to request `id`; `code-key`
   names an entry of error-codes, `data` is left out when nil."
  ([id code-key message] (error id code-key message nil))
  ([id code-key message data]
   {:jsonrpc "2.0" :id id
    :error (cond-> {:code (error-codes code-key) :message message}
             (some? data) (assoc :data data))}))

(defn refusal-error
  "L1 Promote: the JSON-RPC :invalid-params error carrying refusal `a`'s
   message and `data`."
  [id a data]
  (error id :invalid-params (get-in a [:error :message]) data))

(defn notification
  "L1 Promote: a JSON-RPC notification of `method`, `params` left out when
   falsy."
  [method params]
  (cond-> {:jsonrpc "2.0" :method method}
    params (assoc :params params)))

(defn notification?
  "L1 Promote: true when `msg` is a JSON-RPC notification (a method, no id)."
  [msg]
  (and (map? msg) (contains? msg :method) (not (contains? msg :id))))

(defn request?
  "L1 Promote: true when `msg` is a JSON-RPC 2.0 message naming a method."
  [msg]
  (and (map? msg) (= "2.0" (:jsonrpc msg)) (string? (:method msg))))

(defn ->mcp-tool
  "L1 Promote: MCP tool definition of a manifest tool entry."
  [t]
  (cond-> {:name        (:name t)
           :title       (:title t)
           :description (:description t)
           :inputSchema (:input-schema t)
           :annotations (:annotations t)}
    (:output-schema t) (assoc :outputSchema (:output-schema t))))

(defn ->mcp-resource
  "L1 Promote: MCP resource definition of a manifest resource entry."
  [r]
  {:uri (:uri r) :name (:name r) :description (:description r) :mimeType (:mime r)})

(defn- text-content
  "L1 Promote: an MCP content vector of one text item."
  [text]
  [{:type "text" :text text}])

(defn- ok-result
  "L1 Promote: CallToolResult of Ok value `v`; structuredContent must be
   an object, so a value that is not a map is carried as {:result v}."
  [v]
  {:content (text-content (json/write v)) :structuredContent (if (map? v) v {:result v})})

(defn- pending-result
  "L1 Promote: CallToolResult telling the agent how to collect Pending `id`."
  [id]
  {:content (text-content (str "still running; collect the result with autonomous_poll id=" id))
   :structuredContent {:pending id}})

(defn tool-result
  "L1 Promote: MCP CallToolResult of Answer `a`, one per variant."
  [a]
  (cond
    (answer/ok? a)      (ok-result (:ok a))
    (answer/pending? a) (pending-result (:pending a))
    :else               {:content (text-content (json/write (:error a))) :isError true}))

(defn resource-contents
  "L1 Promote: MCP ReadResourceResult of Answer `a` for `uri`."
  [uri a]
  {:contents [{:uri uri
               :mimeType (get-in a [:ok :mime] "application/json")
               :text (json/write (get-in a [:ok :value]))}]})

(def base-instructions
  (str "This server exposes a running application. Its own tools act on the app; tools prefixed "
       "autonomous_ manage your session: autonomous_runtimes lists the running instances (tabs, "
       "processes) and autonomous_attach pins you to one; autonomous_lease gives you exclusive "
       "write access while you run a multi-step change (release it after); autonomous_journal and "
       "autonomous_ledger show what you, other agents and the human did. Arguments are validated: "
       "an error answer says what to change."))

(defn- instructions
  "L1 Promote: the server instructions, led by the app's own description."
  [app]
  (str (when-let [d (:description app)] (str (:name app) ": " d "\n\n"))
       base-instructions))

(defn initialize-result
  "L1 Promote: the MCP InitializeResult for protocol `version`, `app` and
   `server-version`."
  [version app server-version]
  {:protocolVersion version
   :capabilities {:tools {:listChanged true}
                  :resources {}}
   :serverInfo {:name "autonomous" :version server-version}
   :instructions (instructions app)})
