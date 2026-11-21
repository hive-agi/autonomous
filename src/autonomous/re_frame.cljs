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
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]
            [re-frame.registrar :as registrar]
            [reagent.ratom :as ratom]
            [autonomous.domain.answer :as answer]
            [autonomous.spi :as spi]
            [autonomous.surface :as surface]
            [goog.object :as gobj])
  (:require-macros [autonomous.domain.answer :as answer]))

(defn- registered? [kind id]
  (some? (registrar/get-handler kind id)))

(defn- refuse-unregistered [kind id]
  (throw (ex-info (str "no re-frame " (name kind) " handler registered for " id)
                  {:autonomous/error :unregistered-handler :kind kind :id id})))

(def ^:private current-frame-path (model/js-path 're-frame.frame/*current-frame*))
(def ^:private app-db-value-path (model/js-path 're-frame.core/app-db-value))

(defn- refuse-without-frames [frame]
  (throw (ex-info (str "the :frame option " (pr-str frame) " needs re-frame2 (re-frame.core/with-frame, app-db-value); it is not loaded")
                  {:autonomous/error :re-frame/no-frames :frame frame})))

(defn- with-frame
  "Run `f` pinned to re-frame2 frame `frame`, as re-frame.core/with-frame
   does (it binds re-frame.frame/*current-frame*). re-frame2 is resolved
   at run time, so a re-frame 1 app compiles unchanged; a nil `frame` just
   calls `f`."
  [frame f]
  (if (nil? frame)
    (f)
    (let [holder (gobj/getValueByKeys js/goog.global (into-array (pop current-frame-path)))
          k      (peek current-frame-path)]
      (when-not (and holder (js-in k holder)) (refuse-without-frames frame))
      (let [prev (gobj/get holder k)]
        (gobj/set holder k frame)
        (try (f)
             (finally (gobj/set holder k prev)))))))

(defn- app-db-value
  "The app-db value of re-frame2 frame `frame` (re-frame.core/app-db-value),
   or of re-frame's one app-db when `frame` is nil."
  [frame]
  (if (nil? frame)
    @rf-db/app-db
    (let [f (gobj/getValueByKeys js/goog.global (into-array app-db-value-path))]
      (when-not (fn? f) (refuse-without-frames frame))
      (f frame))))

(defn- checked-frame
  "`frame`, after refusing one that is not a frame-id keyword."
  [frame]
  (when-let [problem (model/frame-problem frame)]
    (throw (ex-info problem {:autonomous/error :invalid-tool :frame frame})))
  frame)

(defn- read-sub
  "Value of re-frame `query`, read with a component's lifecycle: pending
   reactions are flushed (no stale cache), the subscription is derefed
   inside an auto-run reaction (a reactive context, so re-frame does not
   warn), and that reaction is disposed at once, so the subscription leaves
   re-frame's cache unless a mounted component still holds it. With a
   re-frame2 `frame` the subscribe and its deref are pinned to it."
  ([query] (read-sub query nil))
  ([query frame]
   (ratom/flush!)
   (with-frame frame
     (fn []
       (let [r (ratom/make-reaction #(deref (rf/subscribe query)) :auto-run true)]
         (try @r
              (finally (ratom/dispose! r))))))))

(defn- result-of
  "What an event tool answers after its dispatch, read from the tool's
   :frame when it names one."
  [tool args event]
  (let [frame (:frame tool)]
    (cond
      (:result tool)      ((:result tool) (app-db-value frame) args)
      (:result-sub tool)  (let [q (:result-sub tool)] (read-sub (if (fn? q) (q args) q) frame))
      (:result-path tool) (get-in (app-db-value frame) (:result-path tool))
      :else               {:dispatched (wire/->wire event)})))

(defn- event-call
  "L3 Pipeline: dispatch an encoded event, pinned to the tool's :frame when
   it names one, and produce its declared result."
  [tool args event grant]
  (when-not (registered? :event (first event)) (refuse-unregistered :event (first event)))
  (let [tagged (model/tag-event event grant)]
    (if (:async? tool)
      (do (with-frame (:frame tool) #(rf/dispatch tagged)) {:queued (wire/->wire event)})
      (do (with-frame (:frame tool) #(rf/dispatch-sync tagged)) (result-of tool args event)))))

(defn- subscription-call
  "L3 Pipeline: read an encoded query through a component lifecycle, in
   re-frame2 frame `frame` when one is given."
  ([query] (subscription-call query nil))
  ([query frame]
   (when-not (registered? :sub (first query)) (refuse-unregistered :sub (first query)))
   (read-sub query frame)))

(def event-codec
  "L3 Codec for a re-frame event tool: encode {:event :args}; dispatch
   output becomes its declared result."
  {:encode (fn [tool args]
             (if (fn? (:args->event tool))
               (answer/ok {:event ((:args->event tool) args) :args args})
               (answer/refusal :re-frame/invalid-call "event tool needs :args->event")))
   :decode (fn [_ value] (answer/ok value))})

(def subscription-codec
  "L3 Codec for a re-frame subscription tool: args become a query; the
   subscription's value is the decoded result."
  {:encode (fn [tool args]
             (if (fn? (:args->query tool))
               (answer/ok ((:args->query tool) args))
               (answer/refusal :re-frame/invalid-call "subscription tool needs :args->query")))
   :decode (fn [_ value] (answer/ok value))})

(defn- invoke-event
  "L3 Boundary: dispatch an encoded event through re-frame."
  [tool {:keys [event args]} grant]
  (answer/ok (event-call tool args event grant)))

(defn- invoke-subscription
  "L3 Boundary: read an encoded query through re-frame."
  [tool query _grant]
  (answer/ok (subscription-call query (:frame tool))))

(defn- exposed-specs
  "L3 Collect: only explicitly exposed event and subscription declarations."
  [{:keys [events subs]}]
  (concat (map (fn [[id spec]] [id (assoc spec :kind :re-frame/event)])
               (filter (comp :expose val) events))
          (map (fn [[id spec]] [id (assoc spec :kind :re-frame/sub :read-only? true)])
               (filter (comp :expose val) subs))))

(defn- declaration-specs
  "L3 Promote: compile exposed re-frame declarations into ToolSpecs."
  [source]
  (surface/compile-tools (exposed-specs source)))

(def re-frame-adapter
  "L3 Source of explicit {:events {id spec} :subs {id spec}} declarations.
   Each spec opts in with :expose true; codec adapters are `event-adapter`
   and `subscription-adapter`."
  {:source-kind :autonomous.re-frame/declarations
   :derive (fn [source opts]
             (answer/let-answer [tools (declaration-specs source)]
               (surface/->surface {:app (:app opts {:name "app"}) :tools tools})))})

(def event-adapter
  "L3 re-frame event Codec and its host dispatch port."
  {:kind :re-frame/event :codec event-codec :invoke invoke-event})

(def subscription-adapter
  "L3 re-frame subscription Codec and its host query port."
  {:kind :re-frame/sub :codec subscription-codec :invoke invoke-subscription})

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
     :frame        re-frame2 frame id: the dispatch and the result read run
                   in that frame (re-frame.core/with-frame, app-db-value)
   Answers the MCP tool name."
  [event-id spec]
  (checked-frame (:frame spec))
  (core/reg-tool (or (:tool-id spec) event-id)
                 (merge (dissoc spec :tool-id)
                        {:kind :re-frame/event
                         :args->event (or (:args->event spec) #(model/default-event event-id %))})))

(defn reg-sub-tool
  "Expose re-frame subscription `query-id` as a read-only tool.
   spec: as reg-tool minus :handler, plus :tool-id,
   :args->query (fn [args] query-vector), default [id] / [id args], and
   :frame, a re-frame2 frame id the subscription is read in."
  [query-id spec]
  (checked-frame (:frame spec))
  (core/reg-tool (or (:tool-id spec) query-id)
                 (merge {:read-only? true}
                        (dissoc spec :tool-id)
                        {:kind :re-frame/sub
                         :args->query (or (:args->query spec) #(model/default-query query-id %))})))

(defn expose-db!
  "Register app-db as a resource. opts: :uri (default re-frame://app-db),
   :paths (only these), :redact (paths shown as <redacted>), :scopes,
   :description, :frame (a re-frame2 frame id: its app-db, read through
   re-frame.core/app-db-value)."
  ([] (expose-db! {}))
  ([{:keys [uri paths redact scopes description frame]}]
   (checked-frame frame)
   (core/reg-resource (or uri "re-frame://app-db")
                      (cond-> {:name "app-db"
                               :description (or description "The re-frame application state")
                               :read (fn [_] (model/db-view (app-db-value frame) paths redact))}
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
