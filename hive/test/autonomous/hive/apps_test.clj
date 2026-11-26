(ns autonomous.hive.apps-test
  "The app facade's pure reaches: what a home-relative app directory becomes."
  (:require [autonomous.hive.apps :as apps]
            [clojure.test :refer [deftest is testing]]))

(def ^:private home
  "HOME, read exactly where the code reads it, so no golden row hardcodes a
   machine's home directory."
  (System/getProperty "user.home"))

(deftest expand-home-golden
  (testing "`~` alone and `~/x` expand to HOME; everything else is unchanged"
    (doseq [[path expected]
            [["~" home]
             ["~/x" (str home "/x")]
             ["~/PP/todo" (str home "/PP/todo")]
             ["/abs/x" "/abs/x"]
             ["rel/x" "rel/x"]
             ["" ""]
             [nil nil]
             [42 42]]]
      (is (= expected (apps/expand-home path)) (pr-str path)))))

(deftest expand-home-leaves-a-tilde-user-form-alone
  (testing "`~user` is not `~`: no user lookup happens here, so the path comes
            back unchanged. The old body concatenated HOME with everything
            after the tilde, so `~user/x` became HOME followed by `user/x`;
            that was a bug, and this test is the pin that it stays fixed."
    (doseq [[path expected]
            [["~user/x" "~user/x"]
             ["~root" "~root"]
             ["~x/y" "~x/y"]]]
      (is (= expected (apps/expand-home path)) (pr-str path)))))

(deftest read-dir-facts-still-expands-the-app-dir
  (testing "the caller keeps its shape: a home-relative :app/dir reaches the
            reader expanded, and an app with no :app/dir reads as nil"
    (is (nil? (apps/read-dir-facts {})))
    (is (nil? (apps/read-dir-facts {:app/id "absent"})))))
