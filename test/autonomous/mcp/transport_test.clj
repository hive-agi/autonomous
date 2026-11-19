(ns autonomous.mcp.transport-test
  "MCP end to end over the in-process channel: stdio framing, and the HTTP
   security pipeline (`route` is pure, so no socket is opened), plus one
   real socket round trip through the adapter this runtime starts (the JDK
   server on the JVM, http-kit on babashka)."
  (:require [autonomous.bridge.auth :as auth]
            [autonomous.bridge.client :as client]
            [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.local :as local]
            [autonomous.core :as a]
            [autonomous.mcp.http :as http]
            [autonomous.mcp.json :as json]
            [autonomous.mcp.server :as server]
            [autonomous.mcp.stdio :as stdio]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [autonomous.mcp.http.start :as http-start])
  (:import (java.io StringReader StringWriter)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(defn- install-app! []
  (a/reset-registry!)
  (a/set-app-info! {:name "todo" :description "A todo list"})
  (a/reg-tool :todo/add {:description "Add a todo" :input [:map [:text [:string {:min 1}]]]
                         :handler (fn [{:keys [text]} grant] {:added text :by (:actor grant)})})
  (a/reg-tool :todo/wipe {:description "Delete all" :scopes [:admin] :handler (fn [_ _] :wiped)}))

(use-fixtures :each (fn [t] (install-app!) (t)))

(defn- srv []
  (server/server (client/context {:target {:target/kind :local :target/id "todo"}
                                  :channel (local/channel)
                                  :ledger (ledger/open {:ledger/kind :memory})})))

(defn- rpc [id method params] (cond-> {:jsonrpc "2.0" :method method} id (assoc :id id) params (assoc :params params)))

;; -----------------------------------------------------------------------------
;; stdio

(defn- run-stdio [opts msgs]
  (let [in  (atom (map #(if (string? %) % (json/write %)) msgs))
        out (atom [])]
    (stdio/serve! (srv) (merge {:watch-ms 0} opts)
                  (fn [] (let [[l & more] @in] (reset! in more) l))
                  (fn [s] (swap! out conj s)))
    (mapv json/read-json @out)))

(deftest stdio-speaks-mcp-line-by-line
  (let [[init tools call wipe bad] (run-stdio {:actor "agent-1" :scopes #{:read :write}}
                                              [(rpc 1 "initialize" {:protocolVersion "2025-03-26"})
                                               (rpc nil "notifications/initialized" nil)
                                               (rpc 2 "tools/list" nil)
                                               (rpc 3 "tools/call" {:name "todo_add" :arguments {:text "milk"}})
                                               (rpc 4 "tools/call" {:name "todo_wipe" :arguments {}})
                                               "{not json"])]
    (is (= "2025-03-26" (get-in init [:result :protocolVersion])))
    (is (str/starts-with? (get-in init [:result :instructions]) "todo: A todo list"))
    (testing "the admin tool is not listed for a read/write grant"
      (is (some #{"todo_add"} (map :name (get-in tools [:result :tools]))))
      (is (not (some #{"todo_wipe"} (map :name (get-in tools [:result :tools]))))))
    (is (= {:added "milk" :by "agent-1"} (get-in call [:result :structuredContent])))
    (is (true? (get-in wipe [:result :isError])))
    (is (= -32700 (get-in bad [:error :code])))))

;; -----------------------------------------------------------------------------
;; HTTP security pipeline

(def tok-a "aut_agent_a_token")
(def tok-b "aut_agent_b_token")
(def cfg {:tokens [(auth/token-entry tok-a "agent-a" [:read :write])
                   (auth/token-entry tok-b "agent-b" [:read])]
          :allowed-origins ["https://ok.example"]})

(defn- post [s st headers body]
  (http/route s st cfg {:method "POST" :path "/mcp" :headers headers :body (json/write body)}))

(defn- bearer [t] {"authorization" (str "Bearer " t)})

(deftest http-refuses-before-it-acts
  (let [s (srv) st (atom {:sessions {}})]
    (is (some? (http/exposure-refusal "0.0.0.0" [])) "a public bind needs tokens")
    (is (nil? (http/exposure-refusal "127.0.0.1" [])))
    (is (nil? (http/exposure-refusal "0.0.0.0" (:tokens cfg))))
    (is (= 401 (:status (post s st {} (rpc 1 "initialize" {})))))
    (is (= 401 (:status (post s st (bearer "aut_wrong") (rpc 1 "initialize" {})))))
    (is (= 403 (:status (post s st (assoc (bearer tok-a) "origin" "https://evil.example") (rpc 1 "initialize" {})))))
    (is (= 200 (:status (post s st (assoc (bearer tok-a) "origin" "https://ok.example") (rpc 1 "initialize" {})))))
    (is (= 405 (:status (http/route s st cfg {:method "GET" :path "/mcp" :headers (bearer tok-a)}))))
    (is (= 200 (:status (http/route s st cfg {:method "GET" :path "/health" :headers {}}))))))

(deftest http-sessions-belong-to-their-actor
  (let [s (srv) st (atom {:sessions {}})
        init (post s st (bearer tok-a) (rpc 1 "initialize" {}))
        sid (get-in init [:headers "Mcp-Session-Id"])
        call (fn [tok args] (post s st (assoc (bearer tok) "mcp-session-id" sid)
                                  (rpc 2 "tools/call" {:name "todo_add" :arguments args})))]
    (is (string? sid))
    (is (= {:added "x" :by "agent-a"} (get-in (json/read-json (:body (call tok-a {:text "x"}))) [:result :structuredContent])))
    (is (= 404 (:status (call tok-b {:text "x"}))) "another actor cannot ride the session")
    (is (= 404 (:status (post s st (bearer tok-a) (rpc 3 "tools/list" nil)))) "no session id, no session")
    (is (= 202 (:status (post s st (assoc (bearer tok-a) "mcp-session-id" sid) (rpc nil "notifications/initialized" nil)))))
    (testing "a read-only token cannot write even through its own session"
      (let [sid-b (get-in (post s st (bearer tok-b) (rpc 1 "initialize" {})) [:headers "Mcp-Session-Id"])
            r (json/read-json (:body (post s st (assoc (bearer tok-b) "mcp-session-id" sid-b)
                                           (rpc 2 "tools/call" {:name "todo_add" :arguments {:text "x"}}))))]
        (is (true? (get-in r [:result :isError])))))
    (is (= 204 (:status (http/route s st cfg {:method "DELETE" :path "/mcp" :headers (assoc (bearer tok-a) "mcp-session-id" sid)}))))
    (is (= 404 (:status (call tok-a {:text "x"}))) "a deleted session is gone")))

(def ^:private prune* http/prune-sessions)

(defn- table [& sid-times]
  (let [ps (partition 2 sid-times)]
    {:sessions (into {} (map (fn [[sid _]] [sid {:session/id sid}])) ps)
     :touched  (into {} (map vec) ps)}))

(deftrifecta prune-sessions-trifecta
  autonomous.mcp.http/prune-sessions
  {:golden-path "test/golden/mcp/prune-sessions.edn"
   :apply? true
   :xf (fn [st] (into (sorted-map) (:touched st)))
   :cases {:all-fresh   [(table "a" 90 "b" 95) 100 50 10]
           :one-idle    [(table "a" 10 "b" 95) 100 50 10]
           :exact-idle  [(table "a" 50) 100 50 10]
           :over-cap    [(table "a" 91 "b" 99 "c" 95) 100 50 2]
           :untouched   [{:sessions {"x" {:session/id "x"}} :touched {}} 100 50 10]
           :empty       [{:sessions {} :touched {}} 100 50 10]}
   :gen (gen/let [sids (gen/vector-distinct (gen/elements ["a" "b" "c" "d" "e"]))
                  ts   (gen/vector (gen/choose 0 100) (count sids))
                  cap  (gen/choose 1 4)]
          (gen/return [(apply table (interleave sids ts)) 100 40 cap]))
   :pred (fn [st] (= (set (keys (:sessions st))) (set (keys (:touched st)))))
   :mutations [["never forgets" (fn [st _ _ _] st)]
               ["no cap" (fn [st now idle _] (prune* st now idle Long/MAX_VALUE))]
               ["keeps the oldest" (fn [st now idle cap]
                                     (let [p (prune* st now idle Long/MAX_VALUE)
                                           k (into {} (take cap) (sort-by val (:touched p)))]
                                       (assoc p :touched k :sessions (select-keys (:sessions p) (keys k)))))]
               ["idle is inclusive" (fn [st now idle cap] (prune* st now (inc idle) cap))]]})

(deftest http-idle-sessions-are-forgotten
  (let [s (srv) st (atom {:sessions {}})
        now (atom 0)
        cfg* (assoc cfg :clock #(deref now) :session-idle-ms 1000)
        post* (fn [headers body] (http/route s st cfg* {:method "POST" :path "/mcp" :headers headers
                                                         :body (json/write body)}))
        sid (get-in (post* (bearer tok-a) (rpc 1 "initialize" {})) [:headers "Mcp-Session-Id"])
        list! #(:status (post* (assoc (bearer tok-a) "mcp-session-id" sid) (rpc 2 "tools/list" nil)))]
    (reset! now 900)
    (is (= 200 (list!)) "use keeps a session alive")
    (reset! now 1800)
    (is (= 200 (list!)) "idle time counts from the last use")
    (reset! now 2900)
    (is (= 404 (list!)) "an idle session is gone")
    (is (empty? (:sessions @st)))))

(deftest http-over-a-real-socket
  (let [h (:ok (http-start/start! (srv) (assoc cfg :bind "127.0.0.1" :port 0)))
        client (HttpClient/newHttpClient)
        send (fn [headers body]
               (let [b (reduce (fn [b [k v]] (.header b k v))
                               (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (:port h) "/mcp")))
                                   (.POST (HttpRequest$BodyPublishers/ofString (json/write body))))
                               headers)]
                 (.send client (.build b) (HttpResponse$BodyHandlers/ofString))))]
    (try
      (let [init (send {"Authorization" (str "Bearer " tok-a)} (rpc 1 "initialize" {}))
            sid (.orElse (.firstValue (.headers init) "mcp-session-id") nil)
            call (send {"Authorization" (str "Bearer " tok-a) "Mcp-Session-Id" sid}
                       (rpc 2 "tools/call" {:name "todo_add" :arguments {:text "over the wire"}}))]
        (is (= 200 (.statusCode init)))
        (is (= "over the wire" (get-in (json/read-json (.body call)) [:result :structuredContent :added])))
        (testing "a taken port is a refusal, not a crash"
          (is (= :http/bind-failed
                 (get-in (http-start/start! (srv) (assoc cfg :bind "127.0.0.1" :port (:port h)))
                         [:error :type])))))
      (finally ((:stop! h))))))
