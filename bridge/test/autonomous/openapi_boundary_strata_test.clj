(ns autonomous.openapi-boundary-strata-test
  "Focused tests for the OpenAPI boundary strata: the loader's read steps
   and the invoke pipeline's client guard, request signing and refusal
   translation."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.openapi.invoke :as oinv]
            [autonomous.openapi.load :as load]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(defn- temp-dir [files]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "oa-strata-" (System/nanoTime)))]
    (.mkdirs d)
    (doseq [[n text] files] (spit (io/file d n) text))
    d))

(deftest a-read-step-skips-known-and-remote-documents
  (let [step #'load/step d (temp-dir {})]
    (is (= {:ok [{:docs {"a" {}}} ["b"]]} (step d [{:docs {"a" {}}} ["a" "b"]])))
    (let [{[bundle pending] :ok} (step d [{:docs {}} ["https://x/y.yaml"]])]
      (is (= {:docs {}} bundle))
      (is (empty? pending)))))

(deftest a-read-step-queues-what-the-document-refers-to
  (let [step #'load/step d (temp-dir {"a.json" "{\"x\": {\"$ref\": \"b.json#/y\"}}"})
        {[bundle pending] :ok} (step d [{:docs {}} ["a.json" "q"]])]
    (is (= {"x" {"$ref" "b.json#/y"}} (get-in bundle [:docs "a.json"])))
    (is (= ["q" "b.json"] (vec pending)))))

(deftest loading-stops-at-the-first-missing-document
  (let [d (temp-dir {"a.json" "{\"x\": {\"$ref\": \"gone.json\"}}"})]
    (is (= :openapi/missing-file (get-in (load/load-bundle (io/file d "a.json")) [:error :type])))
    (is (= :openapi/unreadable
           (get-in (load/load-bundle (io/file (temp-dir {"bad.json" "{"}) "bad.json")) [:error :type])))
    (testing "a complete closure loads"
      (spit (io/file d "gone.json") "{}")
      (is (= #{"a.json" "gone.json"} (set (keys (:docs (:ok (load/load-bundle (io/file d "a.json")))))))))))

(defn- cross-referenced-docs
  "`n` documents d0.json .. d<n-1>.json, each referring to every one of them."
  [n]
  (let [names (map #(str "d" % ".json") (range n))
        body  (str "{" (apply str (interpose "," (map #(str "\"" % "\": {\"$ref\": \"" % "#/x\"}") names))) "}")]
    (zipmap names (repeat body))))

(deftest loading-many-cross-references-runs-in-constant-stack
  (let [n 120
        d (temp-dir (cross-referenced-docs n))]
    (testing "n*n pending entries, nearly all duplicates, answer Ok without a StackOverflowError"
      (is (= n (count (:docs (:ok (load/load-bundle (io/file d "d0.json"))))))))))

(def ^:private op {:operation-id "getA" :method "get" :path "/a" :params [] :responses {"200" {}}})

(deftest the-client-guard-needs-a-base-url
  (let [client-of #'oinv/client-of]
    (is (= {:ok {:base-url "http://h"}} (client-of {:openapi/client {:base-url "http://h"}})))
    (is (= "getA: no :base-url; say where requests go"
           (get-in (client-of {:openapi/operation op :openapi/client {}}) [:error :message])))))

(deftest requests-are-signed-only-by-a-principal
  (let [request #'oinv/request]
    (is (nil? (get-in (request op {:base-url "http://h"} {} {}) [:headers "authorization"])))
    (is (= "Bearer t-a"
           (get-in (request op {:base-url "http://h" :principal (oinv/bearer #(str "t-" (:actor %)))} {} {:actor "a"})
                   [:headers "authorization"])))))

(deftest a-throwing-principal-is-unreachable-too
  (let [tool {:openapi/operation op
              :openapi/client {:base-url "http://h" :principal (fn [_ _] (throw (ex-info "no key" {})))
                               :http (fn [_] {:status 200 :body nil})}}]
    (is (= {:error {:type :openapi/unreachable :message "getA: no key"}} (oinv/call tool {} {})))))

(deftest refusals-round-trip-through-ex-info
  (let [refusal->ex #'oinv/refusal->ex
        a           (answer/refusal :openapi/conflict "taken" {:status 409})]
    (is (= a (answer/from-exception (refusal->ex a))))
    (is (= {:autonomous/error :x} (ex-data (refusal->ex {:error {:type :x :message "m" :data "not a map"}}))))))
