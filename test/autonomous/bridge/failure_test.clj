(ns autonomous.bridge.failure-test
  "The text a transport failure may show: an allowlist by exception class,
   never the exception's own message."
  (:require [autonomous.bridge.failure :as failure]
            [autonomous.bridge.http.jdk :as jdk]
            [autonomous.bridge.port :as port]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private secret "Bearer aut_leaked https://ops:pw@host/?k=v")

(deftest an-exception-message-is-never-shown
  (doseq [e [(java.net.ConnectException. secret)
             (java.net.UnknownHostException. secret)
             (IllegalArgumentException. secret)
             (RuntimeException. secret)
             (ex-info secret {})]]
    (let [m (failure/message e)]
      (is (string? m))
      (is (not (str/includes? m "aut_leaked")) (str (class e) ": " m)))))

(deftest an-allowlisted-class-gets-its-fixed-text
  (is (= "the connection was refused" (failure/message (java.net.ConnectException. "x"))))
  (is (= "the host is unknown" (failure/message (java.net.UnknownHostException. "x"))))
  (testing "any other class is named, not described"
    (is (= "the transport failed (java.lang.RuntimeException)" (failure/message (RuntimeException. "x"))))))

(deftest only-a-failure-before-the-request-is-safe-to-retry
  (doseq [e [(java.net.ConnectException. "x") (java.net.UnknownHostException. "x")
             (IllegalArgumentException. "x")]]
    (is (failure/before-request? e) (str (class e))))
  (doseq [e [(java.net.http.HttpTimeoutException. "x") (java.net.SocketException. "x")
             (java.io.EOFException. "x") (java.io.IOException. "x") (RuntimeException. "x")]]
    (is (not (failure/before-request? e)) (str (class e))))
  (testing "a read timeout is not a connect timeout"
    (is (not (failure/before-request? (java.net.http.HttpTimeoutException. "request timed out"))))))

(deftest a-request-that-cannot-be-built-is-safe-to-retry
  (let [a (port/-request! (jdk/client) {:method :post :url "http://127.0.0.1:9/mcp" :timeout-ms 500
                                        :headers {"x-bad" "line\nbreak aut_leaked"} :body "{}"})]
    (is (= {:type :channel/transport-failed :message "the request could not be built"}
           (select-keys (:error a) [:type :message]))
        (pr-str a)))
  (testing "an IllegalArgumentException once the request is handed over is not proof nothing was sent"
    (is (= :channel/outcome-unknown
           (get-in (#'jdk/transport-failed (IllegalArgumentException. "late")) [:error :type])))
    (is (= :channel/transport-failed
           (get-in (#'jdk/transport-failed (java.net.ConnectException. "x")) [:error :type])))))

(deftest the-jdk-adapter-shows-only-allowlisted-text
  (let [a (port/-request! (jdk/client) {:method :post :url "http://127.0.0.1:9/mcp" :timeout-ms 500
                                        :headers {"x-probe" "aut_leaked"} :body "{}"})]
    (is (= {:type :channel/transport-failed :message "the connection was refused"}
           (select-keys (:error a) [:type :message]))
        (pr-str a))))
