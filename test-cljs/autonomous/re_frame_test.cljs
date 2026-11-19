(ns autonomous.re-frame-test
  "The re-frame adapter in a real ClojureScript runtime: the example todo
   app driven through autonomous.core/rpc exactly as a bridge drives it."
  (:require [autonomous.core :as a]
            [cljs.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [todo.agentic :as agentic]
            [todo.model]))

(defn- fresh-app! []
  (a/reset-registry!)
  (rf/dispatch-sync [:todo/init])
  (agentic/install! true))

(use-fixtures :each {:before fresh-app!})

(def agent {:actor "agent-1" :scopes [:read :write]})

(defn- call [grant tool args] (a/rpc (merge grant {:op :call :tool tool :args args})))

(deftest event-tools-dispatch-through-re-frame
  (let [r (call agent "todo_add" {:text "milk"})]
    (is (= [{:id 1 :text "milk" :done? false}] (:ok r)) "answers the :result-sub after dispatch"))
  (call agent "todo_add" {:text "eggs"})
  (is (= {:ok {:total 2 :done 1}} (call agent "todo_toggle" {:id "1"})) "quoted id decodes to an int")
  (is (= {:ok [{:id 2 :text "eggs" :done? false}]}
         (call agent "todo_set-filter" {:filter "active"})) "enum strings decode to keywords"))

(deftest sub-tools-are-read-only
  (call agent "todo_add" {:text "x"})
  (is (= {:ok {:total 1 :done 0}} (call {:actor "reader" :scopes [:read]} "todos_stats" {})))
  (is (= :forbidden (get-in (call {:actor "reader" :scopes [:read]} "todo_add" {:text "y"}) [:error :type]))))

(deftest invalid-args-never-reach-re-frame
  (let [stats #(call agent "todos_stats" {})
        before (stats)]
    (is (= :invalid-args (get-in (call agent "todo_add" {:text ""}) [:error :type])))
    (is (= :invalid-args (get-in (call agent "todo_set-filter" {:filter "everything"}) [:error :type])))
    (is (= before (stats)))))

(deftest the-journal-tells-agents-from-the-human
  (call agent "todo_add" {:text "by agent"})
  (rf/dispatch-sync [:todo/add "by human"])
  (let [events (filter #(and (= :re-frame/event (:kind %)) (= :todo/add (:event-id %)))
                       (:entries (:ok (a/rpc {:op :journal :since 0}))))]
    (is (= [[:todo/add :agent "agent-1" ["by agent"]]
            [:todo/add :app nil ["by human"]]]
           (map (juxt :event-id :origin :actor :payload) events)))))

(deftest app-db-resource-shows-only-its-paths
  (call agent "todo_add" {:text "x"})
  (let [v (get-in (a/rpc {:op :read :uri "re-frame://app-db"}) [:ok :value])]
    (is (= #{:todos :filter} (set (keys v))))
    (is (not (contains? v :next-id)))))

(deftest dev-tools-need-admin
  (is (= :forbidden (get-in (call agent "re-frame_dispatch" {:event "[:todo/add \"x\"]"}) [:error :type])))
  (let [admin {:actor "dev" :scopes [:admin]}]
    (is (= {:ok {:dispatched [:todo/add "x"]}} (call admin "re-frame_dispatch" {:event "[:todo/add \"x\"]"})))
    (is (= :unregistered-handler (get-in (call admin "re-frame_dispatch" {:event "[:nope]"}) [:error :type])))
    (is (some #{":todo/add"} (get-in (call admin "re-frame_registry" {}) [:ok :events])))))

(deftest the-manifest-describes-the-app
  (let [m (:ok (a/rpc {:op :manifest :scopes [:read :write]}))]
    (is (= "todo" (get-in m [:app :name])))
    (is (= ["todo_add" "todo_remove" "todo_set-filter" "todo_toggle" "todos_stats" "todos_visible"]
           (map :name (:tools m))))
    (is (= {:type "object" :properties {:filter {:type "string" :enum [:all :active :done]}} :required [:filter]}
           (:input-schema (first (filter #(= "todo_set-filter" (:name %)) (:tools m))))))))

(deftest rpc-str-is-what-a-bridge-evaluates
  (is (= "{:ok {:total 0, :done 0}}"
         (a/rpc-str "{:op :call :tool \"todos_stats\" :scopes [:read]}"))))
