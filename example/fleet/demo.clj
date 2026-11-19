(ns fleet.demo
  "Entry of `bb fleet-demo` (see fleet.launch for the flags): start the
   fleet, optionally open the UI headless and run the scripted agent
   workflow through ONE fleet bridge over the three nREPLs."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-mcp.tools.nrepl :as nrepl]
            [clojure.string :as str]
            [fleet.launch :as launch]))

(def bridge-argv
  "The one fleet bridge: the todo UI (shadow build :app), the orders JVM
   service and the inventory bb service. The scopes let the agent move
   stock and money, nothing more (no :audit write-off)."
  ["bb" "serve"
   "--dir" "." "--build" "app" "--target-id" "todo"
   "--dir" "example/orders" "--clj"
   "--port" "7872" "--target-id" "inventory"
   "--actor" "fleet-agent" "--scopes" "read,write,billing"
   "--timeout-ms" "20000"])

(defn- app-runtimes
  "How many runtimes of shadow build :app are connected, nil when shadow's
   nREPL does not answer yet."
  []
  (let [r (nrepl/eval-code {:host "localhost" :port 7888 :timeout-ms 5000
                            :code "(count (shadow.cljs.devtools.api/repl-runtimes :app))"})]
    (when-not (or (:connect-failed? r) (:error? r)) (some-> (:result r) str str/trim parse-long))))

(defn wait-for-ui!
  "Wait until the todo app has a runtime (a browser tab) connected."
  []
  (when-not (#'launch/wait-until 300000 #(and (fs/exists? (fs/path launch/root "example" "public" "js" "main.js"))
                                              (pos? (or (app-runtimes) 0))))
    (throw (ex-info "no todo runtime connected to shadow-cljs build :app (open http://localhost:8280)" {}))))

(defn probe!
  "Run the scripted workflow through the fleet bridge; answers the exit code."
  []
  (let [cmd (into ["bb" "example/probe.clj" "example/fleet/probe.edn"] bridge-argv)]
    (#'launch/log "probe:" (str/join " " cmd))
    (:exit @(p/process {:cmd cmd :dir launch/root :out :inherit :err :inherit}))))

(defn- flag? [args f] (some #{f} args))

(defn -main [& args]
  (let [procs (atom [])
        hook  (Thread. ^Runnable (fn [] (launch/stop-all! procs)))
        probe? (flag? args "--probe")]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (let [code (try
                 (when-not (fs/exists? (fs/path launch/root "node_modules" "react"))
                   (p/shell {:dir launch/root} "npm" "install" "--no-audit" "--no-fund"))
                 (doseq [prog (launch/programs)] (launch/start! procs prog))
                 (when (or probe? (flag? args "--headless"))
                   (#'launch/wait-until 600000 #(fs/exists? (fs/path launch/root "example" "public" "js" "main.js")))
                   (launch/open-headless! procs))
                 (cond
                   (and probe? (flag? args "--keep"))
                   (do (wait-for-ui!) (let [c (probe!)] (#'launch/log "probe exit" c "- kept up (--keep)") @(promise)))
                   probe? (do (wait-for-ui!) (probe!))
                   :else  (do (#'launch/log "fleet up. Bridge it with:\n  " (str/join " " bridge-argv)
                                            "\nthe page: http://localhost:8280 - Ctrl-C stops everything")
                              @(promise)))
                 (catch Exception e
                   (#'launch/log "failed:" (ex-message e))
                   (when (System/getenv "FLEET_DEBUG")
                     (binding [*out* *err*] (println (str e)) (run! println (take 12 (.getStackTrace e)))))
                   2)
                 (finally (launch/stop-all! procs)))]
      (System/exit code))))
