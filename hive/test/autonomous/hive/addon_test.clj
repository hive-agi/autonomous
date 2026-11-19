(ns autonomous.hive.addon-test
  "The hive addon over an in-process app (local target): lifecycle, one
   session per hive agent, grants per agent, and the command surface."
  (:require [autonomous.core :as a]
            [autonomous.hive.addon :as addon]
            [autonomous.hive.apps :as apps]
            [autonomous.mcp.json :as json]
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
  {:autonomous/apps [{:app/id "svc"
                      :app/target {:target/kind :local :target/id "svc"}
                      :app/grants {"ling-reviewer" [:read]}}]})

(defn- started []
  (let [ad (addon/make-addon)]
    (p/initialize! ad app-config)
    ad))

(defn- run [ad params]
  (let [{:keys [content isError]} ((:handler (first (p/tools ad))) params)]
    {:value (json/read-json (:text (first content))) :error? (boolean isError)}))

(deftest lifecycle-is-idempotent
  (let [ad (started)]
    (is (= "autonomous" (p/addon-id ad)))
    (is (:already-initialized? (p/initialize! ad app-config)))
    (is (= {:status :ok :details {:apps ["svc"]}} (p/health ad)))
    (p/shutdown! ad)
    (is (= :down (:status (p/health ad))))))

(deftest every-hive-agent-acts-as-itself
  (let [ad (started)]
    (is (= {:text "hi" :by "ling-1"}
           (get-in (run ad {"command" "call" "app" "svc" "tool" "svc_echo" "args" {"text" "hi"} "agent_id" "ling-1"})
                   [:value :ok])))
    (is (= "ling-2" (get-in (run ad {"command" "whoami" "app" "svc" "agent_id" "ling-2"}) [:value :ok :actor])))
    (testing "a per-agent grant narrows what that agent may do"
      (is (:error? (run ad {"command" "call" "app" "svc" "tool" "svc_echo" "args" {"text" "x"}
                            "agent_id" "ling-reviewer"}))))
    (testing "an attach belongs to the agent that made it"
      (run ad {"command" "attach" "app" "svc" "runtime_id" "local" "agent_id" "ling-1"})
      (is (= "local" (get-in (run ad {"command" "whoami" "app" "svc" "agent_id" "ling-1"}) [:value :ok :runtime])))
      (is (nil? (get-in (run ad {"command" "whoami" "app" "svc" "agent_id" "ling-2"}) [:value :ok :runtime]))))
    (testing "the ledger says which ling did what"
      (is (some #(= ["ling-1" "svc_echo"] [(:actor %) (:tool %)])
                (get-in (run ad {"command" "ledger" "app" "svc" "agent_id" "ling-1"}) [:value :ok]))))))

(deftest unknown-app-and-command-say-what-exists
  (let [ad (started)]
    (is (= ["svc"] (get-in (run ad {"command" "call" "app" "nope"}) [:value :error :data :known])))
    (is (:error? (run ad {"command" "frobnicate" "app" "svc"})))
    (is (= [{:app "svc" :target {:target/kind "local" :target/id "svc"}}]
           (:ok (:value (run ad {"command" "apps"})))))))

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
