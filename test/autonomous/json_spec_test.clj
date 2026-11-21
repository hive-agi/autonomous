(ns autonomous.json-spec-test
  "The JSON tool-spec form: JSON Schema and malli-as-EDN inputs, $refs
   shared across a document, refusals that name the problem, and a
   registered tool that validates like an EDN one."
  (:require [autonomous.core :as a]
            [autonomous.domain.affordance :as affordance]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.wire :as wire]
            [autonomous.json-spec :as js]
            [autonomous.json-spec.load :as load]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each (fn [t] (a/reset-registry!) (t) (a/reset-registry!)))

(def fixture "test/fixtures/json_spec/inventory.json")

(def specs (into {} (:ok (load/read-file fixture))))

(deftest a-document-yields-its-tools-in-order
  (is (= [:inventory/receive :inventory/stock] (mapv first (:ok (load/read-file fixture))))))

(deftest json-schema-inputs-become-malli-with-refs-resolved
  (let [receive (specs :inventory/receive)]
    (is (= [:map
            [:note {:optional true} [:string]]
            [:qty [:int {:min 1}]]
            [:sku [:string {:min 1 :description "Stock keeping unit"}]]]
           (:input receive)))
    (is (= #{:write} (:scopes receive)))
    (is (= [:map [:on_hand [:int]] [:sku [:string {:min 1 :description "Stock keeping unit"}]]]
           (:output receive))
        "output is converted too, sharing the document's $defs")))

(deftest malli-edn-inputs-and-the-other-fields-carry-over
  (let [stock (specs :inventory/stock)]
    (is (= [:map] (:input stock)))
    (is (true? (:read-only? stock)))
    (is (= {:idempotentHint true} (:annotations stock)))
    (is (not (contains? stock :scopes)) "absent scopes keep the runtime's default")))

(deftest a-registered-json-tool-validates-like-an-edn-one
  (is (= {:ok ["inventory_receive" "inventory_stock"]}
         (load/register! (vec specs) {:inventory/receive (fn [{:keys [sku qty]} _] {:sku sku :on_hand qty})
                                      :inventory/stock   (fn [_ _] {})})))
  (testing "lenient decoding, then validation"
    (is (= {:ok {:sku "a" :on_hand 3}}
           (a/rpc {:op :call :tool "inventory_receive" :args {:sku "a" :qty "3"} :scopes #{:write}})))
    (is (= :invalid-args
           (get-in (a/rpc {:op :call :tool "inventory_receive" :args {:sku "" :qty 0} :scopes #{:write}})
                   [:error :type]))))
  (testing "a read-only tool needs only :read"
    (is (answer/ok? (a/rpc {:op :call :tool "inventory_stock" :args {} :scopes #{:read}})))))

(deftest the-edn-text-is-what-the-c-abi-reads
  (doseq [[id spec] specs]
    (let [back (wire/read-edn (load/edn-text spec))]
      (is (= spec back))
      (is (answer/ok? (affordance/->tool id (assoc back :handler (fn [_ _] nil))))))))

(deftest refusals-name-the-problem
  (is (= "tool (no id) JSON spec needs an \"id\""
         (get-in (js/->tool-spec {"description" "x"}) [:error :message])))
  (is (= {"description" ["should be at least 1 character"]}
         (get-in (js/->tool-spec {"id" "a" "description" ""}) [:error :data])))
  (is (= "should be a JSON Schema object or a string of malli EDN"
         (first (get-in (js/->tool-spec {"id" "a" "description" "d" "input" 3}) [:error :data "input"])))
      "a schema is a JSON Schema object or malli EDN text")
  (is (= "tool a input is not readable EDN"
         (get-in (js/->tool-spec {"id" "a" "description" "d" "input" "[:map"}) [:error :message])))
  (is (= "tool a is declared twice"
         (get-in (js/document->specs [{"id" "a" "description" "d"} {"id" "a" "description" "e"}])
                 [:error :message])))
  (is (= :invalid-tool (get-in (js/document->specs {"x" 1}) [:error :type])))
  (is (= :invalid-tool (get-in (js/->tool-spec ["not" "an" "object"]) [:error :type]))))

(deftest an-explicit-id-wins-over-the-spec's-own
  (is (= :b/c (first (:ok (js/->tool-spec "b/c" {"id" "a" "description" "d"}))))))

(deftest a-non-object-json-schema-input-is-refused-at-registration
  (let [[id spec] (:ok (js/->tool-spec {"id" "a" "description" "d" "input" {"type" "string"}}))]
    (is (= :invalid-tool (get-in (affordance/->tool id (assoc spec :handler (fn [_ _] nil))) [:error :type])))))

(deftest the-loader-refuses-instead-of-throwing
  (is (= :invalid-tool (get-in (load/parse "{nope") [:error :type])))
  (is (= "no JSON spec file /no/such.json" (get-in (load/read-file "/no/such.json") [:error :message])))
  (is (= {:missing [:inventory/stock]}
         (get-in (load/register! (vec specs) {:inventory/receive (fn [_ _] nil)}) [:error :data])))
  (is (empty? (:tools (a/rpc {:op :manifest :scopes #{:admin}}))) "nothing registered on a refusal"))
