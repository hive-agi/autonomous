(ns autonomous.bridge.nrepl-channel-test
  "JVM only (the record implements java.io.Closeable, which babashka
   cannot load): the connection lifecycle of the nREPL channel. A stub
   connection sits in the channel's atom, and a real in-process nREPL
   server is the one a reconnect reaches. No with-redefs."
  (:require [autonomous.bridge.endpoint :as endpoint]
            [autonomous.bridge.nrepl :as n]
            [autonomous.bridge.port :as port]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [nrepl.server :as server]))

(def ^:private srv (atom nil))

(use-fixtures :once
  (fn [run]
    (reset! srv (server/start-server :bind "127.0.0.1" :port 0))
    (try (run) (finally (server/stop-server @srv)))))

(defn- live-target [] {:nrepl/mode :clj :nrepl/port (:port @srv)})

(defn- stub-conn
  "A connection whose session runs `on-message`, counting messages and closes."
  [counts on-message]
  {:transport (reify java.io.Closeable (close [_] (swap! counts update :closed inc)))
   :session   (fn [_msg] (swap! counts update :messages inc) (on-message))})

(defn- channel-with
  "An NreplChannel for `target` already holding connection `conn`."
  [target conn]
  (n/->NreplChannel target (atom conn)))

(def ^:private eval! #'n/eval!)

(deftest a-port-refusal-passes-through-unchanged
  (let [target {:nrepl/mode :clj :nrepl/port-file "/nonexistent/.nrepl-port"}]
    (is (= (endpoint/resolve-port target) (port/-send (n/channel target) {:op :ping} nil)))
    (is (= :channel/no-port (get-in (port/-send (n/channel target) {:op :ping} nil) [:error :type])))))

(deftest a-broken-socket-reconnects-exactly-once
  (testing "the stub is dropped once and the eval runs on a fresh connection"
    (let [counts (atom {:messages 0 :closed 0})
          stub   (stub-conn counts #(throw (java.net.SocketException. "connection reset")))
          ch     (channel-with (live-target) stub)]
      (is (= {:ok "3"} (eval! ch "(+ 1 2)")))
      (is (= {:messages 1 :closed 1} @counts))
      (is (some? @(:conn ch)))
      (is (not= stub @(:conn ch)))
      (.close ^java.io.Closeable ch)))
  (testing "a reconnect that cannot open answers its refusal; nothing retries again"
    (let [counts (atom {:messages 0 :closed 0})
          ch     (channel-with {:nrepl/mode :clj}
                               (stub-conn counts #(throw (java.net.SocketException. "reset"))))]
      (is (= :channel/no-port (get-in (eval! ch "(+ 1 2)") [:error :type])))
      (is (= {:messages 1 :closed 1} @counts))
      (is (nil? @(:conn ch))))))

(deftest a-generic-failure-drops-the-connection
  (let [counts (atom {:messages 0 :closed 0})
        ch     (channel-with (live-target) (stub-conn counts #(throw (RuntimeException. "boom"))))]
    (is (= {:error {:type :channel/transport-failed :message "boom"}} (eval! ch "(+ 1 2)")))
    (is (= {:messages 1 :closed 1} @counts))
    (is (nil? @(:conn ch)))))

(deftest an-ex-info-keeps-the-connection
  (let [counts (atom {:messages 0 :closed 0})
        stub   (stub-conn counts #(throw (ex-info "the tool said no" {:autonomous/error :custom/no :why 1})))
        ch     (channel-with (live-target) stub)]
    (is (= {:error {:type :custom/no :message "the tool said no" :data {:why 1}}} (eval! ch "(+ 1 2)")))
    (is (= {:messages 1 :closed 0} @counts))
    (is (= stub @(:conn ch)))))
