(ns inventory.system
  "Composition root of the inventory service: a seeded stock table with
   its agent surface installed. `bb nrepl-server` serves it (see boot.clj
   and README.md); the bridge reaches it through .nrepl-port."
  (:require [inventory.agentic :as agentic]
            [inventory.service :as svc]))

(def seed
  "Demo stock, so an agent has something to read."
  [{:sku "kbd-60"  :on-hand 40 :reserved 5 :reorder-at 10}
   {:sku "mouse-v" :on-hand 8  :reserved 2 :reorder-at 10}
   {:sku "cable-c" :on-hand 200 :reserved 0 :reorder-at 50}])

(defn start!
  "A running inventory: {:db db}, seeded, surface installed. `opts` go to
   inventory.agentic/install!."
  ([] (start! {}))
  ([opts]
   (let [db (svc/db seed)]
     (agentic/install! db opts)
     {:db db})))
