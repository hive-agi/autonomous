(ns autonomous.runtime.intercept-test
  "The call chain and decorators wired into a runtime, end to end through
   rpc: intercept!, the use! shim, output redaction, decorated surfaces."
  (:require [autonomous.core :as a]
            [autonomous.decorator :as decorator]
            [autonomous.runtime.registry :as registry]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [autonomous.runtime.invoke :as invoke]
            [autonomous.domain.answer :as answer]))

(defn- fixture-app! []
  (a/set-app-info! {:name "intercept" :description "test app"})
  (a/reg-tool :t/echo {:description "Echo" :input [:map [:text :string]]
                       :handler (fn [args _] args)})
  (a/reg-tool :t/account {:description "Account" :read-only? true
                          :output [:map [:user :string] [:token :string]]
                          :handler (fn [_ _] {:user "ana" :token "s3cret"})})
  (a/reg-tool :t/later {:description "Async account" :read-only? true
                        :handler (fn [_ _] (future {:user "ana" :token "s3cret"}))}))

(use-fixtures :each (fn [t] (registry/with-instance (registry/make-runtime) #(do (fixture-app!) (t)))))

(def ^:private rw {:actor "agent-1" :scopes [:read :write]})

(defn- call [tool args] (a/rpc (merge rw {:op :call :tool tool :args args})))

(defn- poll-until-settled [id]
  (loop [i 0]
    (let [r (a/rpc {:op :poll :id id})]
      (if (and (:pending r) (< i 200)) (do (Thread/sleep 5) (recur (inc i))) r))))

(defn- tracing [log n]
  {:name  (keyword "t" (name n))
   :enter (fn [ctx] (swap! log conj [:enter n (get-in ctx [:tool :name]) (get-in ctx [:grant :actor])]) ctx)
   :leave (fn [ctx] (swap! log conj [:leave n (:answer ctx)]) ctx)})

(deftest intercept-runs-around-every-call
  (let [log (atom [])]
    (a/intercept! (tracing log :one))
    (a/intercept! (tracing log :two))
    (is (= {:ok {:text "x"}} (call "t_echo" {:text "x"})))
    (is (= [[:enter :one "t_echo" "agent-1"] [:enter :two "t_echo" "agent-1"]
            [:leave :two {:ok {:text "x"}}] [:leave :one {:ok {:text "x"}}]]
           @log))))

(deftest intercept-can-refuse-and-transform
  (a/intercept! {:name :t/deny-echo
                 :enter (fn [ctx] (cond-> ctx (= "t_echo" (get-in ctx [:tool :name]))
                                    (assoc :answer {:error {:type :t/denied :message "no"}})))})
  (a/intercept! {:name :t/upcase
                 :leave (fn [ctx] (update-in ctx [:answer :ok :user] #(some-> % str/upper-case)))})
  (is (= :t/denied (get-in (call "t_echo" {:text "x"}) [:error :type])))
  (is (= "ANA" (get-in (call "t_account" {}) [:ok :user])))
  (testing "a refusal is journaled as the call's outcome"
    (is (= :t/denied (:outcome (first (filter #(= "t_echo" (:tool %))
                                              (:entries (:ok (a/rpc {:op :journal :since 0}))))))))))

(deftest intercept-refuses-bad-links-at-registration
  (a/intercept! {:name :t/once :enter identity})
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already in the chain"
                        (a/intercept! {:name :t/once :leave identity})))
  (is (thrown? clojure.lang.ExceptionInfo (a/intercept! {:enter identity})))
  (is (= 1 (count (:interceptors (registry/surface-value)))) "a refusal changes nothing"))

(deftest use!-is-a-shim-over-the-chain
  (let [log (atom [])
        mw  (fn [n] (fn [h] (fn [tool args grant] (swap! log conj n) (h tool args grant))))]
    (a/use! (mw :first))
    (a/use! (mw :second))
    (is (= {:ok {:text "x"}} (call "t_echo" {:text "x"})))
    (is (= [:second :first] @log) "last registered runs outermost")
    (is (= 2 (count (:interceptors (registry/surface-value)))))
    (is (not (contains? (registry/surface-value) :middleware)))
    (testing "a wrapper that short-circuits answers the call"
      (a/use! (fn [_] (fn [_ _ _] :short)))
      (is (= {:ok :short} (call "t_echo" {:text "x"}))))
    (testing "a wrapper that throws becomes a refusal"
      (a/use! (fn [_] (fn [_ _ _] (throw (ex-info "mw" {:autonomous/error :t/mw})))))
      (is (= :t/mw (get-in (call "t_echo" {:text "x"}) [:error :type]))))))

(deftest use!-links-are-numbered-by-a-counter-not-the-chain-length
  (let [mw (fn [h] h)]
    (a/use! mw)
    (a/intercept! {:name :t/between :enter identity})
    (a/use! mw)
    (is (= [:autonomous.use/mw-0 :t/between :autonomous.use/mw-1]
           (mapv :name (:interceptors (registry/surface-value)))))))

(def ^:private redacted (decorator/each-tool (decorator/redact-output [[:token]])))

(deftest redact-output-masks-direct-and-settled-results
  (a/decorate! redacted)
  (is (= {:ok {:user "ana" :token "<redacted>"}} (call "t_account" {})))
  (let [{:keys [pending]} (call "t_later" {})]
    (is (string? pending))
    (is (= {:ok {:user "ana" :token "<redacted>"}} (poll-until-settled pending)))))

(deftest redact-output-reaches-through-seqs-and-lists
  (a/decorate! (decorator/each-tool (decorator/redact-output [[:accounts 0 :token]])))
  (doseq [[id shape] [[:t/lazy #(map identity %)] [:t/listed #(apply list %)]]]
    (let [tool-name (a/reg-tool id {:description "Accounts" :read-only? true
                                    :handler (fn [_ _] {:accounts (shape [{:token "s3cret"}])})})]
      (is (= {:ok {:accounts [{:token "<redacted>"}]}} (call tool-name {}))
          (str "the secret never reaches the wire through " id)))))

(deftest an-output-path-that-stops-at-a-string-masks-it-whole
  (a/reg-tool :t/session {:description "Session" :read-only? true
                          :handler (fn [_ _] {:user "ana" :set-cookie "sid=s3cret"})})
  (a/decorate! (decorator/each-tool (decorator/redact-output [[:set-cookie 0]])))
  (is (= {:ok {:user "ana" :set-cookie "<redacted>"}} (call "t_session" {}))
      "one cookie string where a vector of cookies was expected never reaches the agent"))

(deftest a-redaction-that-breaks-the-output-schema-is-refused
  (a/decorate! (decorator/each-tool (decorator/redact-output [[:token]] 0)))
  (is (= :output/invalid-redaction (get-in (call "t_account" {}) [:error :type]))))

(deftest the-invalid-redaction-refusal-is-neutral-and-says-what-failed
  (a/decorate! (decorator/each-tool (decorator/redact-output [[:token]] 0)))
  (let [{:keys [message data]} (:error (call "t_account" {}))]
    (is (str/includes? message "wire form"))
    (is (= {:token ["should be a string"]} (:problems data)))))

(def ^:private leaky-request
  "The ex-data an HTTP client attaches to a failure: the request, with its token."
  {:request {:headers {:authorization "Bearer s3cret"}} :status 401})

(defn- reg-leaky! []
  (a/reg-tool :t/login {:description "Login" :read-only? true
                        :input [:map [:mode :string]]
                        :handler (fn [{:keys [mode]} _]
                                   (case mode
                                     "throw"  (throw (ex-info "http 401" leaky-request))
                                     "later"  (future (throw (ex-info "http 401" leaky-request)))
                                     "refuse" (invoke/return-answer
                                               (answer/refusal :t/denied "no" leaky-request))
                                     {:user "ana"}))}))

(deftest redact-refusal-masks-the-data-of-every-refusal-the-tool-makes
  (reg-leaky!)
  (a/journal-calls! {:errors? true})
  (a/decorate! (decorator/each-tool (decorator/redact-refusal [[:request :headers :authorization]])))
  (let [masked {:request {:headers {:authorization "<redacted>"}} :status 401}]
    (is (= masked (get-in (call "t_login" {:mode "throw"}) [:error :data])) "a throw")
    (is (= masked (get-in (call "t_login" {:mode "refuse"}) [:error :data])) "a returned refusal")
    (let [{:keys [pending]} (call "t_login" {:mode "later"})]
      (is (= masked (get-in (poll-until-settled pending) [:error :data])) "an async failure"))
    (is (= {:ok {:user "ana"}} (call "t_login" {:mode "ok"})) "Ok passes untouched")
    (testing "the journal keeps only the masked data"
      (let [errors (keep :error (:entries (:ok (a/rpc {:op :journal :since 0}))))]
        (is (= 3 (count errors)))
        (is (not (str/includes? (pr-str errors) "s3cret")))))))

(deftest without-redact-refusal-the-data-is-kept
  (reg-leaky!)
  (is (= leaky-request (get-in (call "t_login" {:mode "throw"}) [:error :data]))
      "refusal redaction is opt in"))

(deftest redact-refusal-leaves-runtime-refusals-alone
  (reg-leaky!)
  (a/decorate! (decorator/each-tool (decorator/redact-refusal [[]])))
  (is (= :invalid-args (get-in (call "t_login" {}) [:error :type])))
  (is (map? (get-in (call "t_login" {}) [:error :data]))
      "the runtime's own argument diagnosis is not the tool's data")
  (is (= "<redacted>" (get-in (call "t_login" {:mode "throw"}) [:error :data]))
      "the empty path replaces the whole data"))

(deftest a-refusal-path-that-stops-at-a-string-or-a-set-masks-it-and-never-hangs
  (a/reg-tool :t/cookie {:description "Cookie" :read-only? true
                         :input [:map [:mode :string]]
                         :handler (fn [{:keys [mode]} _]
                                    (let [e (ex-info "denied" {:set-cookie "sid=s3cret" :tags #{"s3cret"}})]
                                      (if (= mode "later") (future (throw e)) (throw e))))})
  (a/journal-calls! {:errors? true})
  (a/decorate! (decorator/each-tool (decorator/redact-refusal [[:set-cookie 0] [:tags 0]])))
  (let [masked {:set-cookie "<redacted>" :tags "<redacted>"}]
    (is (= masked (get-in (call "t_cookie" {:mode "now"}) [:error :data]))
        "a string where a vector of cookies was expected, and a set, are masked whole")
    (let [{:keys [pending]} (call "t_cookie" {:mode "later"})
          settled (poll-until-settled pending)]
      (is (= :tool-threw (get-in settled [:error :type])) "the async call settles, never stays Pending")
      (is (= masked (get-in settled [:error :data])) "and its data is masked the same way")
      (let [entries (:entries (:ok (a/rpc {:op :journal :since 0})))]
        (is (some #(and (= :autonomous/settled (:kind %)) (= pending (:pending %))) entries)
            "its settled entry is written")
        (is (not (str/includes? (pr-str entries) "s3cret")) "and the journal never holds the secret")))))

(deftest a-refusal-projection-that-throws-drops-the-data-and-still-settles
  (let [boom (fn [_ a] (if (answer/refused? a) (throw (ex-info "projection broke" {})) a))]
    (is (= {:error {:type :t/x :message "m"}}
           (invoke/settle (invoke/return-answer (answer/refusal :t/x "m" {:token "s3cret"}))
                          answer/ok (partial boom nil)))
        "direct: the refusal survives without the data its projection could not make safe")
    (let [{:keys [pending]} (invoke/settle (future (throw (ex-info "late" {:token "s3cret"})))
                                           answer/ok (partial boom nil))]
      (is (= {:error {:type :tool-threw :message "late"}} (poll-until-settled pending))
          "async: the same, and the call settles"))))

(deftest a-future-of-a-marked-refusal-settles-as-that-refusal-redacted
  (a/reg-tool :t/later-refusal {:description "Async refusal" :read-only? true
                                :handler (fn [_ _] (future (invoke/return-answer
                                                            (answer/refusal :t/denied "no" {:token "s3cret"}))))})
  (a/decorate! (decorator/each-tool (decorator/redact-refusal [[:token]])))
  (let [{:keys [pending]} (call "t_later-refusal" {})]
    (is (= {:error {:type :t/denied :message "no" :data {:token "<redacted>"}}}
           (poll-until-settled pending))
        "never {:ok {:error ...}}, and the refusal projection applies")))

(deftest require-scope-through-a-decorated-runtime
  (a/decorate! (decorator/each-tool (decorator/require-scope :audit)))
  (is (= :forbidden (get-in (call "t_echo" {:text "x"}) [:error :type])))
  (is (= {:ok {:text "x"}} (a/rpc {:op :call :tool "t_echo" :args {:text "x"} :scopes [:write :audit]})))
  (is (empty? (:tools (:ok (a/rpc (merge rw {:op :manifest})))))))

(deftest prefix-names-through-a-decorated-runtime
  (a/decorate! (decorator/prefix-names "app_"))
  (is (= {:ok {:text "x"}} (call "app_t_echo" {:text "x"})))
  (is (= :unknown-tool (get-in (call "t_echo" {:text "x"}) [:error :type]))))

(deftest a-decorator-that-breaks-the-surface-is-refused
  (let [before (registry/surface-value)]
    (is (thrown? clojure.lang.ExceptionInfo (a/decorate! #(dissoc % :tools))))
    (is (thrown? clojure.lang.ExceptionInfo (a/decorate! (decorator/each-tool #(assoc % :name "x")))))
    (is (= before (registry/surface-value)) "a refusal changes nothing")))

(defn- tool-names [] (set (keys (:tools (registry/surface-value)))))

(def ^:private late-spec {:description "Late" :handler (fn [_ _] :late)})

(deftest decorators-apply-to-later-registrations
  (a/decorate! (decorator/prefix-names "app_"))
  (a/reg-tool :t/late late-spec)
  (is (contains? (tool-names) "app_t_late"))
  (is (not (contains? (tool-names) "t_late")))
  (is (= {:ok :late} (call "app_t_late" {}))))

(deftest re-registering-after-a-rename-replaces-the-tool
  (a/decorate! (decorator/prefix-names "app_"))
  (a/reg-tool :t/echo {:description "Echo again" :handler (fn [_ _] :again)})
  (is (= #{"app_t_echo" "app_t_account" "app_t_later"} (tool-names)) "no unprefixed twin")
  (is (= {:ok :again} (call "app_t_echo" {}))))

(deftest a-registration-a-decorator-refuses-changes-nothing
  (a/decorate! (decorator/each-tool #(cond-> % (= :t/late (:id %)) (assoc :name "other"))))
  (let [before (registry/snapshot)]
    (is (thrown? clojure.lang.ExceptionInfo (a/reg-tool :t/late late-spec)))
    (is (= before (registry/snapshot)) "a refused registration changes nothing")))

(def ^:private admin-only (decorator/each-tool (decorator/require-scope :admin)))

(def ^:private admin {:actor "root" :scopes [:admin]})

(deftest a-tool-registered-after-require-scope-is-forbidden-without-it
  (a/decorate! admin-only)
  (a/reg-tool :t/late late-spec)
  (is (= :forbidden (get-in (call "t_late" {}) [:error :type])))
  (is (= {:ok :late} (a/rpc (merge admin {:op :call :tool "t_late" :args {}})))))

(deftest re-registering-an-id-keeps-its-scope-and-its-redaction
  (a/decorate! admin-only redacted)
  (a/reg-tool :t/account {:description "Account again" :read-only? true
                          :handler (fn [_ _] {:user "bia" :token "an0ther"})})
  (is (= :forbidden (get-in (call "t_account" {}) [:error :type])))
  (is (= {:ok {:user "bia" :token "<redacted>"}}
         (a/rpc (merge admin {:op :call :tool "t_account" :args {}})))))

(def ^:private longest-name
  "A valid tool name of exactly 64 characters: no prefix fits in front of it."
  (apply str (repeat 64 "n")))

(deftest a-name-the-prefix-breaks-refuses-the-registration-not-the-prefix
  (a/decorate! (decorator/prefix-names "app_"))
  (let [before (registry/snapshot)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (a/reg-tool :t/long (assoc late-spec :name longest-name))))
    (is (= before (registry/snapshot)) "the refused registration changes nothing")
    (is (= #{"app_t_echo" "app_t_account" "app_t_later"} (tool-names))
        "the surface keeps its prefixed names")))

(deftest unregistering-follows-the-decorators
  (a/decorate! (decorator/prefix-names "app_"))
  (a/unreg-tool :t/echo)
  (is (= #{"app_t_account" "app_t_later"} (tool-names))))
