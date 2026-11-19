(ns autonomous.bridge.bridge-trifecta-test
  "Trifectas over the pure bridge strata (REPL source and parsing, auth,
   MCP protocol mapping, config promotion), a contract suite every ILedger
   adapter must pass, and the client pipeline against a stub channel."
  (:require [autonomous.bridge.auth :as auth]
            [autonomous.bridge.client :as client]
            [autonomous.bridge.eval-source :as src]
            [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.port :as port]
            [autonomous.bridge.session :as s]
            [autonomous.config :as config]
            [autonomous.domain.wire :as wire]
            [autonomous.mcp.protocol :as p]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]))

(def ^:private parse-value* src/parse-value)
(def ^:private authenticate* auth/authenticate)

;; -----------------------------------------------------------------------------
;; REPL source and parsing

(deftrifecta request-source-trifecta
  autonomous.bridge.eval-source/request-source
  {:golden-path "test/golden/bridge/request-source.edn"
   :apply? true
   :cases {:clj        [:clj nil {:op :ping} nil]
           :shadow     [:shadow :app {:op :call :tool "todo_add" :args {:text "say \"hi\""}} nil]
           :pinned     [:shadow "app" {:op :manifest} 7]}
   :gen (gen/tuple (gen/elements [:clj :shadow]) (gen/return :app)
                   (gen/hash-map :op (gen/elements [:ping :manifest])
                                 :args (gen/map gen/keyword gen/string-ascii {:max-elements 3}))
                   (gen/one-of [(gen/return nil) gen/nat]))
   ;; whatever the args, the source reads back as one form
   :pred (fn [code] (seq? (wire/read-edn code)))
   :mutations [["drops the runtime pin" (fn [m b r _] (src/request-source m b r nil))]
               ["unquoted request" (fn [m b r id] (str (src/request-source m b r id) " "))]]})

(def shadow-ok    (pr-str {:results [(pr-str (pr-str {:ok 1}))] :out "" :err ""}))
(def shadow-err   (pr-str {:results [] :err "Use of undeclared Var autonomous.core/rpc-str"}))
(def shadow-none  (pr-str {:results [] :err ""}))
(def shadow-alien (pr-str {:results ["nil"]}))

(deftrifecta parse-value-trifecta
  autonomous.bridge.eval-source/parse-value
  {:golden-path "test/golden/bridge/parse-value.edn"
   :apply? true
   :xf (fn [a] (or (get-in a [:error :type]) a))
   :cases {:clj-ok       [:clj (pr-str (pr-str {:ok {:a 1}}))]
           :clj-alien    [:clj (pr-str "hello")]
           :shadow-ok    [:shadow shadow-ok]
           :shadow-err   [:shadow shadow-err]
           :shadow-none  [:shadow shadow-none]
           :shadow-alien [:shadow shadow-alien]
           :not-edn      [:clj "#object[Foo"]}
   :gen (gen/tuple (gen/elements [:clj :shadow]) gen/string-ascii)
   :pred (fn [a] (= 1 (count (select-keys a [:ok :pending :error]))))
   :mutations [["trusts anything" (fn [m v] (let [r (parse-value* m v)] (if (:error r) {:ok nil} r)))]
               ["reads one level only" (fn [_ v] (try {:ok (wire/read-edn v)} (catch Exception _ {:ok nil})))]]})

;; -----------------------------------------------------------------------------
;; auth

(deftrifecta bearer-trifecta
  autonomous.bridge.auth/bearer
  {:golden-path "test/golden/bridge/bearer.edn"
   :cases {:ok "Bearer aut_x" :lower "bearer aut_x" :spaced "  Bearer   aut_x  "
           :basic "Basic dXNlcg==" :empty "Bearer " :none nil :bare "aut_x"}
   :gen (gen/one-of [gen/string-ascii (gen/fmap #(str "Bearer " %) gen/string-alphanumeric)])
   :pred (fn [t] (or (nil? t) (and (string? t) (seq t))))
   :mutations [["any scheme" (fn [h] (when (string? h) (second (re-find #"\S+\s+(\S+)" h))))]
               ["keeps the scheme" (fn [h] (when (string? h) (clojure.string/trim h)))]]})

(def ^:private tok-a "aut_alpha")
(def ^:private entries [(auth/token-entry tok-a "agent-a" [:read :write])
                        (auth/token-entry "aut_beta" "agent-b" [:read])])

(deftrifecta authenticate-trifecta
  autonomous.bridge.auth/authenticate
  {:golden-path "test/golden/bridge/authenticate.edn"
   :apply? true
   :cases {:a       [entries tok-a]
           :b       [entries "aut_beta"]
           :unknown [entries "aut_gamma"]
           :blank   [entries ""]
           :none    [[] tok-a]}
   :gen (gen/tuple (gen/return entries) (gen/one-of [(gen/elements [tok-a "aut_beta"]) gen/string-alphanumeric]))
   :pred (fn [a] (or (contains? (:ok a) :actor) (= :auth/unauthorized (get-in a [:error :type]))))
   :mutations [["first entry always" (fn [es _] {:ok {:actor (:actor (first es)) :scopes #{:read}}})]
               ["prefix match" (fn [es t] (authenticate* es (subs (str t "xxxxxxxx") 0 8)))]]})

(deftest stored-entries-never-hold-the-token
  (let [t (auth/mint)
        e (auth/token-entry t "x" ["read"])]
    (is (clojure.string/starts-with? t "aut_"))
    (is (not (clojure.string/includes? (pr-str e) t)))
    (is (= {:ok {:actor "x" :scopes #{:read}}} (auth/authenticate [e] t)))))

;; -----------------------------------------------------------------------------
;; MCP protocol

(deftrifecta tool-result-trifecta
  autonomous.mcp.protocol/tool-result
  {:golden-path "test/golden/mcp/tool-result.edn"
   :cases {:map     {:ok {:added "milk"}}
           :vector  {:ok [1 2]}
           :nil     {:ok nil}
           :pending {:pending "p3"}
           :refused {:error {:type :invalid-args :message "bad" :data {:text ["missing required key"]}}}}
   :gen (gen/one-of [(gen/fmap (fn [v] {:ok v}) (gen/map gen/keyword gen/small-integer))
                     (gen/fmap (fn [id] {:pending id}) gen/string-alphanumeric)])
   :pred (fn [r] (and (vector? (:content r)) (map? (:structuredContent r))))
   :mutations [["errors look fine" (fn [a] (dissoc (p/tool-result a) :isError))]
               ["no structured content" (fn [a] (dissoc (p/tool-result a) :structuredContent))]]})

(deftrifecta negotiate-trifecta
  autonomous.mcp.protocol/negotiate
  {:golden-path "test/golden/mcp/negotiate.edn"
   :cases {:newest "2025-06-18" :older "2024-11-05" :unknown "1999-01-01" :none nil}
   :gen gen/string-ascii
   :pred (set p/supported-versions)
   :mutations [["always newest" (constantly (first p/supported-versions))]
               ["echoes anything" identity]]})

;; -----------------------------------------------------------------------------
;; config promotion

(def shadow-dir {:dir "/w/todo" :shadow? true :builds [:app :test]})
(def deps-dir {:dir "/w/api" :shadow? false :builds nil})

(deftrifecta promote-trifecta
  autonomous.config/promote
  {:golden-path "test/golden/config/promote.edn"
   :apply? true
   :cases {:shadow-dir    [{} {} {:dir "/w/todo"} shadow-dir]
           :explicit-build [{} {} {:dir "/w/todo" :build "test"} shadow-dir]
           :clj-dir       [{} {} {:dir "/w/api"} deps-dir]
           :http-remote   [{} {"AUTONOMOUS_TOKENS" "[]"} {:dir "/w/todo" :http "0.0.0.0:7777"} shadow-dir]
           :datahike      [{} {} {:dir "/w/todo" :ledger "datahike" :datahike-path "/tmp/l"} shadow-dir]
           :stdio-scopes  [{} {} {:port "7002" :scopes "read" :actor "agent-9"} nil]
           :bad-port      [{} {} {:port "99999"} nil]}
   :gen (gen/tuple (gen/return {}) (gen/return {})
                   (gen/hash-map :dir (gen/return "/w/todo") :scopes (gen/elements ["read" "read,write"]))
                   (gen/return shadow-dir))
   :pred (fn [r] (or (contains? r :ok) (contains? r :error)))
   :mutations [["ignores --build" (fn [f e o d] (config/promote f e (dissoc o :build) d))]
               ["ignores scopes" (fn [f e o d] (config/promote f e (dissoc o :scopes) d))]
               ["always stdio" (fn [f e o d] (config/promote f e (dissoc o :http) d))]]})

;; -----------------------------------------------------------------------------
;; ILedger contract: every adapter passes the same suite

(defn ledger-contract [l]
  (let [es [(ledger/->ledger-entry "app" "7" {:seq 1 :at 100 :kind :autonomous/call :actor "a" :tool "t" :outcome :ok})
            (ledger/->ledger-entry "app" "7" {:seq 2 :at 200 :kind :autonomous/call :actor "b" :tool "t" :outcome :leased})
            (ledger/->ledger-entry "app" "8" {:seq 1 :at 300 :kind :app/clicked :target "save"})]]
    (testing "append stores new entries only"
      (is (= {:ok 3} (port/-append! l es)))
      (is (= {:ok 0} (port/-append! l es))))
    (testing "queries filter and order newest first"
      (is (= [300 200 100] (map :entry/at (:ok (port/-query l {})))))
      (is (= ["b"] (map :entry/actor (:ok (port/-query l {:actor "b"})))))
      (is (= [200] (map :entry/at (:ok (port/-query l {:since 150 :until 300})))))
      (is (= [300] (map :entry/at (:ok (port/-query l {:kind :app/clicked})))))
      (is (= 1 (count (:ok (port/-query l {:limit 1}))))))
    (testing "views carry the entry's own data"
      (is (= {:data {:target "save"} :source {:target "app" :runtime "8"}}
             (select-keys (ledger/->view (first (:ok (port/-query l {:kind :app/clicked}))))
                          [:data :source]))))))

(deftest memory-ledger-honours-the-contract
  (ledger-contract (ledger/open {:ledger/kind :memory})))

(deftest datahike-ledger-honours-the-contract
  (ledger-contract (ledger/open {:ledger/kind :datahike})))

;; -----------------------------------------------------------------------------
;; client pipeline against a stub channel (no REPL)

(defrecord StubChannel [script sent]
  port/IRuntimeChannel
  (-send [_ request runtime-id]
    (swap! sent conj [request runtime-id])
    ((:respond script) request))
  port/IRuntimeDirectory
  (-runtimes [_] {:ok [{:runtime/id 1} {:runtime/id 2}]}))

(defn- stub [respond] (->StubChannel {:respond respond} (atom [])))

(deftest a-reloaded-runtime-is-drained-from-its-new-epoch
  (let [epoch (atom 1)
        entries (atom [{:seq 1 :at 10 :kind :k} {:seq 2 :at 11 :kind :k}])
        ch (stub (fn [{:keys [op since]}]
                   (case op
                     :journal {:ok {:epoch @epoch :seq (count @entries)
                                    :entries (filterv #(> (:seq %) (or since 0)) @entries)}}
                     {:ok nil})))
        ctx (client/context {:target {:target/id "t"} :channel ch :ledger (ledger/open {:ledger/kind :memory})})
        ses (s/open-session "s" {:actor "a" :scopes #{:read}})]
    (is (= {:ok 2} (client/drain! ctx ses)))
    (is (= {:ok 0} (client/drain! ctx ses)) "nothing new")
    (reset! epoch 2)
    (reset! entries [{:seq 1 :at 20 :kind :k} {:seq 2 :at 21 :kind :k} {:seq 3 :at 22 :kind :k}])
    (is (= {:ok 3} (client/drain! ctx ses)) "after a reload, every entry of the new epoch")))

(deftest pending-answers-are-polled-until-settled-or-the-deadline
  (let [polls  (atom 0)
        now    (atom 0)
        sleeps (atom [])
        ch (stub (fn [{:keys [op]}]
                   (case op
                     :call {:pending "p1"}
                     :poll (if (< (swap! polls inc) 3) {:pending "p1"} {:ok :done})
                     {:ok {:epoch 1 :seq 0 :entries []}})))
        ctx (client/context {:channel ch :clock #(deref now)
                             :sleep! #(do (swap! sleeps conj %) (swap! now + %))
                             :call-timeout-ms 1000})
        ses (s/open-session "s" {:actor "a" :scopes #{:write}})]
    (is (= {:ok :done} (client/call! ctx ses "t" {})))
    (is (= [25 50 100] @sleeps) "backoff doubles between polls"))
  (testing "past the deadline the agent gets Pending back"
    (let [now (atom 0)
          ch (stub (fn [{:keys [op]}] (if (= :journal op) {:ok {:epoch 1 :seq 0 :entries []}} {:pending "p9"})))
          ctx (client/context {:channel ch :clock #(deref now) :sleep! #(swap! now + %) :call-timeout-ms 200})]
      (is (= {:pending "p9"} (client/call! ctx (s/open-session "s" {:actor "a" :scopes #{:write}}) "t" {}))))))

(deftest every-request-carries-the-session-identity
  (let [ch (stub (fn [_] {:ok nil}))
        ctx (client/context {:channel ch})
        ses (s/attach (s/open-session "s1" {:actor "agent-3" :scopes #{:read}}) 2)]
    (client/send! ctx ses {:op :call :tool "t" :actor "someone-else" :scopes [:admin]})
    (is (= [{:op :call :tool "t" :actor "agent-3" :scopes #{:read} :session "s1"} 2]
           (first @(:sent ch))))))
