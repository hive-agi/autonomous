(ns autonomous.re-frame
  "re-frame adapter: make a re-frame app agent-operable with the vocabulary
   it already has.

     (autonomous.re-frame/reg-event-tool :todo/add
       {:description \"Add a todo item\"
        :input [:map [:text [:string {:min 1}]]]
        :args->event (fn [{:keys [text]}] [:todo/add text])
        :result-sub [:todos/visible]})

     (autonomous.re-frame/reg-sub-tool :todos/visible
       {:description \"The todos currently shown\"})

     (autonomous.re-frame/observe-events! {:payloads? false})
     (autonomous.re-frame/expose-db! {:paths [[:todos] [:filter]]})

   Events run through re-frame (dispatch-sync by default), so interceptors,
   effects and the UI behave exactly as for a human. The calling actor rides
   on the event's metadata; the observer journals each event with its
   origin, so agents see what the human did and the human's tools (10x,
   re-frisk) see what agents did.

   For re-frame2, whose app-db lives per frame, use autonomous.re-frame2:
   reg-event-tool, reg-sub-tool and expose-db!, with a :frame option. It
   has no observe-events! or install-dev-tools! yet. This namespace has no
   frames, so a :frame is refused here.

   Pure decisions live in autonomous.re-frame.model; the tool-building code
   both adapters share lives in autonomous.re-frame.kernel."
  (:require [autonomous.core :as core]
            [autonomous.domain.wire :as wire]
            [autonomous.re-frame.kernel :as kernel]
            [autonomous.re-frame.model :as model]
            [autonomous.re-frame.trace :as rf-trace]
            [autonomous.spi :as spi]
            [autonomous.trace.browser :as browser]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]
            [re-frame.registrar :as registrar]
            [reagent.ratom :as ratom]))

(defn- registered? [kind id]
  (some? (registrar/get-handler kind id)))

(defn- read-sub
  "Value of re-frame `query`, read with a component's lifecycle: pending
   reactions are flushed (no stale cache), the subscription is derefed
   inside an auto-run reaction (a reactive context, so re-frame does not
   warn), and that reaction is disposed at once, so the subscription leaves
   re-frame's cache unless a mounted component still holds it."
  [query]
  (ratom/flush!)
  (let [r (ratom/make-reaction #(deref (rf/subscribe query)) :auto-run true)]
    (try @r
         (finally (ratom/dispose! r)))))

(def host
  "The kernel Host for re-frame 1: one global app-db, no frames."
  {:frames?       false
   :registered?   (fn [kind id _frame] (registered? kind id))
   :dispatch      (fn [event _frame] (rf/dispatch event))
   :dispatch-sync (fn [event _frame] (rf/dispatch-sync event))
   :read-sub      (fn [query _frame] (read-sub query))
   :app-db        (fn [_frame] @rf-db/app-db)
   :check-frame   (fn [frame] (kernel/checked-frame! {:frames? false} frame))})

(def ^:private adapters
  (kernel/adapters host {:source-kind :autonomous.re-frame/declarations
                         :event-kind  :re-frame/event
                         :sub-kind    :re-frame/sub}))

(def event-codec kernel/event-codec)

(def subscription-codec kernel/subscription-codec)

(def re-frame-adapter
  "L3 Source of explicit {:events {id spec} :subs {id spec}} declarations.
   Each spec opts in with :expose true; codec adapters are `event-adapter`
   and `subscription-adapter`."
  (:source adapters))

(def event-adapter
  "L3 re-frame event Codec and its host dispatch port."
  (:event adapters))

(def subscription-adapter
  "L3 re-frame subscription Codec and its host query port."
  (:sub adapters))

(spi/register-adapter! re-frame-adapter)
(spi/register-adapter! event-adapter)
(spi/register-adapter! subscription-adapter)

(defn reg-event-tool
  "Expose re-frame event `event-id` as a tool.

   spec: everything reg-tool takes except :handler, plus
     :tool-id      tool id (default `event-id`)
     :args->event  (fn [args] event-vector)  default [id] / [id args]
     :async?       dispatch (queued) instead of dispatch-sync
     :result       (fn [db args] value) answered after the dispatch, or
     :result-sub   query vector (or (fn [args] query)) to deref after, or
     :result-path  app-db path to read after
   Answers the MCP tool name."
  [event-id spec]
  (kernel/reg-event-tool host :re-frame/event event-id spec))

(defn reg-sub-tool
  "Expose re-frame subscription `query-id` as a read-only tool.
   spec: as reg-tool minus :handler, plus :tool-id and
   :args->query (fn [args] query-vector), default [id] / [id args]."
  [query-id spec]
  (kernel/reg-sub-tool host :re-frame/sub query-id spec))

(defn expose-db!
  "Register app-db as a resource. opts: :uri (default re-frame://app-db),
   :paths (only these), :redact (paths shown as <redacted>), :scopes,
   :description."
  ([] (expose-db! {}))
  ([opts] (kernel/expose-db! host opts)))

(defonce ^:private !observing? (atom false))

(defn observe-events!
  "Journal every re-frame event with its origin (:agent or :app).
   opts: :payloads? keep event arguments (default false: only the id),
   :ignore set of event ids not to journal (e.g. high-frequency ticks).
   Calling it again (hot reload, a reinstall) replaces the observer."
  ([] (observe-events! {}))
  ([{:keys [payloads? ignore]}]
   (when @!observing? (rf/remove-post-event-callback ::journal))
   (rf/add-post-event-callback
    ::journal
    (fn [event _queue]
      (when-not (contains? (set ignore) (first event))
        (core/journal! (model/event-entry event (meta event) payloads?)))))
   (reset! !observing? true)
   nil))

(defn install-tracing!
  "Record the page as OpenTelemetry spans: clicks and other UI events,
   every re-frame event (what it changed in app-db, its effects, the
   events it dispatched, each a child of its cause), fetch and XHR calls
   (carrying `traceparent` to same-origin servers), uncaught errors and
   console.error, and agent tool calls. Agents read them with
   autonomous_trace; the bridge exports them (OTLP, files).
   opts: those of autonomous.trace.browser/install! (:payloads? :cap
   :dom? :fetch? :xhr? :errors? :console? :propagate?)."
  ([] (install-tracing! {}))
  ([opts]
   (browser/install! opts)
   (rf-trace/install! opts)
   nil))

(defn- refuse-unregistered [kind id]
  (throw (ex-info (str "no re-frame " (name kind) " handler registered for " id)
                  {:autonomous/error :unregistered-handler :kind kind :id id})))

(defn install-dev-tools!
  "Register generic, :admin-scoped tools for development: dispatch any
   event, read any app-db path, list registered handlers. Never install in
   production; an :admin grant is required to see or call them."
  []
  (core/reg-tool :re-frame/dispatch
                 {:description "Dispatch any re-frame event, given as EDN text, e.g. \"[:todo/add \\\"milk\\\"]\". Development only."
                  :scopes [:admin]
                  :input [:map [:event :string]]
                  :handler (fn [{:keys [event]} grant]
                             (let [ev (wire/read-edn event)]
                               (when-not (registered? :event (first ev)) (refuse-unregistered :event (first ev)))
                               (rf/dispatch-sync (model/tag-event ev grant))
                               {:dispatched ev}))})
  (core/reg-tool :re-frame/db
                 {:description "Read app-db at an EDN path, e.g. \"[:todos]\"; omit path for all of it. Development only."
                  :scopes [:admin]
                  :read-only? true
                  :input [:map [:path {:optional true} :string]]
                  :handler (fn [{:keys [path]} _]
                             (get-in @rf-db/app-db (if path (wire/read-edn path) [])))})
  (core/reg-tool :re-frame/registry
                 {:description "List the registered re-frame event and subscription ids. Development only."
                  :scopes [:admin]
                  :read-only? true
                  :handler (fn [_ _]
                             {:events (vec (sort (map str (keys (get @registrar/kind->id->handler :event)))))
                              :subs   (vec (sort (map str (keys (get @registrar/kind->id->handler :sub)))))})}))
