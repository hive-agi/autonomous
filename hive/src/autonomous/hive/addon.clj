(ns autonomous.hive.addon
  "IAddon `autonomous`: hive agents operate apps built with autonomous.

   One tool, `autonomous`, with a command per operation. Every hive agent
   (coordinator or ling) gets its own session per app, keyed by the agent
   id it calls with, so a swarm spreads over an app's instances
   (command=attach) or shares one under leases, and the app's journal and
   ledger say which ling did what.

   The addon is a thin adapter: each app runs the same MCP server pipeline
   as `autonomous serve` (autonomous.mcp.server), in this JVM."
  (:require [autonomous.bridge.channel :as channel]
            [autonomous.bridge.client :as client]
            [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.session :as s]
            [autonomous.domain.answer :as answer]
            [autonomous.hive.apps :as apps]
            [autonomous.mcp.json :as json]
            [autonomous.mcp.server :as server]
            [hive-addon.protocol :as addon]
            [clojure.walk :as walk]))

(def addon-id-value "autonomous")

;; =============================================================================
;; App contexts
;; =============================================================================

(defn- open-app
  "Running state for one app config: its MCP server and agent sessions."
  [app]
  (let [target (apps/->target app (apps/read-dir-facts app))
        ctx    (client/context {:target  target
                                :channel (channel/make target)
                                :ledger  (ledger/open (or (:app/ledger app) {:ledger/kind :memory}))})]
    {:app app :target target :srv (server/server ctx) :sessions (atom {})}))

(defn- session-of
  "The session `actor` uses on app state `st`, opened on first use."
  [st actor]
  (let [k actor]
    (or (get @(:sessions st) k)
        (let [ses (s/open-session (str addon-id-value ":" (get-in st [:app :app/id]) ":" actor)
                                  (apps/grant-for (:app st) actor))]
          (swap! (:sessions st) assoc k ses)
          ses))))

(defn- run-tool!
  "Answer of `tool` with `args` as `actor`, keeping the session it leaves."
  [st actor tool args]
  (let [{:keys [answer session]} (server/call-tool (:srv st) (session-of st actor) tool args)]
    (swap! (:sessions st) assoc actor session)
    answer))

;; =============================================================================
;; Commands
;; =============================================================================

(def builtin-commands
  "Commands that are the session's built-in tools."
  {"runtimes" "autonomous_runtimes"
   "attach"   "autonomous_attach"
   "whoami"   "autonomous_whoami"
   "journal"  "autonomous_journal"
   "ledger"   "autonomous_ledger"
   "poll"     "autonomous_poll"
   "lease"    "autonomous_lease"
   "release"  "autonomous_release"})

(def command-help
  (array-map
   "help"     "this list"
   "apps"     "the configured apps and where they are reached"
   "tools"    "the tools an app offers you. app"
   "call"     "call an app tool. app, tool, args {..}"
   "read"     "read an app resource. app, uri"
   "runtimes" "running instances of an app (tabs, processes). app"
   "attach"   "pin your session to one instance. app, runtime_id (omit to unpin)"
   "whoami"   "your actor, scopes and pinned instance. app"
   "journal"  "recent events of your instance. app, since, limit"
   "ledger"   "durable record across instances and agents. app, actor, kind, tool, since, until, limit"
   "poll"     "collect a still-running result. app, id"
   "lease"    "exclusive write access to your instance. app, ttl_seconds"
   "release"  "end your lease. app"))

(defn- param [params k] (or (get params k) (get params (keyword k))))

(defn- actor-of [params]
  (str (or (param params "agent_id") (param params "actor") "hive")))

(defn- tool-args
  "Arguments for a built-in tool: every param except the routing ones."
  [params]
  (into {} (comp (map (fn [[k v]] [(keyword (name k)) v]))
                 (remove (fn [[k _]] (#{:command :app :agent_id :actor} k))))
        params))

(defn dispatch
  "Answer of `command` with `params` against the running `apps` map. Tool
   arguments arrive with string keys from the host; they are keywordized
   as the MCP transport does for JSON."
  [apps command params]
  (let [app-id (some-> (param params "app") str)
        st     (get apps app-id)
        actor  (actor-of params)]
    (cond
      (= "help" command) (answer/ok {:commands command-help})
      (= "apps" command) (answer/ok (mapv (fn [[id st]] {:app id :target (:target st)}) (sort-by key apps)))
      (nil? st)          (answer/refusal :autonomous/unknown-app
                                         (str "no app " (pr-str app-id) "; command=apps lists them")
                                         {:known (vec (sort (keys apps)))})
      :else
      (case command
        "tools" (answer/ok (mapv #(select-keys % [:name :description :inputSchema])
                                 (server/list-tools (:srv st) (session-of st actor))))
        "call"  (run-tool! st actor (str (param params "tool"))
                           (walk/keywordize-keys (param params "args")))
        "read"  (client/read-resource (get-in st [:srv :ctx]) (session-of st actor) (str (param params "uri")))
        (if-let [tool (builtin-commands command)]
          (run-tool! st actor tool (tool-args params))
          (answer/refusal :autonomous/unknown-command (str "unknown command " command)
                          {:known (vec (keys command-help))}))))))

(defn- content [a]
  (cond-> {:content [{:type "text" :text (json/write a)}]}
    (answer/refused? a) (assoc :isError true)))

(defn tool [state]
  {:name "autonomous"
   :description (str "Operate running apps built with the autonomous library (re-frame UIs, Clojure "
                     "services): list an app's tools, call them, read its state, and coordinate with "
                     "other agents. Each agent has its own session per app: attach to an instance "
                     "(runtime) to work in parallel, or lease one to change it alone. Pass agent_id "
                     "so the app's journal and ledger attribute your actions. command=help lists commands.")
   :inputSchema {:type "object"
                 :properties {"command"     {:type "string" :enum (vec (keys command-help))}
                              "app"         {:type "string" :description "app id (command=apps lists them)"}
                              "tool"        {:type "string" :description "[call] the app tool's name"}
                              "args"        {:type "object" :description "[call] the tool's arguments"}
                              "uri"         {:type "string" :description "[read] resource uri"}
                              "runtime_id"  {:description "[attach] instance id from command=runtimes"}
                              "since"       {:type "integer" :description "[journal|ledger] after this seq / epoch ms"}
                              "until"       {:type "integer" :description "[ledger] before this epoch ms"}
                              "limit"       {:type "integer"}
                              "actor"       {:type "string" :description "[ledger] filter by actor"}
                              "kind"        {:type "string" :description "[ledger] filter by entry kind"}
                              "id"          {:type "string" :description "[poll] pending result id"}
                              "ttl_seconds" {:type "integer" :description "[lease] lease length"}
                              "agent_id"    {:type "string" :description "your agent id (attribution and session)"}}
                 :required ["command"]}
   :handler (fn [params]
              (let [{:keys [apps]} @state]
                (content (dispatch apps (str (param params "command")) params))))})

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn- configured-apps [cfg]
  (or (:autonomous/apps cfg) (get-in cfg [:addon/config :autonomous/apps]) []))

(defrecord AutonomousAddon [state seed]
  addon/IAddon
  (addon-id [_] addon-id-value)
  (addon-type [_] :native)
  (capabilities [_] #{:tools :health-reporting})
  (initialize! [_ runtime-config]
    (locking state
      (if (= :active (:lifecycle @state))
        {:success? true :already-initialized? true}
        (try
          (let [cfg  (merge seed (:addon/config seed) runtime-config (:addon/config runtime-config))
                apps (into {} (map (fn [app] [(str (:app/id app)) (open-app app)])) (configured-apps cfg))]
            (swap! state assoc :lifecycle :active :apps apps)
            {:success? true :errors [] :metadata {:apps (vec (sort (keys apps)))}})
          (catch Exception e
            (swap! state assoc :lifecycle :error :error (ex-message e))
            {:success? false :errors [(str "autonomous addon: " (ex-message e))]})))))
  (shutdown! [_]
    (locking state
      (doseq [[_ st] (:apps @state)]
        (when-let [ch (get-in st [:srv :ctx :channel])]
          (when (instance? java.io.Closeable ch) (.close ^java.io.Closeable ch))))
      (swap! state assoc :lifecycle :stopped :apps {}))
    nil)
  (tools [this] [(tool state)])
  (schema-extensions [_] {})
  (health [_]
    (let [{:keys [lifecycle apps error]} @state]
      (if (= :active lifecycle)
        {:status :ok :details {:apps (vec (sort (keys apps)))}}
        {:status :down :details {:lifecycle lifecycle :error error}})))
  (excluded-tools [_] #{})
  (hooks [_]
    {:autonomous/dispatch (fn [command params] (dispatch (:apps @state) command params))}))

(defn make-addon
  "Uninitialized addon. No connection is opened until initialize!."
  ([] (make-addon {}))
  ([seed] (->AutonomousAddon (atom {:lifecycle :new :apps {}}) (or seed {}))))

(defn addon-ctor
  "hive-addon.mount constructor: config -> uninitialized IAddon."
  [config]
  (make-addon config))
