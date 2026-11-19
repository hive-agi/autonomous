(ns autonomous.runtime.invoke
  "Pipeline stratum: carry out the Plan the domain decided.

   collect snapshot -> promote (decision/plan-*) -> run through middleware and
   `invoke-kind` -> settle the value into an Answer -> journal it.

   `invoke-kind` is the open seam (OCP): an adapter adds a tool kind with
   one defmethod and nothing here changes."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.decision :as decision]
            [autonomous.domain.wire :as wire]
            [autonomous.runtime.registry :as registry]))

(defmulti invoke-kind
  "Run `tool` with decoded `args` for `grant`. Answer a value, or a promise or
   future of one. Dispatches on the tool's :kind."
  (fn [tool _args _grant] (:kind tool)))

(defmethod invoke-kind :fn [tool args grant]
  ((:handler tool) args grant))

(defmethod invoke-kind :default [tool _ _]
  (throw (ex-info (str "no invoker for tool kind " (:kind tool)
                       "; load the adapter namespace that provides it")
                  {:autonomous/error :unknown-kind :kind (:kind tool)})))

(defn thenable?
  "True for a value still being computed: a JS promise, or a JVM future or
   promise."
  [x]
  #?(:clj  (or (instance? java.util.concurrent.Future x)
               (instance? clojure.lang.IBlockingDeref x))
     :cljs (and (some? x) (object? x) (fn? (.-then x)))))

(defn- settle-later!
  "Park thenable `x`; its outcome becomes the parked answer and a journal
   entry (:autonomous/settled), so the ledger knows how an async call ended.
   A JVM future wraps a failure in ExecutionException; the cause is what the
   tool threw."
  [x]
  (let [id      (registry/park!)
        settle! (fn [a]
                  (registry/settle! id a)
                  (registry/record! {:kind :autonomous/settled :pending id :outcome (answer/outcome a)}))]
    #?(:clj  (future
               (settle! (try (answer/ok (wire/->wire (deref x)))
                             (catch java.util.concurrent.ExecutionException e
                               (answer/from-exception (or (.getCause e) e)))
                             (catch Throwable t (answer/from-exception t)))))
       :cljs (-> x
                 (.then (fn [v] (settle! (answer/ok (wire/->wire v)))))
                 (.catch (fn [e] (settle! (answer/from-exception e))))))
    (answer/pending id)))

(defn settle
  "Answer for value `x`: Ok now, or Pending when it is still being computed."
  [x]
  (if (thenable? x) (settle-later! x) (answer/ok (wire/->wire x))))

(defn- run-invoke [{:keys [tool args]} grant middleware]
  (try
    (let [h (reduce (fn [h mw] (mw h)) invoke-kind middleware)]
      (settle (h tool args grant)))
    (catch #?(:clj Throwable :cljs :default) e
      (answer/from-exception e))))

(defn call!
  "Plan and run a call of `tool-name` with `args` for `grant`; journal the
   actor and outcome. Answers an Answer."
  [tool-name args grant]
  (let [t0   (registry/now-ms)
        snap (registry/snapshot)
        plan (decision/plan-call snap tool-name args grant t0)
        ans  (case (:plan/action plan)
               :invoke (run-invoke plan grant (:middleware snap))
               :refuse (:refusal plan))]
    (registry/record! {:kind    :autonomous/call
                       :actor   (:actor grant)
                       :tool    tool-name
                       :outcome (answer/outcome ans)
                       :ms      (- (registry/now-ms) t0)})
    ans))

(defn read!
  "Plan and run a read of resource `uri` for `grant`."
  [uri grant]
  (let [plan (decision/plan-read (registry/snapshot) uri grant)]
    (case (:plan/action plan)
      :refuse (:refusal plan)
      :read   (let [r (:resource plan)]
                (try
                  (let [a (settle ((:read r) grant))]
                    (if (answer/ok? a)
                      (answer/ok {:uri uri :mime (:mime r) :value (:ok a)})
                      a))
                  (catch #?(:clj Throwable :cljs :default) e
                    (answer/from-exception e)))))))
