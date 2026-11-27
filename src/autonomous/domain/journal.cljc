(ns autonomous.domain.journal
  "The runtime's bounded memory of what happened: a sequenced ring of
   entries. Sequence numbers only grow, so a reader resumes from the last
   number it saw. Pure.

   CallEntry  the entry of one tool call: actor, tool, outcome and duration,
              plus the CallDetail the app opted into: the session, the args
              as sent with redacted paths, an Ok call's answer (bounded),
              the refusal."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.wire :as wire]))

(def empty-journal {:seq 0 :entries []})

(def default-cap 500)

(defn append
  "The journal after appending `entry` at time `at`, keeping the newest `cap`
   entries. The entry gets the next sequence number."
  [journal entry at cap]
  (let [n  (inc (:seq journal))
        es (conj (:entries journal) (assoc entry :seq n :at at))
        es (if (> (count es) cap) (subvec es (- (count es) cap)) es)]
    {:seq n :entries es}))

(defn since
  "Entries with a sequence number above `since` (nil reads from the start),
   at most `limit` (nil means 100), oldest first, with the newest number."
  [journal since limit]
  {:seq     (:seq journal)
   :entries (into [] (comp (filter #(> (:seq %) (or since 0)))
                           (take (or limit 100)))
                  (:entries journal))})

(def redacted "<redacted>")

(defn- mask-path
  "L1: `v` with the value at `path` replaced by `replacement`; the empty
   path is `v` itself. Maps and vectors are stepped through: a missing
   key, an index out of range or a non-integer step into a vector is an
   absent path and adds nothing. nil holds nothing to hide and is kept.
   Any other value the path stops at before it is consumed (a string, a
   set, a number) is masked whole, because the path promised a secret
   somewhere inside it. Never throws."
  [replacement v path]
  (if (empty? path)
    replacement
    (let [[k & more] path]
      (cond
        (map? v)    (if (contains? v k) (assoc v k (mask-path replacement (get v k) more)) v)
        (vector? v) (if (and (int? k) (< -1 k (count v)))
                      (assoc v k (mask-path replacement (nth v k) more))
                      v)
        (nil? v)    v
        :else       replacement))))

(defn redact-paths
  "`m` with the value at every path of `paths` (vectors of keys or
   indices) replaced by `replacement`, default `redacted`. A path that
   misses a map key or a vector index adds nothing; the empty path
   replaces `m` itself. A path that stops at any other non-nil value
   before it is consumed (a string where a vector of cookies was
   expected, a set) masks that whole value: it is never kept as absent.
   Give it a wire-form value (wire/->wire), where every seq and list is
   a vector the path's indices reach."
  ([m paths] (redact-paths m paths redacted))
  ([m paths replacement]
   (reduce (partial mask-path replacement) m paths)))

(def max-detail-items
  "The most items a journaled detail value (:args or :answer) keeps in any
   sequence or set; shared by both, so neither can blow up an entry (the
   ledger prints the whole entry as one string)."
  wire/max-items)

(def max-detail-chars
  "The longest string kept in a journaled detail value (:args or :answer);
   the rest is cut and cut-marker appended. Shared by both."
  1000)

(def cut-marker
  "L0: what a string the journal's size bound cut ends with."
  "<cut>")

(defn- bounded-detail
  "L0 Promote: wire-form `v` bounded for the journal, the one definition
   shared by :args and :answer: any sequence or set keeps its first
   max-detail-items, any string its first max-detail-chars (cut-marker
   appended). Answers [bounded cut?]; cut? is true when anything was cut,
   at any depth. Never throws."
  [v]
  (let [cut? (atom false)]
    (letfn [(walk [x]
              (cond
                (map? x)        (into {} (map (fn [[k y]] [k (walk y)])) x)
                (set? x)        (do (when (> (count x) max-detail-items) (reset! cut? true))
                                    (into #{} (comp (take max-detail-items) (map walk)) x))
                (sequential? x) (do (when (> (count x) max-detail-items) (reset! cut? true))
                                    (into [] (comp (take max-detail-items) (map walk)) x))
                (string? x)     (if (> (count x) max-detail-chars)
                                  (do (reset! cut? true)
                                      (str (subs x 0 max-detail-chars) cut-marker))
                                  x)
                :else           x))]
      [(wire/->wire (walk v)) @cut?])))

(defn- with-answer
  "L0 Promote: `entry` with the Ok value of Answer `ans` kept as :answer, in
   wire form, bounded by the shared detail bound (bounded-detail), with the
   :redact paths that start at :answer masked inside it, and
   :answer/truncated? true when the bound cut it. An Ok answers only: a
   Pending or a refusal leaves the entry unchanged. Total: never throws."
  [entry ans detail]
  (if (answer/ok? ans)
    (let [redact       (:redact detail)
          paths        (into [] (comp (filter #(= :answer (first %))) (map rest)) redact)
          [value cut?] (bounded-detail (:ok ans))]
      (cond-> (assoc entry :answer (redact-paths value paths))
        cut? (assoc :answer/truncated? true)))
    entry))

(defn- with-caller
  "L0 Promote: `entry` with the caller coordinate of `detail` kept as :caller,
   bounded by the shared detail bound (bounded-detail) and :redact paths that
   start at :caller masked inside it, :caller/truncated? true when the bound
   cut it. Recorded whenever detail carries one, independent of the :args? and
   :answers? flags: it is coordinates, not content. Absent otherwise, so an
   entry without a caller is unchanged. Total: never throws."
  [entry detail]
  (if-let [c (:caller detail)]
    (let [paths        (into [] (comp (filter #(= :caller (first %))) (map rest)) (:redact detail))
          [value cut?] (bounded-detail c)]
      (cond-> (assoc entry :caller (redact-paths value paths))
        cut? (assoc :caller/truncated? true)))
    entry))

(defn- with-approval
  "L0 Promote: `entry` with the human Decision Answer `ans` carries as
   :autonomous/approval metadata, as :approval {:decision :approver :ms},
   whenever there is one. CallDetail :redact paths that start with
   :approval mask inside it, as the other paths mask inside the args."
  [entry ans detail]
  (if-let [d (:autonomous/approval (meta ans))]
    (assoc entry :approval (:approval (redact-paths {:approval d} (:redact detail))))
    entry))

(defn call-entry
  "The CallEntry of a call of `tool-name` with `args` by `grant` that ended in
   Answer `ans` after `ms`. A Pending answer's id is always kept as :pending,
   so the call joins the :autonomous/settled entry that says how it ended.
   `detail` is the app's CallDetail, nil for none: with it the entry carries
   the grant's session; with :caller the agent step that issued the call,
   bounded, :redact paths under :caller masked, :caller/truncated? when cut;
   with :args? the args as sent, bounded, :redact paths replaced,
   :args/truncated? true when the bound cut them; with :answers? an Ok
   answer's value, bounded, :redact paths under :answer masked,
   :answer/truncated? when cut; with :errors? a refusal's :error."
  [tool-name args grant ans ms detail]
  (let [[bounded cut?] (when (:args? detail) (bounded-detail (or args {})))]
    (cond-> {:kind    :autonomous/call
             :actor   (:actor grant)
             :tool    tool-name
             :outcome (answer/outcome ans)
             :ms      ms}
      (answer/pending? ans)
      (assoc :pending (:pending ans))

      (and detail (:session grant))
      (assoc :session (:session grant))

      (:args? detail)
      (assoc :args (redact-paths bounded (:redact detail)))

      (and (:args? detail) cut?)
      (assoc :args/truncated? true)

      (and (:answers? detail) (answer/ok? ans))
      (with-answer ans detail)

      (and (:errors? detail) (answer/refused? ans))
      (assoc :error (:error ans))

      :always
      (with-caller detail)

      :always
      (with-approval ans detail))))

(defn settled-entry
  "The entry saying how the async call parked as `id` ended: Answer `ans`.
   With the app's CallDetail :errors? on, a refusal's :error is kept, as
   call-entry keeps it for a call refused at once; with :answers? on, an Ok
   answer's value is kept, bounded, :redact paths under :answer masked,
   :answer/truncated? when cut, as call-entry keeps it. A human approval
   Decision the answer carries is kept as :approval (see call-entry)."
  [id ans detail]
  (cond-> {:kind    :autonomous/settled
           :pending id
           :outcome (answer/outcome ans)}
    (and (:answers? detail) (answer/ok? ans))
    (with-answer ans detail)

    (and (:errors? detail) (answer/refused? ans))
    (assoc :error (:error ans))

    :always
    (with-approval ans detail)))

(defn unrecorded-decision
  "The human Decision `d` (an :autonomous/approval map, or nil) reduced to
   what can be journaled without redaction: :decision when it is a
   keyword, :ms when it is a number, and :approver always as `redacted`.
   nil when `d` is not a map. Total: never throws."
  [d]
  (when (map? d)
    (cond-> {}
      (keyword? (:decision d)) (assoc :decision (:decision d))
      (number? (:ms d))        (assoc :ms (:ms d))
      (contains? d :approver)  (assoc :approver redacted))))

(defn unrecorded-entry
  "The :autonomous/settled entry of parked `id` whose answer could not be
   recorded: outcome :settle-failed, plus :approval (see
   `unrecorded-decision`) when Answer `ans` carries a human Decision as
   :autonomous/approval metadata. Reads no CallDetail and redacts nothing.
   Total: never throws."
  [id ans]
  (let [d (unrecorded-decision (:autonomous/approval (meta ans)))]
    (cond-> {:kind :autonomous/settled :pending id :outcome :settle-failed}
      (some? d) (assoc :approval d))))
