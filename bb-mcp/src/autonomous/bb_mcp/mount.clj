(ns autonomous.bb-mcp.mount
  "IAddons as bb-mcp tools. A Mount is a seq of initialized IAddons; this
   turns them into what bb-mcp serves: `bb-mcp.tool/Tool` records (a tool
   source, the shape of bb-mcp.core's tool-sources entries), and routes a
   call by name to the addon that owns it.

   Host-agnostic on the addon side (only hive-addon.protocol), bb-mcp-shaped
   on the other (only bb-mcp.tool, its public Tool abstraction). Nothing
   here touches bb-mcp's server; autonomous.bb-mcp.host does the plugging."
  (:require [autonomous.mcp.json :as json]
            [bb-mcp.tool :as tool]
            [clojure.string :as str]
            [hive-addon.protocol :as addon]))

(defn- hook [a k]
  (try (get (addon/hooks a) k)
       (catch Exception e
         (if (addon/unimplemented-method? e) nil (throw e)))))

(defn result-text
  "The text of MCP CallToolResult `r`: its text blocks joined."
  [r]
  (str/join "\n\n" (keep :text (:content r))))

(defn ->bb-result
  "bb-mcp's {:result :error?} of CallToolResult `r`."
  [r]
  {:result (result-text r) :error? (boolean (:isError r))})

(defn ->spec
  "bb-mcp tool spec {:name :description :schema} of IAddon tool def `t`,
   as JSON data (string keys inside the schema, no nils)."
  [t]
  {:name        (:name t)
   :description (or (:description t) "")
   :schema      (json/->json-data (or (:inputSchema t) {:type "object" :properties {}}))})

(defn ->tool
  "A bb-mcp NativeTool of IAddon tool def `t`, stamping every call with the
   acting agent `(actor-fn)`."
  [t actor-fn]
  (tool/native-tool (->spec t)
                    (fn [args] (->bb-result ((:handler t) (assoc args :_actor (actor-fn)))))))

(defn tools
  "Every tool the addons of `mount` offer now, as bb-mcp Tools. An addon
   that fails to list is skipped, so one broken brick hides only itself."
  [mount actor-fn]
  (into []
        (mapcat (fn [a]
                  (try (mapv #(->tool % actor-fn) (addon/tools a))
                       (catch Exception e
                         (binding [*out* *err*]
                           (println "[autonomous.bb-mcp]" (addon/addon-id a) "tools failed:" (ex-message e)))
                         []))))
        mount))

(defn owner
  "The addon of `mount` that owns `tool-name` (its :mount/owns? hook), or nil."
  [mount tool-name]
  (some (fn [a] (when-let [owns? (hook a :mount/owns?)]
                  (when (owns? tool-name) a)))
        mount))

(defn call
  "bb-mcp {:result :error?} of calling `tool-name` with `args` on `owner`
   as `actor`, through its :mount/call fast path (no tool listing)."
  [owner tool-name args actor]
  (->bb-result ((hook owner :mount/call) tool-name (assoc args :_actor actor))))

(defn fingerprint
  "One value that moves when any addon's tool list moves."
  [mount]
  (mapv (fn [a] (when-let [f (hook a :mount/fingerprint)] (f))) mount))

(defn actor
  "The agent a bb-mcp head acts as, from its environment `env` (a map):
   the ling id bb-mcp stamps (BB_MCP_AGENT_ID, BB_MCP_CLIENT_ID,
   CLAUDE_SWARM_SLAVE_ID), else \"coordinator\". Each ling gets its own
   session on every app, and the apps' journals say which one acted."
  [env]
  (or (some #(let [v (get env %)] (when-not (str/blank? v) v))
            ["BB_MCP_AGENT_ID" "BB_MCP_CLIENT_ID" "CLAUDE_SWARM_SLAVE_ID"])
      "coordinator"))
