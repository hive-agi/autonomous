(ns autonomous.runtime.chain-test
  "The call chain runner: enter order, reverse unwind, :error recovery,
   short-circuit on an :answer supplied in :enter, and containment of a
   stage that throws or returns a malformed context."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.runtime.chain :as chain]
            [autonomous.surface :as surface]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]))

(defn- logging-link
  "A link that appends [:enter n] and [:leave n] to atom `log`."
  [log n]
  {:name  (keyword "t" (str n))
   :enter (fn [ctx] (swap! log conj [:enter n]) ctx)
   :leave (fn [ctx] (swap! log conj [:leave n]) ctx)})

(defn- ok-terminal [log]
  (fn [_] (swap! log conj :terminal) (answer/ok :done)))

(defn- expected-trace [n]
  (concat (map #(vector :enter %) (range n))
          [:terminal]
          (map #(vector :leave %) (reverse (range n)))))

(deftest enter-in-order-leave-in-reverse
  (let [r (tc/quick-check
           100
           (prop/for-all [n (gen/choose 0 8)]
             (let [log (atom [])
                   a   (chain/run (mapv #(logging-link log %) (range n)) {} (ok-terminal log))]
               (and (= {:ok :done} a) (= (expected-trace n) @log)))))]
    (is (true? (:result r)) (pr-str r))))

(deftest links-are-interceptor-values
  (is (every? #(m/validate surface/Interceptor %)
              [(logging-link (atom []) 1) (chain/legacy-link :t/legacy identity)])))

(deftest error-recovers-and-outer-links-see-the-recovery
  (let [log     (atom [])
        recover {:name  :t/recover
                 :error (fn [ctx refusal]
                          (swap! log conj [:error (get-in refusal [:error :type])])
                          (assoc ctx :answer (answer/ok :recovered)))}
        a       (chain/run [(logging-link log :outer) recover] {}
                           (fn [_] (answer/refusal :t/boom "no")))]
    (is (= {:ok :recovered} a))
    (is (= [[:enter :outer] [:error :t/boom] [:leave :outer]] @log))))

(deftest a-refusal-skips-leave-on-links-without-error
  (let [log (atom [])
        a   (chain/run [(logging-link log 1)] {} (fn [_] (answer/refusal :t/boom "no")))]
    (is (= :t/boom (get-in a [:error :type])))
    (is (= [[:enter 1]] @log))))

(deftest an-answer-in-enter-short-circuits
  (let [log  (atom [])
        gate {:name  :t/gate
              :enter (fn [ctx] (swap! log conj :gate)
                       (assoc ctx :answer (answer/refusal :t/closed "closed")))
              :error (fn [ctx _] (swap! log conj :gate-error) ctx)}
        a    (chain/run [(logging-link log 1) gate (logging-link log 2)] {} (ok-terminal log))]
    (is (= :t/closed (get-in a [:error :type])))
    (testing "no later link enters, the terminal never runs, entered links unwind"
      (is (= [[:enter 1] :gate :gate-error] @log)))))

(deftest a-failing-stage-becomes-a-refusal
  (let [throws {:name :t/throws :enter (fn [_] (throw (ex-info "bad" {:autonomous/error :t/bad})))}
        nils   {:name :t/nil :enter (constantly nil)}
        term   (fn [_] (answer/ok 1))]
    (is (= :t/bad (get-in (chain/run [throws] {} term) [:error :type])))
    (is (= :interceptor/context (get-in (chain/run [nils] {} term) [:error :type])))
    (is (= :t/bad (get-in (chain/run [] {} (fn [_] (throw (ex-info "t" {:autonomous/error :t/bad}))))
                          [:error :type]))
        "a throwing terminal too")))

(deftest legacy-wrappers-nest-last-registered-outermost
  (let [log   (atom [])
        wrap  (fn [n] (fn [h] (fn [] (swap! log conj [:in n]) (let [v (h)] (swap! log conj [:out n]) v))))
        links [(chain/legacy-link :t/a (wrap :a)) (chain/legacy-link :t/b (wrap :b))]
        a     (chain/run links {} (fn [ctx] (answer/ok ((chain/handler ctx (fn [] :v))))))]
    (is (= {:ok :v} a))
    (is (= [[:in :b] [:in :a] [:out :a] [:out :b]] @log))))
