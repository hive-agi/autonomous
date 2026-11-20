(ns autonomous.mcp.server
  "Pipeline stratum of the MCP server: one JSON-RPC message in, one response
   (or none, for a notification) out, for a given session. Transports
   (stdio, http) own sockets and sessions' lifetimes; this owns meaning.

   Built-in tools are compiled by the domain exactly like app tools, and a
   call to one is planned by autonomous.domain.decision/plan-call, so scope
   checks and argument validation are the same code for both."
  (:require [autonomous.bridge.fleet :as fleet]
            [autonomous.bridge.session :as s]
            [autonomous.domain.affordance :as affordance]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.decision :as decision]
            [autonomous.domain.manifest :as manifest]
            [autonomous.mcp.builtins :as builtins]
            [autonomous.mcp.protocol :as p]))

(def version "0.1.0")

(defn- compile-builtin
  "L1 Promote: the compiled Tool of builtin entry {:id :spec :run}, its run
   fn attached. Throws ex-info on an invalid builtin (a programming error)."
  [{:keys [id spec run]}]
  (let [a (affordance/->tool id (assoc spec :kind :autonomous/builtin))]
    (when (answer/refused? a)
      (throw (ex-info (str "invalid builtin " id) {:refusal a})))
    (assoc (:ok a) :run run)))

(defn- builtin-table
  "L1 Promote: name -> compiled Tool of the builtins for fleet `mode` plus
   `extra-builtins`."
  [mode extra-builtins]
  (into {} (map (juxt :name identity))
        (map compile-builtin (concat (builtins/for-mode mode) extra-builtins))))

(defn fleet-server
  "L2 Pipeline: an MCP server over fleet `f` (autonomous.bridge.fleet).
   `extra-builtins` are more entries shaped like autonomous.mcp.builtins/all;
   their run fn receives the fleet."
  ([f] (fleet-server f []))
  ([f extra-builtins]
   {:fleet    f
    :ctx      (fleet/member f (first (:order f)))
    :builtins (builtin-table (:mode f) extra-builtins)}))

(defn server
  "L2 Pipeline: an MCP server over client context `ctx`: a fleet of one
   target in single mode, names exactly as the app gives them."
  ([ctx] (server ctx []))
  ([ctx extra-builtins]
   (fleet-server (fleet/from-context ctx) extra-builtins)))

(defn- builtin-snapshot
  "L1 Promote: the registry snapshot the domain plans builtin calls against."
  [srv]
  {:tools (:builtins srv) :lease nil})

(defn- app-surface
  "L3 Boundary: what `session` sees of the app (or the fleet's apps):
   {:app :tools :resources :apps :fingerprint}."
  [srv session]
  (fleet/surface (:fleet srv) session))

(defn list-tools
  "L2 Pipeline: MCP tool definitions `session` may call: built-ins its
   scopes allow, then the app's (an app tool cannot shadow a built-in
   name). In a fleet, the tools of every app that is up, prefixed by app."
  [srv session]
  (let [own (:tools (manifest/project (builtin-snapshot srv) (s/grant session)))
        app (remove #(contains? (:builtins srv) (:name %)) (:tools (app-surface srv session)))]
    (mapv p/->mcp-tool (concat own app))))

(defn- call-builtin
  "L2 Pipeline: {:answer :session} of planning and running builtin
   `tool-name`; the domain refuses scope and argument errors."
  [srv session tool-name args]
  (let [plan (decision/plan-call (builtin-snapshot srv) tool-name args (s/grant session) 0)]
    (case (:plan/action plan)
      :refuse {:answer (:refusal plan) :session session}
      :invoke ((get-in plan [:tool :run]) (:fleet srv) session (:args plan)))))

(defn call-tool
  "L2 Pipeline: Answer and next session of calling `tool-name` with
   `args`: a built-in, or the app tool of the target that owns the name."
  [srv session tool-name args]
  (if (contains? (:builtins srv) tool-name)
    (call-builtin srv session tool-name args)
    {:answer (fleet/call! (:fleet srv) session tool-name args) :session session}))

;; =============================================================================
;; JSON-RPC methods
;; =============================================================================

(defmulti handle-method
  "L2 Pipeline: {:response r-or-nil :session s'} for JSON-RPC `msg` in
   `session`, by method name (an open set)."
  (fn [_srv _session msg] (:method msg)))

(defn- reply
  "L1 Promote: the step result answering request `id` with result `r`."
  [session id r]
  {:response (p/result id r) :session session})

(defn- respond
  "L1 Promote: the step result carrying JSON-RPC message `response` as is:
   an error, or nil for no reply at all."
  [session response]
  {:response response :session session})

(defn- call-response
  "L1 Promote: the step result of a tools/call `answer`: an unknown tool is
   a protocol error, every other Answer a CallToolResult."
  [session id answer]
  (if (= :unknown-tool (get-in answer [:error :type]))
    (respond session (p/refusal-error id answer (get-in answer [:error :data])))
    (reply session id (p/tool-result answer))))

(defmethod handle-method "initialize" [srv session {:keys [id params]}]
  (reply session id (p/initialize-result (p/negotiate (:protocolVersion params))
                                         (:app (app-surface srv session))
                                         version)))

(defmethod handle-method "ping" [_ session {:keys [id]}]
  (reply session id {}))

(defmethod handle-method "tools/list" [srv session {:keys [id]}]
  (reply session id {:tools (list-tools srv session)}))

(defmethod handle-method "tools/call" [srv session {:keys [id params]}]
  (let [{:keys [answer session]} (call-tool srv session (:name params) (:arguments params))]
    (call-response session id answer)))

(defmethod handle-method "resources/list" [srv session {:keys [id]}]
  (reply session id {:resources (mapv p/->mcp-resource (:resources (app-surface srv session)))}))

(defn- read-response
  "L1 Promote: the step result of a resources/read Answer `a` for `uri`:
   its contents when Ok, else an :invalid-params error carrying the refusal."
  [session id uri a]
  (if (answer/ok? a)
    (reply session id (p/resource-contents uri a))
    (respond session (p/refusal-error id a (:error a)))))

(defmethod handle-method "resources/read" [srv session {:keys [id params]}]
  (read-response session id (:uri params) (fleet/read-resource (:fleet srv) session (:uri params))))

(defmethod handle-method :default [_ session msg]
  (respond session (when-not (p/notification? msg)
                         (p/error (:id msg) :method-not-found (str "method not found: " (:method msg))))))

(defn- unhandled-notification?
  "L1 Promote: true for a notification no handle-method method claims; it
   is dropped without a response."
  [msg]
  (and (p/notification? msg) (not (contains? (methods handle-method) (:method msg)))))

(defn- internal-error
  "L1 Promote: the step result of a handler that threw `e`: a JSON-RPC
   internal error, none for a notification."
  [session msg e]
  (respond session (when-not (p/notification? msg)
                         (p/error (:id msg) :internal (or (ex-message e) (str e))))))

(defn handle
  "L2 Pipeline: response (or nil) and next session for one parsed message.
   Never throws: an internal failure becomes a JSON-RPC internal error."
  [srv session msg]
  (cond
    (not (p/request? msg))
    (respond session (p/error (:id msg) :invalid-request "not a JSON-RPC 2.0 request"))

    (unhandled-notification? msg) (respond session nil)

    :else
    (try (handle-method srv session msg)
         (catch Exception e (internal-error session msg e)))))

(defn- single-fingerprint
  "L1 Promote: a single-mode fingerprint from the one target's
   [_ status version names]: version and names while up, else nothing."
  [[[_ status version names]]]
  (if (= :up status) [version names] [nil []]))

(defn tools-fingerprint
  "L2 Pipeline: what changes when the tool list a session sees changes.
   Single mode: the app's manifest version and tool names. Fleet: per
   target its status and, when up, its version and tool names."
  [srv session]
  (let [{:keys [fingerprint]} (app-surface srv session)]
    (if (= :single (get-in srv [:fleet :mode]))
      (single-fingerprint fingerprint)
      fingerprint)))
