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
            [autonomous.runtime.chain :as chain]
            [autonomous.domain.naming :as naming]))

(def pending-ttl-ms (* 10 60 1000))

(def trace-default-cap
  "Spans the trace ring keeps when the app sets no :cap. Spans are many
   more than journal entries (a click can make ten), so the ring is its own."
  5000)

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
   :trace       journal/empty-journal
   :trace-opts  {:enabled? false :cap trace-default-cap}
   :call-detail nil
   :lease       nil
   :pending     {}
   :next-id     0
   :next-link   0})

(defn- holding
  "State `st` holding `surface` as its base with no decorators, so the
   exposed surface is the base itself."
  [st surface]
  (assoc st :base surface :decorators [] :surface surface))

(defprotocol IRuntime
  "L2 read seam: what an adapter may ask of a running instance. Reads
   answer values; mutation stays with this namespace's `!` functions."
  (-snapshot [rt] "One immutable value: the surface's keys beside the runtime's.")
  (-surface [rt] "The Surface value the instance holds, no runtime state."))

#?(:clj (ns-unmap *ns* 'Runtime))

(defrecord ^{:doc "L2: one running instance. `state` is an atom of {:base Surface
   :decorators [Surface -> Surface] :surface Surface :epoch :version
   :journal :call-detail :lease :pending :next-id}. :base is what was
   registered, :surface what agents see: always the base passed through
   the decorators. The surface changes on deploy or reload, the rest on
   every call. Two runtimes share nothing. Deref answers the snapshot."}
  Runtime [state]
  IRuntime
  (-snapshot [_] (let [{:keys [surface] :as st} @state]
                   (merge (dissoc st :surface :base :decorators) surface)))
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
     (->Runtime (atom (holding (fresh-state) (:ok s)))))))

(defonce ^{:doc "The state atom of `default-runtime`. Only the ATOM is defonce: a
   reload redefines the Runtime record and the IRuntime protocol, so a
   defonce'd record instance would be of the old class and fail protocol
   dispatch. The registrations survive the reload in this atom."}
  default-state
  (:state (make-runtime)))

(def ^{:doc "The ONE process-wide runtime the facade API (reg-tool, reg-resource,
   use!, the re-frame adapter) acts on: a Runtime of the current record
   class over `default-state`, rebuilt on every load of this namespace.
   Adapters that own their instance (autonomous.ring) never touch it."}
  default-runtime
  (->Runtime default-state))

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
  "Forget everything: the registered surface (tools, resources,
   interceptors), its decorators and the runtime state (journal, lease,
   pending results). For tests and for a reload that registers from
   scratch."
  []
  (reset! (state-ref) (holding (fresh-state) surface/empty-surface))
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
;; Transitions: the base changes, the exposed surface follows
;; =============================================================================

(defn- exposed
  "Ok of state `st` whose :surface is its :base passed through its
   :decorators; the refusal of surface/decorate otherwise. Pure."
  [st]
  (answer/bind (surface/decorate (:base st) (:decorators st))
               #(answer/ok (assoc st :surface %))))

(defn- rebased
  "Ok of state `st` with base (f base) and the surface recomputed; the
   first refusal of f or of the decorators otherwise. Pure."
  [st f]
  (answer/bind (f (:base st)) #(exposed (assoc st :base %))))

(defn- transition!
  "Boundary: replace the bound state with the Ok of (f state), bumping the
   version; a refusal throws as an author error and changes nothing."
  [f]
  (swap! (state-ref) #(bump (unwrap! (f %))))
  nil)

(defn- update-base!
  "Boundary: replace the registered surface with the Ok of (f base) and
   recompute the exposed one in the same swap. Throws, changing nothing,
   when f or a decorator refuses."
  [f]
  (transition! #(rebased % f)))

;; =============================================================================
;; Affordances
;; =============================================================================

(defn add-tool!
  "Register tool `id` from author `spec`, replacing an earlier registration
   of the same id; answers its MCP name. Throws, changing nothing, on an
   invalid spec, a name another tool already uses, or a decorator that
   refuses the resulting surface."
  [id spec]
  (let [tool (unwrap! (affordance/->tool id spec))]
    (update-base! #(surface/put-tool % tool))
    (:name tool)))

(defn remove-tool!
  "Unregister the tool with id or MCP name `x` (its registered name).
   The name is the one given at registration, before decorators: under
   (prefix-names \"app_\") remove :t/echo or \"t_echo\". The exposed name
   \"app_t_echo\" matches nothing in the registered surface, so removing
   by it is a no-op, not an error. Prefer the id."
  [x]
  (update-base! #(answer/ok (surface/drop-tools % [x]))))

(defn add-resource!
  "Register resource `uri` from author `spec`; answers the uri."
  [uri spec]
  (let [r (unwrap! (affordance/->resource uri spec))]
    (update-base! #(answer/ok (surface/put-resource % uri r)))
    uri))

(defn remove-resource!
  "Unregister the resource at `uri`."
  [uri]
  (update-base! #(answer/ok (surface/drop-resource % uri))))

(defn set-app!
  "Describe the application to agents (AppInfo). Throws on an invalid one."
  [info]
  (update-base! #(surface/set-app % info)))

(defn add-interceptor!
  "Append Interceptor `ic` (autonomous.surface/Interceptor) to the call
   chain. Throws on an invalid interceptor or a :name already in the chain."
  [ic]
  (update-base! #(surface/add-interceptor % ic)))

(defn decorate!
  "Append Surface -> Surface fns `decorators` to the runtime's decorators.
   They apply, left to right after the earlier ones, to every surface the
   runtime exposes from now on, including later registrations. Throws,
   changing nothing, when any result is not a valid Surface."
  [decorators]
  (transition! #(exposed (update % :decorators into decorators))))

(defn- legacy-name
  "The chain :name of use! wrapper number `n`."
  [n]
  (keyword "autonomous.use" (str "mw-" n)))

(defn- with-legacy-link
  "Ok of state `st` with use! wrapper `mw` appended to its call chain under
   the next number of its :next-link counter, which only grows. Pure."
  [st mw]
  (let [n (:next-link st)]
    (answer/bind (rebased st #(surface/add-interceptor % (chain/legacy-link (legacy-name n) mw)))
                 #(answer/ok (assoc % :next-link (inc n))))))

(defn add-middleware!
  "Wrap every invocation: `mw` is (fn [handler] (fn [tool args grant] ...)).
   Appends one legacy link, named by a counter that never repeats in this
   runtime, to the call chain; the last wrapper registered runs outermost."
  [mw]
  (transition! #(with-legacy-link % mw)))

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

(defn trace-opts
  "How this runtime traces: {:enabled? :cap :payloads? ...}."
  []
  (:trace-opts @(state-ref)))

(defn configure-trace!
  "Merge `opts` into the trace options; answers them. A smaller :cap
   trims the ring at the next span."
  [opts]
  (:trace-opts (swap! (state-ref) update :trace-opts merge opts)))

(defn record-span!
  "Append finished span `span` to the trace ring; answers its sequence
   number."
  [span]
  (-> (swap! (state-ref)
             (fn [st] (update st :trace journal/append span (now-ms)
                              (get-in st [:trace-opts :cap] trace-default-cap))))
      (get-in [:trace :seq])))

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
        (answer/refusal :leased (str "lease held by " (naming/quoted-actor (:actor l)))
                        (lease/refusal-data l))))))

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

(defn- settled
  "State `st` with parked `id` settled to Answer `a` (an id that expired or
   was never parked is not added back) and `entry-of` (fn [st] entry)
   appended to the journal at `now`. Pure."
  [st id a entry-of now]
  (-> st
      (update :pending (fn [p] (if (contains? p id) (update p id assoc :status :settled :answer a) p)))
      (update :journal journal/append (entry-of st) now journal/default-cap)))

(defn- settle-with!
  "Boundary: settle parked `id` with Answer `a` and journal (entry-of state)
   in one swap; answers the entry's sequence number."
  [id a entry-of]
  (let [now (now-ms)]
    (get-in (swap! (state-ref) settled id a entry-of now) [:journal :seq])))

(defn settle!
  "Record the final Answer `a` of parked `id` and its :autonomous/settled
   entry (journal/settled-entry under the CallDetail the state holds) in
   one swap. Answers the entry's sequence number. Throws, changing
   nothing, when the entry cannot be made."
  [id a]
  (settle-with! id a #(journal/settled-entry id a (:call-detail %))))

(defn settle-unrecorded!
  "Record fallback Answer `a` of parked `id` and its literal
   journal/unrecorded-entry in one swap. Reads no CallDetail and redacts
   nothing, so it does not throw on what made settle! throw. Answers the
   entry's sequence number."
  [id a]
  (let [entry (journal/unrecorded-entry id a)]
    (settle-with! id a (constantly entry))))

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
