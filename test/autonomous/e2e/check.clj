(ns autonomous.e2e.check
  "Pure rules of the live e2e: which runtimes a run drives, how a bridge
   host is started, and what a stdio smoke conversation must answer. The
   orchestration (processes, sockets) is autonomous.e2e.bb-runner.")

(defn new-runtimes
  "Ids of the runtimes in `after` that are not in `before`, in the order
   `after` lists them, without repeats. Both are runtime maps as
   autonomous_runtimes answers them ({:runtime/id ..}). The e2e drives
   only runtimes it started itself, so a shadow server reused from an
   earlier run cannot lend it a stale one."
  [before after]
  (let [seen (set (map :runtime/id before))]
    (into [] (comp (map :runtime/id) (remove nil?) (remove seen) (distinct)) after)))

(defmulti serve-command
  "The argv that runs `autonomous serve` with `args` on bridge `host`
   (:bb, :cljw, :jvm). An open set: a new host is one defmethod."
  (fn [host _args] host))

(defmethod serve-command :bb [_ args]
  (into ["bb" "serve"] args))

(defmethod serve-command :cljw [_ args]
  (into ["cljw" "-A:cljw" "-m" "autonomous.cli" "serve"] args))

(defmethod serve-command :jvm [_ args]
  (into ["clojure" "-M:serve"] args))

(defmethod serve-command :default [host _]
  (throw (ex-info (str "no serve command for host " host) {:host host})))

(def smoke-version "2025-03-26")

(defn smoke-requests
  "The JSON-RPC messages of a stdio smoke conversation that attaches to
   `runtime-id` and reads the app through it."
  [runtime-id]
  [{:jsonrpc "2.0" :id 1 :method "initialize" :params {:protocolVersion smoke-version}}
   {:jsonrpc "2.0" :method "notifications/initialized"}
   {:jsonrpc "2.0" :id 2 :method "tools/list"}
   {:jsonrpc "2.0" :id 3 :method "tools/call" :params {:name "autonomous_runtimes" :arguments {}}}
   {:jsonrpc "2.0" :id 4 :method "tools/call" :params {:name "autonomous_attach" :arguments {:runtime_id runtime-id}}}
   {:jsonrpc "2.0" :id 5 :method "tools/call" :params {:name "todos_stats" :arguments {}}}])

(defn smoke-problems
  "What is wrong with the `responses` (JSON-decoded, keyword keys) a stdio
   smoke conversation for `runtime-id` got back: a vector of messages,
   empty when the host served MCP end to end through the runtime."
  [runtime-id responses]
  (let [by-id (into {} (map (juxt :id identity)) responses)
        tools (set (map :name (get-in by-id [2 :result :tools])))
        rts   (set (map (comp str :runtime/id) (get-in by-id [3 :result :structuredContent :runtimes])))]
    (cond-> []
      (not= smoke-version (get-in by-id [1 :result :protocolVersion]))
      (conj (str "initialize did not negotiate " smoke-version))

      (not (and (contains? tools "autonomous_whoami") (contains? tools "todos_stats")))
      (conj "tools/list lacks the session or the app tools")

      (not (contains? rts (str runtime-id)))
      (conj (str "autonomous_runtimes does not list runtime " runtime-id))

      (not= (str runtime-id) (str (get-in by-id [4 :result :structuredContent :attached])))
      (conj "autonomous_attach did not pin the runtime")

      (not (integer? (get-in by-id [5 :result :structuredContent :total])))
      (conj "todos_stats did not answer through the runtime"))))
