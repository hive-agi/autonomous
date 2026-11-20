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
            [autonomous.bridge.http.jdk :as jdk]
            [autonomous.bridge.http.session :as ses]
            [autonomous.bridge.http.channel :as hc]
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
  (is (= 1 (count (rpc/read-messages "data: {\"a\":1}\ndata: not json\n\n"))))
  (testing "a POST reply framed as SSE reads as its first message"
    (is (= {:jsonrpc "2.0" :id 1 :result {}}
           (rpc/message-of ": hi\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n")))))

(defn- pumped
  "What pump! hands a recording sink from `text` with line cap `cap`."
  [text cap]
  (let [seen (atom {:text [] :failed [] :closed 0})
        sink {:text!   #(swap! seen update :text conj %)
              :failed! #(swap! seen update :failed conj %)
              :close!  #(swap! seen update :closed inc)}]
    (jdk/pump! (java.io.StringReader. text) sink cap)
    @seen))

(defn- recording-sink
  "A session sink recording the messages and refusals it is handed."
  []
  (let [seen (atom {:messages [] :failed []})]
    [(ses/sink #(swap! seen update :messages conj %) (fn [])
               #(swap! seen update :failed conj (get-in % [:error :type])))
     seen]))

(def ^:private list-changed-event
  (str "data: " (json/write {:jsonrpc "2.0" :method rpc/list-changed}) "\n\n"))

(deftest an-event-without-a-blank-line-is-capped-not-buffered
  (let [[sink seen] (recording-sink)]
    (dotimes [_ (inc ses/max-event-lines)] ((:text! sink) ": keepalive\n"))
    (is (= [:channel/event-overflow] (:failed @seen)) "the overflow is reported once")
    (dotimes [_ 10] ((:text! sink) "data: {}\n"))
    (is (= [] (:messages @seen)) "a dropped event dispatches nothing")
    ((:text! sink) "\n")
    ((:text! sink) list-changed-event)
    (is (= [rpc/list-changed] (map :method (:messages @seen))) "the next event is read whole"))
  (testing "a big event is capped by size"
    (let [[sink seen] (recording-sink)
          line        (str "data: " (apply str (repeat 1000 "x")) "\n")]
      (dotimes [_ (inc (quot ses/max-event-chars 1000))] ((:text! sink) line))
      (is (= [:channel/event-overflow] (:failed @seen)))))
  (testing "the gathered event is held in O(1) appends"
    (is (= {:lines ["a" "b"] :chars 2 :dropped? false}
           (-> ses/empty-event (ses/add-line "a") (ses/add-line "b"))))))

(defn- dispatched-through-pump
  "The methods a session sink dispatches when pump! reads `text`."
  [text]
  (let [[sink seen] (recording-sink)]
    (jdk/pump! (java.io.BufferedReader. (java.io.StringReader. text)) sink)
    (map :method (:messages @seen))))

(def ^:private list-changed-line
  (str "data: " (json/write {:jsonrpc "2.0" :method rpc/list-changed})))

(deftest every-sse-line-end-completes-an-event
  (doseq [[why eol] [["LF" "\n"] ["CRLF" "\r\n"] ["lone CR" "\r"]]]
    (testing why
      (is (= [rpc/list-changed]
             (dispatched-through-pump (str ": open" eol eol list-changed-line eol eol)))))))

(deftest a-stream-line-past-the-cap-closes-the-stream
  (let [ok (pumped "data: a\n\ndata: b\n" 16)]
    (is (= ["data: a\n" "\n" "data: b\n"] (:text ok)))
    (is (empty? (:failed ok)))
    (is (= 1 (:closed ok))))
  (let [over (pumped (str "data: a\n" (apply str (repeat 40 "x")) "\ndata: late\n") 16)]
    (is (= ["data: a\n"] (:text over)) "nothing past the long line is read")
    (is (= [:channel/stream-overflow] (map #(get-in % [:error :type]) (:failed over))))
    (is (= 1 (:closed over)) "the stream is closed once"))
  (is (= (* 1024 1024) jdk/max-line-chars)))

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

(deftest a-remote-error-body-never-reaches-the-refusal
  (let [[stub _ _] (open-stub)
        listing? #(= "tools/list" (:method (json/read-json (:body %))))
        echo (reify port/IHttpClient
               (-request! [_ req]
                 (if (listing? req)
                   (json-response 403 {:error (str "refused " (get-in req [:headers "authorization"]))})
                   (port/-request! stub req)))
               (-open-stream! [_ req sink] (port/-open-stream! stub req sink)))
        c    (channel/make (assoc target :credentials (credential-policy)
                                  :credential-source (source) :http/client echo))
        a    (manifest-as c "agent-a" #{:read})]
    (is (= {:type :channel/remote-refused :status 403}
           {:type (get-in a [:error :type]) :status (get-in a [:error :data :status])}))
    (is (not (str/includes? (pr-str a) (tokens "agent-a"))) "a body echoing the token is not carried")))

(deftest an-http-target-needs-a-url
  (is (not (m/validate bs/Target (dissoc target :url))) "a Target without one")
  (is (not (m/validate bs/Target (assoc target :url ""))) "an empty one")
  (is (some? (:error (config/promote {:targets [{:app "todo" :channel :http}]} {} {} nil {})))
      "an autonomous.edn app entry without one is refused, not promoted to \"\""))

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

(defn- spied
  "A channel on `t` over a fresh stub, plus the requests it sent."
  [t]
  (let [[client _ _] (open-stub)
        seen (atom [])
        spy  (reify port/IHttpClient
               (-request! [_ req] (swap! seen conj req) (port/-request! client req))
               (-open-stream! [_ req sink] (swap! seen conj req) (port/-open-stream! client req sink)))]
    [(channel/make (assoc t :credential-source (source) :http/client spy)) seen]))

(def ^:private stranger "agent-z")

(deftest an-actor-with-no-credential-entry-never-goes-anonymous
  (let [[c seen] (spied (assoc target :credentials (credential-policy)))
        a        (manifest-as c stranger #{:read})]
    (is (= {:type :channel/credential-missing :data {:actor stranger :target (:target/id target)}}
           (select-keys (:error a) [:type :data]))
        (pr-str a))
    (is (not-any? #(str/includes? (pr-str a) %) (vals tokens)) "no credential value is named")
    (is (empty? @seen) "nothing was sent"))
  (testing "a target with no credential configuration at all stays anonymous"
    (let [[c seen] (spied target)]
      (manifest-as c stranger #{:read})
      (is (seq @seen))
      (is (not-any? #(get-in % [:headers "authorization"]) @seen)))))

(deftest an-empty-credential-policy-never-goes-anonymous
  (let [[c seen] (spied (assoc target :credentials {}))
        a        (manifest-as c "agent-a" #{:read})]
    (is (= :channel/credential-missing (get-in a [:error :type])) (pr-str a))
    (is (empty? @seen) "nothing was sent"))
  (testing "and the schema refuses an empty policy"
    (is (not (m/validate bs/Target (assoc target :credentials {}))))))

(defn- spy-client
  "A stub client that records every request it is handed, plus that record."
  []
  (let [[client _ _] (open-stub)
        seen (atom [])]
    [(reify port/IHttpClient
       (-request! [_ req] (swap! seen conj req) (port/-request! client req))
       (-open-stream! [_ req sink] (swap! seen conj req) (port/-open-stream! client req sink)))
     seen]))

(def ^:private live-session {:id "sid-agent-z" :actor stranger})

(defn- stream-and-close
  "The answers of opening a stream and closing a session as `actor` on `t`
   with credential `values`, plus the requests that went out."
  [t values actor]
  (let [[client seen] (spy-client)
        src           (->Environment values)
        grant         {:actor actor}
        ses           (assoc live-session :actor actor)]
    {:stream (ses/open-stream! client src t grant ses (fn [_]) (fn []))
     :close  (ses/close-session! client src t grant ses)
     :seen   @seen}))

(deftest stream-open-and-session-close-fail-closed
  (testing "an actor with no credential entry"
    (let [{:keys [stream close seen]} (stream-and-close (assoc target :credentials (credential-policy))
                                                        {"REMOTE_AGENT_A" "aut_a"} stranger)]
      (is (= :channel/credential-missing (get-in stream [:error :type])) (pr-str stream))
      (is (= :channel/credential-missing (get-in close [:error :type])) (pr-str close))
      (is (empty? seen) "no stream was opened and no DELETE went out")))
  (testing "a configured credential that is unset"
    (let [{:keys [stream close seen]} (stream-and-close (assoc target :credentials (credential-policy))
                                                        {} "agent-a")]
      (is (= :channel/credential-unset (get-in stream [:error :type])) (pr-str stream))
      (is (= :channel/credential-unset (get-in close [:error :type])) (pr-str close))
      (is (empty? seen))))
  (testing "a bearer refused over plain http to another host"
    (let [{:keys [stream close seen]} (stream-and-close (assoc target :url plain-remote-url
                                                               :credentials (credential-policy))
                                                        {"REMOTE_AGENT_A" "aut_a"} "agent-a")]
      (is (= :channel/insecure-transport (get-in stream [:error :type])) (pr-str stream))
      (is (= :channel/insecure-transport (get-in close [:error :type])) (pr-str close))
      (is (not-any? #(str/includes? (pr-str [stream close]) %) (vals tokens)))
      (is (empty? seen)))))

(deftest a-hostile-actor-is-cut-and-cleaned-in-the-refusal
  (let [hostile  (str "evil\n\u001b[31mFAKE LOG LINE\r" (apply str (repeat 200 "y")))
        [c seen] (spied (assoc target :credentials (credential-policy)))
        a        (manifest-as c hostile #{:read})
        msg      (get-in a [:error :message])
        shown    (get-in a [:error :data :actor])]
    (is (= :channel/credential-missing (get-in a [:error :type])) (pr-str a))
    (is (not-any? #(Character/isISOControl (char %)) msg) "no control character reaches the message")
    (is (= 64 (count shown)) "the actor is cut at 64 characters")
    (is (str/starts-with? shown "evil[31mFAKE LOG LINE"))
    (is (str/includes? msg shown))
    (is (not (str/includes? msg (apply str (repeat 100 "y")))))
    (is (empty? @seen) "nothing was sent")))

(deftest every-request-names-its-protocol-and-what-it-accepts
  (let [[c seen] (spied (assoc target :credentials (credential-policy)))]
    (manifest-as c "agent-a" #{:read})
    (is (<= 3 (count @seen)) "initialize, initialized, the stream and tools/list")
    (is (every? #(= rpc/protocol-version (get-in % [:headers "mcp-protocol-version"])) @seen))
    (is (every? #(contains? #{"application/json, text/event-stream" "text/event-stream"}
                            (get-in % [:headers "accept"]))
                @seen))
    (is (some #(= "application/json, text/event-stream" (get-in % [:headers "accept"])) @seen))))

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

(defn- notifying-during-list
  "A client over stub `client` that, the first time it serves a
   tools/list, first pushes a list_changed to that actor's stream."
  [client streams]
  (let [fired? (atom false)]
    (reify port/IHttpClient
      (-request! [_ req]
        (when (and (= "tools/list" (:method (json/read-json (str (:body req)))))
                   (compare-and-set! fired? false true))
          ((get-in @streams [(actor-of req) :text!])
           (str "data: " (json/write {:jsonrpc "2.0" :method rpc/list-changed}) "\n\n")))
        (port/-request! client req))
      (-open-stream! [_ req sink] (port/-open-stream! client req sink)))))

(deftest a-list-changed-during-a-fetch-is-not-lost
  (let [[client state streams] (open-stub)
        c (channel/make (assoc target :credentials (credential-policy) :ttl-ms 60000
                               :credential-source (source)
                               :http/client (notifying-during-list client streams)))
        first-look (manifest-as c "agent-b" #{:read})]
    (is (= ["todo_list"] (mapv :name (:tools (:ok first-look))))
        "the look answers the listing it fetched")
    (swap! state assoc-in [:scopes "agent-b"] #{:read :write})
    (is (= ["todo_add" "todo_list"] (mapv :name (:tools (:ok (manifest-as c "agent-b" #{:read})))))
        "the event that arrived mid-fetch still forces the next look to refetch")))

(defn- slow-initialize
  "A client over stub `client` that counts initializes in `opened` and
   holds each for a moment, widening the open race."
  [client opened]
  (reify port/IHttpClient
    (-request! [_ req]
      (when (= "initialize" (:method (json/read-json (str (:body req)))))
        (swap! opened inc)
        (Thread/sleep 50))
      (port/-request! client req))
    (-open-stream! [_ req sink] (port/-open-stream! client req sink))))

(deftest concurrent-looks-by-one-actor-open-one-session
  (let [[client _ _] (open-stub)
        opened (atom 0)
        c      (channel/make (assoc target :credentials (credential-policy) :credential-source (source)
                                    :http/client (slow-initialize client opened)))
        calls  (doall (repeatedly 8 #(future (as c "agent-a" #{:read} {:op :call :tool "todo_list"}))))]
    (is (every? #(answer/ok? (deref % 5000 nil)) calls))
    (is (= 1 @opened) "one session was opened for the actor")))

(defn- stream-opens-after-a-drop
  "How many stream opens `agent-a` makes on a channel whose retry interval
   is `retry-ms`, when its stream ends between two calls."
  [retry-ms]
  (let [[client _ streams] (open-stub)
        opens (atom 0)
        spy   (reify port/IHttpClient
                (-request! [_ req] (port/-request! client req))
                (-open-stream! [_ req sink] (swap! opens inc) (port/-open-stream! client req sink)))
        c     (channel/make (assoc target :credentials (credential-policy) :credential-source (source)
                                   :stream-retry-ms retry-ms :http/client spy))
        call  #(as c "agent-a" #{:read} {:op :call :tool "todo_list"})]
    (call)
    ((get-in @streams ["agent-a" :close!]))
    (call)
    @opens))

(deftest a-dead-stream-is-reopened-on-the-next-look-rate-limited
  (is (= 2 (stream-opens-after-a-drop 0)) "the next look reopens the ended stream")
  (is (= 1 (stream-opens-after-a-drop 60000)) "not again within the retry interval"))

(deftest a-stored-listing-is-fresh-only-at-its-own-revision
  (let [sl (assoc hc/empty-slot :revision 3)]
    (is (false? (:stale? (hc/stored sl {:v 1} 10 3))))
    (is (true? (:stale? (hc/stored sl {:v 1} 10 2))) "an event moved the revision meanwhile")
    (is (= {:v 1} (:manifest (hc/stored sl {:v 1} 10 2))) "the fetched listing is still kept")
    (is (= 4 (:revision (hc/mark-stale sl))))))

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
