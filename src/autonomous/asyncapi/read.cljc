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
  "Who is reading, from `grant`: the actor, plus the session."
  [{:keys [actor session]}]
  (str (or actor "anonymous") (when session (str "/" session))))

(defn cursor-of
  "The cursor `reader` last read up to; 0 when it never read."
  [store reader]
  (get @store reader 0))

(defn read-tap
  "Answer of one read of `desc` ({:subject :limit}) by `grant`: the messages
   that reader has not seen, and its cursor advanced past them."
  [tap-atom store desc grant]
  (let [reader (:subject desc)
        key    (reader-key grant)
        since  (cursor-of store key)
        t      @tap-atom
        page   (tap/since t since (:limit desc))]
    (swap! store assoc key (:cursor page))
    (answer/ok (assoc page :subject reader))))
