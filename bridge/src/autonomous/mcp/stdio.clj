(ns autonomous.mcp.stdio
  "MCP over stdio: the local transport. One process, one agent, one session;
   it inherits the trust of whoever started it, so it needs no token. The
   grant (actor, scopes) comes from configuration.

   A watcher notices when the app's tools change (hot reload, a new page)
   and sends notifications/tools/list_changed, so the agent re-lists."
  (:require [autonomous.bridge.session :as s]
            [autonomous.mcp.json :as json]
            [autonomous.mcp.protocol :as p]
            [autonomous.mcp.server :as server]
            [clojure.string :as str]))

(def default-watch-ms 2000)

(defn- log [& xs]
  (binding [*out* *err*] (apply println "[autonomous]" xs)))

(defn- watch-tools!
  "Background loop (a future: every bridge runtime has them) sending
   notifications/tools/list_changed when the tool fingerprint moves."
  [srv session-atom write! running? watch-ms]
  (future
    (let [seen (atom nil)]
      (while @running?
        (try
          (Thread/sleep (long watch-ms))
          (let [fp (server/tools-fingerprint srv @session-atom)]
            (when (and @seen (not= fp @seen))
              (write! (p/notification "notifications/tools/list_changed" nil)))
            (reset! seen fp))
          (catch Exception e (log "tool watcher:" (ex-message e))))))))

(defn serve!
  "Serve MCP on lines read by `read-line!` (nil at end of input), writing
   each message with `write-line!`, until input ends. opts: :actor,
   :scopes, :watch-ms (0 disables the watcher). The 2-arity uses *in* and
   *out*, which every bridge runtime has."
  ([srv opts]
   (let [out *out*]
     (serve! srv opts read-line
             (fn [s] (binding [*out* out] (println s) (flush))))))
  ([srv {:keys [actor scopes watch-ms] :or {watch-ms default-watch-ms}} read-line! write-line!]
   (let [session  (atom (s/open-session "stdio" {:actor (or actor "local")
                                                 :scopes (or scopes #{:read :write})}))
         lock     (Object.)
         write!   (fn [m] (locking lock (write-line! (json/write m))))
         running? (atom true)]
     (when (pos? watch-ms) (watch-tools! srv session write! running? watch-ms))
     (try
       (loop []
         (when-let [line (read-line!)]
           (when-not (str/blank? line)
             (let [msg (try (json/read-json line) (catch Exception _not-json ::unparseable))]
               (if (= ::unparseable msg)
                 (write! (p/error nil :parse "parse error: one JSON-RPC message per line"))
                 (let [{:keys [response] next-session :session} (server/handle srv @session msg)]
                   (reset! session next-session)
                   (when response (write! response))))))
           (recur)))
       (finally (reset! running? false))))))
