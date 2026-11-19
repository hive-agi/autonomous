(ns autonomous.surface-test
  "The Surface value: schema validity, composition, and clash-as-error."
  (:require [autonomous.domain.affordance :as affordance]
            [autonomous.domain.answer :as answer]
            [autonomous.surface :as surface]
            [clojure.test :refer [deftest is testing]]))

(defn- tool [id]
  (:ok (affordance/->tool id {:description (str id)
                              :handler (fn [_ _] id)})))

(defn- resource [uri]
  (:ok (affordance/->resource uri {:read (fn [_] uri)})))

(defn- s-of
  ([tools] (s-of tools {}))
  ([tools resources]
   {:app {:name "t"} :tools tools :resources resources :interceptors []}))

(deftest empty-surface-is-valid-and-exposes-nothing
  (is (surface/surface? surface/empty-surface))
  (is (= {} (:tools surface/empty-surface)))
  (is (= {:ok surface/empty-surface} (surface/->surface {}))))

(deftest surface-schema-rejects-bad-shapes
  (is (not (surface/surface? {})))
  (is (not (surface/surface? {:app {:name 1} :tools {} :resources {} :interceptors []})))
  (is (not (surface/surface? {:app {:name "a"} :tools {"x" {:no :tool}}
                              :resources {} :interceptors []})))
  (is (answer/refused? (surface/->surface {:tools {"bad" {:name "bad"}}}))))

(deftest derived-values-satisfy-the-schema
  (let [s (s-of {"t_add" (tool :t/add)} {"app://x" (resource "app://x")})]
    (is (surface/surface? s))
    (is (= {:ok s} (surface/->surface s)))))

(deftest merge-unions-disjoint-names
  (let [a (s-of {"a_x" (tool :a/x)})
        b (s-of {"b_y" (tool :b/y)} {"app://y" (resource "app://y")})
        m (surface/merge-surfaces a b)]
    (is (answer/ok? m))
    (is (= #{"a_x" "b_y"} (set (keys (:tools (:ok m))))))
    (is (= #{"app://y"} (set (keys (:resources (:ok m))))))
    (is (surface/surface? (:ok m)))))

(deftest merge-refuses-a-name-two-ids-claim
  (let [a (s-of {"same" (tool :a/x)})
        b (s-of {"same" (tool :b/y)})
        m (surface/merge-surfaces a b)]
    (is (answer/refused? m))
    (is (= :name-clash (get-in m [:error :type])))
    (testing "never last-writer-wins: neither tool survives silently"
      (is (not (contains? m :ok))))))

(deftest merge-keeps-one-registration-seen-twice
  (let [t (tool :a/x)
        a (s-of {"a_x" t})]
    (is (= {:ok a} (surface/merge-surfaces a a)))
    (is (= {:ok a} (surface/merge-surfaces a (s-of {"a_x" t}))))))

(deftest merge-refuses-two-readers-of-one-uri
  (let [a (s-of {} {"app://x" (assoc (resource "app://x") :description "one")})
        b (s-of {} {"app://x" (assoc (resource "app://x") :description "two")})]
    (is (= :name-clash (get-in (surface/merge-surfaces a b) [:error :type])))))

(deftest merge-concatenates-chains-and-refuses-duplicate-names
  (let [i1 {:name :i/one :enter identity}
        i2 {:name :i/two :leave identity}
        a (assoc (s-of {}) :interceptors [i1])
        b (assoc (s-of {}) :interceptors [i2])
        m (surface/merge-surfaces a b)]
    (is (= [i1 i2] (:interceptors (:ok m))))
    (is (= :name-clash (get-in (surface/merge-surfaces a a) [:error :type])))))

(deftest merge-of-apps
  (let [a (s-of {}) b (assoc (s-of {}) :app {:name "t" :description "more"})]
    (is (= {:name "t" :description "more"} (:app (:ok (surface/merge-surfaces a b)))))
    (is (= :app-clash (get-in (surface/merge-surfaces
                               (assoc (s-of {}) :app {:name "left"})
                               (assoc (s-of {}) :app {:name "right"}))
                              [:error :type])))))

(deftest merge-is-associative-and-identity-on-disjoint-names
  (let [a (s-of {"a_x" (tool :a/x)})
        b (s-of {"b_y" (tool :b/y)})
        c (s-of {} {"app://z" (resource "app://z")})
        ab-c (surface/merge-surfaces (:ok (surface/merge-surfaces a b)) c)
        a-bc (surface/merge-surfaces a (:ok (surface/merge-surfaces b c)))]
    (is (= ab-c a-bc))
    (is (= {:ok a} (surface/merge-surfaces surface/empty-surface a)))
    (is (= {:ok a} (surface/merge-surfaces a surface/empty-surface)))))

(deftest variadic-merge-short-circuits-on-clash
  (let [a (s-of {"same" (tool :a/x)})
        b (s-of {"same" (tool :b/y)})
        c (s-of {"c_z" (tool :c/z)})]
    (is (= :name-clash (get-in (surface/merge-surfaces a b c) [:error :type])))
    (is (= #{"a_x" "b_y" "c_z"}
           (set (keys (:tools (:ok (surface/merge-surfaces
                                    (s-of {"a_x" (tool :a/x) "b_y" (tool :b/y)})
                                    c)))))))))