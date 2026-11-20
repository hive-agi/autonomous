(ns autonomous.openapi-surface-test
  (:require [autonomous.core :as core]
            [autonomous.openapi :as openapi]
            [autonomous.runtime.registry :as registry]
            [clojure.test :refer [deftest is]]))

(def ^:private source
  {"paths" {"/a" {"get" {"operationId" "getA"}}
            "/b" {"get" {"operationId" "getB"}}}})

(deftest surface-seed-retains-the-app-and-resource-shape
  (let [surface (fn [opts] (:ok (openapi/surface source (assoc opts :expose {"getA" {}}))))]
    (is (= {:app {:name "openapi"} :resources {}} (dissoc (surface {}) :tools)))
    (is (= {:name "sample"} (:app (surface {:prefix :sample}))))
    (is (= {:name "chosen" :description "My app"}
           (:app (surface {:prefix :sample :app {:name "chosen" :description "My app"}}))))))

(deftest first-compile-or-name-refusal-is-the-surface-answer
  (let [opts {:expose (array-map "getA" {} :getA {} "getB" {:description 7})}]
    (is (= {:error {:type :name-clash :message "tool name getA is derived twice"}}
           (openapi/surface source opts)))
    (is (= :invalid-tool
           (get-in (openapi/surface source {:expose {"getB" {:description 7}}}) [:error :type])))))

(defn- registered-tools []
  (set (keys (:tools (registry/snapshot)))))

(deftest installation-has-no-effect-when-derivation-refuses
  (core/reset-registry!)
  (is (= :openapi/unknown-operation
         (get-in (openapi/install! source {:expose (array-map "getA" {} "missing" {})}) [:error :type])))
  (is (empty? (registered-tools)) "a refused derivation registers nothing, not even the valid getA")
  (is (= {:ok ["getA"]} (openapi/install! source {:expose {"getA" {}}})))
  (is (= #{"getA"} (registered-tools)))
  (core/reset-registry!))
