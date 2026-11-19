(ns orders.service
  "Application service: the use cases of the orders context. Each one
   collects what the pure domain needs (an order from the repository, a
   fresh id, the time), lets orders.domain decide, and stores the outcome.
   Knows nothing about agents or MCP; the autonomous surface and any HTTP
   API sit on top of these functions.

   `env` is {:repo OrderRepository :clock (fn [] millis) :new-id (fn [] uuid)}."
  (:require [orders.domain :as d]
            [orders.store :as store]))

(defn env
  "An application environment over `repo` with the system clock and random
   uuids, unless :clock / :new-id are given."
  [{:keys [repo clock new-id]}]
  {:repo   repo
   :clock  (or clock #(System/currentTimeMillis))
   :new-id (or new-id random-uuid)})

(defn- not-found [id]
  {:error {:type :order/not-found :message (str "no order " id) :data {:id id}}})

(defn place!
  "Place order command `cmd` (orders.domain/PlaceOrder). Result of the order."
  [{:keys [repo clock new-id]} cmd]
  (let [r (d/place-order cmd (new-id) (clock))]
    (if-let [o (:ok r)] {:ok (store/-put! repo o)} r)))

(defn orders
  "Every order, newest first, optionally only `customer`'s; at most `limit`."
  [{:keys [repo]} {:keys [customer limit]}]
  (cond->> (store/-all repo)
    customer (filterv #(= customer (:customer %)))
    limit    (take limit)
    true     vec))

(defn order
  "Result of the order with uuid `id`."
  [{:keys [repo]} id]
  (if-let [o (store/-get repo id)] {:ok o} (not-found id)))

(defn refund!
  "Refund `cents` on order `order-id` for `reason`, made by `by`. Result of
   the updated order, or the domain's refusal."
  [{:keys [repo clock new-id]} {:keys [order-id] :as cmd}]
  (let [rid (new-id) now (clock)]
    (or (store/-update! repo order-id #(d/refund % cmd rid now))
        (not-found order-id))))

(defn statement
  "Result of `customer`'s statement: their invoices and the totals."
  [env customer]
  (let [invoices (mapv d/invoice (orders env {:customer customer}))]
    {:ok {:customer       customer
          :orders         (count invoices)
          :paid-cents     (reduce + 0 (map :paid-cents invoices))
          :refunded-cents (reduce + 0 (map :refunded-cents invoices))
          :invoices       invoices}}))

(defn summary
  "Counts and money totals over every order."
  [env]
  (let [os (orders env {})]
    {:orders         (count os)
     :by-status      (frequencies (map :status os))
     :paid-cents     (reduce + 0 (map d/paid-cents os))
     :refunded-cents (reduce + 0 (map d/refunded-cents os))}))
