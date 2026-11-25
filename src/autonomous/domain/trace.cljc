(ns autonomous.domain.trace
  "Telemetry as value objects: spans that say what happened, when, and what
   caused it, across a page, its event loop, its HTTP calls and the servers
   they reach. Pure: building, joining and reading spans. Recording them is
   autonomous.runtime.trace; capturing them in a browser is
   autonomous.trace.browser and autonomous.re-frame.trace.

   Language:
   Span       one thing that happened, with a start and (usually) an end:
              a click, a re-frame event, an HTTP request, an agent's tool
              call, an uncaught error. Instant spans start and end at once.
   Trace      every span that shares one :trace-id: one root cause (a click,
              an agent call, a test mark) and everything it led to.
   Context    {:trace-id :span-id} of the span new spans become children of.
   Parent     the span that caused this one (:parent-id). A span whose
              parent was not collected (it lives in an app the reader did not
              reach) is an orphan; it still belongs to its trace.
   Source     where a span was collected: {:target :runtime}, set by the
              bridge.
   Finding    a span worth a look: an error, a failed request, a slow
              event, an error with no cause.

   Ids are lowercase hex (trace 32, span 16), the W3C Trace Context and
   OpenTelemetry shapes, so a trace crosses into any backend that speaks
   `traceparent` and exports as OTLP JSON."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Value objects
;; =============================================================================

(def TraceId [:re #"^[0-9a-f]{32}$"])

(def SpanId [:re #"^[0-9a-f]{16}$"])

(def Context [:map [:trace-id TraceId] [:span-id SpanId]])

(def Status [:enum :ok :error])

(def Span
  "One recorded span, as the runtime's trace ring holds it (:seq and :at are
   added by the ring)."
  [:map
   [:trace-id TraceId]
   [:span-id SpanId]
   [:parent-id {:optional true} [:maybe SpanId]]
   [:name :string]
   [:type :keyword]
   [:start number?]
   [:end {:optional true} [:maybe number?]]
   [:status {:optional true} Status]
   [:actor {:optional true} [:maybe :string]]
   [:attrs {:optional true} [:map-of :keyword :any]]
   [:error {:optional true} [:maybe [:map [:message {:optional true} [:maybe :string]]]]]
   [:source {:optional true} [:map-of :keyword :any]]])

(def ^:private hex-digits "0123456789abcdef")

(defn hex-id
  "A lowercase hex id of `n` digits drawn with `rand-digit` (fn [] 0..15).
   Never all zeros, which W3C Trace Context forbids."
  [n rand-digit]
  (let [s (apply str (repeatedly n #(nth hex-digits (rand-digit))))]
    (if (every? #{\0} s) (str (subs s 0 (dec n)) "1") s)))

;; =============================================================================
;; W3C Trace Context
;; =============================================================================

(defn traceparent
  "The W3C `traceparent` header value for context `ctx`, sampled."
  [{:keys [trace-id span-id]}]
  (str "00-" trace-id "-" span-id "-01"))

(def ^:private traceparent-re #"^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")

(defn parse-traceparent
  "Context named by `traceparent` header value `s`, or nil when it is
   absent, malformed, version ff, or an all-zero id. Case and surrounding
   whitespace are forgiven."
  [s]
  (when (string? s)
    (when-let [[_ version trace-id span-id] (re-matches traceparent-re (str/lower-case (str/trim s)))]
      (when (and (not= "ff" version)
                 (not (every? #{\0} trace-id))
                 (not (every? #{\0} span-id)))
        {:trace-id trace-id :span-id span-id}))))

;; =============================================================================
;; Reading spans
;; =============================================================================

(defn duration
  "Milliseconds span `s` took; 0 for an instant or unfinished span."
  [s]
  (if (:end s) (max 0 (- (:end s) (:start s))) 0))

(defn- order-key [s] [(:start s) (:seq s 0)])

(defn chronological
  "`spans` oldest first (start, then the order they were recorded)."
  [spans]
  (sort-by order-key spans))

(defn span-key
  "Identity of a span across collections: its trace and span ids."
  [s]
  [(:trace-id s) (:span-id s)])

(defn dedupe-spans
  "`spans` without repeats of the same span (first kept)."
  [spans]
  (first (reduce (fn [[out seen] s]
                   (let [k (span-key s)]
                     (if (seen k) [out seen] [(conj out s) (conj seen k)])))
                 [[] #{}] spans)))

(defn by-trace
  "Map of trace id to that trace's spans, oldest first."
  [spans]
  (update-vals (group-by :trace-id spans) (comp vec chronological)))

(defn tree
  "The spans of ONE trace as a forest: each node is the span with
   :children (oldest first). A span whose parent is not among `spans`
   becomes a root marked :orphan? true."
  [spans]
  (let [spans    (chronological spans)
        ids      (set (map :span-id spans))
        children (group-by :parent-id spans)
        build    (fn build [s]
                   (assoc s :children (mapv build (get children (:span-id s)))))]
    (into []
          (comp (filter #(or (nil? (:parent-id %)) (not (ids (:parent-id %)))))
                (map #(cond-> (build %) (:parent-id %) (assoc :orphan? true))))
          spans)))

(defn- error? [s]
  (= :error (:status s)))

(defn summary
  "What a reader needs to pick a trace out of many: root name and type,
   when it began and ended, how many spans and errors, which apps and
   actors took part."
  [trace-id spans]
  (let [spans (chronological spans)
        roots (filter #(nil? (:parent-id %)) spans)
        root  (or (first roots) (first spans))
        end   (reduce max (map #(or (:end %) (:start %)) spans))]
    (cond-> {:trace-id trace-id
             :name     (:name root)
             :type     (:type root)
             :start    (:start (first spans))
             :end      end
             :ms       (- end (:start (first spans)))
             :spans    (count spans)
             :errors   (count (filter error? spans))
             :types    (into (sorted-set) (map :type) spans)}
      (some :actor spans)  (assoc :actors (into (sorted-set) (keep :actor) spans))
      (some :source spans) (assoc :apps (into (sorted-set) (keep (comp :target :source)) spans)))))

(defn summaries
  "One summary per trace in `spans`, oldest trace first."
  [spans]
  (->> (by-trace spans)
       (map (fn [[tid ss]] (summary tid ss)))
       (sort-by :start)
       vec))

(defn timeline
  "Every span in `spans`, oldest first, flattened from each trace's tree
   with :depth (0 for a root) and :t (milliseconds since the first span
   of all), so many traces read as one story through time. Children are
   dropped from the rows."
  [spans]
  (let [t0    (when (seq spans) (reduce min (map :start spans)))
        rows  (fn rows [depth node]
                (cons (-> node (dissoc :children) (assoc :depth depth :t (- (:start node) t0)))
                      (mapcat #(rows (inc depth) %) (:children node))))]
    (->> (vals (by-trace spans))
         (mapcat tree)
         (mapcat #(rows 0 %))
         (sort-by (juxt :t :depth (fn [r] (:seq r 0))))
         vec)))

(defn- label [s]
  (str (when (:type s) (str (namespace (:type s)) "/" (name (:type s)) " ")) (:name s)))

(defn outline
  "The tree of one trace as indented text lines, one per span: offset from
   the trace start, duration, status, type and name, and the most telling
   attributes. What an agent reads first."
  [spans]
  (let [t0 (when (seq spans) (reduce min (map :start spans)))
        fmt (fn [x] (let [r (/ (Math/round (* 10.0 (double x))) 10.0)] (str r)))
        hint (fn [s] (let [a (:attrs s)]
                       (->> [(when-let [st (:http/status a)] (str "status=" st))
                             (when-let [u (:http/url a)] u)
                             (when-let [d (:db/changed a)] (str "db=" (pr-str d)))
                             (when-let [d (:dispatched a)] (str "dispatched=" (pr-str d)))
                             (when-let [e (get-in s [:error :message])] (str "error=" (pr-str e)))]
                            (remove nil?)
                            (str/join " "))))
        line (fn line [depth s]
               (cons (str (apply str (repeat depth "  "))
                          "+" (fmt (- (:start s) t0)) "ms "
                          (fmt (duration s)) "ms "
                          (name (:status s :ok)) " "
                          (label s)
                          (when (:orphan? s) " (parent not collected)")
                          (let [h (hint s)] (when (seq h) (str "  " h))))
                     (mapcat #(line (inc depth) %) (:children s))))]
    (vec (mapcat #(line 0 %) (tree spans)))))

;; =============================================================================
;; Findings
;; =============================================================================

(def default-slow-ms
  "An event or request taking longer than this is reported slow."
  300)

(defn- path-to
  "Names from the root of `s`'s trace down to `s`, from index `by-id`."
  [by-id s]
  (loop [s s acc ()]
    (if-let [p (and (:parent-id s) (by-id (:parent-id s)))]
      (recur p (cons (:name s) acc))
      (vec (cons (:name s) acc)))))

(defn- finding-of
  "The finding kind span `s` is, given `slow-ms`, or nil."
  [s slow-ms]
  (let [status (get-in s [:attrs :http/status])]
    (cond
      (and (#{:error/uncaught :error/unhandled-rejection} (:type s)) (nil? (:parent-id s)))
      :error-without-cause

      (#{:error/uncaught :error/unhandled-rejection :console/error} (:type s))
      :error

      (and (number? status) (>= status 500)) :http-server-error
      (and (number? status) (>= status 400)) :http-client-error
      (error? s)                             :failed
      (> (duration s) slow-ms)               :slow)))

(defn findings
  "Spans worth a look in `spans`, oldest first: each is {:finding :span
   :path} where :path names the chain of causes from the trace root.
   opts: :slow-ms (default `default-slow-ms`)."
  ([spans] (findings spans {}))
  ([spans {:keys [slow-ms]}]
   (let [slow-ms (or slow-ms default-slow-ms)
         by-id   (into {} (map (juxt :span-id identity)) spans)]
     (into []
           (keep (fn [s]
                   (when-let [f (finding-of s slow-ms)]
                     {:finding  f
                      :trace-id (:trace-id s)
                      :span     (dissoc s :children)
                      :path     (path-to by-id s)})))
           (chronological spans)))))

;; =============================================================================
;; OTLP JSON export
;; =============================================================================

(def ^:private otlp-kind
  {:http/client 3 :http/server 2})

(defn- otlp-value [v]
  (cond
    (string? v)  {:stringValue v}
    (boolean? v) {:boolValue v}
    (integer? v) {:intValue (str v)}
    (number? v)  {:doubleValue v}
    (keyword? v) {:stringValue (if (namespace v) (str (namespace v) "/" (name v)) (name v))}
    :else        {:stringValue (pr-str v)}))

(defn- attr-name [k]
  (if (keyword? k) (str/replace (str (when (namespace k) (str (namespace k) "."))
                                     (name k)) "/" ".")
      (str k)))

(defn- otlp-attrs [m]
  (mapv (fn [[k v]] {:key (attr-name k) :value (otlp-value v)}) (sort-by (comp str key) m)))

(defn- nanos [ms]
  (str (long (* 1000000 (double ms)))))

(defn- otlp-span [s]
  (cond-> {:traceId           (:trace-id s)
           :spanId            (:span-id s)
           :name              (:name s)
           :kind              (otlp-kind (:type s) 1)
           :startTimeUnixNano (nanos (:start s))
           :endTimeUnixNano   (nanos (or (:end s) (:start s)))
           :attributes        (otlp-attrs (cond-> (assoc (:attrs s) :autonomous/type (:type s))
                                            (:actor s) (assoc :autonomous/actor (:actor s))))
           :status            (if (error? s)
                                (cond-> {:code 2} (get-in s [:error :message]) (assoc :message (get-in s [:error :message])))
                                {:code 1})}
    (:parent-id s) (assoc :parentSpanId (:parent-id s))))

(defn ->otlp
  "An OTLP/JSON ExportTraceServiceRequest of `spans` (the body an
   OpenTelemetry collector accepts at /v1/traces, and Jaeger and Tempo
   import), one resource per source app. `service` names the app of a span
   whose source has none."
  [spans service]
  {:resourceSpans
   (->> (group-by #(or (get-in % [:source :target]) service) spans)
        (sort-by key)
        (mapv (fn [[svc ss]]
                {:resource   {:attributes (otlp-attrs {:service.name (str svc)})}
                 :scopeSpans [{:scope {:name "autonomous"}
                               :spans (mapv otlp-span (chronological ss))}]})))})
