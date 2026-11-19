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

(defn query-form
  "Datalog for LedgerQuery `q`: equality clauses for the given fields, range
   predicates for :since and :until. Answers [query args]. Pure."
  [q]
  (let [eqs    (for [[k attr] [[:actor :entry/actor] [:kind :entry/kind]
                               [:tool :entry/tool] [:target :entry/target]]
                     :when (some? (get q k))]
                 [attr (symbol (str "?" (name k))) (get q k)])
        ranges (cond-> []
                 (:since q) (conj ['(>= ?at ?since) '?since (:since q)])
                 (:until q) (conj ['(< ?at ?until) '?until (:until q)]))]
    [(vec (concat [:find '[(pull ?e pattern) ...]
                   :in '$ 'pattern]
                  (map second eqs) (map second ranges)
                  [:where '[?e :entry/key] '[?e :entry/at ?at]]
                  (map (fn [[attr v]] ['?e attr v]) eqs)
                  (map (fn [[pred]] [pred]) ranges)))
     (concat (map last eqs) (map last ranges))]))

(defrecord DatahikeLedger [conn]
  port/ILedger
  (-append! [_ entries]
    (try
      (let [before (d/q '[:find (count ?e) . :where [?e :entry/key]] @conn)]
        (d/transact conn {:tx-data (vec entries)})
        (answer/ok (- (or (d/q '[:find (count ?e) . :where [?e :entry/key]] @conn) 0) (or before 0))))
      (catch Exception e (answer/refusal :ledger/write-failed (ex-message e)))))
  (-query [_ q]
    (try
      (let [[form args] (query-form q)]
        (answer/ok (ledger/select-entries q (apply d/q form @conn pull-pattern args))))
      (catch Exception e (answer/refusal :ledger/query-failed (ex-message e))))))

(def default-config
  {:store {:backend :memory :id #uuid "00000000-0000-0000-0000-00000000a170"}
   :schema-flexibility :write
   :keep-history? true})

(defn store-config
  "Datahike config for ledger `cfg`: an explicit :datahike/config, a file
   store at :datahike/path (its id derived from the path, so a restart
   reopens the same ledger), else a fresh in-memory store."
  [cfg]
  (cond
    (:datahike/config cfg) (:datahike/config cfg)
    (:datahike/path cfg)   (let [p (:datahike/path cfg)]
                             (assoc default-config :store
                                    {:backend :file :path p
                                     :id (java.util.UUID/nameUUIDFromBytes (.getBytes ^String p "UTF-8"))}))
    :else                  (assoc-in default-config [:store :id] (java.util.UUID/randomUUID))))

(defmethod ledger/make :datahike [cfg]
  (let [dcfg (store-config cfg)]
    (when-not (d/database-exists? dcfg)
      (d/create-database dcfg))
    (let [conn (d/connect dcfg)]
      (d/transact conn {:tx-data schema})
      (->DatahikeLedger conn))))
