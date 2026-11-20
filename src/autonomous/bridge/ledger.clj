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
  "L1 Promote: identity of a journal entry across reloads: a reloaded
   runtime restarts its sequence, so the time joins it."
  [target runtime {:keys [seq at]}]
  (str target "|" runtime "|" at "|" seq))

(def ^:private optional-fields
  "L1 Promote: [journal-key entry-attr promote-fn] of the fields an entry
   may lack; a falsy value is left out."
  [[:actor :entry/actor str] [:tool :entry/tool str] [:outcome :entry/outcome identity]])

(defn- promote-optional
  "L1 Promote: `m` with each truthy optional field of `entry` promoted."
  [m entry]
  (reduce (fn [m [k attr f]] (if-let [v (get entry k)] (assoc m attr (f v)) m))
          m optional-fields))

(defn ->ledger-entry
  "L1 Promote: LedgerEntry for journal `entry` drained from `runtime` of
   `target`."
  [target runtime entry]
  (promote-optional {:entry/key     (entry-key target runtime entry)
                     :entry/target  target
                     :entry/runtime (str runtime)
                     :entry/seq     (:seq entry)
                     :entry/at      (:at entry)
                     :entry/kind    (:kind entry)
                     :entry/data    (wire/print-edn (apply dissoc entry promoted-keys))}
                    entry))

(defn- view-optional
  "L1 Promote: `view` with each truthy optional field of LedgerEntry `e`."
  [view e]
  (reduce (fn [v [k attr]] (if-let [x (get e attr)] (assoc v k x) v))
          view optional-fields))

(defn ->view
  "L1 Promote: what an agent reads for a LedgerEntry. Where it came from
   sits under :source and the entry's own fields under :data, so an app
   field never collides with a promoted one."
  [e]
  (let [data (wire/read-edn (:entry/data e))]
    (cond-> (view-optional {:source {:target (:entry/target e) :runtime (:entry/runtime e)}
                            :seq (:entry/seq e) :at (:entry/at e) :kind (:entry/kind e)}
                           e)
      (seq data) (assoc :data data))))

(def ^:private query-clauses
  "L1 Promote: LedgerQuery key -> (fn [query-value entry] bool), a closed set."
  {:actor  (fn [v e] (= v (:entry/actor e)))
   :kind   (fn [v e] (= v (:entry/kind e)))
   :tool   (fn [v e] (= v (:entry/tool e)))
   :target (fn [v e] (= v (:entry/target e)))
   :since  (fn [v e] (>= (:entry/at e) v))
   :until  (fn [v e] (< (:entry/at e) v))})

(defn matches?
  "L1 Promote: true when LedgerEntry `e` satisfies every clause LedgerQuery
   `q` gives; an absent (nil) clause matches anything."
  [q e]
  (every? (fn [[k pred]] (let [v (get q k)] (or (nil? v) (pred v e)))) query-clauses))

(defn select-entries
  "L1 Promote: entries matching `q`, newest first, at most (:limit q 100)."
  [q entries]
  (->> entries
       (filter #(matches? q %))
       (sort-by (juxt :entry/at :entry/seq) #(compare %2 %1))
       (take (:limit q 100))
       vec))

(defmulti make
  "L3 Boundary: an ILedger for config {:ledger/kind ...}. Adapters add a
   method (an open set)."
  :ledger/kind)

(def ^:private adapter-ns
  {:memory   'autonomous.bridge.ledger.memory
   :datahike 'autonomous.bridge.ledger.datahike})

(defn- unavailable
  "L3 Boundary: the :ledger/unavailable ex-info for an adapter of `cfg`'s
   kind that failed to load with `load-failure`."
  [cfg load-failure]
  (ex-info (str "ledger " (name (:ledger/kind cfg)) " cannot load on this runtime ("
                (ex-message load-failure) "); use --ledger memory, or run the bridge "
                "on the JVM with the adapter's alias (clojure -M:datahike:serve)")
           {:autonomous/error :ledger/unavailable :ledger/kind (:ledger/kind cfg)}
           load-failure))

(defn- load-adapter!
  "L3 Boundary: require the adapter namespace this library ships for
   `cfg`'s kind, if any; throws `unavailable` when it cannot load."
  [cfg]
  (when-let [ns-sym (adapter-ns (:ledger/kind cfg))]
    (try (require ns-sym)
         (catch Exception e (throw (unavailable cfg e))))))

(defn open
  "L3 Boundary: load the adapter namespace this library ships for `cfg`'s
   kind (a third party loads its own), then `make` it. An adapter this
   runtime cannot load (datahike on babashka or ClojureWasm) throws ex-info
   with :autonomous/error :ledger/unavailable and a message that says where
   it runs."
  [cfg]
  (load-adapter! cfg)
  (make cfg))
