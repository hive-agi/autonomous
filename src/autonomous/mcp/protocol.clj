(ns autonomous.mcp.protocol
  "MCP over JSON-RPC 2.0, as pure functions from Answers and Manifests to
   protocol messages. No transport, no state: stdio and HTTP share this."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.mcp.json :as json]))

(def supported-versions
  "Newest first. A client asking for another version gets the newest."
  ["2025-06-18" "2025-03-26" "2024-11-05"])

(defn negotiate [requested]
  (if (some #{requested} supported-versions) requested (first supported-versions)))

(def error-codes
  {:parse            -32700
   :invalid-request  -32600
   :method-not-found -32601
   :invalid-params   -32602
   :internal         -32603})

(defn result [id r] {:jsonrpc "2.0" :id id :result r})

(defn error
  ([id code-key message] (error id code-key message nil))
  ([id code-key message data]
   {:jsonrpc "2.0" :id id
    :error (cond-> {:code (error-codes code-key) :message message}
             (some? data) (assoc :data data))}))

(defn notification [method params]
  (cond-> {:jsonrpc "2.0" :method method}
    params (assoc :params params)))

(defn notification? [msg]
  (and (map? msg) (contains? msg :method) (not (contains? msg :id))))

(defn ->mcp-tool
  "MCP tool definition of a manifest tool entry."
  [t]
  (cond-> {:name        (:name t)
           :title       (:title t)
           :description (:description t)
           :inputSchema (:input-schema t)
           :annotations (:annotations t)}
    (:output-schema t) (assoc :outputSchema (:output-schema t))))

(defn ->mcp-resource [r]
  {:uri (:uri r) :name (:name r) :description (:description r) :mimeType (:mime r)})

(defn tool-result
  "MCP CallToolResult of Answer `a`. structuredContent must be an object, so
   a value that is not a map is carried as {:result v}."
  [a]
  (cond
    (answer/ok? a)
    (let [v (:ok a)]
      {:content [{:type "text" :text (json/write v)}]
       :structuredContent (if (map? v) v {:result v})})

    (answer/pending? a)
    {:content [{:type "text"
                :text (str "still running; collect the result with autonomous_poll id=" (:pending a))}]
     :structuredContent {:pending (:pending a)}}

    :else
    {:content [{:type "text" :text (json/write (:error a))}]
     :isError true}))

(defn resource-contents
  "MCP ReadResourceResult of Answer `a` for `uri`."
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

(defn initialize-result
  [version app server-version]
  {:protocolVersion version
   :capabilities {:tools {:listChanged true}
                  :resources {}}
   :serverInfo {:name "autonomous" :version server-version}
   :instructions (str (when-let [d (:description app)] (str (:name app) ": " d "\n\n"))
                      base-instructions)})
