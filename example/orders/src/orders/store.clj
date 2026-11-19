(ns orders.store
  "Order repository: the port the application service depends on, and an
   in-memory adapter. A database adapter is one more `reify`.")

(defprotocol OrderRepository
  (-get [repo id] "The order with uuid `id`, or nil.")
  (-all [repo] "Every order, newest first.")
  (-put! [repo order] "Store `order` (insert or replace); answers it.")
  (-update! [repo id f]
    "Apply Result fn `f` to the order `id` atomically: when `f` answers
     {:ok order'} store order', and answer what `f` answered either way.
     Answers nil when no such order exists."))

(defn memory
  "An OrderRepository over an atom, for demos and tests."
  []
  (let [db (atom {})]
    (reify OrderRepository
      (-get [_ id] (get @db id))
      (-all [_] (vec (sort-by :placed-at > (vals @db))))
      (-put! [_ order] (swap! db assoc (:id order) order) order)
      (-update! [_ id f]
        (let [out (atom nil)]
          (swap! db (fn [m]
                      (if-let [o (get m id)]
                        (let [r (f o)]
                          (reset! out r)
                          (if (contains? r :ok) (assoc m id (:ok r)) m))
                        (do (reset! out nil) m))))
          @out)))))
