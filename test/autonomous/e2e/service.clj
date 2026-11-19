(ns autonomous.e2e.service
  "A cheap microservice for the live fleet test: a babashka nREPL server
   whose process hosts its own autonomous registry. `install!` registers
   the tools of service `kind` (\"orders\" or \"stock\")."
  (:require [autonomous.core :as a]))

(defn install!
  "Register the tools of service `kind` in this process."
  [kind]
  (a/reset-registry!)
  (case kind
    "orders" (do (a/set-app-info! {:name "orders" :description "Order service"})
                 (a/reg-tool :orders/place
                   {:description "Place an order. Answers its id."
                    :input [:map [:sku :string] [:qty [:int {:min 1}]]]
                    :handler (fn [{:keys [sku qty]} grant]
                               {:order (str sku "x" qty) :by (:actor grant)})}))
    "stock"  (do (a/set-app-info! {:name "stock" :description "Stock service"})
                 (a/reg-tool :stock/level
                   {:description "Units in stock of a sku."
                    :read-only? true
                    :input [:map [:sku :string]]
                    :handler (fn [{:keys [sku]} _] {:sku sku :units 42})})))
  :installed)
