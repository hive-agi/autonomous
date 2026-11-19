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
            [malli.error :as me]
            [autonomous.domain.affordance :as affordance]))

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
  "L1 Promote: fill omitted parts and validate a Surface map."
  [value]
  (if (or (nil? value) (map? value))
    (let [s (merge empty-surface value)]
      (if-let [e (m/explain Surface s)]
        (answer/refusal :invalid-surface "not a valid surface" (me/humanize e))
        (answer/ok s)))
    (answer/refusal :invalid-surface "a surface must be a map")))

(defn- add-compiled-tool
  "L1 Promote: add a compiled tool; duplicate declarations are errors."
  [tools {:keys [id name] :as tool}]
  (if-let [other (get tools name)]
    (answer/refusal :name-clash (str "tool name " name " is derived twice")
                    {:name name :ids [(:id other) id]})
    (answer/ok (assoc tools name tool))))

(defn compile-tools
  "L1 Promote: compile [id ToolSpec] pairs into a uniquely keyed tool map."
  [specs]
  (answer/reduce-answer
   (fn [tools [id spec]]
     (answer/bind (affordance/->tool id spec) #(add-compiled-tool tools %)))
   {} specs))

;; =============================================================================
;; Composition: a monoid on Surface. merge-part is OPEN (OCP): a new part
;; of the surface is a new defmethod; merge-surfaces never changes.
;; =============================================================================

(defn- clash
  "L1: the refusal of one name two different values claim."
  [what n left right]
  (answer/refusal :name-clash
                  (str what " " n " is declared by two different values")
                  {:name n :left left :right right}))

(defn- keyed-merge
  "L1: THE keyed merge, one shape for every part: same key with the same
   identity keeps the first; a different identity is a clash refusal.
   `label` is a (fn [v] described-identity) for the refusal."
  [what ident label a b]
  (answer/reduce-answer
   (fn [acc [k v]]
     (let [other (get acc k)]
       (cond
         (nil? other)                  (answer/ok (assoc acc k v))
         (= (ident other) (ident v))   (answer/ok acc)
         :else (clash what k (label other) (label v)))))
   a
   b))

(defmulti merge-part
  "L1: merge part `k` of surfaces `a` and `b` into one value. Answers Ok
   or a refusal. Open: a new surface part adds a method, not an edit."
  (fn [k _a _b] k))

(defmethod merge-part :app [_ a b]
  (let [a (:app a) b (:app b)]
    (cond
      (= a b)                    (answer/ok a)
      (= a (:app empty-surface)) (answer/ok b)
      (= b (:app empty-surface)) (answer/ok a)
      (= (:name a) (:name b))    (answer/ok (merge a b))
      :else (answer/refusal :app-clash "two surfaces name different apps"
                            {:left a :right b}))))

(defmethod merge-part :tools [_ a b]
  (keyed-merge "tool" :id :id (:tools a) (:tools b)))

(defmethod merge-part :resources [_ a b]
  (keyed-merge "resource" #(dissoc % :read) :name (:resources a) (:resources b)))

(defmethod merge-part :interceptors [_ a b]
  (let [by-name #(into (array-map) (map (juxt :name identity)) %)]
    (answer/bind (keyed-merge "interceptor" identity identity
                              (by-name (:interceptors a)) (by-name (:interceptors b)))
                 #(answer/ok (vec (vals %))))))

(defn- merge-parts
  "L1: Ok of `a` with every part replaced by its merge with `b`'s."
  [a b]
  (answer/reduce-answer
   (fn [acc k] (answer/bind (merge-part k acc b) #(answer/ok (assoc acc k %))))
   a
   (keys a)))

(defn- merge-two
  "L1: validate both surfaces, then merge part by part. One railway."
  [a b]
  (answer/let-answer [a (->surface a)
                      b (->surface b)]
    (merge-parts a b)))

(defn merge-surfaces
  "L1: the monoid fold of surfaces, left to right: ALWAYS Ok of the
   composition, or the first refusal (:invalid-surface, :name-clash,
   :app-clash). Identity: `empty-surface`. Associative on disjoint names."
  [& surfaces]
  (answer/reduce-answer merge-two empty-surface surfaces))