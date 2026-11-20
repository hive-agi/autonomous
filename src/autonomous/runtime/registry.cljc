(ns autonomous.runtime.registry
  "The runtime's one piece of state, and the only namespace that touches it.

   Collect: `snapshot` hands the domain an immutable view to decide on.
   Boundary: the `!` functions apply a decision or a registration. Each
   validation is the domain's (autonomous.domain.affordance); this namespace
   only turns a refusal into an exception at REGISTRATION time, where an
   author error should fail loudly while the app loads."
  (:require [autonomous.domain.affordance :as affordance]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.journal :as journal]
            [autonomous.domain.lease :as lease]
            [autonomous.domain.schema :as s]
            [malli.core :as m]
            [malli.error :as me]
            [autonomous.surface :as surface]
            [autonomous.runtime.chain :as chain]))

(def pending-ttl-ms (* 10 60 1000))

(defn now-ms []
  #?(:clj (System/currentTimeMillis) :cljs (js/Date.now)))

(defn- fresh-state
  "Runtime-only state of a fresh instance: journal, lease, pending results
   and their counters. The surface (what agents may do and see) is a
   separate VALUE (autonomous.surface), never mixed in here. :epoch is
   when this runtime life began: a reload starts a new epoch and restarts
   the journal sequence, so a reader keeps its cursor per epoch."
  []
  {:epoch       (now-ms)
   :version     0
   :journal     journal/empty-journal
   :call-detail nil
   :lease       nil
   :pending     {}
   :next-id     0})

(defprotocol IRuntime
  "L2 read seam: what an adapter may ask of a running instance. Reads
   answer values; mutation stays with this namespace's `!` functions."
  (-snapshot [rt] "One immutable value: the surface's keys beside the runtime's.")
  (-surface [rt] "The Surface value the instance holds, no runtime state."))

#?(:clj (ns-unmap *ns* 'Runtime))

(defrecord ^{:doc "L2: one running instance. `state` is an atom of {:surface Surface
   :epoch :version :journal :lease :pending :next-id}: the surface value
   changes on deploy or reload, the rest on every call. Two runtimes
   share nothing. Deref answers the snapshot."}
  Runtime [state]
  IRuntime
  (-snapshot [_] (let [{:keys [surface] :as st} @state]
                   (merge (dissoc st :surface) surface)))
  (-surface [_] (:surface @state))
  #?@(:clj  [clojure.lang.IDeref (deref [rt] (-snapshot rt))]
      :cljs [IDeref (-deref [rt] (-snapshot rt))]))

(defn runtime?
  "L2: true when `x` is a Runtime instance (the record class is not
   addressable through an alias on every host)."
  [x]
  (instance? Runtime x))

(defn make-runtime
  "A fresh Runtime holding surface value `surface`
   (autonomous.surface/Surface; nil means the empty surface) with an
   empty journal, no lease and no pending results. Throws on an invalid
   surface: an author error fails loudly while the app loads, as
   reg-tool does."
  ([] (make-runtime nil))
  ([surface]
   (let [s (surface/->surface (or surface surface/empty-surface))]
     (when (answer/refused? s)
       (throw (ex-info (str "invalid surface: " (get-in s [:error :message]))
                       {:autonomous/error (get-in s [:error :type])
                        :problems (get-in s [:error :data])})))
     (->Runtime (atom (assoc (fresh-state) :surface (:ok s)))))))

(defonce ^{:doc "The ONE process-wide runtime the facade API (reg-tool, reg-resource,
   use!, the re-frame adapter) acts on. Adapters that own their instance
   (autonomous.ring) never touch it."} default-runtime
  (make-runtime))

;; =============================================================================
;; State seam: the global is only the DEFAULT instance
;; =============================================================================

(def ^:dynamic *state*
  "The Runtime the `!` functions and `snapshot` act on when bound;
   unbound, `default-runtime`, the process-wide instance reg-tool fills.
   An adapter that owns its own surface and runtime state
   (autonomous.ring/wrap-autonomous) binds it to its own Runtime, so two
   wrapped apps in one process never share tools, journal, lease or
   pending results, and removing the wrap removes all of it."
  nil)

(defn- state-ref [] (:state (or *state* default-runtime)))

(defn instance
  "A fresh Runtime private to its holder: the affordances of `surface`
   ({:app :tools :resources :interceptors}, already compiled by
   autonomous.domain.affordance) with an empty journal, no lease and no
   pending results. Kept for callers of the pre-split API; new code uses
   make-runtime. Use it with `with-instance`."
  ([] (instance nil))
  ([surface] (make-runtime surface)))

(defn with-instance
  "Run `f` with no arguments against Runtime `rt` (see `instance`)."
  [rt f]
  (binding [*state* rt] (f)))

(defn snapshot
  "L2 read: the runtime as one immutable value (see IRuntime), of the
   bound instance or the default. The shape the domain decides on,
   unchanged by the split."
  []
  (-snapshot (or *state* default-runtime)))

(defn clear!
  "Forget everything: the surface (tools, resources, interceptors) and
   the runtime state (journal, lease, pending results). For tests and
   for a reload that registers from scratch."
  []
  (reset! (state-ref) (assoc (fresh-state) :surface surface/empty-surface))
  nil)

(defn surface-value
  "L2 read: the Surface value the bound (or default) runtime holds right
   now. Pure data: no journal, lease or pending results travel with it."
  []
  (-surface (or *state* default-runtime)))

(defn- unwrap!
  "The value of an Ok answer; a refusal becomes an ex-info carrying it."
  [a]
  (if (answer/refused? a)
    (let [{:keys [type message data]} (:error a)]
      (throw (ex-info message {:autonomous/error type :problems data})))
    (:ok a)))

(defn- bump [st] (update st :version inc))

;; =============================================================================
;; Affordances
;; =============================================================================

(defn- clashing-id
  "Id of a DIFFERENT tool already registered under `tool`'s name."
  [st tool]
  (let [other (get-in st [:surface :tools (:name tool)])]
    (when (and other (not= (:id other) (:id tool))) (:id other))))

(defn add-tool!
  "Register tool `id` from author `spec`; answers its MCP name. Throws on an
   invalid spec or a name another tool already uses."
  [id spec]
  (let [tool (unwrap! (affordance/->tool id spec))]
    (swap! (state-ref)
           (fn [st]
             (when-let [other (clashing-id st tool)]
               (throw (ex-info (str "tool name " (:name tool) " is already used by " other)
                               {:autonomous/error :name-clash :name (:name tool)})))
             (bump (assoc-in st [:surface :tools (:name tool)] tool))))
    (:name tool)))

(defn remove-tool!
  "Unregister the tool with id or MCP name `x`."
  [x]
  (swap! (state-ref)
         (fn [st]
           (let [names (for [[n t] (get-in st [:surface :tools]) :when (or (= n x) (= (:id t) x))] n)]
             (bump (update-in st [:surface :tools] #(apply dissoc % names))))))
  nil)

(defn add-resource!
  "Register resource `uri` from author `spec`; answers the uri."
  [uri spec]
  (let [r (unwrap! (affordance/->resource uri spec))]
    (swap! (state-ref) #(bump (assoc-in % [:surface :resources uri] r)))
    uri))

(defn remove-resource! [uri]
  (swap! (state-ref) #(bump (update-in % [:surface :resources] dissoc uri)))
  nil)

(defn set-app!
  "Describe the application to agents (AppInfo)."
  [info]
  (when-let [e (m/explain s/AppInfo info)]
    (throw (ex-info "invalid app info" {:autonomous/error :invalid-app :problems (me/humanize e)})))
  (swap! (state-ref) #(bump (assoc-in % [:surface :app] info)))
  nil)

(defn- update-surface!
  "Boundary: replace the bound surface with the Ok of (f surface), bumping
   the version; a refusal throws as an author error and changes nothing."
  [f]
  (swap! (state-ref) #(bump (assoc % :surface (unwrap! (f (:surface %))))))
  nil)

(defn add-interceptor!
  "Append Interceptor `ic` (autonomous.surface/Interceptor) to the call
   chain. Throws on an invalid interceptor or a :name already in the chain."
  [ic]
  (update-surface! #(surface/add-interceptor % ic)))

(defn decorate!
  "Replace the surface with the result of Surface -> Surface fns
   `decorators`, applied left to right. Throws, changing nothing, when any
   result is not a valid Surface."
  [decorators]
  (update-surface! #(surface/decorate % decorators)))

(defn- legacy-name
  "A chain :name for the next use! wrapper of `surface`, unique in its chain."
  [surface]
  (keyword "autonomous.use" (str "mw-" (count (:interceptors surface)))))

(defn add-middleware!
  "Wrap every invocation: `mw` is (fn [handler] (fn [tool args grant] ...)).
   Appends one legacy link to the call chain; the last wrapper registered
   runs outermost."
  [mw]
  (update-surface! #(surface/add-interceptor % (chain/legacy-link (legacy-name %) mw))))

;; =============================================================================
;; Journal and lease
;; =============================================================================

(defn set-call-detail!
  "Set the CallDetail the journal keeps about each tool call (nil: none).
   Throws on an invalid one."
  [detail]
  (when-let [e (and (some? detail) (m/explain s/CallDetail detail))]
    (throw (ex-info "invalid call detail" {:autonomous/error :invalid-call-detail
                                           :problems (me/humanize e)})))
  (swap! (state-ref) assoc :call-detail detail)
  nil)

(defn record!
  "Append `entry` to the journal; answers its sequence number."
  [entry]
  (-> (swap! (state-ref) update :journal journal/append entry (now-ms) journal/default-cap)
      (get-in [:journal :seq])))

(defn acquire-lease!
  "Apply the domain's lease decision for `actor`. Answers Ok lease or a
   :leased refusal naming the holder."
  [actor ttl-ms]
  (let [now      (now-ms)
        decided  (atom nil)]
    (swap! (state-ref) (fn [st]
                    (let [[verdict l] (lease/acquire (:lease st) actor ttl-ms now)]
                      (reset! decided [verdict l])
                      (if (= :granted verdict) (assoc st :lease l) st))))
    (let [[verdict l] @decided]
      (if (= :granted verdict)
        (answer/ok l)
        (answer/refusal :leased (str "lease held by " (:actor l)) l)))))

(defn release-lease!
  [actor]
  (swap! (state-ref) update :lease lease/release actor)
  (answer/ok {:released true}))

;; =============================================================================
;; Parked (async) results
;; =============================================================================

(defn- expire [pending now]
  (into {} (remove (fn [[_ e]] (< (:at e) (- now pending-ttl-ms)))) pending))

(defn park!
  "Reserve an id for a result still being computed."
  []
  (let [now (now-ms)
        st  (swap! (state-ref) (fn [st]
                            (let [n (inc (:next-id st))]
                              (-> st
                                  (assoc :next-id n)
                                  (update :pending expire now)
                                  (assoc-in [:pending (str "p" n)] {:status :pending :at now})))))]
    (str "p" (:next-id st))))

(defn settle!
  "Record the final answer `a` of parked id."
  [id a]
  (swap! (state-ref) update :pending
         (fn [p] (if (contains? p id) (update p id assoc :status :settled :answer a) p)))
  nil)

(defn take-settled!
  "Answer for parked `id`: the settled answer (removed once taken), Pending
   while it runs, or an :unknown-pending refusal."
  [id]
  (let [e (get-in @(state-ref) [:pending id])]
    (case (:status e)
      nil      (answer/refusal :unknown-pending (str "no pending result " id " (expired or already collected)"))
      :pending (answer/pending id)
      (do (swap! (state-ref) update :pending dissoc id)
          (:answer e)))))
