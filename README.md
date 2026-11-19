# autonomous

Make an application agent-operable. The app declares what an agent may **do**
(tools) and **see** (resources), in its own vocabulary, inside the running
program. A bridge exposes that surface over MCP, so any MCP client (Claude
Code, a hive swarm, your own agent) can drive the app: locally over stdio,
remotely over authenticated HTTP.

- **UI first:** a re-frame adapter turns events into tools and subscriptions
  into read-only tools. Events go through re-frame, so interceptors, effects
  and the screen behave exactly as when a human clicks.
- **Backend next:** the runtime core is `.cljc`. A Clojure service registers
  tools the same way and is reached through its nREPL or in-process.
- **Swarm-ready:** every agent has its own identity and session. Agents spread
  over an app's instances (tabs, processes) or share one under write leases.
  A journal and a durable ledger (datahike) record who did what.
- **Secure by default:** scopes on every tool, least privilege when none is
  granted, hashed bearer tokens, Origin checks, and a bridge that refuses a
  public bind without tokens. The nREPL never leaves the machine.

```
 agent(s) ──MCP──▶ bridge (JVM) ──nREPL / in-process──▶ runtime (browser tab, node, JVM)
                    sessions, auth,                       autonomous.core registry
                    ledger (datahike)                     + adapters (re-frame, ...)
```

## Quickstart: a re-frame app

Add the dependency to the app build (ClojureScript; malli comes along):

```clojure
;; deps.edn of the app, alias used by shadow-cljs
io.github.hive-agi/autonomous {:mvn/version "0.1.0-SNAPSHOT"}
```

Declare the agent surface, next to your events and subs:

```clojure
(ns todo.agentic
  (:require [autonomous.core :as a]
            [autonomous.re-frame :as arf]))

(defn install! [dev?]
  (a/set-app-info! {:name "todo"
                    :description "A todo list. Add with todo_add, read todos_visible."})

  (arf/reg-event-tool :todo/add
    {:description "Add a todo item. Answers the visible todos."
     :input [:map [:text [:string {:min 1 :max 200}]]]       ; malli
     :args->event (fn [{:keys [text]}] [:todo/add text])
     :result-sub [:todos/visible]})

  (arf/reg-sub-tool :todos/visible
    {:description "The todos currently visible."})

  (arf/expose-db! {:paths [[:todos] [:filter]] :redact [[:user :email]]})
  (arf/observe-events! {:payloads? true})   ; journal what the human does too
  (when dev? (arf/install-dev-tools!)))     ; :admin-only generic dispatch/db tools
```

Call `(todo.agentic/install! goog.DEBUG)` from your init. Run the app as
usual (`shadow-cljs watch app`), open it in a browser, then start the bridge:

```sh
clojure -M:serve --dir ../todo          # stdio MCP, finds .shadow-cljs/nrepl.port
```

Register it with your MCP client, e.g. Claude Code:

```sh
claude mcp add todo -- clojure -Sdeps '{:deps {io.github.hive-agi/autonomous {:mvn/version "0.1.0-SNAPSHOT"}}}' \
  -M -m autonomous.cli serve --dir /path/to/todo
```

The agent now sees `todo_add`, `todos_visible` and the session tools below.
Input schemas come from your malli `:input` (as JSON Schema); arguments are
decoded leniently (`"2"` for an int, `"done"` for `:done`) and then validated,
so a wrong call is refused with a message that says what to change.

### Session tools (every server)

| tool | what |
|---|---|
| `autonomous_whoami` | actor, scopes, attached instance |
| `autonomous_runtimes` | running instances of the app (tabs, node processes) |
| `autonomous_attach` | pin this session to one instance |
| `autonomous_journal` | recent events of the instance: tool calls, leases, the human's re-frame events |
| `autonomous_ledger` | durable, queryable record across instances, reloads and agents |
| `autonomous_poll` | collect a result that is still running (async tools) |
| `autonomous_lease` / `autonomous_release` | exclusive write access for a multi-step change |

## Plain tools and the backend

Any program that runs `autonomous.core` can register tools; re-frame is one
adapter, not a requirement.

```clojure
(require '[autonomous.core :as a])

(a/reg-tool :orders/refund
  {:description "Refund an order. Money moves: confirm the amount first."
   :input  [:map [:order-id :uuid] [:cents [:int {:min 1}]]]
   :output [:map [:refund-id :uuid]]
   :scopes [:billing]                       ; app-defined scope
   :annotations {:destructiveHint true}
   :handler (fn [{:keys [order-id cents]} grant]
              (refund! order-id cents (:actor grant)))})   ; may return a future
```

A Clojure service is reached through its nREPL (`--clj`, port from
`.nrepl-port`), or embeds the bridge in-process with the `:local` target:

```clojure
(require '[autonomous.cli :as cli] '[autonomous.mcp.http :as http])
(http/start! (cli/build-server {:target {:target/kind :local :target/id "orders"}})
             {:bind "127.0.0.1" :port 7777 :tokens tokens})
```

Extend with an open multimethod: `(defmethod autonomous.runtime.invoke/invoke-kind :my/kind ...)`
adds a tool kind; `autonomous.core/use!` adds middleware around every call;
`autonomous.bridge.channel/make` adds a transport; `autonomous.bridge.ledger/make`
adds a ledger store.

## Remote, safely

The nREPL is remote code execution by design. **It stays on the machine.**
Run the bridge next to the app and expose only the MCP endpoint, which offers
nothing but the scoped tools the app declared.

```sh
# 1. one token per agent (shown once; config stores only its SHA-256)
clojure -M:token --actor agent-1 --scopes read,write

# 2. serve MCP over HTTP with those tokens
AUTONOMOUS_TOKENS='[{:sha256 "…" :actor "agent-1" :scopes [:read :write]}]' \
  clojure -M:datahike:serve --dir ../todo --http 127.0.0.1:7777 --ledger datahike --datahike-path .autonomous/ledger

# 3. reach it from elsewhere through a tunnel, not an open port
ssh -N -L 7777:127.0.0.1:7777 dev-box        # or Tailscale / WireGuard
```

What the HTTP transport enforces, in order: a non-loopback bind without
tokens refuses to start; a request with a foreign `Origin` gets 403 (DNS
rebinding); a missing or unknown bearer token gets 401; a session id is
honoured only for the actor that opened it (404 otherwise). Scopes are checked
again inside the runtime, and a request carrying no scopes gets `#{:read}`.

Secrets: run the bridge itself as a hive-compose host program and let
compose inject the token entries from `pass`
(`:program/command ["clojure" "-M:serve" "--dir" "../todo" "--http" "127.0.0.1:7777"]`,
`:program/env {"AUTONOMOUS_TOKENS" {:pass "autonomous/todo/tokens"}}`), or use
any secret store that sets the variable.

## Swarms

- **Parallel:** open N instances (tabs, or `node target/e2e/main.js` for a
  headless runtime). Each agent calls `autonomous_runtimes`, then
  `autonomous_attach` to its own instance. No interference.
- **Shared:** agents on one instance coordinate with `autonomous_lease`: the
  holder writes, everyone keeps reading, other writers get a `leased` refusal
  naming the holder until release or expiry.
- **Memory:** `autonomous_ledger` answers "what did agent-3 do in the last
  ten minutes", across reloads. With datahike, history is kept (`as-of`).

## hive

`hive/` is an IAddon for hive-mcp (tool `autonomous`); the core library never
depends on hive. Configure apps in `config.edn`:

```clojure
:addons {"autonomous" {:autonomous/apps
                       [{:app/id "todo"
                         :app/dir "~/PP/todo"                 ; finds its nREPL port file
                         :app/scopes [:read :write]
                         :app/grants {"ling-reviewer" [:read]} ; per-agent override
                         :app/ledger {:ledger/kind :datahike}}]}}
```

```
autonomous command=apps
autonomous command=runtimes app=todo
autonomous command=attach   app=todo runtime_id=3 agent_id=<ling>
autonomous command=call     app=todo tool=todo_add args={"text":"milk"} agent_id=<ling>
autonomous command=ledger   app=todo actor=<ling>
```

Every hive agent gets its own session per app, keyed by `agent_id`, so a ling
swarm spreads over instances or shares one under leases, and the ledger says
which ling did what.

**hive-compose:** declare the app as a host program of a compose project
(`:program/dir "../todo"`). Compose starts `shadow-cljs watch` with its
nREPL; the program writes `.shadow-cljs/nrepl.port`; `:app/dir` (or
`--dir`) reads it. Nothing else to wire.

## Architecture

Stratified, CPPB (collect, promote, pipeline, boundary), DDD value objects in
malli, SOLID ports:

| stratum | namespaces | role |
|---|---|---|
| domain (pure, cljc) | `autonomous.domain.*` | ubiquitous language (`schema`), naming, scope algebra, leases, journal ring, wire values, tool compilation, call planning, manifest |
| runtime (cljc) | `autonomous.runtime.registry` / `invoke` / `rpc` | the only state (collect), the invocation pipeline, the EDN entry every bridge speaks (boundary, never throws) |
| adapters | `autonomous.re-frame` (+ pure `.model`) | events, subs, app-db, event origin |
| bridge (JVM) | `autonomous.bridge.*` | ports (`IRuntimeChannel`, `IRuntimeDirectory`, `ILedger`), nREPL and local channels, sessions, auth, client pipeline, ledgers |
| MCP | `autonomous.mcp.*` | pure JSON-RPC mapping, built-in session tools, stdio and HTTP transports |
| entry | `autonomous.config`, `autonomous.cli` | collect + promote config, serve |

The decision about a call (`autonomous.domain.decision/plan-call`) is a pure
function of a registry snapshot, the args, the grant and the time; app tools
and built-in tools go through the same plan.

## Develop and test

Tests run in warm REPLs (hive-test trifectas: golden, property, mutation).

```clojure
;; JVM suite (aliases :test:nrepl)
(clojure.test/run-all-tests #"autonomous\..*-test")

;; ClojureScript suite (aliases :cljs:nrepl), then `node target/cljs-test/test.js`
(shadow.cljs.devtools.server/start!) (shadow.cljs.devtools.api/compile :test)
```

Run the live e2e: `(shadow.cljs.devtools.api/watch :e2e)`, start two
`node target/e2e/main.js`, then run `autonomous.e2e.live-test` (kaocha
`:integration`). It proves isolation across instances, lease coordination and
ledger attribution over the real nREPL channel.

## Status

Experimental (0.1.0-SNAPSHOT). Next: a websocket channel the page dials out on
(production, no nREPL), resource subscriptions, a Playwright-backed visual
check tool via hive-cljs, re-frame2 frames.
