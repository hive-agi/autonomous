(ns autonomous.domain.journal
  "The runtime's bounded memory of what happened: a sequenced ring of
   entries. Sequence numbers only grow, so a reader resumes from the last
   number it saw. Pure.")

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
