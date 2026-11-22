(ns autonomous.hive.addon
  "IAddon `autonomous`: hive agents operate apps built with autonomous.

   One tool, `autonomous`, with a command per operation. Every configured
   app is a member of ONE fleet (autonomous.bridge.fleet) served by one MCP
   server (autonomous.mcp.server/fleet-server), the same pipeline as
   `autonomous serve` with several targets. Every hive agent (coordinator or
   ling) has one session across the whole fleet, keyed by the agent_id it
   calls with, so it pins an instance per app (command=attach), leases one,
   and the fleet's one ledger says which ling did what on which app.

   Routing is the fleet's: `app` + a tool or uri as the app names it is
   turned into the fleet name (<app>__<tool>, autonomous://<app>/<uri>), and
   a fleet name passed as is routes by itself. A down or slow app never
   delays the others (command=apps shows each app's status).

   The addon is a thin adapter. It composes the core's public API and never
   changes it: per-app grants are kept by narrowing each request to the
   agent's grant on the app it goes to (ScopedChannel)."
  (:require [autonomous.bridge.channel :as channel]
            [autonomous.bridge.client :as client]
            [autonomous.bridge.fleet :as fleet]
            [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.port :as port]
            [autonomous.bridge.session :as s]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.fleet :as domain]
            [autonomous.hive.apps :as apps]
            [autonomous.mcp.json :as json]
            [autonomous.mcp.server :as server]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [hive-addon.protocol :as addon]))

(def addon-id-value "autonomous")

;; =============================================================================
;; Fleet
;; =============================================================================

(defrecord ScopedChannel [app inner]
  port/IRuntimeChannel
  (-send [_ request runtime-id]
    (port/-send inner (apps/scope-request app request) runtime-id))

  port/IRuntimeDirectory
  (-runtimes [_] (port/-runtimes inner))

  java.io.Closeable
  (close [_]
    (when (instance? java.io.Closeable inner) (.close ^java.io.Closeable inner))))

(defn- shared-ledger-config
  "Promote: the fleet's one ledger: :autonomous/ledger, else the first app's
   :app/ledger (the per-app key of earlier versions), else memory."
  [cfg app-cfgs]
  (or (:autonomous/ledger cfg)
      (some :app/ledger app-cfgs)
      {:ledger/kind :memory}))

(defn- app-id
  "Promote: the fleet name of an app declaration."
  [app]
  (str (:app/id app)))

(defn- checked-app-ids
  "Promote: ordered app ids, or the public open-fleet configuration exception."
  [app-cfgs]
  (let [ids (mapv app-id app-cfgs)]
    (if-let [problems (domain/check-targets ids)]
      (throw (ex-info (str/join "; " problems) {:autonomous/error :fleet/invalid :problems problems}))
      ids)))

(defn- open-member
  "Boundary: open one app's scoped channel and client context."
  [l app]
  (let [target (apps/->target app (apps/read-dir-facts app))]
    [(:target/id target)
     (client/context {:target target
                      :channel (->ScopedChannel app (channel/make target))
                      :ledger l
                      :call-timeout-ms (:app/call-timeout-ms app)})]))

(defn- fleet-state
  "Boundary: assemble a fleet's server and empty session store."
  [f ids app-cfgs]
  {:configs  (into {} (map (juxt app-id identity)) app-cfgs)
   :order    ids
   :fleet    f
   :srv      (server/fleet-server f)
   :sessions (atom {})})

(defn open-fleet
  "Boundary: start one fleet and its MCP server. Throws ex-info when the
   app ids cannot form a fleet."
  [cfg app-cfgs]
  (let [ids (checked-app-ids app-cfgs)
        l   (ledger/open (shared-ledger-config cfg app-cfgs))
        f   (fleet/make (mapv #(open-member l %) app-cfgs)
                        {:mode :fleet :probe-ms (:autonomous/probe-ms cfg)})]
    (fleet-state f ids app-cfgs)))

(defn close-fleet!
  "Boundary: close every member's channel."
  [st]
  (doseq [[_ ctx] (get-in st [:fleet :members])]
    (when (instance? java.io.Closeable (:channel ctx))
      (.close ^java.io.Closeable (:channel ctx)))))

(defn- session-of
  "Boundary: the one session `actor` uses across the fleet, opened on first use."
  [st actor]
  (or (get @(:sessions st) actor)
      (let [ses (s/open-session (str addon-id-value ":hive:" actor)
                                (apps/fleet-grant (vals (:configs st)) actor))]
        (get (swap! (:sessions st) #(if (contains? % actor) % (assoc % actor ses))) actor))))

(defn- run-tool!
  "Boundary: answer of server tool `tool` with `args` as `actor`, keeping the session
   it leaves (an attach pins it)."
  [st actor tool args]
  (let [{:keys [answer session]} (server/call-tool (:srv st) (session-of st actor) tool args)]
    (swap! (:sessions st) assoc actor session)
    answer))

;; =============================================================================
;; Routing (pure)
;; =============================================================================

(defn exposed-tool
  "Promote: fleet name of tool `tool` of app `app`; `tool` as is when no app is named
   (it is then a fleet name, <app>__<tool>, or a built-in)."
  [app tool]
  (if app (domain/exposed-name (domain/prefix app) tool) tool))

(defn exposed-uri
  "Promote: fleet uri of resource `uri` of app `app`; `uri` as is when no app is
   named or it already is a fleet uri."
  [app uri]
  (if (and app (not (str/starts-with? (str uri) domain/resource-scheme)))
    (domain/exposed-uri (domain/prefix app) uri)
    uri))

(defn pending-id
  "Promote: fleet pending id: `id` qualified by `app` unless it already is."
  [app id]
  (if (and app (not (str/starts-with? (str id) (str app ":")))) (str app ":" id) id))

(defn ledger-query
  "Promote: the autonomous_ledger arguments of addon `params` (keyword keys): `app`
   reads one app, `by_actor` filters by who acted. The caller's own
   identity (agent_id) is never a filter."
  [{:keys [app by_actor kind tool since until limit]}]
  (into {} (remove (comp nil? val))
        {:target app :actor by_actor :kind kind :tool tool :since since :until until :limit limit}))

;; =============================================================================
;; Commands
;; =============================================================================

(def command-help
  (array-map
   "help"     "this list"
   "apps"     "every app of the fleet: status (up, down, slow), tool prefix, tool count, version, target"
   "tools"    "the tools you may call, named <app>__<tool>. app narrows to one app"
   "call"     "call an app tool. app + tool (as the app names it), or tool = <app>__<tool>; args {..}"
   "read"     "read an app resource. app + uri, or uri = autonomous://<app>/<uri>"
   "runtimes" "running instances of an app (tabs, processes). app"
   "attach"   "pin your session to one instance of an app. app, runtime_id (omit to unpin)"
   "whoami"   "your actor, scopes and the instance pinned on each app"
   "journal"  "recent events of your instance of an app. app, since, limit"
   "ledger"   "durable record across apps, instances and agents. app, by_actor, kind, tool, since, until, limit"
   "poll"     "collect a still-running result. id (as the call answered it, <app>:<id>)"
   "lease"    "exclusive write access to your instance of an app. app, ttl_seconds"
   "release"  "end your lease on an app. app"))

(defn- param [params k] (or (get params k) (get params (keyword k))))

(defn actor-of
  "Promote: the caller: agent_id, else \"hive\"."
  [params]
  (str (or (param params "agent_id") "hive")))

(defn- keywordized [params]
  (into {} (map (fn [[k v]] [(keyword (name k)) v])) params))

(defn- unknown-app [st app]
  (answer/refusal :autonomous/unknown-app (str "no app " (pr-str app) "; command=apps lists them")
                  {:known (:order st)}))

(defn- with-app-targets
  "Promote: enrich fleet status rows with each member's target."
  [members rows]
  (mapv #(assoc % :target (get-in members [(:app %) :target])) rows))

(defn- apps-answer
  "Boundary: fetch fleet status with the targets it describes."
  [st actor]
  (answer/let-answer [{:keys [apps]} (run-tool! st actor "autonomous_apps" {})]
    (answer/ok (with-app-targets (get-in st [:fleet :members]) apps))))

(defn- listed-tools
  "Promote: display fields of the tools, optionally filtered to one app."
  [app tools]
  (let [pfx (when app (str (domain/prefix app) domain/separator))]
    (into [] (comp (filter #(or (nil? pfx) (str/starts-with? (:name %) pfx)))
                   (map #(select-keys % [:name :description :inputSchema])))
          tools)))

(defn- tools-answer
  "Boundary: list the tools this actor is offered."
  [st actor app]
  (answer/ok (listed-tools app (server/list-tools (:srv st) (session-of st actor)))))

(defn- command-context
  "Promote: normalize host arguments without losing the caller's identity."
  [params]
  (let [p (keywordized params)]
    {:params p :app (some-> (:app p) str) :actor (actor-of params)}))

(defn- checked-fleet
  "Promote: an initialized fleet serving the requested app, or a refusal."
  [st app]
  (cond
    (nil? st) (answer/refusal :autonomous/not-running "the autonomous addon is not initialized")
    (and app (not (contains? (:configs st) app))) (unknown-app st app)
    :else (answer/ok st)))

(defn- run-with-args!
  "Boundary: invoke a fleet tool with only the arguments supplied."
  [st actor tool args]
  (run-tool! st actor tool (into {} (remove (comp nil? val)) args)))

(defmulti ^:private run-command
  "Boundary: run a command against a validated fleet and normalized context."
  (fn [command _st _ctx] command))

(defmethod run-command "apps" [_ st {:keys [actor]}]
  (apps-answer st actor))

(defmethod run-command "tools" [_ st {:keys [actor app]}]
  (tools-answer st actor app))

(defmethod run-command "call" [_ st {:keys [actor app params]}]
  (run-with-args! st actor (exposed-tool app (str (:tool params)))
                (walk/keywordize-keys (or (:args params) {}))))

(defmethod run-command "read" [_ st {:keys [actor app params]}]
  (fleet/read-resource (:fleet st) (session-of st actor) (exposed-uri app (str (:uri params)))))

(defmethod run-command "runtimes" [_ st {:keys [actor app]}]
  (run-with-args! st actor "autonomous_runtimes" {:target app}))

(defmethod run-command "attach" [_ st {:keys [actor app params]}]
  (run-with-args! st actor "autonomous_attach" {:target app :runtime_id (:runtime_id params)}))

(defmethod run-command "whoami" [_ st {:keys [actor]}]
  (run-with-args! st actor "autonomous_whoami" {}))

(defmethod run-command "journal" [_ st {:keys [actor app params]}]
  (run-with-args! st actor "autonomous_journal" {:target app :since (:since params) :limit (:limit params)}))

(defmethod run-command "ledger" [_ st {:keys [actor params]}]
  (run-with-args! st actor "autonomous_ledger" (ledger-query params)))

(defmethod run-command "poll" [_ st {:keys [actor app params]}]
  (run-with-args! st actor "autonomous_poll" {:id (pending-id app (str (:id params)))}))

(defmethod run-command "lease" [_ st {:keys [actor app params]}]
  (run-with-args! st actor "autonomous_lease" {:target app :ttl_seconds (:ttl_seconds params)}))

(defmethod run-command "release" [_ st {:keys [actor app]}]
  (run-with-args! st actor "autonomous_release" {:target app}))

(defmethod run-command :default [command _ _]
  (answer/refusal :autonomous/unknown-command (str "unknown command " command)
                  {:known (vec (keys command-help))}))

(defn dispatch
  "Pipeline: normalize host arguments, validate the fleet, run the command.
   Help needs no initialized fleet. Returns an Answer."
  [st command params]
  (let [{:keys [app] :as ctx} (command-context params)]
    (if (= "help" command)
      (answer/ok {:commands command-help})
      (answer/let-answer [st (checked-fleet st app)]
        (run-command command st ctx)))))

(defn- content [a]
  (cond-> {:content [{:type "text" :text (json/write a)}]}
    (answer/refused? a) (assoc :isError true)))

(def ^:private tool-template
  "Promote: the public tool description and argument schema, apart from
   the command enum."
  {:name "autonomous"
   :description (str "Operate running apps built with the autonomous library (re-frame UIs, Clojure "
                     "services), all behind one fleet: list the apps and their status, list their tools, "
                     "call them, read their state, and coordinate with other agents. You have one session "
                     "across the fleet: attach to an instance of an app to work in parallel, or lease one "
                     "to change it alone. Pass agent_id so journals and the ledger attribute your actions; "
                     "ledger by_actor reads what another agent did. command=help lists commands.")
   :inputSchema {:type "object"
                 :properties {"command"     {:type "string"}
                              "app"         {:type "string" :description "app id (command=apps lists them)"}
                              "tool"        {:type "string" :description "[call] the tool as the app names it (with app), or <app>__<tool>"}
                              "args"        {:type "object" :description "[call] the tool's arguments"}
                              "uri"         {:type "string" :description "[read] resource uri (with app), or autonomous://<app>/<uri>"}
                              "runtime_id"  {:description "[attach] instance id from command=runtimes"}
                              "since"       {:type "integer" :description "[journal|ledger] after this seq / epoch ms"}
                              "until"       {:type "integer" :description "[ledger] before this epoch ms"}
                              "limit"       {:type "integer"}
                              "by_actor"    {:type "string" :description "[ledger] only what this actor (agent id) did"}
                              "kind"        {:type "string" :description "[ledger] filter by entry kind, e.g. autonomous/call"}
                              "id"          {:type "string" :description "[poll] pending result id"}
                              "ttl_seconds" {:type "integer" :description "[lease] lease length"}
                              "agent_id"    {:type "string" :description "you: your agent id (session and attribution)"}}
                 :required ["command"]}})

(defn tool
  "Boundary: attach the fleet's command handler to its advertised schema."
  [state]
  (-> tool-template
      (assoc-in [:inputSchema :properties "command" :enum] (vec (keys command-help)))
      (assoc :handler (fn [params]
                        (content (dispatch (:fleet-state @state) (str (param params "command")) params))))))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn- configured-apps [cfg]
  (or (:autonomous/apps cfg) (get-in cfg [:addon/config :autonomous/apps]) []))

(defn- effective-config
  "Promote: runtime config overlays the seed; nested addon config wins within each."
  [seed runtime-config]
  (merge seed (:addon/config seed) runtime-config (:addon/config runtime-config)))

(defn- activate!
  "Boundary: open the configured fleet and publish its lifecycle state."
  [state seed runtime-config]
  (try
    (let [cfg (effective-config seed runtime-config)
          st  (open-fleet cfg (configured-apps cfg))]
      (swap! state assoc :lifecycle :active :fleet-state st)
      {:success? true :errors [] :metadata {:apps (:order st)}})
    (catch Exception e
      (swap! state assoc :lifecycle :error :error (ex-message e))
      {:success? false :errors [(str "autonomous addon: " (ex-message e))]})))

(defrecord AutonomousAddon [state seed]
  addon/IAddon
  (addon-id [_] addon-id-value)
  (addon-type [_] :native)
  (capabilities [_] #{:tools :health-reporting})
  (initialize! [_ runtime-config]
    (locking state
      (if (= :active (:lifecycle @state))
        {:success? true :already-initialized? true}
        (activate! state seed runtime-config))))
  (shutdown! [_]
    (locking state
      (some-> (:fleet-state @state) close-fleet!)
      (swap! state assoc :lifecycle :stopped :fleet-state nil))
    nil)
  (tools [_] [(tool state)])
  (schema-extensions [_] {})
  (health [_]
    (let [{:keys [lifecycle fleet-state error]} @state]
      (if (= :active lifecycle)
        {:status :ok :details {:apps (:order fleet-state)}}
        {:status :down :details {:lifecycle lifecycle :error error}})))
  (excluded-tools [_] #{})
  (hooks [_]
    {:autonomous/dispatch (fn [command params] (dispatch (:fleet-state @state) command params))}))

(defn make-addon
  "Pipeline: an uninitialized addon. No connection is opened until initialize!, and an
   nREPL connection only on an app's first use."
  ([] (make-addon {}))
  ([seed] (->AutonomousAddon (atom {:lifecycle :new :fleet-state nil}) (or seed {}))))

(defn addon-ctor
  "Pipeline: hive-addon.mount constructor: config -> uninitialized IAddon."
  [config]
  (make-addon config))
