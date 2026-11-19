# Swarms

Several agents can operate one app at once. Each has its own identity, so the
app can tell them apart, and two patterns keep them from stepping on each
other: spread them over instances, or share one instance under leases.

## Identity first

Every agent needs its own actor name:

- over HTTP, one bearer token per agent (see [security.md](security.md)),
- over stdio, one bridge process per agent, each with its own `--actor`,
- in hive, the `agent_id` each ling calls with (see [hive.md](hive.md)).

The bridge stamps that actor on every request, and the runtime journals it
with every call, lease and settled result. That is what makes the journal and
the ledger answer "who did what".

## Parallel: one instance per agent

An app usually has several **runtimes** reachable through one target: every
browser tab and every node process connected to the same shadow-cljs build.
Each runtime has its own registry, app-db, journal and lease, so agents on
different runtimes cannot interfere.

1. Start N instances: open N tabs, or run the headless example runtime N
   times (`node target/e2e/main.js` after compiling the `:e2e` build).
2. Each agent calls `autonomous_runtimes` and picks one.
3. Each agent calls `autonomous_attach` with that `runtime_id`.

From then on the session's calls go to that runtime only. Calling
`autonomous_attach` with no `runtime_id` unpins the session, and its calls go
to the target's default runtime again.

A Clojure process (`--clj`) or an in-process target has exactly one runtime,
so for a backend the parallel pattern means one process per agent.

## Shared: one instance, leases for writes

When agents must work on the same state, they share one runtime and take
turns writing:

1. The agent about to make a multi-step change calls `autonomous_lease`
   (`ttl_seconds`, default 60, at most 3600).
2. While the lease is live, other agents' calls to tools that change things
   are refused with `:leased`, and the refusal names the holder. Read-only
   tools stay open to everyone.
3. The holder renews by calling `autonomous_lease` again, and ends it with
   `autonomous_release`. A lease also ends on its own when its time runs out,
   so a crashed agent never blocks the others for long.

Only one lease exists per runtime. A second agent asking for a live lease gets
a `:leased` refusal and should wait, read, or back off and retry.

## Watching each other

- `autonomous_journal` shows recent events of the attached runtime: every
  agent's calls with their outcome, leases, and, with `observe-events!`, the
  human's own re-frame events (`origin :app`). Pass `since` with the last
  `seq` you saw to read only what is new.
- `autonomous_ledger` is the durable record across runtimes, reloads and
  agents. Filter by `actor`, `kind`, `tool` and a `since` / `until` window in
  epoch milliseconds. A refused call is recorded too, with its outcome (for
  example `leased`), so the ledger shows who was blocked and when.

Typical questions:

```json
{"actor": "agent-3", "since": 1795060000000}
{"kind": "autonomous/call", "tool": "todo_remove"}
{"kind": "re-frame/event", "limit": 20}
```

With the datahike ledger (`--ledger datahike --datahike-path PATH`, on the
`:datahike` alias), entries survive a bridge restart and history is kept.

## What the live test proves

`autonomous.e2e.live-test` (the kaocha `:integration` suite) runs the swarm
story against real node runtimes of the example app, over the real nREPL
channel:

- two agents attached to two runtimes do not see each other's todos,
- on one runtime, a lease held by agent A refuses agent B's write, B can
  still read, and B's write goes through after A releases,
- the ledger records B's refused call with the outcome `leased`.

To run it, from a checkout:

```sh
npm install
clojure -M:cljs -m shadow.cljs.devtools.cli watch e2e   # writes .shadow-cljs/nrepl.port
node target/e2e/main.js &                                # two runtimes
node target/e2e/main.js &
clojure -M:test integration
```

Without two connected runtimes the test prints why it skipped and passes.
