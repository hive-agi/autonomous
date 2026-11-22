(ns autonomous.domain.journal
  "The runtime's bounded memory of what happened: a sequenced ring of
   entries. Sequence numbers only grow, so a reader resumes from the last
   number it saw. Pure.

   CallEntry  the entry of one tool call: actor, tool, outcome and duration,
              plus the CallDetail the app opted into: the session, the args
              as sent with redacted paths, the refusal."
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
   `detail` is the app's CallDetail (nil: none): with it the entry carries the
   grant's session; with :args? the args as sent, :redact paths replaced; with
   :errors? a refusal's :error."
  [tool-name args grant ans ms detail]
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
    (assoc :args (redact-paths (wire/->wire (or args {})) (:redact detail)))

    (and (:errors? detail) (answer/refused? ans))
    (assoc :error (:error ans))

    :always
    (with-approval ans detail)))

(defn settled-entry
  "The entry saying how the async call parked as `id` ended: Answer `ans`.
   With the app's CallDetail :errors? on, a refusal's :error is kept, as
   call-entry keeps it for a call refused at once. A human approval
   Decision the answer carries is kept as :approval (see call-entry)."
  [id ans detail]
  (cond-> {:kind    :autonomous/settled
           :pending id
           :outcome (answer/outcome ans)}
    (and (:errors? detail) (answer/refused? ans))
    (assoc :error (:error ans))

    :always
    (with-approval ans detail)))
