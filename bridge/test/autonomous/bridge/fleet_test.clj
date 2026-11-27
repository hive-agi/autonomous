(ns autonomous.bridge.fleet-test
  "A fleet served by one MCP server against stub channels: routing of
   tools, resources, pending results and built-ins to the owning target,
   isolation of a down or slow target, list_changed when one comes back,
   and ledger entries that name their target. Ports are injected; nothing
   is redefined. Portable: runs on the JVM and on babashka."
  (:require [autonomous.bridge.client :as client]
            [autonomous.bridge.fleet :as bf]
            [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.port :as port]
            [autonomous.bridge.session :as s]
            [autonomous.domain.fleet]
            [autonomous.mcp.server :as server]
            [clojure.test :refer [deftest is testing]]
            [autonomous.mcp.stdio :as stdio]))

(defrecord StubChannel [respond sent]
  port/IRuntimeChannel
  (-send [_ request runtime-id]
    (swap! sent conj [request runtime-id])
    (respond request runtime-id))
  port/IRuntimeDirectory
  (-runtimes [_] {:ok [{:runtime/id 1} {:runtime/id 2}]}))

(defn- manifest [app & tools]
  {:ok {:protocol 1 :version 1 :app {:name app :description (str app " app")}
        :tools (mapv (fn [t] {:name t :description t :input-schema {:type "object"}}) tools)
        :resources [{:uri "app://db" :name "db"}]}})

(defn- app-responder
  "A runtime that answers like autonomous for app `app` with `tools`,
   journaling every call it gets."
  [app tools]
  (let [journal (atom [])
        polls   (atom 0)]
    (fn [{:keys [op tool actor since uri id]} runtime-id]
      (case op
        :manifest (apply manifest app tools)
        :call     (do (swap! journal conj {:seq (inc (count @journal)) :at (+ 100 (count @journal))
                                           :kind :autonomous/call :actor actor :tool tool :outcome :ok})
                      (if (= tool "slow_job") {:pending "p7"} {:ok {:app app :tool tool :runtime runtime-id}}))
        :poll     (if (< (swap! polls inc) 2) {:pending id} {:ok {:app app :polled id}})
        :read     {:ok {:mime "application/json" :value {:app app :uri uri}}}
        :journal  {:ok {:epoch 1 :seq (count @journal)
                        :entries (filterv #(> (:seq %) (or since 0)) @journal)}}
        {:ok {:app app}}))))

(def ^:private down
  (fn [_ _] {:error {:type :channel/connect-failed :message "cannot reach nREPL"}}))

(defn- member [tid respond shared-ledger]
  [tid (client/context {:target {:target/id tid}
                        :channel (->StubChannel respond (atom []))
                        :ledger shared-ledger
                        :drain :inline
                        :call-timeout-ms 100
                        :clock (let [t (atom 0)] #(swap! t + 60))
                        :sleep! (fn [_] nil)})])

(defn- fleet-srv
  "A fleet server over [tid respond] pairs sharing one memory ledger."
  [pairs]
  (let [l (ledger/open {:ledger/kind :memory})]
    (server/fleet-server (bf/make (mapv (fn [[tid r]] (member tid r l)) pairs) {:probe-ms 150}))))

(def ^:private rw (s/open-session "s1" {:actor "agent-1" :scopes #{:read :write}}))

(defn- call [srv ses tool args]
  (server/call-tool srv ses tool args))

(deftest calls-route-to-the-owning-target
  (let [srv (fleet-srv [["todo" (app-responder "todo" ["todo_add"])]
                        ["orders_api" (app-responder "orders" ["refund" "slow_job"])]])
        names (set (map :name (server/list-tools srv rw)))]
    (is (every? names ["todo__todo_add" "orders-api__refund" "autonomous_apps"]))
    (is (= {:ok {:app "orders" :tool "refund" :runtime nil}}
           (:answer (call srv rw "orders-api__refund" {}))))
    (is (= {:ok {:app "todo" :tool "todo_add" :runtime nil}}
           (:answer (call srv rw "todo__todo_add" {}))))
    (testing "an unprefixed or unknown name is refused, naming the apps"
      (is (= :unknown-tool (get-in (call srv rw "todo_add" {}) [:answer :error :type])))
      (is (= ["orders-api" "todo"] (get-in (call srv rw "shop__buy" {}) [:answer :error :data :apps]))))
    (testing "a pending id carries its target and polls there"
      (is (= {:pending "orders_api:p7"} (:answer (call srv rw "orders-api__slow_job" {}))))
      (is (= {:ok {:app "orders" :polled "p7"}}
             (:answer (call srv rw "autonomous_poll" {:id "orders_api:p7"})))))
    (testing "resources are listed and read under their app"
      (is (= {:app "todo" :uri "app://db"}
             (get-in (bf/read-resource (:fleet srv) rw "autonomous://todo/app://db") [:ok :value]))))))

(deftest built-ins-take-a-target
  (let [srv (fleet-srv [["a" (app-responder "a" ["x"])] ["b" (app-responder "b" ["y"])]])]
    (is (= :target/required (get-in (call srv rw "autonomous_runtimes" {}) [:answer :error :type])))
    (is (= :target/unknown (get-in (call srv rw "autonomous_runtimes" {:target "zz"}) [:answer :error :type])))
    (let [{ses :session} (call srv rw "autonomous_attach" {:target "b" :runtime_id 2})]
      (is (= {"b" 2} (:session/runtimes ses)) "attaching on b leaves a alone")
      (is (= {:app "b" :tool "y" :runtime 2} (:ok (:answer (call srv ses "b__y" {})))))
      (is (= {:app "a" :tool "x" :runtime nil} (:ok (:answer (call srv ses "a__x" {})))))
      (is (= 2 (get-in (call srv ses "autonomous_runtimes" {:target "b"}) [:answer :ok :attached]))))))

(deftest a-fleet-of-one-needs-no-target
  (let [l   (ledger/open {:ledger/kind :memory})
        srv (server/fleet-server
             (bf/make [(member "backend" (app-responder "backend" ["todos_add"]) l)]
                      {:mode :fleet :probe-ms 150}))]
    (is (= [{:runtime/id 1} {:runtime/id 2}]
           (get-in (call srv rw "autonomous_runtimes" {}) [:answer :ok :runtimes]))
        "the only app is addressed when target is omitted")
    (is (= {"backend" 2} (:session/runtimes (:session (call srv rw "autonomous_attach" {:runtime_id 2})))))))

(deftest the-ledger-names-each-entry's-target
  (let [srv (fleet-srv [["a" (app-responder "a" ["x"])] ["b" (app-responder "b" ["y"])]])]
    (call srv rw "a__x" {})
    (call srv rw "b__y" {})
    (let [rows (get-in (call srv rw "autonomous_ledger" {:kind "autonomous/call"}) [:answer :ok])]
      (is (= #{["a" "x"] ["b" "y"]} (set (map (juxt (comp :target :source) :tool) rows)))))
    (is (= ["b"] (map (comp :target :source)
                      (get-in (call srv rw "autonomous_ledger" {:target "b"}) [:answer :ok]))))))

(deftest a-down-or-slow-target-never-breaks-the-others
  (let [state   (atom :down)
        api     (app-responder "api" ["refund"])
        flaky   (fn [req rt] (if (= :down @state) (down req rt) (api req rt)))
        sleepy  (fn [req rt] (when (= :manifest (:op req)) (Thread/sleep 1500)) {:ok nil})
        srv     (fleet-srv [["todo" (app-responder "todo" ["todo_add"])] ["api" flaky] ["ui" sleepy]])
        t0      (System/currentTimeMillis)
        names   (set (map :name (server/list-tools srv rw)))
        elapsed (- (System/currentTimeMillis) t0)
        fp-down (server/tools-fingerprint srv rw)]
    (is (contains? names "todo__todo_add"))
    (is (not-any? #(clojure.string/starts-with? % "api__") names) "a down app's tools disappear")
    (is (< elapsed 1000) "a slow app costs at most the probe deadline")
    (is (= [:up :down :slow]
           (map :status (get-in (call srv rw "autonomous_apps" {}) [:answer :ok :apps]))))
    (is (= {:ok {:app "todo" :tool "todo_add" :runtime nil}} (:answer (call srv rw "todo__todo_add" {}))))
    (is (= :channel/connect-failed (get-in (call srv rw "api__refund" {}) [:answer :error :type]))
        "an app that never listed its tools answers its own error, and nothing is sent")
    (testing "it comes back on reconnect, and the fingerprint moves"
      (reset! state :up)
      (is (contains? (set (map :name (server/list-tools srv rw))) "api__refund"))
      (is (not= fp-down (server/tools-fingerprint srv rw))))))

(deftest single-mode-names-stay-as-they-were
  (let [l   (ledger/open {:ledger/kind :memory})
        srv (server/server (second (member "todo" (app-responder "todo" ["todo_add"]) l)))
        names (set (map :name (server/list-tools srv rw)))]
    (is (contains? names "todo_add"))
    (is (not (contains? names "autonomous_apps")))
    (is (= {:ok {:app "todo" :tool "todo_add" :runtime nil}} (:answer (call srv rw "todo_add" {}))))
    (is (= [1 ["todo_add"]] (server/tools-fingerprint srv rw)))))

(deftest single-mode-never-lists-an-invalid-name
  (let [l   (ledger/open {:ledger/kind :memory})
        f   (bf/from-context (second (member "todo" (app-responder "todo" ["todo_add" "admin.tools.list"]) l)))
        s   (bf/surface f rw)]
    (is (= ["todo_add"] (mapv :name (:tools s))))
    (is (= ["admin.tools.list"] (get-in s [:apps 0 :dropped])))))

(def ^:private long-stem
  "A tool-name stem long enough that <prefix>__<stem>... is cut at 64."
  (apply str (repeat 60 "x")))

(def ^:private per-actor-tools
  "What the remote lists each actor: tools whose exposed names are both cut
   and share their first 59 characters. agent-3 is refused outright."
  {"agent-1" [(str long-stem "_one")]
   "agent-2" [(str long-stem "_two")]})

(defn- per-actor-responder
  "A runtime that lists each actor its own tools, refuses an actor it does
   not know, and counts its journal reads (a drain) in `journal-reads`."
  [journal-reads]
  (fn [{:keys [op tool actor]} _]
    (case op
      :manifest (if-let [ts (per-actor-tools actor)]
                  (apply manifest "todo" ts)
                  {:error {:type :channel/remote-refused :message "unknown agent"}})
      :call     {:ok {:tool tool :by actor}}
      :journal  (do (swap! journal-reads inc) {:ok {:epoch 1 :seq 0 :entries []}})
      {:ok nil})))

(deftest the-last-probe-is-kept-per-actor
  (let [reads (atom 0)
        l     (ledger/open {:ledger/kind :memory})
        srv   (server/fleet-server (bf/make [(member "todo" (per-actor-responder reads) l)
                                             (member "other" (app-responder "other" ["x"]) l)]
                                            {:probe-ms 150}))
        [one two refused] (map #(s/open-session % {:actor % :scopes #{:read :write}})
                               ["agent-1" "agent-2" "agent-3"])
        cut-name (fn [ses] (first (filter #(clojure.string/starts-with? % "todo__")
                                          (map :name (server/list-tools srv ses)))))
        exposed  (cut-name one)]
    (is (= 64 (count exposed)) "agent-1's tool name is cut")
    (cut-name two)
    (is (= {:ok {:tool (first (per-actor-tools "agent-1")) :by "agent-1"}}
           (:answer (call srv one exposed {})))
        "a cut name resolves against the listing of the agent calling it, not the last prober")
    (server/list-tools srv refused)
    (reset! reads 0)
    (call srv one "autonomous_ledger" {})
    (is (pos? @reads) "another agent's refused probe does not mark the target down for agent-1")))

(defn- wait-for
  "True once `p` holds, polling for up to about two seconds."
  [p]
  (loop [n 100] (cond (p) true (zero? n) false :else (do (Thread/sleep 20) (recur (dec n))))))

(deftest concurrent-probes-of-two-actors-never-share-a-listing
  (let [latch    (promise)
        arrived  (atom [])
        blocking (fn [{:keys [op actor]} _]
                   (when (= :manifest op)
                     (swap! arrived conj actor)
                     @latch
                     (apply manifest "todo" (per-actor-tools actor))))
        f        (bf/make [(member "todo" blocking nil)] {:mode :fleet :probe-ms 3000})
        [one two] (map #(s/open-session % {:actor % :scopes #{:read}}) ["agent-1" "agent-2"])
        tools-of #(map :name (get-in % [0 :manifest :tools]))
        p1       (future (bf/probe f one))]
    (is (wait-for #(= ["agent-1"] @arrived)) "agent-1's look is in flight")
    (let [p2 (future (bf/probe f two))]
      (is (wait-for #(= 2 (count @arrived))) "agent-2's own look is in flight beside it")
      (deliver latch true)
      (is (= (per-actor-tools "agent-1") (tools-of @p1)))
      (is (= (per-actor-tools "agent-2") (tools-of @p2))
          "agent-2 waits on its own look, not on agent-1's"))))

(deftest a-target-coming-back-fires-list-changed
  (let [state  (atom :down)
        api    (app-responder "api" ["refund"])
        flaky  (fn [req rt] (if (= :down @state) (down req rt) (api req rt)))
        srv    (fleet-srv [["todo" (app-responder "todo" ["todo_add"])] ["api" flaky]])
        out    (atom [])
        done   (promise)
        runner (future (stdio/serve! srv {:watch-ms 30}
                                     (fn [] (deref done 2000 nil) nil)
                                     (fn [l] (swap! out conj l))))
        changed? (fn [] (some #(clojure.string/includes? % "list_changed") @out))]
    (Thread/sleep 150)
    (is (not (changed?)) "nothing changes while the fleet is steady")
    (reset! state :up)
    (is (wait-for changed?) "the app coming back moves the fleet fingerprint")
    (deliver done true)
    (deref runner 3000 nil)))

(defn- fleet-name [tid tool]
  (autonomous.domain.fleet/exposed-name (autonomous.domain.fleet/prefix tid) tool))

(defn- listing-responder
  "A runtime whose manifest lists (@listing actor scopes) and answers a call
   with the tool it was given; @mode :slow sleeps past the probe deadline,
   :down refuses."
  [listing mode]
  (fn [{:keys [op tool actor scopes]} _]
    (case op
      :manifest (case @mode
                  :slow (do (Thread/sleep 400) (apply manifest "r" (@listing actor scopes)))
                  :down {:error {:type :channel/connect-failed :message "down"}}
                  (apply manifest "r" (@listing actor scopes)))
      :call     {:ok {:tool tool}}
      {:ok nil})))

(defn- routing-fleet [listing mode]
  (let [l (ledger/open {:ledger/kind :memory})]
    (bf/make [(member "r" (listing-responder listing mode) l)
              (member "o" (app-responder "o" ["x"]) l)]
             {:probe-ms 150})))

(defn- tool-names [f ses]
  (set (map :name (:tools (bf/surface f ses)))))

(deftest sanitized-and-colliding-names-route-by-the-original-name
  (let [listing (atom (constantly ["pay.out" "pay_out"]))
        mode    (atom :up)
        f       (routing-fleet listing mode)
        dotted  (fleet-name "r" "pay.out")
        names   (tool-names f rw)]
    (is (contains? names "r__pay_out"))
    (is (contains? names dotted) "the dotted tool gets its own digested name")
    (is (= {:ok {:tool "pay_out"}} (bf/call! f rw "r__pay_out" {})))
    (is (= {:ok {:tool "pay.out"}} (bf/call! f rw dotted {})))
    (testing "a reorder of the listing rebinds nothing"
      (reset! listing (constantly ["pay_out" "pay.out"]))
      (tool-names f rw)
      (is (= {:ok {:tool "pay_out"}} (bf/call! f rw "r__pay_out" {})))
      (is (= {:ok {:tool "pay.out"}} (bf/call! f rw dotted {}))))
    (testing "a literal name in the digest shape never takes over a held digested name"
      (let [squatter (subs dotted 3)]
        (reset! listing (constantly ["pay_out" squatter]))
        (tool-names f rw)
        (is (= :unknown-tool (get-in (bf/call! f rw dotted {}) [:error :type]))
            "the held name of the delisted pay.out reaches nothing")
        (is (not= dotted (fleet-name "r" squatter)))
        (is (= {:ok {:tool squatter}} (bf/call! f rw (fleet-name "r" squatter) {}))
            "the literal tool is reachable under its own digested name")))
    (testing "a tool the target stops listing is refused, not sent as it is"
      (reset! listing (constantly ["pay_out"]))
      (tool-names f rw)
      (is (= :unknown-tool (get-in (bf/call! f rw dotted {}) [:error :type]))))))

(deftest a-slow-or-down-look-never-erases-the-listing-a-name-routes-by
  (let [listing (atom (constantly ["pay.out"]))
        mode    (atom :up)
        f       (routing-fleet listing mode)
        dotted  (fleet-name "r" "pay.out")]
    (tool-names f rw)
    (doseq [m [:slow :down]]
      (reset! mode m)
      (tool-names f rw)
      (is (= {:ok {:tool "pay.out"}} (bf/call! f rw dotted {}))
          (str "after a " (name m) " look the name still reaches its tool")))
    (testing "a session with no listing of a down target gets the target's own error, never a call"
      (let [fresh (s/open-session "s9" {:actor "agent-9" :scopes #{:read :write}})]
        (reset! mode :down)
        (is (= :channel/connect-failed (get-in (bf/call! f fresh dotted {}) [:error :type])))
        (is (= :channel/connect-failed (get-in (bf/call! f fresh "r__pay_out" {}) [:error :type])))))
    (testing "a slow target with no listing answers :target/slow"
      (let [fresh (s/open-session "s7" {:actor "agent-7" :scopes #{:read :write}})]
        (reset! mode :slow)
        (is (= :target/slow (get-in (bf/call! f fresh dotted {}) [:error :type])))))
    (testing "with no listing yet, the call probes the target first"
      (let [fresh (s/open-session "s8" {:actor "agent-8" :scopes #{:read :write}})]
        (reset! mode :up)
        (is (= {:ok {:tool "pay.out"}} (bf/call! f fresh dotted {})))))))

(deftest two-sessions-of-one-actor-keep-their-own-listing
  (let [listing (atom (fn [_actor scopes]
                        (if (contains? (set scopes) :write) ["pay.out"] ["pay_out" "pay.out"])))
        mode    (atom :up)
        f       (routing-fleet listing mode)
        ro      (s/open-session "s-ro" {:actor "agent-1" :scopes #{:read}})
        dotted  (fleet-name "r" "pay.out")]
    (tool-names f rw)
    (tool-names f ro)
    (is (= {:ok {:tool "pay.out"}} (bf/call! f rw dotted {}))
        "the read-only session's later look does not change where rw's call goes")
    (is (= {:ok {:tool "pay_out"}} (bf/call! f ro "r__pay_out" {})))
    (is (= :unknown-tool (get-in (bf/call! f rw "r__pay_out" {}) [:error :type]))
        "rw was never listed pay_out")))

(deftest views-and-in-flight-looks-are-bounded
  (let [now     (atom 0)
        l       (ledger/open {:ledger/kind :memory})
        f       (bf/make [(member "r" (app-responder "r" ["pay.out"]) l)
                          (member "o" (app-responder "o" ["x"]) l)]
                         {:probe-ms 150 :clock #(deref now) :view-ttl-ms 1000 :max-views 6})
        ses     #(s/open-session % {:actor % :scopes #{:read :write}})
        dotted  (fleet-name "r" "pay.out")
        keeper  (ses "keeper")]
    (bf/probe f keeper)
    (testing "a settled look leaves :inflight"
      (is (wait-for #(empty? @(:inflight f)))))
    (testing "the cap keeps the most recently touched views"
      (doseq [i (range 10)] (swap! now inc) (bf/probe f (ses (str "a" i))))
      (is (<= (count @(:last f)) 6))
      (is (= #{"a7" "a8" "a9"} (set (map second (keys @(:last f)))))))
    (testing "a view a live session keeps routing by is not evicted"
      (bf/probe f keeper)
      (doseq [i (range 3)]
        (swap! now + 400)
        (is (= {:app "r" :tool "pay.out" :runtime nil} (:ok (bf/call! f keeper dotted {}))))
        (bf/probe f (ses (str "b" i))))
      (is (contains? (set (map second (keys @(:last f)))) "keeper")))
    (testing "an untouched view expires after its TTL"
      (swap! now + 1001)
      (bf/probe f (ses "late"))
      (is (= #{"late"} (set (map second (keys @(:last f)))))))))

(deftest the-view-just-written-is-never-the-one-evicted
  (let [l  (ledger/open {:ledger/kind :memory})
        f  (bf/make [(member "r" (app-responder "r" ["pay.out"]) l)
                     (member "o" (app-responder "o" ["x"]) l)]
                    {:probe-ms 150 :clock (constantly 5) :max-views 2})
        ses #(s/open-session % {:actor % :scopes #{:read :write}})]
    (doseq [a ["a1" "a2" "a3"]]
      (bf/probe f (ses a))
      (is (= #{a} (set (map second (keys @(:last f)))))
          (str "with every view touched in the same millisecond, " a "'s two views stay")))
    (is (= {:app "r" :tool "pay.out" :runtime nil}
           (:ok (bf/call! f (ses "a3") (fleet-name "r" "pay.out") {}))))))
