(ns inventory.agentic-test
  "The inventory agent surface through a real MCP server over the
   in-process channel (requests travel as printed EDN, as over nREPL): the
   app-defined :audit scope, domain refusals as refusals, the asynchronous
   cycle count (Pending, then autonomous_poll) and the low-stock resource.
   Runs on babashka (`bb test` in example/inventory)."
  (:require [autonomous.bridge.client :as client]
            [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.local :as local]
            [autonomous.bridge.session :as s]
            [autonomous.core :as a]
            [autonomous.mcp.server :as server]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [inventory.system :as system]))

(use-fixtures :each (fn [t] (a/reset-registry!) (system/start! {:count-ms 50}) (t)))

(defn- srv [timeout-ms]
  (server/server (client/context {:target {:target/kind :local :target/id "inventory"}
                                  :channel (local/channel)
                                  :ledger (ledger/open {:ledger/kind :memory})
                                  :call-timeout-ms timeout-ms
                                  :drain :inline})))

(defn- agent-session [actor & scopes]
  (s/open-session actor {:actor actor :scopes (set scopes)}))

(defn- call [srv ses tool args]
  (:answer (server/call-tool srv ses tool args)))

(deftest reserving-is-bounded-by-what-is-available
  (let [srv (srv 2000) clerk (agent-session "clerk" :read :write)]
    (is (= 33 (:available (:ok (call srv clerk "inventory_reserve" {:sku "kbd-60" :units 2})))))
    (let [e (:error (call srv clerk "inventory_reserve" {:sku "kbd-60" :units 34}))]
      (is (= :stock/insufficient (:type e)))
      (is (= 33 (get-in e [:data :available]))))
    (is (= :stock/unknown-sku (get-in (call srv clerk "inventory_item" {:sku "nope"}) [:error :type])))
    (is (= :invalid-args (get-in (call srv clerk "inventory_receive" {:sku "kbd-60" :units 0}) [:error :type])))))

(deftest writing-off-needs-the-audit-scope
  (let [srv     (srv 2000)
        clerk   (agent-session "clerk" :read :write)
        auditor (agent-session "auditor" :read :write :audit)]
    (testing "a read/write agent neither sees nor may call it"
      (is (not (some #{"inventory_write_off"} (map :name (server/list-tools srv clerk)))))
      (is (= :forbidden (get-in (call srv clerk "inventory_write_off" {:sku "kbd-60" :units 1})
                                [:error :type]))))
    (testing "an auditor writes off unreserved units only"
      (is (= 39 (:on-hand (:ok (call srv auditor "inventory_write_off" {:sku "kbd-60" :units 1})))))
      (is (= :stock/reserved-units
             (get-in (call srv auditor "inventory_write_off" {:sku "kbd-60" :units 35}) [:error :type]))))))

(deftest low-stock-resource-follows-the-stock
  (let [srv (srv 2000) clerk (agent-session "clerk" :read :write)
        low (fn [] (get-in (call srv clerk "inventory_items" {:low-only true}) [:ok]))]
    (is (= ["mouse-v"] (map :sku (low))))
    (call srv clerk "inventory_receive" {:sku "mouse-v" :units 20})
    (is (empty? (low)))
    (is (= "[]" (get-in (server/handle srv clerk {:jsonrpc "2.0" :id 1 :method "resources/read"
                                                  :params {:uri "inventory://low-stock"}})
                        [:response :result :contents 0 :text]))
        "the resource reads the same table")))

(deftest the-cycle-count-answers-pending-then-polls
  (let [srv (srv 0) reader (agent-session "reader" :read)
        id  (:pending (call srv reader "inventory_count" {}))]
    (is (string? id))
    (let [done (loop [n 100]
                 (let [a (call srv reader "autonomous_poll" {:id id})]
                   (if (and (:pending a) (pos? n)) (do (Thread/sleep 20) (recur (dec n))) a)))]
      (is (= {:counted-by "reader" :skus 3 :on-hand 248 :reserved 7} (:ok done))))))
