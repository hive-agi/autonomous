(ns autonomous.asyncapi.read
  "Boundary stratum of a live tap: the reader's cursor and the Answer of one
   read. The store is an atom {reader-key -> cursor}, so \"since my last
   read\" is the SERVER's memory and an agent never has to round-trip a
   cursor back.

   A reader is its actor, qualified by its session when it has one, so two
   sessions of one agent do not consume each other's messages."
  (:require [autonomous.asyncapi.tap :as tap]
            [autonomous.domain.answer :as answer]))

(defn reader-key
  "Who is reading, from `grant`: the actor and session as separate map keys,
   so delimiters in either name cannot alias another reader's cursor."
  [{:keys [actor session]}]
  {:actor (or actor "anonymous") :session session})

(defn cursor-of
  "The cursor `reader` last read up to; 0 when it never read."
  [store reader]
  (get @store reader 0))

(defn claim-page
  "Advance `store` and answer the page `reader` claims from `t`. The page and
   cursor update are one atomic operation; concurrent readers cannot claim
   the same messages or move the cursor backwards."
  [store reader t limit]
  (let [claimed (promise)]
    (swap! store (fn [cursors]
                   (let [page (tap/since t (get cursors reader 0) limit)]
                     (deliver claimed page)
                     (assoc cursors reader (:cursor page)))))
    @claimed))

(defn read-tap
  "Answer of one read of `desc` ({:subject :limit}) by `grant`: the messages
   that reader has not seen, and its cursor advanced past them. `page-fn` is
   the injected page operation, defaulting to the tap's pure `since`."
  ([tap-atom store desc grant]
   (read-tap tap-atom store desc grant claim-page))
  ([tap-atom store desc grant page-fn]
   (let [reader (:subject desc)
         key    (reader-key grant)
         t      @tap-atom
         page   (page-fn store key t (:limit desc))]
     (answer/ok (assoc page :subject reader)))))
