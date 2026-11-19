(ns ^:integration autonomous.e2e.live-test
  "Live end to end: real shadow-cljs, real node runtimes of the example
   app, the nREPL channel and the MCP server. Runs only when two runtimes
   of build :e2e are connected (see README, 'Run the live e2e'); otherwise
   every test here is a no-op that says why.

   Proves the swarm story: two agents isolated on two instances, then two
   agents coordinating on one instance through a lease, with the ledger
   attributing every call."
  (:require [autonomous.bridge.channel :as channel]
            [autonomous.bridge.client :as client]
            [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.session :as s]
            [autonomous.mcp.server :as server]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

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

(defn- call [srv ses tool args]
  (let [{:keys [response session]} (server/handle srv ses {:jsonrpc "2.0" :id 1 :method "tools/call"
                                                            :params {:name tool :arguments args}})]
    {:value (get-in response [:result :structuredContent])
     :error? (get-in response [:result :isError])
     :session session}))

(defn- agent-on [srv actor runtime]
  (:session (call srv (s/open-session actor {:actor actor :scopes #{:read :write}})
                  "autonomous_attach" {:runtime_id runtime})))

(deftest swarm-on-live-runtimes
  (if-let [[srv [r1 r2]] (live-server)]
    (let [a (agent-on srv "agent-a" r1)
          b (agent-on srv "agent-b" r2)]
      (testing "agents on different instances do not see each other's work"
        (call srv a "todo_add" {:text "only in A"})
        (call srv b "todo_add" {:text "only in B"})
        (is (not-any? #(= "only in B" (:text %)) (get-in (call srv a "todos_visible" {}) [:value :result])))
        (is (not-any? #(= "only in A" (:text %)) (get-in (call srv b "todos_visible" {}) [:value :result]))))
      (testing "agents on one instance coordinate with a lease"
        (let [b1 (agent-on srv "agent-b" r1)]
          (is (= "agent-a" (:actor (:value (call srv a "autonomous_lease" {:ttl_seconds 30})))))
          (is (true? (:error? (call srv b1 "todo_add" {:text "blocked"}))))
          (is (map? (:value (call srv b1 "todos_stats" {}))) "reads stay open")
          (call srv a "autonomous_release" {})
          (is (not (:error? (call srv b1 "todo_add" {:text "after release"}))))))
      (testing "the ledger attributes every call, refusals included"
        (let [rows (get-in (call srv a "autonomous_ledger" {:kind "autonomous/call" :limit 1000}) [:value :result])]
          (is (some #(and (= "agent-b" (:actor %)) (= :leased (keyword (:outcome %)))) rows)))))
    (println "skipped: start shadow watch :e2e and two `node target/e2e/main.js` runtimes")))
