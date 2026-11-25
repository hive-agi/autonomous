(ns autonomous.runtime.chain
  "L2 Pipeline: reduce an ordered vector of interceptor data around an
   injected terminal. The runner itself owns no state or host effects."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.schema :as s]
            [malli.core :as m]))

(def ^:private valid-answer? (m/validator s/Answer))

(defn- valid-stage?
  "L2 Pipeline: once a stage supplies an Answer, only another Answer may replace it."
  [before after]
  (and (map? after)
       (if (contains? after :answer)
         (let [a (:answer after)]
           (and (valid-answer? a) (= 1 (count (select-keys a [:ok :error :pending])))))
         (not (contains? before :answer)))))

(defn- run-stage
  "L2 boundary: contain an interceptor exception or malformed stage result."
  [stage ctx]
  (try
    (let [next (stage ctx)]
      (if (valid-stage? ctx next)
        next
        (assoc ctx :answer (answer/refusal :interceptor/context "stage must return a valid call context"))))
    (catch #?(:clj Throwable :cljs :default) e
      (assoc ctx :answer (answer/from-exception e)))))

(defn- advance
  "L2 Pipeline: enter one link and stop entering at a supplied Answer."
  [{:keys [context stack]} link]
  (let [next (run-stage (:enter link identity) context)
        frame {:context next :stack (conj stack link)}]
    (if (contains? next :answer) (reduced frame) frame)))

(defn- finish
  "L2 Pipeline: invoke the terminal unless entry already answered."
  [terminal ctx]
  (if (contains? ctx :answer)
    ctx
    (run-stage #(assoc % :answer (terminal %)) ctx)))

(defn- unwind
  "L2 Pipeline: run error on a refusal, leave on Ok or Pending. An outer
   link sees the Answer left by the inner link, including any recovery."
  [ctx link]
  (let [a (:answer ctx)]
    (if (answer/refused? a)
      (if-let [recover (:error link)] (run-stage #(recover % a) ctx) ctx)
      (run-stage (:leave link identity) ctx))))

(defn run
  "L2 Pipeline: enter in order, answer with the terminal, and unwind only
   entered links in reverse order. :error takes [context refusal]; all
   stages return context. Leave sees Pending, not its future settlement."
  [links ctx terminal]
  (let [{:keys [context stack]} (reduce advance {:context ctx :stack []} links)]
    (:answer (reduce unwind (finish terminal context) (rseq stack)))))

(defn legacy-link
  "L2 compatibility: represent a legacy use! wrapper as one named link."
  [id mw]
  {:name id :enter #(update % ::legacy (fnil conj []) mw)})

(defn handler
  "L2 compatibility: use! has always wrapped the last registration outside
   the first one. Preserve arbitrary wrappers, including ones that call the
   handler more than once or not at all."
  [ctx invoke]
  (reduce (fn [next wrap] (wrap next)) invoke (::legacy ctx)))
