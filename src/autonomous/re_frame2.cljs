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
  "Throw :re-frame/unknown-frame unless `frame` is a registered frame now."
  [frame]
  (kernel/checked-frame! {:frames? true} frame)
  (when-not (contains? (set (rf/frame-ids)) frame)
    (throw (ex-info (str "no re-frame2 frame " (pr-str frame) " is registered")
                    {:autonomous/error :re-frame/unknown-frame
                     :frame frame
                     :frames (vec (sort-by str (rf/frame-ids)))}))))

(defn- registered?
  "Is a `kind` handler `id` visible to `frame` (the frame's sealed image),
   or to the registrar when `frame` is nil."
  [kind id frame]
  (some? (if frame
           (rf/handler-meta {:frame frame :kind kind :id id})
           (rf/handler-meta kind id))))

(defn- dispatch-sync-or-throw!
  "dispatch-sync `event` (in `frame` when given) and throw the first error
   re-frame2 reported while it ran. re-frame2 does not throw from a failing
   handler; it emits on the :errors listener stream, so this listens there
   for the length of the call."
  [event frame]
  (let [id     (keyword "autonomous.re-frame2" (str "dispatch-" (random-uuid)))
        errors (atom [])]
    (rf/register-listener! :errors id #(swap! errors conj %))
    (try
      (if frame (rf/dispatch-sync event {:frame frame}) (rf/dispatch-sync event))
      (finally (rf/unregister-listener! :errors id)))
    (when-let [{:keys [error event-id exception] :as record} (first @errors)]
      (throw (ex-info (str "re-frame2 reported " error " for " (pr-str event-id)
                           (when exception (str ": " (ex-message exception))))
                      {:autonomous/error :re-frame/handler-error
                       :error error :event-id event-id :frame (:frame record)})))))

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
