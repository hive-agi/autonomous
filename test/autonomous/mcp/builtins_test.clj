(ns autonomous.mcp.builtins-test
  (:require [autonomous.bridge.fleet :as fleet]
            [autonomous.bridge.port :as port]
            [autonomous.bridge.session :as session]
            [autonomous.mcp.builtins :as builtins]
            [clojure.test :refer [deftest is testing]]))

(defrecord Directory [read-rows]
  port/IRuntimeDirectory
  (-runtimes [_] (read-rows)))

(defn- test-fleet [read-rows]
  (fleet/make [["app" {:channel (->Directory read-rows)}] ["other" {}]] {:mode :fleet}))

(def ^:private initial
  (session/open-session "s" {:actor "agent" :scopes #{:read}}))

(deftest runtime-selection-keeps-the-original-id
  (let [match #'builtins/matching-runtime
        rows [{:runtime/id 7} {:runtime/id "tab"} {:runtime/id 7}]]
    (is (= {:ok 7} (match rows "7")))
    (is (= {:ok "tab"} (match rows "tab")))
    (let [a (match rows "missing")]
      (is (= :unknown-runtime (get-in a [:error :type])))
      (is (= #{7 "tab"} (set (get-in a [:error :data :known])))))))

(deftest applying-a-pin-answers-with-the-updated-session-only-on-success
  (let [result #'builtins/pin-result
        prior (session/attach-on :fleet initial "other" "keep")
        {:keys [session answer]} (result :fleet prior {:ok ["app" 7]})]
    (is (= {:ok {:attached 7}} answer))
    (is (= {"app" 7 "other" "keep"} (:session/runtimes session)))
    (is (= {"app" nil "other" "keep"} (:session/runtimes (:session (result :fleet session {:ok ["app" nil]})))))
    (is (= {:session prior :answer {:error {:type :example}}}
           (result :fleet prior {:error {:type :example}})))))

(deftest attaching-short-circuits-at-the-target-and-directory
  (let [attach (:run builtins/attach)
        directory-refusal {:error {:type :directory/down}}
        f (test-fleet (constantly directory-refusal))]
    (is (= {:session initial :answer directory-refusal} (attach f initial {:target "app" :runtime_id "tab"})))
    (is (= :target/unknown (get-in (attach f initial {:target "missing" :runtime_id "tab"}) [:answer :error :type])))
    (testing "a directory answering neither Ok nor a refusal knows no runtime"
      (doseq [rows [nil {:pending "p"}]]
        (let [{:keys [answer session]} (attach (test-fleet (constantly rows)) initial
                                               {:target "app" :runtime_id "tab"})]
          (is (= :unknown-runtime (get-in answer [:error :type])))
          (is (= [] (get-in answer [:error :data :known])))
          (is (= initial session)))))
    (testing "unpinning does not read the directory"
      (let [f (test-fleet #(throw (ex-info "directory should not run" {})))]
        (is (= {:ok {:attached nil}} (:answer (attach f initial {:target "app"}))))))))

(deftest ledger-filters-only-validate-an-explicit-target
  (let [query #'builtins/checked-ledger-query
        f (test-fleet (constantly {:ok []}))]
    (is (= {:ok {:actor "someone" :since 0}} (query f {:actor "someone" :since 0 :target nil})))
    (is (= {:ok {:target "app" :limit 1}} (query f {:target "app" :limit 1 :tool nil})))
    (is (= :target/unknown (get-in (query f {:target "missing"}) [:error :type])))))
