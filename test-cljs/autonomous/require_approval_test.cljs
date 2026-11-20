(ns autonomous.require-approval-test
  "require-approval on a JS runtime: the asker answers a thenable, the call
   is Pending, and the settled answer is the human's decision."
  (:require [autonomous.approval :as approval]
            [autonomous.core :as a]
            [autonomous.decorator :as decorator]
            [cljs.test :refer [async deftest is]]))

(def ^:private runs (atom 0))

(defn- install! [verdicts timeout-ms]
  (reset! runs 0)
  (a/reset-registry!)
  (a/reg-tool :pay {:description "Move money" :input [:map [:amount :int]]
                    :handler (fn [args _] (swap! runs inc) {:paid (:amount args)})})
  (a/decorate! (a/each-tool (decorator/require-approval
                             {:asker (approval/queue-asker verdicts) :timeout-ms timeout-ms}))))

(defn- settle-then [id f]
  (let [r (a/rpc {:op :poll :id id})]
    (if (:pending r) (js/setTimeout #(settle-then id f) 5) (f r))))

(defn- call [] (a/rpc {:op :call :tool "pay" :args {:amount 7} :actor "agent" :scopes [:write]}))

(deftest approve-runs-once-on-js
  (async done
    (install! [{:decision :approve :approver "ana"}] 1000)
    (let [r (call)]
      (is (string? (:pending r)))
      (settle-then (:pending r)
                   (fn [s] (is (= {:ok {:paid 7}} s)) (is (= 1 @runs)) (done))))))

(deftest deny-and-timeout-never-run-on-js
  (async done
    (install! [{:decision :deny :approver "bob"}] 1000)
    (settle-then (:pending (call))
                 (fn [s]
                   (is (= :approval-denied (get-in s [:error :type])))
                   (install! [] 20)
                   (settle-then (:pending (call))
                                (fn [t]
                                  (is (= :approval-timeout (get-in t [:error :type])))
                                  (is (zero? @runs))
                                  (done)))))))
