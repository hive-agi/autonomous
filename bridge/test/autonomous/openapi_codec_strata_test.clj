(ns autonomous.openapi-codec-strata-test
  (:require [autonomous.openapi.codec :as codec]
            [clojure.test :refer [deftest is]]))

(deftest parameter-locations-keep-wire-names-order-and-absence-rules
  (let [operation {:method "get" :path "/{a}/{b}"
                   :params [{:in "path" :key :a :name "a"}
                            {:in "path" :key :b :name "b"}
                            {:in "query" :key :keys :name "Filter Name"}
                            {:in "query" :key :skip :name "skip"}
                            {:in "header" :key :flag :name "X-FLAG"}
                            {:in "header" :key :accept :name "Accept"}
                            {:in "cookie" :key :sid :name "sid"}]}
        request (codec/encode operation "https://example.invalid/"
                              {:a "a/b" :keys [:ready false 2] :flag false :accept "example" :sid "s"})]
    (is (= {:method :get :url "https://example.invalid/a%2Fb/?Filter%20Name=ready&Filter%20Name=false&Filter%20Name=2"
            :headers {"accept" "example" "x-flag" "false"} :body nil}
           request))))

(deftest declaration-order-and-body-kind-control-success
  (let [decode (fn [declarations response]
                 (codec/decode {:operation-id "get" :responses declarations} response))
        raw {:status 206 :headers {:Content-Type ["text/plain" "application/json"]} :body "abcd"}
        json {:status 206 :headers {"Content-Type" "APPLICATION/JSON; charset=utf-8"} :body "false"}]
    (is (= {:ok {:status 206 :media "text/plain" :size 4}}
           (decode {"2xx" {:schema [:int]}} raw)))
    (is (= {:ok false} (decode {"206" {:schema [:boolean]} "2XX" {:schema [:int]}} json)))
    (is (= :openapi/response-mismatch
           (get-in (decode {"2XX" {:schema [:int]} "2xx" {:schema [:boolean]}} json) [:error :type])))
    (is (= {:ok nil} (decode {"204" {}} {:status 204 :body "   "})))
    (is (= :openapi/undeclared-response
           (get-in (decode {"default" {}} (assoc json :body "{")) [:error :type])))
    (is (= :openapi/bad-response
           (get-in (decode {"2XX" {}} (assoc json :body "{")) [:error :type])))))

(deftest refusal-reasons-preserve-server-text-and-transport-precedence
  (let [decode (partial codec/decode {:operation-id "get" :responses {"200" {}}})
        failure (fn [value] (:error (decode {:status 418 :body value})))
        long-text (apply str (repeat 305 "x"))]
    (is (= "get:   " (:message (failure {:detail "  " :title "other"}))))
    (is (= "get: title" (:message (failure {:detail "" :title "title" :message "other"}))))
    (is (= (str "get: " (subs long-text 0 300)) (:message (failure long-text))))
    (is (= :openapi/http-error (:type (failure nil))))
    (is (= "get: HTTP 503" (get-in (decode {:status 503 :headers {"Content-Type" "application/problem+json"} :body "{"})
                                    [:error :message])))
    (is (= {:type :openapi/unreachable :message "get: no HTTP response (offline)"}
           (:error (decode {:status 200 :body 10 :error (ex-info "offline" {})}))))
    (is (= "get: no HTTP response (no status)" (get-in (decode []) [:error :message])))))
