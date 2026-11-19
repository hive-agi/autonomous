(ns autonomous.cli
  "Command line. Boundary stratum: collects configuration, starts the
   bridge, prints. Commands:

     serve  run the MCP bridge for an app
            --dir PATH         app directory (finds shadow-cljs.edn / port files)
            --build ID         shadow build (default: the first in shadow-cljs.edn)
            --port N | --port-file PATH | --host H | --clj
            --target-id ID     app id (default: the dir name)
                               repeat --dir / --port / --port-file to serve a
                               fleet: tools are named <app>__<tool>
            --fleet            fleet naming even for one target
            --probe-ms N       how long a fleet waits for an app (default 1500)
            --http [HOST:]PORT serve MCP over HTTP instead of stdio
            --ledger memory|datahike [--datahike-path PATH]
            --actor NAME --scopes read,write   (stdio identity)
            --config PATH      EDN file (default ./autonomous.edn)
     token  mint a bearer token
            --actor NAME --scopes read,write

   Only stdout carries MCP in stdio mode; everything else goes to stderr."
  (:require [autonomous.bridge.client :as client]
            [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.channel :as channel]
            [autonomous.config :as config]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.wire :as wire]
            [autonomous.mcp.server :as server]
            [autonomous.mcp.stdio :as stdio]
            [autonomous.bridge.fleet :as fleet]))

;; HTTP and token minting load on demand: they need hashing and an HTTP
;; server, which ClojureWasm does not have, while stdio serving needs neither.
(defn- http-start! [srv cfg] ((requiring-resolve 'autonomous.mcp.http.start/start!) srv cfg))
(defn- stop-on-exit! [stop!] ((requiring-resolve 'autonomous.mcp.http.start/stop-on-exit!) stop!))
(defn- mint [] ((requiring-resolve 'autonomous.bridge.auth/mint)))
(defn- token-entry [t actor scopes] ((requiring-resolve 'autonomous.bridge.auth/token-entry) t actor scopes))

(defn- err [& xs] (binding [*out* *err*] (apply println xs)))

(defn- member-context [cfg ledger target]
  (client/context {:target          target
                   :channel         (channel/make target)
                   :ledger          ledger
                   :call-timeout-ms (:call-timeout-ms cfg)}))

(defn build-server
  "MCP server for validated `cfg`: one target in single mode, or a fleet of
   :targets sharing one ledger."
  [cfg]
  (let [ledger (some-> (:ledger cfg) ledger/open)]
    (if-let [targets (seq (:targets cfg))]
      (server/fleet-server
       (fleet/make (mapv (fn [t] [(:target/id t) (member-context cfg ledger t)]) targets)
                   {:mode :fleet :probe-ms (:probe-ms cfg)}))
      (server/server (member-context cfg ledger (:target cfg))))))

(defn- serve-http!
  "Start HTTP for `cfg`, announce it on stderr and block until the process
   is told to end. Answers the exit code when it cannot start."
  [srv cfg]
  (let [h (http-start! srv (merge (:http cfg) {:tokens (:tokens cfg)}))]
    (if (answer/refused? h)
      (do (err "autonomous serve:" (get-in h [:error :message])) 3)
      (do (stop-on-exit! (get-in h [:ok :stop!]))
          (err (str "autonomous: MCP on http://" (get-in cfg [:http :bind] "127.0.0.1")
                    ":" (get-in h [:ok :port]) "/mcp"
                    (if (seq (:tokens cfg)) " (bearer tokens required)" " (loopback, no tokens)")))
          @(promise)))))

(defn- serve [args]
  (let [{:keys [opts errors]} (config/parse-args args)]
    (if (seq errors)
      (do (err "autonomous serve:" (first errors)) 2)
      (let [file (config/read-file (or (:config opts) "autonomous.edn"))
            res  (config/promote file
                                 (config/read-env (System/getenv))
                                 opts
                                 (when (:dir opts) (config/read-app-dir (:dir opts)))
                                 (config/read-fleet-dirs file opts))]
        (if-let [e (:error res)]
          (do (err "autonomous serve:" (:message e) (pr-str (:problems e))) 2)
          (let [cfg (:ok res)
                srv (try (build-server cfg)
                         (catch Exception start-failure
                           (err "autonomous serve:" (ex-message start-failure))
                           nil))]
            (when srv
              (if (:targets cfg)
                (doseq [t (:targets cfg)] (err "autonomous: fleet target" (pr-str t)))
                (err "autonomous: target" (pr-str (:target cfg)))))
            (cond
              (nil? srv) 2
              (= :stdio (:transport cfg))
              (do (stdio/serve! srv {:actor (:stdio/actor cfg) :scopes (:stdio/scopes cfg)})
                  0)
              :else
              (serve-http! srv cfg))))))))

(defn- token [args]
  (let [{:keys [opts errors]} (config/parse-args args)]
    (if (or (seq errors) (nil? (:actor opts)))
      (do (err "autonomous token --actor NAME [--scopes read,write]") 2)
      (let [minted (try (let [t (mint)]
                          {:token t
                           :entry (token-entry t (:actor opts)
                                               (config/split-on (or (:scopes opts) "read,write") ","))})
                        (catch Exception no-crypto
                          {:failure (ex-message no-crypto)}))]
        (if-let [why (:failure minted)]
          (do (err "autonomous token: this runtime cannot mint tokens (it needs SecureRandom, base64 and"
                   "SHA-256; ClojureWasm has none yet). Run `bb token` or `clojure -M:token`." why)
              3)
          (do (println "token (give it to the agent, store it like a password; shown once):")
              (println (:token minted))
              (println "config entry (put it in :tokens of autonomous.edn or AUTONOMOUS_TOKENS):")
              (println (wire/print-edn (:entry minted)))
              0))))))

(defn -main [& [command & args]]
  (let [code (case command
               "serve" (serve args)
               "token" (token args)
               (do (err "usage: autonomous serve|token [options]") 2))]
    (shutdown-agents)
    (System/exit code)))
