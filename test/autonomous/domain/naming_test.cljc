(ns autonomous.domain.naming-test
  "The tool-name rule at its edges: hyphens kept, dots, slashes and
   unicode made `_`, 64 characters at most, explicit names refused rather
   than repaired, and fleet names valid whatever a remote app lists."
  (:require [autonomous.domain.affordance :as affordance]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.fleet :as fleet]
            [autonomous.domain.naming :as naming]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(def ^:private mcp-name #"[A-Za-z0-9_-]{1,64}")

(defn- mcp-name? [s] (boolean (and (string? s) (re-matches mcp-name s))))

(defn- spec [extra]
  (merge {:description "d" :handler (fn [_ _] nil)} extra))

(deftest hyphens-are-kept
  (is (= "todos_clear-done" (naming/tool-name :todos/clear-done)))
  (is (= "backend__todos_clear-done"
         (fleet/exposed-name (fleet/prefix "backend") (naming/tool-name :todos/clear-done))))
  (is (= "app_todos_add-item" (naming/tool-name :app.todos/add-item)))
  (is (= "-" (naming/tool-name "-")) "a name may start with a hyphen")
  (is (naming/valid-tool-name? "clear-done"))
  (is (naming/valid-tool-name? "-x-")))

(deftest dots-and-slashes-become-underscores
  (is (= "admin_tools_list" (naming/tool-name "admin.tools.list")))
  (is (= "admin_tools_list" (naming/tool-name :admin.tools/list)))
  (is (= "a_b_c" (naming/tool-name "a/b/c")))
  (is (= "a_b_c" (naming/tool-name :a/b.c)))
  (is (= "_" (naming/tool-name "/")))
  (is (not (naming/valid-tool-name? "a.b")))
  (is (not (naming/valid-tool-name? "a/b"))))

(deftest unicode-becomes-underscores
  (testing "one _ per UTF-16 code unit"
    (is (= "a__o" (naming/tool-name "ação")))
    (is (= "caf_" (naming/tool-name "café")))
    (is (= "x__y" (naming/tool-name "x\uD83D\uDE00y")) "an emoji is two code units"))
  (is (= "add_a_todo" (naming/tool-name "add a todo")))
  (is (= "a_b" (naming/tool-name "a\tb")))
  (is (not (naming/valid-tool-name? "ação")))
  (is (not (naming/valid-tool-name? "a b"))))

(deftest names-over-64-characters-are-cut
  (is (= (apply str (repeat 64 "a")) (naming/tool-name (keyword (apply str (repeat 80 "a"))))))
  (is (= (apply str (repeat 64 "_")) (naming/tool-name (apply str (repeat 70 "ç")))))
  (is (= (str (apply str (repeat 63 "b")) "-")
         (naming/tool-name (str (apply str (repeat 63 "b")) "-tail"))))
  (is (naming/valid-tool-name? (apply str (repeat 64 "a"))))
  (is (not (naming/valid-tool-name? (apply str (repeat 65 "a")))))
  (is (not (naming/valid-tool-name? "")))
  (is (not (naming/valid-tool-name? nil))))

(deftest derivation-is-total-idempotent-and-deterministic
  (let [r (tc/quick-check 300
                          (prop/for-all [id (gen/one-of [gen/keyword-ns gen/keyword gen/string
                                                         (gen/fmap #(apply str %) (gen/vector gen/char 0 200))])]
                            (let [n (naming/tool-name id)]
                              (and (mcp-name? n)
                                   (= n (naming/tool-name id))
                                   (= n (naming/tool-name n)))))
                          :seed 11)]
    (is (:pass? r) (pr-str (:shrunk r))))
  (testing "a valid name maps to itself"
    (doseq [n ["todo_add" "clear-done" "A-Z_0-9" (apply str (repeat 64 "z"))]]
      (is (= n (naming/tool-name n))))))

(deftest explicit-names-are-refused-not-repaired
  (doseq [bad ["a.b" "a/b" "ação" "a b" "" (apply str (repeat 65 "a"))]]
    (let [a (affordance/->tool :t/x (spec {:name bad}))]
      (is (answer/refused? a) (pr-str bad))
      (is (= :invalid-tool (get-in a [:error :type])))))
  (doseq [good ["clear-done" "-" (apply str (repeat 64 "a"))]]
    (is (= good (get-in (affordance/->tool :t/x (spec {:name good})) [:ok :name])))))

(defn- tools-of [& names]
  (mapv (fn [n] {:name n :description "" :input-schema {}}) names))

(deftest fleet-names-are-valid-for-any-remote-name
  (testing "a remote name that is already valid is exposed as it is"
    (is (= "remote__clear-done" (fleet/exposed-name "remote" "clear-done")))
    (is (= "remote__pay_out" (fleet/exposed-name "remote" "pay_out"))))
  (testing "a remote name MCP 2025-11-25 allows and this bridge does not is made safe and digested"
    (let [n (fleet/exposed-name "remote" "admin.tools.list")]
      (is (mcp-name? n))
      (is (= (str "remote__admin_tools_list-" (fleet/digest "remote__admin.tools.list")) n))
      (is (fleet/cut? n)))
    (is (not= (fleet/exposed-name "remote" "pay.out") (fleet/exposed-name "remote" "pay_out"))
        "names differing only by . and _ never collide")
    (is (mcp-name? (fleet/exposed-name "remote" "a/b")))
    (is (mcp-name? (fleet/exposed-name "remote" "ação"))))
  (testing "the bridge finds the tool a changed name stands for"
    (let [n (fleet/exposed-name "remote" "admin.tools.list")]
      (is (= "admin.tools.list" (fleet/resolve-local "remote" n ["x" "admin.tools.list" "admin_tools_list"])))))
  (testing "a long remote name is cut to 64 with a digest of the original name"
    (let [tool (str "v1." (apply str (repeat 80 "q")))
          n    (fleet/exposed-name "remote" tool)]
      (is (= 64 (count n)))
      (is (mcp-name? n))
      (is (fleet/cut? n))
      (is (= tool (fleet/resolve-local "remote" n [tool])))))
  (testing "names differing past the cut, or by one character, get different digests"
    (let [names (map #(str (apply str (repeat 70 "z")) %) (range 2000))]
      (is (= 2000 (count (set (map #(fleet/exposed-name "r" %) names)))))))
  (let [r (tc/quick-check 300
                          (prop/for-all [pfx (gen/fmap fleet/prefix gen/string)
                                         tool (gen/fmap #(apply str %) (gen/vector gen/char 1 120))]
                            (mcp-name? (fleet/exposed-name pfx tool)))
                          :seed 13)]
    (is (:pass? r) (pr-str (:shrunk r))))
  (testing "the exposed name does not depend on list order"
    (let [s1 (fleet/merge-probes [{:target "r" :status :up :manifest {:tools (tools-of "pay_out" "pay.out")}}])
          s2 (fleet/merge-probes [{:target "r" :status :up :manifest {:tools (tools-of "pay.out" "pay_out")}}])]
      (is (= (:routes s1) (:routes s2)))
      (is (= {:target "r" :tool "pay_out"} (get-in s1 [:routes "r__pay_out"])))
      (is (= 2 (count (:routes s1))))))
  (testing "a real collision of exposed names exposes neither and reports both as dropped"
    (let [long-a (str (apply str (repeat 70 "q")) ".x")
          clash  (first (for [i (range 5000) :let [b (str long-a i)]
                              :when (= (fleet/exposed-name "r" long-a) (fleet/exposed-name "r" b))]
                          b))]
      (is (nil? clash) "no collision among the digested names searched")
      (with-redefs [fleet/exposed-name (fn [_ t] (if (#{"a" "b"} t) "r__ab" (str "r__" t)))]
        (let [s (fleet/merge-probes [{:target "r" :status :up :manifest {:tools (tools-of "a" "b" "c")}}])]
          (is (= ["r__c"] (mapv :name (:tools s))))
          (is (= #{"a" "b"} (set (get-in s [:apps 0 :dropped]))))
          (is (nil? (fleet/resolve-local "r" "r__ab" ["a" "b"]))))))))

(deftest every-name-doc-naming-shows
  (doseq [[id expected] [[:todo/add "todo_add"]
                         [:todos/clear-done "todos_clear-done"]
                         [:app.todos/add-item "app_todos_add-item"]
                         ["admin.tools.list" "admin_tools_list"]
                         ["ação" "a__o"]
                         ["" "_"]]]
    (is (= expected (naming/tool-name id)) (pr-str id))))
