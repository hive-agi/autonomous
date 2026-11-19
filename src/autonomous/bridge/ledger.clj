(ns autonomous.bridge.ledger
  "The Ledger: every runtime's journal, drained into one durable, queryable
   record. A swarm's shared memory of who did what, surviving page reloads
   and bridge restarts (with a durable adapter).

   Pure here: promoting a journal entry to a LedgerEntry, and selecting
   entries for a query. Adapters implement autonomous.bridge.port/ILedger and
   register with `make` (open multimethod on :ledger/kind)."
  (:require [autonomous.domain.wire :as wire]))

(def promoted-keys #{:seq :at :kind :actor :tool :outcome})

(defn entry-key
  "Identity of a journal entry across reloads: a reloaded runtime restarts
   its sequence, so the time joins it."
  [target runtime {:keys [seq at]}]
  (str target "|" runtime "|" at "|" seq))

(defn ->ledger-entry
  "LedgerEntry for journal `entry` drained from `runtime` of `target`."
  [target runtime entry]
  (cond-> {:entry/key     (entry-key target runtime entry)
           :entry/target  target
           :entry/runtime (str runtime)
           :entry/seq     (:seq entry)
           :entry/at      (:at entry)
           :entry/kind    (:kind entry)
           :entry/data    (wire/print-edn (apply dissoc entry promoted-keys))}
    (:actor entry)   (assoc :entry/actor (str (:actor entry)))
    (:tool entry)    (assoc :entry/tool (str (:tool entry)))
    (:outcome entry) (assoc :entry/outcome (:outcome entry))))

(defn ->view
  "What an agent reads for a LedgerEntry. Where it came from sits under
   :source and the entry's own fields under :data, so an app field never
   collides with a promoted one."
  [e]
  (let [data (wire/read-edn (:entry/data e))]
    (cond-> {:source {:target (:entry/target e) :runtime (:entry/runtime e)}
             :seq (:entry/seq e) :at (:entry/at e) :kind (:entry/kind e)}
      (:entry/actor e)   (assoc :actor (:entry/actor e))
      (:entry/tool e)    (assoc :tool (:entry/tool e))
      (:entry/outcome e) (assoc :outcome (:entry/outcome e))
      (seq data)         (assoc :data data))))

(defn matches?
  "True when LedgerEntry `e` satisfies LedgerQuery `q`."
  [q e]
  (and (or (nil? (:actor q))  (= (:actor q) (:entry/actor e)))
       (or (nil? (:kind q))   (= (:kind q) (:entry/kind e)))
       (or (nil? (:tool q))   (= (:tool q) (:entry/tool e)))
       (or (nil? (:target q)) (= (:target q) (:entry/target e)))
       (or (nil? (:since q))  (>= (:entry/at e) (:since q)))
       (or (nil? (:until q))  (< (:entry/at e) (:until q)))))

(defn select-entries
  "Entries matching `q`, newest first, at most (:limit q 100)."
  [q entries]
  (->> entries
       (filter #(matches? q %))
       (sort-by (juxt :entry/at :entry/seq) #(compare %2 %1))
       (take (:limit q 100))
       vec))

(defmulti make
  "An ILedger for config {:ledger/kind ...}. Adapters add a method."
  :ledger/kind)

(def ^:private adapter-ns
  {:memory   'autonomous.bridge.ledger.memory
   :datahike 'autonomous.bridge.ledger.datahike})

(defn open
  "Load the adapter namespace this library ships for `cfg`'s kind (a third
   party loads its own), then `make` it."
  [cfg]
  (when-let [ns-sym (adapter-ns (:ledger/kind cfg))]
    (require ns-sym))
  (make cfg))
