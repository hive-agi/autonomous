(ns orders.agentic-test
  "The orders agent surface through a real MCP server over the in-process
   channel: requests travel as printed EDN exactly as they do over nREPL,
   so this proves what an agent sees without starting a REPL. Covers the
   app-defined :billing scope, a domain refusal surfacing as a refusal, the
   asynchronous statement (Pending, then autonomous_poll) and the summary
   resource."
  (:require [autonomous.bridge.client :as client]
            [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.local :as local]
            [autonomous.bridge.session :as s]
            [autonomous.core :as a]
            [autonomous.mcp.json :as json]
            [autonomous.mcp.server :as server]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [orders.main :as main]))

(use-fixtures :each (fn [t] (a/reset-registry!) (main/system {:statement-ms 50}) (t)))

(defn- srv
  "An MCP server over this process; `timeout-ms` bounds how long a call
   waits for an asynchronous result before answering Pending."
  [timeout-ms]
  (server/server (client/context {:target {:target/kind :local :target/id "orders"}
                                  :channel (local/channel)
                                  :ledger (ledger/open {:ledger/kind :memory})
                                  :call-timeout-ms timeout-ms
                                  :drain :inline})))

(defn- agent-session [actor & scopes]
  (s/open-session actor {:actor actor :scopes (set scopes)}))

(defn- call [srv ses tool args]
  (:answer (server/call-tool srv ses tool args)))

(defn- ada-order [srv ses]
  (first (:ok (call srv ses "orders_list" {:customer "ada"}))))

(deftest refunds-need-the-billing-scope
  (let [srv   (srv 2000)
        clerk (agent-session "clerk" :read :write)
        payer (agent-session "payer" :read :write :billing)
        order (ada-order srv clerk)]
    (testing "a read/write agent does not see the refund tool"
      (is (not (some #{"orders_refund"} (map :name (server/list-tools srv clerk)))))
      (is (some #{"orders_refund"} (map :name (server/list-tools srv payer)))))
    (testing "and is refused when it calls it anyway"
      (is (some? (:error (call srv clerk "orders_refund"
                               {:order-id (str (:id order)) :cents 100 :reason "x"})))))
    (testing "a billing agent refunds, attributed to it"
      (let [o (:ok (call srv payer "orders_refund"
                         {:order-id (str (:id order)) :cents 1000 :reason "damaged"}))]
        (is (= :partially-refunded (:status o)))
        (is (= "payer" (:by (first (:refunds o)))))))
    (testing "the domain refuses more than is refundable, saying how much is left"
      (let [e (:error (call srv payer "orders_refund"
                            {:order-id (str (:id order)) :cents 999999 :reason "all"}))]
        (is (= :refund/exceeds-paid (:type e)))
        (is (= 10300 (get-in e [:data :refundable])))))))

(deftest placing-an-order-validates-and-answers-it
  (let [srv (srv 2000) clerk (agent-session "clerk" :read :write)]
    (is (= :placed (:status (:ok (call srv clerk "orders_place"
                                       {:customer "linus"
                                        :lines [{:sku "hub" :qty 2 :unit-cents 1500}]})))))
    (is (some? (:error (call srv clerk "orders_place" {:customer "linus" :lines []}))))
    (is (some? (:error (call srv clerk "orders_get"
                             {:order-id "00000000-0000-0000-0000-000000000000"})))
        "an unknown order is a refusal")))

(deftest the-summary-resource-counts-orders
  (let [srv (srv 2000) reader (agent-session "reader" :read)
        rpc (fn [id method params]
              (get-in (server/handle srv reader (cond-> {:jsonrpc "2.0" :id id :method method}
                                                  params (assoc :params params)))
                      [:response :result]))]
    (is (= ["orders://summary"] (map :uri (:resources (rpc 1 "resources/list" nil)))))
    (let [v (json/read-json (get-in (rpc 2 "resources/read" {:uri "orders://summary"}) [:contents 0 :text]))]
      (is (= 2 (:orders v)))
      (is (= 15800 (:paid-cents v))))))

(deftest the-statement-answers-pending-then-polls
  (let [srv    (srv 0)
        reader (agent-session "reader" :read)
        id     (:pending (call srv reader "orders_statement" {:customer "ada"}))]
    (is (string? id) "a call that outlives the timeout answers Pending")
    (let [done (loop [n 100]
                 (let [a (call srv reader "autonomous_poll" {:id id})]
                   (if (and (:pending a) (pos? n)) (do (Thread/sleep 20) (recur (dec n))) a)))]
      (is (= {:customer "ada" :orders 1 :paid-cents 11300 :refunded-cents 0}
             (select-keys (:ok done) [:customer :orders :paid-cents :refunded-cents]))))))
