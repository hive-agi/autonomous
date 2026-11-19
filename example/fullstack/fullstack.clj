(ns fullstack
  "`bb fullstack`: run the fullstack todo example and print how to drive it.

     Elm UI (browser) --REST/SSE--> Rust backend
          |                              |
          | autonomous.js                | libautonomous
          v                              v
          +----- websocket ----> bridge <-+   <-- MCP --  agent / Clojure

   1. Builds what is missing: libautonomous (bb cabi), the backend (cargo),
      the Elm app and autonomous.js (shadow-cljs :js) into frontend/dist.
   2. Mints three tokens (runtime tokens for the targets \"backend\" and
      \"frontend\", and one agent token) and writes target/fullstack/
      autonomous.edn: a fleet of two websocket targets.
   3. Starts the bridge (`bb serve --config ... --http`) and the backend,
      which serves the UI and dials the bridge; the browser tab dials too.
   4. Prints the URL, the `claude mcp add` line, and the Clojure driver.

   Options: --headless (open the page in headless Chromium instead of
   asking you to), --no-build, --http-port N (bridge MCP, 7891),
   --ws-port N (7890), --app-port N (8090). Ctrl-C stops everything."
  (:require [autonomous.bridge.auth :as auth]
            [autonomous.domain.wire :as wire]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def root (str (or (some-> (System/getProperty "babashka.config") fs/parent) (fs/cwd))))
(defn- in-root [& parts] (str (apply fs/path root parts)))
(def here (in-root "example" "fullstack"))
(def work (in-root "target" "fullstack"))

(defn- log [& xs] (binding [*out* *err*] (apply println "[fullstack]" xs)))

(defn- wait-until [timeout-ms step-ms f]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (f) (when (< (System/currentTimeMillis) deadline) (Thread/sleep (long step-ms)) (recur))))))

(defn- answers? [url]
  (try (slurp url) true (catch Exception _not-up false)))

;; -----------------------------------------------------------------------------
;; Build

(defn backend-binary []
  (or (System/getenv "TODO_BACKEND_BIN")
      (in-root "example" "fullstack" "backend" "target" "release" "todo-backend")))

(defn- sh! [dir & argv]
  (log (str/join " " argv))
  (let [r @(apply p/process {:dir dir :out :inherit :err :inherit} argv)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "failed: " (str/join " " argv)) {:exit (:exit r)})))))

(defn build!
  "Build whatever is missing (or everything with `force?`)."
  [force?]
  (when (or force? (not (fs/exists? (in-root "cabi" "build" "libautonomous.so"))))
    (sh! root "bb" "cabi"))
  (when (or force? (not (fs/exists? (backend-binary))))
    (sh! (in-root "example" "fullstack" "backend") "cargo" "build" "--release"))
  (when (or force? (not (fs/exists? (in-root "target" "autonomous-js" "autonomous.js"))))
    (sh! root "clojure" "-J-Xmx2g" "-M:cljs" "-m" "shadow.cljs.devtools.cli" "release" "js"))
  (let [dist (in-root "example" "fullstack" "frontend" "dist")]
    (fs/create-dirs dist)
    (sh! (in-root "example" "fullstack" "frontend") "elm" "make" "src/Main.elm" "--optimize" "--output=dist/elm.js")
    (doseq [f (fs/list-dir (in-root "example" "fullstack" "frontend" "static"))]
      (fs/copy f (fs/path dist (fs/file-name f)) {:replace-existing true}))
    (fs/copy (in-root "target" "autonomous-js" "autonomous.js") (fs/path dist "autonomous.js") {:replace-existing true})))

;; -----------------------------------------------------------------------------
;; Run

(defn tokens
  "Fresh tokens: {:backend t :frontend t :agent t :entries [...]}"
  []
  (let [t {:backend (auth/mint) :frontend (auth/mint) :agent (auth/mint)}]
    (assoc t :entries [(auth/token-entry (:backend t) "backend" [:runtime])
                       (auth/token-entry (:frontend t) "frontend" [:runtime])
                       (auth/token-entry (:agent t) "agent" [:read :write])])))

(defn config
  "The bridge's autonomous.edn: one fleet of two websocket-dialed apps."
  [ws-port entries]
  {:targets [{:target/kind :ws :target/id "backend"}
             {:target/kind :ws :target/id "frontend"}]
   :ws      {:bind "127.0.0.1" :port ws-port}
   :tokens  entries})

(defn- spawn! [procs label argv opts]
  (let [out (io/file work (str label ".log"))]
    (io/make-parents out)
    (let [proc (apply p/process (merge {:dir root :out :write :out-file out :err :write :err-file out} opts) argv)]
      (swap! procs conj proc)
      proc)))

(defn stop-all! [procs]
  (doseq [proc (reverse @procs)]
    (try (p/destroy-tree proc) (catch Exception _gone nil)))
  (reset! procs []))

(defn start!
  "Start bridge and backend (and a headless tab when asked). Answers the
   endpoints: {:mcp url :app url :agent-token t}."
  [procs {:keys [http-port ws-port app-port headless?]}]
  (let [t   (tokens)
        cfg (io/file work "autonomous.edn")]
    (io/make-parents cfg)
    (spit cfg (wire/print-edn (config ws-port (:entries t))))
    (log "bridge: MCP on" http-port ", apps dial" ws-port)
    (spawn! procs "bridge" ["bb" "serve" "--config" (str cfg) "--http" (str "127.0.0.1:" http-port)] {})
    (when-not (wait-until 60000 300 #(answers? (str "http://127.0.0.1:" http-port "/health")))
      (throw (ex-info "the bridge did not start: see target/fullstack/bridge.log" {})))
    (log "backend: http://127.0.0.1:" app-port)
    (spawn! procs "backend" [(backend-binary)]
            {:extra-env {"TODO_BIND"                 (str "127.0.0.1:" app-port)
                         "TODO_STATIC"               (in-root "example" "fullstack" "frontend" "dist")
                         "AUTONOMOUS_WS_URL"         (str "ws://127.0.0.1:" ws-port "/autonomous/ws")
                         "AUTONOMOUS_BACKEND_TOKEN"  (:backend t)
                         "AUTONOMOUS_FRONTEND_TOKEN" (:frontend t)}})
    (when-not (wait-until 30000 200 #(answers? (str "http://127.0.0.1:" app-port "/api/todos")))
      (throw (ex-info "the backend did not start: see target/fullstack/backend.log" {})))
    (when headless?
      (log "opening the page in headless Chromium")
      (spawn! procs "browser" ["python3" (str (fs/path here "headless.py")) (str "http://127.0.0.1:" app-port "/")] {}))
    {:mcp         (str "http://127.0.0.1:" http-port "/mcp")
     :app         (str "http://127.0.0.1:" app-port "/")
     :agent-token (:agent t)}))

(defn- parse-opts [args]
  (loop [[a & more] args o {:http-port 7891 :ws-port 7890 :app-port 8090}]
    (case a
      nil o
      "--headless" (recur more (assoc o :headless? true))
      "--no-build" (recur more (assoc o :no-build? true))
      "--rebuild" (recur more (assoc o :rebuild? true))
      "--http-port" (recur (rest more) (assoc o :http-port (parse-long (first more))))
      "--ws-port" (recur (rest more) (assoc o :ws-port (parse-long (first more))))
      "--app-port" (recur (rest more) (assoc o :app-port (parse-long (first more))))
      (throw (ex-info (str "unknown option " a) {})))))

(defn -main [& args]
  (let [opts  (parse-opts args)
        procs (atom [])]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(stop-all! procs)))
    (when-not (:no-build? opts) (build! (:rebuild? opts)))
    (let [{:keys [mcp app agent-token]} (start! procs opts)]
      (println)
      (println "  todo app (humans):   " app)
      (println "  MCP bridge (agents): " mcp)
      (println)
      (println "  Claude Code:")
      (println (str "    claude mcp add --transport http todo " mcp " --header \"Authorization: Bearer " agent-token "\""))
      (println)
      (println "  Clojure (drives the UI and the backend through the bridge):")
      (println (str "    AUTONOMOUS_MCP=" mcp " AUTONOMOUS_TOKEN=" agent-token " bb fullstack-drive"))
      (println)
      (println "  Tools: frontend__ui_state, frontend__ui_type, frontend__ui_submit, frontend__ui_set_filter, ...")
      (println "         backend__todos_list, backend__todos_add, backend__todos_toggle, ...")
      (println "  Open the app in a browser first: the frontend tools live in the tab. Ctrl-C stops everything.")
      @(promise))))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(defn e2e
  "`bb fullstack-e2e`: build what is missing, start everything on free ports
   with a headless tab, run the driver's checks, stop everything."
  [& _]
  (let [procs (atom [])
        code  (try
                (build! false)
                (let [{:keys [mcp agent-token]} (start! procs {:http-port (free-port) :ws-port (free-port)
                                                               :app-port (free-port) :headless? true})
                      problems ((requiring-resolve 'drive/check!) mcp agent-token)]
                  (if (empty? problems)
                    (do (println "fullstack e2e: ok (Elm UI and Rust backend driven through one bridge)") 0)
                    (do (println "fullstack e2e: FAILED")
                        (doseq [p problems] (println "  -" p))
                        (println "  logs: target/fullstack/")
                        1)))
                (catch Exception e
                  (log "failed:" (ex-message e))
                  2)
                (finally (stop-all! procs)))]
    (System/exit code)))
