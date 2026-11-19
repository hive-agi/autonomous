(ns autonomous.bridge.schema
  "Value objects of the bridge context (see autonomous.domain.schema for the
   shared language).

   Target       where runtimes are reached. :nrepl mode :shadow (a
                ClojureScript build) or :clj (a Clojure process), or :local
                (this JVM, for a backend embedding the bridge).
   TokenEntry   one credential: the SHA-256 of a bearer token, the actor it
                names and the scopes it grants. The token itself is never
                stored.
   Session      one agent's conversation: its grant and pinned runtime.
   LedgerEntry  a journal entry promoted for durable storage, keyed so that
                re-draining a journal never stores it twice.
   Config       everything `autonomous.cli serve` runs on."
  (:require [autonomous.domain.schema :as ds]))

(def Mode [:enum :shadow :clj])

(def NreplTarget
  [:map
   [:target/kind [:= :nrepl]]
   [:target/id :string]
   [:nrepl/host {:optional true} :string]
   [:nrepl/port {:optional true} [:maybe [:int {:min 1 :max 65535}]]]
   [:nrepl/port-file {:optional true} :string]
   [:nrepl/mode Mode]
   [:shadow/build {:optional true} [:maybe :keyword]]
   [:timeout-ms {:optional true} [:int {:min 100}]]])

(def LocalTarget
  [:map
   [:target/kind [:= :local]]
   [:target/id :string]])

(def Target
  [:multi {:dispatch :target/kind}
   [:nrepl NreplTarget]
   [:local LocalTarget]])

(def ^:private hex-chars (set "0123456789abcdef"))

(defn sha256-hex?
  "True for 64 lowercase hex characters."
  [s]
  (and (string? s) (= 64 (count s)) (every? hex-chars s)))

(def Sha256Hex [:fn {:error/message "64 lowercase hex characters"} sha256-hex?])

(def TokenEntry
  [:map
   [:sha256 Sha256Hex]
   [:actor ds/Actor]
   [:scopes [:or [:set :keyword] [:sequential :keyword]]]])

(def Session
  [:map
   [:session/id :string]
   [:session/actor ds/Actor]
   [:session/scopes ds/Scopes]
   [:session/runtime {:optional true} [:maybe :any]]])

(def LedgerEntry
  [:map
   [:entry/key :string]
   [:entry/target :string]
   [:entry/runtime :string]
   [:entry/seq :int]
   [:entry/at :int]
   [:entry/kind :keyword]
   [:entry/actor {:optional true} [:maybe :string]]
   [:entry/tool {:optional true} [:maybe :string]]
   [:entry/outcome {:optional true} [:maybe :keyword]]
   [:entry/data :string]])

(def LedgerQuery
  [:map
   [:actor {:optional true} :string]
   [:kind {:optional true} :keyword]
   [:tool {:optional true} :string]
   [:target {:optional true} :string]
   [:since {:optional true} :int]
   [:until {:optional true} :int]
   [:limit {:optional true} [:int {:min 1 :max 1000}]]])

(def HttpConfig
  [:map
   [:bind {:optional true} :string]
   [:port [:int {:min 0 :max 65535}]]
   [:allowed-origins {:optional true} [:vector :string]]])

(def LedgerConfig
  [:map
   [:ledger/kind [:enum :memory :datahike]]
   [:datahike/config {:optional true} :map]])

(def Config
  [:map
   [:target Target]
   [:transport [:enum :stdio :http]]
   [:http {:optional true} HttpConfig]
   [:tokens {:optional true} [:vector TokenEntry]]
   [:stdio/actor {:optional true} ds/Actor]
   [:stdio/scopes {:optional true} [:set :keyword]]
   [:ledger {:optional true} LedgerConfig]
   [:call-timeout-ms {:optional true} [:int {:min 100}]]])
