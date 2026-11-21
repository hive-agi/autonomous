(ns autonomous.surface-test
  "The Surface value: schema validity, composition, and clash-as-error."
  (:require [autonomous.domain.affordance :as affordance]
            [autonomous.domain.answer :as answer]
            [autonomous.surface :as surface]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [autonomous.decorator :as decorator]
            [malli.core :as m]))

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
  (is (= {:ok surface/empty-surface} (surface/->surface {})))
  (is (= {:ok surface/empty-surface} (surface/merge-surfaces))
      "the fold of nothing is Ok of the identity, never doubly wrapped"))

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

(deftest merge-refuses-one-id-under-one-name-with-different-data
  (let [t (tool :a/x)
        a (s-of {"a_x" t})]
    (doseq [[why t'] [["scopes" (assoc t :scopes #{:admin})]
                      ["description" (assoc t :description "another")]
                      ["read-only?" (assoc t :read-only? true)]]]
      (is (= :name-clash (get-in (surface/merge-surfaces a (s-of {"a_x" t'})) [:error :type]))
          (str "never first-writer-wins on " why)))
    (is (= {:ok a} (surface/merge-surfaces a (s-of {"a_x" (assoc t :handler (fn [_ _] :other))})))
        "only fn-valued fields may differ")))

(deftest merge-of-two-equally-decorated-surfaces-keeps-one
  (let [a (s-of {"a_x" (tool :a/x)})
        d (comp (decorator/each-tool (decorator/redact-output [[:secret]]))
                (decorator/each-tool (decorator/redact-refusal [[:token]])))]
    (is (= {:ok (d a)} (surface/merge-surfaces (d a) (d a)))
        "each application builds equal redaction data, never a fresh closure")
    (is (= :name-clash (get-in (surface/merge-surfaces
                                (d a) ((decorator/each-tool (decorator/redact-output [[:other]])) a))
                               [:error :type]))
        "different redactions are different affordances")))

(defn- schema-tool
  "Tool :a/x with `output` as its :output, compiled afresh on each call."
  [output]
  (:ok (affordance/->tool :a/x {:description "x" :handler (fn [_ _] 1) :output output})))

(deftest merge-compares-schemas-by-form
  (doseq [[why make] [["a compiled schema" #(m/schema [:map [:n :int]])]
                      ["a compiled schema holding a regex" #(m/schema [:map [:s [:re #"^a+$"]]])]
                      ["a regex in the schema form" #(vector :re (re-pattern "^a+$"))]]]
    (is (answer/ok? (surface/merge-surfaces (s-of {"a_x" (schema-tool (make))})
                                            (s-of {"a_x" (schema-tool (make))})))
        (str "two compilations of " why " are one tool")))
  (is (= :name-clash (get-in (surface/merge-surfaces (s-of {"a_x" (schema-tool (m/schema [:map [:n :int]]))})
                                                     (s-of {"a_x" (schema-tool (m/schema [:map [:n :string]]))}))
                             [:error :type]))
      "different forms still clash"))

(defn- amount-schema
  "[:map [:amount :my/amount]] where :my/amount, from the options
   :registry, is a leaf type validated by `pred`."
  [pred]
  (m/schema [:map [:amount :my/amount]]
            {:registry (merge (m/default-schemas)
                              {:my/amount (m/-simple-schema {:type :my/amount :pred pred})})}))

(deftest schemas-built-with-a-registry-of-their-own-are-compared-by-identity
  (let [strict  (amount-schema #(and (int? %) (< % 100)))
        lenient (amount-schema int?)
        input-tool #(:ok (affordance/->tool :a/x {:description "x" :handler (fn [_ _] 1) :input %}))]
    (is (not= (m/validate strict {:amount 5000}) (m/validate lenient {:amount 5000}))
        "the two schemas share a form but validate differently")
    (is (= :name-clash (get-in (surface/merge-surfaces (s-of {"a_x" (input-tool strict)})
                                                       (s-of {"a_x" (input-tool lenient)}))
                               [:error :type]))
        "never a silent merge that drops the strict limit")
    (is (answer/ok? (surface/merge-surfaces (s-of {"a_x" (input-tool strict)})
                                            (s-of {"a_x" (input-tool strict)})))
        "the one schema value seen twice is one tool")))

(deftest regexes-that-differ-only-in-flags-clash
  (let [ci (java.util.regex.Pattern/compile "^a+$" java.util.regex.Pattern/CASE_INSENSITIVE)
        cs (java.util.regex.Pattern/compile "^a+$")]
    (is (= :name-clash (get-in (surface/merge-surfaces (s-of {"a_x" (schema-tool [:re ci])})
                                                       (s-of {"a_x" (schema-tool [:re cs])}))
                               [:error :type])))
    (is (answer/ok? (surface/merge-surfaces
                     (s-of {"a_x" (schema-tool [:re (java.util.regex.Pattern/compile "^a+$" java.util.regex.Pattern/CASE_INSENSITIVE)])})
                     (s-of {"a_x" (schema-tool [:re ci])})))
        "same source and same flags are one tool")))

(deftest a-clash-names-the-keys-that-differ
  (let [t (tool :a/x)
        m (surface/merge-surfaces (s-of {"a_x" t})
                                  (s-of {"a_x" (assoc t :scopes #{:admin} :description "other")}))]
    (is (= {:name "a_x" :left :a/x :right :a/x :differs [:description :scopes]}
           (get-in m [:error :data])))))

(deftest the-merged-tool-keeps-the-first-handler
  (let [t (tool :a/x)
        other (assoc t :handler (fn [_ _] :other))
        m (surface/merge-surfaces (s-of {"a_x" t}) (s-of {"a_x" other}))]
    (is (identical? (:handler t) (get-in m [:ok :tools "a_x" :handler]))
        "fn-valued fields are first-writer-wins")))

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
    (is (= {:ok a} (surface/merge-surfaces a a)) "the same link twice is idempotent")
    (let [i1' (assoc i1 :enter (fn [ctx] ctx))]
      (is (= :name-clash (get-in (surface/merge-surfaces
                                  a (assoc (s-of {}) :interceptors [i1']))
                                 [:error :type]))
          "one :name, two different links"))))

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

(def ^:private disjoint-gen
  "One surface whose tool names carry its own prefix, so two generated
   surfaces never share a name."
  (gen/let [prefix (gen/elements ["a" "b" "c" "d"])
            ids    (gen/vector-distinct (gen/elements [:x :y :z]) {:max-elements 3})]
    (s-of (into {} (map (fn [i]
                          (let [id (keyword prefix (name i))]
                            [(str prefix "_" (name i)) (tool id)])))
                ids))))

(defn- one-answer? [a]
  (= 1 (count (select-keys a [:ok :pending :error]))))

(deftest monoid-laws
  (let [r (tc/quick-check
           100
           (prop/for-all [a disjoint-gen b disjoint-gen c disjoint-gen]
             (let [ab   (surface/merge-surfaces a b)
                   a-bc (surface/merge-surfaces a (:ok (surface/merge-surfaces b c)))
                   ab-c (surface/merge-surfaces (:ok ab) c)]
               (and (one-answer? ab)
                    (= a-bc ab-c)
                    (= {:ok a} (surface/merge-surfaces surface/empty-surface a))
                    (= {:ok a} (surface/merge-surfaces a surface/empty-surface))
                    (= {:ok a} (surface/merge-surfaces a))
                    (= {:ok surface/empty-surface} (surface/merge-surfaces))))))]
    (is (true? (:result r)) (pr-str r))))

(deftest clash-is-always-one-refusal-answer
  (let [r (tc/quick-check
           60
           (prop/for-all [id (gen/elements [:x :y :z])]
             (let [m (surface/merge-surfaces (s-of {"same" (tool (keyword "a" (name id)))})
                                             (s-of {"same" (tool (keyword "b" (name id)))}))]
               (and (one-answer? m)
                    (answer/refused? m)
                    (= :name-clash (get-in m [:error :type]))))))]
    (is (true? (:result r)) (pr-str r))))

(deftest answer-railway
  (is (= {:ok 3} (answer/let-answer [a (answer/ok 1) b (answer/ok 2)] (answer/ok (+ a b)))))
  (is (= {:error {:type :no :message "stop"}}
         (answer/let-answer [a (answer/ok 1)
                             b (answer/refusal :no "stop")
                             c (answer/ok (throw (ex-info "never evaluated" {})))]
           (answer/ok c))))
  (is (= {:ok [1 2]} (answer/reduce-answer (fn [acc x] (answer/ok (conj acc x))) [] [1 2])))
  (is (= :stop (get-in (answer/reduce-answer
                        (fn [acc x] (if (= x 2) (answer/refusal :stop "at 2") (answer/ok (conj acc x))))
                        [] [1 2 3])
                       [:error :type]))))