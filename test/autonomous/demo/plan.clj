(ns autonomous.demo.plan
  "Pure rules of `bb demo`: which ports it asks shadow-cljs for, how a
   scripted step names a todo by its text, the caption of a call, and the
   instructions printed for a real agent. The processes and sockets live
   in autonomous.demo.runner."
  (:require [clojure.string :as str]))

(def default-ports
  "Ports the demo prefers, each one off the shadow-cljs defaults (7888,
   9630, 8280) so a developer's own watch keeps running beside it."
  {:nrepl 7889 :shadow-http 9631 :dev-http 8281 :bridge 7777})

(defn pick-ports
  "Port map: the default of each key when `free?` says it is free, else
   the next value of `fallback` (a fn answering a free port). Pure over
   its two fns."
  [free? fallback]
  (into {} (map (fn [[k p]] [k (if (free? p) p (fallback))])) default-ports))

(defn shadow-env
  "Environment for a shadow-cljs server on `ports`: SHADOW_CLJS overrides
   the global :nrepl and :http ports (--config-merge only reaches build
   config), AUTONOMOUS_DEV_HTTP_PORT the :dev-http key."
  [{:keys [nrepl shadow-http dev-http]}]
  {"SHADOW_CLJS"              (pr-str {:nrepl {:port nrepl} :http {:port shadow-http}})
   "AUTONOMOUS_DEV_HTTP_PORT" (str dev-http)})

(defn todo-ref?
  "True for a step argument that names a todo by its text: {\"todo\" text}."
  [v]
  (and (map? v) (= 1 (count v)) (string? (or (get v "todo") (get v :todo)))))

(defn resolve-args
  "Step `args` with every todo reference replaced by the id of the todo
   with that text in `todos` (as todos_visible answers them). A reference
   to a todo that is not there stays as is, so the app refuses the call."
  [args todos]
  (let [by-text (into {} (map (juxt :text :id)) todos)]
    (into {} (map (fn [[k v]]
                    [k (if (todo-ref? v)
                         (get by-text (or (get v "todo") (get v :todo)) v)
                         v)]))
          args)))

(defn- args-text [args]
  (str/join ", " (map (fn [[k v]] (str (name k) ": " (pr-str v))) args)))

(defn caption
  "One line naming an MCP call: `todo_add {text: \"milk\"}`."
  [tool args]
  (if (seq args)
    (str tool " {" (args-text args) "}")
    tool))

(defn outcome-line
  "A short description of a CallOutcome {:value :error?} for the console."
  [{:keys [value error? text]}]
  (let [s (if error?
            (str/replace (str text) #"\s+" " ")
            (pr-str (if (and (map? value) (contains? value :result)) (:result value) value)))]
    (str (if error? "refused: " "ok ") (if (< 140 (count s)) (str (subs s 0 137) "...") s))))

(defn newest-runtime
  "Id of the runtime among `ids` (as autonomous_runtimes lists them) that
   connected last: every reload or tab mints a higher id. nil when none."
  [ids]
  (when (seq ids) (apply max-key #(if (number? %) % (or (parse-long (str %)) -1)) ids)))

(defn agent-instructions
  "Lines telling a human how to point a real agent at the bridge at
   `mcp-url` (Claude Code, or a hive ling using curl)."
  [mcp-url]
  [(str "MCP endpoint (loopback, no token): " mcp-url)
   ""
   "Claude Code:"
   (str "  claude mcp add --transport http todo-demo " mcp-url)
   ""
   "A hive ling (any model), from a coordinator:"
   (str "  swarm agent spawn type=ling task=\"The todo app in my browser is served over MCP at "
        mcp-url ". POST JSON-RPC with curl: initialize (keep the Mcp-Session-Id header), "
        "tools/list, autonomous_runtimes, autonomous_attach to the highest runtime id, then add, "
        "toggle, filter and remove todos with the todo_* tools, one call per turn.\"")])
