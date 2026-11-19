(ns autonomous.asyncapi-core-test
  "Pure strata: NATS subject matching, the bounded tap, and opt-in
   derivation against funeraria's nats-events spec (a read-only copy under
   test/fixtures/asyncapi)."
  (:require [autonomous.asyncapi.derive :as derive]
            [autonomous.asyncapi.doc :as doc]
            [autonomous.asyncapi.subject :as subject]
            [autonomous.asyncapi.tap :as tap]
            [autonomous.domain.answer :as answer]
            [autonomous.openapi.load :as load]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]))

(def bundle (:ok (load/load-bundle "test/fixtures/asyncapi/nats-events.yaml")))

;; -----------------------------------------------------------------------------
;; Subjects: `*` is exactly one token, `>` is the rest

(deftest star-matches-exactly-one-token
  (is (subject/matches? "sisf.payment.*.entry" "sisf.payment.monero.entry"))
  (is (subject/matches? "sisf.payment.*.entry" "sisf.payment.pix.entry"))
  (is (not (subject/matches? "sisf.payment.*.entry" "sisf.payment.entry")))
  (is (not (subject/matches? "sisf.payment.*.entry" "sisf.payment.a.b.entry")))
  (is (not (subject/matches? "sisf.payment.*" "sisf.payment")))
  (is (subject/matches? "*" "crm"))
  (is (not (subject/matches? "*" "crm.phone"))))

(deftest tail-wildcard-matches-one-or-more-tokens
  (is (subject/matches? "sisf.payment.>" "sisf.payment.monero.entry"))
  (is (subject/matches? "sisf.payment.>" "sisf.payment.monero.settled"))
  (is (subject/matches? ">" "anything.at.all"))
  (is (not (subject/matches? "sisf.payment.>" "sisf.payment"))
      "`>` is one token or more, as NATS has it: it is not a zero-width tail")
  (is (not (subject/matches? "sisf.payment.>" "sisf.payments.x"))))

(deftest a-literal-filter-matches-only-itself
  (is (subject/matches? "crm.phone.state.v1" "crm.phone.state.v1"))
  (is (not (subject/matches? "crm.phone.state.v1" "crm.phone.state.v2")))
  (is (subject/literal? "crm.phone.state.v1"))
  (is (not (subject/literal? "crm.phone.*.v1"))))

(deftest unusable-filters-are-named
  (is (= [:blank] (subject/problems "")))
  (is (= [:blank] (subject/problems nil)))
  (is (= [:empty-token] (subject/problems "a..b")))
  (is (= [:empty-token] (subject/problems "a.b.")))
  (is (= [:wildcard-rest-not-last] (subject/problems "a.>.b")))
  (is (subject/valid? "a.*.c"))
  (is (= :asyncapi/bad-subject (get-in (subject/check "a..b") [:error :type]))))

;; -----------------------------------------------------------------------------
;; The tap: bounded, cursored, flagging rather than dropping

(defn- fill [t n]
  (reduce (fn [t i] (first (tap/admit t {:subject "s.x" :payload {:i i} :at i})))
          t (range 1 (inc n))))

(deftest the-ring-is-bounded-and-counts-what-fell-out
  (let [t (fill (tap/tap "s.x" 2) 5)]
    (is (= [4 5] (mapv :seq (:messages t))))
    (is (= 5 (:cursor t)))
    (is (= 3 (:dropped t)))
    (is (= {:messages (mapv #(get (into {} (map (juxt :seq identity) (:messages t))) %) [4 5])
            :cursor 5 :dropped 3}
           (tap/since t 0)))))

(deftest since-answers-only-what-is-after-the-cursor
  (let [t (fill (tap/tap "s.x" 10) 4)]
    (is (= [3 4] (mapv :seq (:messages (tap/since t 2)))))
    (is (= 4 (:cursor (tap/since t 2))))
    (is (= [] (:messages (tap/since t 4))))
    (is (= 4 (:cursor (tap/since t 4))) "an idle read does not move the cursor")))

(deftest a-page-beyond-the-limit-reports-the-rest-as-dropped
  (let [t    (fill (tap/tap "s.x" 10) 5)
        page (tap/since t 0 2)]
    (is (= [1 2] (mapv :seq (:messages page))))
    (is (= 2 (:cursor page)))
    (is (= 3 (:dropped page)) "3 messages were skipped by the limit")))

(deftest a-readmit-of-the-same-seq-is-not-admitted
  (let [t (fill (tap/tap "s.x" 5) 1)
        [t2 admitted?] (tap/admit t {:subject "s.x" :payload :dup :at 9 :seq 1})]
    (is (false? admitted?))
    (is (= 1 (count (:messages t2))))
    (is (= 0 (:dropped t2)) "a redelivery must not inflate dropped")))

(deftest conforming-payloads-pass-and-non-conforming-ones-are-kept-flagged
  (let [validate (m/explainer [:map [:ok :int]])
        t        (first (tap/admit (tap/tap "s.x" 5 validate)
                                   {:subject "s.x" :payload {:ok 1} :at 1}))
        t        (first (tap/admit t {:subject "s.x" :payload {:ok "no"} :at 2}))]
    (is (true? (:conforms? (first (:messages t)))))
    (is (false? (:conforms? (second (:messages t)))))
    (is (some? (:problems (second (:messages t)))))
    (is (= 2 (count (:messages t))) "the bad payload is kept, not dropped")
    (is (not (tap/conforming? t)))))

;; -----------------------------------------------------------------------------
;; Opt-in derivation

(deftest only-the-exposed-subjects-are-derived
  (let [ts (:ok (derive/taps bundle {:expose {"user.events.created" {:scope :events/read}
                                              "sisf.payment.*.entry" {:scope :payments/read}}}))]
    (is (= ["asyncapi://user.events.created" "asyncapi://sisf.payment.*.entry"]
           (mapv :uri ts)))
    (is (= [#{:events/read} #{:payments/read}] (mapv :scopes ts)))))

(deftest a-declared-subject-carries-its-message-schema-and-description
  (let [[t] (:ok (derive/taps bundle {:expose {"crm.phone.state.v1" {:scope :crm/read}}}))]
    (is (some? (:schema t)) "the payload schema comes from components.messages")
    (is (= 50 (:capacity t)) "default ring")
    (is (= 100 (:limit t)) "default page")
    (is (re-find #"crm.phone.state.v1" (:description t)))))

(deftest the-document-addresses-are-the-2-and-3-x-ones
  (is (= #{"user.events.created" "user.events.updated" "crm.phone.state.v1"
           "user.events.password_changed" "user.events.password_reset_requested"
           "reference.cobrador.sector.assigned"}
         (set (doc/addresses bundle))))
  (is (= :map (get (doc/schema-of bundle "crm.phone.state.v1") 0))
      "a payload schema resolves through the component message")
  (is (= #{:Phone :From :To :At}
         (set (map first (drop 1 (doc/schema-of bundle "crm.phone.state.v1")))))
      "and carries the message's own required properties"))

(deftest a-typo-an-empty-list-or-a-bad-filter-is-refused-at-derivation
  (testing "a literal subject the document does not declare"
    (let [e (derive/taps bundle {:expose {"user.event.created" {}}})]
      (is (= :asyncapi/unknown-subject (get-in e [:error :type])))
      (is (contains? (set (get-in e [:error :data :known])) "user.events.created"))))
  (testing "an empty expose"
    (is (= :asyncapi/nothing-exposed (get-in (derive/taps bundle {:expose {}}) [:error :type]))))
  (testing "no expose at all"
    (is (= :asyncapi/bad-expose (get-in (derive/taps bundle {}) [:error :type]))))
  (testing "a filter NATS cannot use"
    (is (= :asyncapi/bad-subject (get-in (derive/taps bundle {:expose {"a..b" {}}}) [:error :type]))))
  (testing "options that are not a map"
    (is (= :asyncapi/bad-expose
           (get-in (derive/taps bundle {:expose {"user.events.created" :read}}) [:error :type])))))

(deftest a-wildcard-the-document-has-not-caught-up-on-is-allowed
  (let [ts (:ok (derive/taps bundle {:expose {"sisf.payment.*.entry" {}}}))]
    (is (= ["sisf.payment.*.entry"] (mapv :subject ts)))
    (is (nil? (:schema (first ts))) "no schema to validate that subject against yet")
    (is (subject/valid? (:subject (first ts))))))

(deftest capacity-and-limit-are-taken-from-the-author-and-sanitized
  (let [[t] (:ok (derive/taps bundle {:expose {"user.events.created" {:capacity 5 :limit 7}}}))]
    (is (= 5 (:capacity t)))
    (is (= 7 (:limit t))))
  (let [[t] (:ok (derive/taps bundle {:expose {"user.events.created" {:capacity 0 :limit -3}}}))]
    (is (= tap/default-capacity (:capacity t)))
    (is (= 100 (:limit t)))))
