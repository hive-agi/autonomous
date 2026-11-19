# The bridge on babashka and ClojureWasm

The MCP bridge is one source tree that runs on three hosts. Pick the
cheapest one that does what you need:

| host | start | stdio | HTTP | tokens | ledger | start time |
|---|---|---|---|---|---|---|
| babashka 1.13+ | `bb serve` | yes | yes (http-kit) | yes | memory | well under a second |
| ClojureWasm 1.15+ | `cljw -A:cljw -m autonomous.cli serve` | yes | no | no | memory | well under a second |
| JVM (Clojure 1.12) | `clojure -M:serve` | yes | yes (JDK server) | yes | memory, datahike | seconds |

A babashka bridge is one small process per agent (or per fleet), which is
what makes it practical to give every agent of a swarm its own bridge. The
app side is unchanged: the bridge reaches the app's nREPL (shadow-cljs for
a ClojureScript app, any nREPL for a Clojure service) and calls
`autonomous.core/rpc-str` there, whatever host the bridge runs on.

## Tasks

Run from the repository root (`bb tasks` lists them):

| task | what |
|---|---|
| `bb serve --dir ../todo` | MCP over stdio for the app in `../todo` |
| `bb serve --dir ../todo --http 127.0.0.1:7777` | MCP over HTTP |
| `bb token --actor agent-1 --scopes read,write` | mint a bearer token and print its config entry |
| `bb test` | every unit suite on babashka |
| `bb e2e` | the live end to end from a bb bridge (below) |

`bb serve` takes every option of `autonomous.cli` (`--build`, `--port`,
`--port-file`, `--clj`, `--actor`, `--scopes`, `--ledger memory`,
`--timeout-ms`, `--config`). Only stdout carries MCP in stdio mode; logs go
to stderr.

Register it with an MCP client the same way as the JVM bridge, e.g. Claude
Code, from a checkout of this repository:

```sh
claude mcp add todo -- bb --config /path/to/autonomous/bb.edn serve --dir /path/to/todo
```

## HTTP on babashka

`bb serve --http` runs the same security pipeline as the JVM bridge. The
decision code is shared (`autonomous.mcp.http/route`, pure); only the
socket adapter differs (http-kit on bb, the JDK server on the JVM).

1. A non-loopback bind without tokens refuses to start (exit code 3).
2. A request whose `Origin` is not in `:allowed-origins` gets 403.
3. With tokens configured, a missing or unknown bearer token gets 401.
   Tokens are compared by SHA-256 hash, in constant time over every entry.
4. A session id is honoured only for the actor that opened it (404
   otherwise).
5. Sessions are bounded: one unused for an hour is forgotten, and at most
   1024 are kept (least recently used first out).
6. A body over 1 MiB gets 413; any failure is a 500, never a dropped
   connection.

A port that is taken, or an address that cannot be bound, ends `serve`
with a message (exit code 3) instead of a stack trace. SIGINT and SIGTERM
stop the server gracefully (in-flight requests finish, up to 5 seconds).

```sh
bb token --actor agent-1 --scopes read,write      # shown once; keep the token
AUTONOMOUS_TOKENS='[{:sha256 "..." :actor "agent-1" :scopes [:read :write]}]' \
  bb serve --dir ../todo --http 127.0.0.1:7777
```

As with the JVM bridge, bind to loopback and reach it through a tunnel
(ssh, Tailscale, WireGuard); the app's nREPL never leaves the machine.

### What babashka does not do

- **datahike ledger.** datahike does not load on babashka, so
  `--ledger datahike` stops `serve` with a message. Use `--ledger memory`
  (the default) or a JVM bridge for a durable ledger.

## ClojureWasm (stdio only)

```sh
cljw -A:cljw -m autonomous.cli serve --dir ../todo
```

The `:cljw` alias of deps.edn adds bb-mcp, whose bencode client is the
nREPL channel on both babashka and ClojureWasm. Everything an agent does
over stdio works: session tools, app tools, leases, the journal, the
memory ledger, `tools/list_changed`.

What ClojureWasm 1.15.7 lacks, and how the bridge answers:

| gap | effect | answer |
|---|---|---|
| no HTTP server | `--http` | refuses: "no HTTP server on ClojureWasm yet" |
| no SecureRandom, base64, SHA-256 | `token`, bearer tokens | `token` refuses and points at `bb token`; HTTP needs tokens anyway |
| no datahike | `--ledger datahike` | refuses, as on babashka |

## The live end to end: `bb e2e`

`bb e2e` proves the bb host against real runtimes:

1. shadow-cljs: reuses the server whose `.shadow-cljs/nrepl.port`
   answers, otherwise starts one (one JVM, `-Xmx2g`), and watches build
   `:e2e`. It runs `npm install` once if `node_modules` is missing.
2. Starts `bb serve --http` on a free loopback port with a freshly minted
   bearer token per agent.
3. Starts two headless runtimes of the example todo app
   (`node target/e2e/main.js`) and drives only those two, even when the
   shadow server already has other runtimes connected.
4. Over MCP HTTP, as real authenticated agents
   (`autonomous.e2e.scenario`, the same assertions the JVM live test
   makes):
   - isolation: agents on different instances do not see each other's
     todos;
   - leases: while agent-a holds a lease on an instance, agent-b's writes
     there are refused and its reads still work; after release, its write
     goes through;
   - ledger: every call is attributed, the `leased` refusal included;
   - security, live: an unknown token gets 401, a read-only token cannot
     write but can read.
5. A stdio smoke conversation through one runtime on `bb serve`, and on
   `cljw` when it is on the PATH (initialize, tools/list, runtimes,
   attach, an app tool).

Everything it started (bridge, node runtimes, a shadow server it started
itself) is stopped on the way out, Ctrl-C included. Logs are in
`target/e2e-logs/`. Exit code 0 means every check passed.

```
$ bb e2e
[e2e] starting shadow-cljs (one JVM, -Xmx2g)
[e2e] watching build :e2e
[e2e] starting the bb bridge on 127.0.0.1: 40487
[e2e] starting two node runtimes of the todo app
[e2e] driving runtimes 4 and 5 through MCP
[e2e] stdio smoke on bb
[e2e] stdio smoke on cljw
e2e over MCP HTTP: 19 assertions, 0 failures, 0 errors
stdio smoke bb: ok
stdio smoke cljw: ok
```

The JVM live test (`autonomous.e2e.live-test`, kaocha `:integration`)
drives the same scenario through an in-process server; run it while a
shadow watch of `:e2e` and two node runtimes are up.

## Tests on babashka

`bb test` runs every unit suite the JVM kaocha `:unit` suite runs: the
domain and bridge trifectas (hive-test golden, property and mutation
facets, hive-schemas trifectas included), the MCP transport suite with a
real http-kit socket round trip, the runtime RPC suite, the re-frame model
and the e2e rules. Only the datahike ledger contract is JVM-only (a reader
conditional in `bridge_trifecta_test.cljc`); the memory ledger passes the
same contract on both.
