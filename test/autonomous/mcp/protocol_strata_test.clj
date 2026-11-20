(ns autonomous.mcp.protocol-strata-test
  "Focused tests for the pure strata of MCP: JSON-RPC message predicates,
   the per-variant CallToolResults, and the server's response steps."
  (:require [autonomous.mcp.protocol :as p]
            [autonomous.mcp.server :as server]
            [clojure.test :refer [deftest is testing]]))

(deftest requests-need-version-two-and-a-string-method
  (is (p/request? {:jsonrpc "2.0" :method "ping" :id 1}))
  (is (p/request? {:jsonrpc "2.0" :method "note"}))
  (is (not (p/request? {:jsonrpc "1.0" :method "ping"})))
  (is (not (p/request? {:jsonrpc "2.0" :method :ping})))
  (is (not (p/request? "ping"))))

(deftest refusal-errors-are-invalid-params-with-the-refusal-message
  (is (= {:jsonrpc "2.0" :id 3 :error {:code -32602 :message "no" :data {:k 1}}}
         (p/refusal-error 3 {:error {:type :x :message "no"}} {:k 1})))
  (is (not (contains? (:error (p/refusal-error 3 {:error {:message "no"}} nil)) :data))))

(deftest each-answer-variant-has-its-own-result
  (let [ok-result #'p/ok-result pending-result #'p/pending-result]
    (is (= {:result [1]} (:structuredContent (ok-result [1]))))
    (is (= {:a 1} (:structuredContent (ok-result {:a 1}))))
    (is (= {:pending "p1"} (:structuredContent (pending-result "p1"))))
    (is (re-find #"id=p1$" (get-in (pending-result "p1") [:content 0 :text])))))

(deftest instructions-lead-with-the-app-description-only-when-given
  (let [instructions #'p/instructions]
    (is (= p/base-instructions (instructions {:name "todo"})))
    (is (= (str "todo: lists\n\n" p/base-instructions) (instructions {:name "todo" :description "lists"})))))

(deftest tools-call-unknown-tool-is-a-protocol-error
  (let [call-response #'server/call-response]
    (testing "an unknown tool is a JSON-RPC error with the refusal data"
      (let [{:keys [response session]} (call-response :s 7 {:error {:type :unknown-tool :message "no t" :data {:known []}}})]
        (is (= :s session))
        (is (= {:code -32602 :message "no t" :data {:known []}} (:error response)))))
    (testing "any other refusal is a CallToolResult flagged as an error"
      (is (true? (get-in (call-response :s 7 {:error {:type :forbidden :message "x"}}) [:response :result :isError]))))))

(deftest resource-reads-answer-contents-or-the-refusal
  (let [read-response #'server/read-response]
    (is (= "u" (get-in (read-response :s 1 "u" {:ok {:value 1}}) [:response :result :contents 0 :uri])))
    (is (= {:type :nope :message "m"}
           (get-in (read-response :s 1 "u" {:error {:type :nope :message "m"}}) [:response :error :data])))))

(deftest single-fingerprints-forget-a-target-that-is-down
  (let [single #'server/single-fingerprint]
    (is (= ["v1" ["a"]] (single [[:t :up "v1" ["a"]]])))
    (is (= [nil []] (single [[:t :down "v1" ["a"]]])))))

(deftest internal-errors-answer-requests-and-drop-notifications
  (let [internal-error #'server/internal-error
        boom           (ex-info "boom" {})]
    (is (= {:code -32603 :message "boom"} (get-in (internal-error :s {:id 1 :method "m"} boom) [:response :error])))
    (is (= {:response nil :session :s} (internal-error :s {:method "m"} boom)))))
