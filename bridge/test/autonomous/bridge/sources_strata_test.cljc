(ns autonomous.bridge.sources-strata-test
  "Focused tests for the pure strata of the REPL channels and the ledger:
   eval-source parsing steps, nREPL result promotion, ledger entry
   promotion and query clauses, and the adapters' pure folds."
  (:require [autonomous.bridge.eval-source :as src]
            [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.ledger.memory]
            [autonomous.domain.journal :as journal]
            #?@(:bb [[autonomous.bridge.nrepl-wire]]
                :clj [[autonomous.bridge.ledger.datahike :as dh]
                      [autonomous.bridge.nrepl]])
            [clojure.test :refer [deftest is testing]]))

;; -----------------------------------------------------------------------------
;; eval-source

(deftest answer-shape-needs-exactly-one-variant
  (let [shaped? #'src/answer-shaped?]
    (is (shaped? {:ok nil}))
    (is (shaped? {:error {:type :x}}))
    (is (not (shaped? {:ok 1 :error {}})))
    (is (not (shaped? [:ok 1])))
    (is (not (shaped? nil)))))

(deftest excerpts-are-bounded-to-400-characters
  (let [excerpt #'src/excerpt]
    (is (= "abc" (excerpt "abc")))
    (is (= 400 (count (excerpt (apply str (repeat 500 "x"))))))
    (is (= "" (excerpt nil)))))

(deftest printed-text-is-read-or-refused-as-unreadable
  (let [read-printed #'src/read-printed]
    (is (= {:ok {:a 1}} (read-printed "{:a 1}")))
    (is (= :channel/unreadable (get-in (read-printed "#object[Foo") [:error :type])))))

(deftest the-shadow-envelope-prefers-results-then-err
  (let [shadow (fn [outer] (#'src/shadow-answer (pr-str outer) outer))]
    (is (= {:ok 1} (shadow {:results ["\"x\"" (pr-str (pr-str {:ok 1}))] :err "ignored"})))
    (is (= :channel/runtime-error (get-in (shadow {:results [] :err "boom"}) [:error :type])))
    (is (= :channel/no-runtime (get-in (shadow {:results [] :err ""}) [:error :type])))
    (is (= :channel/no-runtime (get-in (shadow "not a map") [:error :type])))))

(deftest only-clj-mode-reads-the-value-directly
  (let [answer-of #'src/answer-of
        text      (pr-str {:ok 2})]
    (is (= {:ok 2} (answer-of :clj "p" text)))
    (is (= :channel/no-runtime (get-in (answer-of :shadow "p" text) [:error :type])))
    (is (= :channel/not-autonomous (get-in (answer-of :clj "p" "\"hello\"") [:error :type])))))

(deftest runtime-views-label-by-description-agent-or-host
  (let [view #'src/runtime-view]
    (is (= "tab" (:runtime/label (view {:client-id 1 :desc "tab" :user-agent "ua"}))))
    (is (= "ua" (:runtime/label (view {:client-id 1 :user-agent "ua"}))))
    (is (= {:runtime/id 4 :runtime/kind :unknown :runtime/label "runtime 4"} (view {:client-id 4})))))

(deftest runtimes-answer-parses-ok-and-passes-refusals
  (is (= {:ok [{:runtime/id 3 :runtime/kind :browser :runtime/label "browser 3"}]}
         (src/runtimes-answer {:ok (pr-str [{:client-id 3 :host :browser}])})))
  (is (= {:error {:type :x}} (src/runtimes-answer {:error {:type :x}})))
  (is (= "jvm" (get-in src/clj-runtimes [:ok 0 :runtime/id]))))

;; -----------------------------------------------------------------------------
;; nREPL result promotion

;; nrepl-wire rides bb-mcp, which only the babashka classpath carries.
#?(:bb (deftest bb-nrepl-results-promote-to-answers
         (let [wire-answer @(resolve 'autonomous.bridge.nrepl-wire/wire-answer)]
           (is (= {:ok "1"} (wire-answer 7 {:result "1"})))
           (is (= :channel/eval-threw (get-in (wire-answer 7 {:result "x" :error? true}) [:error :type])))
           (testing "a failed connection wins over an eval error and names the port"
             (let [a (wire-answer 7 {:result "refused" :error? true :connect-failed? true})]
               (is (= :channel/connect-failed (get-in a [:error :type])))
               (is (= "cannot reach nREPL on port 7" (get-in a [:error :message])))))))
   :clj nil)

#?(:bb nil
   :clj (deftest jvm-nrepl-messages-promote-to-answers
          (let [eval-answer #'autonomous.bridge.nrepl/eval-answer]
            (is (= {:ok "2"} (eval-answer "c" [{:value "1"} {:value "2"}])))
            (is (= :channel/eval-threw (get-in (eval-answer "c" [{:ex "E" :err " boom "}]) [:error :type])))
            (is (= "boom" (get-in (eval-answer "c" [{:err " boom "}]) [:error :message])))
            (is (= "the eval produced no value" (get-in (eval-answer "c" []) [:error :message]))))))

;; -----------------------------------------------------------------------------
;; ledger

(def ^:private entry
  (ledger/->ledger-entry "app" 7 {:seq 1 :at 100 :kind :k :actor :a :tool "t" :outcome :ok :x 1}))

(deftest optional-fields-promote-only-when-present
  (let [promoted (fn [e] (select-keys (ledger/->ledger-entry "app" 7 (merge {:seq 1 :at 1 :kind :k} e))
                                      [:entry/actor :entry/tool :entry/outcome]))]
    (is (= {:entry/actor ":a" :entry/tool "t" :entry/outcome :ok}
           (promoted {:actor :a :tool 't :outcome :ok})))
    (is (= {} (promoted {:actor nil :outcome false})))))

(deftest views-carry-only-present-optional-fields
  (let [bare (ledger/->ledger-entry "app" 7 {:seq 2 :at 200 :kind :k})]
    (is (= {:source {:target "app" :runtime "7"} :seq 2 :at 200 :kind :k} (ledger/->view bare)))
    (is (= {:actor ":a" :tool "t" :outcome :ok :data {:x 1}}
           (select-keys (ledger/->view entry) [:actor :tool :outcome :data])))))

(deftest a-journaled-answer-reaches-the-view-data
  (testing "CallDetail :answers? carries the Ok value into the ledger view"
    (let [e (journal/call-entry "t" {} {:actor "a"} {:ok {:id 7 :title "milk"}} 3 {:answers? true})
          view (ledger/->view (ledger/->ledger-entry "app" 7 (assoc e :seq 1 :at 100)))]
      (is (= {:id 7 :title "milk"} (get-in view [:data :answer])))
      (is (= 3 (get-in view [:data :ms])))))
  (testing "off by default, the view carries no answer"
    (let [e (journal/call-entry "t" {} {:actor "a"} {:ok {:id 7}} 1 nil)
          view (ledger/->view (ledger/->ledger-entry "app" 7 (assoc e :seq 1 :at 100)))]
      (is (not (contains? (:data view) :answer))))))

(deftest every-query-clause-filters-on-its-own
  (testing "absent clauses match"
    (is (ledger/matches? {} entry))
    (is (ledger/matches? {:actor nil :tool nil} entry)))
  (testing "each clause can reject"
    (doseq [q [{:actor "b"} {:kind :other} {:tool "u"} {:target "web"} {:since 101} {:until 100}]]
      (is (not (ledger/matches? q entry)) (pr-str q))))
  (testing "range bounds are half-open"
    (is (ledger/matches? {:since 100 :until 101} entry))))

(deftest the-memory-fold-upserts-and-keeps-the-newest
  (let [upsert #'autonomous.bridge.ledger.memory/upsert
        e      (fn [at] {:entry/key (str at) :entry/at at})]
    (is (= #{"2" "3"} (set (keys (upsert {} [(e 1) (e 2) (e 3)] 2)))))
    (is (= {"1" (assoc (e 1) :v 2)} (upsert {"1" (e 1)} [(assoc (e 1) :v 2)] 5)))))

#?(:bb nil
   :clj (deftest datahike-query-forms-bind-every-given-clause
          (let [[form args] (dh/query-form {:actor "a" :until 9})]
            (is (= ["a" 9] (vec args)))
            (is (some #{'?actor} form))
            (is (some #{['(< ?at ?until)]} form))
            (is (empty? (second (dh/query-form {}))) "no clause, no input")
            (is (= ['?e :entry/tool '?tool] (some #{['?e :entry/tool '?tool]} (first (dh/query-form {:tool "t"}))))))))
