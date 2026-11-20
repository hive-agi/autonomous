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
            [clojure.test :refer [deftest is testing]]
            [autonomous.bridge.http :as http]
            [autonomous.bridge.fleet :as bf]
            [autonomous.mcp.server :as server]
            [autonomous.bridge.schema :as bs]
            [autonomous.bridge.url :as url]
            [autonomous.bridge.http.jdk]
            [autonomous.cli :as cli]
            [autonomous.config :as config]
            [malli.core :as m]))

(def ^:private remote-url "https://remote.test/todo")

(def ^:private target
  {:target/kind :http :target/id "todo" :url remote-url :timeout-ms 2000})

(def ^:private leaked
  "A secret someone pasted into a url; it must never be shown again."
  "hunter2")

(def ^:private leaky-url
  (str "https://ops:" leaked "@remote.test/todo?api_key=" leaked "#" leaked))

(def ^:private plain-remote-url
  "Plain http to a host other than this one: no bearer may ride it."
  "http://remote.test/todo")

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

(deftype StubClient [state streams]
  port/IHttpClient
  (-request! [_ req]
    (if-let [actor (actor-of req)]
      (on-post state actor req)
      (json-response 401 {:error "no token"})))
  (-open-stream! [_ req sink]
    (let [actor (actor-of req)]
      (swap! streams assoc actor sink)
      {:ok {:close! (fn [] (swap! streams dissoc actor))}})))

(defn- open-stub
  "A stub client over fresh state, plus that state and its actor stream cells."
  []
  (let [state   (atom {:sessions {} :calls [] :scopes {"agent-a" #{:read :write} "agent-b" #{:read}}})
        streams (atom {})]
    [(->StubClient state streams) state streams]))

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
    (is (= [{:uri "app://db" :name nil :description nil :mime "x"}] (:resources m)))
    (is (= 1 (:protocol m)))
    (testing "the version moves with the tools"
      (is (not= (:version m) (:version (rpc/manifest-of "todo" "http://r/todo" [] [])))))))

;; -----------------------------------------------------------------------------
;; Contract

(defrecord Environment [values]
  port/ICredentialSource
  (-resolve-token! [_ {:keys [token-env]}] (answer/ok (get values token-env))))

(defn- source []
  (->Environment {"REMOTE_AGENT_A" "aut_a" "REMOTE_AGENT_B" "aut_b"}))

(defn- credential-policy []
  {"agent-a" {:token-env "REMOTE_AGENT_A"}
   "agent-b" {:token-env "REMOTE_AGENT_B"}})

(defn- ch
  "A channel over a fresh stub, plus the stub's state and actor streams."
  []
  (let [[client state streams] (open-stub)]
    [(channel/make (assoc target :credentials (credential-policy)
                          :credential-source (source) :http/client client))
     state streams]))

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

(def ^:private wrong-env "REMOTE_INTRUDER")

(def ^:private wrong-token
  "A token the remote never issued."
  "aut_wrong")

(deftest a-down-remote-refuses-and-hides-only-itself
  (let [[client _ _] (open-stub)
        c     (channel/make (assoc target
                                   :credentials (assoc (credential-policy) "intruder" {:token-env wrong-env})
                                   :credential-source (->Environment {wrong-env wrong-token})
                                   :http/client client))
        dead  (channel/make {:target/kind :http :target/id "dead" :url "http://127.0.0.1:9"})]
    (is (= :channel/remote-refused (get-in (as c "intruder" #{:read} {:op :manifest}) [:error :type]))
        "an unknown token is refused by the remote, never thrown")
    (is (= :channel/transport-failed (get-in (manifest-as dead "nobody" #{:read}) [:error :type])))
    (testing "a call to a down remote is a refusal too"
      (is (= :channel/transport-failed (get-in (as dead "nobody" #{:write} {:op :call :tool "x"})
                                               [:error :type]))))))

(deftest a-listing-cached-before-the-remote-went-down-still-answers
  (let [[stub _ _] (open-stub)
        up?   (atom true)
        down  {:error {:type :channel/transport-failed :message "connection refused"}}
        flaky (reify port/IHttpClient
                (-request! [_ req] (if @up? (port/-request! stub req) down))
                (-open-stream! [_ req sink] (if @up? (port/-open-stream! stub req sink) down)))
        c     (channel/make (assoc target :credentials (credential-policy) :ttl-ms 60000
                                   :credential-source (source) :http/client flaky))
        first-look (manifest-as c "agent-a" #{:read})]
    (is (answer/ok? first-look))
    (reset! up? false)
    (is (= first-look (manifest-as c "agent-a" #{:read})) "within its ttl, the cached listing")
    (is (= :channel/transport-failed (get-in (manifest-as c "agent-b" #{:read}) [:error :type]))
        "an agent with no cached listing sees the remote is down")))

;; -----------------------------------------------------------------------------
;; Credentials are references; urls carry none

(deftest a-url-secret-is-never-shown
  (let [[client _ _] (open-stub)
        t (assoc target :url leaky-url :credentials (credential-policy)
                 :credential-source (source) :http/client client)
        c (channel/make t)
        shown [(http/host-label t) (http/shown-url t) (:url (cli/target-label t))
               (get-in (manifest-as c "agent-a" #{:read}) [:ok :app :description])
               (get-in (port/-runtimes c) [:ok 0 :runtime-label])]]
    (is (every? string? shown))
    (is (not-any? #(str/includes? % leaked) shown) (pr-str shown))
    (is (not-any? #(str/includes? % "ops@") shown))
    (testing "and configuration refuses such a url outright"
      (is (some? (:error (config/promote {:targets [{:app "todo" :channel :http :url leaky-url}]}
                                         {} {} nil {})))))))

(deftest a-bearer-never-rides-plain-http-to-another-host
  (let [[client _ _] (open-stub)
        seen (atom [])
        spy  (reify port/IHttpClient
               (-request! [_ req] (swap! seen conj req) (port/-request! client req))
               (-open-stream! [_ req sink] (swap! seen conj req) (port/-open-stream! client req sink)))
        c    (channel/make (assoc target :url plain-remote-url :credentials (credential-policy)
                                  :credential-source (source) :http/client spy))]
    (is (= :channel/insecure-transport (get-in (manifest-as c "agent-a" #{:read}) [:error :type])))
    (is (empty? @seen) "nothing was sent")
    (is (some? (:error (config/promote {:targets [{:app "todo" :channel :http :url plain-remote-url
                                                   :credentials (credential-policy)}]}
                                       {} {} nil {}))))
    (testing "https anywhere, and plain http to this host, may carry one"
      (is (url/bearer-allowed? remote-url))
      (is (url/bearer-allowed? "http://127.0.0.1:8080/mcp"))
      (is (url/bearer-allowed? "http://localhost/mcp"))
      (is (not (url/bearer-allowed? "http://127.0.0.1.remote.test/mcp")))
      (is (url/bearer-allowed? "http://127.255.255.255/mcp"))
      (is (not (url/bearer-allowed? "http://127.0.0.999/mcp")) "an octet past 255 is no loopback address")
      (is (not (url/bearer-allowed? "http://127.300.0.1/mcp")))
      (is (not (url/bearer-allowed? plain-remote-url))))))

(deftest a-literal-token-fails-validation
  (let [literal {:token-env "REMOTE_AGENT_A" :token leaked}]
    (is (not (m/validate bs/Target (assoc target :token leaked))) "a full Target")
    (is (not (m/validate bs/Target (assoc target :credentials {"agent-a" literal})))
        "a credential reference with its value beside it")
    (is (some? (:error (config/promote {:targets [{:app "todo" :channel :http :url remote-url
                                                   :token leaked}]}
                                       {} {} nil {})))
        "an autonomous.edn app entry")
    (is (m/validate bs/Target (assoc target :credentials (credential-policy))))))

(deftest every-jdk-type-hint-resolves-to-its-class
  (let [jdk-ns (the-ns 'autonomous.bridge.http.jdk)
        tags   (for [v (vals (ns-interns jdk-ns))
                     al (:arglists (meta v))
                     t (cons (:tag (meta al)) (map (comp :tag meta) al))
                     :when (symbol? t)]
                 t)]
    (is (seq tags))
    (is (every? #(class? (ns-resolve jdk-ns %)) tags) (pr-str tags))))

(deftest a-token-rides-a-header-and-never-a-url
  (let [[client _ _] (open-stub)
        seen (atom [])
        spy  (reify port/IHttpClient
               (-request! [_ req] (swap! seen conj req) (port/-request! client req))
               (-open-stream! [_ req sink] (swap! seen conj req) (port/-open-stream! client req sink)))
        c    (channel/make (assoc target :credentials (credential-policy)
                                  :credential-source (source) :http/client spy))]
    (manifest-as c "agent-a" #{:read})
    (is (seq @seen))
    (is (every? #(not (str/includes? (:url %) "aut_")) @seen) "no token in any url")
    (is (every? #(= "Bearer aut_a" (get-in % [:headers "authorization"])) @seen)
        "the remote token rides a header")))

(deftest remote-credentials-belong-to-the-calling-agent
  (let [[client state _] (open-stub)
        seen (atom [])
        spy  (reify port/IHttpClient
               (-request! [_ req] (swap! seen conj req) (port/-request! client req))
               (-open-stream! [_ req sink] (swap! seen conj req) (port/-open-stream! client req sink)))
        c    (channel/make (assoc target :credentials (credential-policy)
                                  :credential-source (source) :http/client spy))
        calls [["agent-a" "aut_a"] ["agent-b" "aut_b"]]]
    (doseq [[actor token] calls]
      (is (= {:ok {:by actor :tool "todo_list" :args {}}}
             (as c actor #{:read :write} {:op :call :tool "todo_list"})))
      (is (every? #(= (str "Bearer " token) (get-in % [:headers "authorization"])) @seen)
          "no bridge or other-agent token crosses this boundary")
      (is (= [{:actor actor :tool "todo_list"}] (:calls @state)))
      (reset! seen [])
      (swap! state assoc :calls []))))

(def ^:private agent-a-env
  "The environment reference agent-a's credential is configured under."
  (get-in (credential-policy) ["agent-a" :token-env]))

(def ^:private newline-token
  "agent-a's token as an env file leaves it: with a trailing newline."
  (str (tokens "agent-a") "\n"))

(def ^:private loopback-url
  "A loopback url nothing listens on: plain http may carry a bearer there."
  "http://127.0.0.1:9")

(deftest a-token-with-a-control-character-is-refused-without-echoing-it
  (let [c (channel/make (assoc target :url loopback-url :credentials (credential-policy)
                               :credential-source (->Environment {agent-a-env newline-token})))
        a (manifest-as c "agent-a" #{:read})]
    (is (= :channel/credential-invalid (get-in a [:error :type])) (pr-str a))
    (is (not (str/includes? (pr-str a) (tokens "agent-a"))) "the token text is never echoed")
    (is (str/includes? (get-in a [:error :message]) agent-a-env) "the refusal names the env var"))
  (testing "the JDK adapter never echoes the header value it refuses"
    (let [a (port/-request! (autonomous.bridge.http.jdk/client)
                            {:method :post :url (str loopback-url "/mcp")
                             :headers (http/bearer-headers newline-token) :body "{}"})]
      (is (= :channel/transport-failed (get-in a [:error :type])))
      (is (not (str/includes? (pr-str a) (tokens "agent-a"))) (pr-str a)))))

(deftest a-configured-credential-that-is-unset-never-goes-anonymous
  (doseq [[why values] [["unset" {}] ["empty" {agent-a-env ""}] ["blank" {agent-a-env "  "}]]]
    (testing why
      (let [[client _ _] (open-stub)
            seen (atom [])
            spy  (reify port/IHttpClient
                   (-request! [_ req] (swap! seen conj req) (port/-request! client req))
                   (-open-stream! [_ req sink] (swap! seen conj req) (port/-open-stream! client req sink)))
            c    (channel/make (assoc target :credentials (credential-policy)
                                      :credential-source (->Environment values) :http/client spy))
            a    (manifest-as c "agent-a" #{:read})]
        (is (= :channel/credential-unset (get-in a [:error :type])) (pr-str a))
        (is (str/includes? (get-in a [:error :message]) agent-a-env) "the refusal names the env var")
        (is (empty? @seen) "nothing was sent")))))

(deftest credential-config-promotes-references-only
  (let [entry {:app "todo" :channel :http :url remote-url
               :credentials (credential-policy)}
        cfg (:ok (config/promote {:targets [entry]} {} {} nil {}))]
    (is (= (credential-policy) (get-in cfg [:targets 0 :credentials])))
    (is (= {:target/kind :http :target/id "todo" :url remote-url}
           (cli/target-label (first (:targets cfg)))))))

(deftest list-changed-refreshes-only-the-notified-actor
  (let [[c state streams] (ch)
        names #(mapv :name (:tools (:ok (manifest-as c % #{:read}))))]
    (is (= ["todo_add" "todo_list"] (names "agent-a")))
    (is (= ["todo_list"] (names "agent-b")))
    (is (= #{"agent-a" "agent-b"} (set (keys @streams))))
    (swap! state assoc :scopes {"agent-a" #{:read} "agent-b" #{:read :write}})
    (is (= ["todo_add" "todo_list"] (names "agent-a")) "without an event, the cached listing")
    ((get-in @streams ["agent-a" :text!])
     (str "data: " (json/write {:jsonrpc "2.0" :method rpc/list-changed}) "\n\n"))
    (is (= ["todo_list"] (names "agent-a")) "A's listing is refetched after its list_changed")
    (is (= ["todo_list"] (names "agent-b")) "B was not notified: its cached listing stands")))

(deftest fleet-refreshes-the-notified-target-without-crossing-actors
  (let [[c state streams] (ch)
        f   (bf/make [["todo" (client/context {:target target :channel c :drain :inline})]]
                     {:mode :fleet :probe-ms 1000})
        srv (server/fleet-server f)
        a   (s/open-session "a" {:actor "agent-a" :scopes #{:read :write}})
        b   (s/open-session "b" {:actor "agent-b" :scopes #{:read :write}})
        names #(set (map :name (server/list-tools srv %)))]
    (is (contains? (names a) "todo__todo_add"))
    (is (not (contains? (names b) "todo__todo_add")))
    (swap! state assoc-in [:scopes "agent-b"] #{:read :write})
    ((get-in @streams ["agent-b" :text!])
     (str "data: " (json/write {:jsonrpc "2.0" :method rpc/list-changed}) "\n\n"))
    (is (contains? (names b) "todo__todo_add") "B's SSE refresh reaches the fleet")
    (is (contains? (names a) "todo__todo_add"))
    (is (not (contains? (names b) "todo__missing")))
    (is (= {:ok {:by "agent-b" :tool "todo_add" :args {}}}
           (:answer (server/call-tool srv b "todo__todo_add" {}))))))

(deftest a-remote-make-builds-the-channel
  (is (satisfies? port/IRuntimeChannel (channel/make {:target/kind :http :target/id "mk"
                                                      :url "http://127.0.0.1:9"}))))
