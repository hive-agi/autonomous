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

(deftest fleet-names-are-valid-for-any-remote-name
  (testing "a remote app may list names MCP 2025-11-25 allows and this bridge does not"
    (is (= "remote__admin_tools_list" (fleet/exposed-name "remote" "admin.tools.list")))
    (is (= "remote__a_b" (fleet/exposed-name "remote" "a/b")))
    (is (= "remote__a__o" (fleet/exposed-name "remote" "ação"))))
  (testing "the bridge finds the tool a changed name stands for"
    (is (= "admin.tools.list"
           (fleet/resolve-local "remote" "remote__admin_tools_list" ["x" "admin.tools.list"]))))
  (testing "a long remote name is cut to 64 with a checksum of the sanitized name"
    (let [tool (str "v1." (apply str (repeat 80 "q")))
          n    (fleet/exposed-name "remote" tool)]
      (is (= 64 (count n)))
      (is (mcp-name? n))
      (is (fleet/cut? n))
      (is (= tool (fleet/resolve-local "remote" n [tool])))))
  (let [r (tc/quick-check 300
                          (prop/for-all [pfx (gen/fmap fleet/prefix gen/string)
                                         tool (gen/fmap #(apply str %) (gen/vector gen/char 1 120))]
                            (mcp-name? (fleet/exposed-name pfx tool)))
                          :seed 13)]
    (is (:pass? r) (pr-str (:shrunk r))))
  (testing "two remote names that sanitize alike: the first listed keeps the name"
    (let [s (fleet/merge-probes [{:target "r" :status :up
                                  :manifest {:tools [{:name "a.b" :description "" :input-schema {}}
                                                     {:name "a_b" :description "" :input-schema {}}]}}])]
      (is (= ["r__a_b"] (mapv :name (:tools s))))
      (is (= {:target "r" :tool "a.b"} (get-in s [:routes "r__a_b"])))
      (is (= ["a_b"] (get-in s [:apps 0 :dropped]))))))

(deftest every-name-doc-naming-shows
  (doseq [[id expected] [[:todo/add "todo_add"]
                         [:todos/clear-done "todos_clear-done"]
                         [:app.todos/add-item "app_todos_add-item"]
                         ["admin.tools.list" "admin_tools_list"]
                         ["ação" "a__o"]
                         ["" "_"]]]
    (is (= expected (naming/tool-name id)) (pr-str id))))
