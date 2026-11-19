(ns autonomous.openapi-invoke-test
  "encode -> identity port -> stub HTTP fn -> decode, through the runtime.
   No real endpoint is ever called."
  (:require [autonomous.core :as a]
            [autonomous.domain.answer :as answer]
            [autonomous.openapi :as openapi]
            [autonomous.openapi-test :as fx]
            [autonomous.openapi.invoke :as oinv]
            [autonomous.openapi.spi :as ospi]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def json-resp #'fx/json-resp)

(defn- stub
  "HTTP fn answering `resp` and recording every request into `seen`."
  [seen resp]
  (fn [req] (swap! seen conj req) resp))

(defn- install-with! [http]
  (a/reset-registry!)
  (openapi/install! fx/bundle {:prefix "payment" :base-url fx/base :expose fx/expose :http http
                               :principal (oinv/bearer (fn [grant] (str "tok-" (:actor grant))))}))

(deftest a-call-runs-through-the-identity-port-and-the-injected-http
  (let [seen (atom [])]
    (is (answer/ok? (install-with! (stub seen (json-resp 200 fx/payment)))))
    (is (= {:ok fx/payment}
           (a/rpc {:op :call :tool "payment_getPayment" :args {:id fx/uuid}
                   :actor "clerk-1" :scopes [:payment/read]})))
    (is (= 1 (count @seen)))
    (is (= "Bearer tok-clerk-1" (get-in (first @seen) [:headers "authorization"])))
    (is (= (str fx/base "/payments/" fx/uuid) (:url (first @seen))))))

(deftest a-refusal-from-the-service-reaches-the-agent-as-a-refusal
  (install-with! (stub (atom []) fx/problem-409))
  (let [x (a/rpc {:op :call :tool "payment_cancelPayment" :args {:id fx/uuid :reason "duplicated at the till"}
                  :actor "clerk-1" :scopes [:payment/write]})]
    (is (= :openapi/conflict (get-in x [:error :type])))
    (is (str/includes? (get-in x [:error :message]) "already cancelled"))))

(deftest scopes-gate-the-call-before-any-request-is-sent
  (let [seen (atom [])]
    (install-with! (stub seen (json-resp 200 fx/payment)))
    (is (= :forbidden (get-in (a/rpc {:op :call :tool "payment_markBoletoPaid"
                                      :args {:id fx/uuid :paymentDate "2026-11-10" :paidAmount "1.00"}
                                      :actor "clerk-1" :scopes [:payment/read :payment/write]})
                              [:error :type])))
    (is (empty? @seen) "a money-moving op without its scope never reaches HTTP")))

(deftest invalid-args-are-refused-before-any-request-is-sent
  (let [seen (atom [])]
    (install-with! (stub seen (json-resp 200 fx/payment)))
    (is (= :invalid-args (get-in (a/rpc {:op :call :tool "payment_cancelPayment" :args {:id fx/uuid :reason "short"}
                                         :actor "c" :scopes [:payment/write]})
                                 [:error :type])))
    (is (empty? @seen))))

(deftest persona-headers-and-a-missing-base-url
  (let [p (oinv/persona-headers (fn [g] {"x-persona" (:actor g)}))]
    (is (= {"x-persona" "cobrador-2"} (:headers (p {:actor "cobrador-2"} {:headers {}})))))
  (is (= :openapi/no-base-url
         (get-in (oinv/call (assoc (fx/specs :payment/getPayment) :openapi/client {}) {:id fx/uuid} {:actor "x"})
                 [:error :type]))))

(deftest a-throwing-http-fn-is-a-refusal
  (let [t (assoc-in (fx/specs :payment/getPayment) [:openapi/client :http] (fn [_] (throw (ex-info "boom" {}))))]
    (is (= :openapi/unreachable (get-in (oinv/call t {:id fx/uuid} {:actor "x"}) [:error :type])))))

(deftest the-spi-method-is-plain-and-registers-only-when-the-spi-exists
  (is (boolean? ospi/registered?))
  (is (answer/ok? (ospi/derive-surface ospi/source-kind fx/bundle {:expose {"getPayment" {}} :base-url fx/base}))))
