(ns autonomous.domain.schema
  "Ubiquitous language of autonomous, as malli value objects.

   Affordance  something an application lets an agent DO (a Tool) or SEE (a
               Resource). The application declares its affordances; agents
               only ever reach the application through them.
   Tool        an affordance that acts: name, description, input schema,
               required scopes, and an invocation strategy (its :kind).
   Resource    an affordance that is read: a URI, a description, a reader.
   Scope       a permission keyword: :read, :write, :admin (implies every
               scope) or any keyword the application invents.
   Grant       who asks and what they may do: {:actor :scopes}.
   Lease       one actor's exclusive right to WRITE to a runtime until a
               time. Reads stay open to every actor.
   Request     one message to a runtime, selected by :op.
   Answer      the runtime's reply: Ok, Pending (collect later) or Refusal.
   Plan        the pure decision about a call: invoke the tool, or refuse.
   Entry       one journal line: who did what, with which outcome.
   Journal     the runtime's bounded, sequenced memory of entries.
   Manifest    the affordances one Grant may use.
   Runtime     one running program carrying a registry (a tab, a process).
   Target      where runtimes are reached: an nREPL endpoint, or in-process.
   Session     one agent's conversation with a Target: a Grant and the
               Runtime it is pinned to.
   Ledger      the durable, queryable record of journal entries across
               runtimes, sessions and reloads (bridge side)."
  (:require [autonomous.domain.naming :as naming]))

;; =============================================================================
;; Identity and permission
;; =============================================================================

(def Scope :keyword)

(def Scopes [:set Scope])

(def Actor [:string {:min 1 :max 128}])

(def Grant
  [:map
   [:actor Actor]
   [:scopes Scopes]
   [:session {:optional true} [:maybe :string]]])

(def Millis [:int {:min 0}])

(def Lease
  [:maybe [:map [:actor Actor] [:until Millis]]])

;; =============================================================================
;; Affordances
;; =============================================================================

(def ToolName
  "MCP tool names: [A-Za-z0-9_-], 1..64 characters."
  [:and :string [:fn {:error/message "a tool name is 1..64 of [A-Za-z0-9_-]"}
                 naming/valid-tool-name?]])

(def ToolId [:or :keyword :string])

(def ToolSpec
  "What an application author writes in `reg-tool`."
  [:map
   [:description [:string {:min 1}]]
   [:title {:optional true} :string]
   [:name {:optional true} ToolName]
   [:kind {:optional true} :keyword]
   [:handler {:optional true} fn?]
   [:input {:optional true} :any]
   [:output {:optional true} :any]
   [:json-schema {:optional true} map?]
   [:read-only? {:optional true} :boolean]
   [:scopes {:optional true} [:or [:set :keyword] [:sequential :keyword]]]
   [:annotations {:optional true} [:map-of :keyword :any]]])

(def Tool
  "A normalized, registered tool. Carries compiled malli functions."
  [:map
   [:id ToolId]
   [:name ToolName]
   [:kind :keyword]
   [:description :string]
   [:scopes Scopes]
   [:read-only? :boolean]
   [:json-schema map?]
   [:annotations map?]
   [:decode fn?]
   [:explain fn?]])

(def ResourceSpec
  [:map
   [:read fn?]
   [:name {:optional true} :string]
   [:description {:optional true} :string]
   [:mime {:optional true} :string]
   [:scopes {:optional true} [:or [:set :keyword] [:sequential :keyword]]]])

(def Resource
  [:map
   [:uri :string]
   [:name :string]
   [:description :string]
   [:mime :string]
   [:scopes Scopes]
   [:read fn?]])

(def AppInfo
  [:map
   [:name :string]
   [:description {:optional true} :string]
   [:version {:optional true} :string]])

;; =============================================================================
;; Answers, plans, journal
;; =============================================================================

(def ErrorType :keyword)

(def Refusal
  [:map [:error [:map
                 [:type ErrorType]
                 [:message :string]
                 [:data {:optional true} :any]]]])

(def Ok [:map [:ok :any]])

(def Pending [:map [:pending :string]])

(def Answer [:or Ok Pending Refusal])

(def Plan
  "Closed set of decisions the domain hands the runtime pipeline."
  [:multi {:dispatch :plan/action}
   [:invoke [:map [:plan/action [:= :invoke]] [:tool :map] [:args :any]]]
   [:read [:map [:plan/action [:= :read]] [:resource :map]]]
   [:refuse [:map [:plan/action [:= :refuse]] [:refusal Refusal]]]])

(def Entry
  [:map
   [:seq [:int {:min 1}]]
   [:at Millis]
   [:kind :keyword]
   [:actor {:optional true} [:maybe :string]]])

(def Journal
  [:map
   [:seq [:int {:min 0}]]
   [:entries [:vector Entry]]])

(def CallDetail
  "What an app lets the journal keep about each tool call, beyond actor, tool,
   outcome and duration. Every flag is off by default."
  [:map {:closed true}
   [:args? {:optional true} :boolean]
   [:errors? {:optional true} :boolean]
   [:redact {:optional true} [:sequential [:vector {:min 1} :any]]]])

;; =============================================================================
;; Requests
;; =============================================================================

(def ^:private request-base
  [[:actor {:optional true} [:maybe :string]]
   [:scopes {:optional true} [:or [:set :any] [:sequential :any]]]
   [:session {:optional true} [:maybe :string]]])

(defn- op [k & entries]
  [k (into [:map [:op [:= k]]] (concat entries request-base))])

(def Request
  [:multi {:dispatch :op}
   (op :ping)
   (op :manifest)
   (op :call [:tool :string] [:args {:optional true} [:maybe [:map-of :any :any]]])
   (op :read [:uri :string])
   (op :poll [:id :string])
   (op :journal [:since {:optional true} [:maybe :int]] [:limit {:optional true} [:maybe :int]])
   (op :lease [:ttl-ms {:optional true} [:maybe [:int {:min 1}]]])
   (op :release)
   (op :trace [:since {:optional true} [:maybe :int]] [:limit {:optional true} [:maybe :int]])
   (op :trace-mark [:label [:string {:min 1 :max 200}]]
       [:attrs {:optional true} [:maybe [:map-of :any :any]]])
   (op :trace-config [:enabled? {:optional true} :boolean] [:payloads? {:optional true} :boolean]
       [:cap {:optional true} [:int {:min 1 :max 100000}]])])

(def Manifest
  [:map
   [:protocol :int]
   [:version :int]
   [:app :map]
   [:tools [:vector [:map [:name ToolName] [:description :string] [:input-schema map?]]]]
   [:resources [:vector [:map [:uri :string] [:name :string]]]]])
