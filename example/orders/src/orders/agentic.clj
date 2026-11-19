(ns orders.agentic
  "The orders service's agent surface: an adapter from autonomous tools and
   resources onto the use cases of orders.service. Nothing here decides
   anything about orders; it names the affordances, describes them for an
   agent, maps a domain refusal onto an autonomous refusal and passes the
   caller's actor through.

   Scopes: reading needs :read, placing an order :write, and a refund the
   app-defined :billing scope (an agent granted read,write cannot refund)."
  (:require [autonomous.core :as a]
            [orders.domain :as d]
            [orders.service :as svc]))

(defn- value!
  "The value of service Result `r`; a refusal becomes an ex-info whose
   :autonomous/error is the domain's error type, which autonomous answers
   as a refusal of that type carrying the domain's data."
  [r]
  (if-let [{:keys [type message data]} (:error r)]
    (throw (ex-info message (assoc data :autonomous/error type)))
    (:ok r)))

(def ^:private OrderRef [:map [:order-id :uuid]])

(def ^:private description
  (str "An order service. Place orders with orders_place, browse them with orders_list and "
       "orders_get, refund money with orders_refund (needs the billing scope; refunds are "
       "final), and build a customer statement with orders_statement (slow: it may answer "
       "'still running', collect it with autonomous_poll). Money is in integer cents. "
       "Read orders://summary for totals."))

(defn- install-reads!
  "Register the read-only tools and the summary resource over `env`."
  [env]
  (a/reg-tool :orders/list
    {:description "Orders, newest first. Filter by customer; limit caps the count."
     :read-only? true
     :input [:map [:customer {:optional true} :string]
             [:limit {:optional true} [:int {:min 1 :max 500}]]]
     :handler (fn [args _grant] (svc/orders env args))})

  (a/reg-tool :orders/get
    {:description "One order by id, with its lines, status and refunds."
     :read-only? true
     :input OrderRef
     :output d/Order
     :handler (fn [{:keys [order-id]} _grant] (value! (svc/order env order-id)))})

  (a/reg-resource "orders://summary"
    {:name "summary"
     :description "How many orders exist, by status, and the money paid and refunded."
     :read (fn [_grant] (svc/summary env))}))

(defn- install-writes!
  "Register placing, refunding and the asynchronous statement over `env`.
   The statement handler returns a future that takes `statement-ms`."
  [env statement-ms]
  (a/reg-tool :orders/place
    {:description "Place an order for a customer: one or more lines of sku, qty and unit-cents."
     :input d/PlaceOrder
     :output d/Order
     :handler (fn [cmd _grant] (value! (svc/place! env cmd)))})

  (a/reg-tool :orders/refund
    {:description (str "Refund cents on an order, with a reason. Money moves and cannot be taken "
                       "back: read the order first and refund at most what is still refundable.")
     :input [:map [:order-id :uuid] [:cents d/Cents] [:reason [:string {:min 1 :max 200}]]]
     :output d/Order
     :scopes [:billing]
     :annotations {:destructiveHint true :idempotentHint false}
     :handler (fn [cmd grant] (value! (svc/refund! env (assoc cmd :by (:actor grant)))))})

  (a/reg-tool :orders/statement
    {:description "A customer's statement: every invoice and the totals. Slow; may answer 'still running'."
     :read-only? true
     :input [:map [:customer [:string {:min 1}]]]
     :handler (fn [{:keys [customer]} _grant]
                (future
                  (Thread/sleep (long statement-ms))
                  (value! (svc/statement env customer))))}))

(defn install!
  "Describe the app and register its whole agent surface over application
   environment `env` (orders.service/env). opts: :statement-ms, how long
   the statement takes (default 1500)."
  ([env] (install! env {}))
  ([env {:keys [statement-ms] :or {statement-ms 1500}}]
   (a/set-app-info! {:name "orders" :description description :version "0.1.0"})
   (install-reads! env)
   (install-writes! env statement-ms)
   nil))
