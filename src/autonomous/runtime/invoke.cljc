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
            [autonomous.runtime.chain :as chain]
            [autonomous.decorator :as decorator]
            [autonomous.thenable :as thenable]))

(defmulti invoke-kind
  "Run `tool` with decoded `args` for `grant`. Answer a value, or a promise or
   future of one. Dispatches on the tool's :kind."
  (fn [tool _args _grant] (:kind tool)))

(defn return-answer
  "L2 Pipeline: distinguish an adapter's Answer from an ordinary tool
   payload (which can itself be a map containing :ok or :error)."
  [a]
  (answer/complete a))

(defn invocation-answer?
  "L2 Pipeline: has the invoke-kind method supplied a complete Answer?"
  [value]
  (answer/complete? value))

(defmethod invoke-kind :fn [tool args grant]
  ((:handler tool) args grant))

(defmethod invoke-kind :default [tool _ _]
  (throw (ex-info (str "no invoker for tool kind " (:kind tool)
                       "; load the adapter namespace that provides it")
                  {:autonomous/error :unknown-kind :kind (:kind tool)})))

(defn thenable?
  "True for a value still being computed (autonomous.thenable/thenable?)."
  [x]
  (thenable/thenable? x))

(defn- normalize
  "L2 Pipeline: an Ok value in wire form; Pending and refusals unchanged."
  [a]
  (if (answer/ok? a) (answer/ok (wire/->wire (:ok a))) a))

(defn- settle-value
  "L2 Pipeline: the Answer of settled value `v`. A complete Answer keeps its
   variant (and its metadata) with an Ok bound through `project`; any other
   value is projected. Ok values end in wire form."
  [v project]
  (if (answer/complete? v)
    (with-meta (normalize (answer/bind v project)) (meta v))
    (normalize (project v))))

(defn- settled!
  "L2 Boundary: settle parked call `id` with Answer `a` and journal how it
   ended (:autonomous/settled), under the app's CallDetail as it is now."
  [id a]
  (registry/settle! id a)
  (registry/record! (journal/settled-entry id a (:call-detail (registry/snapshot)))))

(defn- settle-later!
  "L2 Pipeline: park thenable `x` and answer its Pending. Its value through
   `settle-value`, or the refusal of what it fails with, settles the call."
  [x project]
  (let [id (registry/park!)]
    (thenable/chain (thenable/attempt #(thenable/chain x (fn [v] (settle-value v project)))
                                      answer/from-exception)
                    #(settled! id %))
    (answer/pending id)))

(defn settle
  "L2 Pipeline: a plain return becomes Ok, a thenable becomes Pending,
   and an Answer marked complete (answer/complete?) keeps its variant.
   Tool values are opaque, so an :ok key in one is not unwrapped.
   `project` (raw -> Answer, default answer/ok) maps every Ok value,
   direct or settled later, before wire normalization."
  ([x] (settle x answer/ok))
  ([x project]
   (if (thenable? x)
     (settle-later! x project)
     (settle-value x project))))

(defn- terminal
  "L2 Pipeline: the chain's terminal over context {:tool :args :grant}:
   use! wrappers around invoke-kind, behind the tool's approval gate
   (autonomous.decorator/gate), settled through its output projection
   (autonomous.decorator/project-output)."
  [{:keys [tool args grant] :as ctx}]
  (settle (decorator/gate tool args grant #((chain/handler ctx invoke-kind) tool args grant))
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
