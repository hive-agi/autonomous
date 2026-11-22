(ns autonomous.bridge.ledger.datahike
  "Datahike ILedger: the durable swarm ledger. Entries are upserted on
   :entry/key (idempotent re-drains), history is kept, so `as-of` and
   `history` queries answer what any agent did at any past time.

   Requires io.replikativ/datahike on the classpath (alias :datahike).
   Config: {:ledger/kind :datahike :datahike/config <datahike config>}; the
   default config is an in-memory store, pass a :file backend to persist."
  (:require [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.port :as port]
            [autonomous.domain.answer :as answer]
            [datahike.api :as d]))

(def schema
  (let [attr (fn [ident type & {:as more}]
               (merge {:db/ident ident :db/valueType type :db/cardinality :db.cardinality/one} more))]
    [(attr :entry/key :db.type/string :db/unique :db.unique/identity)
     (attr :entry/target :db.type/string :db/index true)
     (attr :entry/runtime :db.type/string)
     (attr :entry/seq :db.type/long)
     (attr :entry/at :db.type/long :db/index true)
     (attr :entry/kind :db.type/keyword :db/index true)
     (attr :entry/actor :db.type/string :db/index true)
     (attr :entry/tool :db.type/string :db/index true)
     (attr :entry/outcome :db.type/keyword)
     (attr :entry/data :db.type/string)]))

(def pull-pattern
  [:entry/key :entry/target :entry/runtime :entry/seq :entry/at :entry/kind
   :entry/actor :entry/tool :entry/outcome :entry/data])

(def ^:private equality-attrs
  "LedgerQuery key and the attribute it must equal."
  [[:actor :entry/actor] [:kind :entry/kind] [:tool :entry/tool] [:target :entry/target]])

(defn- equality-clauses
  "L1 Promote: [attr ?var value] for each equality field `q` gives."
  [q]
  (for [[k attr] equality-attrs
        :when (some? (get q k))]
    [attr (symbol (str "?" (name k))) (get q k)]))

(defn- range-clauses
  "L1 Promote: [predicate ?var value] for :since and :until when `q` gives them."
  [q]
  (cond-> []
    (:since q) (conj ['(>= ?at ?since) '?since (:since q)])
    (:until q) (conj ['(< ?at ?until) '?until (:until q)])))

(defn- datalog
  "L1 Promote: the pull query binding `eqs` and `ranges` as inputs."
  [eqs ranges]
  (vec (concat [:find '[(pull ?e pattern) ...] :in '$ 'pattern]
               (map second eqs) (map second ranges)
               [:where '[?e :entry/key] '[?e :entry/at ?at]]
               (map (fn [[attr v]] ['?e attr v]) eqs)
               (map (fn [[pred]] [pred]) ranges))))

(defn query-form
  "L1 Promote: Datalog for LedgerQuery `q`: equality clauses for the given
   fields, range predicates for :since and :until. Answers [query args]."
  [q]
  (let [eqs (equality-clauses q) ranges (range-clauses q)]
    [(datalog eqs ranges) (concat (map last eqs) (map last ranges))]))

(defn- entry-count
  "L3 Boundary: how many entries database `db` holds."
  [db]
  (or (d/q '[:find (count ?e) . :where [?e :entry/key]] db) 0))

(defn- append!
  "L3 Boundary: upsert `entries` into `conn`; Ok of how many were new."
  [conn entries]
  (let [before (entry-count @conn)]
    (d/transact conn {:tx-data (vec entries)})
    (answer/ok (- (entry-count @conn) before))))

(defn- query
  "L3 Boundary: Ok of the entries in `conn` matching LedgerQuery `q`."
  [conn q]
  (let [[form args] (query-form q)]
    (answer/ok (ledger/select-entries q (apply d/q form @conn pull-pattern args)))))

(defn- guarded
  "L3 Boundary: (f), or a refusal of `type` carrying what it threw."
  [type f]
  (try (f) (catch Exception e (answer/refusal type (ex-message e)))))

(defrecord DatahikeLedger [conn]
  port/ILedger
  (-append! [_ entries] (guarded :ledger/write-failed #(append! conn entries)))
  (-query [_ q] (guarded :ledger/query-failed #(query conn q))))

(def default-config
  {:store {:backend :memory :id #uuid "00000000-0000-0000-0000-00000000a170"}
   :schema-flexibility :write
   :keep-history? true})

(defn- file-store
  "L1 Promote: the config of a file store at `path`, its id derived from
   the path so a restart reopens the same ledger."
  [^String path]
  (assoc default-config :store {:backend :file :path path
                                :id (java.util.UUID/nameUUIDFromBytes (.getBytes path "UTF-8"))}))

(defn store-config
  "L1 Promote: Datahike config for ledger `cfg`: an explicit
   :datahike/config, a file store at :datahike/path, else a fresh
   in-memory store."
  [cfg]
  (cond
    (:datahike/config cfg) (:datahike/config cfg)
    (:datahike/path cfg)   (file-store (:datahike/path cfg))
    :else                  (assoc-in default-config [:store :id] (java.util.UUID/randomUUID))))

(defn- connect!
  "L3 Boundary: a connection to the database of `dcfg`, created and given
   the ledger schema as needed."
  [dcfg]
  (when-not (d/database-exists? dcfg) (d/create-database dcfg))
  (doto (d/connect dcfg) (d/transact {:tx-data schema})))

(defmethod ledger/make :datahike [cfg]
  (->DatahikeLedger (connect! (store-config cfg))))
