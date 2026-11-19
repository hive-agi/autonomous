(ns autonomous.hive.addon-test
  "The hive addon over one fleet: an in-process app (local target) and an
   app that is down (an nREPL target with no port file). Lifecycle, one
   session per hive agent across the fleet, per-app grants, fleet routing,
   autonomous_apps status, and the ledger's by_actor filter."
  (:require [autonomous.core :as a]
            [autonomous.hive.addon :as addon]
            [autonomous.hive.apps :as apps]
            [autonomous.mcp.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.generators :as gen]
            [hive-addon.protocol :as p]
            [hive-test.trifecta :refer [deftrifecta]]))

(defn- install-app! []
  (a/reset-registry!)
  (a/set-app-info! {:name "svc" :description "a backend service"})
  (a/reg-tool :svc/echo {:description "Echo who asked" :input [:map [:text :string]]
                         :handler (fn [{:keys [text]} grant] {:text text :by (:actor grant)})}))

(use-fixtures :each (fn [t] (install-app!) (t)))

(def app-config
  {:autonomous/probe-ms 500
   :autonomous/apps [{:app/id "svc"
                      :app/target {:target/kind :local :target/id "svc"}
                      :app/grants {"ling-reviewer" [:read]}}
                     {:app/id "orders"
                      :app/target {:target/kind :nrepl :nrepl/mode :clj
                                   :nrepl/port-file "/nonexistent/autonomous/orders/.nrepl-port"}}]})

(defn- started
  ([] (started app-config))
  ([cfg] (let [ad (addon/make-addon)] (p/initialize! ad cfg) ad)))

(defn- run [ad params]
  (let [{:keys [content isError]} ((:handler (first (p/tools ad))) params)]
    {:value (json/read-json (:text (first content))) :error? (boolean isError)}))

(defn- ok [ad params] (get-in (run ad params) [:value :ok]))

(deftest lifecycle-is-idempotent
  (let [ad (started)]
    (is (= "autonomous" (p/addon-id ad)))
    (is (:already-initialized? (p/initialize! ad app-config)))
    (is (= {:status :ok :details {:apps ["svc" "orders"]}} (p/health ad)))
    (p/shutdown! ad)
    (is (= :down (:status (p/health ad))))
    (is (:error? (run ad {"command" "apps"})) "a stopped addon answers a refusal, not a crash")))

(deftest a-bad-fleet-refuses-to-start
  (let [ad (addon/make-addon)
        r  (p/initialize! ad {:autonomous/apps [{:app/id "a_b" :app/target {:target/kind :local}}
                                                {:app/id "a.b" :app/target {:target/kind :local}}]})]
    (is (false? (:success? r)))
    (is (str/includes? (first (:errors r)) "share a tool prefix"))
    (is (= :down (:status (p/health ad))))))

(deftest apps-shows-the-whole-fleet-with-status
  (let [ad   (started)
        rows (ok ad {"command" "apps" "agent_id" "ling-1"})]
    (is (= [["svc" "up"] ["orders" "down"]] (mapv (juxt :app :status) rows)))
    (is (= 1 (:tools (first rows))))
    (is (= "channel/no-port" (get-in (second rows) [:error :type])) "a down app says why")
    (is (= "local" (get-in (first rows) [:target :target/kind])))))

(deftest every-hive-agent-acts-as-itself
  (let [ad (started)]
    (is (= {:text "hi" :by "ling-1"}
           (ok ad {"command" "call" "app" "svc" "tool" "svc_echo" "args" {"text" "hi"} "agent_id" "ling-1"})))
    (is (= "ling-2" (:actor (ok ad {"command" "whoami" "agent_id" "ling-2"}))))
    (testing "a per-agent grant narrows what that agent may do on that app"
      (is (:error? (run ad {"command" "call" "app" "svc" "tool" "svc_echo" "args" {"text" "x"}
                            "agent_id" "ling-reviewer"})))
      (is (= ["svc__svc_echo"] (mapv :name (ok ad {"command" "tools" "app" "svc" "agent_id" "ling-1"}))))
      (is (empty? (ok ad {"command" "tools" "app" "svc" "agent_id" "ling-reviewer"}))
          "the reviewer is not offered the write tool"))
    (testing "an attach belongs to the agent that made it, and to that app"
      (is (= "local" (:attached (ok ad {"command" "attach" "app" "svc" "runtime_id" "local" "agent_id" "ling-1"}))))
      (is (= {:svc "local"} (:runtimes (ok ad {"command" "whoami" "agent_id" "ling-1"}))))
      (is (= {} (:runtimes (ok ad {"command" "whoami" "agent_id" "ling-2"})))))))

(deftest fleet-routing
  (let [ad (started)]
    (testing "a fleet name routes by itself; app + local name is the same call"
      (is (= {:text "a" :by "ling-1"}
             (ok ad {"command" "call" "tool" "svc__svc_echo" "args" {"text" "a"} "agent_id" "ling-1"}))))
    (testing "tools lists every app that is up, prefixed by app"
      (let [names (set (map :name (ok ad {"command" "tools" "agent_id" "ling-1"})))]
        (is (contains? names "svc__svc_echo"))
        (is (contains? names "autonomous_apps"))
        (is (not-any? #(str/starts-with? % "orders__") names))))
    (testing "a down app is refused with its reason, and never breaks the others"
      (is (= "channel/no-port"
             (get-in (run ad {"command" "call" "app" "orders" "tool" "place" "agent_id" "ling-1"})
                     [:value :error :type])))
      (is (= {:text "b" :by "ling-1"}
             (ok ad {"command" "call" "app" "svc" "tool" "svc_echo" "args" {"text" "b"} "agent_id" "ling-1"}))))
    (testing "per-app built-ins need the app when the fleet has several"
      (is (= "target/required" (get-in (run ad {"command" "runtimes" "agent_id" "ling-1"}) [:value :error :type])))
      (is (= [{:runtime/id "local" :runtime/kind "clj" :runtime/label "this process"}]
             (:runtimes (ok ad {"command" "runtimes" "app" "svc" "agent_id" "ling-1"})))))))

(deftest the-ledger-filters-by-actor-not-by-caller
  (let [ad (started)]
    (ok ad {"command" "call" "app" "svc" "tool" "svc_echo" "args" {"text" "1"} "agent_id" "ling-1"})
    (ok ad {"command" "call" "app" "svc" "tool" "svc_echo" "args" {"text" "2"} "agent_id" "ling-2"})
    (let [calls (fn [params] (->> (ok ad (merge {"command" "ledger" "kind" "autonomous/call"} params))
                                  (map (juxt :actor :tool (comp :target :source)))
                                  set))]
      (is (= #{["ling-1" "svc_echo" "svc"] ["ling-2" "svc_echo" "svc"]}
             (calls {"agent_id" "coordinator"}))
          "the caller's agent_id is not a filter")
      (is (= #{["ling-2" "svc_echo" "svc"]} (calls {"agent_id" "coordinator" "by_actor" "ling-2"}))
          "by_actor reads what one ling did")
      (is (= #{["ling-1" "svc_echo" "svc"]} (calls {"agent_id" "ling-2" "by_actor" "ling-1" "app" "svc"}))))))

(deftest unknown-app-and-command-say-what-exists
  (let [ad (started)]
    (is (= ["svc" "orders"] (get-in (run ad {"command" "call" "app" "nope"}) [:value :error :data :known])))
    (is (:error? (run ad {"command" "frobnicate" "app" "svc"})))
    (is (contains? (ok ad {"command" "help"}) :commands))))

(deftest routing-helpers
  (is (= "svc__svc_echo" (addon/exposed-tool "svc" "svc_echo")))
  (is (= "svc__svc_echo" (addon/exposed-tool nil "svc__svc_echo")))
  (is (= "orders-api__refund" (addon/exposed-tool "orders_api" "refund")))
  (is (= "autonomous://svc/app://db" (addon/exposed-uri "svc" "app://db")))
  (is (= "autonomous://svc/app://db" (addon/exposed-uri "svc" "autonomous://svc/app://db")))
  (is (= "svc:p7" (addon/pending-id "svc" "p7")))
  (is (= "svc:p7" (addon/pending-id "svc" "svc:p7")))
  (is (= {:target "svc" :actor "ling-2" :kind "autonomous/call"}
         (addon/ledger-query {:app "svc" :by_actor "ling-2" :kind "autonomous/call" :agent_id "me"})))
  (is (= "ling-9" (addon/actor-of {"agent_id" "ling-9" "actor" "someone-else"})))
  (is (= "hive" (addon/actor-of {"actor" "ling-x"})) "actor is no longer read as the caller"))

(deftest a-request-is-narrowed-to-the-grant-on-its-app
  (let [app {:app/id "svc" :app/grants {"r" [:read]}}]
    (is (= #{:read} (:scopes (apps/scope-request app {:actor "r" :scopes #{:read :write}}))))
    (is (= #{:read :write} (:scopes (apps/scope-request app {:actor "w" :scopes #{:read :write}}))))
    (is (= #{:read} (:scopes (apps/scope-request app {:actor "autonomous/bridge" :scopes #{:read}})))
        "the bridge's journal reader is left alone")
    (is (= #{:read :write} (apps/narrow #{:read :write} #{:admin})))
    (is (= {:actor "r" :scopes #{:read :admin}}
           (apps/fleet-grant [app {:app/id "x" :app/grants {"r" [:admin]}}] "r")))))

(deftrifecta grant-for-trifecta
  autonomous.hive.apps/grant-for
  {:golden-path "test/golden/grant-for.edn"
   :apply? true
   :cases {:default      [{:app/id "a"} "ling-1"]
           :app-scopes   [{:app/id "a" :app/scopes [:read]} "ling-1"]
           :agent-grant  [{:app/id "a" :app/scopes [:read :write] :app/grants {"ling-1" [:admin]}} "ling-1"]
           :other-agent  [{:app/id "a" :app/grants {"ling-1" [:admin]}} "ling-2"]}
   :gen (gen/tuple (gen/return {:app/id "a" :app/grants {"x" [:read]}}) (gen/elements ["x" "y"]))
   :pred (fn [g] (and (string? (:actor g)) (set? (:scopes g))))
   :mutations [["ignores per-agent grants" (fn [app actor] (apps/grant-for (dissoc app :app/grants) actor))]
               ["everyone admin" (fn [_ actor] {:actor actor :scopes #{:admin}})]]})
