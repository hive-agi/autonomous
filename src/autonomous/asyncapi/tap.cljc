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

(defn- entry [n subject payload validate at]
  (let [problems        (when validate (some-> (validate payload) me/humanize))
        non-conforming? (some? problems)]
    (cond-> {:seq n :subject subject :payload payload :at at :conforms? (not non-conforming?)}
      non-conforming? (assoc :problems problems))))

(defn tap
  "An empty tap of `filter` keeping at most `capacity` messages. `validate`
   is a fn of a payload answering a malli explanation or nil."
  ([filter] (tap filter default-capacity nil))
  ([filter capacity] (tap filter capacity nil))
  ([filter capacity validate]
   {:filter filter :capacity capacity :cursor 0 :dropped 0 :validate validate :messages []}))

(defn admit
  "`tap` with message `msg` ({:subject :payload :at}) admitted. Answers
   [tap admitted?]: a message whose subject the filter does not match, or
   the same seq admitted twice (a redelivery), is not admitted."
  [tap {:keys [subject payload at] :as msg}]
  (let [n (or (get msg :seq) (inc (:cursor tap)))]
    (if (contains? (into #{} (map :seq) (:messages tap)) n)
      [tap false]
      (let [e    (entry n subject payload (:validate tap) at)
            over (max 0 (- (inc (count (:messages tap))) (:capacity tap)))]
        [(-> tap
             (assoc :cursor n)
             (update :dropped + over)
             (update :messages #(conj (vec (take-last (dec (:capacity tap)) %)) e)))
         true]))))

(defn- gap
  "Messages dropped between reading `cursor` and the oldest kept message."
  [tap cursor]
  (if-let [oldest (some-> (:messages tap) first :seq)]
    (max 0 (- oldest cursor 1))
    0))

(defn since
  "What a reader at `cursor` has not seen: {:messages [...] :cursor n
   :dropped k}. At most `limit` messages, oldest first; the answer's cursor
   is the seq of the last one, or `cursor` when there is nothing new."
  ([tap cursor] (since tap cursor (:capacity tap)))
  ([tap cursor limit]
   (let [after (filterv #(< cursor (:seq %)) (:messages tap))
         page  (vec (take (max 1 limit) after))]
     {:messages page
      :cursor   (if (seq page) (:seq (peek page)) cursor)
      :dropped  (+ (gap tap cursor) (max 0 (- (count after) (count page))))})))

(defn view
  "The full current ring, oldest first. For a resource that asks for
   everything it holds."
  [tap]
  (since tap 0 (:capacity tap)))

(defn conforming?
  "True when every kept message matches the schema."
  [tap]
  (every? :conforms? (:messages tap)))
