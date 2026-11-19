(ns inventory.service
  "Application service of the inventory: the stock table in an atom and the
   use cases over it. Knows nothing about agents; inventory.agentic adapts
   these functions into tools.

   `db` is an atom of {sku item}."
  (:require [inventory.domain :as d]))

(defn db
  "A stock table holding `items`."
  [items]
  (atom (into (sorted-map) (map (juxt :sku identity)) items)))

(defn- not-found [sku]
  {:error {:type :stock/unknown-sku :message (str "no item " sku) :data {:sku sku}}})

(defn items
  "Every item as viewed (available, low?), optionally only the low ones."
  [db {:keys [low-only]}]
  (cond->> (map d/view (vals @db))
    low-only (filter :low?)
    true     vec))

(defn item
  "Result of the item `sku`."
  [db sku]
  (if-let [i (get @db sku)] {:ok (d/view i)} (not-found sku)))

(defn apply!
  "Apply domain fn `f` (item, units -> Result) to item `sku` atomically;
   answers the Result with the updated item viewed."
  [db sku f units]
  (let [out (atom nil)]
    (swap! db (fn [m]
                (if-let [i (get m sku)]
                  (let [r (f i units)]
                    (reset! out r)
                    (if (:ok r) (assoc m sku (:ok r)) m))
                  (do (reset! out (not-found sku)) m))))
    (let [r @out] (if (:ok r) {:ok (d/view (:ok r))} r))))

(defn add-item!
  "Result of adding a new sku with nothing on hand."
  [db sku reorder-at]
  (let [added (atom false)]
    (swap! db (fn [m] (if (contains? m sku)
                        m
                        (do (reset! added true) (assoc m sku (d/new-item sku reorder-at))))))
    (if @added
      {:ok (d/view (get @db sku))}
      {:error {:type :stock/exists :message (str sku " already exists") :data {:sku sku}}})))
