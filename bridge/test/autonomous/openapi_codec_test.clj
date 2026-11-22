(ns autonomous.openapi-codec-test
  "Codec trifectas (encode, decode) and the invoke path through the
   runtime with a stub HTTP fn. Fixtures from autonomous.openapi-test."
  (:require [autonomous.mcp.json :as json]
            [autonomous.openapi-test :as fx]
            [autonomous.openapi.codec :as codec]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]))

(def base fx/base)
(def uuid fx/uuid)
(def payment fx/payment)
(def op-of #'fx/op-of)
(def json-resp #'fx/json-resp)
(def problem-409 fx/problem-409)

;; -----------------------------------------------------------------------------
;; encode

(defn encode-case [[op-id args]] (codec/encode (op-of op-id) base args))

(deftrifecta encode-trifecta
  autonomous.openapi-codec-test/encode-case
  {:golden-path "test/golden/openapi/encode.edn"
   :cases {:list-empty   ["listPayments" {}]
           :list-filters ["listPayments" {:status "paid" :page 2 :referenceMonth 11 :limit nil}]
           :get          ["getPayment" {:id uuid}]
           :get-escapes  ["getPayment" {:id "a b/c"}]
           :cancel       ["cancelPayment" {:id uuid :reason "duplicated at the till"}]
           :mark-paid    ["markBoletoPaid" {:id uuid :paymentDate "2026-11-10" :paidAmount "120.00"}]}
   :gen (gen/tuple (gen/elements ["listPayments" "getPayment" "cancelPayment" "markBoletoPaid"])
                   (gen/map (gen/elements [:id :status :page :reason :paidAmount :junk])
                            (gen/one-of [gen/string-alphanumeric gen/small-integer gen/boolean])))
   :pred (fn [{:keys [method url headers]}]
           (and (keyword? method) (str/starts-with? url base) (not (str/includes? url "{"))
                (map? headers) (not (str/includes? url "junk"))))
   :mutations [["drops the query" (fn [[op args]] (update (codec/encode (op-of op) base args) :url #(first (str/split % #"\?"))))]
               ["sends no body" (fn [[op args]] (assoc (codec/encode (op-of op) base args) :body nil))]
               ["unescaped path" (fn [[op args]] (let [r (codec/encode (op-of op) base args)]
                                                   (cond-> r (:id args) (assoc :url (str base "/payments/" (:id args))))))]]})

;; -----------------------------------------------------------------------------
;; decode

(defn decode-case [[op-id resp]] (codec/decode (op-of op-id) resp))

(def ^:private decode* codec/decode)

(deftrifecta decode-trifecta
  autonomous.openapi-codec-test/decode-case
  {:golden-path "test/golden/openapi/decode.edn"
   :cases {:ok-declared      ["getPayment" (json-resp 200 payment)]
           :ok-list          ["listPayments" (json-resp 200 {:data [payment] :pagination {:page 1 :limit 20 :total 1 :totalPages 1}})]
           :ok-wrong-shape   ["getPayment" (json-resp 200 {:id 7})]
           :ok-undeclared    ["getPayment" (json-resp 201 payment)]
           :ok-bad-json      ["getPayment" (json-resp 200 "{nope")]
           :problem-409      ["cancelPayment" problem-409]
           :problem-400      ["cancelPayment" (json-resp 400 {:type "t" :title "Validation Error" :status 400
                                                              :detail "reason too short"
                                                              :errors [{:field "reason" :message "min 10"}]}
                                                         "application/problem+json")]
           :not-found-plain  ["getPayment" {:status 404 :headers {"content-type" "text/plain"} :body "no such payment"}]
           :unauthorized     ["getPayment" {:status 401 :headers {} :body ""}]
           :server-error     ["getPayment" (json-resp 503 {:message "upstream down"})]
           :transport-failed ["getPayment" {:error (ex-info "connection refused" {})}]
           :no-status        ["getPayment" {}]}
   :gen (gen/tuple (gen/elements ["listPayments" "getPayment" "cancelPayment"])
                   (gen/hash-map :status (gen/one-of [(gen/choose 100 599) (gen/return nil) gen/string-alphanumeric])
                                 :headers (gen/elements [{} {"content-type" "application/json"} {"content-type" "application/problem+json"}])
                                 :body (gen/one-of [gen/string (gen/return nil) (gen/return "{\"detail\":\"x\"}")])))
   :pred (fn [a] (= 1 (count (select-keys a [:ok :error]))))
   :num-tests 300
   :mutations [["every 2xx is ok" (fn [[op r]] (if (<= 200 (or (:status r) 0) 299) {:ok (:body r)} (decode* (op-of op) r)))]
               ["loses the message" (fn [[op r]] (let [x (decode* (op-of op) r)] (cond-> x (:error x) (assoc-in [:error :message] "error"))))]
               ["no schema check" (fn [[op r]] (if (= 200 (:status r))
                                                 {:ok (try (json/read-json (:body r)) (catch Exception _ nil))}
                                                 (decode* (op-of op) r)))]]})

(deftest refusals-carry-the-servers-message
  (let [x (codec/decode (op-of "cancelPayment") problem-409)]
    (is (= :openapi/conflict (get-in x [:error :type])))
    (is (str/includes? (get-in x [:error :message]) "payment already cancelled"))
    (is (= 409 (get-in x [:error :data :status])))))
