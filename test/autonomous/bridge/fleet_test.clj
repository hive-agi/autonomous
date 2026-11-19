(ns autonomous.bridge.fleet-test
  "A fleet served by one MCP server against stub channels: routing of
   tools, resources, pending results and built-ins to the owning target,
   isolation of a down or slow target, list_changed when one comes back,
   and ledger entries that name their target. Ports are injected; nothing
   is redefined. Portable: runs on the JVM and on babashka."
  (:require [autonomous.bridge.client :as client]
            [autonomous.bridge.fleet :as bf]
            [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.port :as port]
            [autonomous.bridge.session :as s]
            [autonomous.mcp.server :as server]
            [clojure.test :refer [deftest is testing]]))

(defrecord StubChannel [respond sent]
  port/IRuntimeChannel
  (-send [_ request runtime-id]
    (swap! sent conj [request runtime-id])
    (respond request runtime-id))
  port/IRuntimeDirectory
  (-runtimes [_] {:ok [{:runtime/id 1} {:runtime/id 2}]}))

(defn- manifest [app & tools]
  {:ok {:protocol 1 :version 1 :app {:name app :description (str app " app")}
        :tools (mapv (fn [t] {:name t :description t :input-schema {:type "object"}}) tools)
        :resources [{:uri "app://db" :name "db"}]}})

(defn- app-responder
  "A runtime that answers like autonomous for app `app` with `tools`,
   journaling every call it gets."
  [app tools]
  (let [journal (atom [])
        polls   (atom 0)]
    (fn [{:keys [op tool actor since uri id]} runtime-id]
      (case op
        :manifest (apply manifest app tools)
        :call     (do (swap! journal conj {:seq (inc (count @journal)) :at (+ 100 (count @journal))
                                           :kind :autonomous/call :actor actor :tool tool :outcome :ok})
                      (if (= tool "slow_job") {:pending "p7"} {:ok {:app app :tool tool :runtime runtime-id}}))
        :poll     (if (< (swap! polls inc) 2) {:pending id} {:ok {:app app :polled id}})
        :read     {:ok {:mime "application/json" :value {:app app :uri uri}}}
        :journal  {:ok {:epoch 1 :seq (count @journal)
                        :entries (filterv #(> (:seq %) (or since 0)) @journal)}}
        {:ok {:app app}}))))

(def ^:private down
  (fn [_ _] {:error {:type :channel/connect-failed :message "cannot reach nREPL"}}))

(defn- member [tid respond shared-ledger]
  [tid (client/context {:target {:target/id tid}
                        :channel (->StubChannel respond (atom []))
                        :ledger shared-ledger
                        :drain :inline
                        :call-timeout-ms 100
                        :clock (let [t (atom 0)] #(swap! t + 60))
                        :sleep! (fn [_] nil)})])

(defn- fleet-srv
  "A fleet server over [tid respond] pairs sharing one memory ledger."
  [pairs]
  (let [l (ledger/open {:ledger/kind :memory})]
    (server/fleet-server (bf/make (mapv (fn [[tid r]] (member tid r l)) pairs) {:probe-ms 150}))))

(def ^:private rw (s/open-session "s1" {:actor "agent-1" :scopes #{:read :write}}))

(defn- call [srv ses tool args]
  (server/call-tool srv ses tool args))

(deftest calls-route-to-the-owning-target
  (let [srv (fleet-srv [["todo" (app-responder "todo" ["todo_add"])]
                        ["orders_api" (app-responder "orders" ["refund" "slow_job"])]])
        names (set (map :name (server/list-tools srv rw)))]
    (is (every? names ["todo__todo_add" "orders-api__refund" "autonomous_apps"]))
    (is (= {:ok {:app "orders" :tool "refund" :runtime nil}}
           (:answer (call srv rw "orders-api__refund" {}))))
    (is (= {:ok {:app "todo" :tool "todo_add" :runtime nil}}
           (:answer (call srv rw "todo__todo_add" {}))))
    (testing "an unprefixed or unknown name is refused, naming the apps"
      (is (= :unknown-tool (get-in (call srv rw "todo_add" {}) [:answer :error :type])))
      (is (= ["orders-api" "todo"] (get-in (call srv rw "shop__buy" {}) [:answer :error :data :apps]))))
    (testing "a pending id carries its target and polls there"
      (is (= {:pending "orders_api:p7"} (:answer (call srv rw "orders-api__slow_job" {}))))
      (is (= {:ok {:app "orders" :polled "p7"}}
             (:answer (call srv rw "autonomous_poll" {:id "orders_api:p7"})))))
    (testing "resources are listed and read under their app"
      (is (= {:app "todo" :uri "app://db"}
             (get-in (bf/read-resource (:fleet srv) rw "autonomous://todo/app://db") [:ok :value]))))))

(deftest built-ins-take-a-target
  (let [srv (fleet-srv [["a" (app-responder "a" ["x"])] ["b" (app-responder "b" ["y"])]])]
    (is (= :target/required (get-in (call srv rw "autonomous_runtimes" {}) [:answer :error :type])))
    (is (= :target/unknown (get-in (call srv rw "autonomous_runtimes" {:target "zz"}) [:answer :error :type])))
    (let [{ses :session} (call srv rw "autonomous_attach" {:target "b" :runtime_id 2})]
      (is (= {"b" 2} (:session/runtimes ses)) "attaching on b leaves a alone")
      (is (= {:app "b" :tool "y" :runtime 2} (:ok (:answer (call srv ses "b__y" {})))))
      (is (= {:app "a" :tool "x" :runtime nil} (:ok (:answer (call srv ses "a__x" {})))))
      (is (= 2 (get-in (call srv ses "autonomous_runtimes" {:target "b"}) [:answer :ok :attached]))))))

(deftest the-ledger-names-each-entry's-target
  (let [srv (fleet-srv [["a" (app-responder "a" ["x"])] ["b" (app-responder "b" ["y"])]])]
    (call srv rw "a__x" {})
    (call srv rw "b__y" {})
    (let [rows (get-in (call srv rw "autonomous_ledger" {:kind "autonomous/call"}) [:answer :ok])]
      (is (= #{["a" "x"] ["b" "y"]} (set (map (juxt (comp :target :source) :tool) rows)))))
    (is (= ["b"] (map (comp :target :source)
                      (get-in (call srv rw "autonomous_ledger" {:target "b"}) [:answer :ok]))))))

(deftest a-down-or-slow-target-never-breaks-the-others
  (let [state   (atom :down)
        api     (app-responder "api" ["refund"])
        flaky   (fn [req rt] (if (= :down @state) (down req rt) (api req rt)))
        sleepy  (fn [req rt] (when (= :manifest (:op req)) (Thread/sleep 1500)) {:ok nil})
        srv     (fleet-srv [["todo" (app-responder "todo" ["todo_add"])] ["api" flaky] ["ui" sleepy]])
        t0      (System/currentTimeMillis)
        names   (set (map :name (server/list-tools srv rw)))
        elapsed (- (System/currentTimeMillis) t0)
        fp-down (server/tools-fingerprint srv rw)]
    (is (contains? names "todo__todo_add"))
    (is (not-any? #(clojure.string/starts-with? % "api__") names) "a down app's tools disappear")
    (is (< elapsed 1000) "a slow app costs at most the probe deadline")
    (is (= [:up :down :slow]
           (map :status (get-in (call srv rw "autonomous_apps" {}) [:answer :ok :apps]))))
    (is (= {:ok {:app "todo" :tool "todo_add" :runtime nil}} (:answer (call srv rw "todo__todo_add" {}))))
    (is (= :channel/connect-failed (get-in (call srv rw "api__refund" {}) [:answer :error :type])))
    (testing "it comes back on reconnect, and the fingerprint moves"
      (reset! state :up)
      (is (contains? (set (map :name (server/list-tools srv rw))) "api__refund"))
      (is (not= fp-down (server/tools-fingerprint srv rw))))))

(deftest single-mode-names-stay-as-they-were
  (let [l   (ledger/open {:ledger/kind :memory})
        srv (server/server (second (member "todo" (app-responder "todo" ["todo_add"]) l)))
        names (set (map :name (server/list-tools srv rw)))]
    (is (contains? names "todo_add"))
    (is (not (contains? names "autonomous_apps")))
    (is (= {:ok {:app "todo" :tool "todo_add" :runtime nil}} (:answer (call srv rw "todo_add" {}))))
    (is (= [1 ["todo_add"]] (server/tools-fingerprint srv rw)))))
