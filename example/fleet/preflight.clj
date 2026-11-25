(ns fleet.preflight
  "`bb fleet-preflight [--headless]`: before the fleet comes up (by
   hive-compose or `bb fleet-demo`), check the tools its programs run and
   the ports they open. Starts nothing; prints one line per missing tool
   or taken port and exits 1 on any, else prints one ok line.

   The lists are data (`tools`, `ports`) taken from the fleet itself:
   example/fleet/hive-compose.edn (the program commands), shadow-cljs.edn
   (:nrepl 7888, :dev-http 8280 or AUTONOMOUS_DEV_HTTP_PORT) and
   example/fleet/demo.clj (npm when node_modules lacks react, Chromium
   for --headless and --probe). orders opens a free port of its own and
   the fleet bridge talks stdio, so neither has a port to check."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def tools
  "What the fleet runs. `:when` names the fact that makes a tool needed;
   absent, it is always needed. `:any` lists executables of which one is enough."
  [{:tool "clojure" :any ["clojure"] :for "the todo UI (shadow-cljs watch) and the orders service"}
   {:tool "bb" :any ["bb"] :for "the inventory service and the fleet bridge"}
   {:tool "npm" :any ["npm"] :for "installing node_modules (react is missing)" :when :node-modules-missing?}
   {:tool "chromium" :any ["chrome-headless-shell" "chromium" "chromium-browser" "google-chrome"]
    :for "the headless todo page (--headless, --probe; FLEET_CHROMIUM overrides)" :when :headless?}])

(def ports
  "The fixed ports the fleet's programs open. `:env` overrides the port."
  [{:port 8280 :env "AUTONOMOUS_DEV_HTTP_PORT" :for "the todo page (shadow :dev-http)"}
   {:port 7888 :for "shadow-cljs nREPL (program todo)"}
   {:port 7872 :for "inventory nREPL (program inventory)"}])

(defn port-of
  "The port `p` resolves to under environment map `env`."
  [env p]
  (or (some-> (:env p) env str/trim not-empty parse-long) (:port p)))

(defn findings
  "Pure: the lines to print for `facts`, a map of
   :found       set of executables on the PATH (or otherwise found)
   :taken       set of ports something already listens on
   :env         environment map
   plus the boolean facts the tools' :when name. Empty when all is well."
  [facts]
  (let [{:keys [found taken env] :or {found #{} taken #{} env {}}} facts]
    (concat
     (for [t tools
           :when (or (nil? (:when t)) (get facts (:when t)))
           :when (not-any? found (:any t))]
       (str "missing tool: " (:tool t) " (" (str/join " or " (:any t)) ") - needed for " (:for t)))
     (for [p ports
           :let [n (port-of env p)]
           :when (contains? taken n)]
       (str "port taken: " n " - needed for " (:for p))))))

(defn- listening? [port]
  (try (with-open [s (java.net.Socket.)]
         (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 250) true)
       (catch java.io.IOException _refused false)))

(defn- playwright-shell? []
  (seq (fs/glob (fs/path (System/getProperty "user.home") ".cache" "ms-playwright")
                "chromium_headless_shell-*/*/chrome-headless-shell")))

(defn- facts
  "The host's facts: what is on the PATH, which ports answer. Looks only."
  [root headless?]
  (let [env (into {} (System/getenv))
        exes (distinct (mapcat :any tools))]
    {:env env
     :headless? headless?
     :node-modules-missing? (not (fs/exists? (fs/path root "node_modules" "react")))
     :found (cond-> (set (filter fs/which exes))
              (playwright-shell?) (conj "chrome-headless-shell")
              (some-> (get env "FLEET_CHROMIUM") fs/executable?) (conj "chrome-headless-shell"))
     :taken (set (filter listening? (map #(port-of env %) ports)))}))

(defn -main [& args]
  (let [root (str (fs/canonicalize (or (some-> (System/getProperty "babashka.config") fs/parent) ".")))
        lines (findings (facts root (boolean (some #{"--headless" "--probe"} args))))]
    (if (seq lines)
      (do (run! println lines) (System/exit 1))
      (println "fleet preflight: ok (tools found, ports free)"))))
