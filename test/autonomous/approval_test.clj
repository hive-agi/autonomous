(ns autonomous.approval-test
  (:require [autonomous.approval :as approval]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m])
  (:import [java.io BufferedReader StringReader StringWriter]))

(def ^:private request {:tool "pay" :args {:amount 10} :actor "agent"})

(defn- ask [asker timeout-ms]
  (deref (approval/ask! asker request timeout-ms) 2000 ::hung))

(deftest a-queue-asker-answers-its-verdicts-in-order-and-records-requests
  (let [asker (approval/queue-asker [{:decision :approve :approver "ana"}
                                     {:decision :deny :approver "bob"}])]
    (is (approval/asker? asker))
    (is (= {:decision :approve :approver "ana"} (dissoc (ask asker 500) :ms)))
    (is (= {:decision :deny :approver "bob"} (dissoc (ask asker 500) :ms)))
    (is (= [request request] @(:requests asker)))))

(deftest no-verdict-in-time-is-a-timeout
  (let [d (ask (approval/queue-asker []) 30)]
    (is (= :timeout (:decision d)))
    (is (<= 30 (:ms d)))))

(deftest a-malformed-verdict-denies
  (is (= :deny (:decision (ask (approval/queue-asker [{:decision :maybe}]) 500))))
  (is (= :deny (:decision (ask (approval/queue-asker [:yes]) 500)))))

(deftest every-decision-matches-the-decision-schema
  (doseq [v [{:decision :approve :approver "a"} {:decision :deny :approver "b"} nil]]
    (is (m/validate approval/Decision (ask (approval/queue-asker (if v [v] [])) 20)))))

(defn- stdin-asker [typed out]
  (approval/stdin-asker {:in (BufferedReader. (StringReader. typed)) :out out :approver "op"}))

(deftest the-stdin-asker-approves-only-its-own-numbered-yes
  (testing "\"1 y\" approves request 1 and the prompt shows its number and tool"
    (let [out (StringWriter.)]
      (is (= {:decision :approve :approver "op"} (dissoc (ask (stdin-asker "1 Y\n" out) 500) :ms)))
      (is (re-find #"\[1\].*pay" (str out)))))
  (testing "a bare y, another number, anything else or end of input denies"
    (doseq [typed ["y\n" "yes\n" "2 y\n" "1 y please\n" "n\n" ""]]
      (is (= :deny (:decision (ask (stdin-asker typed (StringWriter.)) 500))) typed))))

(defn- typist
  "A fake terminal: read-line blocks until a line is typed with `type!`."
  []
  (let [q (java.util.concurrent.LinkedBlockingQueue.)]
    {:read-line #(.take q) :type! #(.put q %)}))

(defn- typed-asker [{:keys [read-line]}]
  (approval/stdin-asker {:read-line read-line :out (StringWriter.) :approver "op"}))

(deftest concurrent-calls-are-asked-one-at-a-time-in-order
  (let [t     (typist)
        asker (typed-asker t)
        a     (approval/ask! asker (assoc request :tool "a") 2000)
        b     (approval/ask! asker (assoc request :tool "b") 2000)]
    ((:type! t) "2 y")
    ((:type! t) "2 y")
    (is (= :deny (:decision (deref a 1000 ::hung))) "request 1 read the first line, meant for 2")
    (is (= :approve (:decision (deref b 1000 ::hung))) "request 2 is approved by its own line")))

(deftest a-timed-out-request-is-withdrawn-and-never-takes-a-later-line
  (let [t     (typist)
        asker (typed-asker t)
        a     (approval/ask! asker (assoc request :tool "a") 50)]
    (is (= :timeout (:decision (deref a 1000 ::hung))))
    (let [b (approval/ask! asker (assoc request :tool "b") 2000)
          c (approval/ask! asker (assoc request :tool "c") 2000)]
      ((:type! t) "2 n")
      ((:type! t) "3 y")
      ((:type! t) "3 y")
      (is (= :timeout (:decision (deref a 10 ::hung))) "a late line never answers request 1")
      (is (= :deny (:decision (deref b 1000 ::hung)))
          "the stale reader of 1 ate \"2 n\"; request 2 then read \"3 y\", which is not its yes")
      (is (= :approve (:decision (deref c 1000 ::hung))) "request 3 is approved by its own line"))))
