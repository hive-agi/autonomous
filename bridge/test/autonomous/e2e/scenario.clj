(ns autonomous.e2e.scenario
  "The swarm story the live suites prove, written once over the Agent port
   so the same assertions drive an in-process server (the JVM live test)
   and a bridge process reached over MCP (`bb e2e`).

   Needs two distinct runtimes of the example todo app."
  (:require [clojure.test :refer [is testing]]))

(defprotocol Agent
  (-call [agent tool args]
    "Call MCP tool `tool` with `args` as this agent. Answers a CallOutcome
     (see `call-outcome`)."))

(defn call-outcome
  "CallOutcome {:value :error?} of a JSON-RPC response to tools/call:
   :value is the structured content, :error? is true for a tool error
   result and for a JSON-RPC error alike (both mean the call did not
   happen as asked). Pure."
  [response]
  {:value  (get-in response [:result :structuredContent])
   :error? (boolean (or (get-in response [:result :isError]) (:error response)))})

(defn texts
  "The texts of the todos `agent` sees."
  [agent]
  (set (map :text (get-in (-call agent "todos_visible" {}) [:value :result]))))

(defn ledger-facts
  "#{[actor tool outcome]} of the tool calls the ledger recorded, read as
   `agent`. Outcomes arrive as keywords in process and as strings over
   JSON; both become keywords."
  [agent]
  (set (map (juxt :actor :tool (comp keyword :outcome))
            (get-in (-call agent "autonomous_ledger" {:kind "autonomous/call" :limit 1000})
                    [:value :result]))))

(defn swarm!
  "Assert the swarm story. `agent-on` is (fn [actor runtime-id] Agent): an
   agent session for `actor` attached to that runtime. r1 and r2 are two
   distinct runtimes of the todo app. `tag` makes this run's todo texts
   unique, so runtimes reused across runs do not confuse the isolation
   checks."
  [agent-on [r1 r2] tag]
  (let [a      (agent-on "agent-a" r1)
        b      (agent-on "agent-b" r2)
        in-a   (str "only in A " tag)
        in-b   (str "only in B " tag)
        held   (str "blocked " tag)
        after  (str "after release " tag)]
    (testing "agents on different instances do not see each other's work"
      (is (not (:error? (-call a "todo_add" {:text in-a}))))
      (is (not (:error? (-call b "todo_add" {:text in-b}))))
      (is (contains? (texts a) in-a))
      (is (contains? (texts b) in-b))
      (is (not (contains? (texts a) in-b)))
      (is (not (contains? (texts b) in-a))))
    (testing "agents on one instance coordinate with a lease"
      (let [b1 (agent-on "agent-b" r1)]
        (is (= "agent-a" (:actor (:value (-call a "autonomous_lease" {:ttl_seconds 30})))))
        (is (true? (:error? (-call b1 "todo_add" {:text held}))) "another writer is refused")
        (is (map? (:value (-call b1 "todos_stats" {}))) "reads stay open")
        (is (not (:error? (-call a "autonomous_release" {}))))
        (is (not (:error? (-call b1 "todo_add" {:text after}))))
        (is (contains? (texts a) after))
        (is (not (contains? (texts a) held)))))
    (testing "the ledger attributes every call, refusals included"
      (let [facts (ledger-facts a)]
        (is (contains? facts ["agent-a" "todo_add" :ok]))
        (is (contains? facts ["agent-b" "todo_add" :ok]))
        (is (contains? facts ["agent-b" "todo_add" :leased]))))))
