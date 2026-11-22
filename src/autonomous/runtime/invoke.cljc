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

(defn- completed
  "L2 Pipeline: the Answer of a settled (non-thenable) value `x`: an Answer
   marked complete (answer/complete?) keeps its variant and metadata, its
   Ok through `project`; any other value is a tool payload for `project`.
   Wire-normalized either way."
  [x project]
  (if (answer/complete? x)
    (with-meta (normalize (answer/bind x project)) (meta x))
    (normalize (project x))))

(defn- guarded
  "L2 Pipeline: `finish` (Answer -> Answer) that never throws: when it
   does, a refusal falls back to itself without :data (the value finish
   could not make safe), any other Answer to itself. The Answer's metadata
   is kept either way."
  [finish]
  (fn [a]
    (with-meta (try (finish a)
                    (catch #?(:clj Throwable :cljs :default) _
                      (if (answer/refused? a) (update a :error dissoc :data) a)))
      (meta a))))

(defn- ran-phrase
  "L2 Promote: what is known of whether the tool behind Answer `a` ran:
   it ran for an Ok, it did not for a refusal carrying a human Decision
   other than :approve, and it may have otherwise."
  [a]
  (let [decision (:decision (:autonomous/approval (meta a)))]
    (cond (answer/ok? a)                          "the tool ran"
          (and decision (not= :approve decision)) (str "the tool did not run (decision " decision ")")
          :else                                   "the tool may have run")))

(defn- failure-name
  "L1 host: the name of the type of what was thrown, `e`: never its
   message or data, which may hold values. Total: never throws."
  [e]
  #?(:clj  (.getName (class e))
     :cljs (if (instance? ExceptionInfo e) "ExceptionInfo" (or (some-> e .-name str) "error"))))

(defn- unrecorded
  "L2 Promote: the :settle-failed refusal standing in for Answer `a` of a
   call whose answer could not be recorded (`e` says why), keeping `a`'s
   :autonomous/approval metadata. Its message says whether the tool ran
   (ran-phrase), that its result is unknown, and the type of `e` only
   (failure-name); its data names `a`'s outcome. Total: never throws."
  [a e]
  (with-meta (answer/refusal :settle-failed
                             (str (ran-phrase a) ", but its result could not be recorded, so it is"
                                  " unknown: check the app's state before you retry ("
                                  (failure-name e) ")")
                             {:outcome (answer/outcome a)})
    (select-keys (meta a) [:autonomous/approval])))

(defn- report-settle-failure!
  "L2 Boundary: say on stderr (the JS console on cljs) what went wrong
   settling parked call `id`, `what`, and the type of `e` that was thrown,
   never its message. Total: never throws."
  [id what e]
  (try
    #?(:clj  (binding [*out* *err*]
               (println "[autonomous] parked call" id what (str "(" (failure-name e) ")")))
       :cljs (js/console.error "[autonomous] parked call" id what (str "(" (failure-name e) ")")))
    (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn- settled!
  "L2 Boundary: settle parked call `id` with Answer `a` and its
   :autonomous/settled entry in one registry swap (registry/settle!). When
   that throws, settle it with the `unrecorded` refusal and a literal entry
   (registry/settle-unrecorded!). An id already journaled (a settle! that
   committed, then threw from a watch on the state) keeps its answer and
   entry, and the throw is reported (report-settle-failure!), as is a
   fallback that throws too. Total: never throws."
  [id a]
  (try
    (registry/settle! id a)
    (catch #?(:clj Throwable :cljs :default) e
      (try
        (when (nil? (registry/settle-unrecorded! id (unrecorded a e)))
          (report-settle-failure! id "settled, but a watch on the runtime state threw" e))
        (catch #?(:clj Throwable :cljs :default) e2
          (report-settle-failure! id "may be unsettled: its fallback settle threw" e2))))))

(defn- settle-later!
  "L2 Pipeline: park thenable `x` and answer its Pending. Its value
   `completed` through `project`, or the refusal of what it fails with,
   then `finish` (Answer -> Answer, already guarded) settles the call."
  [x project finish]
  (let [id (registry/park!)]
    (thenable/chain (thenable/attempt #(thenable/chain x (fn [v] (completed v project)))
                                      answer/from-exception)
                    (comp (partial settled! id) finish))
    (answer/pending id)))

(defn settle
  "L2 Pipeline: a plain return becomes Ok, a thenable becomes Pending,
   and an Answer marked complete (answer/complete?) keeps its variant,
   returned directly or as what a thenable resolves to. Tool values are
   opaque, so an :ok key in one is not unwrapped. `project` (raw -> Answer,
   default answer/ok) maps every Ok value, direct or settled later, before
   wire normalization. `finish` (Answer -> Answer, default identity) maps
   the final Answer, direct or settled later, refusals included; if it
   throws, a refusal is kept without its :data, so a call always settles."
  ([x] (settle x answer/ok))
  ([x project] (settle x project identity))
  ([x project finish]
   (let [finish (guarded finish)]
     (if (thenable? x)
       (settle-later! x project finish)
       (finish (completed x project))))))

(defn- invoke-gated
  "L2 Pipeline: the tool's invocation (use! wrappers around invoke-kind)
   behind its approval gate (autonomous.decorator/gate), which shows the
   human the args with the app's CallDetail :redact paths masked."
  [{:keys [tool args grant] :as ctx}]
  (decorator/gate tool args grant (:redact (:call-detail (registry/snapshot)))
                  #((chain/handler ctx invoke-kind) tool args grant)))

(defn- terminal
  "L2 Pipeline: the chain's terminal over context {:tool :args :grant}:
   the gated invocation (invoke-gated), settled through the tool's output
   projection (autonomous.decorator/project-output). What the invocation
   throws becomes its refusal here, so the tool's refusal projection
   (autonomous.decorator/project-refusal) sees it as it sees a refusal
   answered or settled later."
  [{:keys [tool] :as ctx}]
  (let [finish (partial decorator/project-refusal tool)]
    (try
      (settle (invoke-gated ctx) (partial decorator/project-output tool) finish)
      (catch #?(:clj Throwable :cljs :default) e
        ((guarded finish) (answer/from-exception e))))))

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
