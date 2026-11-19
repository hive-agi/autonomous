(ns ^:integration autonomous.e2e.live-test
  "Live end to end: real shadow-cljs, real node runtimes of the example
   app, the nREPL channel and the MCP server. Runs only when two runtimes
   of build :e2e are connected (see README, 'Run the live e2e'); otherwise
   every test here is a no-op that says why.

   Proves the swarm story (autonomous.e2e.scenario, shared with `bb e2e`):
   two agents isolated on two instances, then two agents coordinating on
   one instance through a lease, with the ledger attributing every call."
  (:require [autonomous.bridge.channel :as channel]
            [autonomous.bridge.client :as client]
            [autonomous.bridge.ledger :as ledger]
            [autonomous.e2e.agents :as agents]
            [autonomous.e2e.scenario :as scenario]
            [autonomous.mcp.server :as server]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest]]))

(def port-file (or (System/getenv "AUTONOMOUS_E2E_PORT_FILE") ".shadow-cljs/nrepl.port"))

(def target {:target/kind :nrepl :target/id "todo" :nrepl/port-file port-file
             :nrepl/mode :shadow :shadow/build :e2e})

(defn- live-server []
  (when (.exists (io/file port-file))
    (let [srv (server/server (client/context {:target target :channel (channel/make target)
                                              :ledger (ledger/open {:ledger/kind :memory})
                                              :drain :inline}))
          rts (:ok (client/runtimes (:ctx srv)))]
      (when (<= 2 (count rts)) [srv (mapv :runtime/id rts)]))))

(deftest swarm-on-live-runtimes
  (if-let [[srv [r1 r2]] (live-server)]
    (scenario/swarm! (agents/local-agent-on srv) [r1 r2] (str (System/currentTimeMillis)))
    (println "skipped: start shadow watch :e2e and two `node target/e2e/main.js` runtimes (or run `bb e2e`)")))
