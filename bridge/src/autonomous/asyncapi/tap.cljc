(ns autonomous.asyncapi.tap
  "A bounded tap: the last N messages of one subject, with a cursor. Pure.

   A tap is a value. `admit` returns a new one, so nothing here holds state:
   the Source keeps the newest value per subject in an atom and swaps it.

     {:filter   \"sisf.payment.*.entry\"   the subscription it holds
      :capacity 50                      N, at most
      :cursor   17                      seq of the last admitted message
      :dropped  3                       messages that fell out of the ring
      :validate (fn [payload] ...)      nil when no schema is declared
      :messages [{:seq :subject :payload :conforms? :problems :at}]}

   A reader asks `since` with the cursor it last saw and gets what came
   after it, oldest first, and its own `:dropped`: how many matched messages
   fell out of the ring before it got to them. That count is per reader and
   cumulative between its reads, so a slow reader is told exactly what it
   missed instead of silently seeing a gap.

   A payload that does not fit the document's schema is KEPT and flagged
   :conforms? false with the humanized problems. Dropping it would hide
   exactly the drift an agent is watching for."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def default-capacity
  "Messages kept per subject when a resource names none."
  50)

(defn- validation-problems
  "Human-readable schema problems of `payload`, nil when it conforms or the
   tap declares no schema."
  [validate payload]
  (when validate (some-> (validate payload) me/humanize)))

(defn- message-entry
  "One retained message, with non-conformance reported alongside its payload."
  [n subject payload at problems]
  (cond-> {:seq n :subject subject :payload payload :at at
           :conforms? (nil? problems)}
    problems (assoc :problems problems)))

(defn- entry
  "Validate `payload` and collect one retained message entry."
  [n subject payload validate at]
  (message-entry n subject payload at (validation-problems validate payload)))

(defn tap
  "An empty tap of `filter` keeping at most `capacity` messages. `validate`
   is a fn of a payload answering a malli explanation or nil."
  ([filter] (tap filter default-capacity nil))
  ([filter capacity] (tap filter capacity nil))
  ([filter capacity validate]
   {:filter filter :capacity capacity :cursor 0 :dropped 0 :validate validate :messages []}))

(defn- next-seq
  "The sequence `msg` must carry, or nil when it is a redelivery, a gap, or
   not an integer."
  [tap {:keys [seq]}]
  (let [expected (inc (:cursor tap))]
    (cond
      (nil? seq) expected
      (and (integer? seq) (= expected seq)) seq
      :else nil)))

(defn- keep-last
  "The current ring plus `e`, bounded by `capacity`."
  [messages capacity e]
  (conj (vec (take-last (dec capacity) messages)) e))

(defn- append
  "Append entry `e`, advancing the cursor and counting ring evictions."
  [{:keys [capacity] :as t} e]
  (let [evicted (max 0 (- (inc (count (:messages t))) capacity))]
    (-> t
        (assoc :cursor (:seq e))
        (update :dropped + evicted)
        (update :messages keep-last capacity e))))

(defn admit
  "`tap` with message `msg` ({:subject :payload :at}) admitted. Answers
   [tap admitted?]. Only the next contiguous integer sequence on this tap's
   subject is admitted: redeliveries and gaps leave the tap untouched."
  [{:keys [filter validate] :as t} {:keys [subject payload at] :as msg}]
  (if-let [n (and (= subject filter) (next-seq t msg))]
    [(append t (entry n subject payload validate at)) true]
    [t false]))

(defn- gap
  "Messages dropped between reading `cursor` and the oldest kept message."
  [tap cursor]
  (if-let [oldest (some-> (:messages tap) first :seq)]
    (max 0 (- oldest cursor 1))
    0))

(defn since
  "What a reader at `cursor` has not seen: {:messages [...] :cursor n
   :dropped k}. At most `limit` messages, oldest first; the answer's cursor
   is the seq of the last one, or `cursor` when there is nothing new.
   :dropped counts only messages lost from the ring, never unread ones held
   for a later page."
  ([tap cursor] (since tap cursor (:capacity tap)))
  ([tap cursor limit]
   (let [after (filterv #(< cursor (:seq %)) (:messages tap))
         page  (vec (take (max 1 limit) after))]
     {:messages page
      :cursor   (if (seq page) (:seq (peek page)) cursor)
      :dropped  (gap tap cursor)})))

(defn view
  "The full current ring, oldest first. For a resource that asks for
   everything it holds."
  [tap]
  (since tap 0 (:capacity tap)))

(defn conforming?
  "True when every kept message matches the schema."
  [tap]
  (every? :conforms? (:messages tap)))
