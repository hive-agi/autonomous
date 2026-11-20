(ns autonomous.bb-mcp.addon
  "IAddon `autonomous.fleet`: an app fleet (autonomous.bridge.fleet) as a
   hive-addon brick any IAddon host can mount. bb-mcp is the first host
   (autonomous.bb-mcp.transport); nothing here knows it.

   A thin adapter, no kernel: the fleet's MCP server (autonomous.mcp.server)
   lists and calls the tools, this only keeps one session per acting agent
   and shapes answers as MCP CallToolResults. The tool list is live: every
   `tools` call probes the fleet, so an app that comes up is listed and one
   that goes down is not.

   Hooks (the host's fast paths, so a call never has to probe the fleet):
     :mount/owns?       (fn [tool-name] -> bool)  the name is this addon's
     :mount/call        (fn [tool-name params] -> CallToolResult)
     :mount/fingerprint (fn [] -> value) moves when the tool list changes"
  (:require [autonomous.bridge.fleet :as fleet]
            [autonomous.bridge.session :as s]
            [autonomous.domain.answer :as answer]
            [autonomous.mcp.protocol :as p]
            [autonomous.mcp.server :as server]
            [hive-addon.protocol :as addon]))

(def addon-id-value "autonomous.fleet")

(def routing-keys
  "Arguments a host stamps for routing and identity; never an app tool's."
  #{:_actor :_caller_id :_caller_cwd :_caller_credential :agent_id})

(defn args-of
  "The tool arguments in `params`: keyword keys, routing keys removed."
  [params]
  (into {} (comp (map (fn [[k v]] [(keyword (name k)) v]))
                 (remove (fn [[k _]] (contains? routing-keys k))))
        params))

(defn- actor-of
  "Who acts: the host's stamp (:_actor), else the addon's default actor."
  [st params]
  (str (or (get params :_actor) (get params "_actor") (:actor @st))))

(defn- session-of
  "The session `actor` speaks with, opened on first use."
  [st actor]
  (or (get-in @st [:sessions actor])
      (let [ses (s/open-session (str "bb-mcp:" actor) {:actor actor :scopes (:scopes @st)})]
        (swap! st assoc-in [:sessions actor] ses)
        ses)))

(defn owns?
  "True when `tool-name` is a built-in of the fleet or routes to one of its
   apps (fleet names are <app>__<tool>, so a host's own names never do)."
  [st tool-name]
  (let [srv (:srv @st)]
    (boolean (and srv (string? tool-name)
                  (or (contains? (:builtins srv) tool-name)
                      ;; ownership is the prefix alone: no actor's listing is needed
                      (answer/ok? (fleet/route-tool (:fleet srv) nil tool-name)))))))

(defn call
  "CallToolResult of `tool-name` with `params`, as the acting agent, keeping
   the session the call leaves (an attach, a lease)."
  [st tool-name params]
  (let [actor (actor-of st params)
        {:keys [answer session]} (server/call-tool (:srv @st) (session-of st actor)
                                                   tool-name (args-of params))]
    (swap! st assoc-in [:sessions actor] session)
    (p/tool-result answer)))

(defn tool-defs
  "IAddon tool definitions of what the default actor sees of the fleet now."
  [st]
  (mapv (fn [t] (assoc t :handler (fn [params] (call st (:name t) params))))
        (server/list-tools (:srv @st) (session-of st (:actor @st)))))

(defn fingerprint
  "What moves when the fleet's tool list moves."
  [st]
  (server/tools-fingerprint (:srv @st) (session-of st (:actor @st))))

(defn- health-of
  "Health from the last look at every target: :ok all up (or none looked
   at yet), :degraded some down or slow."
  [srv]
  (let [last-probes @(get-in srv [:fleet :last])
        status      (into (sorted-map) (map (fn [[tid pr]] [tid (:status pr)])) last-probes)]
    {:status  (if (every? #{:up} (vals status)) :ok :degraded)
     :details {:apps (get-in srv [:fleet :order]) :status status}}))

(defrecord FleetAddon [state seed]
  addon/IAddon
  (addon-id [_] addon-id-value)
  (addon-type [_] :native)
  (capabilities [_] #{:tools :health-reporting})
  (initialize! [_ runtime-config]
    (locking state
      (if (= :active (:lifecycle @state))
        {:success? true :already-initialized? true}
        (try
          (let [cfg (merge seed (:addon/config seed) runtime-config (:addon/config runtime-config))
                srv (or (:server cfg) ((:build cfg)))]
            (swap! state assoc :lifecycle :active :srv srv :sessions {}
                   :actor (str (or (:actor cfg) "local"))
                   :scopes (or (:scopes cfg) #{:read :write}))
            {:success? true :errors [] :metadata {:apps (get-in srv [:fleet :order])}})
          (catch Exception e
            (swap! state assoc :lifecycle :error :error (ex-message e))
            {:success? false :errors [(str "autonomous.fleet: " (ex-message e))]})))))
  (shutdown! [_]
    (locking state
      (swap! state assoc :lifecycle :stopped :srv nil :sessions {}))
    nil)
  (tools [_]
    (if (= :active (:lifecycle @state)) (tool-defs state) []))
  (schema-extensions [_] {})
  (health [_]
    (let [{:keys [lifecycle srv error]} @state]
      (if (= :active lifecycle)
        (health-of srv)
        {:status :down :details {:lifecycle lifecycle :error error}})))
  (excluded-tools [_] #{})
  (hooks [_]
    {:mount/owns?       (fn [tool-name] (owns? state tool-name))
     :mount/call        (fn [tool-name params] (call state tool-name params))
     :mount/fingerprint (fn [] (when (:srv @state) (fingerprint state)))}))

(defn make-addon
  "Uninitialized addon. initialize! takes :server (a built fleet server) or
   :build (a zero-arg fn building one), :actor and :scopes."
  ([] (make-addon {}))
  ([seed] (->FleetAddon (atom {:lifecycle :new}) (or seed {}))))

(defn addon-ctor
  "hive-addon.mount constructor: config -> uninitialized IAddon."
  [config]
  (make-addon config))
