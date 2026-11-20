(ns autonomous.bb-mcp.mount-test
  "The fleet mounted in an unmodified bb-mcp server: the IAddon over a stub
   fleet, the bb-mcp Tool adapter, and bb-mcp's own run-server over the
   mounted transport (tools/list merged, owned calls answered by the mount,
   bb-mcp's calls untouched, listChanged advertised). Stub channels, no
   network, no JVM: runs on babashka."
  (:require [autonomous.bb-mcp.addon :as fa]
            [autonomous.bb-mcp.host :as host]
            [autonomous.bb-mcp.main :as main]
            [autonomous.bb-mcp.mount :as mount]
            [autonomous.bridge.client :as client]
            [autonomous.bridge.fleet :as bf]
            [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.port :as port]
            [autonomous.bridge.session :as s]
            [autonomous.mcp.server :as server]
            [bb-mcp.core :as bb]
            [bb-mcp.protocol :as proto]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]))

(defrecord StubChannel [respond]
  port/IRuntimeChannel
  (-send [_ request runtime-id] (respond request runtime-id))
  port/IRuntimeDirectory
  (-runtimes [_] {:ok [{:runtime/id 1}]}))

(defn- app [app-name tools]
  (fn [{:keys [op tool actor]} _rid]
    (case op
      :manifest {:ok {:protocol 1 :version 1 :app {:name app-name :description app-name}
                      :tools (mapv (fn [t] {:name t :description t :input-schema {:type "object"}}) tools)
                      :resources []}}
      :call     {:ok {:app app-name :tool tool :by actor}}
      :journal  {:ok {:epoch 1 :seq 0 :entries []}}
      {:ok {}})))

(def ^:private down (fn [_ _] {:error {:type :channel/connect-failed :message "down"}}))

(defn- fleet-server [pairs]
  (let [l (ledger/open {:ledger/kind :memory})]
    (server/fleet-server
     (bf/make (mapv (fn [[tid r]]
                      [tid (client/context {:target {:target/id tid} :channel (->StubChannel r)
                                            :ledger l :drain :inline :call-timeout-ms 100})])
                    pairs)
              {:mode :fleet :probe-ms 150}))))

(defn- started [pairs]
  (let [a (fa/make-addon {:server (fleet-server pairs) :actor "coordinator"})]
    (addon/initialize! a {})
    a))

(def ^:private two-apps [["todo" (app "todo" ["todo_add"])] ["orders" (app "orders" ["refund"])]])

(deftest the-addon-lists-and-calls-the-fleet
  (let [a     (started two-apps)
        names (set (map :name (addon/tools a)))]
    (is (= "autonomous.fleet" (addon/addon-id a)))
    (is (:already-initialized? (addon/initialize! a {})))
    (is (every? names ["todo__todo_add" "orders__refund" "autonomous_apps"]))
    (testing "ownership is by fleet name; bb-mcp's names are never claimed"
      (let [owns? (:mount/owns? (addon/hooks a))]
        (is (owns? "todo__todo_add"))
        (is (owns? "autonomous_apps"))
        (is (not (owns? "bash")))
        (is (not (owns? "clojure_eval")))
        (is (not (owns? "memory")))))
    (testing "a call acts as the stamped agent, routing keys stripped"
      (let [r ((:mount/call (addon/hooks a)) "orders__refund" {:_actor "ling-7" :_caller_id "x" :amount 3})]
        (is (not (:isError r)))
        (is (= {:app "orders" :tool "refund" :by "ling-7"} (:structuredContent r)))))
    (is (= :ok (:status (addon/health a))))
    (addon/shutdown! a)
    (is (= [] (addon/tools a)))
    (is (= :down (:status (addon/health a))))))

(deftest a-down-app-hides-only-itself
  (let [a     (started [["todo" (app "todo" ["todo_add"])] ["orders" down]])
        names (set (map :name (addon/tools a)))]
    (is (contains? names "todo__todo_add"))
    (is (not-any? #(re-find #"^orders__" %) names))
    (is (= :degraded (:status (addon/health a))))))

(def ^:private refused-actor "intruder")

(defn- refusing
  "App `app-name` with `tools` that refuses `refused-actor` its listing."
  [app-name tools]
  (let [serve (app app-name tools)]
    (fn [req rid]
      (if (and (= :manifest (:op req)) (= refused-actor (:actor req)))
        {:error {:type :channel/remote-refused :message "unknown agent"}}
        (serve req rid)))))

(deftest health-is-per-target-not-per-actor
  (let [srv (fleet-server [["todo" (refusing "todo" ["todo_add"])] ["orders" (app "orders" ["refund"])]])
        a   (fa/make-addon {:server srv :actor "coordinator"})]
    (addon/initialize! a {})
    (bf/probe (:fleet srv) (s/open-session refused-actor {:actor refused-actor :scopes #{:read}}))
    (addon/tools a)
    (is (= {:status :ok :apps-status {"orders" :up "todo" :up}}
           (let [h (addon/health a)] {:status (:status h) :apps-status (get-in h [:details :status])}))
        "another agent reaching the target later clears one agent's refusal, keyed by target id")))

(deftest addon-tools-become-bb-mcp-tools
  (let [m     [(started two-apps)]
        tools (mount/tools m (constantly "ling-1"))
        by    (into {} (map (juxt #(:name (bb-mcp.tool/tool-spec %)) identity)) tools)]
    (is (contains? by "todo__todo_add"))
    (is (map? (:schema (bb-mcp.tool/tool-spec (by "todo__todo_add")))))
    (is (= false (:error? (bb-mcp.tool/invoke (by "todo__todo_add") {}))))
    (is (re-find #"ling-1" (:result (bb-mcp.tool/invoke (by "todo__todo_add") {}))))
    (is (nil? (mount/owner m "bash")))
    (is (= "coordinator" (mount/actor {})))
    (is (= "ling-2" (mount/actor {"CLAUDE_SWARM_SLAVE_ID" "ling-2"})))))

(defn- queue-transport
  "A bb-mcp Transport reading `msgs` in order, collecting what is written."
  [msgs out]
  (let [q (atom (seq msgs))]
    (reify proto/Transport
      (read-msg [_] (let [[m & more] @q] (reset! q more) m))
      (write-msg [_ msg] (swap! out conj msg)))))

(defn- serve [msgs]
  (let [out (atom [])
        m   [(started two-apps)]]
    (bb/run-server (host/mounted (queue-transport msgs out) m {:actor (constantly "ling-3") :watch-ms 0})
                   {:workers 0 :select-receptor (fn [_] (bb-mcp.sense.receptor/null-receptor))})
    (loop [n 0] (when (and (< n 100) (not-any? #(= 3 (:id %)) @out)) (Thread/sleep 20) (recur (inc n))))
    (into {} (keep (fn [r] (when (:id r) [(:id r) r]))) @out)))

(deftest bb-mcp-serves-the-fleet-beside-its-own-tools
  (let [rs (serve [{:jsonrpc "2.0" :id 1 :method "initialize" :params {}}
                   {:jsonrpc "2.0" :id 2 :method "tools/list"}
                   {:jsonrpc "2.0" :id 3 :method "tools/call"
                    :params {:name "todo__todo_add" :arguments {}}}])
        names (set (map :name (get-in rs [2 :result :tools])))]
    (is (true? (get-in rs [1 :result :capabilities :tools :listChanged])))
    (testing "bb-mcp's own tools and the fleet's, in one list"
      (is (every? names ["bash" "clojure_eval" "todo__todo_add" "orders__refund" "autonomous_apps"])))
    (testing "an owned call is answered by the mount, as the head's agent"
      (is (false? (get-in rs [3 :result :isError])))
      (is (re-find #"ling-3" (get-in rs [3 :result :content 0 :text]))))))

(deftest a-host-tool-is-never-shadowed
  (is (= [{:name "bash"} {:name "x__y"}]
         (get-in (host/with-tools {:result {:tools [{:name "bash"}]}} [{:name "bash" :d 1} {:name "x__y"}])
                 [:result :tools]))))

(deftest fleet-configuration
  (is (:none (main/fleet-config {} {} {} nil {})))
  (let [{:keys [ok]} (main/fleet-config {:targets [{:port "7001" :target-id "orders"}]} {} {} nil {})]
    (is (= ["orders"] (map :target/id (:targets ok))))
    (is (true? (:fleet? ok))))
  (testing "one app is still served with fleet names"
    (is (:fleet? (:ok (main/fleet-config {} {} {:port "7002" :target-id "stock"} nil {})))))
  (is (= "/p/autonomous.edn" (main/config-path {} {"BB_MCP_CALLER_CWD" "/p"})))
  (is (= "/f.edn" (main/config-path {} {"AUTONOMOUS_FLEET" "/f.edn" "BB_MCP_CALLER_CWD" "/p"}))))
