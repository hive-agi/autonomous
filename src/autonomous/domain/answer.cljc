(ns autonomous.domain.answer
  "Answers: Ok, Pending and Refusal. Every runtime reply is exactly one of
   them, so a bridge never has to guess. Pure."
  (:require [autonomous.domain.wire :as wire]))

(defn ok [v] {:ok v})

(defn pending [id] {:pending id})

(defn refusal
  "A refusal of `type` (keyword) with a `message` written for the agent, and
   optional `data` that says what to change."
  ([type message] (refusal type message nil))
  ([type message data]
   {:error (cond-> {:type type :message message}
             (some? data) (assoc :data (wire/->wire data)))}))

(defn ok? [a] (contains? a :ok))

(defn pending? [a] (contains? a :pending))

(defn refused? [a] (contains? a :error))

(defn outcome
  "The journal outcome of answer `a`: :ok, :pending, or the refusal type."
  [a]
  (cond (ok? a)      :ok
        (pending? a) :pending
        :else        (get-in a [:error :type] :malformed)))

(defn from-exception
  "The refusal an exception becomes. ex-data key :autonomous/error names the
   type (default :tool-threw); the rest of the ex-data is the refusal data."
  [e]
  (let [data (ex-data e)]
    (refusal (or (:autonomous/error data) :tool-threw)
             (or (ex-message e) (str e))
             (not-empty (dissoc data :autonomous/error)))))
