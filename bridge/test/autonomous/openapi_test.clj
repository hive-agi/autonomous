(ns autonomous.openapi-test
  "The :openapi Source against funeraria's sisf-payment spec (a read-only
   copy under test/fixtures/openapi). No real endpoint is ever called: the
   HTTP fn is a stub that records requests and answers canned responses."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.openapi :as openapi]
            [autonomous.openapi.doc :as doc]
            [autonomous.domain.affordance :as affordance]
            [autonomous.openapi.load :as load]
            [autonomous.mcp.json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def bundle (:ok (load/load-bundle "test/fixtures/openapi/sisf-payment.yaml")))

(def base "http://stub.invalid/api/payment")

(def expose
  {"listPayments"   {:scope :payment/read}
   "getPayment"     {:scope :payment/read}
   "cancelPayment"  {:scope :payment/write}
   "markBoletoPaid" {:scope :payment/money}})

(def specs (into {} (:ok (openapi/derive-tools bundle {:prefix "payment" :base-url base :expose expose}))))

(defn- op-of [op-id] (:openapi/operation (specs (keyword "payment" op-id))))

(def list-op (op-of "listPayments"))
(def get-op (op-of "getPayment"))
(def cancel-op (op-of "cancelPayment"))
(def mark-op (op-of "markBoletoPaid"))

(def uuid "550e8400-e29b-41d4-a716-446655440000")

(def payment
  {:id uuid :paymentDate "2026-11-10" :amount "120.00" :netAmount "120.00"
   :paymentType "monthly_fee" :status "paid"})

(defn- json-resp [status body & [ct]]
  {:status status :headers {"content-type" (or ct "application/json")}
   :body (if (string? body) body (autonomous.mcp.json/write body))})

(def problem-409
  (json-resp 409 {:type "https://x/problems/conflict" :title "Conflict" :status 409
                  :detail "payment already cancelled"} "application/problem+json"))

;; -----------------------------------------------------------------------------
;; $ref resolution

(deftest refs-resolve-within-the-doc-and-into-sibling-files
  (is (= #{"sisf-payment.yaml" "common.yaml"} (set (keys (:docs bundle)))))
  (is (empty? (doc/missing-docs bundle)))
  (let [p (doc/resolved bundle {"$ref" "#/components/schemas/Payment"})]
    (is (= "object" (get p "type")))
    (is (= "string" (get-in p ["properties" "id" "type"])) "UUID came from common.yaml")
    (is (= ["pending" "paid" "cancelled" "overdue"] (get-in p ["properties" "status" "enum"])))))

(deftest refs-that-cycle-or-dangle-become-markers-not-exceptions
  (let [b {:root "a.yaml"
           :docs {"a.yaml" {"components" {"schemas" {"Node" {"type" "object"
                                                             "properties" {"next" {"$ref" "#/components/schemas/Node"}}}}}}}}
        n (doc/resolved b {"$ref" "#/components/schemas/Node"})]
    (is (= {"x-autonomous-cycle" "#/components/schemas/Node"} (get-in n ["properties" "next"])))
    (is (= {"x-autonomous-unresolved" "./gone.yaml#/x"} (doc/resolved b {"$ref" "./gone.yaml#/x"})))
    (is (= #{"gone.yaml"} (set (doc/missing-docs (assoc-in b [:docs "a.yaml" "x"] {"$ref" "./gone.yaml#/x"})))))))

(deftest sibling-keys-of-a-ref-override-the-target
  (let [b (doc/bundle {"d" {"type" "string" "description" "old"}})]
    (is (= {"type" "string" "description" "new"}
           (doc/resolved b {"$ref" "#/d" "description" "new"})))))

(deftest document-names-join-relative-to-the-referring-document
  (is (= "common.yaml" (doc/join-path "sisf-payment.yaml" "./common.yaml")))
  (is (= "api/common.yaml" (doc/join-path "api/payment.yaml" "./common.yaml")))
  (is (= "common.yaml" (doc/join-path "api/payment.yaml" "../common.yaml"))))

;; -----------------------------------------------------------------------------
;; opt-in derivation

(deftest only-the-listed-operation-ids-become-tools
  (is (= #{:payment/listPayments :payment/getPayment :payment/cancelPayment :payment/markBoletoPaid}
         (set (keys specs))))
  (let [s (:ok (openapi/surface bundle {:prefix "payment" :base-url base :expose expose}))]
    (is (= #{"payment_listPayments" "payment_getPayment" "payment_cancelPayment" "payment_markBoletoPaid"}
           (set (keys (:tools s)))))
    (is (not-any? #(str/includes? % "createBoleto") (keys (:tools s))))))

(deftest scopes-and-read-only-follow-the-expose-map-and-the-method
  (is (= #{:payment/read} (:scopes (specs :payment/listPayments))))
  (is (true? (:read-only? (specs :payment/listPayments))))
  (is (false? (:read-only? (specs :payment/cancelPayment))))
  (is (= #{:payment/money} (:scopes (specs :payment/markBoletoPaid)))))

(deftest a-typo-or-an-empty-list-is-refused-not-ignored
  (is (= :openapi/unknown-operation
         (get-in (openapi/derive-tools bundle {:expose {"listPayment" {}}}) [:error :type])))
  (is (= :openapi/nothing-exposed (get-in (openapi/derive-tools bundle {:expose {}}) [:error :type])))
  (is (= :openapi/unsupported-media
         (get-in (openapi/derive-tools bundle {:expose {"processReturnFile" {}}}) [:error :type]))
      "multipart bodies are refused at derivation, not at call time"))

(deftest the-input-schema-joins-params-and-the-json-body
  (let [t (get-in (openapi/surface bundle {:base-url base :expose {"cancelPayment" {}}}) [:ok :tools "cancelPayment"])]
    (is (= #{:id :reason} (set (keys (get-in t [:json-schema :properties])))))
    (is (= #{:id :reason} (set (get-in t [:json-schema :required]))))
    (is (answer/refused? (affordance/conform-args t {:id uuid :reason "short"})))
    (is (answer/ok? (affordance/conform-args t {:id uuid :reason "duplicated at the till"})))))
