(ns fleet.launch
  "`bb fleet-demo`: the fleet demo without hive-compose. Starts the programs
   example/fleet/hive-compose.edn declares (the todo UI, the orders JVM
   service, the inventory bb service), the same commands in the same
   directories, and waits until each nREPL answers.

     bb fleet-demo            start them; Ctrl-C stops everything
     bb fleet-demo --headless also open the todo page in a headless Chromium,
                              so the UI has a runtime with no browser window
     bb fleet-demo --probe    start, open headless, run the scripted agent
                              workflow (example/fleet/probe.edn) through one
                              fleet bridge, stop everything; exit 1 on failure
     bb fleet-demo --probe --keep   the same, but keep the fleet up after

   A program whose nREPL port already answers is somebody else's: it is
   reused, never started or stopped. Before each JVM it waits its turn
   (fewer than FLEET_MAX_JVMS=3 test-run JVMs on the host) and checks
   MemAvailable (at least 15 GiB, FLEET_MIN_AVAIL_GB overrides). Logs go to
   target/fleet-demo/<program>.log."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def root (str (fs/canonicalize (or (some-> (System/getProperty "babashka.config") fs/parent) "."))))
(def fleet-dir (str (fs/path root "example" "fleet")))
(def log-dir (str (fs/path root "target" "fleet-demo")))

(defn- log [& xs] (binding [*out* *err*] (apply println "[fleet-demo]" xs)))

(defn programs
  "The programs of the hive-compose project, dirs made absolute."
  []
  (mapv (fn [prog] (update prog :program/dir #(str (fs/normalize (fs/path fleet-dir %)))))
        (:project/programs (edn/read-string (slurp (str (fs/path fleet-dir "hive-compose.edn")))))))

(defn- listening? [port]
  (try (with-open [s (java.net.Socket.)]
         (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 250) true)
       (catch java.io.IOException _refused false)))

(defn- nrepl-port
  "The declared nREPL port of `prog`, else the one its .nrepl-port names."
  [prog]
  (or (:program/nrepl-port prog)
      (let [f (io/file (:program/dir prog) ".nrepl-port")]
        (when (.isFile f) (parse-long (str/trim (slurp f)))))))

(defn- jvm? [prog] (boolean (re-find #"\bclojure\b" (str (:program/command prog)))))

(defn wait-until
  "The first truthy (f), polled every 500 ms, or nil after `ms`."
  [ms f]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (or (f) (when (< (System/currentTimeMillis) deadline) (Thread/sleep 500) (recur))))))

(defn- avail-gb
  "MemAvailable in GiB, nil when unknown. Read through `cat`: the JVM's
   buffered reader asks a /proc file how much is available and fails."
  []
  (some->> (:out (p/sh "cat" "/proc/meminfo")) (re-find #"MemAvailable:\s+(\d+)") second parse-long
           (* 1.0) (#(/ % 1024 1024))))

(defn- busy-jvms
  "How many test-run JVMs (a -M:..test alias) run on the host, as the
   host's turn-taking rule counts them; services and watchers do not count."
  []
  (-> (p/sh "pgrep" "-fc" "java.*-M:[a-z:]*test") :out str/trim parse-long (or 0)))

(defn- await-jvm-turn!
  "Wait while FLEET_MAX_JVMS (default 3) or more build-heavy JVMs run, up
   to FLEET_JVM_WAIT_S seconds (default 1200)."
  [prog]
  (let [limit (or (some-> (System/getenv "FLEET_MAX_JVMS") parse-long) 3)
        wait  (or (some-> (System/getenv "FLEET_JVM_WAIT_S") parse-long) 1200)]
    (when-not (wait-until (* 1000 wait) #(< (busy-jvms) limit))
      (throw (ex-info (format "%d test JVMs still run after %d s; %s waits for fewer than %d (FLEET_MAX_JVMS)"
                              (busy-jvms) wait (:program/id prog) limit) {})))))

(defn- check-memory! [prog]
  (let [need (or (some-> (System/getenv "FLEET_MIN_AVAIL_GB") parse-long) 15)
        have (avail-gb)]
    (when (and have (< have need))
      (throw (ex-info (format "only %.1f GiB available, %s needs %d (FLEET_MIN_AVAIL_GB)"
                              have (:program/id prog) need) {})))))

(defn- argv [command]
  (if (string? command) ["sh" "-c" command] (vec command)))


(defn start!
  "Start `prog` unless its nREPL answers already; remember it in `procs`.
   Answers once its nREPL answers."
  [procs prog]
  (let [id (:program/id prog)]
    (if (some-> (:program/nrepl-port prog) listening?)
      (log id "already answers on" (:program/nrepl-port prog) "- reusing it")
      (do (when (jvm? prog) (await-jvm-turn! prog) (check-memory! prog))
          ;; a port file left by an earlier run would point the wait at a dead port
          (when-not (:program/nrepl-port prog) (fs/delete-if-exists (fs/path (:program/dir prog) ".nrepl-port")))
          (let [out (io/file log-dir (str id ".log"))]
            (io/make-parents out)
            (log "starting" id "-" (str/join " " (argv (:program/command prog))))
            (swap! procs conj {:id id :proc (p/process {:cmd (argv (:program/command prog))
                                                        :dir (:program/dir prog) :out :write :out-file out
                                                        :err :write :err-file (io/file log-dir (str id ".err.log"))})}))))
    (when-not (wait-until 600000 #(some-> (nrepl-port prog) listening?))
      (throw (ex-info (str id " did not open its nREPL; see " log-dir "/" id ".log") {})))
    (log id "nREPL on" (nrepl-port prog))))

(defn- chromium []
  (or (System/getenv "FLEET_CHROMIUM")
      (some->> (fs/glob (fs/path (System/getProperty "user.home") ".cache" "ms-playwright")
                        "chromium_headless_shell-*/*/chrome-headless-shell")
               (map str) sort last)
      (some #(some-> (fs/which %) str) ["chromium" "chromium-browser" "google-chrome"])))

(def page
  "The todo page shadow's dev-http serves (it answers / with 404)."
  "http://localhost:8280/index.html")

(defn open-headless!
  "Open the todo page in a headless Chromium (the UI's runtime)."
  [procs]
  (let [exe (or (chromium) (throw (ex-info "no Chromium found; set FLEET_CHROMIUM" {})))]
    (when-not (wait-until 300000 #(try (slurp page) (catch Exception _not-yet nil)))
      (throw (ex-info "the todo page did not answer on http://localhost:8280" {})))
    (log "opening http://localhost:8280 in" exe)
    (swap! procs conj {:id "browser"
                       :proc (p/process {:out :write :out-file (io/file log-dir "browser.log") :err :out
                                         :cmd [exe "--headless" "--no-sandbox" "--disable-gpu"
                                               (str "--user-data-dir=" (fs/path log-dir "chromium-profile"))
                                               "--remote-debugging-port=0" page]})})))

(defn stop-all! [procs]
  (doseq [{:keys [id proc]} (reverse @procs)]
    (log "stopping" id)
    (try (p/destroy-tree proc) (deref proc 15000 nil)
         (catch Exception e (log "could not stop" id (ex-message e)))))
  (reset! procs []))
