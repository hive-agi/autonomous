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

(deftest the-stdin-asker-approves-only-on-yes
  (testing "y approves and the prompt names the call"
    (let [out (StringWriter.)]
      (is (= {:decision :approve :approver "op"} (dissoc (ask (stdin-asker "Y\n" out) 500) :ms)))
      (is (re-find #"pay" (str out)))))
  (testing "anything else, end of input included, denies"
    (doseq [typed ["n\n" "maybe\n" ""]]
      (is (= :deny (:decision (ask (stdin-asker typed (StringWriter.)) 500)))))))
