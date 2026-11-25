(ns autonomous.runtime.invoke
  "Pipeline stratum: carry out the Plan the domain decided.

   collect snapshot -> promote (decision/plan-*) -> run through middleware and
   `invoke-kind` -> settle the value into an Answer -> journal it.

   `invoke-kind` is the open seam (OCP): an adapter adds a tool kind with
   one defmethod and nothing here changes."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.decision :as decision]
            [autonomous.domain.wire :as wire]
            [autonomous.runtime.registry :as registry]
            [autonomous.runtime.trace :as trace]
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
   entry (:autonomous/settled), so the ledger knows how an async call ended."
  [x]
  (let [id      (registry/park!)
        ctx     (trace/current)
        settle! (fn [a]
                  (registry/settle! id a)
                  (registry/record! {:kind :autonomous/settled :pending id :outcome (answer/outcome a)})
                  (when (trace/enabled?)
                    (trace/event! (str "settled " id) :agent/settled
                                  (merge {:parent ctx :attrs {:pending id :outcome (answer/outcome a)}}
                                         (when (answer/refused? a)
                                           {:status :error :error {:message (get-in a [:error :message])}})))))]
    #?(:cljs    (-> ^js x
                    (.then (fn [v] (settle! (answer/ok (wire/->wire v)))))
                    (.catch (fn [e] (settle! (answer/from-exception e)))))
       :default (future
                  (settle! (try (answer/ok (wire/->wire (deref x)))
                                (catch Throwable t
                                  (answer/from-exception (unwrap-deref-failure t)))))))
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

(defn span-outcome
  "How an :agent/call span ends for Answer `a`: an error when refused, the
   pending id when still running, the result when payloads are kept."
  [a]
  (cond
    (answer/refused? a) {:status :error
                         :error  (select-keys (:error a) [:type :message])
                         :attrs  {:autonomous.outcome :refused
                                  :error.type (str (get-in a [:error :type]))}}
    (answer/pending? a) {:attrs {:autonomous.outcome :pending :autonomous.pending (:pending a)}}
    :else               {:attrs (cond-> {:autonomous.outcome :ok}
                                  (trace/payloads?) (assoc :autonomous.result (:ok a)))}))

(defn call!
  "Plan and run a call of `tool-name` with `args` for `grant`; journal the
   actor and outcome. Answers an Answer."
  [tool-name args grant]
  (let [t0   (registry/now-ms)
        snap (registry/snapshot)
        plan (decision/plan-call snap tool-name args grant t0)
        span (when (trace/enabled?)
               (trace/start tool-name :agent/call
                            {:actor (:actor grant)
                             :attrs (cond-> {:autonomous.tool tool-name}
                                      (trace/payloads?) (assoc :autonomous.args (wire/->wire args)))}))
        ans  (trace/with-context (or (trace/context-of span) (trace/current))
               #(case (:plan/action plan)
                  :invoke (run-invoke plan grant (:middleware snap))
                  :refuse (:refusal plan)))]
    (when span (trace/finish! span (span-outcome ans)))
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
