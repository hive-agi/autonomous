(ns autonomous.demo.plan-test
  "The pure rules of `bb demo`."
  (:require [autonomous.demo.plan :as plan]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest ports-stay-off-the-shadow-defaults
  (is (= plan/default-ports (plan/pick-ports (constantly true) #(throw (ex-info "unused" {})))))
  (is (empty? (filter #{7888 9630 8280} (vals plan/default-ports))))
  (testing "a taken port is replaced by a free one, the others kept"
    (let [taken #{7777}
          ports (plan/pick-ports (complement taken) (constantly 40001))]
      (is (= 40001 (:bridge ports)))
      (is (= 8281 (:dev-http ports))))))

(deftest shadow-env-moves-every-port
  (let [env (plan/shadow-env {:nrepl 1 :shadow-http 2 :dev-http 3})]
    (is (= {:nrepl {:port 1} :http {:port 2}} (edn/read-string (get env "SHADOW_CLJS"))))
    (is (= "3" (get env "AUTONOMOUS_DEV_HTTP_PORT")))))

(deftest todo-references-become-ids
  (let [todos [{:id 1 :text "milk"} {:id 2 :text "eggs"}]]
    (is (= {:id 2} (plan/resolve-args {:id {:todo "eggs"}} todos)))
    (is (= {:id {:todo "bread"}} (plan/resolve-args {:id {:todo "bread"}} todos)) "unknown text stays, the app refuses it")
    (is (= {:text "milk" :n 3} (plan/resolve-args {:text "milk" :n 3} todos)) "plain values pass")
    (is (= {"id" 1} (plan/resolve-args {"id" {"todo" "milk"}} todos)) "string keys pass as they are")))

(deftest captions-name-the-call
  (is (= "todos_stats" (plan/caption "todos_stats" {})))
  (is (= "todo_add {text: \"milk\"}" (plan/caption "todo_add" {:text "milk"}))))

(deftest outcome-lines
  (is (= "ok [1 2]" (plan/outcome-line {:value {:result [1 2]} :error? false})))
  (is (= "refused: should be at least 1 character"
         (plan/outcome-line {:error? true :text "should be at least\n 1 character"})))
  (is (str/ends-with? (plan/outcome-line {:value (vec (range 100))}) "...")))

(deftest the-newest-runtime-is-the-tab-just-opened
  (is (= 7 (plan/newest-runtime [3 7 5])))
  (is (= "12" (plan/newest-runtime ["9" "12"])))
  (is (nil? (plan/newest-runtime []))))

(deftest agent-instructions-name-the-endpoint
  (let [ls (plan/agent-instructions "http://127.0.0.1:7777/mcp")]
    (is (every? #(str/includes? (str/join "\n" ls) %)
                ["claude mcp add --transport http" "swarm agent spawn" "http://127.0.0.1:7777/mcp"]))))
