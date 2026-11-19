(ns inventory.domain-trifecta-test
  "Trifectas over the pure inventory domain: reserving and writing off.
   Mutants call captured fn values, never the subject vars."
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [inventory.domain :as d]
            [malli.core :as m]))

(def ^:private reserve* d/reserve)

(def item {:sku "kbd" :on-hand 10 :reserved 4 :reorder-at 2})

(defn result-shape
  "A Result as what was decided: the stock numbers, or the refusal type."
  [r]
  (if-let [i (:ok r)]
    [:ok (:on-hand i) (:reserved i) (d/available i)]
    [:error (get-in r [:error :type])]))

(defn- sound?
  "Every Result is a refusal or an item that never promises more than it holds."
  [r]
  (or (contains? r :error)
      (and (m/validate d/Item (:ok r)) (<= (:reserved (:ok r)) (:on-hand (:ok r))))))

(def ^:private gen-args
  (gen/tuple (gen/elements [item (assoc item :reserved 0) (assoc item :reserved 10)])
             (gen/choose -2 14)))

(deftrifecta reserve-trifecta
  inventory.domain/reserve
  {:golden-path "example/inventory/test/golden/reserve.edn"
   :apply? true
   :xf result-shape
   :cases {:some [item 3] :all [item 6] :over [item 7] :zero [item 0] :negative [item -1]}
   :gen gen-args
   :pred sound?
   :num-tests 200
   :mutations [["ignores reservations" (fn [i u] (reserve* (assoc i :reserved 0) u))]
               ["allows any amount" (fn [i u] {:ok (update i :reserved + u)})]]})

(deftrifecta write-off-trifecta
  inventory.domain/write-off
  {:golden-path "example/inventory/test/golden/write-off.edn"
   :apply? true
   :xf result-shape
   :cases {:some [item 3] :all-free [item 6] :into-reserved [item 7] :zero [item 0]}
   :gen gen-args
   :pred sound?
   :num-tests 200
   :mutations [["may touch reserved units"
                (fn [i u] (if (<= 1 u (:on-hand i)) {:ok (update i :on-hand - u)} (reserve* i u)))]]})
