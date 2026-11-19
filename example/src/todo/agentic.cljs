(ns todo.agentic
  "The todo app's agent surface: which of its events and subscriptions an
   agent may use, described for the agent. This is the whole integration."
  (:require [autonomous.core :as a]
            [autonomous.re-frame :as arf]))

(def Todo [:map [:id :int] [:text :string] [:done? :boolean]])

(defn install!
  "Register the agent surface. `dev?` adds the generic :admin dev tools."
  [dev?]
  (a/set-app-info!
   {:name "todo"
    :description (str "A todo list. Add items with todo_add, mark them with todo_toggle, "
                      "remove them with todo_remove, and read todos_visible or todos_stats. "
                      "Ids come from todos_visible.")})

  (arf/reg-event-tool :todo/add
    {:description "Add a todo item. Answers the visible todos."
     :input [:map [:text [:string {:min 1 :max 200}]]]
     :args->event (fn [{:keys [text]}] [:todo/add text])
     :result-sub [:todos/visible]})

  (arf/reg-event-tool :todo/toggle
    {:description "Mark a todo done, or not done again."
     :input [:map [:id :int]]
     :args->event (fn [{:keys [id]}] [:todo/toggle id])
     :result-sub [:todos/stats]})

  (arf/reg-event-tool :todo/remove
    {:description "Delete a todo item."
     :input [:map [:id :int]]
     :annotations {:destructiveHint true}
     :args->event (fn [{:keys [id]}] [:todo/remove id])
     :result-sub [:todos/stats]})

  (arf/reg-event-tool :todo/set-filter
    {:description "Choose which todos are visible."
     :input [:map [:filter [:enum :all :active :done]]]
     :args->event (fn [{:keys [filter]}] [:todo/set-filter filter])
     :result-sub [:todos/visible]})

  (arf/reg-sub-tool :todos/visible
    {:description "The todos currently visible under the active filter."
     :output [:map [:result [:vector Todo]]]})

  (arf/reg-sub-tool :todos/stats
    {:description "How many todos exist and how many are done."})

  (arf/expose-db! {:paths [[:todos] [:filter]]})
  (arf/observe-events! {:payloads? true})
  (when dev? (arf/install-dev-tools!)))
