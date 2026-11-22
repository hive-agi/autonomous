(ns autonomous.runtime.rpc-test
  "The runtime boundary end to end on the JVM (the same cljc runs in the
   browser): a trifecta locks the answers of `rpc-str` for stateless
   requests; scenario tests cover what needs state over time (async
   results, leases between two actors, the journal, middleware)."
  (:require [autonomous.approval :as approval]
            [autonomous.core :as a]
            [autonomous.decorator :as decorator]
            [autonomous.domain.wire :as wire]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [autonomous.runtime.registry :as registry]
            [autonomous.runtime.rpc :as rpc]
            [autonomous.support.wait :as wait]))

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

(use-fixtures :each (fn [t] (install-fixture-app!) (try (t) (finally (install-fixture-app!)))))

(def ^:private rw {:actor "agent-1" :scopes [:read :write]})

(defn- call [grant tool args] (a/rpc (merge grant {:op :call :tool tool :args args})))

(def ^:private state
  "The default runtime's state atom, the one these tests' rpc acts on."
  (wait/state-of registry/default-runtime))

(defn- poll-until-settled
  "The poll answer of parked `id` once the runtime has settled it; throws
   naming the id when it has not within the wait's deadline."
  [id]
  (wait/await-settled! state id)
  (a/rpc {:op :poll :id id}))

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

(defn- settled-entry
  "The :autonomous/settled journal entry of parked `id`, as the journal op
   reads it, once the runtime state holds it (collected or not); throws
   naming the id when it does not within the wait's deadline."
  [id]
  (wait/await-settled-entry! state id)
  (wait/settled-of id (:entries (:ok (a/rpc {:op :journal})))))

(deftest no-state-holds-a-settled-answer-without-its-journal-entry
  (let [torn (atom [])
        k    ::torn
        p    (promise)]
    (add-watch state k (fn [_ _ _ st]
                         (doseq [[id {:keys [status]}] (:pending st)
                                 :when (and (= :settled status)
                                            (nil? (wait/settled-of id (get-in st [:journal :entries]))))]
                           (swap! torn conj id))))
    (try
      (a/reg-tool :slow {:description "Slow" :handler (fn [_ _] p)})
      (let [{:keys [pending]} (call rw "slow" {})]
        (deliver p {:done true})
        (wait/await-settled! state pending)
        (is (= [] @torn) "every state that holds the settled answer holds its entry too")
        (is (some? (settled-entry pending))))
      (finally (remove-watch state k)))))

(defn- settle-under-detail
  "Call tool `tool-name` (its handler answers promise `p`), swap CallDetail
   `detail` into the runtime state while the call is parked, deliver `v`,
   and answer [parked-id poll-answer settled-entry]."
  [tool-name p detail v]
  (let [{:keys [pending]} (call rw tool-name {})]
    (is (string? pending))
    (swap! state assoc :call-detail detail)
    (deliver p v)
    [pending (poll-until-settled pending) (settled-entry pending)]))

(deftest an-approved-answer-that-cannot-be-journaled-settles-as-unknown-keeping-the-decision
  (let [p (promise)]
    (a/reg-tool :pay {:description "Pay" :handler (fn [_ _] p)})
    (a/decorate! (a/each-tool (decorator/require-approval
                               {:asker (approval/queue-asker [{:decision :approve :approver "ana"}])
                                :timeout-ms 1000})))
    (let [[id r e] (settle-under-detail "pay" p {:redact 42} {:paid 1})]
      (testing "the poll: a refusal that says the tool ran and its result is unknown"
        (is (= :settle-failed (get-in r [:error :type])))
        (is (re-find #"^the tool ran, .*unknown.*before you retry" (get-in r [:error :message])))
        (is (= {:outcome :ok} (get-in r [:error :data]))))
      (testing "the journal: a literal entry that keeps the Decision, its approver masked"
        (is (= {:kind :autonomous/settled :pending id :outcome :settle-failed}
               (dissoc e :approval :seq :at)))
        (is (= {:decision :approve :approver "<redacted>"} (dissoc (:approval e) :ms)))
        (is (nat-int? (get-in e [:approval :ms])))))))

(deftest a-journal-failure-that-does-not-depend-on-the-answer-still-settles-the-call
  (let [p (promise)]
    (a/reg-tool :slow {:description "Slow" :handler (fn [_ _] p)})
    (let [[id r e] (settle-under-detail "slow" p (sorted-map 1 1) {:done true})]
      (is (= :settle-failed (get-in r [:error :type])) "settled, not Pending for ever")
      (is (re-find #"^the tool ran, " (get-in r [:error :message])))
      (is (str/ends-with? (get-in r [:error :message]) "(java.lang.ClassCastException)")
          "the agent is told the type of the failure")
      (is (not (str/includes? (get-in r [:error :message]) "cannot be cast"))
          "and never the failure's own message")
      (is (= {:kind :autonomous/settled :pending id :outcome :settle-failed}
             (dissoc e :seq :at))
          "no :approval when there was no human Decision"))))

(defn- reporting-writer
  "A Writer that keeps what is written to it, and delivers it to promise
   `p` on the first flush whose text holds `needle`."
  [p needle]
  (let [sb (StringBuilder.)]
    (proxy [java.io.Writer] []
      (write
        ([x] (locking sb (.append sb (cond (string? x)  x
                                           (integer? x) (str (char x))
                                           :else        (String. ^chars x)))))
        ([x off len] (locking sb (.append sb (if (string? x)
                                               (subs x off (+ off len))
                                               (String. ^chars x (int off) (int len)))))))
      (flush [] (let [s (locking sb (str sb))] (when (str/includes? s needle) (deliver p s))))
      (close [] nil))))

(deftest a-settle-whose-watch-throws-keeps-its-answer-and-one-entry
  (let [p        (promise)
        reported (promise)
        parked   (atom nil)
        k        ::throwing-watch
        newly    (fn [id old new] (and (nil? (wait/settled-of id (get-in old [:journal :entries])))
                                       (wait/settled-of id (get-in new [:journal :entries]))))]
    (add-watch state k (fn [_ _ old new]
                         (when-let [id @parked]
                           (when (newly id old new)
                             (throw (ex-info "watch saw s3cret" {:value "s3cret"}))))))
    (try
      (a/reg-tool :slow {:description "Slow" :handler (fn [_ _] p)})
      (let [{:keys [pending]} (binding [*err* (reporting-writer reported "parked call")]
                                (call rw "slow" {}))]
        (reset! parked pending)
        (deliver p {:done true})
        (let [err (deref reported wait/default-ms nil)]
          (is (some? err) "the watch's throw is reported, not lost in the discarded future")
          (is (str/includes? (str err) (str "parked call " pending " settled, but a watch")))
          (is (str/includes? (str err) "(clojure.lang.ExceptionInfo)"))
          (is (not (str/includes? (str err) "s3cret")) "by its type, never its message"))
        (is (= {:ok {:done true}} (poll-until-settled pending))
            "the answer the committed settle! recorded, not a :settle-failed in its place")
        (is (= [{:kind :autonomous/settled :pending pending :outcome :ok}]
               (map #(dissoc % :seq :at)
                    (filter #(and (= :autonomous/settled (:kind %)) (= pending (:pending %)))
                            (:entries (:ok (a/rpc {:op :journal}))))))
            "one settled entry for the id, not a second fallback one"))
      (finally (remove-watch state k)))))


(deftest an-async-call-joins-its-settled-entry-by-pending-id
  (a/reg-tool :slow-boom {:description "x" :handler (fn [_ _] (future (throw (ex-info "late" {:why 1}))))})
  (let [call-of (fn [id] (first (filter #(and (= :autonomous/call (:kind %)) (= id (:pending %)))
                                        (:entries (:ok (a/rpc {:op :journal}))))))]
    (testing "without call detail: the id joins them, the outcome says how it ended"
      (let [{:keys [pending]} (call rw "slow-boom" {})]
        (poll-until-settled pending)
        (is (= :pending (:outcome (call-of pending))))
        (is (= {:kind :autonomous/settled :pending pending :outcome :tool-threw}
               (dissoc (settled-entry pending) :seq :at)))))
    (testing "with :errors? the settled entry keeps the refusal"
      (a/journal-calls! {:errors? true})
      (let [{:keys [pending]} (call rw "slow-boom" {})]
        (poll-until-settled pending)
        (is (= pending (:pending (call-of pending))))
        (is (= {:type :tool-threw :message "late" :data {:why 1}}
               (:error (settled-entry pending))))))))

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

(deftest call-detail-is-opt-in
  (let [s1 (assoc rw :session "s1")
        calls #(filter (comp #{:autonomous/call} :kind) (:entries (:ok (a/rpc {:op :journal}))))]
    (call s1 "echo_args" {:text "x"})
    (testing "by default a call entry keeps actor, tool, outcome and duration only"
      (is (= #{:kind :actor :tool :outcome :ms :seq :at} (set (keys (first (calls))))))
      (is (= :ok (:outcome (first (calls))))))
    (a/journal-calls! {:args? true :errors? true :redact [[:token]]})
    (call s1 "echo_args" {:text "x" :token "t0p"})
    (call s1 "echo_args" {:n 1})
    (call s1 "echo_arg" {:text "x"})
    (let [[_ ok invalid unknown] (calls)]
      (testing "the args as sent, with redacted paths, and the session"
        (is (= {:text "x" :token "<redacted>"} (:args ok)))
        (is (= "s1" (:session ok)))
        (is (not (contains? ok :error))))
      (testing "a refusal keeps its type, message and data"
        (is (= {:n 1} (:args invalid)))
        (is (= [:invalid-args {:text ["missing required key"]}]
               ((juxt :type :data) (:error invalid))))
        (is (= :unknown-tool (get-in unknown [:error :type])))
        (is (some #{"echo_args"} (get-in unknown [:error :data :known])))))
    (testing "an invalid detail is refused and nil turns it off"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid call detail"
                            (a/journal-calls! {:args? "yes"})))
      (is (thrown? clojure.lang.ExceptionInfo (a/journal-calls! {:bogus true})))
      (a/journal-calls! nil)
      (call s1 "echo_args" {:text "y"})
      (is (not (contains? (last (calls)) :args))))))

(deftest a-journal-args-path-that-stops-at-a-string-masks-it-whole
  (a/journal-calls! {:args? true :redact [[:text 0] [:n :x]]})
  (call rw "echo_args" {:text "s3cret" :n 2})
  (let [entry (last (filter (comp #{:autonomous/call} :kind) (:entries (:ok (a/rpc {:op :journal})))))]
    (is (= {:text "<redacted>" :n "<redacted>"} (:args entry))
        "a string or a number where the path expected a collection is masked, not kept as absent")))

(deftest middleware-wraps-every-invocation
  (let [seen (atom [])]
    (a/use! (fn [h] (fn [tool args grant] (swap! seen conj [(:name tool) (:actor grant)]) (h tool args grant))))
    (call rw "echo_args" {:text "x"})
    (is (= [["echo_args" "agent-1"]] @seen))))

(deftest resource-reads-settle-any-value
  (a/reg-resource "app://count" {:description "A number" :read (fn [_] 42)})
  (a/reg-resource "app://later" {:description "Computed later" :read (fn [_] (future {:n 1}))})
  (a/reg-resource "app://answer" {:description "Answers itself" :read (fn [_] {:ok #{:a}})})
  (let [read (fn [uri] (a/rpc (merge rw {:op :read :uri uri})))]
    (testing "a plain value that is not a map is Ok"
      (is (= 42 (get-in (read "app://count") [:ok :value]))))
    (testing "a thenable is Pending, then settles"
      (let [r (read "app://later")]
        (is (string? (:pending r)))
        (is (= {:n 1} (:ok (poll-until-settled (:pending r)))))))
    (testing "an Ok Answer is unwrapped once and wire-normalized"
      (is (= (wire/->wire #{:a}) (get-in (read "app://answer") [:ok :value]))))))

(deftest registration-fails-loudly
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs a :handler"
                        (a/reg-tool :x {:description "x"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already used"
                        (a/reg-tool :echo_args {:description "clash" :handler (fn [_ _])})))
  (testing "re-registering the same id replaces it (hot reload)"
    (a/reg-tool :echo/args {:description "v2" :handler (fn [_ _] :v2)})
    (is (= {:ok :v2} (call rw "echo_args" {})))))
