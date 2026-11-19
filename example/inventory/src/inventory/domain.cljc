(ns inventory.domain
  "Inventory bounded context, pure. A stock level per sku: units on hand
   and units reserved for orders; what is available is on hand minus
   reserved. Every behaviour is a function from values to a Result, {:ok
   value} or {:error {:type :message :data}}.

   Ubiquitous language:
     Item         a sku with its units on hand, reserved, and reorder point.
     Receive      units arriving at the warehouse: on hand grows.
     Reserve      units promised to an order: never more than available.
     Release      a reservation given back.
     Write-off    units lost or damaged: on hand shrinks, never below what
                  is reserved.")

(def Sku [:string {:min 1 :max 64}])

(def Units [:int {:min 1 :max 100000}])

(def Item
  [:map
   [:sku Sku]
   [:on-hand [:int {:min 0}]]
   [:reserved [:int {:min 0}]]
   [:reorder-at [:int {:min 0}]]])

(defn- fail
  ([type message] (fail type message nil))
  ([type message data]
   {:error (cond-> {:type type :message message} data (assoc :data data))}))

(defn available
  "Units of `item` that may still be reserved."
  [item]
  (- (:on-hand item) (:reserved item)))

(defn low?
  "True when `item`'s available units are at or below its reorder point."
  [item]
  (<= (available item) (:reorder-at item)))

(defn view
  "`item` with what an agent asks about first: available and low?."
  [item]
  (assoc item :available (available item) :low? (low? item)))

(defn new-item
  "An item for `sku` with nothing on hand."
  [sku reorder-at]
  {:sku sku :on-hand 0 :reserved 0 :reorder-at reorder-at})

(defn receive
  "Result of `units` arriving for `item`."
  [item units]
  (if (pos-int? units)
    {:ok (update item :on-hand + units)}
    (fail :stock/invalid-units "units are a positive integer" {:units units})))

(defn reserve
  "Result of reserving `units` of `item`: refused beyond what is available."
  [item units]
  (cond
    (not (pos-int? units))
    (fail :stock/invalid-units "units are a positive integer" {:units units})

    (> units (available item))
    (fail :stock/insufficient
          (str "only " (available item) " units of " (:sku item) " are available")
          {:requested units :available (available item)})

    :else {:ok (update item :reserved + units)}))

(defn release
  "Result of giving back `units` of `item`'s reservations."
  [item units]
  (cond
    (not (pos-int? units))
    (fail :stock/invalid-units "units are a positive integer" {:units units})

    (> units (:reserved item))
    (fail :stock/not-reserved (str "only " (:reserved item) " units are reserved")
          {:requested units :reserved (:reserved item)})

    :else {:ok (update item :reserved - units)}))

(defn write-off
  "Result of writing `units` of `item` off: refused when it would leave
   less on hand than is reserved."
  [item units]
  (cond
    (not (pos-int? units))
    (fail :stock/invalid-units "units are a positive integer" {:units units})

    (> units (available item))
    (fail :stock/reserved-units
          (str "only " (available item) " unreserved units can be written off")
          {:requested units :available (available item)})

    :else {:ok (update item :on-hand - units)}))
