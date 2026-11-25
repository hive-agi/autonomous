(ns autonomous.re-frame.trace
  "Spans for re-frame: every event a span, with what it changed in app-db,
   which effects it asked for and which events it dispatched; every
   dispatched event a child of the event (or click, or request callback,
   or agent call) that caused it.

     (autonomous.re-frame.trace/install! {:payloads? false})

   How causes travel:
   - a global interceptor starts the event's span in :before and makes it
     the current context (autonomous.runtime.trace/*context*), so effects
     run by do-fx (an HTTP request, a dispatch) see it; it ends the span in
     :after and rewrites the effects so every dispatched event carries its
     cause on its metadata (autonomous.re-frame.model/map-dispatches);
   - a post-event callback restores the context the event found;
   - re-frame.router/dispatch is wrapped so an event dispatched from a
     callback (a click handler, a fetch continuation) carries the context
     current at that moment (autonomous.trace.browser sets it);
   - an event that throws ends its span as an error through the event
     error handler, which then defers to the one that was registered.

   Pure decisions are in autonomous.re-frame.model."
  (:require [autonomous.re-frame.model :as model]
            [autonomous.runtime.trace :as trace]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.router :as router]))

(defonce ^:private !orig-dispatch (atom nil))
(defonce ^:private !orig-error-handler (atom nil))
(defonce ^:private !open (atom nil))
(defonce ^:private !payloads? (atom nil))

(defn- cause-of [event]
  (or (:autonomous/trace (meta event)) (trace/current)))

(defn- interceptor [payloads?]
  (rf/->interceptor
   :id ::trace
   :before (fn [ctx]
             (if-not (trace/enabled?)
               ctx
               (let [event (rf/get-coeffect ctx :event)
                     m     (meta event)
                     s     (trace/start (str (first event)) :re-frame/event
                                        {:parent (cause-of event)
                                         :actor  (:autonomous/actor m)
                                         :attrs  (model/event-span-attrs event payloads?)})]
                 (reset! !open {:span s :restore trace/*context*})
                 (set! trace/*context* (trace/context-of s))
                 (assoc ctx ::span s))))
   :after (fn [ctx]
            (if-let [s (::span ctx)]
              (let [effects (:effects ctx)
                    actor   (:actor s)
                    ctx'    (update ctx :effects model/map-dispatches
                                    #(model/tag-cause % (trace/context-of s) actor))]
                (trace/finish! s {:attrs (model/effects-span-attrs (rf/get-coeffect ctx :db) effects)})
                (swap! !open assoc :finished? true)
                ctx')
              ctx))))

(defn- restore! []
  (when-let [{:keys [restore]} @!open]
    (set! trace/*context* restore)
    (reset! !open nil)))

(defn- on-error [original re-frame-error]
  (when-let [{:keys [span finished?]} @!open]
    (when-not finished?
      (trace/finish! span {:status :error :error (trace/error-info original)
                           :attrs {:error.type (or (some-> original .-name str) "error")}})))
  (restore!)
  (when-let [h @!orig-error-handler] (h original re-frame-error)))

(defn install!
  "Trace every re-frame event. opts: :payloads? (event arguments on the
   spans, default false). Idempotent: a reinstall with other opts replaces
   the interceptor; the original dispatch and error handler are kept."
  ([] (install! {}))
  ([{:keys [payloads?]}]
   (when (not= (boolean payloads?) @!payloads?)
     (reset! !payloads? (boolean payloads?))
     (rf/reg-global-interceptor (interceptor (boolean payloads?)))
     (rf/add-post-event-callback ::restore (fn [_ _] (restore!))))
   (when-not @!orig-error-handler
     (reset! !orig-error-handler (registrar/get-handler :error :event-handler))
     (rf/reg-event-error-handler on-error))
   (when-not @!orig-dispatch
     (let [orig router/dispatch]
       (reset! !orig-dispatch orig)
       (set! router/dispatch
             (fn [event]
               (orig (if (trace/enabled?)
                       (model/tag-cause event (trace/current) nil)
                       event))))))
   nil))
