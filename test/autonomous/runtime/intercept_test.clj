(ns autonomous.runtime.intercept-test
  "The call chain and decorators wired into a runtime, end to end through
   rpc: intercept!, the use! shim, output redaction, decorated surfaces."
  (:require [autonomous.core :as a]
            [autonomous.decorator :as decorator]
            [autonomous.runtime.registry :as registry]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]))

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

(deftest a-redaction-that-breaks-the-output-schema-is-refused
  (a/decorate! (decorator/each-tool (decorator/redact-output [[:token]] 0)))
  (is (= :output/invalid-redaction (get-in (call "t_account" {}) [:error :type]))))

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

(deftest unregistering-follows-the-decorators
  (a/decorate! (decorator/prefix-names "app_"))
  (a/unreg-tool :t/echo)
  (is (= #{"app_t_account" "app_t_later"} (tool-names))))
