(ns sim.control-test
  "The sim control-plane tools against a stub simulator on 127.0.0.1 (real
   sockets, http-kit, port 0). The real simulator is never called."
  (:require [autonomous.approval :as approval]
            [autonomous.core :as a]
            [autonomous.runtime.registry :as registry]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [org.httpkit.server :as hk]
            [sim.control :as control]
            [sim.persona :as persona]))

(def ^:private seen (atom []))

(defn- stub-handler
  "A stand-in for the simulator: records every request, answers like it."
  [req]
  (let [body (some-> (:body req) slurp)
        uri  (:uri req)]
    (swap! seen conj {:method (:request-method req) :uri uri :body body
                      :content-type (get-in req [:headers "content-type"])})
    (cond
      (= uri "/__sim/state")
      {:status 200 :headers {"Content-Type" "application/json"}
       :body (json/write-str {:charges [{:txid "tx1" :status "ATIVA"}] :boletos nil :webhook_url "http://x"})}

      (= uri "/__sim/pix/missing/pay")
      {:status 409 :headers {"Content-Type" "application/json"} :body "{\"error\":\"cobranca nao encontrada\"}"}

      (str/starts-with? uri "/__sim/pix/")
      {:status 200 :headers {"Content-Type" "application/json"}
       :body (json/write-str {:txid (second (re-find #"/__sim/pix/(.*)/pay" uri)) :status "CONCLUIDA" :delivered true})}

      (str/starts-with? uri "/__sim/boleto/")
      {:status 200 :headers {"Content-Type" "application/json"}
       :body (json/write-str {:nossoNumero 42 :situacao "Liquidado" :delivered true})}

      :else {:status 404 :body "no"})))

(def ^:dynamic *base* nil)

(defn- with-stub [t]
  (reset! seen [])
  (let [srv (hk/run-server stub-handler {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    (try
      (binding [*base* (str "http://127.0.0.1:" (hk/server-port srv))]
        (registry/with-instance (registry/make-runtime)
          #(do (a/set-app-info! {:name "sim" :description "payment simulator control plane"})
               (control/install! {:base-url *base* :timeout-ms 5000})
               (t))))
      (finally @(hk/server-stop! srv {:timeout 1000})))))

(use-fixtures :each with-stub)

(def ^:private payer {:actor "persona-1" :scopes [:read :write :sim/pay]})

(defn- call
  ([tool args] (call payer tool args))
  ([grant tool args] (a/rpc (merge grant {:op :call :tool tool :args args}))))

(defn- tool-names [] (set (map :name (:tools (:ok (a/rpc {:op :manifest :actor "x" :scopes [:admin]}))))))

(deftest the-surface-is-exactly-the-three-tools
  (is (= #{"sim_pix_pay" "sim_boleto_pay" "sim_state"} (tool-names)))
  (testing "no tool reaches reset, balance or waf"
    (is (not-any? #(re-find #"(?i)reset|balance|waf" %) (tool-names)))
    (is (not-any? #(some control/forbidden-segments (:path %)) (vals control/operations)))
    (is (not-any? #(re-find #"(?i)reset" (:description %)) (vals control/operations)))))

(deftest pix-pay-posts-to-the-txid-with-the-optional-body
  (is (= {:ok {:txid "tx-1_A" :status "CONCLUIDA" :delivered true}}
         (call "sim_pix_pay" {:txid "tx-1_A" :valor "1.27" :payerName "ANA" :payerCpf "12345678909"})))
  (let [r (first @seen)]
    (is (= :post (:method r)))
    (is (= "/__sim/pix/tx-1_A/pay" (:uri r)))
    (is (= {:valor "1.27" :payerName "ANA" :payerCpf "12345678909"} (json/read-str (:body r) :key-fn keyword)))
    (is (= "application/json" (:content-type r)))))

(deftest pix-pay-without-a-body-pays-in-full
  (is (:ok (call "sim_pix_pay" {:txid "tx1"})))
  (is (str/blank? (:body (first @seen)))))

(deftest boleto-pay-and-state
  (is (= {:ok {:nossoNumero 42 :situacao "Liquidado" :delivered true}}
         (call "sim_boleto_pay" {:nossoNumero "42" :valorPago 10.5})))
  (is (= "/__sim/boleto/42/pay" (:uri (first @seen))))
  (is (= {:valorPago 10.5} (json/read-str (:body (first @seen)) :key-fn keyword)))
  (is (= "tx1" (get-in (call {:actor "r" :scopes [:read]} "sim_state" {}) [:ok :charges 0 :txid])))
  (is (= :get (:method (last @seen)))))

(deftest identifiers-must-be-path-safe
  (doseq [bad ["a/b" ".." "../reset" "%2e%2e" "a%2Fb" "tx 1" "" "tx?x=1" "tx#f" "tx\u00e9"
               (apply str (repeat 65 "a"))]]
    (is (= :invalid-args (get-in (call "sim_pix_pay" {:txid bad}) [:error :type])) (pr-str bad)))
  (doseq [bad ["4/2" "../1" "1a" "" "%31"]]
    (is (= :invalid-args (get-in (call "sim_boleto_pay" {:nossoNumero bad}) [:error :type])) (pr-str bad)))
  (testing "a trailing newline is refused everywhere ($ would match before it)"
    (is (= :invalid-args (get-in (call "sim_pix_pay" {:txid "tx1\n"}) [:error :type])))
    (is (= :invalid-args (get-in (call "sim_boleto_pay" {:nossoNumero "42\n"}) [:error :type])))
    (is (= :invalid-args (get-in (call "sim_pix_pay" {:txid "tx1" :valor "1.27\n"}) [:error :type])))
    (is (= :invalid-args (get-in (call "sim_pix_pay" {:txid "tx1" :payerCpf "12345678909\n"}) [:error :type]))))
  (is (empty? @seen) "nothing reached the simulator"))

(deftest a-path-segment-is-checked-and-encoded-even-past-the-schema
  (is (= "/__sim/pix/tx-1/pay" (control/request-path (:sim.pix/pay control/operations) {:txid "tx-1"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (control/request-path (:sim.pix/pay control/operations) {:txid "../reset"}))))

(deftest the-simulator-refusing-is-a-refusal
  (let [r (call "sim_pix_pay" {:txid "missing"})]
    (is (= :sim/refused (get-in r [:error :type])))
    (is (= 409 (get-in r [:error :data :status])))
    (is (str/includes? (get-in r [:error :message]) "cobranca nao encontrada"))))

(deftest pay-needs-its-scope
  (is (= :forbidden (get-in (call {:actor "p" :scopes [:read :write]} "sim_pix_pay" {:txid "tx1"}) [:error :type])))
  (is (empty? @seen)))

(deftest a-down-port-forward-is-unreachable
  (a/reset-registry!)
  (control/install! {:base-url "http://127.0.0.1:1" :timeout-ms 500})
  (is (= :sim/unreachable (get-in (call "sim_state" {}) [:error :type]))))

(deftest only-a-loopback-base-url-is-accepted
  (doseq [bad ["https://staging.example.com" "http://10.0.0.5:8099" "http://staging-sicoob-sim:8099"
               "http://user:pw@127.0.0.1:1" "http://127.0.0.1:1/?x=1" "http://127.0.0.1:1/__sim"
               "ftp://127.0.0.1:1" nil "" "127.0.0.1:18099"
               "http://[::1].evil.com" "http://[::1]evil.com:80" "http://127.0.0.1:8099.evil.com"
               "http://127.0.0.1:x" "http://127.0.0.1.evil.com:80" "http://localhost.:1"
               "http://127.0.0.1:1#f" "http://@127.0.0.1:1" "http://127.0.0.1:1:2"
               "http://127.0.0.1\\@evil.com" "http://127.0.0.1%2f@evil.com"]]
    (is (= :invalid-sim-config (try (control/tool-specs {:base-url bad}) nil
                                    (catch clojure.lang.ExceptionInfo e (:autonomous/error (ex-data e)))))
        (pr-str bad)))
  (doseq [good ["http://localhost:18099/" "http://127.0.0.1:18099" "http://[::1]:18099" "https://127.0.0.2"]]
    (is (= 3 (count (control/tool-specs {:base-url good}))) good)))

(deftest a-redirect-is-never-followed
  (is (= :never ((requiring-resolve 'autonomous.openapi.http/redirect-policy))))
  (let [srv (hk/run-server (fn [_] {:status 302 :headers {"Location" (str *base* "/__sim/state")} :body ""})
                           {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    (try
      (a/reset-registry!)
      (control/install! {:base-url (str "http://127.0.0.1:" (hk/server-port srv)) :timeout-ms 2000})
      (let [r (call "sim_state" {})]
        (is (= :sim/refused (get-in r [:error :type])))
        (is (= 302 (get-in r [:error :data :status]))))
      (is (empty? @seen) "the redirect target was never asked")
      (finally @(hk/server-stop! srv {:timeout 1000})))))

(deftest the-persona-guard-allows-only-the-sim-provider
  (a/reg-tool :payment/createPixCharge
              {:description "Create a Pix charge" :input [:map [:provider :string] [:amount :string]]
               :handler (fn [args _] {:created (:provider args)})})
  (let [asker (approval/queue-asker [{:decision :deny :approver "pedro"}])]
    (a/decorate! (persona/money-guard asker {:tools #{"payment_createPixCharge"}}))
    (let [g {:actor "persona-1" :scopes [:read :write :payment/money]}]
      (is (= {:ok {:created "sicoob-sim"}} (call g "payment_createPixCharge" {:provider "sicoob-sim" :amount "1.27"})))
      (is (empty? @(:requests asker)))
      (let [r (call g "payment_createPixCharge" {:provider "756" :amount "1.27"})]
        (is (:pending r) "the real bank waits for a human")
        (is (= ["756"] (map (comp :provider :args) @(:requests asker)))))
      (is (= :forbidden (get-in (call {:actor "p" :scopes [:write]} "payment_createPixCharge"
                                      {:provider "sicoob-sim" :amount "1"}) [:error :type]))))
    (is (:ok (call "sim_state" {})) "the sim tools themselves are not money tools")))

(deftest the-persona-guard-refuses-to-guard-a-tool-that-is-not-there
  (let [asker (approval/queue-asker [])]
    (testing "the default money-tools are not registered here, so nothing would be guarded"
      (let [e (try (a/decorate! (persona/money-guard asker)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "decorate! throws")
        (is (= ["payment_createBoleto" "payment_createPixCharge"]
               (:missing (ex-data (or (ex-cause e) e)))))))
    (testing "one name of several missing is enough to refuse"
      (a/reg-tool :payment/createPixCharge {:description "Create a Pix charge" :input [:map [:provider :string]]
                                            :handler (fn [args _] args)})
      (is (thrown? clojure.lang.ExceptionInfo (a/decorate! (persona/money-guard asker))))
      (is (= {:ok {:provider "756"}}
             (call {:actor "p" :scopes [:write]} "payment_createPixCharge" {:provider "756"}))
          "the refused decoration left the surface as it was; the caller sees it is unguarded"))))
