(ns autonomous.e2e.check-trifecta-test
  "Trifectas over the pure rules of the live e2e: runtime selection, the
   stdio smoke verdict and the reading of a tools/call response."
  (:require [autonomous.e2e.check :as check]
            [autonomous.e2e.scenario :as scenario]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]))

(def ^:private new-runtimes* check/new-runtimes)
(def ^:private smoke-problems* check/smoke-problems)

(defn- rts [& ids] (mapv (fn [id] {:runtime/id id}) ids))

(deftrifecta new-runtimes-trifecta
  autonomous.e2e.check/new-runtimes
  {:golden-path "test/golden/e2e/new-runtimes.edn"
   :apply? true
   :cases {:fresh-server  [[] (rts 1 2)]
           :reused-server [(rts 1 2) (rts 1 2 3 4)]
           :one-left      [(rts 1 2 3) (rts 2 3 5)]
           :none-new      [(rts 1) (rts 1)]
           :repeats       [[] (rts 4 4 5)]
           :no-id         [[] [{:runtime/label "x"} {:runtime/id 9}]]}
   :gen (gen/tuple (gen/fmap #(apply rts %) (gen/vector (gen/choose 0 6)))
                   (gen/fmap #(apply rts %) (gen/vector (gen/choose 0 6))))
   :pred (fn [ids] (and (vector? ids) (= ids (distinct ids))))
   :mutations [["keeps the old ones" (fn [_ after] (new-runtimes* [] after))]
               ["keeps repeats" (fn [before after]
                                  (let [seen (set (map :runtime/id before))]
                                    (into [] (comp (map :runtime/id) (remove nil?) (remove seen)) after)))]
               ["reversed order" (fn [b a] (vec (reverse (new-runtimes* b a))))]]})

(def ^:private good-responses
  [{:id 1 :result {:protocolVersion "2025-03-26"}}
   {:id 2 :result {:tools [{:name "autonomous_whoami"} {:name "todos_stats"}]}}
   {:id 3 :result {:structuredContent {:runtimes [{:runtime/id 7}]}}}
   {:id 4 :result {:structuredContent {:attached 7}}}
   {:id 5 :result {:structuredContent {:total 0 :done 0}}}])

(defn- without [id] (vec (remove #(= id (:id %)) good-responses)))

(deftrifecta smoke-problems-trifecta
  autonomous.e2e.check/smoke-problems
  {:golden-path "test/golden/e2e/smoke-problems.edn"
   :apply? true
   :cases {:all-good      [7 good-responses]
           :string-id     ["7" good-responses]
           :other-runtime [8 good-responses]
           :no-initialize [7 (without 1)]
           :no-tools      [7 (without 2)]
           :no-attach     [7 (without 4)]
           :no-app-answer [7 (without 5)]
           :nothing       [7 []]}
   :gen (gen/tuple (gen/elements [7 8])
                   (gen/fmap (fn [keep] (filterv #(contains? keep (:id %)) good-responses))
                             (gen/set (gen/elements [1 2 3 4 5]))))
   :pred (fn [ps] (and (vector? ps) (every? string? ps)))
   :mutations [["always fine" (constantly [])]
               ["ignores the runtime" (fn [_ rs] (smoke-problems* 7 rs))]
               ["first problem only" (fn [id rs] (vec (take 1 (smoke-problems* id rs))))]]})

(deftrifecta call-outcome-trifecta
  autonomous.e2e.scenario/call-outcome
  {:golden-path "test/golden/e2e/call-outcome.edn"
   :cases {:ok        {:result {:structuredContent {:added "milk"}}}
           :tool-err  {:result {:content [] :isError true}}
           :rpc-err   {:error {:code -32602 :message "unknown tool"}}
           :empty     {}}
   :gen (gen/one-of [(gen/fmap (fn [v] {:result {:structuredContent v}}) (gen/map gen/keyword gen/small-integer))
                     (gen/fmap (fn [b] {:result {:isError b}}) gen/boolean)
                     (gen/return {:error {:code -32603}})])
   :pred (fn [o] (boolean? (:error? o)))
   :mutations [["tool errors pass" (fn [r] {:value (get-in r [:result :structuredContent]) :error? (boolean (:error r))})]
               ["rpc errors pass" (fn [r] {:value (get-in r [:result :structuredContent])
                                           :error? (boolean (get-in r [:result :isError]))})]]})

(deftest every-host-serves-with-the-same-arguments
  (is (= ["bb" "serve" "--dir" "x"] (check/serve-command :bb ["--dir" "x"])))
  (is (= ["cljw" "-A:cljw" "-m" "autonomous.cli" "serve" "--dir" "x"] (check/serve-command :cljw ["--dir" "x"])))
  (is (= ["clojure" "-M:serve" "--dir" "x"] (check/serve-command :jvm ["--dir" "x"])))
  (is (thrown? clojure.lang.ExceptionInfo (check/serve-command :lua []))))
