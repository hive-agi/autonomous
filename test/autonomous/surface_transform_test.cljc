(ns autonomous.surface-transform-test
  "Transforming a Surface value: add-interceptor, decorators folded and
   validated by surface/decorate, and the decision layer enforcing the
   scopes require-scope adds."
  (:require [autonomous.decorator :as decorator]
            [autonomous.domain.affordance :as affordance]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.decision :as decision]
            [autonomous.domain.manifest :as manifest]
            [autonomous.surface :as surface]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(defn- tool [id spec]
  (:ok (affordance/->tool id (merge {:description (str id) :handler (fn [_ _] id)} spec))))

(def ^:private fixture
  {:app {:name "d"} :resources {} :interceptors []
   :tools {"d_read"  (tool :d/read {:read-only? true})
           "d_write" (tool :d/write {})}})

(defn- snapshot [s] (assoc s :lease nil))

(def ^:private reader {:actor "r" :scopes #{:read}})

(def ^:private audited
  (decorator/each-tool (decorator/require-scope :audit)))

(deftest add-interceptor-validates-and-refuses-duplicates
  (let [ic {:name :t/one :enter identity}
        s  (:ok (surface/add-interceptor fixture ic))]
    (is (= [ic] (:interceptors s)))
    (is (surface/surface? s))
    (is (= :name-clash (get-in (surface/add-interceptor s ic) [:error :type])))
    (is (= :invalid-interceptor (get-in (surface/add-interceptor fixture {:enter identity}) [:error :type])))
    (is (= :invalid-interceptor (get-in (surface/add-interceptor fixture {:name :t/x :enter 1}) [:error :type])))))

(deftest each-tool-lifts-a-tool-decorator
  (let [s (audited fixture)]
    (is (surface/surface? s))
    (is (= (set (keys (:tools fixture))) (set (keys (:tools s)))))
    (is (every? #(contains? (:scopes %) :audit) (vals (:tools s))))))

(deftest require-scope-is-enforced-by-the-decision-layer
  (let [s (snapshot (:ok (surface/decorate fixture [audited])))]
    (testing "a grant without the scope is refused the call and does not see the tool"
      (is (= :forbidden (get-in (decision/plan-call s "d_read" {} reader 0) [:refusal :error :type])))
      (is (empty? (:tools (manifest/project s reader)))))
    (testing "a grant with it calls and sees the tool"
      (let [g {:actor "r" :scopes #{:read :audit}}]
        (is (= :invoke (:plan/action (decision/plan-call s "d_read" {} g 0))))
        (is (= ["d_read"] (mapv :name (:tools (manifest/project s g)))))))))

(deftest prefix-names-through-decorate
  (let [s (:ok (surface/decorate fixture [(decorator/prefix-names "fleet_")]))]
    (is (= #{"fleet_d_read" "fleet_d_write"} (set (keys (:tools s)))))
    (is (every? (fn [[k t]] (= k (:name t))) (:tools s)))
    (is (= :invoke (:plan/action (decision/plan-call (snapshot s) "fleet_d_read" {} reader 0))))))

(deftest a-decorator-that-breaks-the-surface-is-refused
  (doseq [[why d] [["drops a part" #(dissoc % :app)]
                   ["not a map" (constantly 42)]
                   ["an invalid tool" #(assoc-in % [:tools "x"] {:bad 1})]
                   ["renames without rekeying" (decorator/each-tool #(assoc % :name "other"))]]]
    (is (= :invalid-surface (get-in (surface/decorate fixture [d]) [:error :type])) why)))

(def ^:private decorator-gen
  (gen/elements [(decorator/prefix-names "p_")
                 audited
                 (decorator/each-tool (decorator/redact-output [[:secret]]))
                 identity]))

(defn- tool-view
  "Names and scopes per key: what decorators change, without closures."
  [a]
  (into {} (map (fn [[k t]] [k (select-keys t [:name :scopes])])) (:tools (:ok a))))

(deftest decorate-is-a-validated-left-fold
  (let [r (tc/quick-check
           100
           (prop/for-all [ds (gen/vector decorator-gen 0 4)]
             (let [a (surface/decorate fixture ds)]
               (and (answer/ok? a)
                    (surface/surface? (:ok a))
                    (= (tool-view a)
                       (tool-view (surface/decorate fixture [(apply comp identity (reverse ds))])))))))]
    (is (true? (:result r)) (pr-str r)))
  (is (= {:ok fixture} (surface/decorate fixture [])) "no decorators is the identity"))
