(ns autonomous.bridge.ledger.memory
  "In-memory ILedger: the zero-dependency default. Bounded to the newest
   `:ledger/cap` entries (default 10000); lost when the bridge stops."
  (:require [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.port :as port]
            [autonomous.domain.answer :as answer]))

(def default-cap 10000)

(defn- trim [m cap]
  (if (<= (count m) cap)
    m
    (let [keep-keys (->> (vals m) (sort-by :entry/at) (take-last cap) (map :entry/key))]
      (select-keys m keep-keys))))

(defrecord MemoryLedger [store cap]
  port/ILedger
  (-append! [_ entries]
    (let [before (count @store)]
      (swap! store (fn [m] (trim (into m (map (juxt :entry/key identity)) entries) cap)))
      (answer/ok (max 0 (- (count @store) before)))))
  (-query [_ q]
    (answer/ok (ledger/select-entries q (vals @store)))))

(defmethod ledger/make :memory [cfg]
  (->MemoryLedger (atom {}) (:ledger/cap cfg default-cap)))
