(ns orders.domain-trifecta-test
  "Trifectas over the pure orders domain: placing an order and refunding
   one. Mutants call captured fn values, never the subject vars."
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [orders.domain :as d]))

(def ^:private place-order* d/place-order)
(def ^:private refund* d/refund)

(def oid #uuid "00000000-0000-0000-0000-000000000001")
(def rid #uuid "00000000-0000-0000-0000-0000000000aa")

(def order
  (:ok (d/place-order {:customer "ada" :lines [{:sku "kbd" :qty 2 :unit-cents 500}]} oid 10)))

(defn result-shape
  "A Result as what was decided: the status and money of an order, or the
   refusal type."
  [r]
  (if-let [o (:ok r)]
    [:ok (:status o) (d/paid-cents o) (d/refunded-cents o)]
    [:error (get-in r [:error :type])]))

(def refunded-once (:ok (d/refund order {:cents 300 :reason "late" :by "a"} rid 20)))

(defn- cmd [cents] {:cents cents :reason "r" :by "agent"})

(deftrifecta refund-trifecta
  orders.domain/refund
  {:golden-path "example/orders/test/golden/refund.edn"
   :apply? true
   :xf result-shape
   :cases {:partial        [order (cmd 300) rid 20]
           :full           [order (cmd 1000) rid 20]
           :over           [order (cmd 1001) rid 20]
           :zero           [order (cmd 0) rid 20]
           :negative       [order (cmd -5) rid 20]
           :rest-after-one [refunded-once (cmd 700) rid 30]
           :over-after-one [refunded-once (cmd 701) rid 30]}
   :gen (gen/tuple (gen/elements [order refunded-once])
                   (gen/fmap cmd (gen/choose -10 1200))
                   (gen/return rid)
                   (gen/choose 0 100))
   :pred (fn [r] (or (contains? r :error)
                     (and (m/validate d/Order (:ok r))
                          (<= (d/refunded-cents (:ok r)) (d/paid-cents (:ok r))))))
   :num-tests 300
   :mutations [["allows any amount"
                (fn [o c id now]
                  {:ok (update o :refunds conj (assoc c :id id :at now))})]
               ["forgets the status"
                (fn [o c id now]
                  (let [r (refund* o c id now)]
                    (if (:ok r) (assoc-in r [:ok :status] :placed) r)))]
               ["ignores earlier refunds"
                (fn [o c id now]
                  (let [r (refund* (assoc o :refunds []) c id now)]
                    (if (:ok r) (update-in r [:ok :refunds] #(into (:refunds o) %)) r)))]]})

(def ^:private gen-line
  (gen/hash-map :sku (gen/elements ["kbd" "mouse" ""])
                :qty (gen/choose 0 3)
                :unit-cents (gen/choose -1 900)))

(deftrifecta place-order-trifecta
  orders.domain/place-order
  {:golden-path "example/orders/test/golden/place-order.edn"
   :apply? true
   :xf result-shape
   :cases {:one-line    [{:customer "ada" :lines [{:sku "kbd" :qty 2 :unit-cents 500}]} oid 1]
           :two-lines   [{:customer "ada" :lines [{:sku "kbd" :qty 1 :unit-cents 500}
                                                  {:sku "usb" :qty 3 :unit-cents 100}]} oid 1]
           :no-lines    [{:customer "ada" :lines []} oid 1]
           :no-customer [{:customer "" :lines [{:sku "kbd" :qty 1 :unit-cents 500}]} oid 1]
           :free-item   [{:customer "ada" :lines [{:sku "kbd" :qty 1 :unit-cents 0}]} oid 1]
           :zero-qty    [{:customer "ada" :lines [{:sku "kbd" :qty 0 :unit-cents 5}]} oid 1]}
   :gen (gen/tuple (gen/hash-map :customer (gen/elements ["ada" ""])
                                 :lines (gen/vector gen-line 0 3))
                   (gen/return oid)
                   (gen/choose 0 100))
   :pred (fn [r] (or (contains? r :error) (m/validate d/Order (:ok r))))
   :mutations [["skips validation"
                (fn [c id now] {:ok {:id id :customer (:customer c) :lines (:lines c)
                                     :status :placed :placed-at now :refunds []}})]
               ["drops all but the first line"
                (fn [c id now]
                  (let [r (place-order* c id now)]
                    (if (:ok r) (update-in r [:ok :lines] (comp vec (partial take 1))) r)))]]})
