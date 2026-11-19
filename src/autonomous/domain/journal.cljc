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

(defn redact-paths
  "`m` with the value at every present path of `paths` replaced by
   `redacted`."
  [m paths]
  (reduce (fn [m p] (if (= ::absent (get-in m p ::absent)) m (assoc-in m p redacted)))
          m paths))

(defn call-entry
  "The CallEntry of a call of `tool-name` with `args` by `grant` that ended in
   Answer `ans` after `ms`. `detail` is the app's CallDetail (nil: none):
   with it the entry carries the grant's session; with :args? the args as
   sent, :redact paths replaced; with :errors? a refusal's :error."
  [tool-name args grant ans ms detail]
  (cond-> {:kind    :autonomous/call
           :actor   (:actor grant)
           :tool    tool-name
           :outcome (answer/outcome ans)
           :ms      ms}
    (and detail (:session grant))
    (assoc :session (:session grant))

    (:args? detail)
    (assoc :args (redact-paths (wire/->wire (or args {})) (:redact detail)))

    (and (:errors? detail) (answer/refused? ans))
    (assoc :error (:error ans))))
