(ns autonomous.demo.runner
  "`bb demo`: open the example todo app in a browser and let an agent
   drive it over MCP.

   1. shadow-cljs: reuse the server of this checkout when its nREPL
      answers, else start one (one JVM, -Xmx2g) on ports off the defaults
      (autonomous.demo.plan/default-ports), then watch build :app.
   2. The bridge: `bb serve --http` on loopback, no tokens (actor local).
   3. The page: opened with xdg-open/open, or its URL printed when there is
      no display (--no-open forces that).
   4. An agent: the scripted one (example/demo-script.json, one MCP call
      every --step-ms) unless --no-script, then the commands to point
      Claude Code or a hive ling at the same bridge. --record PATH makes
      a GIF with headless Playwright instead (doc/demo/record.py).

   Ctrl-C stops every process it started; --exit ends after the script.
   Logs go to target/demo-logs/."
  (:require [autonomous.demo.plan :as plan]
            [autonomous.e2e.agents :as agents]
            [autonomous.e2e.check :as check]
            [autonomous.e2e.scenario :as scenario]
            [autonomous.mcp.json :as json]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-mcp.tools.nrepl :as bb-nrepl]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- log [& xs]
  (binding [*out* *err*] (apply println "[demo]" xs)))

(def root
  "The repository: the directory of the bb.edn this task runs from."
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

(defn- port-free? [port]
  (try (with-open [_ (java.net.ServerSocket. (int port) 50 (java.net.InetAddress/getByName "127.0.0.1"))] true)
       (catch Exception _taken false)))

;; -----------------------------------------------------------------------------
;; Processes

(defn- spawn! [procs label argv opts]
  (let [out (io/file (in-root "target" "demo-logs" (str label ".out.log")))
        err (io/file (in-root "target" "demo-logs" (str label ".err.log")))]
    (io/make-parents out)
    (let [proc (apply p/process (merge {:dir root :out :write :out-file out :err :write :err-file err} opts) argv)]
      (swap! procs conj {:label label :proc proc})
      proc)))

(defn stop-all!
  "Stop every process in `procs`, newest first, with its children."
  [procs]
  (doseq [{:keys [label proc]} (reverse @procs)]
    (try (p/destroy-tree proc)
         (deref (future (deref proc)) 15000 nil)
         (log "stopped" label)
         (catch Exception stop-failure
           (log "could not stop" label (ex-message stop-failure)))))
  (reset! procs []))

;; -----------------------------------------------------------------------------
;; shadow-cljs

(defn- shadow-port []
  (let [f (in-root ".shadow-cljs" "nrepl.port")]
    (when (fs/exists? f) (parse-long (str/trim (slurp f))))))

(defn- shadow-eval [code timeout-ms]
  (when-let [port (shadow-port)]
    (let [r (bb-nrepl/eval-code {:host "localhost" :port port :code code :timeout-ms timeout-ms})]
      (when-not (or (:connect-failed? r) (:error? r)) (:result r)))))

(defn- ensure-shadow! [procs ports]
  (if (shadow-eval "1" 5000)
    (log "reusing the shadow-cljs server of this checkout (nREPL" (str (shadow-port) ")"))
    (do (log "starting shadow-cljs (one JVM, -Xmx2g) on nREPL" (:nrepl ports) "and dev-http" (:dev-http ports))
        (fs/delete-if-exists (in-root ".shadow-cljs" "nrepl.port"))
        (spawn! procs "shadow" ["clojure" "-J-Xmx2g" "-M:cljs" "-m" "shadow.cljs.devtools.cli" "server"]
                {:extra-env (plan/shadow-env ports)})
        (when-not (wait-until 300000 1000 #(shadow-eval "1" 5000))
          (throw (ex-info "shadow-cljs did not start: see target/demo-logs/shadow.err.log" {}))))))

(defn- watch-app! []
  (log "watching build :app (the first build takes ~20 s)")
  (when-not (shadow-eval "(shadow.cljs.devtools.api/watch :app)" 600000)
    (throw (ex-info "shadow-cljs could not watch :app" {})))
  (when-not (wait-until 120000 500 #(fs/exists? (in-root "example" "public" "js" "main.js")))
    (throw (ex-info "example/public/js/main.js was not written" {}))))

(defn- page-url
  "The dev-http URL serving example/public, read from the running server."
  []
  (some-> (shadow-eval (str "(some->> @(:dev-http @shadow.cljs.devtools.server.runtime/instance-ref)"
                            " :servers (keep :http-url) first)")
                       10000)
          (str/replace "\"" "")
          (str/trim)
          (#(when-not (#{"" "nil"} %) %))
          (str "/")))

;; -----------------------------------------------------------------------------
;; The bridge, the page

(defn- healthy? [port]
  (try (str/includes? (slurp (str "http://127.0.0.1:" port "/health")) "ok")
       (catch Exception _not-up-yet false)))

(defn- start-bridge! [procs port]
  (log "starting the bridge: bb serve --http 127.0.0.1:" port)
  (spawn! procs "bridge" (check/serve-command :bb ["--dir" root "--build" "app" "--target-id" "todo"
                                                    "--http" (str "127.0.0.1:" port)])
          {})
  (when-not (wait-until 60000 300 #(healthy? port))
    (throw (ex-info "the bridge did not answer /health: see target/demo-logs/bridge.err.log" {}))))

(defn- display? []
  (or (System/getenv "DISPLAY") (System/getenv "WAYLAND_DISPLAY")
      (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")))

(defn- open-page! [url]
  (if-let [opener (some fs/which ["xdg-open" "open"])]
    (do (log "opening" url) (p/process {:out :discard :err :discard} (str opener) url) true)
    false))

;; -----------------------------------------------------------------------------
;; The scripted agent

(defn- runtime-ids [agent]
  (mapv :runtime/id (get-in (scenario/-call agent "autonomous_runtimes" {}) [:value :runtimes])))

(defn- await-runtime
  "Id of the newest runtime that was not in `before`, waiting up to
   `timeout-ms` for a tab to connect."
  [agent before timeout-ms]
  (wait-until timeout-ms 500 #(plan/newest-runtime (remove (set before) (runtime-ids agent)))))

(defn- read-script [path]
  (mapv (fn [step] (update step :args #(or % {}))) (json/read-json (slurp path))))

(defn- call
  "CallOutcome of `tool` called by HttpAgent `agent`, with :text, the text
   content of the answer (a refusal's reason lives there)."
  [{:keys [client url token sid ids]} tool args]
  (let [{:keys [body]} (#'agents/post! client url token sid
                                      {:jsonrpc "2.0" :id (swap! ids inc) :method "tools/call"
                                       :params {:name tool :arguments args}})]
    (assoc (scenario/call-outcome body)
           :text (or (some :text (get-in body [:result :content])) (get-in body [:error :message])))))

(defn run-script!
  "Play the scripted agent: each step is one MCP call, printed with its
   caption and outcome, `step-ms` apart."
  [agent steps step-ms]
  (doseq [{:keys [tool args note]} steps]
    (let [todos (get-in (scenario/-call agent "todos_visible" {}) [:value :result])
          args  (plan/resolve-args args todos)]
      (println (str "agent> " (plan/caption tool args) (when note (str "   ; " note))))
      (println (str "       " (plan/outcome-line (call agent tool args))))
      (Thread/sleep (long step-ms)))))

(defn- record! [page mcp-url script out]
  (log "recording" out "with headless Playwright")
  (let [r (p/shell {:dir root :continue true}
                   "python3" (in-root "doc" "demo" "record.py")
                   "--page" page "--mcp" mcp-url "--script" script "--out" out)]
    (when-not (zero? (:exit r))
      (throw (ex-info "the recording failed" {:exit (:exit r)})))))

;; -----------------------------------------------------------------------------
;; Entry

(defn- parse-opts [args]
  (loop [[a & more] args acc {:step-ms 1500 :script (in-root "example" "demo-script.json")}]
    (case a
      nil           acc
      "--no-open"   (recur more (assoc acc :no-open? true))
      "--no-script" (recur more (assoc acc :no-script? true))
      "--exit"      (recur more (assoc acc :exit? true))
      "--step-ms"   (recur (rest more) (assoc acc :step-ms (parse-long (str (first more)))))
      "--script"    (recur (rest more) (assoc acc :script (first more)))
      "--record"    (recur (rest more) (assoc acc :record (first more) :exit? true :no-open? true))
      (throw (ex-info (str "unknown argument " a " (bb demo [--no-open] [--no-script] [--exit]"
                           " [--step-ms N] [--script FILE] [--record OUT.gif])")
                      {})))))

(defn run!
  "Start everything for `opts` and play the agent. Answers when the demo
   is done; without --exit it blocks until Ctrl-C."
  [procs opts]
  (when-not (fs/exists? (in-root "node_modules" "react"))
    (log "npm install (once)")
    (p/shell {:dir root} "npm" "install" "--no-audit" "--no-fund"))
  (let [ports   (plan/pick-ports port-free? free-port)
        _       (ensure-shadow! procs ports)
        _       (watch-app!)
        page    (or (page-url) (str "http://localhost:" (:dev-http ports) "/"))
        mcp-url (str "http://127.0.0.1:" (:bridge ports) "/mcp")
        _       (start-bridge! procs (:bridge ports))
        agent   (agents/http-session! (agents/http-client) mcp-url "")]
    (if-let [out (:record opts)]
      (record! page mcp-url (:script opts) (str (fs/absolutize out)))
      (let [before (runtime-ids agent)
            opened (and (not (:no-open? opts)) (display?) (open-page! page))]
        (println (str "\nThe todo app: " page (when-not opened "   (open it in a browser)")))
        (doseq [l (plan/agent-instructions mcp-url)] (println l))
        (when-not (:no-script? opts)
          (log "waiting for the page to connect (one runtime per tab)")
          (if-let [rt (await-runtime agent before 600000)]
            (do (scenario/-call agent "autonomous_attach" {:runtime_id rt})
                (println (str "\nScripted agent, attached to runtime " rt ":"))
                (run-script! agent (read-script (:script opts)) (:step-ms opts)))
            (log "no page connected in 10 minutes; skipping the scripted agent")))))
    (when-not (:exit? opts)
      (println "\nEverything keeps running for your agent. Ctrl-C stops what bb demo started.")
      @(promise))))

(defn -main [& args]
  (let [procs (atom [])
        opts  (try (parse-opts args) (catch Exception bad (log (ex-message bad)) (System/exit 2)))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (stop-all! procs))))
    (let [code (try (run! procs opts) 0
                    (catch Exception run-failure
                      (log "failed:" (ex-message run-failure) (pr-str (ex-data run-failure)))
                      1)
                    (finally (stop-all! procs)))]
      (System/exit code))))
