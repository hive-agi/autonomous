(ns autonomous.core
  "Public API of the in-app runtime. An application declares what it lets an
   agent do (tools) and see (resources); a bridge carries agent requests to
   `rpc`. Everything here delegates to the strata below:

     autonomous.domain.*   pure value objects and decisions (malli)
     autonomous.runtime.*  registry state, invocation pipeline, rpc boundary

   Minimal use:

     (autonomous.core/set-app-info! {:name \"todo\" :description \"A todo list\"})
     (autonomous.core/reg-tool :todo/add
       {:description \"Add a todo item\"
        :input [:map [:text [:string {:min 1}]]]
        :handler (fn [{:keys [text]} _grant] (add! text))})"
  (:require [autonomous.runtime.invoke :as invoke]
            [autonomous.runtime.registry :as registry]
            [autonomous.runtime.rpc :as rpc]
            [autonomous.decorator :as decorator]))

(defn reg-tool
  "Register (or replace) a tool. `id` is a keyword; its MCP name derives
   from it (:todo/add is todo_add) unless spec gives :name.

   spec (autonomous.domain.schema/ToolSpec):
     :description  what the tool does, for the agent        [required]
     :input        malli schema of the args map             [default [:map]]
     :output       malli schema of the result (advertised)
     :handler      (fn [args grant] value|promise)          [for :kind :fn]
     :kind         invocation strategy                      [default :fn]
     :read-only?   true when it changes nothing             [default false]
     :scopes       scopes required        [default #{:write}, #{:read} if read-only]
     :title, :annotations (MCP), :json-schema (override the derived one)

   Answers the MCP name. Throws ex-info on an invalid spec."
  [id spec]
  (registry/add-tool! id spec))

(defn unreg-tool
  "Unregister a tool by id or MCP name."
  [id-or-name]
  (registry/remove-tool! id-or-name))

(defn reg-resource
  "Register (or replace) a read-only resource at `uri`.
   spec: :read (fn [grant] value) [required], :name, :description, :mime,
   :scopes [default #{:read}]."
  [uri spec]
  (registry/add-resource! uri spec))

(defn unreg-resource [uri]
  (registry/remove-resource! uri))

(defn set-app-info!
  "Describe the application: {:name :description :version}. The description
   is the first thing an agent reads: say what the app is for and how its
   tools fit together."
  [info]
  (registry/set-app! info))

(defn use!
  "Add middleware around every invocation:
   (fn [handler] (fn [tool args grant] ...)). A shim over the call chain:
   each wrapper becomes one link; the last registered runs outermost."
  [mw]
  (registry/add-middleware! mw))

(defn intercept!
  "Append one Interceptor map {:name kw :enter? :leave? :error?}
   (autonomous.surface/Interceptor) to the call chain. :enter and :leave
   take the call context {:tool :args :grant}, :error takes [context
   refusal]; each returns the context. Throws on an invalid interceptor or
   a :name already in the chain."
  [interceptor]
  (registry/add-interceptor! interceptor))

(defn decorate!
  "Decorate the surface agents see with Surface -> Surface fns, applied
   left to right after earlier ones (see autonomous.decorator; lift a
   Tool -> Tool fn with `each-tool`). They persist: tools and resources
   registered later are decorated too. Throws, changing nothing, when a
   result is not a valid Surface."
  [& decorators]
  (registry/decorate! decorators))

(def each-tool
  "Lift a Tool -> Tool fn to a Surface -> Surface fn; see autonomous.decorator."
  decorator/each-tool)

(defn journal!
  "Append an application event to the journal agents read (e.g. what the
   human just did). `entry` is a map with :kind. Answers its sequence
   number."
  [entry]
  (registry/record! entry))

(defn journal-calls!
  "Choose what the journal (and so the ledger) keeps about each tool call
   beyond actor, tool, outcome and duration. Off by default.

   detail (autonomous.domain.schema/CallDetail), or nil to turn it off:
     :args?    the args as the agent sent them              [default false]
     :answers? an Ok call's value, bounded                  [default false]
     :errors?  a refusal's type, message and data           [default false]
     :redact   paths whose values are replaced by \"<redacted>\": into the
               args, and (a path that starts with :answer) into the answer

   Any detail also records the caller's session. Turn :args? or :answers? on
   only when the journal may hold what agents send or get back: redact
   secrets and personal data. Throws ex-info on an invalid detail."
  [detail]
  (registry/set-call-detail! detail))

(def invoke-kind
  "The open multimethod of tool kinds; see autonomous.runtime.invoke."
  invoke/invoke-kind)

(defn rpc
  "Answer one Request map; see autonomous.runtime.rpc."
  [req]
  (rpc/rpc req))

(defn ^:export rpc-str
  "Answer one EDN Request text with EDN Answer text."
  [s]
  (rpc/rpc-str s))

(defn reset-registry!
  "Forget every registration and all runtime state."
  []
  (registry/clear!))
