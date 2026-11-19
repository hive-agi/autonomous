(ns autonomous.bb-mcp.main
  "bb-mcp with an app fleet mounted: everything bb-mcp serves (bash,
   clojure_eval, the hive tools) plus the tools of every configured app,
   named <app>__<tool>, and the autonomous_* built-ins. One babashka
   process, no JVM in the path: apps are reached over their own nREPLs
   through autonomous.bridge.fleet.

     bb -m autonomous.bb-mcp.main [fleet options]

   The fleet is configured like `autonomous serve` (doc/fleet.md): fleet
   options on the command line (--dir, --port, --port-file, --clj,
   --target-id, --probe-ms, --config), or :targets in the EDN file named by
   --config, else AUTONOMOUS_FLEET, else autonomous.edn in the client's
   project directory. With no app configured it is plain bb-mcp. Fleet
   naming is always on, so an app tool can never shadow a bb-mcp tool."
  (:require [autonomous.bb-mcp.addon :as fleet-addon]
            [autonomous.bb-mcp.host :as host]
            [autonomous.bb-mcp.mount :as mount]
            [autonomous.cli :as cli]
            [autonomous.config :as config]
            [bb-mcp.core :as bb]
            [bb-mcp.protocol :as proto]
            [bb-mcp.tools.hive :as hive]
            [clojure.java.io :as io]
            [hive-addon.protocol :as addon]))

(defn- log [& xs] (binding [*out* *err*] (apply println "[autonomous.bb-mcp]" xs)))

(defn project-dir
  "The client's project directory, as bb-mcp resolves it."
  [env]
  (or (get env "BB_MCP_CALLER_CWD") (get env "BB_MCP_PROJECT_DIR") (System/getProperty "user.dir")))

(defn config-path
  "The fleet file: --config, else AUTONOMOUS_FLEET, else
   <project>/autonomous.edn."
  [opts env]
  (or (:config opts) (get env "AUTONOMOUS_FLEET")
      (str (io/file (project-dir env) "autonomous.edn"))))

(defn configured?
  "True when `file` or `opts` names at least one app."
  [file opts]
  (boolean (or (seq (:targets opts)) (seq (:targets file)) (:target file)
               (:dir opts) (:port opts) (:port-file opts))))

(defn fleet-config
  "{:ok cfg} (always fleet mode, stdio identity from the environment),
   {:none true} when no app is configured, or {:error ..}. Pure over the
   collected `file`, `env`, `opts`, `app-dir` and `dir-facts`."
  [file env opts app-dir dir-facts]
  (if-not (configured? file opts)
    {:none true}
    (config/promote file (config/read-env env) (assoc opts :fleet? true) app-dir dir-facts)))

(defn collect
  "fleet-config of command-line `args` and environment `env` (effects:
   reads the fleet file and every app directory)."
  [args env]
  (let [{:keys [opts errors]} (config/parse-args args)]
    (if (seq errors)
      {:error {:message (first errors)}}
      (let [file (config/read-file (config-path opts env))]
        (fleet-config file env opts
                      (when (:dir opts) (config/read-app-dir (:dir opts)))
                      (config/read-fleet-dirs file opts))))))

(defn mount-of
  "The initialized Mount for collected config `res`, listing tools as
   `actor` unless the fleet file names its own :stdio/actor: [] when none."
  [res actor]
  (if-let [cfg (:ok res)]
    (let [a (fleet-addon/make-addon {:build #(cli/build-server cfg)
                                     :actor (if (= "local" (:stdio/actor cfg)) actor (:stdio/actor cfg))
                                     :scopes (:stdio/scopes cfg)})
          r (addon/initialize! a {})]
      (if (:success? r)
        (do (log "fleet:" (pr-str (get-in r [:metadata :apps]))) [a])
        (do (log "fleet failed:" (pr-str (:errors r))) [])))
    (do (when-let [e (:error res)] (log "fleet not mounted:" (:message e) (pr-str (:problems e))))
        [])))

(defn -main [& args]
  (let [env   (into {} (System/getenv))
        actor (or (get env "AUTONOMOUS_ACTOR") (mount/actor env))
        m     (mount-of (collect args env) actor)]
    (hive/init!)
    (bb/run-server (host/mounted (proto/stdio-transport) m {:actor (constantly actor)}))
    (doseq [a m] (addon/shutdown! a))
    (System/exit 0)))
