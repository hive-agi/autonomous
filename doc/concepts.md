# Concepts

`autonomous` has two halves. The **runtime** lives inside your program
(`autonomous.core`, `.cljc`) and holds a registry of what agents may do and
see. The **bridge** lives next to it (`autonomous.cli serve`) and speaks MCP to
agents. Every word below is defined as a malli value object in
`autonomous.domain.schema` or `autonomous.bridge.schema`.

## Tools

A tool is something an agent may do. Register one with `reg-tool`:

```clojure
(require '[autonomous.core :as a])

(a/reg-tool :orders/refund
  {:description "Refund an order. Money moves: confirm the amount first."
   :input  [:map [:order-id :uuid] [:cents [:int {:min 1}]]]
   :output [:map [:refund-id :uuid]]
   :scopes [:billing]
   :annotations {:destructiveHint true}
   :handler (fn [{:keys [order-id cents]} grant]
              (refund! order-id cents (:actor grant)))})
;; => "orders_refund"
```

| key | meaning | default |
|---|---|---|
| `:description` | what the tool does, written for the agent | required |
| `:input` | malli schema of the args map; must describe a map | `[:map]` |
| `:output` | malli schema of the result, advertised as `outputSchema` | none |
| `:handler` | `(fn [args grant] value)`; may return a promise or future | required for `:kind :fn` |
| `:kind` | invocation strategy (see Extending) | `:fn` |
| `:read-only?` | true when the tool changes nothing | `false` |
| `:scopes` | scopes the caller needs | `#{:read}` if read-only, else `#{:write}` |
| `:name` | MCP name override | derived from the id |
| `:title`, `:annotations`, `:json-schema` | MCP extras; `:json-schema` replaces the derived schema | |

The MCP name derives from the id: `:todo/add` becomes `todo_add`. Every
character outside `[A-Za-z0-9_-]` becomes `_`, and the name is cut at 64
characters; hyphens stay (`:todos/clear-done` is `todos_clear-done`).
Registering a second tool under a name another tool uses throws. The whole
rule, with fleet names, is in [naming](naming.md).
`reg-tool` answers the name; `unreg-tool` takes the id or the name.

An invalid spec throws at registration, so an author error fails while the app
loads, not when an agent calls.

### Arguments

Agents send JSON, and language models often quote numbers. Arguments are
decoded leniently first (JSON rules, then string rules: `"2"` becomes `2`,
`"done"` becomes `:done` for an enum, then malli defaults) and validated
after. A value of the wrong type is still refused, with an `:invalid-args`
refusal that carries malli's humanized explanation, so the agent knows what
to change.

### How a call is decided

`autonomous.domain.decision/plan-call` is a pure function of a registry
snapshot, the tool name, the args, the grant and the time. It checks, in
order:

1. the tool exists (`:unknown-tool`, with the known names),
2. the grant's scopes cover the tool's (`:forbidden`),
3. no other actor holds a live lease, unless the tool is read-only (`:leased`),
4. the args conform to the input schema (`:invalid-args`).

The built-in `autonomous_*` tools go through the same plan.

### Answers

Every call answers one of three shapes:

- **Ok**: `{:ok value}`. MCP gets the value as JSON text and as
  `structuredContent` (a non-map value is wrapped as `{:result v}`).
- **Pending**: `{:pending id}`. The handler returned a promise or future that
  had not settled. The bridge polls it with backoff (25 ms, doubling to
  500 ms) until the call timeout (30 s by default, `--timeout-ms` to change).
  A result still running after that is answered as pending, and the agent
  collects it later with `autonomous_poll`. A parked result is kept for
  10 minutes.

A re-frame event tool with `:async? true` answers `{:queued event}` at once,
without waiting for the event to run.
- **Refusal**: `{:error {:type :message :data}}`. MCP marks the tool result
  `isError`. A call to a tool that does not exist is a JSON-RPC
  `invalid-params` error instead.

## Resources

A resource is something an agent may read, at a URI:

```clojure
(a/reg-resource "orders://open"
  {:description "Open orders, newest first."
   :read (fn [grant] (open-orders))})
```

`:read` is required; `:name`, `:description` and `:mime` (default
`application/json`) are optional. Resources need `#{:read}` unless `:scopes`
says otherwise.

## Scopes and grants

A **scope** is a keyword. `:read` and `:write` are the usual pair, `:admin`
covers every scope, and an app may invent its own (`:billing` above). A
**grant** is `{:actor :scopes}`: who asks and what they may do. The grant's
scopes cover a tool when they contain every scope the tool requires, or
`:admin`.

A request that carries no scopes is given `#{:read}`, never more.

The manifest an agent lists (`tools/list`, `resources/list`) holds only the
tools and resources its scopes allow, so a read-only agent never sees the
write tools.

## Actors and sessions

An **actor** is the name an agent acts under. It comes from the transport:

- stdio: `--actor` (or `AUTONOMOUS_ACTOR`), default `local`, with `--scopes`
  (default `read,write`),
- HTTP: the bearer token, which names an actor and its scopes (see
  [security.md](security.md)),
- hive: the `agent_id` the agent calls with (see [hive.md](hive.md)).

A **session** is one agent's conversation with the bridge: its actor, its
scopes and the runtime it is attached to. The bridge stamps the session's
actor and scopes on every request it sends to the runtime, over anything the
request says, so an agent never speaks as someone else.

## Runtimes and targets

A **runtime** is one running program carrying a registry: a browser tab, a
node process, a JVM. A **target** is where the bridge reaches runtimes:

| target | how | runtimes |
|---|---|---|
| nREPL, mode `:shadow` | `shadow.cljs.devtools.api/cljs-eval` on a shadow-cljs build | every tab or node process connected to the build |
| nREPL, mode `:clj` | eval in the Clojure process the nREPL serves | one, `jvm` |
| `:local` | in process, no REPL | one, `local` |

`serve --dir` derives the target from the app directory: with a
`shadow-cljs.edn` the target is that build over `.shadow-cljs/nrepl.port`
(`--build` picks the build, else the first one in the file); otherwise a
Clojure process over `.nrepl-port`. `--clj` forces the Clojure mode, and
`--port`, `--port-file` and `--host` override the endpoint. The port file is
read on every call, so a restarted app is found again.

`autonomous_runtimes` lists the runtimes; `autonomous_attach` pins a session
to one.

## Leases

A **lease** gives one actor exclusive write access to one runtime until a
time. Other actors keep reading; their calls to tools that are not read-only
are refused with `:leased`, naming the holder. The refusal's `:data` is the
lease: `:actor` is the holder's raw actor, data for code to compare, never
text to display (it may hold control, bidi or zero-width characters).
Display `:shown-actor` beside it, the holder as `naming/shown-actor` shows
it, or the refusal's message. `autonomous_lease` takes
`ttl_seconds` (default 60, at most 3600); the holder may call it again to
renew. `autonomous_release` ends it, and releasing a lease you do not hold
changes nothing. Taking and releasing a lease need `:write`.

## Journal

Each runtime keeps a **journal**: a sequenced ring of its newest 500 entries.
It records:

| kind | when | fields |
|---|---|---|
| `:autonomous/call` | every tool call | `:actor :tool :outcome :ms`, `:pending` (the id) when the answer is pending, `:session :args :error` as `journal-calls!` asks |
| `:autonomous/settled` | a pending result settles | `:pending :outcome`, `:error` when `journal-calls!` asks for errors |
| `:autonomous/lease`, `:autonomous/release` | lease changes | `:actor` |
| `:re-frame/event` | `observe-events!` is on | `:event-id :origin :actor`, `:payload` if asked |
| anything | the app calls `autonomous.core/journal!` | the app's own |

`autonomous_journal` reads the attached runtime's journal: pass `since` (the
last `seq` you saw) to read only what is new, and `limit` (default 100, at
most 500). A reload starts a new **epoch** and restarts the sequence; the
answer carries the epoch, so a reader knows to start over.

## Ledger

The **ledger** is the bridge's durable, queryable record of every runtime's
journal. After each call, lease and release, the bridge drains the runtime's
journal into the ledger in the background (calls within 250 ms share one
read). `autonomous_ledger` drains first, then answers, newest first. It filters
by `actor`, `kind` (for example `autonomous/call`), `tool`, and a time window
`since` / `until` in epoch milliseconds; `limit` defaults to 100, at most 1000.

Each entry is keyed by target, runtime, time and sequence, so draining twice
never stores an entry twice, and entries from before a reload stay apart from
those after it.

Two stores ship:

| `--ledger` | store | notes |
|---|---|---|
| `memory` | an atom | the default; gone when the bridge stops |
| `datahike` | datahike | needs `io.replikativ/datahike` on the classpath (the `:datahike` alias in a checkout); `--datahike-path PATH` keeps it on disk; the store keeps history |

## The re-frame adapter

`autonomous.re-frame` maps re-frame's vocabulary onto tools:

- `reg-event-tool` exposes an event. The call dispatches through re-frame
  (`dispatch-sync`, or `dispatch` with `:async?`), so interceptors and effects
  run as for a human click. `:args->event` builds the event vector (default
  `[id]` or `[id args]`). The answer is, in order of preference, `:result`
  `(fn [db args])`, the value of the `:result-sub` query, the app-db value at
  `:result-path`, or the dispatched event.
- `reg-sub-tool` exposes a subscription as a read-only tool. It derefs the
  subscription with a component's lifecycle (flush, reaction, dispose), so it
  never reads a stale cache.
- `expose-db!` registers app-db as the resource `re-frame://app-db`, limited
  to `:paths` when given, with `:redact` paths shown as `<redacted>`.
- re-frame2 keeps one app-db per frame and refuses a bare `subscribe` outside
  a frame. Its apps load `autonomous.re-frame2` instead. It has
  `reg-event-tool`, `reg-sub-tool` and `expose-db!` (not yet `observe-events!`
  or `install-dev-tools!`), and each also takes `:frame`, a frame-id keyword such as `:app/main`. A
  call then goes through re-frame2's frame-targeted API: `dispatch` and
  `dispatch-sync` with `{:frame f}`, `subscribe-once` with `{:frame f}`, and
  `app-db-value`. A frame that is not registered when the call runs is
  refused with `:re-frame/unknown-frame`, never read as an empty app-db.
  Without `:frame`, the ambient frame (`with-frame`, `frame-provider`) is
  used. `autonomous.re-frame` has no frames and refuses `:frame` with
  `:re-frame/no-frames`.
- `observe-events!` journals every re-frame event with its origin: `:agent`
  when a tool dispatched it (the actor rides on the event's metadata), `:app`
  otherwise. Event arguments are kept only with `:payloads? true`, since they
  may carry what the human typed. `:ignore` skips high-frequency events.
- `install-dev-tools!` adds `re-frame_dispatch`, `re-frame_db` and
  `re-frame_registry`, all `:admin` only. For development; never install it in
  production.

## Plain tools and the backend

re-frame is one adapter, not a requirement. `autonomous.core` runs on the JVM
too, so a Clojure service registers tools with `reg-tool` the same way. The
bridge reaches it through the service's nREPL (`serve --dir <service> --clj`),
or the service embeds the bridge with a `:local` target:

```clojure
(require '[autonomous.cli :as cli]
         '[autonomous.mcp.http.start :as http])

(http/start! (cli/build-server {:target {:target/kind :local :target/id "orders"}
                                :ledger {:ledger/kind :memory}})
             {:bind "127.0.0.1" :port 7777 :tokens tokens})
;; => {:ok {:port 7777 :state #atom[...] :stop! #function[...]}}
```

## Extending

| seam | how |
|---|---|
| a tool kind | `(defmethod autonomous.runtime.invoke/invoke-kind :my/kind [tool args grant] ...)` |
| middleware around every call | `(autonomous.core/use! (fn [handler] (fn [tool args grant] ...)))` |
| a transport to runtimes | `(defmethod autonomous.bridge.channel/make :my/kind [target] ...)` returning an `IRuntimeChannel` and `IRuntimeDirectory` |
| a ledger store | `(defmethod autonomous.bridge.ledger/make :my/kind [cfg] ...)` returning an `ILedger` |
| more session tools | entries shaped like `autonomous.mcp.builtins/all`, passed to `autonomous.mcp.server/server` |

The `autonomous_` prefix is reserved for session tools, and an app tool cannot
shadow one.

## MCP details

The server negotiates protocol versions `2025-06-18`, `2025-03-26` and
`2024-11-05`, and answers the newest for any other request. It offers tools
and resources. The app's `set-app-info!` description opens the server's
`instructions`, so write it as the first thing an agent reads: what the app is
for and how its tools fit together. Over stdio, a watcher checks the app's
tools every two seconds and sends `notifications/tools/list_changed` when they
change (a hot reload, a new page).
