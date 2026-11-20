(ns autonomous.runtime.invoke
  "Pipeline stratum: carry out the Plan the domain decided.

   collect snapshot -> promote (decision/plan-*) -> run through middleware and
   `invoke-kind` -> settle the value into an Answer -> journal it.

   `invoke-kind` is the open seam (OCP): an adapter adds a tool kind with
   one defmethod and nothing here changes."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.decision :as decision]
            [autonomous.domain.journal :as journal]
            [autonomous.domain.wire :as wire]
            [autonomous.runtime.registry :as registry]
            [clojure.string :as str]))

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

(defn- settle-later!
  "Park thenable `x`; its outcome becomes the parked answer and a journal
   entry (:autonomous/settled), so the ledger knows how an async call ended.
   The entry follows the app's CallDetail as it is when the call settles."
  [x]
  (let [id      (registry/park!)
        settle! (fn [a]
                  (registry/settle! id a)
                  (registry/record! (journal/settled-entry id a (:call-detail (registry/snapshot)))))]
    #?(:cljs    (-> ^js x
                    (.then (fn [v] (settle! (answer/ok (wire/->wire v)))))
                    (.catch (fn [e] (settle! (answer/from-exception e)))))
       :default (future
                  (settle! (try (answer/ok (wire/->wire (deref x)))
                                (catch Throwable t
                                  (answer/from-exception (unwrap-deref-failure t)))))))
    (answer/pending id)))

(defn settle
  "Answer for tool value `x`: Ok now, or Pending when it is still being
   computed. Tool values are opaque, so an :ok key in one is not unwrapped."
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
    (registry/record! (journal/call-entry tool-name args grant ans (- (registry/now-ms) t0)
                                          (:call-detail snap)))
    ans))

(defn- resource-answer
  "Answer for resource result `x`: an Ok (value wire-normalized), Pending or
   refusal map as given; any other value, thenables included, through
   `settle`."
  [x]
  (cond
    (not (map? x))                               (settle x)
    (answer/ok? x)                               (answer/ok (wire/->wire (:ok x)))
    (or (answer/pending? x) (answer/refused? x)) x
    :else                                        (settle x)))

(defn- read-result
  "The runtime read envelope of resource `uri` for result `x`."
  [uri mime x]
  (answer/bind (resource-answer x) #(answer/ok {:uri uri :mime mime :value %})))

(defn read!
  "Plan and run a read of resource `uri` for `grant`. Both a plain resource
   value and an Answer from the resource become one runtime read envelope."
  [uri grant]
  (let [plan (decision/plan-read (registry/snapshot) uri grant)]
    (case (:plan/action plan)
      :refuse (:refusal plan)
      :read   (let [r (:resource plan)]
                (try
                  (read-result uri (:mime r) ((:read r) grant))
                  (catch #?(:clj Throwable :cljs :default) e
                    (answer/from-exception e)))))))
