(ns autonomous.runtime.invoke
  "Pipeline stratum: carry out the Plan the domain decided.

   collect snapshot -> promote (decision/plan-*) -> run the surface's call
   chain around `invoke-kind` -> settle the value into an Answer -> journal it.

   `invoke-kind` is the open seam (OCP): an adapter adds a tool kind with
   one defmethod and nothing here changes."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.decision :as decision]
            [autonomous.domain.journal :as journal]
            [autonomous.domain.wire :as wire]
            [autonomous.runtime.registry :as registry]
            [clojure.string :as str]
            [autonomous.runtime.chain :as chain]
            [autonomous.decorator :as decorator]))

(defmulti invoke-kind
  "Run `tool` with decoded `args` for `grant`. Answer a value, or a promise or
   future of one. Dispatches on the tool's :kind."
  (fn [tool _args _grant] (:kind tool)))

(defn return-answer
  "L2 Pipeline: distinguish an adapter's Answer from an ordinary tool
   payload (which can itself be a map containing :ok or :error)."
  [a]
  (vary-meta a assoc ::answer true))

(defn invocation-answer?
  "L2 Pipeline: has the invoke-kind method supplied a complete Answer?"
  [value]
  (true? (::answer (meta value))))

(defmethod invoke-kind :fn [tool args grant]
  ((:handler tool) args grant))

(defmethod invoke-kind :default [tool _ _]
  (throw (ex-info (str "no invoker for tool kind " (:kind tool)
                       "; load the adapter namespace that provides it")
                  {:autonomous/error :unknown-kind :kind (:kind tool)})))

#?(:cljs nil
   :default (def ^:private promise-type
              "The class `promise` instances have on this runtime."
              (type (promise))))

(defn thenable?
  "True for a value still being computed: a JS promise; on the JVM, babashka
   and ClojureWasm a future or promise (and, on the JVM, any
   java.util.concurrent.Future). No JVM-only class is named outside the
   :clj-only branch, so the namespace compiles on every runtime."
  [x]
  ;; Not `object?`: that is true only for plain {} objects, never for a
  ;; Promise. The property is read by name so advanced compilation keeps it.
  #?(:cljs    (and (some? x) (fn? (unchecked-get x "then")))
     :cljw    (or (future? x) (instance? promise-type x))
     :bb      (or (future? x) (instance? promise-type x))
     :default (or (future? x) (instance? promise-type x)
                  (instance? java.util.concurrent.Future x))))

(defn- unwrap-deref-failure
  "What a tool threw, given what deref of its future threw: futures wrap a
   failure in java.util.concurrent.ExecutionException. Matched by class
   name, so no runtime has to know the class."
  [t]
  (if (and (ex-cause t) (str/ends-with? (str (type t)) "java.util.concurrent.ExecutionException"))
    (ex-cause t)
    t))

(defn- normalize
  "L2 Pipeline: an Ok value in wire form; Pending and refusals unchanged."
  [a]
  (if (answer/ok? a) (answer/ok (wire/->wire (:ok a))) a))

(defn- settle-later!
  "Park thenable `x`; its value, through `project` (raw -> Answer), becomes
   the parked answer and a journal entry (:autonomous/settled), so the
   ledger knows how an async call ended. The entry follows the app's
   CallDetail as it is when the call settles."
  [x project]
  (let [id      (registry/park!)
        settle! (fn [a]
                  (registry/settle! id a)
                  (registry/record! (journal/settled-entry id a (:call-detail (registry/snapshot)))))]
    #?(:cljs    (-> ^js x
                    (.then (fn [v] (settle! (normalize (project v)))))
                    (.catch (fn [e] (settle! (answer/from-exception e)))))
       :default (future
                  (settle! (try (normalize (project (deref x)))
                                (catch Throwable t
                                  (answer/from-exception (unwrap-deref-failure t)))))))
    (answer/pending id)))

(defn settle
  "L2 Pipeline: a plain return becomes Ok, a thenable becomes Pending,
   and an explicit adapter Answer keeps its variant. `project` (raw ->
   Answer, default answer/ok) maps every Ok value, direct or settled
   later, before wire normalization."
  ([x] (settle x answer/ok))
  ([x project]
   (cond
     (invocation-answer? x) (normalize (answer/bind x project))
     (thenable? x)          (settle-later! x project)
     :else                  (normalize (project x)))))

(defn- terminal
  "L2 Pipeline: the chain's terminal over context {:tool :args :grant}:
   use! wrappers around invoke-kind, settled through the tool's output
   projection (autonomous.decorator/project-output)."
  [{:keys [tool args grant] :as ctx}]
  (settle ((chain/handler ctx invoke-kind) tool args grant)
          (partial decorator/project-output tool)))

(defn- run-invoke
  "L2 Pipeline: the Answer of `plan` run through call chain `links`.
   What any stage throws becomes a refusal (autonomous.runtime.chain)."
  [{:keys [tool args]} grant links]
  (chain/run links {:tool tool :args args :grant grant} terminal))

(defn call!
  "Plan and run a call of `tool-name` with `args` for `grant` through the
   surface's call chain; journal the actor and outcome. Answers an Answer."
  [tool-name args grant]
  (let [t0   (registry/now-ms)
        snap (registry/snapshot)
        plan (decision/plan-call snap tool-name args grant t0)
        ans  (case (:plan/action plan)
               :invoke (run-invoke plan grant (:interceptors snap))
               :refuse (:refusal plan))]
    (registry/record! (journal/call-entry tool-name args grant ans (- (registry/now-ms) t0)
                                          (:call-detail snap)))
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
