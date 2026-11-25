(ns autonomous.openapi-derive-test
  "Focused tests for the pure OpenAPI derivation strata."
  (:require [autonomous.openapi.derive :as derive]
            [autonomous.openapi.doc :as doc]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- operation [op]
  (let [bundle (doc/bundle {"paths" {"/a" {"post" (assoc op "operationId" "a")}}})]
    (derive/operation bundle (first (derive/operations bundle)))))

(deftest parameters-have-consistent-codec-and-schema-projections
  (let [{:keys [params input]}
        (:ok (operation {"parameters" [{"name" "id" "in" "path" "description" "Identifier"}
                                        {"name" "page" "in" "query"}
                                        {"name" "sid" "in" "cookie" "required" true}
                                        {"name" "unspecified"}]}))]
    (is (= [{:name "id" :in "path" :key :id :required? true}
            {:name "page" :in "query" :key :page :required? false}
            {:name "sid" :in "cookie" :key :sid :required? true}
            {:name "unspecified" :in nil :key :unspecified :required? false}]
           params))
    (is (= [:map [:id {:description "Identifier"} [:any]]
            [:page {:optional true} [:any]] [:sid [:any]] [:unspecified {:optional true} [:any]]]
           input))))

(deftest body-layout-keeps-requiredness-and-parameter-collisions
  (let [schema {"type" "object" "description" "body" "properties" {"x" {"type" "string"}, "y" {}} "required" ["x"]}
        body {"content" {"application/json" {"schema" schema}}}
        flat (fn [required] (:ok (operation {"requestBody" (assoc body "required" required)})))
        nested (:ok (operation {"requestBody" body "parameters" [{"name" "x" "in" "query"}]}))]
    (is (= {:media "application/json" :mode :flat :required? true :keys [:x :y]} (:body (flat true))))
    (is (= [:map [:x [:string]] [:y {:optional true} [:any]]] (:input (flat true))))
    (is (= [:map [:x {:optional true} [:string]] [:y {:optional true} [:any]]] (:input (flat false))))
    (is (= :nested (get-in nested [:body :mode])))
    (is (= [:body {:optional true} [:map {:description "body"} [:x [:string]] [:y {:optional true} [:any]]]]
           (last (:input nested))))))

(deftest no-body-empty-object-and-scalar-are-distinct
  (let [with-schema (fn [schema] (operation {"requestBody" {"content" {"application/json" {"schema" schema}}}}))]
    (is (= {:body nil :input [:map]} (select-keys (:ok (operation {})) [:body :input])))
    (is (= {:body {:media "application/json" :required? false :mode :flat :keys []} :input [:map]}
           (select-keys (:ok (with-schema {"type" "object"})) [:body :input])))
    (is (= [:map [:body {:optional true} [:string]]] (get-in (with-schema {"type" "string"}) [:ok :input])))))

(deftest spec-media-selection-keeps-the-declared-spelling-and-json-rules
  (let [op (operation {"requestBody" {"content" {"Application/Vendor+JSON" {}}}
                       "responses" {204 {}, 200 {"content" {"text/plain" {}}}
                                    "2XX" {"content" (array-map "text/plain" {} "APPLICATION/JSON" {"schema" {"type" "number"}})}}})]
    (is (= "Application/Vendor+JSON" (get-in op [:ok :body :media])))
    (is (= {"200" {:media "text/plain" :schema nil}, "204" {:media nil :schema nil}
            "2XX" {:media "APPLICATION/JSON" :schema [:and 'number?]}}
           (get-in op [:ok :responses])))
    (is (= :openapi/unsupported-media
           (get-in (operation {"requestBody" {"content" {"application/json; charset=utf-8" {}}}}) [:error :type])))))

(deftest explicit-empty-scopes-override-a-single-scope
  (let [source {"paths" {"/a" {"get" {"operationId" "a"}}}}
        spec (fn [cfg] (get-in (derive/derive-tools source {:expose {"a" cfg}}) [:ok 0 1]))]
    (is (= #{} (:scopes (spec {:scope :read :scopes []}))))
    (is (= #{:write} (:scopes (spec {:scope :read :scopes [:write]}))))
    (is (= #{:read} (:scopes (spec {:scope :read}))))
    (is (not (contains? (spec {}) :scopes)))))

(deftest the-exposure-guard-answers-before-compiling
  (let [by-id {"a" {}} check #'derive/checked-exposure]
    (is (= :openapi/nothing-exposed (get-in (check {} by-id) [:error :type])))
    (is (= {:unknown ["bad"] :known ["a"]} (get-in (check {:bad {}} by-id) [:error :data])))
    (is (= {:ok {:a {}}} (check {:a {}} by-id)))))

(deftest a-refused-operation-stops-the-fold
  (let [bundle {:root "a.yaml" :docs {"a.yaml" {"paths" {"/a" {"get" {"operationId" "a"}}
                                                          "/b" {"post" {"operationId" "b"
                                                                        "requestBody" {"content" {"multipart/form-data" {}}}}}}}}}
        r (derive/derive-tools bundle {:expose {"a" {} "b" {}}})]
    (is (= :openapi/unsupported-media (get-in r [:error :type])))
    (is (str/includes? (get-in r [:error :message]) "operation b")
        "the message names the operation that failed")))

(deftest parameters-are-deduplicated-by-name-and-location
  (let [bundle {:root "a.yaml"
                :docs {"a.yaml" {"paths" {"/a" {"get" {"operationId" "a"
                                                       "parameters" [{"name" "id" "in" "path" "required" true}
                                                                     {"name" "id" "in" "path" "description" "the id"}
                                                                     {"name" "id" "in" "query"}]}}}}}}
        ps (#'derive/params bundle (first (derive/operations bundle)))]
    (is (= 2 (count ps)) "same name in two locations is two parameters")
    (is (= [["id" "path"] ["id" "query"]] (mapv (juxt #(get % "name") #(get % "in")) ps))
        "the first occurrence keeps the position")
    (is (= ["the id" nil] (mapv #(get % "description") ps))
        "the last occurrence of a pair has the content, the first its position")))
