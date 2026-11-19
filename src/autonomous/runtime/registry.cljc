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
            [malli.error :as me]))

(def pending-ttl-ms (* 10 60 1000))

(defn now-ms []
  #?(:clj (System/currentTimeMillis) :cljs (js/Date.now)))

(defn- empty-state []
  {:app        {:name "app"}
   :tools      {}
   :resources  {}
   :middleware []
   :version    0
   :journal    journal/empty-journal
   :lease      nil
   :pending    {}
   :next-id    0})

(defonce ^:private !state (atom (empty-state)))

(defn snapshot
  "The registry as an immutable value."
  []
  @!state)

(defn clear!
  "Forget everything: tools, resources, middleware, journal, lease, pending
   results. For tests and for a reload that registers from scratch."
  []
  (reset! !state (empty-state))
  nil)

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
  (let [other (get-in st [:tools (:name tool)])]
    (when (and other (not= (:id other) (:id tool))) (:id other))))

(defn add-tool!
  "Register tool `id` from author `spec`; answers its MCP name. Throws on an
   invalid spec or a name another tool already uses."
  [id spec]
  (let [tool (unwrap! (affordance/->tool id spec))]
    (swap! !state
           (fn [st]
             (when-let [other (clashing-id st tool)]
               (throw (ex-info (str "tool name " (:name tool) " is already used by " other)
                               {:autonomous/error :name-clash :name (:name tool)})))
             (bump (assoc-in st [:tools (:name tool)] tool))))
    (:name tool)))

(defn remove-tool!
  "Unregister the tool with id or MCP name `x`."
  [x]
  (swap! !state
         (fn [st]
           (let [names (for [[n t] (:tools st) :when (or (= n x) (= (:id t) x))] n)]
             (bump (update st :tools #(apply dissoc % names))))))
  nil)

(defn add-resource!
  "Register resource `uri` from author `spec`; answers the uri."
  [uri spec]
  (let [r (unwrap! (affordance/->resource uri spec))]
    (swap! !state #(bump (assoc-in % [:resources uri] r)))
    uri))

(defn remove-resource! [uri]
  (swap! !state #(bump (update % :resources dissoc uri)))
  nil)

(defn set-app!
  "Describe the application to agents (AppInfo)."
  [info]
  (when-let [e (m/explain s/AppInfo info)]
    (throw (ex-info "invalid app info" {:autonomous/error :invalid-app :problems (me/humanize e)})))
  (swap! !state #(bump (assoc % :app info)))
  nil)

(defn add-middleware!
  "Wrap every invocation: `mw` is (fn [handler] (fn [tool args grant] ...))."
  [mw]
  (swap! !state update :middleware conj mw)
  nil)

;; =============================================================================
;; Journal and lease
;; =============================================================================

(defn record!
  "Append `entry` to the journal; answers its sequence number."
  [entry]
  (-> (swap! !state update :journal journal/append entry (now-ms) journal/default-cap)
      (get-in [:journal :seq])))

(defn acquire-lease!
  "Apply the domain's lease decision for `actor`. Answers Ok lease or a
   :leased refusal naming the holder."
  [actor ttl-ms]
  (let [now      (now-ms)
        decided  (atom nil)]
    (swap! !state (fn [st]
                    (let [[verdict l] (lease/acquire (:lease st) actor ttl-ms now)]
                      (reset! decided [verdict l])
                      (if (= :granted verdict) (assoc st :lease l) st))))
    (let [[verdict l] @decided]
      (if (= :granted verdict)
        (answer/ok l)
        (answer/refusal :leased (str "lease held by " (:actor l)) l)))))

(defn release-lease!
  [actor]
  (swap! !state update :lease lease/release actor)
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
        st  (swap! !state (fn [st]
                            (let [n (inc (:next-id st))]
                              (-> st
                                  (assoc :next-id n)
                                  (update :pending expire now)
                                  (assoc-in [:pending (str "p" n)] {:status :pending :at now})))))]
    (str "p" (:next-id st))))

(defn settle!
  "Record the final answer `a` of parked id."
  [id a]
  (swap! !state update :pending
         (fn [p] (if (contains? p id) (update p id assoc :status :settled :answer a) p)))
  nil)

(defn take-settled!
  "Answer for parked `id`: the settled answer (removed once taken), Pending
   while it runs, or an :unknown-pending refusal."
  [id]
  (let [e (get-in @!state [:pending id])]
    (case (:status e)
      nil      (answer/refusal :unknown-pending (str "no pending result " id " (expired or already collected)"))
      :pending (answer/pending id)
      (do (swap! !state update :pending dissoc id)
          (:answer e)))))
