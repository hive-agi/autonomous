(ns autonomous.runtime.trace
  "Recording spans: the runtime half of telemetry (the value objects are
   autonomous.domain.trace).

   A span is started under the CURRENT context, so causes chain without
   anyone passing ids around:

     (trace/in-span \"checkout\" :app/step {:attrs {:items 3}}
       (fn [_span] (place-order!)))     ; spans made inside are its children

   The current context is the dynamic var `*context*`. On the JVM it is
   conveyed by `binding` (and so into futures); in a browser, where one
   cause spreads over callbacks, the adapters set it for the duration of
   an event or a callback and restore it afterwards
   (autonomous.re-frame.trace, autonomous.trace.browser).

   Nothing is recorded until tracing is enabled (`enable!`), so the
   instrumentation costs nothing in an app that never turns it on. Spans
   land in the registry's trace ring; the bridge reads them with the :trace
   op."
  (:require [autonomous.domain.journal :as journal]
            [autonomous.domain.trace :as dt]
            [autonomous.domain.wire :as wire]
            [autonomous.runtime.registry :as registry]
            [clojure.string :as str]))

(def ^:dynamic *context*
  "Context {:trace-id :span-id} new spans become children of; nil starts a
   new trace."
  nil)

(defn now
  "Wall-clock milliseconds; sub-millisecond in a browser."
  []
  #?(:cljs (if (and (exists? js/performance) (.-timeOrigin js/performance))
             (+ (.-timeOrigin js/performance) (.now js/performance))
             (js/Date.now))
     :default (registry/now-ms)))

(defn- rand-digit [] (rand-int 16))

(defn new-trace-id [] (dt/hex-id 32 rand-digit))
(defn new-span-id [] (dt/hex-id 16 rand-digit))

;; =============================================================================
;; Configuration
;; =============================================================================

(defn enabled?
  "True when spans are recorded."
  []
  (boolean (:enabled? (registry/trace-opts))))

(defn payloads?
  "True when spans may carry payloads (event arguments, request bodies)."
  []
  (boolean (:payloads? (registry/trace-opts))))

(defn enable!
  "Start recording spans. opts: :cap (spans kept, default 5000),
   :payloads? (keep event arguments and request and response bodies,
   default false: they may carry what a person typed)."
  ([] (enable! {}))
  ([opts]
   (registry/configure-trace! (assoc opts :enabled? true))))

(defn disable!
  "Stop recording; the spans already kept stay readable."
  []
  (registry/configure-trace! {:enabled? false}))

;; =============================================================================
;; Context
;; =============================================================================

(defn current
  "The current context, or nil."
  []
  *context*)

(defn context-of
  "The context that makes children of `span`."
  [span]
  (when span (select-keys span [:trace-id :span-id])))

(defn with-context
  "Run `f` (no arguments) with `ctx` as the current context."
  [ctx f]
  (binding [*context* ctx] (f)))

(defn headers
  "The HTTP headers that carry the current context to another service:
   {\"traceparent\" ...}, or {} outside any span."
  []
  (if-let [ctx (current)] {"traceparent" (dt/traceparent ctx)} {}))

(defn incoming
  "Context named by an incoming `traceparent` header value, or nil."
  [traceparent]
  (dt/parse-traceparent traceparent))

;; =============================================================================
;; Spans
;; =============================================================================

(defn error-info
  "What a span records of throwable or JS error `e`: message, class and a
   bounded stack."
  [e]
  (let [stack #?(:cljs (when (some? e) (some-> (unchecked-get e "stack") str))
                 :cljw nil
                 :default (when (instance? Throwable e)
                            (str/join "\n" (map str (take 20 (.getStackTrace ^Throwable e))))))]
    (cond-> {:message (or (ex-message e) (str e))}
      (ex-data e) (assoc :data (wire/->wire (ex-data e)))
      (some? e)   (assoc :class #?(:cljs (or (some-> (unchecked-get e "name") str) "error")
                                   :default (str (type e))))
      stack       (assoc :stack (subs stack 0 (min 4000 (count stack)))))))

(defn start
  "A started span named `nm` of type `type` (a keyword such as
   :re-frame/event). opts: :parent (a context; default the current one,
   nil starts a new trace), :actor, :attrs, :start (milliseconds)."
  ([nm type] (start nm type {}))
  ([nm type {:keys [actor attrs] :as opts}]
   (let [parent (if (contains? opts :parent) (:parent opts) (current))]
     (cond-> {:trace-id (or (:trace-id parent) (new-trace-id))
              :span-id  (new-span-id)
              :name     (str nm)
              :type     type
              :start    (or (:start opts) (now))}
       (:span-id parent) (assoc :parent-id (:span-id parent))
       actor             (assoc :actor (str actor))
       (seq attrs)       (assoc :attrs attrs)))))

(defn finish!
  "Record `span` as ended now. `extra` merges in: :status (default :ok, or
   :error with an :error), :attrs (merged into the span's), :error, :end.
   Recorded only while tracing is enabled. Answers the finished span."
  ([span] (finish! span {}))
  ([span {:keys [attrs] :as extra}]
   (let [s (-> (merge span (dissoc extra :attrs))
               (update :status #(or % (if (:error extra) :error :ok)))
               (update :end #(or % (now)))
               (cond-> (seq attrs) (update :attrs merge attrs)))]
     (when (enabled?) (registry/record-span! s))
     s)))

(defn event!
  "Record an instant span (start and end at once); answers it. opts as
   `start`, plus :status and :error."
  ([nm type] (event! nm type {}))
  ([nm type opts]
   (let [s (start nm type opts)]
     (finish! s (cond-> {:end (:start s)}
                  (:status opts) (assoc :status (:status opts))
                  (:error opts)  (assoc :error (:error opts)))))))

(defn in-span
  "Run (f span) inside a new span, the span's context current for the
   duration. A throw ends the span as an error and is rethrown. With
   tracing disabled, (f nil) runs and nothing is recorded."
  [nm type opts f]
  (if-not (enabled?)
    (f nil)
    (let [s (start nm type opts)]
      (try
        (let [v (binding [*context* (context-of s)] (f s))]
          (finish! s)
          v)
        (catch #?(:clj Throwable :cljs :default) e
          (finish! s {:status :error :error (error-info e)})
          (throw e))))))

(defn mark!
  "Record an instant root span: a boundary a person or a test runner sets
   (\"test: checkout happy path\") so the spans after it read as one
   story. Answers its trace id."
  ([label] (mark! label {}))
  ([label {:keys [actor attrs]}]
   (:trace-id (event! label :test/mark {:parent nil :actor actor :attrs attrs}))))

;; =============================================================================
;; Reading
;; =============================================================================

(defn spans
  "Spans recorded after sequence number `since` (nil for all), at most
   `limit` (nil: 1000), oldest first, with the newest sequence number."
  [since limit]
  (journal/since (:trace (registry/snapshot)) since (or limit 1000)))

(defn dump
  "Every span the ring holds, oldest first."
  []
  (:entries (:trace (registry/snapshot))))
