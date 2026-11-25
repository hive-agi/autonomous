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

   Pure decisions live in autonomous.re-frame.model."
  (:require [autonomous.core :as core]
            [autonomous.domain.wire :as wire]
            [autonomous.re-frame.model :as model]
            [autonomous.runtime.invoke :as invoke]
            [autonomous.re-frame.trace :as rf-trace]
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

(defn- refuse-unregistered [kind id]
  (throw (ex-info (str "no re-frame " (name kind) " handler registered for " id)
                  {:autonomous/error :unregistered-handler :kind kind :id id})))

(defn- result-of
  "What an event tool answers after its dispatch."
  [tool args event]
  (cond
    (:result tool)      ((:result tool) @rf-db/app-db args)
    (:result-sub tool)  (let [q (:result-sub tool)] (read-sub (if (fn? q) (q args) q)))
    (:result-path tool) (get-in @rf-db/app-db (:result-path tool))
    :else               {:dispatched (wire/->wire event)}))

(defmethod invoke/invoke-kind :re-frame/event [tool args grant]
  (let [event ((:args->event tool) args)]
    (when-not (registered? :event (first event)) (refuse-unregistered :event (first event)))
    (let [tagged (model/tag-event event grant)]
      (if (:async? tool)
        (do (rf/dispatch tagged) {:queued (wire/->wire event)})
        (do (rf/dispatch-sync tagged) (result-of tool args event))))))

(defmethod invoke/invoke-kind :re-frame/sub [tool args _grant]
  (let [query ((:args->query tool) args)]
    (when-not (registered? :sub (first query)) (refuse-unregistered :sub (first query)))
    (read-sub query)))

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
  (core/reg-tool (or (:tool-id spec) event-id)
                 (merge (dissoc spec :tool-id)
                        {:kind :re-frame/event
                         :args->event (or (:args->event spec) #(model/default-event event-id %))})))

(defn reg-sub-tool
  "Expose re-frame subscription `query-id` as a read-only tool.
   spec: as reg-tool minus :handler, plus :tool-id and
   :args->query (fn [args] query-vector), default [id] / [id args]."
  [query-id spec]
  (core/reg-tool (or (:tool-id spec) query-id)
                 (merge {:read-only? true}
                        (dissoc spec :tool-id)
                        {:kind :re-frame/sub
                         :args->query (or (:args->query spec) #(model/default-query query-id %))})))

(defn expose-db!
  "Register app-db as a resource. opts: :uri (default re-frame://app-db),
   :paths (only these), :redact (paths shown as <redacted>), :scopes,
   :description."
  ([] (expose-db! {}))
  ([{:keys [uri paths redact scopes description]}]
   (core/reg-resource (or uri "re-frame://app-db")
                      (cond-> {:name "app-db"
                               :description (or description "The re-frame application state")
                               :read (fn [_] (model/db-view @rf-db/app-db paths redact))}
                        scopes (assoc :scopes scopes)))))

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
