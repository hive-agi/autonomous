(ns autonomous.cli
  "Command line. Boundary stratum: collects configuration, starts the
   bridge, prints. Commands:

     serve  run the MCP bridge for an app
            --dir PATH         app directory (finds shadow-cljs.edn / port files)
            --build ID         shadow build (default: the first in shadow-cljs.edn)
            --port N | --port-file PATH | --host H | --clj
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
            [autonomous.mcp.stdio :as stdio]))

;; HTTP and token minting load on demand: they need hashing and an HTTP
;; server, which ClojureWasm does not have, while stdio serving needs neither.
(defn- http-start! [srv cfg] ((requiring-resolve 'autonomous.mcp.http.start/start!) srv cfg))
(defn- stop-on-exit! [stop!] ((requiring-resolve 'autonomous.mcp.http.start/stop-on-exit!) stop!))
(defn- mint [] ((requiring-resolve 'autonomous.bridge.auth/mint)))
(defn- token-entry [t actor scopes] ((requiring-resolve 'autonomous.bridge.auth/token-entry) t actor scopes))

(defn- err [& xs] (binding [*out* *err*] (apply println xs)))

(defn build-server
  "MCP server for validated `cfg`."
  [cfg]
  (let [ctx (client/context {:target          (:target cfg)
                             :channel         (channel/make (:target cfg))
                             :ledger          (some-> (:ledger cfg) ledger/open)
                             :call-timeout-ms (:call-timeout-ms cfg)})]
    (server/server ctx)))

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
      (let [res (config/promote (config/read-file (or (:config opts) "autonomous.edn"))
                                (config/read-env (System/getenv))
                                opts
                                (when (:dir opts) (config/read-app-dir (:dir opts))))]
        (if-let [e (:error res)]
          (do (err "autonomous serve:" (:message e) (pr-str (:problems e))) 2)
          (let [cfg (:ok res)
                srv (try (build-server cfg)
                         (catch Exception start-failure
                           (err "autonomous serve:" (ex-message start-failure))
                           nil))]
            (cond
              (nil? srv) 2
              (= :stdio (:transport cfg))
              (do (err "autonomous: target" (pr-str (:target cfg)))
                  (stdio/serve! srv {:actor (:stdio/actor cfg) :scopes (:stdio/scopes cfg)})
                  0)
              :else
              (do (err "autonomous: target" (pr-str (:target cfg)))
                  (serve-http! srv cfg)))))))))

(defn- token [args]
  (let [{:keys [opts errors]} (config/parse-args args)]
    (if (or (seq errors) (nil? (:actor opts)))
      (do (err "autonomous token --actor NAME [--scopes read,write]") 2)
      (let [t     (mint)
            entry (token-entry t (:actor opts) (config/split-on (or (:scopes opts) "read,write") ","))]
        (println "token (give it to the agent, store it like a password; shown once):")
        (println t)
        (println "config entry (put it in :tokens of autonomous.edn or AUTONOMOUS_TOKENS):")
        (println (wire/print-edn entry))
        0))))

(defn -main [& [command & args]]
  (let [code (case command
               "serve" (serve args)
               "token" (token args)
               (do (err "usage: autonomous serve|token [options]") 2))]
    (shutdown-agents)
    (System/exit code)))
