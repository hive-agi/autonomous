(ns autonomous.mcp.transport-test
  "MCP end to end over the in-process channel: stdio framing, and the HTTP
   security pipeline (`route` is pure, so no socket is opened), plus one
   real socket round trip through the adapter this runtime starts (the JDK
   server on the JVM, http-kit on babashka)."
  (:require [autonomous.bridge.auth :as auth]
            [autonomous.bridge.client :as client]
            [autonomous.bridge.fleet :as bf]
            [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.port :as port]
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
            [autonomous.cli :as cli]
            [autonomous.config :as config]
            [autonomous.mcp.http.start :as http-start])
  (:import (java.io BufferedReader InputStreamReader StringReader StringWriter)
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
    (is (= 405 (:status (http/route s st cfg {:method "PUT" :path "/mcp" :headers (bearer tok-a)}))))
    (is (= 406 (:status (http/route s st cfg {:method "GET" :path "/mcp" :headers (bearer tok-a)})))
        "a GET that does not accept an event stream")
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

;; -----------------------------------------------------------------------------
;; HTTP without tokens: the configured actor

(deftest http-without-tokens-acts-as-the-configured-actor
  (let [promoted (fn [env opts]
                   (:ok (config/promote {} (config/read-env env) (merge {:http "127.0.0.1:0" :port "1"} opts) nil)))
        actor-of (fn [cfg]
                   (let [s (srv) st (http/new-state) hcfg (cli/http-config cfg)
                         post* (fn [headers body] (http/route s st hcfg {:method "POST" :path "/mcp" :headers headers
                                                                         :body (json/write body)}))
                         sid (get-in (post* {} (rpc 1 "initialize" {})) [:headers "Mcp-Session-Id"])]
                     (get-in (json/read-json (:body (post* {"mcp-session-id" sid}
                                                           (rpc 2 "tools/call" {:name "todo_add" :arguments {:text "x"}}))))
                             [:result :structuredContent :by])))]
    (is (= "claude" (actor-of (promoted {"AUTONOMOUS_ACTOR" "claude"} {}))) "AUTONOMOUS_ACTOR names the caller")
    (is (= "ops" (actor-of (promoted {"AUTONOMOUS_ACTOR" "claude"} {:actor "ops"}))) "--actor wins over the env")
    (is (= "local" (actor-of (promoted {} {}))) "unconfigured, the caller is local")
    (is (= {:actor "claude" :scopes #{:read :write}}
           (cli/anonymous-grant (promoted {"AUTONOMOUS_ACTOR" "claude"} {:scopes "read"})))
        "the anonymous grant keeps read and write")))

(deftest the-http-banner-quotes-the-anonymous-actor-as-shown
  (let [promoted (fn [env opts]
                   (:ok (config/promote {} (config/read-env env) (merge {:http "127.0.0.1:0" :port "1"} opts) nil)))
        banner   #(cli/http-banner (promoted {"AUTONOMOUS_ACTOR" %} {}) 8080)]
    (is (str/ends-with? (banner "claude") "/mcp (loopback, no tokens, acting as \"claude\")"))
    (is (str/includes? (banner "ops\u202e\u001b[2J") "acting as \"ops\\u202e\\u001b[2J\")")
        "a bidi control and an ESC in the configured actor are escaped")
    (is (every? #(<= 0x20 (int %) 0x7E) (banner "ops\u202e\u001b[2J")))
    (is (str/includes? (banner "ops (+9 chars)") "acting as \"ops (+9 chars)\")"))
    (is (str/ends-with? (cli/http-banner (promoted {} {}) 8080) "acting as \"local\")")))
  (is (str/ends-with? (cli/http-banner {:tokens {"h" {}}} 8080) "/mcp (bearer tokens required)")))

;; -----------------------------------------------------------------------------
;; HTTP server stream: tools/list_changed pushed per session

(defrecord StubChannel [respond]
  port/IRuntimeChannel
  (-send [_ request runtime-id] (respond request runtime-id))
  port/IRuntimeDirectory
  (-runtimes [_] {:ok [{:runtime/id 1}]}))

(defn- stub-app [app tool]
  (fn [{:keys [op]} _]
    (case op
      :manifest {:ok {:protocol 1 :version 1 :app {:name app :description app}
                      :tools [{:name tool :description tool :input-schema {:type "object"}}]
                      :resources []}}
      {:ok {:app app}})))

(defn- flaky-fleet
  "A fleet server of an app that is always up and one that is up while
   `up?` holds."
  [up?]
  (let [l   (ledger/open {:ledger/kind :memory})
        api (stub-app "api" "refund")
        mem (fn [tid respond]
              [tid (client/context {:target {:target/id tid} :channel (->StubChannel respond)
                                    :ledger l :drain :inline :call-timeout-ms 100})])]
    (server/fleet-server
     (bf/make [(mem "todo" (stub-app "todo" "todo_add"))
               (mem "api" (fn [req rt] (if @up? (api req rt)
                                           {:error {:type :channel/connect-failed :message "down"}})))]
              {:probe-ms 150}))))

(defn- sink [] (let [out (atom []) closed (atom false)]
                 {:out out :closed closed
                  :sink {:send! (fn [t] (when @closed (throw (ex-info "closed" {}))) (swap! out conj t))
                         :close! (fn [] (reset! closed true))}}))

(defn- pushed [{:keys [out]}] (count (filter #(str/includes? % "list_changed") @out)))

(deftest http-streams-push-list-changed-to-their-owner
  (let [up?  (atom false)
        s    (flaky-fleet up?)
        st   (http/new-state)
        cfg* (assoc cfg :clock (constantly 0) :max-streams 2)
        req  (fn [method tok sid & [accept]]
               (http/route s st cfg* {:method method :path "/mcp"
                                      :headers (cond-> (bearer tok)
                                                 sid    (assoc "mcp-session-id" sid)
                                                 accept (assoc "accept" "text/event-stream"))
                                      :body (when (= "POST" method) (json/write (rpc 1 "initialize" {})))}))
        sid  (get-in (req "POST" tok-a nil) [:headers "Mcp-Session-Id"])
        open (req "GET" tok-a sid :sse)
        a    (sink)
        id   (http/attach-stream! st (:stream open) (:sink a))]
    (testing "a stream is the session owner's"
      (is (= 404 (:status (req "GET" tok-a "no-such" :sse))))
      (is (= 404 (:status (req "GET" tok-b sid :sse))) "another actor cannot listen on the session")
      (is (= 200 (:status open)))
      (is (= "text/event-stream" (get-in open [:headers "Content-Type"])))
      (is (string? id))
      (is (= [http/sse-open] @(:out a))))
    (testing "nothing is pushed while the fleet is steady"
      (http/push-tick! s st cfg*)
      (is (zero? (pushed a))))
    (testing "an app coming back, then going down, is pushed once each"
      (reset! up? true)
      (http/push-tick! s st cfg*)
      (is (= 1 (pushed a)))
      (http/push-tick! s st cfg*)
      (is (= 1 (pushed a)) "no repeat while nothing moves")
      (reset! up? false)
      (http/push-tick! s st cfg*)
      (is (= 2 (pushed a)))
      (is (= {:jsonrpc "2.0" :method "notifications/tools/list_changed"}
             (json/read-json (subs (last @(:out a)) (count "data: "))))))
    (testing "a quiet stream gets a keepalive comment"
      (http/push-tick! s st (assoc cfg* :clock (constantly 20000)))
      (is (= http/sse-keepalive (last @(:out a)))))
    (testing "streams are bounded: one per session, :max-streams in all"
      (let [b (sink)]
        (http/attach-stream! st (:stream (req "GET" tok-a sid :sse)) (:sink b))
        (is @(:closed a) "a new stream of the session replaces the old one")
        (let [sid2 (get-in (req "POST" tok-a nil) [:headers "Mcp-Session-Id"])
              sid3 (get-in (req "POST" tok-a nil) [:headers "Mcp-Session-Id"])
              c    (sink)]
          (http/attach-stream! st (:stream (req "GET" tok-a sid2 :sse)) (:sink c))
          (is (= 503 (:status (req "GET" tok-a sid3 :sse))))
          (testing "a stream whose client went away is dropped"
            (reset! (:closed c) true)
            (reset! up? true)
            (http/push-tick! s st cfg*)
            (is (not (contains? (:streams @st) sid2)))
            (is (= 1 (pushed b)))))
        (testing "a stream ends with its session"
          (is (= 204 (:status (req "DELETE" tok-a sid))))
          (is @(:closed b))
          (is (not (contains? (:streams @st) sid))))))))

(def ^:private peer-tokens
  "Two agents holding the same scopes, so only their actor tells them apart."
  {"agent-c" "aut_agent_c_token" "agent-d" "aut_agent_d_token"})

(def ^:private peer-cfg
  {:tokens (mapv (fn [[actor t]] (auth/token-entry t actor [:read :write])) peer-tokens)
   :clock  (constantly 0)})

(defn- per-actor-fleet
  "A fleet server of one app whose tools are (tools-of actor) for each caller,
   as a remote app that filters its listing per agent does."
  [tools-of]
  (let [respond (fn [{:keys [op actor]} _]
                  (if (= :manifest op)
                    {:ok {:protocol 1 :version 1 :app {:name "todo" :description "todo"}
                          :tools (mapv (fn [t] {:name t :description t :input-schema {:type "object"}})
                                       (tools-of actor))
                          :resources []}}
                    {:ok {}}))]
    (server/fleet-server
     (bf/make [["todo" (client/context {:target {:target/id "todo"} :channel (->StubChannel respond)
                                        :ledger (ledger/open {:ledger/kind :memory})
                                        :drain :inline :call-timeout-ms 100})]]
              {:mode :fleet :probe-ms 150}))))

(defn- open-peer-stream!
  "A sink holding `actor`'s event stream on server `s` with state `st`."
  [s st actor]
  (let [h    (bearer (get peer-tokens actor))
        sid  (get-in (http/route s st peer-cfg {:method "POST" :path "/mcp" :headers h
                                                :body (json/write (rpc 1 "initialize" {}))})
                     [:headers "Mcp-Session-Id"])
        open (http/route s st peer-cfg {:method "GET" :path "/mcp"
                                        :headers (assoc h "mcp-session-id" sid "accept" "text/event-stream")})
        k    (sink)]
    (http/attach-stream! st (:stream open) (:sink k))
    k))

(deftest http-streams-push-list-changed-per-actor
  (let [tools (atom (zipmap (keys peer-tokens) (repeat ["todo_add"])))
        s     (per-actor-fleet #(get @tools %))
        st    (http/new-state)
        sinks (into {} (map (fn [actor] [actor (open-peer-stream! s st actor)])) (keys peer-tokens))
        counts #(update-vals sinks pushed)]
    (doseq [actor (keys peer-tokens)]
      (testing (str "only " actor " is told its own tools moved, though the scopes are the same")
        (let [before (counts)]
          (swap! tools update actor conj "todo_more")
          (http/push-tick! s st peer-cfg)
          (is (= (update before actor inc) (counts))))))))

(deftest http-is-a-ring-handler
  (let [s   (srv)
        st  (http/new-state)
        h   (http/ring-handler s st cfg)
        ring-post (fn [headers msg] (h {:request-method :post :uri "/mcp" :headers headers :body (json/write msg)}))
        init (ring-post (bearer tok-a) (rpc 1 "initialize" {}))
        sid  (get-in init [:headers "Mcp-Session-Id"])
        get* (fn [handler] (handler {:request-method :get :uri "/mcp"
                                     :headers (assoc (bearer tok-a) "mcp-session-id" sid "accept" "text/event-stream")}))]
    (is (= 200 (:status init)))
    (is (= "agent-a" (get-in (json/read-json (:body (ring-post (assoc (bearer tok-a) "mcp-session-id" sid)
                                                               (rpc 2 "tools/call" {:name "todo_add" :arguments {:text "r"}}))))
                             [:result :structuredContent :by])))
    (is (= 401 (:status (ring-post {} (rpc 1 "initialize" {})))))
    (is (= 405 (:status (get* h))) "no held connection on offer: request/response only")
    (is (= {:held sid} (get* (http/ring-handler s st cfg {:stream-response (fn [_ resp] {:held (get-in resp [:stream :session])})})))
        "a host that can hold a connection gets the stream descriptor")))

(deftest http-stream-over-a-real-socket
  (let [up?    (atom false)
        h      (:ok (http-start/start! (flaky-fleet up?) (assoc cfg :bind "127.0.0.1" :port 0 :watch-ms 30)))
        client (HttpClient/newHttpClient)
        uri    (URI. (str "http://127.0.0.1:" (:port h) "/mcp"))
        auth   (str "Bearer " tok-a)]
    (try
      (let [init (.send client (-> (HttpRequest/newBuilder uri) (.header "Authorization" auth)
                                   (.POST (HttpRequest$BodyPublishers/ofString (json/write (rpc 1 "initialize" {}))))
                                   (.build))
                        (HttpResponse$BodyHandlers/ofString))
            sid  (.orElse (.firstValue (.headers init) "mcp-session-id") nil)
            resp (.send client (-> (HttpRequest/newBuilder uri) (.header "Authorization" auth)
                                   (.header "Mcp-Session-Id" sid) (.header "Accept" "text/event-stream")
                                   (.GET) (.build))
                        (HttpResponse$BodyHandlers/ofInputStream))
            rdr  (BufferedReader. (InputStreamReader. (.body resp) "UTF-8"))
            heard (future (loop [] (when-let [l (.readLine rdr)]
                                     (if (str/includes? l "list_changed") l (recur)))))]
        (is (= 200 (.statusCode resp)))
        (is (str/starts-with? (.orElse (.firstValue (.headers resp) "content-type") "") "text/event-stream"))
        (Thread/sleep 100)
        (reset! up? true)
        (let [line (deref heard 5000 nil)]
          (is (some? line) "the app coming back is pushed on the stream")
          (is (= "notifications/tools/list_changed" (:method (json/read-json (subs (str line) (count "data: "))))))))
      (finally ((:stop! h))))))
