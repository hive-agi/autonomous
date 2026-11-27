(ns autonomous.domain.primitives-trifecta-test
  "Trifectas over the small pure rules everything else stands on: naming,
   scope algebra, leases, the journal ring, wire coercion, answer outcomes."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.caller :as caller]
            [autonomous.domain.journal :as journal]
            [autonomous.domain.lease :as lease]
            [autonomous.domain.naming :as naming]
            [autonomous.domain.schema :as s]
            [autonomous.domain.scope :as scope]
            [autonomous.domain.wire :as wire]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [malli.core :as m]
            [hive-schemas.test :refer [deftrifecta-from-schema]]
            [hive-test.trifecta :refer [deftrifecta]]))

;; Originals captured before any mutant rebinds the vars.
(def ^:private tool-name* naming/tool-name)
(def ^:private allowed?* scope/allowed?)
(def ^:private acquire* lease/acquire)

;; -----------------------------------------------------------------------------
;; naming

(deftrifecta tool-name-trifecta
  autonomous.domain.naming/tool-name
  {:golden-path "test/golden/domain/tool-name.edn"
   :cases {:namespaced :todo/add
           :dotted     :app.todos/add-item
           :string     "plain"
           :empty      ""
           :spaces     "add a todo"
           :unicode    "ação/é"
           :long       (keyword (apply str (repeat 80 "a")))}
   :gen (gen/one-of [gen/keyword-ns gen/keyword gen/string])
   :pred naming/valid-tool-name?
   :num-tests 300
   :mutations [["keeps the colon" str]
               ["no length cut" (fn [id] (let [s (if (keyword? id) (subs (str id) 1) (str id))]
                                           (apply str (map #(if (= \/ %) \_ %) s))))]
               ["slash survives" (fn [id] (subs (tool-name* (str id)) 0))]
               ["empty stays empty" (fn [id] (if (= "" (str id)) "" (tool-name* id)))]]})

;; -----------------------------------------------------------------------------
;; scope algebra

(deftrifecta allowed-trifecta
  autonomous.domain.scope/allowed?
  {:golden-path "test/golden/domain/allowed.edn"
   :apply? true
   :cases {:read-covers-read    [#{:read} #{:read}]
           :read-not-write      [#{:write} #{:read}]
           :admin-covers-all    [#{:write :billing} #{:admin}]
           :needs-every-scope   [#{:read :write} #{:write}]
           :nothing-required    [#{} #{}]
           :app-defined-scope   [#{:billing} #{:billing :read}]}
   :gen (gen/tuple (gen/set (gen/elements [:read :write :admin :billing]))
                   (gen/set (gen/elements [:read :write :admin :billing])))
   :pred boolean?
   :mutations [["always allows" (constantly true)]
               ["admin is not special" (fn [r g] (every? #(contains? g %) r))]
               ["any one scope suffices" (fn [r g] (or (empty? r) (boolean (some g r))))]]})

;; -----------------------------------------------------------------------------
;; leases

(deftrifecta acquire-trifecta
  autonomous.domain.lease/acquire
  {:golden-path "test/golden/domain/lease-acquire.edn"
   :apply? true
   :cases {:free              [nil "a" 1000 0]
           :other-live        [{:actor "b" :until 500} "a" 1000 100]
           :other-expired     [{:actor "b" :until 50} "a" 1000 100]
           :own-renewal       [{:actor "a" :until 500} "a" 1000 100]
           :default-ttl       [nil "a" nil 10]}
   :gen (gen/tuple (gen/one-of [(gen/return nil)
                                (gen/hash-map :actor (gen/elements ["a" "b"])
                                              :until gen/nat)])
                   (gen/elements ["a" "b"])
                   (gen/one-of [(gen/return nil) gen/s-pos-int])
                   gen/nat)
   :pred (fn [[verdict l]] (and (#{:granted :refused} verdict) (map? l)))
   :mutations [["always grants" (fn [_ a ttl now] [:granted {:actor a :until (+ now (or ttl 60000))}])]
               ["refuses own renewal" (fn [l a ttl now]
                                        (if (and l (= a (:actor l)) (> (:until l) now))
                                          [:refused l]
                                          (acquire* l a ttl now)))]
               ["expired still blocks" (fn [l a ttl now]
                                         (if (and l (not= a (:actor l))) [:refused l] (acquire* l a ttl now)))]]})

(deftrifecta blocks-trifecta
  autonomous.domain.lease/blocks?
  {:golden-path "test/golden/domain/lease-blocks.edn"
   :apply? true
   :cases {:no-lease        [nil {:read-only? false} {:actor "a"} 0]
           :other-writes    [{:actor "b" :until 100} {:read-only? false} {:actor "a"} 10]
           :other-reads     [{:actor "b" :until 100} {:read-only? true} {:actor "a"} 10]
           :holder-writes   [{:actor "a" :until 100} {:read-only? false} {:actor "a"} 10]
           :expired         [{:actor "b" :until 100} {:read-only? false} {:actor "a"} 100]}
   :gen (gen/tuple (gen/one-of [(gen/return nil)
                                (gen/hash-map :actor (gen/elements ["a" "b"]) :until gen/nat)])
                   (gen/hash-map :read-only? gen/boolean)
                   (gen/hash-map :actor (gen/elements ["a" "b"]))
                   gen/nat)
   :pred boolean?
   :mutations [["never blocks" (constantly false)]
               ["blocks reads too" (fn [l _ g now] (and (lease/live? l now) (not= (:actor l) (:actor g))))]
               ["blocks the holder" (fn [l t _ now] (and (lease/live? l now) (not (:read-only? t))))]]})

;; -----------------------------------------------------------------------------
;; journal ring

(def ^:private NewEntry [:map [:kind :keyword] [:actor {:optional true} :string]])

(deftrifecta-from-schema journal-append-trifecta
  autonomous.domain.journal/append
  {:in [:cat s/Journal NewEntry s/Millis [:int {:min 1 :max 4}]]
   :out s/Journal
   :rel (fn [[j e at cap] out]
          (and (= (:seq out) (inc (:seq j)))
               (<= (count (:entries out)) cap)
               (= (assoc e :seq (:seq out) :at at) (peek (:entries out)))))
   :golden-path "test/golden/domain/journal-append.edn"
   :num-tests 200
   :seed 11
   :n-cases 6})

(deftrifecta journal-since-trifecta
  autonomous.domain.journal/since
  {:golden-path "test/golden/domain/journal-since.edn"
   :apply? true
   :cases {:from-start [{:seq 3 :entries [{:seq 1} {:seq 2} {:seq 3}]} nil nil]
           :resume     [{:seq 3 :entries [{:seq 1} {:seq 2} {:seq 3}]} 1 nil]
           :limited    [{:seq 3 :entries [{:seq 1} {:seq 2} {:seq 3}]} 0 2]
           :caught-up  [{:seq 3 :entries [{:seq 1} {:seq 2} {:seq 3}]} 3 nil]}
   :gen (gen/tuple (gen/return {:seq 3 :entries [{:seq 1} {:seq 2} {:seq 3}]})
                   (gen/one-of [(gen/return nil) (gen/choose 0 4)])
                   (gen/one-of [(gen/return nil) (gen/choose 1 4)]))
   :pred (fn [{:keys [entries]}] (apply < 0 (map :seq entries)))
   :mutations [["ignores since" (fn [j _ l] (journal/since j nil l))]
               ["ignores limit" (fn [j s _] {:seq (:seq j) :entries (filterv #(> (:seq %) (or s 0)) (:entries j))})]]})

(def ^:private call-entry* journal/call-entry)

(def ^:private refused {:error {:type :invalid-args :message "bad" :data {:text ["missing required key"]}}})

(deftrifecta call-entry-trifecta
  autonomous.domain.journal/call-entry
  {:golden-path "test/golden/domain/call-entry.edn"
   :apply? true
   :cases {:no-detail      ["todo_add" {:text "x"} {:actor "a" :session "s"} {:ok 1} 3 nil]
           :session-only   ["todo_add" {:text "x"} {:actor "a" :session "s"} {:ok 1} 3 {}]
           :args-redacted  ["todo_add" {:text "x" :token "t"} {:actor "a"} {:ok 1} 3
                            {:args? true :redact [[:token] [:absent]]}]
           :nested-redact  ["pay" {:card {:pan "4111" :name "n"}} {:actor "a"} {:ok nil} 0
                            {:args? true :redact [[:card :pan]]}]
           :no-args        ["ping" nil {:actor "a"} {:ok nil} 0 {:args? true}]
           :refusal-kept   ["todo_add" {} {:actor "a"} refused 1 {:errors? true}]
           :refusal-quiet  ["todo_add" {} {:actor "a"} refused 1 {:args? true}]
           :ok-no-error    ["todo_add" {:text "x"} {:actor "a"} {:ok 1} 1 {:errors? true}]
           :pending-id     ["todo_slow" {} {:actor "a"} {:pending "p7"} 2 nil]
           :answer-kept     ["todo_add" {:text "x"} {:actor "a"} {:ok {:id 7 :title "milk"}} 3
                             {:answers? true}]
           :answer-redacted ["login" {:user "u"} {:actor "a"} {:ok {:token "s" :user "u"}} 2
                             {:answers? true :redact [[:answer :token]]}]
           :answer-and-args ["todo_add" {:text "x"} {:actor "a"} {:ok 1} 1
                             {:args? true :answers? true}]
           :answer-refused  ["todo_add" {} {:actor "a"} refused 1 {:answers? true}]
           :args-truncated  ["t" {:text (apply str (repeat 1001 "x"))} {:actor "a"} {:ok 1} 1 {:args? true}]}
   :gen (gen/tuple (gen/elements ["t" "u"])
                   (gen/one-of [(gen/return nil) (gen/map gen/keyword gen/small-integer {:max-elements 3})])
                   (gen/hash-map :actor (gen/elements ["a" "b"]) :session (gen/elements [nil "s"]))
                   (gen/one-of [(gen/fmap answer/ok gen/small-integer)
                                (gen/fmap answer/pending gen/string-alphanumeric)
                                (gen/fmap #(answer/refusal % "m") gen/keyword)])
                   gen/nat
                   (gen/one-of [(gen/return nil)
                                (gen/hash-map :args? gen/boolean :errors? gen/boolean
                                              :answers? gen/boolean)]))
   :pred (fn [e] (and (= :autonomous/call (:kind e)) (keyword? (:outcome e))
                      (or (nil? (:args e)) (map? (:args e)))
                      (or (not (contains? e :answer)) (= :ok (:outcome e)))
                      (or (not (contains? e :args/truncated?)) (contains? e :args))
                      (= (= :pending (:outcome e)) (contains? e :pending))))
   :mutations [["ignores detail" (fn [t a g ans ms _] (call-entry* t a g ans ms nil))]
               ["session without detail" (fn [t a g ans ms d] (call-entry* t a g ans ms (or d {})))]
               ["no redaction" (fn [t a g ans ms d] (call-entry* t a g ans ms (some-> d (dissoc :redact))))]
               ["errors always" (fn [t a g ans ms d] (call-entry* t a g ans ms (some-> d (assoc :errors? true))))]
               ["answers always" (fn [t a g ans ms d] (call-entry* t a g ans ms (some-> d (assoc :answers? true))))]
               ["answers even refused" (fn [t a g ans ms d]
                                         (let [e (call-entry* t a g ans ms d)]
                                           (if (:answers? d) (assoc e :answer (:ok ans)) e)))]
               ["pending id dropped" (fn [t a g ans ms d] (dissoc (call-entry* t a g ans ms d) :pending))]
               ["args never truncated" (fn [t a g ans ms d] (dissoc (call-entry* t a g ans ms d) :args/truncated?))]]})

(def ^:private settled-entry* journal/settled-entry)

(deftrifecta settled-entry-trifecta
  autonomous.domain.journal/settled-entry
  {:golden-path "test/golden/domain/settled-entry.edn"
   :apply? true
   :cases {:ok-no-detail     ["p1" {:ok 1} nil]
           :threw-no-detail  ["p2" {:error {:type :tool-threw :message "boom"}} nil]
           :threw-kept       ["p3" {:error {:type :tool-threw :message "boom" :data {:at "db"}}} {:errors? true}]
           :threw-args-only  ["p4" {:error {:type :tool-threw :message "boom"}} {:args? true}]
           :ok-errors-on     ["p5" {:ok 1} {:errors? true}]
           :ok-answer        ["p6" {:ok {:id 7}} {:answers? true}]
           :answer-redacted  ["p7" {:ok {:token "s" :id 7}} {:answers? true :redact [[:answer :token]]}]
           :threw-answer-off ["p8" {:error {:type :tool-threw :message "boom"}} {:answers? true}]}
   :gen (gen/tuple gen/string-alphanumeric
                   (gen/one-of [(gen/fmap answer/ok gen/small-integer)
                                (gen/fmap #(answer/refusal % "m") gen/keyword)])
                   (gen/one-of [(gen/return nil)
                                (gen/hash-map :args? gen/boolean :errors? gen/boolean
                                              :answers? gen/boolean)]))
   :pred (fn [e] (and (= :autonomous/settled (:kind e)) (keyword? (:outcome e))
                      (contains? e :pending)
                      (or (not (contains? e :answer)) (= :ok (:outcome e)))))
   :mutations [["ignores detail" (fn [id a _] (settled-entry* id a nil))]
               ["errors always" (fn [id a d] (settled-entry* id a (assoc d :errors? true)))]
               ["answers always" (fn [id a d] (settled-entry* id a (some-> d (assoc :answers? true))))]
               ["id dropped" (fn [id a d] (dissoc (settled-entry* id a d) :pending))]]})

(deftest the-answer-is-opt-in-bounded-and-redacted
  (testing "off by default: no detail, no answer"
    (is (not (contains? (journal/call-entry "t" {} {:actor "a"} {:ok 1} 1 nil) :answer)))
    (is (not (contains? (journal/call-entry "t" {} {:actor "a"} {:ok 1} 1 {}) :answer))))
  (testing "an Ok call and its settled entry carry :answer; a refusal does not"
    (is (= {:id 7} (:answer (journal/call-entry "t" {} {:actor "a"} {:ok {:id 7}} 1 {:answers? true}))))
    (is (= {:id 7} (:answer (journal/settled-entry "p1" {:ok {:id 7}} {:answers? true}))))
    (is (not (contains? (journal/call-entry "t" {} {:actor "a"}
                                           {:error {:type :invalid-args :message "bad"}}
                                           1 {:answers? true})
                        :answer))))
  (testing "a path under :answer is masked inside it"
    (is (= {:token journal/redacted :user "u"}
           (:answer (journal/call-entry "t" {} {:actor "a"} {:ok {:token "s" :user "u"}} 1
                                        {:answers? true :redact [[:answer :token]]})))))
  (testing "the size bound cuts a long answer and marks it"
    (let [long (journal/call-entry "t" {} {:actor "a"} {:ok (apply str (repeat 5000 "x"))} 1 {:answers? true})
          wide (journal/call-entry "t" {} {:actor "a"} {:ok (vec (range 20000))} 1 {:answers? true})]
      (is (:answer/truncated? long))
      (is (= (+ journal/max-detail-chars (count journal/cut-marker)) (count (:answer long))))
      (is (:answer/truncated? wide))
      (is (= journal/max-detail-items (count (:answer wide))))
      (is (= wire/max-items journal/max-detail-items))
      (testing "a small answer is not marked"
        (is (not (contains? (journal/call-entry "t" {} {:actor "a"} {:ok {:id 7}} 1 {:answers? true})
                            :answer/truncated?))))))
  (testing "the size bound cuts long args and marks them, symmetric with :answer"
    (let [long (journal/call-entry "t" {:text (apply str (repeat 5000 "x"))} {:actor "a"} {:ok 1} 1 {:args? true})
          wide (journal/call-entry "t" {:items (vec (range 20000))} {:actor "a"} {:ok 1} 1 {:args? true})]
      (is (:args/truncated? long))
      (is (= (+ journal/max-detail-chars (count journal/cut-marker)) (count (:text (:args long)))))
      (is (:args/truncated? wide))
      (is (= journal/max-detail-items (count (:items (:args wide))))))
    (testing "small args and unrecorded args are not marked"
      (is (not (contains? (journal/call-entry "t" {:text "x"} {:actor "a"} {:ok 1} 1 {:args? true})
                          :args/truncated?)))
      (is (not (contains? (journal/call-entry "t" {:text (apply str (repeat 5000 "x"))} {:actor "a"} {:ok 1} 1 nil)
                          :args/truncated?)))))
  (testing "the CallDetail schema takes :answers? and stays closed"
    (is (m/validate s/CallDetail {:answers? true}))
    (is (not (m/validate s/CallDetail {:answers? "yes"})))))

;; -----------------------------------------------------------------------------
;; caller coordinate (MCP _meta)

(def ^:private from-meta* caller/from-meta)

(def ^:private tp-valid "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")

(def ^:private caller-params
  "A tools/call params with a full, valid _meta, under the wire spelling."
  {:name "todo_add" :arguments {:text "x"}
   :_meta {"traceparent" tp-valid
           "tracestate" "rojo=00f067aa0ba902b7"
           "io.github.hive-agi/chat-point" {"run-id" "r1" "seq" 5 "turn" 3}
           "io.github.hive-agi/run-task-id" "card-7"
           "io.github.hive-agi/run-attempt" 2}})

(def ^:private hex16 "0123456789abcdef")

(defn- gen-hex
  "Generator of an `n`-digit lowercase hex string."
  [n]
  (gen/fmap #(apply str %) (gen/vector (gen/elements (vec hex16)) n)))

(def ^:private gen-traceparent
  "Generator of well-formed W3C traceparents (version 00, non-zero ids, flags 01)."
  (gen/fmap (fn [[t s]] (str "00-" t "-" s "-01"))
            (gen/tuple (gen/such-that #(not (every? #{\0} %)) (gen-hex 32) 50)
                       (gen/such-that #(not (every? #{\0} %)) (gen-hex 16) 50))))

(def ^:private gen-chat-point
  (gen/hash-map :run-id (gen/such-that seq gen/string-alphanumeric 20)
                :seq (gen/choose 0 100000)
                :turn (gen/choose 0 100000)))

(def ^:private gen-caller-params
  "Generator of a tools/call params whose _meta carries a valid coordinate."
  (gen/fmap (fn [[cp tp ts tid att]]
              {:name "t" :arguments {}
               :_meta {"io.github.hive-agi/chat-point" (into {} (map (fn [[k v]] [(name k) v])) cp)
                       "traceparent" tp
                       "tracestate" ts
                       "io.github.hive-agi/run-task-id" tid
                       "io.github.hive-agi/run-attempt" att}})
            (gen/tuple gen-chat-point gen-traceparent gen/string-alphanumeric
                       gen/string-alphanumeric gen/nat)))

(deftrifecta from-meta-trifecta
  autonomous.domain.caller/from-meta
  {:golden-path "test/golden/domain/caller.edn"
   :cases {:full            caller-params
           :no-meta         {:name "t" :arguments {}}
           :empty-meta      {:_meta {}}
           :trace-only      {:_meta {"traceparent" tp-valid}}
           :invalid-tp      {:_meta {"traceparent" "nope"}}
           :zeros-tp        {:_meta {"traceparent" "00-00000000000000000000000000000000-0000000000000000-01"}}
           :bad-chat-point  {:_meta {"io.github.hive-agi/chat-point" {"run-id" "r"}}}
           :keyword-meta    {:_meta {:traceparent tp-valid
                                     :io.github.hive-agi/chat-point {:run-id "r" :seq 1 :turn 2}}}
           :meta-itself     {"traceparent" tp-valid}
           :bad-attempt     {:_meta {"io.github.hive-agi/run-attempt" -1}}}
   :gen gen-caller-params
   :pred (fn [{:keys [caller dropped]}]
           (and (or (nil? caller) (m/validate s/Caller caller))
                (vector? dropped)
                (every? keyword? dropped)))
   :mutations
   [["keeps an invalid traceparent"
     ;; Drops the traceparent check: a raw traceparent is kept, whatever its
     ;; shape. Killed by :invalid-tp and :zeros-tp.
     (fn [p]
       (let [r  (from-meta* p)
             tp (get (or (caller/meta-of p) {}) "traceparent")]
         (if (and (string? tp) (nil? (get-in r [:caller :trace/traceparent])))
           (assoc r :caller (assoc (:caller r) :trace/traceparent tp)
                    :dropped (vec (remove #{:trace/traceparent} (:dropped r))))
           r)))]
    ["keeps any chat-point"
     ;; Drops the chat-point check: any map is kept. Killed by :bad-chat-point.
     (fn [p]
       (let [r  (from-meta* p)
             cp (get (or (caller/meta-of p) {}) "io.github.hive-agi/chat-point")]
         (if (and (map? cp) (nil? (get-in r [:caller :chat-point])))
           (assoc r :caller (assoc (:caller r) :chat-point cp)
                    :dropped (vec (remove #{:chat-point} (:dropped r))))
           r)))]]})

(deftest the-caller-coordinate-is-read-and-validated
  (testing "a full valid _meta reads as the coordinate, nothing dropped"
    (is (= {:caller {:trace/traceparent tp-valid
                     :trace/tracestate "rojo=00f067aa0ba902b7"
                     :chat-point {:run-id "r1" :seq 5 :turn 3}
                     :run/task-id "card-7"
                     :run/attempt 2}
            :dropped []}
           (caller/from-meta caller-params))))
  (testing "off by default: no _meta reads as no caller"
    (is (= {:caller nil :dropped []} (caller/from-meta {:name "t" :arguments {}})))
    (is (= {:caller nil :dropped []} (caller/from-meta {:_meta {}})))
    (is (= {:caller nil :dropped []} (caller/from-meta nil))))
  (testing "an invalid traceparent is dropped and counted, never an error"
    (is (= {:caller nil :dropped [:trace/traceparent]} (caller/from-meta {:_meta {"traceparent" "nope"}})))
    (is (= [:trace/traceparent]
           (:dropped (caller/from-meta {:_meta {"traceparent" "00-00000000000000000000000000000000-0000000000000000-01"}}))))
    (is (= {:caller {:chat-point {:run-id "r" :seq 0 :turn 0}} :dropped [:trace/traceparent]}
           (caller/from-meta {:_meta {"traceparent" "bad"
                                     "io.github.hive-agi/chat-point" {"run-id" "r" "seq" 0 "turn" 0}}}))))
  (testing "a chat-point that is not a ChatPoint, or an attempt below zero, is dropped"
    (is (= {:caller nil :dropped [:chat-point]} (caller/from-meta {:_meta {"io.github.hive-agi/chat-point" {"run-id" "r"}}})))
    (is (= {:caller nil :dropped [:run/attempt]} (caller/from-meta {:_meta {"io.github.hive-agi/run-attempt" -1}}))))
  (testing "both spellings of _meta and of its inner keys are read"
    (is (= {:caller {:trace/traceparent tp-valid :chat-point {:run-id "r" :seq 1 :turn 2}} :dropped []}
           (caller/from-meta {:_meta {:traceparent tp-valid
                                     :io.github.hive-agi/chat-point {:run-id "r" :seq 1 :turn 2}}})))
    (is (= {:caller {:trace/traceparent tp-valid} :dropped []}
           (caller/from-meta {"traceparent" tp-valid})))))

(deftest the-caller-schema-is-closed-and-mirrors-hive-spi
  (testing "Caller validates the shape the reader returns"
    (is (m/validate s/Caller (:caller (caller/from-meta caller-params))))
    (is (m/validate s/Caller {})))
  (testing "the key names mirror hive-spi/hive-agent exactly"
    (is (m/validate s/ChatPoint {:run-id "r" :seq 0 :turn 0}))
    (is (not (m/validate s/ChatPoint {:run-id "r" :turn 0})))
    (is (m/validate s/RunCorrelation {:run/task-id "c" :run/attempt 0}))
    (is (not (m/validate s/RunCorrelation {:run/attempt -1}))))
  (testing "CallDetail stays closed and takes :caller"
    (is (m/validate s/CallDetail {:caller (:caller (caller/from-meta caller-params))}))
    (is (m/validate s/CallDetail {:caller nil}))
    (is (not (m/validate s/CallDetail {:caller 5})))
    (is (not (m/validate s/CallDetail {:not-a-key 1})))))

(deftest a-caller-is-recorded-on-the-entry-bounded-and-redacted
  (testing "recorded whenever detail carries one, independent of :args?/:answers?"
    (let [e (journal/call-entry "t" {} {:actor "a"} {:ok 1} 1
                                {:caller {:chat-point {:run-id "r" :seq 0 :turn 0}}})]
      (is (= {:chat-point {:run-id "r" :seq 0 :turn 0}} (:caller e)))
      (is (not (contains? e :args)))
      (is (not (contains? e :answer)))))
  (testing "a :redact path under :caller is masked inside it"
    (is (= {:run/task-id journal/redacted}
           (:caller (journal/call-entry "t" {} {:actor "a"} {:ok 1} 1
                                        {:caller {:run/task-id "secret"}
                                         :redact [[:caller :run/task-id]]})))))
  (testing "a caller too large to keep is cut and marked"
    (let [big (journal/call-entry "t" {} {:actor "a"} {:ok 1} 1
                                  {:caller {:run/task-id (apply str (repeat 5000 "x"))}})]
      (is (:caller/truncated? big))
      (is (= (+ journal/max-detail-chars (count journal/cut-marker))
             (count (:run/task-id (:caller big)))))))
  (testing "with no caller the entry is unchanged"
    (is (not (contains? (journal/call-entry "t" {} {:actor "a"} {:ok 1} 1 {}) :caller)))
    (is (not (contains? (journal/call-entry "t" {} {:actor "a"} {:ok 1} 1 nil) :caller)))))

;; -----------------------------------------------------------------------------
;; wire values

(defrecord Point [x y])

(deftrifecta wire-trifecta
  autonomous.domain.wire/->wire
  {:golden-path "test/golden/domain/wire.edn"
   :cases {:scalar   42
           :nested   {:a [1 #{:k}] "s" nil}
           :record   (->Point 1 2)
           :lazy     (map inc (range 3))
           :object   (java.net.URI. "http://example.test/x")
           :odd-key  {(java.net.URI. "u:k") 1}}
   :gen gen/any-printable-equatable
   :pred (fn [v] (= v (wire/read-edn (wire/print-edn v))))
   :num-tests 200
   :mutations [["identity" identity]
               ["drops maps" (fn [x] (if (map? x) {} x))]]})

;; -----------------------------------------------------------------------------
;; answers

(deftrifecta outcome-trifecta
  autonomous.domain.answer/outcome
  {:golden-path "test/golden/domain/outcome.edn"
   :cases {:ok        {:ok nil}
           :pending   {:pending "p1"}
           :refused   {:error {:type :forbidden :message "no"}}
           :malformed {}}
   :gen (gen/one-of [(gen/fmap answer/ok gen/any-printable-equatable)
                     (gen/fmap answer/pending gen/string-alphanumeric)
                     (gen/fmap #(answer/refusal % "m") gen/keyword)])
   :pred keyword?
   :mutations [["ok whenever truthy" (fn [a] (if (:ok a) :ok :pending))]
               ["refusal type lost" (fn [a] (cond (answer/ok? a) :ok (answer/pending? a) :pending :else :error))]]})
