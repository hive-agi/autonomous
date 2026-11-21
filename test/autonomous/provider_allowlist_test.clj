(ns autonomous.provider-allowlist-test
  "provider-allowlist end to end through rpc: an allowed provider runs at
   once, past the approval gate; any other value, however it is spelled,
   waits for a human (or is refused when there is no gate); scopes come
   first in either composition order."
  (:require [autonomous.approval :as approval]
            [autonomous.contract :as contract]
            [autonomous.core :as a]
            [autonomous.decorator :as decorator]
            [autonomous.runtime.registry :as registry]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(def providers
  "As the payment service resolves them: the real bank and its simulator."
  [{:code "756" :name "Sicoob"} {:code "sicoob-sim" :name "SicoobSim"}])

(def ^:private allowlist
  (decorator/provider-allowlist {:path [:provider] :providers providers :allowed ["sicoob-sim"]}))

(def ^:private runs (atom []))

(defn- fixture-app! []
  (reset! runs [])
  (a/set-app-info! {:name "allowlist" :description "test app"})
  (a/reg-tool :pix {:description "Create a Pix charge"
                    :input [:map [:provider :any] [:amount :int]]
                    :handler (fn [args _] (swap! runs conj (:provider args)) {:charged (:amount args)})}))

(use-fixtures :each (fn [t] (registry/with-instance (registry/make-runtime) #(do (fixture-app!) (t)))))

(def ^:private rw {:actor "persona-1" :scopes [:read :write]})

(defn- call
  ([args] (call rw args))
  ([grant args] (a/rpc (merge grant {:op :call :tool "pix" :args args}))))

(defn- poll-until-settled [id]
  (loop [i 0]
    (let [r (a/rpc {:op :poll :id id})]
      (if (and (:pending r) (< i 400)) (do (Thread/sleep 5) (recur (inc i))) r))))

(defn- settled [r] (if (:pending r) (poll-until-settled (:pending r)) r))

(defn- gated! [verdicts]
  (let [asker (approval/queue-asker verdicts)]
    (a/decorate! (a/each-tool (comp allowlist (decorator/require-approval {:asker asker :timeout-ms 1000}))))
    asker))

(deftest an-allowed-provider-skips-the-human
  (let [asker (gated! [])]
    (doseq [v ["sicoob-sim" "SicoobSim" "sicoobsim" "SICOOBSIM"]]
      (is (= {:ok {:charged 1}} (call {:provider v :amount 1})) v))
    (is (empty? @(:requests asker)) "no human was asked")
    (is (= 4 (count @runs)))))

(deftest any-other-value-goes-to-the-human
  (let [values ["756" "Sicoob" "sicoob" "SICOOB" 756 "0756" " sicoob-sim" "sicoob-sim " "sicoob-sim\n"
                "SICOOB-SIM" "Sicoob-Sim" "sicoob_sim" "ſicoobsim" "sicoobsım" "SicoobSim\u200b" "" nil]
        asker  (gated! (repeat (count values) {:decision :deny :approver "bob"}))]
    (doseq [v values]
      (is (= :approval-denied (get-in (settled (call {:provider v :amount 1})) [:error :type])) (pr-str v)))
    (is (= (count values) (count @(:requests asker))) "every one was put to the human")
    (is (empty? @runs) "none ran")))

(deftest the-real-bank-runs-once-a-human-approves
  (let [asker (gated! [{:decision :approve :approver "ana"}])]
    (is (= {:ok {:charged 5}} (settled (call {:provider "756" :amount 5}))))
    (is (= ["756"] @runs))
    (is (= "756" (get-in (first @(:requests asker)) [:args :provider])))))

(deftest without-an-approval-gate-anything-else-is-refused
  (a/decorate! (a/each-tool allowlist))
  (let [r (call {:provider "756" :amount 1})]
    (is (= :provider-not-allowed (get-in r [:error :type])))
    (is (= {:path [:provider] :allowed ["SicoobSim" "sicoob-sim"]} (get-in r [:error :data]))))
  (is (= :provider-not-allowed (get-in (call {:amount 1 :provider nil}) [:error :type])))
  (is (= {:ok {:charged 2}} (call {:provider "sicoob-sim" :amount 2})))
  (is (= ["sicoob-sim"] @runs)))

(deftest a-refusal-is-journaled
  (a/journal-calls! {:errors? true})
  (a/decorate! (a/each-tool allowlist))
  (call {:provider "Sicoob" :amount 1})
  (is (= :provider-not-allowed
         (:outcome (last (filter #(= :autonomous/call (:kind %)) (:entries (:ok (a/rpc {:op :journal})))))))))

(deftest a-key-a-json-decoder-folds-onto-the-path-is-refused
  (a/reg-tool :charge {:description "Create a charge, the body sent whole"
                       :input [:map [:body [:map [:provider :any]]]]
                       :handler (fn [args _] (swap! runs conj (:body args)) {:sent (:body args)})})
  (let [asker (approval/queue-asker (repeat 10 {:decision :approve :approver "ana"}))
        nested (decorator/provider-allowlist {:path [:body :provider] :providers providers
                                              :allowed ["sicoob-sim"]})]
    (a/decorate! (a/each-tool (comp nested (decorator/require-approval {:asker asker :timeout-ms 1000}))))
    (doseq [sibling [:Provider "Provider" :PROVIDER "PROVIDER" "provider" (keyword "PROVİDER")
                     "pROVIDER"]
            :let [body (assoc {:provider "sicoob-sim"} sibling "756")]
            :when (not= sibling :provider)]
      (let [r (a/rpc (merge rw {:op :call :tool "charge" :args {:body body}}))]
        (is (= :provider-ambiguous (get-in r [:error :type])) (pr-str sibling))))
    (is (empty? @(:requests asker)) "no human was shown one provider while the service would read another")
    (is (empty? @runs) "nothing was sent"))
  (testing "the Kelvin sign and the long s fold like k and s, as Go folds them"
    (is (decorator/ambiguous-path? [:bank-key] {:bank-key "sicoob-sim" (keyword "ban\u212a-key") "756"}))
    (is (decorator/ambiguous-path? [:bank-key] {:bank-key "sicoob-sim" "BAN\u212a-\u212aEY" "756"}))
    (is (decorator/ambiguous-path? [:sim] {:sim "sicoob-sim" "\u017fIM" "756"})))
  (testing "a folding key at an outer level is refused too"
    (is (decorator/ambiguous-path? [:body :provider] {:body {:provider "sicoob-sim"} "BODY" {:provider "756"}})))
  (testing "unrelated keys are fine"
    (is (not (decorator/ambiguous-path? [:body :provider] {:body {:provider "sicoob-sim" :amount 1 :providers 2}})))))

(deftest go-folds-the-kelvin-sign-and-the-long-s
  (is (= (decorator/json-fold "k") (decorator/json-fold "\u212a")))
  (is (= (decorator/json-fold "s") (decorator/json-fold "\u017f")))
  (is (= (decorator/json-fold "provider") (decorator/json-fold "PROVIDER")))
  (is (not= (decorator/json-fold "provider") (decorator/json-fold "provider_"))))

(deftest a-second-allowlist-cannot-widen-the-first
  (a/decorate! (a/each-tool allowlist))
  (let [wider (decorator/provider-allowlist {:path [:provider] :providers providers
                                             :allowed ["756" "sicoob-sim"]})]
    (is (thrown? clojure.lang.ExceptionInfo (a/decorate! (a/each-tool wider)))
        "the runtime refuses the decoration and keeps the first")
    (is (= :provider-not-allowed (get-in (call {:provider "756" :amount 1}) [:error :type])))
    (is (empty? @runs) "756 never ran"))
  (testing "the same allowlist again is harmless"
    (a/decorate! (a/each-tool allowlist))
    (is (= {:ok {:charged 1}} (call {:provider "sicoob-sim" :amount 1})))))

(deftest providers-the-service-could-confuse-are-refused
  (doseq [ps [[{:code "sicoob-sim" :name "Sicoob"} {:code "756" :name "Sicoob"}]
              [{:code "sicoob-sim" :name "SICOOB"} {:code "756" :name "sicoob"}]
              [{:code "sicoob-sim" :name "Sicoob\u212a"} {:code "756" :name "SicoobK"}]
              [{:code "sicoob-sim" :name "756"} {:code "756" :name "Sicoob"}]
              [{:code "756" :name "SICOOB-SIM"} {:code "sicoob-sim" :name "SicoobSim"}]
              [{:code "sicoob-sim"} {:code "sicoob-sim" :name "Other"}]]]
    (is (= :invalid-allowlist
           (try (decorator/provider-allowlist {:path [:provider] :providers ps :allowed ["sicoob-sim"]}) nil
                (catch clojure.lang.ExceptionInfo e (:autonomous/error (ex-data e)))))
        (pr-str ps)))
  (is (fn? (decorator/provider-allowlist {:path [:provider] :providers providers :allowed ["sicoob-sim"]}))
      "the real configuration is accepted"))

(deftest scope-is-checked-first-in-either-order
  (doseq [[label d] [["allowlist outside" (comp allowlist (decorator/require-scope :payments))]
                     ["allowlist inside" (comp (decorator/require-scope :payments) allowlist)]]]
    (testing label
      (reset! runs [])
      (a/decorate! (a/each-tool d))
      (is (= :forbidden (get-in (call {:provider "sicoob-sim" :amount 1}) [:error :type])))
      (is (= :forbidden (get-in (call {:provider "756" :amount 1}) [:error :type])))
      (is (empty? @runs))
      (is (= {:ok {:charged 1}} (call (update rw :scopes conj :payments) {:provider "sicoob-sim" :amount 1}))))))

(deftest the-allowlist-is-in-the-decorator-law
  (let [d (:provider-allowlist contract/tool-decorators)
        t {:name "t" :input [:map [:provider :string]] :output [:map]}]
    (is (fn? d))
    (is (contains? (d t) :autonomous.decorator/allowlist))
    (is (= (select-keys t [:input :output]) (select-keys (d t) [:input :output])))))

(deftest bad-options-throw-at-construction
  (doseq [opts [{:path [] :providers providers :allowed ["sicoob-sim"]}
                {:path [:provider] :providers [] :allowed ["sicoob-sim"]}
                {:path [:provider] :providers providers :allowed []}
                {:path [:provider] :providers providers :allowed ["card-sim"]}
                {:path [:provider] :providers providers :allowed "sicoob-sim"}
                {:path :provider :providers providers :allowed ["sicoob-sim"]}]]
    (is (= :invalid-allowlist (try (decorator/provider-allowlist opts) nil
                                   (catch clojure.lang.ExceptionInfo e (:autonomous/error (ex-data e)))))
        (pr-str opts))))

(defn- ascii-lower [s] (apply str (map #(if (<= (int \A) (int %) (int \Z)) (char (+ 32 (int %))) %) s)))

(def ^:private spelling
  "Strings near the sim's code and name: case flips, padding, a code point
   swapped, plus arbitrary strings."
  (gen/one-of [gen/string
               gen/string-ascii
               (gen/let [base (gen/elements ["sicoob-sim" "SicoobSim" "756" "Sicoob"])
                         flips (gen/vector gen/boolean (count base))
                         pad (gen/elements ["" " " "\t" "\u00a0" "\u200b" "%20"])]
                 (str pad (apply str (map #(if %2 (str/upper-case (str %1)) (str %1)) base flips)) pad))]))

(deftest only-the-service-spellings-of-an-allowed-provider-are-allowed
  (let [r (tc/quick-check 500
            (prop/for-all [s spelling]
              (= (= "sicoob-sim" (decorator/resolve-provider providers s))
                 (boolean (or (= s "sicoob-sim")
                              (and (re-matches #"[\x20-\x7E]+" s) (= "sicoobsim" (ascii-lower s))))))))]
    (is (:pass? r) (pr-str (:shrunk r)))))
