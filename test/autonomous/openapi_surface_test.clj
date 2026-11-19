(ns autonomous.openapi-surface-test
  (:require [autonomous.core :as core]
            [autonomous.openapi :as openapi]
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

(deftest installation-has-no-effect-when-derivation-refuses
  (with-redefs [core/reg-tool (fn [& _] (throw (ex-info "should not register" {})))]
    (is (= :openapi/unknown-operation
           (get-in (openapi/install! source {:expose (array-map "getA" {} "missing" {})}) [:error :type])))))
