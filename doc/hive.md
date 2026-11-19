# hive

`autonomous` works with any MCP client. In a hive setup it also mounts as an
IAddon of hive-mcp, so every hive agent (a coordinator or a ling) can operate
apps without starting a bridge of its own.

The core library never depends on hive. The integration is a separate module
in `hive/` (namespaces `autonomous.hive.addon` and `autonomous.hive.apps`),
built on the released `io.github.hive-agi/autonomous` and `hive-addon`. It
is not published to Clojars yet; mount it from a checkout.

## The `autonomous` addon

The manifest (`hive/resources/META-INF/hive-addons/autonomous.edn`) declares
addon id `autonomous`, constructor `autonomous.hive.addon/addon-ctor`,
capabilities tools and health reporting, maturity experimental, license MIT.

The addon publishes one tool, `autonomous`, with a command per operation:

| command | params | what |
|---|---|---|
| `help` | | the command list |
| `apps` | | the configured apps and their targets |
| `tools` | `app` | the tools the app offers you |
| `call` | `app`, `tool`, `args` | call an app tool |
| `read` | `app`, `uri` | read an app resource |
| `runtimes` | `app` | running instances of the app |
| `attach` | `app`, `runtime_id` | pin your session to one instance (omit `runtime_id` to unpin) |
| `whoami` | `app` | your actor, scopes and pinned instance |
| `journal` | `app`, `since`, `limit` | recent events of your instance |
| `ledger` | `app`, `kind`, `tool`, `since`, `until`, `limit` | the durable record |
| `poll` | `app`, `id` | collect a still-running result |
| `lease` | `app`, `ttl_seconds` | exclusive write access to your instance |
| `release` | `app` | end your lease |

Every command also takes `agent_id`. The commands from `runtimes` down are the
session tools of [concepts.md](concepts.md) (`autonomous_runtimes` and so on),
run through the same MCP server pipeline as `autonomous serve`, inside the
hive-mcp JVM.

One difference from the MCP tool: the addon reads `actor` as the caller's
identity (when there is no `agent_id`), not as a ledger filter, so
`command=ledger` cannot filter by actor yet. Filter by `kind` or `tool` and
read the `actor` field of each entry.

## Sessions per agent

The addon keeps one session per app and per agent, keyed by the `agent_id`
the agent calls with (or `actor`, else `hive`). The `agent_id` is also the
actor the app's journal and ledger record. So a ling swarm can spread over an
app's instances (each ling attaches to its own) or share one under leases, and
the ledger says which ling did what. See [swarms.md](swarms.md).

```
autonomous command=apps
autonomous command=runtimes app=todo agent_id=ling-1
autonomous command=attach   app=todo runtime_id=3 agent_id=ling-1
autonomous command=call     app=todo tool=todo_add args={"text":"milk"} agent_id=ling-1
autonomous command=ledger   app=todo kind=autonomous/call agent_id=coordinator
```

## Configuring apps

Apps live under the addon's entry in the hive-mcp `config.edn`:

```clojure no-run
:addons {"autonomous" {:autonomous/apps
                       [{:app/id     "todo"
                         :app/dir    "~/PP/todo"                ; finds its nREPL port file
                         :app/build  :app                       ; optional shadow build
                         :app/scopes [:read :write]             ; default grant for every agent
                         :app/grants {"ling-reviewer" [:read]}  ; per-agent override
                         :app/ledger {:ledger/kind :datahike}}]}}
```

| key | meaning | default |
|---|---|---|
| `:app/id` | the name agents pass as `app` | required |
| `:app/dir` | app directory; the target derives from it as for `serve --dir` (`~` expands) | |
| `:app/build` | shadow-cljs build | the first build in `shadow-cljs.edn` |
| `:app/target` | an explicit target map, instead of `:app/dir` | |
| `:app/scopes` | scopes every agent gets | `[:read :write]` |
| `:app/grants` | per-agent scopes, by agent id | |
| `:app/ledger` | ledger config, `{:ledger/kind :memory}` or `:datahike` | memory |

An agent's scopes are its own entry in `:app/grants`, else `:app/scopes`, else
read and write. nREPL connections open lazily, on the first call, and the
addon's health lists the configured apps.

## hive-compose

hive-compose starts a project's containers and its host programs. An app built
with `autonomous` is just a host program: declare its directory, and compose
starts it with its nREPL.

```clojure no-run
{:project/id "todo"
 :project/dir "~/PP/todo"
 :project/programs [{:program/id "todo-web" :program/dir "."}]}
```

For a directory with a `shadow-cljs.edn`, compose runs
`npx shadow-cljs watch` on every build, and shadow-cljs writes
`.shadow-cljs/nrepl.port`. The addon's `:app/dir` (or `serve --dir`) reads
that file on every call, so nothing else needs wiring, and a restarted app is
found again on its new port.

compose can also run the bridge itself as a program and inject its token
entries from pass; see [security.md](security.md).

## Developing the addon

```sh
cd hive
clojure -M:test            # kaocha suite of the addon
```

To work on the core and the addon at once, point
`io.github.hive-agi/autonomous` at the local checkout from a `local.deps.edn`,
which `.gitignore` keeps out of commits, so the declared dependency stays on
the released version.
