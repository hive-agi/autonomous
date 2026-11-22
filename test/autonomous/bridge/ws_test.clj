(ns autonomous.bridge.ws-test
  "The websocket channel: its pure protocol, the channel contract against
   fake sockets (no network), and one real round trip: an http-kit
   endpoint, the JVM/bb client of autonomous.ws dialing it, and an MCP
   server driving the app's tools through it."
  (:require [autonomous.bridge.auth :as auth]
            [autonomous.bridge.channel :as channel]
            [autonomous.bridge.client :as client]
            [autonomous.bridge.port :as port]
            [autonomous.bridge.session :as s]
            [autonomous.bridge.ws :as ws]
            [autonomous.core :as a]
            [autonomous.domain.wire :as wire]
            [autonomous.ws :as rt]
            [autonomous.ws.ring :as ws-ring]
            [autonomous.ws.server :as ws-server]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def runtime-token "aut_runtime_todo")
(def agent-token "aut_agent_with_admin")

(def entries [(auth/token-entry runtime-token "todo" [:runtime])
              (auth/token-entry agent-token "todo" [:read :write :admin])])

(defn- install-app! []
  (a/reset-registry!)
  (a/set-app-info! {:name "todo" :description "A todo list"})
  (a/reg-tool :todo/add {:description "Add a todo" :input [:map [:text [:string {:min 1}]]]
                         :handler (fn [{:keys [text]} grant] {:added text :by (:actor grant)})}))

(use-fixtures :each (fn [t] (install-app!) (t)))

(defn- wait-until [ms f]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (or (f) (when (< (System/currentTimeMillis) deadline) (Thread/sleep 20) (recur))))))

;; -----------------------------------------------------------------------------
;; Pure protocol

(deftest frames-are-edn-maps-and-nothing-runs
  (is (= {:ok {:ws/type :hello}} (ws/read-frame "{:ws/type :hello}")))
  (is (= :ws/bad-frame (get-in (ws/read-frame "(System/exit 1)") [:error :type])))
  (is (= :ws/bad-frame (get-in (ws/read-frame "#=(+ 1 2)") [:error :type])) "no read-eval")
  (is (= :ws/bad-frame (get-in (ws/read-frame "{:type :hello}") [:error :type]))))

(deftest hello-needs-a-runtime-token
  (let [authn (ws/authenticator entries)
        fresh (constantly "fresh")]
    (is (= {:ok {:target "todo" :runtime {:runtime/id "tab-1" :runtime/kind :browser :runtime/label "tab"}}}
           (ws/hello-grant authn {:ws/type :hello :token runtime-token :runtime/id "tab-1"
                                  :runtime/kind :browser :runtime/label "tab"} fresh)))
    (is (= "fresh" (get-in (ws/hello-grant authn {:ws/type :hello :token runtime-token :runtime/id "bad id!"} fresh)
                           [:ok :runtime :runtime/id])))
    (testing "an agent token, even :admin, is not a runtime token"
      (is (= :auth/unauthorized (get-in (ws/hello-grant authn {:ws/type :hello :token agent-token} fresh)
                                        [:error :type]))))
    (is (= :auth/unauthorized (get-in (ws/hello-grant authn {:ws/type :hello :token "aut_nope"} fresh) [:error :type])))
    (is (= :ws/bad-frame (get-in (ws/hello-grant authn {:ws/type :response} fresh) [:error :type])))))

(deftest the-runtime-side-answers-only-through-rpc-str
  (is (= {:ok {:added "x" :by "a"}}
         (wire/read-edn (:answer (rt/reply {:ws/type :request :id 1
                                            :request (pr-str {:op :call :tool "todo_add" :args {:text "x"}
                                                              :actor "a" :scopes #{:write}})})))))
  (is (= :unknown-op (get-in (wire/read-edn (:answer (rt/reply {:ws/type :request :id 2
                                                                 :request "{:op :eval :code \"(+ 1 2)\"}"})))
                             [:error :type]))
      "there is no eval op to reach")
  (is (nil? (rt/reply {:ws/type :welcome})))
  (is (rt/fatal? {:ws/type :refused :error {:type :auth/unauthorized}}))
  (is (not (rt/fatal? {:ws/type :refused :error {:type :ws/hello-timeout}}))))

(deftest backoff-doubles-up-to-a-cap
  (is (= [500 1000 2000 4000] (map #(rt/backoff-ms % rt/default-backoff 1.0) (range 4))))
  (is (= 30000 (rt/backoff-ms 20 rt/default-backoff 1.0)))
  (is (= 250 (rt/backoff-ms 0 rt/default-backoff 0.1)) "jitter never halves below 0.5"))

;; -----------------------------------------------------------------------------
;; Channel contract against fake sockets: a fake runtime answers through rpc-str

(defn- fake-runtime!
  "Open a link on `hubs` whose peer is a runtime answering through
   rpc-str (or `respond`, (fn [frame] reply-frame-or-nil)). Answers
   {:link :sent :closed :lopts}."
  ([hubs] (fake-runtime! hubs rt/reply))
  ([hubs respond]
   (let [lopts  {:authenticate (ws/authenticator entries) :hubs hubs :hello-timeout-ms 0}
         sent   (atom [])
         closed (atom nil)
         link   (atom nil)
         io     {:send!  (fn [text]
                           (let [f (wire/read-edn text)]
                             (swap! sent conj f)
                             (when-let [r (respond f)]
                               (future (ws/on-text! lopts @link (wire/print-edn r))))))
                 :close! (fn [code reason] (reset! closed [code reason]))}]
     (reset! link (ws/open-link lopts io))
     {:link @link :sent sent :closed closed :lopts lopts})))

(defn- hello! [{:keys [link lopts]} token rid]
  (ws/on-text! lopts link (pr-str {:ws/type :hello :token token :runtime/id rid :runtime/kind :node})))

(deftest the-ws-channel-honours-the-runtime-channel-contract
  (let [hubs (atom {})
        ch   (ws/channel {:target/kind :ws :target/id "todo" :timeout-ms 2000} hubs)]
    (testing "no runtime yet: a refusal, not a hang"
      (is (= {:ok []} (port/-runtimes ch)))
      (is (= :channel/no-runtime (get-in (port/-send ch {:op :ping} nil) [:error :type]))))
    (let [r1 (fake-runtime! hubs)
          r2 (fake-runtime! hubs)]
      (hello! r1 runtime-token "r1")
      (hello! r2 runtime-token "r2")
      (testing "runtimes appear in the directory as they connect"
        (is (= ["r1" "r2"] (map :runtime/id (:ok (port/-runtimes ch)))))
        (is (= {:ws/type :welcome :target "todo" :runtime/id "r1"} (first @(:sent r1)))))
      (testing "requests reach rpc-str on the chosen runtime"
        (is (true? (get-in (port/-send ch {:op :ping} "r2") [:ok :pong])))
        (is (= {:ok {:added "milk" :by "agent-a"}}
               (port/-send ch {:op :call :tool "todo_add" :args {:text "milk"} :actor "agent-a" :scopes #{:write}} nil)))
        (is (= :channel/unknown-runtime (get-in (port/-send ch {:op :ping} "r9") [:error :type]))))
      (testing "the client pipeline drives it like any channel"
        (let [ctx (client/context {:target {:target/id "todo"} :channel ch})
              ses (s/attach (s/open-session "s" {:actor "agent-b" :scopes #{:write}}) "r1")]
          (is (= {:ok {:added "eggs" :by "agent-b"}} (client/call! ctx ses "todo_add" {:text "eggs"})))))
      (testing "a runtime leaves the directory when its socket closes"
        (ws/on-close! (:link r1))
        (is (= ["r2"] (map :runtime/id (:ok (port/-runtimes ch)))))
        (is (= :channel/unknown-runtime (get-in (port/-send ch {:op :ping} "r1") [:error :type])))))))

(deftest refusals-close-the-socket
  (let [hubs (atom {})
        _    (ws/channel {:target/kind :ws :target/id "todo"} hubs)]
    (testing "an agent token cannot register a runtime"
      (let [r (fake-runtime! hubs)]
        (hello! r agent-token "x")
        (is (= 4401 (first @(:closed r))))
        (is (= :refused (:ws/type (first @(:sent r)))))))
    (testing "a runtime token for a target this bridge does not serve"
      (let [other [(auth/token-entry "aut_rt_other" "other" [:runtime])]
            r     (fake-runtime! hubs)
            lopts (assoc (:lopts r) :authenticate (ws/authenticator other))]
        (ws/on-text! lopts (:link r) (pr-str {:ws/type :hello :token "aut_rt_other"}))
        (is (= 4404 (first @(:closed r))))))
    (testing "an authenticate answering neither Ok nor a refusal is unauthorized"
      (doseq [authenticate [(constantly nil) (constantly {:pending "p"})]]
        (let [r     (fake-runtime! hubs)
              lopts (assoc (:lopts r) :authenticate authenticate)]
          (ws/on-text! lopts (:link r) (pr-str {:ws/type :hello :token "t"}))
          (is (= 4401 (first @(:closed r))))
          (is (= :auth/unauthorized (get-in (first @(:sent r)) [:error :type]))))))
    (testing "an authenticate answering both :ok and :error is refused, not granted"
      (let [r     (fake-runtime! hubs)
            both  {:ok {:actor "todo" :scopes #{:runtime}}
                   :error {:type :auth/unauthorized :message "revoked"}}
            lopts (assoc (:lopts r) :authenticate (constantly both))]
        (ws/on-text! lopts (:link r) (pr-str {:ws/type :hello :token "t"}))
        (is (= 4401 (first @(:closed r))))
        (is (= :refused (:ws/type (first @(:sent r)))))
        (is (= :hello (:state @(:link r))))))
    (testing "an authenticate refusal typed :ws/unknown-target closes 4400, as a bad frame"
      (let [r     (fake-runtime! hubs)
            lopts (assoc (:lopts r) :authenticate
                         (constantly {:error {:type :ws/unknown-target :message "x"}}))]
        (ws/on-text! lopts (:link r) (pr-str {:ws/type :hello :token "t"}))
        (is (= 4400 (first @(:closed r))))))
    (testing "garbage before hello"
      (let [r (fake-runtime! hubs)]
        (ws/on-text! (:lopts r) (:link r) "(launch-missiles)")
        (is (= 4400 (first @(:closed r))))))))

(deftest a-silent-or-vanishing-runtime-does-not-hang-a-call
  (let [hubs (atom {})
        ch   (ws/channel {:target/kind :ws :target/id "todo" :timeout-ms 200} hubs)
        mute (fake-runtime! hubs (constantly nil))]
    (hello! mute runtime-token "mute")
    (is (= :channel/timeout (get-in (port/-send ch {:op :ping} "mute") [:error :type])))
    (let [p (future (port/-send (ws/channel {:target/kind :ws :target/id "todo" :timeout-ms 5000} hubs)
                                {:op :ping} "mute"))]
      (Thread/sleep 100)
      (ws/on-close! (:link mute))
      (is (= :channel/runtime-left (get-in (deref p 2000 nil) [:error :type]))
          "a request in flight is refused the moment its runtime leaves"))))

(deftest a-reconnect-under-the-same-id-replaces-the-old-socket
  (let [hubs (atom {})
        ch   (ws/channel {:target/kind :ws :target/id "todo"} hubs)
        old  (fake-runtime! hubs)
        new  (fake-runtime! hubs)]
    (hello! old runtime-token "tab")
    (hello! new runtime-token "tab")
    (is (= 4409 (first @(:closed old))))
    (ws/on-close! (:link old))
    (is (= ["tab"] (map :runtime/id (:ok (port/-runtimes ch)))) "the old socket closing does not evict the new one")
    (is (true? (get-in (port/-send ch {:op :ping} "tab") [:ok :pong])))))

(deftest channel-make-builds-the-ws-channel
  (is (satisfies? port/IRuntimeChannel (channel/make {:target/kind :ws :target/id "todo-make"}))))

;; -----------------------------------------------------------------------------
;; A real socket: http-kit endpoint, the JVM/bb client dialing it

(deftest a-runtime-dials-in-over-a-real-socket
  (let [_   (ws/channel {:target/kind :ws :target/id "todo"})
        h   (:ok (ws-server/start! {:bind "127.0.0.1" :port 0 :tokens entries}))
        url (str "ws://127.0.0.1:" (:port h) ws-ring/default-path)
        ch  (channel/make {:target/kind :ws :target/id "todo" :timeout-ms 5000})]
    (is (some? h) "the listener starts")
    (try
      (let [conn (rt/connect! {:url url :token runtime-token :runtime-id "svc-1"
                               :backoff {:base-ms 50 :max-ms 200}})]
        (try
          (is (wait-until 5000 #(some #{"svc-1"} (map :runtime/id (:ok (port/-runtimes ch))))))
          (is (wait-until 5000 #(= :open ((:status conn)))) "the client sees the hello acknowledged")
          (is (= {:ok {:added "wire" :by "agent-a"}}
                 (port/-send ch {:op :call :tool "todo_add" :args {:text "wire"}
                                 :actor "agent-a" :scopes #{:write}} "svc-1")))
          (finally (rt/disconnect! conn)))
        (is (wait-until 5000 #(empty? (:ok (port/-runtimes ch)))) "it leaves on disconnect"))
      (testing "a bad token is refused and not redialed"
        (let [conn (rt/connect! {:url url :token "aut_wrong" :backoff {:base-ms 50 :max-ms 100}})]
          (is (wait-until 5000 #(= :refused ((:status conn)))))
          (rt/disconnect! conn)))
      (testing "the endpoint refuses a plain HTTP request"
        (is (= 426 (:status ((ws-ring/handler {:tokens entries}) {:uri ws-ring/default-path :headers {}})))))
      (finally ((:stop! h))))))
