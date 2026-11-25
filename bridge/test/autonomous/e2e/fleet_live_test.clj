(ns ^:integration autonomous.e2e.fleet-live-test
  "Live fleet: two babashka nREPL servers (`bb nrepl-server`, src and test
   on the classpath), each hosting its own autonomous registry with
   different tools, behind one bridge over real nREPL connections. Needs
   `bb` on the PATH; otherwise every test here says why and passes."
  (:require [autonomous.bridge.channel :as channel]
            [autonomous.bridge.client :as client]
            [autonomous.bridge.fleet :as bf]
            [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.session :as s]
            [autonomous.mcp.server :as server]
            [clojure.test :refer [deftest is testing]]
            [nrepl.core :as nrepl]))

(defn- free-port []
  (with-open [ss (java.net.ServerSocket. 0)] (.getLocalPort ss)))

(defn- start-bb!
  "A `bb nrepl-server` on a free port, or nil when bb cannot start."
  []
  (let [port (free-port)]
    (try
      (let [pb (doto (ProcessBuilder. ["bb" "nrepl-server" (str port)])
                 (.redirectErrorStream true)
                 (.redirectOutput (java.io.File. (str "target/bb-nrepl-" port ".log"))))]
        (.mkdirs (java.io.File. "target"))
        {:port port :proc (.start pb)})
      (catch java.io.IOException _no-bb nil))))

(defn- eval-on!
  "Values of evaluating `code` on the nREPL at `port`, retrying while the
   server starts (up to about 15 s)."
  [port code]
  (loop [tries 60]
    (let [r (try (with-open [t (nrepl/connect :port port)]
                   (doall (keep :value (nrepl/message (nrepl/client t 20000) {:op "eval" :code code}))))
                 (catch java.net.ConnectException _not-yet ::retry))]
      (if (and (= ::retry r) (pos? tries))
        (do (Thread/sleep 250) (recur (dec tries)))
        r))))

(defn- target [id port]
  {:target/kind :nrepl :target/id id :nrepl/host "localhost" :nrepl/port port :nrepl/mode :clj})

(defn- fleet-of [targets]
  (let [l (ledger/open {:ledger/kind :memory})]
    (server/fleet-server
     (bf/make (mapv (fn [t] [(:target/id t)
                             (client/context {:target t :channel (channel/make t) :ledger l :drain :inline})])
                    targets)
              {:probe-ms 3000}))))

(defn- call [srv ses tool args]
  (:answer (server/call-tool srv ses tool args)))

(deftest one-bridge-two-bb-services
  (let [a (start-bb!) b (start-bb!)]
    (try
      (if-not (and a b)
        (println "skipped: bb is not on the PATH")
        (let [install (fn [{:keys [port]} kind]
                        (eval-on! port (str "(require 'autonomous.e2e.service)"
                                            "(autonomous.e2e.service/install! " (pr-str kind) ")")))
              _   (install a "orders")
              _   (install b "stock")
              srv (fleet-of [(target "orders" (:port a)) (target "stock" (:port b))])
              ses (s/open-session "live" {:actor "agent-7" :scopes #{:read :write}})
              names (set (map :name (server/list-tools srv ses)))]
          (testing "each service's own tools, under its app prefix"
            (is (contains? names "orders__orders_place"))
            (is (contains? names "stock__stock_level"))
            (is (not (contains? names "orders__stock_level"))))
          (testing "calls route to the service that hosts the tool"
            (is (= {:order "abcx2" :by "agent-7"}
                   (:ok (call srv ses "orders__orders_place" {:sku "abc" :qty 2}))))
            (is (= {:sku "abc" :units 42} (:ok (call srv ses "stock__stock_level" {:sku "abc"})))))
          (testing "the shared ledger names each call's service"
            (is (= #{"orders" "stock"}
                   (set (map (comp :target :source)
                             (:ok (call srv ses "autonomous_ledger" {:kind "autonomous/call"})))))))
          (testing "a service going away takes only its own tools"
            (.destroy ^Process (:proc b))
            (.waitFor ^Process (:proc b))
            (let [names (set (map :name (server/list-tools srv ses)))]
              (is (contains? names "orders__orders_place"))
              (is (not (contains? names "stock__stock_level"))))
            (is (= [:up :down] (map :status (:apps (:ok (call srv ses "autonomous_apps" {}))))))
            (is (= "abcx1" (:order (:ok (call srv ses "orders__orders_place" {:sku "abc" :qty 1}))))))))
      (finally
        (doseq [p [a b] :when p] (.destroy ^Process (:proc p)))))))
