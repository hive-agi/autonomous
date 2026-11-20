(ns autonomous.require-approval-test
  "require-approval end to end through rpc: the call is Pending until a
   human's Verdict (a queue asker injected through the Asker port), runs
   once on approve, never on deny or timeout, and the journal keeps the
   Decision."
  (:require [autonomous.approval :as approval]
            [autonomous.core :as a]
            [autonomous.decorator :as decorator]
            [autonomous.runtime.registry :as registry]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:private runs (atom 0))

(defn- fixture-app! []
  (reset! runs 0)
  (a/set-app-info! {:name "approval" :description "test app"})
  (a/reg-tool :pay {:description "Move money" :input [:map [:amount :int] [:pin {:optional true} :string]]
                    :handler (fn [args _] (swap! runs inc) {:paid (:amount args)})}))

(use-fixtures :each (fn [t] (registry/with-instance (registry/make-runtime) #(do (fixture-app!) (t)))))

(def ^:private rw {:actor "agent-1" :scopes [:read :write]})

(defn- call
  ([args] (call rw args))
  ([grant args] (a/rpc (merge grant {:op :call :tool "pay" :args args}))))

(defn- poll-until-settled [id]
  (loop [i 0]
    (let [r (a/rpc {:op :poll :id id})]
      (if (and (:pending r) (< i 400)) (do (Thread/sleep 5) (recur (inc i))) r))))

(defn- approving! [verdicts timeout-ms]
  (let [asker (approval/queue-asker verdicts)]
    (a/decorate! (a/each-tool (decorator/require-approval {:asker asker :timeout-ms timeout-ms})))
    asker))

(defn- settled-entry [id]
  (first (filter #(and (= :autonomous/settled (:kind %)) (= id (:pending %)))
                 (:entries (:ok (a/rpc {:op :journal}))))))

(deftest approve-runs-the-tool-exactly-once
  (let [asker (approving! [{:decision :approve :approver "ana"}] 1000)
        r     (call {:amount 10})]
    (is (string? (:pending r)) "the call is parked while the human decides")
    (is (= {:ok {:paid 10}} (poll-until-settled (:pending r))))
    (is (= 1 @runs))
    (is (= [{:tool "pay" :args {:amount 10} :actor "agent-1" :session nil}] @(:requests asker)))))

(deftest deny-refuses-and-never-runs-the-tool
  (approving! [{:decision :deny :approver "bob"}] 1000)
  (let [r (poll-until-settled (:pending (call {:amount 10})))]
    (is (= :approval-denied (get-in r [:error :type])))
    (is (zero? @runs))))

(deftest timeout-refuses-and-never-runs-the-tool
  (approving! [] 30)
  (let [r (poll-until-settled (:pending (call {:amount 10})))]
    (is (= :approval-timeout (get-in r [:error :type])))
    (Thread/sleep 50)
    (is (zero? @runs))))

(deftest the-decision-lands-in-the-journal
  (testing "approve: approver, decision and ms on the settled entry"
    (approving! [{:decision :approve :approver "ana"}] 1000)
    (let [id (:pending (call {:amount 10}))
          _  (poll-until-settled id)
          e  (settled-entry id)]
      (is (= :ok (:outcome e)))
      (is (= {:decision :approve :approver "ana"} (dissoc (:approval e) :ms)))
      (is (nat-int? (get-in e [:approval :ms])))))
  (testing "timeout is journaled as such, with no approver"
    (approving! [] 20)
    (let [id (:pending (call {:amount 1}))
          _  (poll-until-settled id)]
      (is (= [:approval-timeout {:decision :timeout}]
             ((juxt :outcome #(dissoc (:approval %) :ms)) (settled-entry id)))))))

(deftest the-approval-is-redacted-the-way-args-are
  (a/journal-calls! {:args? true :redact [[:pin] [:approval :approver]]})
  (approving! [{:decision :approve :approver "ana@bank"}] 1000)
  (let [id (:pending (call {:amount 10 :pin "1234"}))
        _  (poll-until-settled id)
        e  (settled-entry id)
        c  (first (filter #(= :autonomous/call (:kind %)) (:entries (:ok (a/rpc {:op :journal})))))]
    (is (= "<redacted>" (get-in c [:args :pin])))
    (is (= {:decision :approve :approver "<redacted>"} (dissoc (:approval e) :ms)))))

(deftest scope-is-checked-before-a-human-is-asked
  (let [asker (approval/queue-asker [{:decision :approve :approver "ana"}])]
    (a/decorate! (a/each-tool (comp (decorator/require-scope :payments)
                                    (decorator/require-approval {:asker asker :timeout-ms 1000}))))
    (testing "an unscoped caller is refused at once and never reaches the asker"
      (is (= :forbidden (get-in (call {:amount 10}) [:error :type])))
      (is (empty? @(:requests asker)))
      (is (zero? @runs)))
    (testing "a scoped caller is asked, then runs"
      (let [r (call (update rw :scopes conj :payments) {:amount 5})]
        (is (= {:ok {:paid 5}} (poll-until-settled (:pending r))))
        (is (= 1 (count @(:requests asker))))
        (is (= 1 @runs))))))

(defn- approving-tool! [handler]
  (a/reg-tool :pay {:description "Move money" :input [:map [:amount :int]] :handler handler})
  (approving! [{:decision :approve :approver "ana"}] 1000))

(deftest an-approved-tool-that-fails-keeps-its-approval-in-the-journal
  (doseq [[label handler] [["throws" (fn [_ _] (swap! runs inc) (throw (ex-info "bank down" {})))]
                           ["fails later" (fn [_ _] (swap! runs inc) (future (throw (ex-info "bank down" {}))))]]]
    (testing label
      (reset! runs 0)
      (approving-tool! handler)
      (let [id (:pending (call {:amount 10}))
            r  (poll-until-settled id)
            e  (settled-entry id)]
        (is (= :tool-threw (get-in r [:error :type])))
        (is (= 1 @runs))
        (is (= :tool-threw (:outcome e)))
        (is (= {:decision :approve :approver "ana"} (dissoc (:approval e) :ms)))))))

(defrecord ThrowingAsker []
  approval/Asker
  (-ask! [_ _] (throw (ex-info "asker down" {}))))

(defrecord PlainAsker []
  approval/Asker
  (-ask! [_ _] {:decision :approve :approver "not a promise"}))

(deftest an-asker-that-fails-refuses-and-journals-an-error-decision
  (doseq [asker [(->ThrowingAsker) (->PlainAsker)]]
    (a/decorate! (a/each-tool (decorator/require-approval {:asker asker :timeout-ms 1000})))
    (let [id (:pending (call {:amount 10}))
          r  (poll-until-settled id)]
      (is (= :approval-error (get-in r [:error :type])))
      (is (= :error (get-in (settled-entry id) [:approval :decision])))
      (is (zero? @runs)))))

(deftest the-human-sees-redacted-args-and-a-clean-short-actor
  (a/journal-calls! {:redact [[:pin]]})
  (let [asker (approving! [{:decision :deny :approver "bob"}] 1000)
        actor (str "evil\u001b[2J\n" (apply str (repeat 80 "x")))
        id    (:pending (call {:actor actor :scopes [:write]} {:amount 10 :pin "1234"}))
        _     (poll-until-settled id)
        seen  (first @(:requests asker))]
    (is (= "pay" (:tool seen)))
    (is (= {:amount 10 :pin "<redacted>"} (:args seen)) "never the raw pin")
    (is (= 64 (count (:actor seen))))
    (is (not (re-find #"\p{Cntrl}" (:actor seen))))
    (is (.startsWith ^String (:actor seen) "evil[2J"))))

(deftest a-timeout-at-or-past-the-pending-ttl-is-refused
  (let [asker (approval/queue-asker [])
        make  #(decorator/require-approval {:asker asker :timeout-ms %})]
    (is (< decorator/max-approval-ms registry/pending-ttl-ms) "a margin below the pending TTL")
    (is (fn? (make (dec decorator/max-approval-ms))))
    (doseq [ms [decorator/max-approval-ms registry/pending-ttl-ms (* 2 registry/pending-ttl-ms)]]
      (is (= :invalid-approval (try (make ms) nil
                                    (catch clojure.lang.ExceptionInfo e (:autonomous/error (ex-data e)))))
          ms))))

(deftest bad-options-throw-at-construction
  (is (thrown? clojure.lang.ExceptionInfo (decorator/require-approval {:asker :nope :timeout-ms 10})))
  (is (thrown? clojure.lang.ExceptionInfo
               (decorator/require-approval {:asker (approval/queue-asker []) :timeout-ms 0}))))
