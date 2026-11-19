(ns autonomous.bridge.http-test
  "The remote-MCP channel: its pure JSON-RPC codec, its SSE framing, and the
   channel contract against a stub IHttpClient (no network). A stub record,
   not with-redefs: the port is the seam.

   The stub is a small remote MCP server, honest about the parts that
   matter: initialize hands out a session owned by the caller's token,
   tools/list is filtered by that actor's scopes, another actor's session
   answers 404, and tools/call reports the actor it saw so attribution can
   be checked."
  (:require [autonomous.bridge.channel :as channel]
            [autonomous.bridge.client :as client]
            [autonomous.bridge.port :as port]
            [autonomous.bridge.rpc :as rpc]
            [autonomous.bridge.session :as s]
            [autonomous.domain.answer :as answer]
            [autonomous.mcp.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private target
  {:target/kind :http :target/id "todo" :url "http://remote.test/todo" :timeout-ms 2000})

(def ^:private tokens {"agent-a" "aut_a" "agent-b" "aut_b"})

(def ^:private tools
  "The remote's tools, by the scope each needs."
  [{:name "todo_add" :description "Add" :scopes #{:write} :inputSchema {:type "object"}}
   {:name "todo_list" :description "List" :scopes #{:read} :inputSchema {:type "object"}}])

(defn- bearer-of [req]
  (some-> (get-in req [:headers "authorization"]) (str/split #" ") last))

(defn- actor-of [req]
  (some (fn [[a tok]] (when (= tok (bearer-of req)) a)) tokens))

(defn- json-response [status body]
  {:ok {:status status :headers {} :body (json/write body)}})

(defn- result [msg body] (json-response 200 {:jsonrpc "2.0" :id (:id msg) :result body}))

(defn- visible-tools [scopes]
  (mapv #(dissoc % :scopes) (filter (fn [t] (some (:scopes t) scopes)) tools)))

(defn- on-post
  "Stub answer to one POST of `req` as `actor` over `state`."
  [state actor req]
  (let [msg (json/read-json (:body req))
        sid (get-in req [:headers "mcp-session-id"])]
    (case (:method msg)
      "initialize" (let [new-sid (str "sid-" actor)]
                     (swap! state assoc-in [:sessions new-sid] actor)
                     {:ok {:status 200 :headers {"mcp-session-id" new-sid}
                           :body (json/write {:jsonrpc "2.0" :id (:id msg)
                                              :result {:protocolVersion rpc/protocol-version}})}})
      "notifications/initialized" {:ok {:status 202 :headers {} :body ""}}
      "tools/call" (let [tool (get-in msg [:params :name])]
                     (swap! state update :calls conj {:actor actor :tool tool})
                     (result msg {:structuredContent {:tool tool :by actor
                                                      :args (get-in msg [:params :arguments])}}))
      "resources/list" (result msg {:resources []})
      (if (= actor (get-in @state [:sessions sid]))
        (result msg {:tools (visible-tools (get-in @state [:scopes actor]))})
        (json-response 404 {:error "not your session"})))))

(deftype StubClient [state stream]
  port/IHttpClient
  (-request! [_ req]
    (if-let [actor (actor-of req)]
      (on-post state actor req)
      (json-response 401 {:error "no token"})))
  (-open-stream! [_ _req sink]
    (reset! stream sink)
    {:ok {:close! (fn [] (reset! stream nil))}}))

(defn- open-stub
  "A stub client over fresh state, plus that state and its stream cell."
  []
  (let [state  (atom {:sessions {} :calls [] :scopes {"agent-a" #{:read :write} "agent-b" #{:read}}})
        stream (atom nil)]
    [(->StubClient state stream) state stream]))

(defn- as
  "The channel's answer as `actor` sends `req` with `scopes`."
  [ch actor scopes req]
  (port/-send ch (assoc req :actor actor :scopes scopes) nil))

(defn- manifest-as [ch actor scopes] (as ch actor scopes {:op :manifest}))

;; -----------------------------------------------------------------------------
;; Pure codec

(deftest a-call-reads-as-the-answer-it-stands-for
  (is (= {:ok {:a 1}} (rpc/answer-of {:result {:structuredContent {:a 1}}})))
  (is (= {:pending "p7"} (rpc/answer-of {:result {:structuredContent {:pending "p7"}}})))
  (is (= :channel/tool-failed
         (get-in (rpc/answer-of {:result {:isError true :content [{:type "text" :text "boom"}]}})
                 [:error :type])))
  (is (= :channel/rpc-error (get-in (rpc/answer-of {:error {:code -32602 :message "bad"}}) [:error :type])))
  (is (= :channel/bad-answer (get-in (rpc/answer-of {}) [:error :type])))
  (testing "a text-only success is still a value"
    (is (= {:ok "done"} (rpc/answer-of {:result {:content [{:type "text" :text "done"}]}})))))

(deftest sse-framing-is-per-message
  (is (= [{:jsonrpc "2.0" :method "notifications/tools/list_changed"}]
         (rpc/read-messages (str ": stream open\n\ndata: {\"jsonrpc\":\"2.0\","
                                 "\"method\":\"notifications/tools/list_changed\"}\n\n"))))
  (is (empty? (rpc/read-messages ": keepalive\n\n")))
  (is (= 1 (count (rpc/read-messages "data: {\"a\":1}\ndata: not json\n\n")))))

(deftest a-listing-becomes-a-manifest
  (let [m (rpc/manifest-of "todo" "http://r/todo" tools [{:uri "app://db" :mimeType "x"}])]
    (is (= ["todo_add" "todo_list"] (map :name (:tools m))))
    (is (= [{:uri "app://db" :name nil :description nil :mime "x"}]) (:resources m))
    (is (= 1 (:protocol m)))
    (testing "the version moves with the tools"
      (is (not= (:version m) (:version (rpc/manifest-of "todo" "http://r/todo" [] [])))))))

;; -----------------------------------------------------------------------------
;; Contract

(defn- auth-of [g] {"authorization" (str "Bearer " (tokens (:actor g)))})

(defn- ch
  "A channel over a fresh stub, plus the stub's state."
  []
  (let [[client state _] (open-stub)]
    [(channel/make (assoc target :identity auth-of :http/client client)) state]))

(deftest the-manifest-is-the-remote's-listing-for-the-agent
  (let [[c state] (ch)]
    (is (= ["todo_add" "todo_list"]
           (mapv :name (:tools (:ok (manifest-as c "agent-a" #{:read :write}))))))
    (testing "a read-only agent is listed only its own tools"
      (is (= ["todo_list"] (mapv :name (:tools (:ok (manifest-as c "agent-b" #{:read})))))))
    (testing "and a call goes over JSON-RPC attributed to that agent"
      (is (= {:ok {:by "agent-a" :tool "todo_add" :args {:text "milk"}}}
             (as c "agent-a" #{:write} {:op :call :tool "todo_add" :args {:text "milk"}})))
      (is (= [{:actor "agent-a" :tool "todo_add"}] (:calls @state))))))

(deftest an-agent-keeps-its-own-remote-session
  (let [[c _] (ch)]
    (manifest-as c "agent-a" #{:read})
    (is (= {:ok {:by "agent-b" :tool "todo_list" :args {}}}
           (as c "agent-b" #{:read} {:op :call :tool "todo_list"}))
        "agent-b's own session, not agent-a's")))

(deftest a-down-remote-refuses-and-hides-only-itself
  (let [[c _] (ch)
        dead  (channel/make {:target/kind :http :target/id "dead" :url "http://127.0.0.1:9"})]
    (is (= :channel/remote-refused (get-in (as c "nobody" #{:read} {:op :manifest}) [:error :type]))
        "an unknown token is refused by the remote, never thrown")
    (is (= :channel/transport-failed (get-in (manifest-as dead "nobody" #{:read}) [:error :type])))
    (testing "a listing cached before it went down still answers, though"
      (is (= :channel/transport-failed (get-in (as dead "nobody" #{:write} {:op :call :tool "x"})
                                               [:error :type]))))))

(deftest a-token-rides-a-header-and-never-a-url
  (let [[client _ _] (open-stub)
        seen (atom [])
        spy  (reify port/IHttpClient
               (-request! [_ req] (swap! seen conj req) (port/-request! client req))
               (-open-stream! [_ req sink] (swap! seen conj req) (port/-open-stream! client req sink)))
        c    (channel/make (assoc target :identity (constantly {"authorization" "Bearer aut_secret"})
                              :http/client spy))]
    (manifest-as c "agent-a" #{:read})
    (is (seq @seen))
    (is (every? #(not (str/includes? (:url %) "aut_")) @seen) "no token in any url")
    (is (every? #(str/includes? (get-in % [:headers "authorization"]) "aut_secret") @seen)
        "the token rides a header")))

(deftest a-remote-make-builds-the-channel
  (is (satisfies? port/IRuntimeChannel (channel/make {:target/kind :http :target/id "mk"
                                                      :url "http://127.0.0.1:9"}))))
