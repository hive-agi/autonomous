(ns autonomous.bridge.ws-strata-test
  "The websocket bridge's Promote stratum: the pure functions pulled out
   of hello-grant, send-request! and on-text!. Private helpers are reached
   through their vars; nothing here opens a socket."
  (:require [autonomous.bridge.ws :as ws]
            [clojure.test :refer [deftest is testing]]))

(def ^:private runtime-grant @#'ws/runtime-grant)
(def ^:private runtime-of @#'ws/runtime-of)
(def ^:private ordered @#'ws/ordered)
(def ^:private conn-named @#'ws/conn-named)
(def ^:private request-frame @#'ws/request-frame)
(def ^:private settled-answer @#'ws/settled-answer)
(def ^:private response-answer @#'ws/response-answer)
(def ^:private welcome-frame @#'ws/welcome-frame)
(def ^:private welcome-conn @#'ws/welcome-conn)

(deftest runtime-grant-needs-the-runtime-scope-itself
  (is (= {:actor "todo" :scopes [:runtime]}
         (:ok (runtime-grant {:actor "todo" :scopes [:runtime]}))))
  (testing ":admin does not stand in for :runtime"
    (is (= :auth/unauthorized
           (get-in (runtime-grant {:actor "todo" :scopes [:admin :read :write]})
                   [:error :type]))))
  (is (= :auth/unauthorized (get-in (runtime-grant {:actor "todo"}) [:error :type]))))

(deftest runtime-of-normalizes-the-proposed-row
  (is (= {:runtime/id "tab-1" :runtime/kind :browser :runtime/label "tab"}
         (runtime-of {:runtime/id "tab-1" :runtime/kind :browser :runtime/label "tab"}
                     (constantly "fresh"))))
  (testing "an invalid or absent id takes the fresh one"
    (is (= "fresh" (:runtime/id (runtime-of {:runtime/id "bad id!"} (constantly "fresh")))))
    (is (= "fresh" (:runtime/id (runtime-of {} (constantly "fresh"))))))
  (testing "a non-keyword kind is :unknown, a missing label is the default"
    (let [row (runtime-of {:runtime/kind "browser"} (constantly "fresh"))]
      (is (= :unknown (:runtime/kind row)))
      (is (= "websocket runtime" (:runtime/label row)))))
  (testing "a label is cut at 120 characters"
    (is (= 120 (count (:runtime/label (runtime-of {:runtime/label (apply str (repeat 300 "x"))}
                                                  (constantly "fresh"))))))))

(deftest ordered-and-conn-named-pick-by-age-then-id
  (let [conns {"b" {:runtime/id "b" :since 2} "a" {:runtime/id "a" :since 2}
               "z" {:runtime/id "z" :since 1}}
        rows  (ordered conns)]
    (is (= ["z" "a" "b"] (map :runtime/id rows)))
    (is (= {:runtime/id "b" :since 2} (:ok (conn-named rows "b"))))
    (is (= {:runtime/id "7" :since 1}
           (:ok (conn-named (ordered {"7" {:runtime/id "7" :since 1}}) 7)))
        "an integer id addresses the runtime of that name")
    (let [a (conn-named rows "nope")]
      (is (= :channel/unknown-runtime (get-in a [:error :type])))
      (is (= ["z" "a" "b"] (get-in a [:error :data :known]))))))

(deftest request-and-welcome-frames-name-what-they-carry
  (let [f (request-frame 7 {:op :ping})]
    (is (= :request (:ws/type f)))
    (is (= 7 (:id f)))
    (is (= "{:op :ping}" (:request f))))
  (is (= {:ws/type :welcome :target "todo" :runtime/id "tab-1"}
         (welcome-frame {:target-id "todo"} {:runtime/id "tab-1"}))))

(deftest welcome-conn-merges-the-grant-with-the-link
  (let [link (atom {})
        io   {:send! identity :close! identity}
        conn (welcome-conn {:runtime/id "r" :runtime/kind :node} link io 42)]
    (is (= {:runtime/id "r" :runtime/kind :node :since 42 :link link
            :send! identity :close! identity}
           conn))))

(deftest settled-answer-turns-a-silent-promise-into-a-timeout-refusal
  (let [conn {:runtime/id "r"}]
    (is (= {:ok :pong} (settled-answer {:ok :pong} conn 200)))
    (is (= {:pending "p"} (settled-answer {:pending "p"} conn 200)) "a pending passes through")
    (let [a (settled-answer :autonomous.bridge.ws/timeout conn 200)]
      (is (= :channel/timeout (get-in a [:error :type])))
      (is (= "runtime r did not answer within 200 ms" (get-in a [:error :message]))))))

(deftest response-answer-reads-edn-text-and-refuses-everything-else
  (is (= {:ok 1} (response-answer "{:ok 1}")))
  (is (= :channel/bad-answer (get-in (response-answer "(+ 1 2)") [:error :type])))
  (is (= :channel/bad-answer (get-in (response-answer "{:ok 1 :error 2}") [:error :type])))
  (is (= :channel/bad-answer (get-in (response-answer 42) [:error :type]))
      "an Answer must travel as EDN text"))

(deftest edn-reads-on-the-railway-and-frames-refuse-the-rest
  (let [read-edn @#'ws/read-edn-safely]
    (is (= {:ok {:a 1}} (read-edn "{:a 1}")))
    (is (= {:ok nil} (read-edn "nil")))
    (is (= :ws/not-edn (get-in (read-edn "{:a") [:error :type]))))
  (is (= {:ok {:ws/type :hello}} (ws/read-frame "{:ws/type :hello}")))
  (doseq [text ["{:a" "nil" "{:ws/type \"hello\"}" 42 (apply str (repeat 300000 " "))]]
    (is (= :ws/bad-frame (get-in (ws/read-frame text) [:error :type])))))
