(ns orders.domain
  "Orders bounded context, pure. Value objects are malli schemas; every
   behaviour is a function from values to a Result, {:ok value} or
   {:error {:type :message :data}}. No state, clock or id generator is read
   here: callers pass `now` and new ids in.

   Ubiquitous language:
     Order    what a customer bought: lines, a status and the refunds made.
     Line     one sku, a quantity and a unit price in cents.
     Refund   money given back on an order, never more than was paid.
     Invoice  the billing view of one order: line totals, paid, refunded, due."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def Cents [:int {:min 1}])

(def Sku [:string {:min 1 :max 64}])

(def Line
  [:map
   [:sku Sku]
   [:qty [:int {:min 1 :max 1000}]]
   [:unit-cents Cents]])

(def Refund
  [:map
   [:id :uuid]
   [:cents Cents]
   [:reason [:string {:min 1 :max 200}]]
   [:by :string]
   [:at [:int {:min 0}]]])

(def Status [:enum :placed :partially-refunded :refunded])

(def Order
  [:map
   [:id :uuid]
   [:customer [:string {:min 1 :max 128}]]
   [:lines [:vector {:min 1} Line]]
   [:status Status]
   [:placed-at [:int {:min 0}]]
   [:refunds [:vector Refund]]])

(def Invoice
  [:map
   [:order-id :uuid]
   [:lines [:vector [:map [:sku Sku] [:qty :int] [:line-cents :int]]]]
   [:paid-cents :int]
   [:refunded-cents :int]
   [:net-cents :int]])

(defn- fail
  "A Result refusing with `type` and a `message` written for the caller."
  ([type message] (fail type message nil))
  ([type message data]
   {:error (cond-> {:type type :message message} data (assoc :data data))}))

(defn line-cents
  "Total of one line: quantity times unit price."
  [{:keys [qty unit-cents]}]
  (* qty unit-cents))

(defn paid-cents
  "What the customer paid for `order`: the sum of its line totals."
  [order]
  (reduce + 0 (map line-cents (:lines order))))

(defn refunded-cents
  "What has been given back on `order` so far."
  [order]
  (reduce + 0 (map :cents (:refunds order))))

(defn refundable-cents
  "What may still be refunded on `order`: paid minus refunded, never below 0."
  [order]
  (max 0 (- (paid-cents order) (refunded-cents order))))

(defn status-of
  "The status `order` is in given its refunds."
  [order]
  (let [refunded (refunded-cents order)]
    (cond (zero? refunded)                    :placed
          (>= refunded (paid-cents order))    :refunded
          :else                               :partially-refunded)))

(def PlaceOrder
  "What it takes to place an order."
  [:map
   [:customer [:string {:min 1 :max 128}]]
   [:lines [:vector {:min 1 :max 50} Line]]])

(defn place-order
  "Result of placing command `cmd` (PlaceOrder) as order `id` at `now`."
  [cmd id now]
  (if-let [e (m/explain PlaceOrder cmd)]
    (fail :order/invalid "the order does not match PlaceOrder" (me/humanize e))
    {:ok {:id        id
          :customer  (:customer cmd)
          :lines     (vec (:lines cmd))
          :status    :placed
          :placed-at now
          :refunds   []}}))

(defn refund
  "Result of refunding `cents` on `order` for `reason`, as refund `refund-id`
   made by `by` at `now`. Refuses a non-positive amount and any amount above
   what is still refundable; answers the updated order."
  [order {:keys [cents reason by]} refund-id now]
  (let [left (refundable-cents order)]
    (cond
      (not (pos-int? cents))
      (fail :refund/invalid-amount "a refund is a positive number of cents" {:cents cents})

      (> cents left)
      (fail :refund/exceeds-paid
            (str "only " left " cents are still refundable on this order")
            {:requested cents :refundable left})

      :else
      (let [r {:id refund-id :cents cents :reason reason :by by :at now}
            o (update order :refunds conj r)]
        {:ok (assoc o :status (status-of o))}))))

(defn invoice
  "The Invoice of `order`."
  [order]
  {:order-id       (:id order)
   :lines          (mapv (fn [l] {:sku (:sku l) :qty (:qty l) :line-cents (line-cents l)})
                         (:lines order))
   :paid-cents     (paid-cents order)
   :refunded-cents (refunded-cents order)
   :net-cents      (- (paid-cents order) (refunded-cents order))})
