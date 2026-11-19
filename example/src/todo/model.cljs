(ns todo.model
  "An ordinary re-frame todo app. Nothing here knows about agents."
  (:require [re-frame.core :as rf]))

(def initial-db {:todos {} :next-id 1 :filter :all})

(rf/reg-event-db :todo/init (fn [_ _] initial-db))

(rf/reg-event-db :todo/add
  (fn [db [_ text]]
    (let [id (:next-id db)]
      (-> db
          (assoc-in [:todos id] {:id id :text text :done? false})
          (update :next-id inc)))))

(rf/reg-event-db :todo/toggle
  (fn [db [_ id]] (update-in db [:todos id :done?] not)))

(rf/reg-event-db :todo/remove
  (fn [db [_ id]] (update db :todos dissoc id)))

(rf/reg-event-db :todo/set-filter
  (fn [db [_ f]] (assoc db :filter f)))

(rf/reg-sub :todos/all (fn [db _] (sort-by :id (vals (:todos db)))))

(rf/reg-sub :todos/filter (fn [db _] (:filter db)))

(rf/reg-sub :todos/visible
  :<- [:todos/all] :<- [:todos/filter]
  (fn [[todos f] _]
    (vec (case f
           :active (remove :done? todos)
           :done   (filter :done? todos)
           todos))))

(rf/reg-sub :todos/stats
  :<- [:todos/all]
  (fn [todos _]
    {:total (count todos) :done (count (filter :done? todos))}))
