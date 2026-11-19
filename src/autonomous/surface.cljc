(ns autonomous.surface
  "L1 Surface: what an app lets agents do and see, as a PURE VALUE
   {:app :tools :resources :interceptors}. No runtime state lives here
   (journal, leases, pending results belong to a Runtime instance,
   autonomous.runtime.registry). Derivation builds a surface, decorators
   transform one, `merge-surfaces` composes them; a name two different
   affordances would share is an ERROR VALUE, never last-writer-wins.

   Pure: every function answers the value or {:ok v} / a refusal
   (autonomous.domain.answer), never throws."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.schema :as s]
            [malli.core :as m]
            [malli.error :as me]))

(def Interceptor
  "One link of the agent-call chain, as DATA so it can be inspected and
   ordered: :enter (fn [ctx] ctx'), :leave (fn [ctx] ctx'), :error
   (fn [ctx ex] ctx'). ctx carries at least {:tool :args :grant}; :enter
   may refuse by setting :refusal, :leave sees :answer."
  [:map
   [:name :keyword]
   [:enter {:optional true} fn?]
   [:leave {:optional true} fn?]
   [:error {:optional true} fn?]])

(def Surface
  "The L1 value: compiled affordances (autonomous.domain.affordance) plus
   the call chain. Tools are keyed by MCP name, resources by URI."
  [:map
   [:app s/AppInfo]
   [:tools [:map-of s/ToolName s/Tool]]
   [:resources [:map-of :string s/Resource]]
   [:interceptors [:vector Interceptor]]])

(def empty-surface
  "The surface of an app that exposes nothing: what unmounting leaves."
  {:app {:name "app"} :tools {} :resources {} :interceptors []})

(defn surface?
  "True when `x` is a valid Surface value."
  [x]
  (nil? (m/explain Surface x)))

(defn ->surface
  "Ok of the Surface of partial map `m` (missing keys take the empty
   surface's), or an :invalid-surface refusal naming the problems."
  [m]
  (let [s (merge empty-surface m)]
    (if-let [e (m/explain Surface s)]
      (answer/refusal :invalid-surface "not a valid surface" (me/humanize e))
      (answer/ok s))))

(defn- clash
  [what n left right]
  (answer/refusal :name-clash
                  (str what " " n " is declared by two different affordances")
                  {:name n :left left :right right}))

(defn- merge-tools
  "Tools of `a` and `b`: the same registration (same :id) twice keeps the
   first; a name two different ids claim is a refusal."
  [a b]
  (reduce-kv (fn [acc n t]
               (if-let [other (get acc n)]
                 (if (= (:id other) (:id t))
                   acc
                   (reduced (clash "tool" n (:id other) (:id t))))
                 (assoc acc n t)))
             a b))

(defn- merge-resources
  "Resources of `a` and `b` by URI: the same description twice keeps the
   first (readers are fns, compared by their metadata); two different
   descriptions of one URI are a refusal."
  [a b]
  (reduce-kv (fn [acc uri r]
               (if-let [other (get acc uri)]
                 (if (= (dissoc other :read) (dissoc r :read))
                   acc
                   (reduced (clash "resource" uri (:name other) (:name r))))
                 (assoc acc uri r)))
             a b))

(defn- merge-interceptors
  "Chains of `a` then `b`; one :name twice is a refusal (the chain must
   stay inspectable and unambiguous)."
  [a b]
  (reduce (fn [acc i]
            (if (some #(= (:name %) (:name i)) acc)
              (reduced (clash "interceptor" (:name i) (:name i) (:name i)))
              (conj acc i)))
          (vec a) b))

(defn- merge-app
  "The more specific AppInfo: the empty surface's app yields, a same-named
   app is refined key by key (b wins a shared key); two different named
   apps are a clash."
  [a b]
  (cond
    (= a b)                    a
    (= a (:app empty-surface)) b
    (= b (:app empty-surface)) a
    (= (:name a) (:name b))    (merge a b)
    :else                      ::clash))

(defn merge-surfaces
  "Ok of the composition of surfaces, left to right, or a :name-clash /
   :invalid-surface / :app-clash refusal. The identity is `empty-surface`;
   merging is associative on disjoint names."
  ([] empty-surface)
  ([s] (->surface s))
  ([a b]
   (let [va (->surface a)
         vb (->surface b)]
     (cond
       (answer/refused? va) va
       (answer/refused? vb) vb
       :else
       (let [a (:ok va) b (:ok vb)
             app (merge-app (:app a) (:app b))]
         (if (= ::clash app)
           (answer/refusal :app-clash "two surfaces name different apps"
                           {:left (:app a) :right (:app b)})
           (let [merged (reduce (fn [acc [k f]]
                                  (let [v (f (get acc k) (get b k))]
                                    (if (answer/refused? v) (reduced v) (assoc acc k v))))
                                (assoc a :app app)
                                [[:tools merge-tools]
                                 [:resources merge-resources]
                                 [:interceptors merge-interceptors]])]
             (if (answer/refused? merged) merged (answer/ok merged))))))))
  ([a b & more]
   (reduce (fn [acc s]
             (let [m (merge-surfaces acc s)]
               (if (answer/refused? m) (reduced m) (:ok m))))
           (let [m (merge-surfaces a b)]
             (if (answer/refused? m) m (:ok m)))
           more)))