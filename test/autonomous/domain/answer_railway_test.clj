(ns autonomous.domain.answer-railway-test
  "The answer railway: bind, let-answer and reduce-answer carry Ok forward
   and let the first Pending or Refusal end the line untouched."
  (:require [autonomous.domain.answer :as answer]
            [clojure.test :refer [deftest is testing]]))

(def refused (answer/refusal :nope "no"))
(def pending (answer/pending "p-1"))

(deftest bind-runs-f-only-on-ok
  (is (= (answer/ok 2) (answer/bind (answer/ok 1) #(answer/ok (inc %)))))
  (is (= (answer/ok {:a 1}) (answer/bind (answer/ok {:a 1}) answer/ok)))
  (testing "a refusal passes through, f never runs"
    (is (= refused (answer/bind refused (fn [_] (is false "f ran"))))))
  (testing "a pending passes through, f never runs"
    (is (= pending (answer/bind pending (fn [_] (is false "f ran"))))))
  (testing "f's own refusal is the answer"
    (is (= refused (answer/bind (answer/ok 1) (fn [_] refused))))))

(deftest let-answer-threads-ok-values-and-stops-at-the-first-bad-step
  (is (= (answer/ok 3)
         (answer/let-answer [a (answer/ok 1)
                             b (answer/ok (inc a))]
           (answer/ok (+ a b)))))
  (testing "destructuring binds the Ok value"
    (is (= (answer/ok "todo")
           (answer/let-answer [{:keys [target]} (answer/ok {:target "todo"})]
             (answer/ok target)))))
  (testing "a refusal short-circuits: later steps never run"
    (is (= refused
           (answer/let-answer [a (answer/ok 1)
                               b refused
                               c (do (is false "ran past a refusal") (answer/ok 3))]
             (answer/ok (+ a b c))))))
  (testing "a pending short-circuits too"
    (is (= pending
           (answer/let-answer [a pending
                               b (do (is false "ran past a pending") (answer/ok 2))]
             (answer/ok (+ a b))))))
  (testing "an empty binding vector is the body"
    (is (= (answer/ok :body) (answer/let-answer [] (answer/ok :body)))))
  (testing "the body may answer anything, it is not re-wrapped"
    (is (= refused (answer/let-answer [a (answer/ok 1)] refused)))))

(deftest reduce-answer-folds-ok-and-short-circuits
  (is (= (answer/ok 6)
         (answer/reduce-answer (fn [acc v] (answer/ok (+ acc v))) 0 [1 2 3])))
  (is (= (answer/ok 0)
         (answer/reduce-answer (fn [acc v] (answer/ok (+ acc v))) 0 []))
      "the seed is Ok on an empty coll")
  (testing "the first refusal is the fold's answer; later steps never run"
    (let [steps (atom 0)]
      (is (= refused
             (answer/reduce-answer (fn [acc v]
                                     (swap! steps inc)
                                     (if (= v :bad) refused (answer/ok (+ acc v))))
                                   0 [1 :bad 3])))
      (is (= 2 @steps) "the fold stopped at the refusal")))
  (testing "a pending step ends the fold"
    (let [steps (atom 0)]
      (is (= pending
             (answer/reduce-answer (fn [_ v]
                                     (swap! steps inc)
                                     (if (= v :wait) pending (answer/ok v)))
                                   0 [1 :wait 3])))
      (is (= 2 @steps))))
  (testing "the accumulator is the raw Ok value, not an Answer"
    (is (= (answer/ok [0 1 2])
           (answer/reduce-answer (fn [acc v] (answer/ok (conj acc (count acc))))
                                 [] [:a :b :c])))))