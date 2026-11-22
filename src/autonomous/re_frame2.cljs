(ns autonomous.re-frame2
  "re-frame2 adapter: autonomous.re-frame for apps on re-frame2, whose
   app-db lives per frame.

     (autonomous.re-frame2/reg-event-tool :todo/add
       {:description \"Add a todo item\"
        :args->event (fn [{:keys [text]}] [:todo/add text])
        :result-sub [:todos/visible]
        :frame :app/main})

   reg-event-tool, reg-sub-tool and expose-db! take the specs
   autonomous.re-frame takes, plus :frame, a frame-id keyword. With :frame
   the call goes through re-frame2's public frame-targeted API:
   (dispatch ev {:frame f}), (dispatch-sync ev {:frame f}),
   (subscribe-once q {:frame f}) and (app-db-value f). A frame that is not
   registered when the call runs is refused with :re-frame/unknown-frame.
   Without :frame the call uses the ambient frame (with-frame,
   frame-provider), which re-frame2 refuses outside any scope.

   Load this namespace instead of autonomous.re-frame, never both: each
   needs its own re-frame on the classpath."
  (:require [autonomous.re-frame.kernel :as kernel]
            [autonomous.spi :as spi]
            [re-frame.core :as rf]))

(defn- check-frame!
  "Throw :re-frame/unknown-frame unless `frame` is a registered frame now.
   Its shape was checked when the tool was registered."
  [frame]
  (let [frames (rf/frame-ids)]
    (when-not (contains? frames frame)
      (throw (ex-info (str "no re-frame2 frame " (pr-str frame) " is registered")
                      {:autonomous/error :re-frame/unknown-frame
                       :frame frame
                       :frames (vec (sort-by str frames))})))))

(defn- registered?
  "Is a `kind` handler `id` visible to `frame` (the frame's sealed image),
   or to the registrar when `frame` is nil."
  [kind id frame]
  (some? (if frame
           (rf/handler-meta {:frame frame :kind kind :id id})
           (rf/handler-meta kind id))))

(def ^:private pre-commit-errors
  "re-frame2 error categories raised before the failing event's :db write:
   that event changed nothing. Any other category (an :fx handler threw,
   a later event of the cascade failed) comes after a commit."
  #{:rf.error/handler-exception
    :rf.error/interceptor-exception
    :rf.error/coeffect-exception
    :rf.error/flow-eval-exception})

(defn- dispatch-state
  "Pure: :app-db-unchanged when the failure belongs to the root dispatch
   alone and frame `target`'s app-db is the same value `before` and
   `after`; else :maybe-applied. The root dispatch alone means `processed`
   (the :events records of the call) is exactly one event, the dispatched
   `event-id` in `target`, which did not settle :ok, and every error in
   `records` is a pre-commit failure of that event in that frame. A
   re-dispatch of the same event id by an effect is a second processed
   event, so it is :maybe-applied: that first event's effects ran. Even
   :app-db-unchanged proves only the target frame's app-db; an interceptor
   or coeffect may still have acted, so it never means safe to retry."
  [records processed event-id target before after]
  (let [[root & more] processed]
    (if (and (identical? before after)
             (some? root)
             (empty? more)
             (= event-id (:event-id root))
             (= target (:frame root))
             (not= :ok (:outcome root))
             (every? (fn [{:keys [error frame] :as record}]
                       (and (contains? pre-commit-errors error)
                            (= event-id (:event-id record))
                            (= target frame)))
                     records))
      :app-db-unchanged
      :maybe-applied)))

(def ^:private exception-classes
  "Fixed names by exception constructor, most specific first. A handler
   can set an exception's .name or message to anything, so neither is
   read; only instance? against these."
  [[ExceptionInfo   "ExceptionInfo"]
   [js/TypeError      "TypeError"]
   [js/RangeError     "RangeError"]
   [js/ReferenceError "ReferenceError"]
   [js/SyntaxError    "SyntaxError"]
   [js/Error          "Error"]])

(defn- exception-class
  "Pure: the fixed class name of exception `e` (nil when there is none),
   \"an unrecognised exception\" for a thrown value no constructor matches."
  [e]
  (when (some? e)
    (or (some (fn [[ctor nm]] (when (instance? ctor e) nm)) exception-classes)
        "an unrecognised exception")))

(def ^:private state-messages
  {:app-db-unchanged "the target frame's app-db is unchanged; effects may have run"
   :maybe-applied    "the dispatch may have changed app-db"})

(defn- handler-error
  "Pure: the :re-frame/handler-error refusal for re-frame2 error `record`.
   Its message is fixed text, the category, the event id and the
   exception's class; never the exception's own message, which the
   handler or a remote it called may have written."
  [{:keys [error event-id exception] :as record} state]
  (let [cls (exception-class exception)]
    (ex-info (str "re-frame2 reported " error " for " (pr-str event-id)
                  (when cls (str " (" cls ")"))
                  "; " (state-messages state))
             (cond-> {:autonomous/error :re-frame/handler-error
                      :error error :event-id event-id :frame (:frame record)
                      :state state}
               cls (assoc :exception-class cls)))))

(defn- dispatch-sync-or-throw!
  "dispatch-sync `event` (in `frame` when given) and throw the first error
   re-frame2 reported while it ran. re-frame2 does not throw from a failing
   handler; it emits on the :errors listener stream, so this listens there
   for the length of the call. Both that stream and :events carry every
   frame and the whole cascade the call drains, so it also listens on
   :events, to tell the root dispatch from a re-dispatch of the same
   event, and the refusal's :state says what is known (see
   dispatch-state)."
  [event frame]
  (let [id        (keyword "autonomous.re-frame2" (str "dispatch-" (random-uuid)))
        errors    (atom [])
        processed (atom [])
        target    (or frame (try (rf/current-frame-id) (catch :default _ nil)))
        before    (when target (rf/app-db-value target))]
    (rf/register-listener! :errors id #(swap! errors conj %))
    (rf/register-listener! :events id #(swap! processed conj %))
    (try
      (if frame (rf/dispatch-sync event {:frame frame}) (rf/dispatch-sync event))
      (finally
        (rf/unregister-listener! :errors id)
        (rf/unregister-listener! :events id)))
    (when-let [record (first @errors)]
      (throw (handler-error record (dispatch-state @errors @processed (first event) target before
                                                   (when target (rf/app-db-value target))))))))

(def host
  "The kernel Host for re-frame2."
  {:frames?       true
   :registered?   registered?
   :dispatch      (fn [event frame] (if frame (rf/dispatch event {:frame frame}) (rf/dispatch event)))
   :dispatch-sync dispatch-sync-or-throw!
   :read-sub      (fn [query frame] (if frame (rf/subscribe-once query {:frame frame}) (rf/subscribe-once query)))
   :app-db        (fn [frame] (rf/app-db-value (or frame (rf/current-frame-id))))
   :check-frame   check-frame!})

(def ^:private adapters
  (kernel/adapters host {:source-kind :autonomous.re-frame2/declarations
                         :event-kind  :re-frame2/event
                         :sub-kind    :re-frame2/sub}))

(def re-frame2-adapter
  "L3 Source of {:events {id spec} :subs {id spec}} declarations, each
   opting in with :expose true and optionally naming its :frame."
  (:source adapters))

(def event-adapter
  "L3 re-frame2 event Codec and its dispatch port."
  (:event adapters))

(def subscription-adapter
  "L3 re-frame2 subscription Codec and its query port."
  (:sub adapters))

(spi/register-adapter! re-frame2-adapter)
(spi/register-adapter! event-adapter)
(spi/register-adapter! subscription-adapter)

(defn reg-event-tool
  "Expose re-frame2 event `event-id` as a tool; spec as
   autonomous.re-frame/reg-event-tool, plus :frame."
  [event-id spec]
  (kernel/reg-event-tool host :re-frame2/event event-id spec))

(defn reg-sub-tool
  "Expose re-frame2 subscription `query-id` as a read-only tool; spec as
   autonomous.re-frame/reg-sub-tool, plus :frame."
  [query-id spec]
  (kernel/reg-sub-tool host :re-frame2/sub query-id spec))

(defn expose-db!
  "Register a frame's app-db as a resource; opts as
   autonomous.re-frame/expose-db!, plus :frame."
  ([] (expose-db! {}))
  ([opts] (kernel/expose-db! host opts)))
