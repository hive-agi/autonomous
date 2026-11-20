(ns autonomous.openapi-pure-strata-test
  "Focused tests for the small pure steps of the OpenAPI Source: document
   paths and pointers, JSON Schema pieces, codec request and refusal
   parts, exposure guards and the surface fold step."
  (:require [autonomous.openapi :as openapi]
            [autonomous.openapi.codec :as codec]
            [autonomous.openapi.derive :as derive]
            [autonomous.openapi.doc :as doc]
            [autonomous.openapi.schema :as schema]
            [clojure.test :refer [deftest is testing]]))

;; -----------------------------------------------------------------------------
;; doc

(deftest keys-normalize-to-strings-without-colons
  (let [key-string #'doc/key-string]
    (is (= "a" (key-string :a)))
    (is (= "x/a" (key-string :x/a)))
    (is (= "200" (key-string 200)))))

(deftest segments-fold-dots-and-keep-leading-parents
  (let [fold #'doc/fold-segment]
    (is (= ["a"] (fold ["a"] ".")))
    (is (= ["a"] (fold ["a"] "")))
    (is (= [] (fold ["a"] "..")))
    (is (= [".."] (fold [] "..")))
    (is (= [".." ".."] (fold [".."] "..")))))

(deftest pointer-children-are-total
  (let [child #'doc/child]
    (is (= 1 (child {"a" 1} "a")))
    (is (nil? (child {"a" nil} "a")) "a present nil is a value, not missing")
    (is (= ::doc/missing (child {} "a")))
    (is (= :b (child [:a :b] "1")))
    (is (= ::doc/missing (child [:a] "1")))
    (is (= ::doc/missing (child [:a] "-1")))
    (is (= ::doc/missing (child "leaf" "0")))))

(deftest refs-resolve-with-siblings-or-become-markers
  (let [b (doc/bundle {"x" {"type" "string"} "self" {"$ref" "#/self"}})]
    (is (= {"type" "string" "description" "d"} (doc/resolved b {"$ref" "#/x" "description" "d"})))
    (is (= {"x-autonomous-unresolved" "#/nope"} (doc/resolved b {"$ref" "#/nope"})))
    (is (= {"x-autonomous-cycle" "#/self"} (doc/resolved b {"$ref" "#/self"})))))

;; -----------------------------------------------------------------------------
;; schema

(deftest combinators-are-read-by-keyword-not-type
  (let [combinator? #'schema/combinator?]
    (is (combinator? {"type" "string" "enum" ["a"]}))
    (is (not (combinator? {"type" "string"})))
    (is (= [:enum "a"] (schema/->malli {"type" "string" "enum" ["a" nil {}]})))))

(deftest consts-and-enums-refuse-collection-literals
  (let [const-schema #'schema/const-schema enum-schema #'schema/enum-schema]
    (is (= [:= 3] (const-schema 3)))
    (is (= [:any] (const-schema [3])))
    (is (= [:enum 1 2] (enum-schema [1 nil 2 [3]])))
    (is (= [:any] (enum-schema [nil {}])))))

(deftest arrays-carry-bounds-and-items
  (let [array-schema #'schema/array-schema]
    (is (= [:vector :any] (array-schema {} {})))
    (is (= [:vector {:min 1} [:boolean]] (array-schema {"minItems" 1 "items" {"type" "boolean"}} {})))))

(deftest input-properties-drop-read-only-ones
  (let [entries #'schema/property-entries
        s {"properties" {"b" {"readOnly" true} "a" {}} "required" ["a"]}]
    (is (= [[:a {} [:any]]] (vec (entries s {:input? true}))))
    (is (= [:a :b] (mapv first (entries s {}))))
    (is (= [:map [:a [:any]] [:b {:optional true} [:any]]] (schema/->malli (assoc s "type" "object"))))))

(deftest all-of-merges-objects-and-intersects-the-rest
  (let [all-of #'schema/all-of-schema]
    (is (= [:map [:a [:any]] [:b {:optional true} [:any]]]
           (all-of {"allOf" [{"properties" {"a" {}} "required" ["a"]} {"type" "object" "properties" {"b" {}}}]} {})))
    (is (= [:and [:string] [:int]] (all-of {"allOf" [{"type" "string"} {"type" "integer"}]} {})))))

;; -----------------------------------------------------------------------------
;; codec

(deftest flat-bodies-keep-declared-non-nil-args
  (let [flat-body #'codec/flat-body]
    (is (= {:a 1} (flat-body {:keys [:a :b]} {:a 1 :b nil :c 3})))
    (is (nil? (flat-body {:keys [:a]} {:c 3})))
    (is (= {} (flat-body {:keys [:a] :required? true} {})))))

(deftest urls-join-without-doubled-slashes
  (let [request-url #'codec/request-url]
    (is (= "http://h/p" (request-url "http://h//" "/p" [])))
    (is (= "http://h/p?a=1&b=2" (request-url "http://h" "/p" ["a=1" "b=2"])))))

(deftest refusal-types-follow-the-status
  (let [refusal-type #'codec/refusal-type problem-data #'codec/problem-data]
    (is (= :openapi/conflict (refusal-type 409)))
    (is (= :openapi/server-error (refusal-type 503)))
    (is (= :openapi/http-error (refusal-type 418)))
    (is (= {:status 400} (problem-data 400 nil)))
    (is (= {:status 400 :problem {:errors []}} (problem-data 400 {:errors []})) "empty errors are not repeated")
    (is (= [:e] (:errors (problem-data 400 {:errors [:e]}))))))

;; -----------------------------------------------------------------------------
;; derive and the surface fold

(deftest unknown-operations-list-what-the-spec-knows
  (let [unknown #'derive/unknown-operations]
    (is (= {:error {:type :openapi/unknown-operation :message "the spec has no operationId x, y"
                    :data {:unknown ["x" "y"] :known ["a" "b"]}}}
           (unknown [:x "y"] {"b" {} "a" {}})))))

(deftest operations-are-indexed-by-operation-id
  (let [by-id #'derive/operations-by-id
        b     (doc/bundle {"paths" {"/a" {"get" {"operationId" "g"} "post" {"operationId" "p"}}}})]
    (is (= #{"g" "p"} (set (keys (by-id b)))))
    (is (= "post" (:method ((by-id b) "p"))))))

(deftest tool-ids-take-the-prefix-as-namespace
  (let [tool-id #'derive/tool-id]
    (is (= :p/x (tool-id :p "x")))
    (is (= :x (tool-id nil "x")))))

(deftest adding-a-tool-refuses-a-taken-name
  (let [add-tool #'openapi/add-tool]
    (is (= {:ok {:tools {"a" {:name "a"}}}} (add-tool {:tools {}} {:name "a"})))
    (testing "the first registration wins"
      (is (= :name-clash (get-in (add-tool {:tools {"a" {:name "a" :v 1}}} {:name "a"}) [:error :type]))))))
