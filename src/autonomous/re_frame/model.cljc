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

(defn frame-problem
  "nil when `frame` is absent or a frame-id keyword, else why it is not."
  [frame]
  (when-not (or (nil? frame) (keyword? frame))
    (str "a re-frame2 :frame is a frame-id keyword, got " (pr-str frame))))
