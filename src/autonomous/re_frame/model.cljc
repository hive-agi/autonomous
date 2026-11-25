(ns autonomous.re-frame.model
  "Pure half of the re-frame adapter: how args become an event or a query,
   what part of app-db an agent may see, and what the journal records about
   an event. cljc, so it is tested on the JVM with no re-frame loaded.

   Language:
   EventTool  a tool that dispatches a re-frame event.
   QueryTool  a read-only tool that derefs a re-frame subscription.
   DbView     the slice of app-db a resource shows: chosen paths, with
              redacted paths replaced.
   Origin     who caused an event: :agent (dispatched through a tool, the
              actor rides on the event's metadata) or :app (the human, a
              timer, a server push)."
  (:require [autonomous.domain.wire :as wire]))

(def redacted "<redacted>")

(defn default-event
  "Event for `args` when the author gives no :args->event: [id] without
   args, [id args] with them."
  [event-id args]
  (if (seq args) [event-id args] [event-id]))

(defn default-query
  "Query vector for `args`, by the same rule as `default-event`."
  [query-id args]
  (if (seq args) [query-id args] [query-id]))

(defn db-view
  "The DbView of `db`: only `paths` when given (each kept at its place),
   then every present path in `redact` replaced by the redaction marker."
  [db paths redact]
  (let [picked (if (seq paths)
                 (reduce (fn [m p] (let [v (get-in db p ::absent)]
                                     (if (= ::absent v) m (assoc-in m p v))))
                         {} paths)
                 db)]
    (reduce (fn [m p] (if (= ::absent (get-in m p ::absent)) m (assoc-in m p redacted)))
            picked redact)))

(defn event-entry
  "Journal entry for re-frame `event` with metadata `m`. The payload is
   kept only when `payloads?` (it may carry what the human typed)."
  [event m payloads?]
  (let [actor (:autonomous/actor m)]
    (cond-> {:kind     :re-frame/event
             :event-id (first event)
             :origin   (if actor :agent :app)}
      actor     (assoc :actor actor)
      payloads? (assoc :payload (wire/->wire (vec (rest event)))))))

(defn tag-event
  "`event` carrying the calling actor on its metadata, so the event
   observer can tell an agent's dispatch from the app's own."
  [event grant]
  (vary-meta event assoc :autonomous/actor (:actor grant)))

;; =============================================================================
;; Tracing: what an event span records, and how its children find it
;; =============================================================================

(defn- dispatch-later-event [x] (if (map? x) (:event x) x))

(defn- map-dispatch-later [f x]
  (if (map? x) (update x :event #(when % (f %))) x))

(defn- map-fx-entry
  "One [:fx] entry with every event it dispatches passed through `f`."
  [f [id arg :as entry]]
  (case id
    :dispatch       [id (f arg)]
    :dispatch-n     [id (mapv #(when % (f %)) arg)]
    :dispatch-later [id (if (sequential? arg) (mapv #(map-dispatch-later f %) arg) (map-dispatch-later f arg))]
    entry))

(defn map-dispatches
  "Effects map `effects` with every event it dispatches (:dispatch,
   :dispatch-n, :dispatch-later and their :fx forms) passed through `f`,
   so a child event can carry its cause. Anything else is untouched."
  [effects f]
  (cond-> effects
    (vector? (:dispatch effects))  (update :dispatch f)
    (:dispatch-n effects)          (update :dispatch-n #(mapv (fn [e] (when e (f e))) %))
    (:dispatch-later effects)      (update :dispatch-later
                                           #(if (sequential? %) (mapv (fn [x] (map-dispatch-later f x)) %)
                                                (map-dispatch-later f %)))
    (sequential? (:fx effects))    (update :fx #(mapv (fn [e] (if (vector? e) (map-fx-entry f e) e)) %))))

(defn dispatched
  "Ids of the events `effects` dispatches, in order."
  [effects]
  (let [seen (atom [])]
    (map-dispatches effects (fn [ev] (swap! seen conj (first ev)) ev))
    @seen))

(defn effect-ids
  "The effects `effects` asks for besides :db, :fx entries included, in
   order and without repeats."
  [effects]
  (into [] (distinct)
        (concat (remove #{:db :fx} (keys effects))
                (keep #(when (vector? %) (first %)) (:fx effects)))))

(defn changed-paths
  "Paths of app-db that differ between `before` and `after`: top-level keys,
   and one level deeper inside a changed map, at most `limit` paths. Cheap
   on persistent data: unchanged branches are identical and skipped."
  [before after limit]
  (if (identical? before after)
    []
    (let [ks (into (sorted-set-by #(compare (str %1) (str %2))) (concat (keys before) (keys after)))]
      (->> ks
           (mapcat (fn [k]
                     (let [a (get before k) b (get after k)]
                       (cond
                         (identical? a b) nil
                         (= a b)          nil
                         (and (map? a) (map? b))
                         (let [kks (into (sorted-set-by #(compare (str %1) (str %2))) (concat (keys a) (keys b)))
                               ch  (filter #(not= (get a %) (get b %)) kks)]
                           (if (<= (count ch) 5) (map #(vector k %) ch) [[k]]))
                         :else [[k]]))))
           (take limit)
           vec))))

(defn event-span-attrs
  "Attributes of the span of `event` (payload only when `payloads?`)."
  [event payloads?]
  (cond-> {:re-frame.event.id (first event)}
    payloads? (assoc :re-frame.event.payload (wire/->wire (vec (rest event))))))

(defn effects-span-attrs
  "Attributes an event span ends with: what changed in app-db, which
   effects ran, which events it dispatched."
  [db-before effects]
  (let [d (dispatched effects)
        e (effect-ids effects)]
    (cond-> {}
      (contains? effects :db) (assoc :re-frame.db.changed (changed-paths db-before (:db effects) 20))
      (seq e)                 (assoc :re-frame.effects e)
      (seq d)                 (assoc :re-frame.dispatched d))))

(defn tag-cause
  "`event` carrying context `ctx` (and `actor`, when the event has none) on
   its metadata, unless it already names a cause."
  [event ctx actor]
  (if (or (nil? ctx) (:autonomous/trace (meta event)))
    event
    (vary-meta event #(cond-> (assoc % :autonomous/trace ctx)
                        (and actor (not (:autonomous/actor %))) (assoc :autonomous/actor actor)))))
