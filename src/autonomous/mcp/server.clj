(ns autonomous.mcp.server
  "Pipeline stratum of the MCP server: one JSON-RPC message in, one response
   (or none, for a notification) out, for a given session. Transports
   (stdio, http) own sockets and sessions' lifetimes; this owns meaning.

   Built-in tools are compiled by the domain exactly like app tools, and a
   call to one is planned by autonomous.domain.decision/plan-call, so scope
   checks and argument validation are the same code for both."
  (:require [autonomous.bridge.client :as client]
            [autonomous.bridge.session :as s]
            [autonomous.domain.affordance :as affordance]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.decision :as decision]
            [autonomous.domain.manifest :as manifest]
            [autonomous.mcp.builtins :as builtins]
            [autonomous.mcp.protocol :as p]))

(def version "0.1.0")

(defn- compile-builtin [{:keys [id spec run]}]
  (let [a (affordance/->tool id (assoc spec :kind :autonomous/builtin))]
    (when (answer/refused? a)
      (throw (ex-info (str "invalid builtin " id) {:refusal a})))
    (assoc (:ok a) :run run)))

(defn server
  "An MCP server over client context `ctx`. `extra-builtins` are more
   entries shaped like autonomous.mcp.builtins/all."
  ([ctx] (server ctx []))
  ([ctx extra-builtins]
   {:ctx      ctx
    :builtins (into {} (map (juxt :name identity))
                    (map compile-builtin (concat builtins/all extra-builtins)))}))

(defn- builtin-snapshot [srv] {:tools (:builtins srv) :lease nil})

(defn- app-manifest [srv session]
  (let [a (client/manifest (:ctx srv) session)]
    (when (answer/ok? a) (:ok a))))

(defn list-tools
  "MCP tool definitions `session` may call: built-ins its scopes allow, then
   the app's (an app tool cannot shadow a built-in name)."
  [srv session]
  (let [own  (:tools (manifest/project (builtin-snapshot srv) (s/grant session)))
        app  (remove #(contains? (:builtins srv) (:name %)) (:tools (app-manifest srv session)))]
    (mapv p/->mcp-tool (concat own app))))

(defn call-tool
  "Answer and next session of calling `tool-name` with `args`."
  [srv session tool-name args]
  (if (contains? (:builtins srv) tool-name)
    (let [plan (decision/plan-call (builtin-snapshot srv) tool-name args (s/grant session) 0)]
      (case (:plan/action plan)
        :refuse {:answer (:refusal plan) :session session}
        :invoke ((get-in plan [:tool :run]) (:ctx srv) session (:args plan))))
    {:answer (client/call! (:ctx srv) session tool-name args) :session session}))

;; =============================================================================
;; JSON-RPC methods
;; =============================================================================

(defmulti handle-method
  "{:response r-or-nil :session s'} for JSON-RPC `msg` in `session`."
  (fn [_srv _session msg] (:method msg)))

(defn- reply [session id r] {:response (p/result id r) :session session})

(defmethod handle-method "initialize" [srv session {:keys [id params]}]
  (reply session id (p/initialize-result (p/negotiate (:protocolVersion params))
                                         (:app (app-manifest srv session))
                                         version)))

(defmethod handle-method "ping" [_ session {:keys [id]}]
  (reply session id {}))

(defmethod handle-method "tools/list" [srv session {:keys [id]}]
  (reply session id {:tools (list-tools srv session)}))

(defmethod handle-method "tools/call" [srv session {:keys [id params]}]
  (let [{:keys [answer session]} (call-tool srv session (:name params) (:arguments params))]
    (if (= :unknown-tool (get-in answer [:error :type]))
      {:response (p/error id :invalid-params (get-in answer [:error :message]) (get-in answer [:error :data]))
       :session session}
      (reply session id (p/tool-result answer)))))

(defmethod handle-method "resources/list" [srv session {:keys [id]}]
  (reply session id {:resources (mapv p/->mcp-resource (:resources (app-manifest srv session)))}))

(defmethod handle-method "resources/read" [srv session {:keys [id params]}]
  (let [a (client/read-resource (:ctx srv) session (:uri params))]
    (if (answer/ok? a)
      (reply session id (p/resource-contents (:uri params) a))
      {:response (p/error id :invalid-params (get-in a [:error :message]) (:error a)) :session session})))

(defmethod handle-method :default [_ session msg]
  {:response (when-not (p/notification? msg)
               (p/error (:id msg) :method-not-found (str "method not found: " (:method msg))))
   :session session})

(defn handle
  "Response (or nil) and next session for one parsed message. Never throws:
   an internal failure becomes a JSON-RPC internal error."
  [srv session msg]
  (cond
    (not (and (map? msg) (= "2.0" (:jsonrpc msg)) (string? (:method msg))))
    {:response (p/error (:id msg) :invalid-request "not a JSON-RPC 2.0 request") :session session}

    (and (p/notification? msg) (not (contains? (methods handle-method) (:method msg))))
    {:response nil :session session}

    :else
    (try (handle-method srv session msg)
         (catch Exception e
           {:response (when-not (p/notification? msg)
                        (p/error (:id msg) :internal (or (ex-message e) (str e))))
            :session session}))))

(defn tools-fingerprint
  "What changes when the tool list a session sees changes: the app's
   manifest version and its tool names."
  [srv session]
  (let [m (app-manifest srv session)]
    [(:version m) (mapv :name (:tools m))]))
