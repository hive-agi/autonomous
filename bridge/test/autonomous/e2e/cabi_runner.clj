(ns autonomous.e2e.cabi-runner
  "`bb cabi-e2e`: a native app (Rust, through libautonomous) driven by agents
   over MCP, live.

   1. A websocket-only bb bridge (`bb serve --target-id inventory --ws ..
      --http ..`) with an agent token, a read-only token and the app's
      runtime token.
   2. The Rust example (cabi/examples/rust) dials it with the runtime token.
   3. Agents attach and call its tools over MCP HTTP: the tools run in Rust,
      validation, scopes and the journal run in the library. When the app
      dies it leaves the directory.

   Needs cabi/build/libautonomous.so (`bb cabi`) and the Rust binary
   (`bb cabi-rust`, or AUTONOMOUS_CABI_BIN). Logs: target/e2e-logs/."
  (:require [autonomous.bridge.auth :as auth]
            [autonomous.domain.wire :as wire]
            [autonomous.e2e.agents :as agents]
            [autonomous.e2e.check :as check]
            [autonomous.e2e.scenario :as scenario]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]))

(defn- log [& xs]
  (binding [*out* *err*] (apply println "[cabi-e2e]" xs)))

(def root
  (str (or (some-> (System/getProperty "babashka.config") fs/parent) (fs/cwd))))

(defn- in-root [& parts] (str (apply fs/path root parts)))

(defn- wait-until [timeout-ms step-ms f]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (f)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep (long step-ms))
            (recur))))))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(defn- spawn! [procs label argv opts]
  (let [out (io/file (in-root "target" "e2e-logs" (str label ".out.log")))
        err (io/file (in-root "target" "e2e-logs" (str label ".err.log")))]
    (io/make-parents out)
    (let [proc (apply p/process (merge {:dir root :out :write :out-file out :err :write :err-file err} opts) argv)]
      (swap! procs conj {:label label :proc proc})
      proc)))

(defn- stop-all! [procs]
  (doseq [{:keys [label proc]} (reverse @procs)]
    (try (p/destroy-tree proc)
         (deref (future (deref proc)) 10000 nil)
         (catch Exception stop-failure
           (log "could not stop" label (ex-message stop-failure)))))
  (reset! procs []))

(defn- healthy? [port]
  (try (str/includes? (slurp (str "http://127.0.0.1:" port "/health")) "ok")
       (catch Exception _not-up-yet false)))

(defn app-binary
  "The Rust example binary: AUTONOMOUS_CABI_BIN, else its cargo release path."
  []
  (or (System/getenv "AUTONOMOUS_CABI_BIN")
      (in-root "cabi" "examples" "rust" "target" "release" "inventory")))

(defn- runtimes [agent]
  (get-in (scenario/-call agent "autonomous_runtimes" {}) [:value :runtimes]))

(defn- call [agent tool args]
  (scenario/-call agent tool args))

(defn run!
  "The live check; answers clojure.test counters."
  [procs]
  (doseq [f [(in-root "cabi" "build" "libautonomous.so") (app-binary)]]
    (when-not (fs/exists? f)
      (throw (ex-info (str "missing " f ": run `bb cabi` and `bb cabi-rust` first") {}))))
  (let [tokens   {"agent-a" (auth/mint) "agent-ro" (auth/mint)}
        rt-token (auth/mint)
        entries  [(auth/token-entry (tokens "agent-a") "agent-a" [:read :write])
                  (auth/token-entry (tokens "agent-ro") "agent-ro" [:read])
                  (auth/token-entry rt-token "inventory" [:runtime])]
        [hp wp]  [(free-port) (free-port)]
        url      (str "http://127.0.0.1:" hp "/mcp")
        client   (agents/http-client)]
    (log "websocket-only bb bridge on" hp "(runtimes dial" wp ")")
    (spawn! procs "cabi-bridge"
            (check/serve-command :bb ["--target-id" "inventory" "--ws" (str "127.0.0.1:" wp)
                                      "--http" (str "127.0.0.1:" hp) "--ledger" "memory"])
            {:extra-env {"AUTONOMOUS_TOKENS" (wire/print-edn entries)}})
    (when-not (wait-until 60000 300 #(healthy? hp))
      (throw (ex-info "the bridge did not answer /health: see target/e2e-logs/cabi-bridge.err.log" {})))
    (let [agent (agents/http-session! client url (tokens "agent-a"))
          ro    (agents/http-session! client url (tokens "agent-ro"))
          app   (spawn! procs "cabi-rust"
                        [(app-binary) (str "ws://127.0.0.1:" wp "/autonomous/ws") rt-token "inv-1"] {})]
      (binding [t/*report-counters* (ref t/*initial-report-counters*)]
        (t/testing "a Rust app dialing the bridge through libautonomous"
          (t/is (wait-until 60000 300 #(some #{"inv-1"} (map :runtime/id (runtimes agent))))
                "the native runtime appears in the directory")
          (doseq [a [agent ro]]
            (t/is (not (:error? (call a "autonomous_attach" {:runtime_id "inv-1"})))))
          (let [r (call agent "inventory_receive" {:sku "bolt" :qty 5})]
            (t/is (= 5 (get-in r [:value :on_hand])) (pr-str r))
            (t/is (= "rust" (get-in r [:value :by])) "the tool body ran in Rust")
            (t/is (= "agent-a" (get-in r [:value :actor])) "the caller's actor reached the Rust host"))
          (t/is (= 8 (get-in (call agent "inventory_receive" {:sku "bolt" :qty "3"}) [:value :on_hand]))
                "a quoted number is decoded by the library's malli schema")
          (t/is (:error? (call agent "inventory_receive" {:sku "bolt" :qty 0}))
                "the library validates args before Rust sees them")
          (t/is (:error? (call ro "inventory_receive" {:sku "nut" :qty 1}))
                "a read-only agent cannot call a write tool")
          (t/is (= 8 (get-in (call ro "inventory_stock" {}) [:value :bolt]))
                "a read-only agent can call a read-only tool")
          (p/destroy-tree app)
          (t/is (wait-until 30000 300 #(empty? (runtimes agent)))
                "it leaves the directory when the process dies"))
        @t/*report-counters*))))

(defn -main [& _]
  (let [procs (atom [])]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (stop-all! procs))))
    (let [code (try
                 (let [c (run! procs)]
                   (println (format "cabi e2e: %d assertions, %d failures, %d errors"
                                    (:pass c 0) (:fail c 0) (:error c 0)))
                   (if (zero? (+ (:fail c 0) (:error c 0))) 0 1))
                 (catch Exception run-failure
                   (log "failed:" (ex-message run-failure) (pr-str (ex-data run-failure)))
                   2)
                 (finally (stop-all! procs)))]
      (System/exit code))))
