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
  (:require [autonomous.domain.schema :as ds]
            [autonomous.domain.naming :as naming]))

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

(def WsTarget
  "Runtimes that dial the bridge over a websocket (production, no nREPL),
   authenticating with a runtime token whose actor is this :target/id."
  [:map
   [:target/kind [:= :ws]]
   [:target/id :string]
   [:timeout-ms {:optional true} [:int {:min 100}]]])

(def HttpTarget
  "A remote app reached by URL over MCP-over-HTTP (its own /mcp, the handler
   autonomous.ring mounts), so no nREPL and no inbound port. `:token-env`
   names the environment variable holding the bearer sent with each call;
   the token itself is never stored here."
  [:map
   [:target/kind [:= :http]]
   [:target/id :string]
   [:url {:optional true} :string]
   [:token-env {:optional true} :string]
   [:timeout-ms {:optional true} [:int {:min 100}]]])

(def Target
  [:multi {:dispatch :target/kind}
   [:nrepl NreplTarget]
   [:local LocalTarget]
   [:ws WsTarget]
   [:http HttpTarget]])

(def TargetId
  [:and :string [:fn {:error/message "a target id is 1..48 of [A-Za-z0-9._-]"}
                 naming/valid-target-id?]])

(def ProbeStatus [:enum :up :down :slow])

(def Probe
  "What one look at a target found: its manifest when :up, why not otherwise."
  [:map
   [:target TargetId]
   [:status ProbeStatus]
   [:at :int]
   [:manifest {:optional true} [:maybe :map]]
   [:error {:optional true} [:maybe :map]]])

(def FleetMode [:enum :single :fleet])

(def AppRow
  "One line of autonomous_apps."
  [:map
   [:app TargetId]
   [:prefix :string]
   [:status ProbeStatus]
   [:tools {:optional true} :int]
   [:version {:optional true} [:maybe :int]]
   [:description {:optional true} :string]
   [:dropped {:optional true} [:vector :string]]
   [:error {:optional true} :map]])

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
   [:session/runtime {:optional true} [:maybe :any]]
   [:session/runtimes {:optional true} [:map-of :string :any]]])

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
   [:datahike/config {:optional true} :map]
   [:datahike/path {:optional true} :string]])

(def Config
  "One :target serves single mode (names as the app gives them); :targets
   (two or more, or :fleet? true) serves a fleet (names prefixed by app)."
  [:and
   [:map
    [:target {:optional true} Target]
    [:targets {:optional true} [:vector {:min 1} Target]]
    [:fleet? {:optional true} :boolean]
    [:probe-ms {:optional true} [:int {:min 50}]]
    [:transport [:enum :stdio :http]]
    [:http {:optional true} HttpConfig]
    [:ws {:optional true} HttpConfig]
    [:tokens {:optional true} [:vector TokenEntry]]
    [:stdio/actor {:optional true} ds/Actor]
    [:stdio/scopes {:optional true} [:set :keyword]]
    [:ledger {:optional true} LedgerConfig]
    [:call-timeout-ms {:optional true} [:int {:min 100}]]]
   [:fn {:error/message "configure :target or :targets"}
    (fn [c] (or (:target c) (seq (:targets c))))]])
