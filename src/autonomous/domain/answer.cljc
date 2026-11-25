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

(defn complete
  "L0: Answer `a` marked complete, so a pipeline settling a tool's value
   keeps its variant instead of treating it as an opaque payload (which
   can itself be a map containing :ok or :error). Pure."
  [a]
  (vary-meta a assoc ::complete true))

(defn complete?
  "L0: is `x` an Answer marked complete (see `complete`)? Pure."
  [x]
  (true? (::complete (meta x))))

(defn bind
  "Railway: when answer `a` is Ok of v, (f v) — itself an Answer; a Pending
   or Refusal passes through untouched, so the next step never runs. Pure."
  [a f]
  (if (ok? a) (f (:ok a)) a))

(defmacro let-answer
  "Railway let: each binding's expression is an Answer; its Ok value is
   bound (destructuring works) and the next step runs. The first Pending
   or Refusal is the value of the whole form; with all Ok, the body's
   value. Steps are pairs, like let. Pure."
  [bindings & body]
  (if (empty? bindings)
    `(do ~@body)
    (let [[sym expr & more] bindings]
      `(bind ~expr (fn [~sym] (let-answer [~@more] ~@body))))))

(defn reduce-answer
  "Railway fold: (f acc v) — an Answer — over `coll` from seed `init`,
   short-circuiting on the first Pending or Refusal f answers. The
   monoid-fold shape for steps that may fail. Pure."
  [f init coll]
  (reduce (fn [a v] (if (ok? a) (f (:ok a) v) (reduced a)))
          (ok init) coll))
