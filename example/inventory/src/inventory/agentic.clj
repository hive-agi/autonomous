(ns inventory.agentic
  "The inventory service's whole agent surface, in one namespace: an
   adapter from autonomous tools and resources onto inventory.service.
   Nothing here decides anything about stock.

   Scopes: reading needs :read, moving stock :write, and writing stock off
   the app-defined :audit scope (an agent granted read,write cannot)."
  (:require [autonomous.core :as a]
            [inventory.domain :as d]
            [inventory.service :as svc]))

(defn- value!
  "The value of Result `r`; a refusal becomes an ex-info whose
   :autonomous/error is the domain's error type, answered to the agent as a
   refusal of that type with the domain's data."
  [r]
  (if-let [{:keys [type message data]} (:error r)]
    (throw (ex-info message (assoc data :autonomous/error type)))
    (:ok r)))

(def ^:private Move [:map [:sku d/Sku] [:units d/Units]])

(def ^:private description
  (str "A warehouse inventory. inventory_items lists stock (available = on hand minus "
       "reserved); receive, reserve and release move it; inventory_write_off removes lost "
       "units (needs the audit scope); inventory_count runs a cycle count (slow: it may answer "
       "'still running', collect it with autonomous_poll). Read inventory://low-stock for what "
       "to reorder."))

(defn- move-tool
  "Register a tool that applies domain fn `f` to a sku's units."
  [id f doc extra]
  (a/reg-tool id (merge {:description doc :input Move :output d/Item
                         :handler (fn [{:keys [sku units]} _grant]
                                    (value! (svc/apply! (:db extra) sku f units)))}
                        (dissoc extra :db))))

(defn install!
  "Describe the app and register its agent surface over stock table `db`.
   opts: :count-ms, how long a cycle count takes (default 1500)."
  ([db] (install! db {}))
  ([db {:keys [count-ms] :or {count-ms 1500}}]
   (a/set-app-info! {:name "inventory" :description description :version "0.1.0"})

   (a/reg-tool :inventory/items
     {:description "Every sku with on hand, reserved, available and low?. low-only filters."
      :read-only? true
      :input [:map [:low-only {:optional true} :boolean]]
      :handler (fn [args _grant] (svc/items db args))})

   (a/reg-tool :inventory/item
     {:description "One sku's stock."
      :read-only? true
      :input [:map [:sku d/Sku]]
      :handler (fn [{:keys [sku]} _grant] (value! (svc/item db sku)))})

   (a/reg-tool :inventory/add-item
     {:name "inventory_add_item"
      :description "Start tracking a new sku, with nothing on hand."
      :input [:map [:sku d/Sku] [:reorder-at {:optional true} [:int {:min 0}]]]
      :handler (fn [{:keys [sku reorder-at]} _grant] (value! (svc/add-item! db sku (or reorder-at 0))))})

   (move-tool :inventory/receive d/receive "Units arrived for a sku: on hand grows." {:db db})
   (move-tool :inventory/reserve d/reserve
              "Promise units of a sku to an order; refused beyond what is available." {:db db})
   (move-tool :inventory/release d/release "Give back units reserved earlier." {:db db})
   (move-tool :inventory/write-off d/write-off
              "Remove lost or damaged units. Needs the audit scope; cannot touch reserved units."
              {:db db :name "inventory_write_off" :scopes [:audit]
               :annotations {:destructiveHint true}})

   (a/reg-tool :inventory/count
     {:description "Cycle count: walk every shelf and answer the totals. Slow; may answer 'still running'."
      :read-only? true
      :handler (fn [_args grant]
                 (future
                   (Thread/sleep (long count-ms))
                   (let [is (svc/items db {})]
                     {:counted-by (:actor grant)
                      :skus       (count is)
                      :on-hand    (reduce + 0 (map :on-hand is))
                      :reserved   (reduce + 0 (map :reserved is))})))})

   (a/reg-resource "inventory://low-stock"
     {:name "low-stock"
      :description "The skus at or below their reorder point."
      :read (fn [_grant] (mapv :sku (svc/items db {:low-only true})))})
   nil))
