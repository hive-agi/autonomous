(ns autonomous.runtime.rpc-test
  "The runtime boundary end to end on the JVM (the same cljc runs in the
   browser): a trifecta locks the answers of `rpc-str` for stateless
   requests; scenario tests cover what needs state over time (async
   results, leases between two actors, the journal, middleware)."
  (:require [autonomous.core :as a]
            [autonomous.domain.wire :as wire]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [autonomous.runtime.rpc :as rpc]))

(defn install-fixture-app! []
  (a/reset-registry!)
  (a/set-app-info! {:name "fixture" :description "test app"})
  (a/reg-tool :echo/args {:description "Echo the args"
                          :input [:map [:text :string] [:n {:optional true} :int]]
                          :handler (fn [args _] args)})
  (a/reg-tool :echo/actor {:description "Who is calling" :read-only? true
                           :handler (fn [_ grant] (:actor grant))})
  (a/reg-tool :boom {:description "Always throws"
                     :handler (fn [_ _] (throw (ex-info "kaboom" {:autonomous/error :app-failure :why 1})))})
  (a/reg-tool :secret {:description "Admin only" :scopes [:admin] :handler (fn [_ _] :s)})
  (a/reg-resource "app://status" {:description "Status" :read (fn [_] {:up true})}))

(install-fixture-app!)

(defn- strip-volatile
  "Answers minus fields that vary run to run."
  [edn-text]
  (let [a (wire/read-edn edn-text)]
    (cond-> a
      (get-in a [:ok :version]) (assoc-in [:ok :version] :v))))

(deftrifecta rpc-str-trifecta
  autonomous.core/rpc-str
  {:golden-path "test/golden/runtime/rpc-str.edn"
   :xf strip-volatile
   :cases {:ping          "{:op :ping}"
           :manifest-anon "{:op :manifest}"
           :manifest-rw   "{:op :manifest :actor \"a\" :scopes [:read :write]}"
           :call-ok       "{:op :call :tool \"echo_args\" :args {:text \"hi\" :n 2} :scopes [:write]}"
           :call-coerces  "{:op :call :tool \"echo_args\" :args {:text \"hi\" :n \"2\"} :scopes [:write]}"
           :call-actor    "{:op :call :tool \"echo_actor\" :actor \"agent-7\" :scopes [:read]}"
           :call-invalid  "{:op :call :tool \"echo_args\" :args {} :scopes [:write]}"
           :call-denied   "{:op :call :tool \"echo_args\" :args {:text \"x\"}}"
           :call-admin    "{:op :call :tool \"secret\" :scopes [:read :write]}"
           :call-throws   "{:op :call :tool \"boom\" :scopes [:write]}"
           :read-ok       "{:op :read :uri \"app://status\"}"
           :read-unknown  "{:op :read :uri \"app://nope\"}"
           :bad-shape     "{:op :call :tool 42}"
           :unknown-op    "{:op :zap}"
           :not-edn       "{:op"}
   :gen (gen/fmap wire/print-edn
                  (gen/hash-map :op (gen/elements [:ping :manifest :call :read :zap])
                                :tool (gen/elements ["echo_args" "echo_actor" "nope"])
                                :uri (gen/elements ["app://status" "app://x"])))
   :pred (fn [s] (let [a (wire/read-edn s)] (and (map? a) (= 1 (count (select-keys a [:ok :pending :error]))))))
   :num-tests 200
   ;; Mutants call the runtime ns directly: the subject var is the one rebound.
   :mutations [["always ok" (fn [_] "{:ok nil}")]
               ["ignores scopes" (fn [s] (rpc/rpc-str (wire/print-edn (assoc (wire/read-edn s) :scopes [:admin]))))]
               ["drops refusal data" (fn [s] (let [x (wire/read-edn (rpc/rpc-str s))]
                                               (wire/print-edn (update x :error dissoc :data))))]]})

(use-fixtures :each (fn [t] (install-fixture-app!) (t)))

(def ^:private rw {:actor "agent-1" :scopes [:read :write]})

(defn- call [grant tool args] (a/rpc (merge grant {:op :call :tool tool :args args})))

(defn- poll-until-settled [id]
  (loop [i 0]
    (let [r (a/rpc {:op :poll :id id})]
      (if (and (:pending r) (< i 200)) (do (Thread/sleep 5) (recur (inc i))) r))))

(deftest async-results-park-then-settle
  (let [p (promise)]
    (a/reg-tool :slow {:description "Slow" :handler (fn [_ _] p)})
    (let [{:keys [pending]} (call rw "slow" {})]
      (is (string? pending))
      (is (= {:pending pending} (a/rpc {:op :poll :id pending})))
      (deliver p {:done true})
      (is (= {:ok {:done true}} (poll-until-settled pending)))
      (is (= :unknown-pending (get-in (a/rpc {:op :poll :id pending}) [:error :type]))
          "a collected result is gone"))))

(deftest a-failed-future-settles-as-a-refusal
  (a/reg-tool :slow-boom {:description "x" :handler (fn [_ _] (future (throw (ex-info "late" {}))))})
  (let [{:keys [pending]} (call rw "slow-boom" {})]
    (is (string? pending))
    (is (= "late" (get-in (poll-until-settled pending) [:error :message])))))

(deftest leases-serialize-writers-and-leave-reads-open
  (let [b {:actor "agent-2" :scopes [:read :write]}]
    (is (:ok (a/rpc (merge rw {:op :lease :ttl-ms 10000}))))
    (is (= :leased (get-in (a/rpc (merge b {:op :lease})) [:error :type])))
    (is (= :leased (get-in (call b "echo_args" {:text "x"}) [:error :type])))
    (is (= {:ok "agent-2"} (call b "echo_actor" {})) "reads stay open")
    (is (:ok (call rw "echo_args" {:text "x"})) "the holder writes")
    (a/rpc (merge rw {:op :release}))
    (is (:ok (call b "echo_args" {:text "x"})) "released: others write again")))

(deftest the-journal-records-who-did-what
  (call rw "echo_args" {:text "x"})
  (call {:actor "agent-2" :scopes [:read]} "echo_args" {:text "x"})
  (a/journal! {:kind :app/user-clicked :target "save"})
  (let [{:keys [seq entries]} (:ok (a/rpc {:op :journal :since 0}))]
    (is (= 3 seq))
    (is (= [["agent-1" :ok] ["agent-2" :forbidden]]
           (map (juxt :actor :outcome) (filter #(= :autonomous/call (:kind %)) entries))))
    (is (= :app/user-clicked (:kind (last entries))))
    (testing "a reader resumes from the last sequence it saw"
      (is (= 1 (count (:entries (:ok (a/rpc {:op :journal :since 2})))))))))

(deftest middleware-wraps-every-invocation
  (let [seen (atom [])]
    (a/use! (fn [h] (fn [tool args grant] (swap! seen conj [(:name tool) (:actor grant)]) (h tool args grant))))
    (call rw "echo_args" {:text "x"})
    (is (= [["echo_args" "agent-1"]] @seen))))

(deftest registration-fails-loudly
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs a :handler"
                        (a/reg-tool :x {:description "x"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already used"
                        (a/reg-tool :echo_args {:description "clash" :handler (fn [_ _])})))
  (testing "re-registering the same id replaces it (hot reload)"
    (a/reg-tool :echo/args {:description "v2" :handler (fn [_ _] :v2)})
    (is (= {:ok :v2} (call rw "echo_args" {})))))
