(ns autonomous.e2e.bb-runner
  "`bb e2e`: the live end to end on the babashka host.

   1. shadow-cljs: reuse the server whose .shadow-cljs/nrepl.port answers,
      else start one (`clojure -J-Xmx2g -M:cljs -m shadow.cljs.devtools.cli
      server`); then watch build :e2e through its nREPL.
   2. A bridge process, `bb serve --http` on a free loopback port, with one
      freshly minted bearer token per agent (AUTONOMOUS_TOKENS).
   3. Two headless runtimes, `node target/e2e/main.js`; the run drives only
      the runtimes that appeared after it started them.
   4. Over MCP HTTP: the swarm scenario (autonomous.e2e.scenario: isolation
      across instances, lease coordination, ledger attribution) and the
      security pipeline live (wrong token, read-only token).
   5. stdio smoke conversations through each runtime: `bb serve`, and
      `cljw -A:cljw -m autonomous.cli serve` when cljw is on the PATH.

   Every process it started is stopped on the way out (and on Ctrl-C).
   Logs go to target/e2e-logs/. Exit code 0 only when everything passed."
  (:require [autonomous.bridge.auth :as auth]
            [autonomous.domain.wire :as wire]
            [autonomous.e2e.agents :as agents]
            [autonomous.e2e.check :as check]
            [autonomous.e2e.scenario :as scenario]
            [autonomous.mcp.json :as json]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-mcp.tools.nrepl :as bb-nrepl]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]))

(defn- log [& xs]
  (binding [*out* *err*] (apply println "[e2e]" xs)))

(def root
  "The repository: the directory of the bb.edn this task runs from."
  (str (or (some-> (System/getProperty "babashka.config") fs/parent) (fs/cwd))))

(defn- in-root [& parts] (str (apply fs/path root parts)))

(defn- wait-until
  "The first truthy value of (f), polling every `step-ms`, or nil once
   `timeout-ms` has passed."
  [timeout-ms step-ms f]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (f)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep (long step-ms))
            (recur))))))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

;; -----------------------------------------------------------------------------
;; Processes

(defn- spawn!
  "Start `argv` in the repository with stdout and stderr in
   target/e2e-logs/<label>.{out,err}.log; remember it in `procs`."
  [procs label argv opts]
  (let [out (io/file (in-root "target" "e2e-logs" (str label ".out.log")))
        err (io/file (in-root "target" "e2e-logs" (str label ".err.log")))]
    (io/make-parents out)
    (let [proc (apply p/process (merge {:dir root :out :write :out-file out :err :write :err-file err} opts) argv)]
      (swap! procs conj {:label label :proc proc})
      proc)))

(defn- stop-all!
  "Stop every process in `procs`, newest first, with its children."
  [procs]
  (doseq [{:keys [label proc]} (reverse @procs)]
    (try (p/destroy-tree proc)
         (deref (future (deref proc)) 10000 nil)
         (catch Exception stop-failure
           (log "could not stop" label (ex-message stop-failure)))))
  (reset! procs []))

;; -----------------------------------------------------------------------------
;; shadow-cljs

(defn- shadow-port []
  (let [f (in-root ".shadow-cljs" "nrepl.port")]
    (when (fs/exists? f) (parse-long (str/trim (slurp f))))))

(defn- shadow-eval
  "The printed value of `code` evaluated in shadow's (Clojure) nREPL, or
   nil when it is not reachable or the eval failed."
  [code timeout-ms]
  (when-let [port (shadow-port)]
    (let [r (bb-nrepl/eval-code {:host "localhost" :port port :code code :timeout-ms timeout-ms})]
      (when-not (or (:connect-failed? r) (:error? r)) (:result r)))))

(defn- ensure-shadow!
  "A shadow-cljs server answering in this repository: the running one, or
   one started here (remembered in `procs`, so it is stopped at the end)."
  [procs]
  (if (shadow-eval "1" 5000)
    (log "reusing the shadow-cljs server on nREPL port" (shadow-port))
    (do (log "starting shadow-cljs (one JVM, -Xmx2g)")
        (spawn! procs "shadow" ["clojure" "-J-Xmx2g" "-M:cljs" "-m" "shadow.cljs.devtools.cli" "server"] {})
        (when-not (wait-until 300000 1000 #(shadow-eval "1" 5000))
          (throw (ex-info "shadow-cljs did not start: see target/e2e-logs/shadow.err.log" {}))))))

(defn- watch-e2e! []
  (log "watching build :e2e")
  (let [r (shadow-eval "(shadow.cljs.devtools.api/watch :e2e)" 600000)]
    (when-not r
      (throw (ex-info "shadow-cljs could not watch :e2e" {})))
    (when-not (wait-until 120000 500 #(fs/exists? (in-root "target" "e2e" "main.js")))
      (throw (ex-info "target/e2e/main.js was not written" {:watch r})))))

;; -----------------------------------------------------------------------------
;; The bridge and the runtimes

(def ^:private actors {"agent-a" [:read :write] "agent-b" [:read :write] "agent-ro" [:read]})

(defn- mint-tokens
  "{:tokens {actor token} :entries [TokenEntry]} for `actors`."
  []
  (let [tokens (into {} (map (fn [[a _]] [a (auth/mint)])) actors)]
    {:tokens  tokens
     :entries (mapv (fn [[a scopes]] (auth/token-entry (tokens a) a scopes)) actors)}))

(defn- serve-args [& more]
  (into ["--dir" root "--build" "e2e" "--target-id" "todo"] more))

(defn- healthy? [port]
  (try (str/includes? (slurp (str "http://127.0.0.1:" port "/health")) "ok")
       (catch Exception _not-up-yet false)))

(defn- start-bridge!
  "Start `bb serve --http` on `port` with token `entries`; wait for it."
  [procs port entries]
  (log "starting the bb bridge on 127.0.0.1:" port)
  (spawn! procs "bridge"
          (check/serve-command :bb (serve-args "--http" (str "127.0.0.1:" port) "--ledger" "memory"))
          {:extra-env {"AUTONOMOUS_TOKENS" (wire/print-edn entries)}})
  (when-not (wait-until 60000 300 #(healthy? port))
    (throw (ex-info "the bb bridge did not answer /health: see target/e2e-logs/bridge.err.log" {}))))

(defn- runtimes [agent]
  (get-in (scenario/-call agent "autonomous_runtimes" {}) [:value :runtimes]))

(defn- start-runtimes!
  "Start two node runtimes; answer the ids of the two that appeared."
  [procs probe]
  (let [before (runtimes probe)]
    (log "starting two node runtimes of the todo app")
    (doseq [i [1 2]] (spawn! procs (str "node-" i) ["node" "target/e2e/main.js"] {}))
    (or (wait-until 90000 500 #(let [ids (check/new-runtimes before (runtimes probe))]
                                 (when (<= 2 (count ids)) (subvec ids 0 2))))
        (throw (ex-info "the node runtimes did not connect to shadow-cljs" {:before before})))))

;; -----------------------------------------------------------------------------
;; Checks

(defn- with-counters
  "clojure.test counters {:test :pass :fail :error} after running `f`."
  [f]
  (binding [t/*report-counters* (ref t/*initial-report-counters*)]
    (f)
    @t/*report-counters*))

(defn- init-status [client url token]
  (try (agents/http-session! client url token) 200
       (catch clojure.lang.ExceptionInfo refused (:status (ex-data refused)))))

(defn- security! [client url tokens r1]
  (t/testing "the security pipeline, live"
    (t/is (= 401 (init-status client url "aut_not_a_token")) "an unknown bearer token is refused")
    (let [ro ((agents/http-agent-on client url tokens) "agent-ro" r1)]
      (t/is (true? (:error? (scenario/-call ro "todo_add" {:text "read-only"}))) "a read token cannot write")
      (t/is (map? (:value (scenario/-call ro "todos_stats" {}))) "a read token can read"))))

(defn- smoke!
  "Problems of a stdio smoke conversation through `runtime-id` on bridge
   `host`: empty when it served MCP end to end."
  [host runtime-id]
  (log "stdio smoke on" (name host))
  (let [input (str (str/join "\n" (map json/write (check/smoke-requests runtime-id))) "\n")
        proc  (apply p/process {:dir root :in input :out :string :err :string}
                     (check/serve-command host (serve-args "--timeout-ms" "20000")))
        res   (deref (future (deref proc)) 180000 ::timeout)]
    (if (= ::timeout res)
      (do (p/destroy-tree proc) [(str (name host) " stdio did not finish in 180 s")])
      (let [lines     (str/split-lines (str (:out res)))
            responses (keep #(try (json/read-json %) (catch Exception _not-json nil)) lines)
            problems  (check/smoke-problems runtime-id responses)]
        (when (seq problems) (log (name host) "stderr:" (:err res)))
        problems))))

(defn- ensure-node-deps! []
  (when-not (fs/exists? (in-root "node_modules" "ws"))
    (log "npm install (once)")
    (p/shell {:dir root} "npm" "install" "--no-audit" "--no-fund")))

(defn run!
  "Run the live e2e. Answers {:counters {..} :smoke {host problems}}."
  [procs]
  (ensure-node-deps!)
  (ensure-shadow! procs)
  (watch-e2e!)
  (let [{:keys [tokens entries]} (mint-tokens)
        port   (free-port)
        url    (str "http://127.0.0.1:" port "/mcp")
        client (agents/http-client)]
    (start-bridge! procs port entries)
    (let [probe    (agents/http-session! client url (tokens "agent-a"))
          [r1 r2]  (start-runtimes! procs probe)
          _        (log "driving runtimes" r1 "and" r2 "through MCP")
          counters (with-counters
                     (fn []
                       (scenario/swarm! (agents/http-agent-on client url tokens) [r1 r2]
                                        (str (System/currentTimeMillis)))
                       (security! client url tokens r1)))
          hosts    (cond-> [:bb] (fs/which "cljw") (conj :cljw))]
      {:counters counters
       :smoke    (into {} (map (fn [h] [h (smoke! h r1)])) hosts)
       :skipped  (when-not (fs/which "cljw") ["cljw (not on the PATH)"])})))

(defn -main [& _]
  (let [procs (atom [])
        hook  (Thread. ^Runnable (fn [] (stop-all! procs)))]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (let [code (try
                 (let [{:keys [counters smoke skipped]} (run! procs)
                       bad (into [] (mapcat (fn [[h ps]] (map #(str (name h) ": " %) ps))) smoke)]
                   (println (format "e2e over MCP HTTP: %d assertions, %d failures, %d errors"
                                    (:pass counters 0) (:fail counters 0) (:error counters 0)))
                   (doseq [[h ps] smoke]
                     (println (str "stdio smoke " (name h) ": " (if (empty? ps) "ok" (str/join "; " ps)))))
                   (doseq [s skipped] (println "skipped:" s))
                   (if (and (zero? (+ (:fail counters 0) (:error counters 0))) (empty? bad)) 0 1))
                 (catch Exception run-failure
                   (log "failed:" (ex-message run-failure) (pr-str (ex-data run-failure)))
                   2)
                 (finally (stop-all! procs)))]
      (System/exit code))))
