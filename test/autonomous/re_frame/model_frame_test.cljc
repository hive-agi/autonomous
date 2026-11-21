(ns autonomous.re-frame.model-frame-test
  "The pure half of the re-frame2 :frame option: which frame ids are taken,
   and where a ClojureScript build keeps the re-frame2 entry points."
  (:require [autonomous.re-frame.model :as model]
            [clojure.test :refer [deftest is testing]]))

(deftest a-frame-is-absent-or-a-frame-id-keyword
  (testing "no :frame is the default path and is never a problem"
    (is (nil? (model/frame-problem nil))))
  (testing "a keyword is a re-frame2 frame id, qualified or not"
    (is (nil? (model/frame-problem :app/main)))
    (is (nil? (model/frame-problem :rf/default))))
  (testing "anything else is refused with a reason naming it"
    (doseq [bad ["app/main" 'app/main [:app/main] {:id :app/main} 1]]
      (is (string? (model/frame-problem bad)) (pr-str bad))
      (is (re-find #"frame-id keyword" (model/frame-problem bad))))))
