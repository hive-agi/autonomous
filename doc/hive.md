# hive

`autonomous` works with any MCP client. In a hive setup it also mounts as an
IAddon of hive-mcp, so every hive agent (a coordinator or a ling) can operate
apps without starting a bridge of its own.

The core library never depends on hive. The integration is a separate module
in `hive/` (namespaces `autonomous.hive.addon` and `autonomous.hive.apps`),
built on the released `io.github.hive-agi/autonomous` (0.1.3 or later, which
has fleet mode) and `hive-addon`. It is not published to Clojars yet; mount
it from a checkout.

## The `autonomous` addon

The manifest (`hive/resources/META-INF/hive-addons/autonomous.edn`) declares
addon id `autonomous`, constructor `autonomous.hive.addon/addon-ctor`,
capabilities tools and health reporting, maturity experimental, license MIT.

The addon serves every configured app as one [fleet](fleet.md): one
`autonomous.bridge.fleet` over all the apps, one shared ledger, served by
`autonomous.mcp.server/fleet-server`. This is the same pipeline as
`autonomous serve` with several targets, running inside the hive-mcp JVM.

It publishes one tool, `autonomous`, with a command per operation:

| command | params | what |
|---|---|---|
| `help` | | the command list |
| `apps` | | every app: status (`up`, `down`, `slow`), tool prefix, tool count, manifest version, error, target |
| `tools` | `app` (optional) | the tools you may call, named `<app>__<tool>`; `app` narrows to one app |
| `call` | `app`, `tool`, `args` | call an app tool; or `tool` = `<app>__<tool>` without `app` |
| `read` | `app`, `uri` | read an app resource; or `uri` = `autonomous://<app>/<uri>` without `app` |
| `runtimes` | `app` | running instances of the app |
| `attach` | `app`, `runtime_id` | pin your session to one instance of the app (omit `runtime_id` to unpin) |
| `whoami` | | your actor, scopes and the instance pinned on each app |
| `journal` | `app`, `since`, `limit` | recent events of your instance of the app |
| `ledger` | `app`, `by_actor`, `kind`, `tool`, `since`, `until`, `limit` | the durable record of the whole fleet |
| `poll` | `id` (`app` optional) | collect a still-running result, by the id the call answered (`<app>:<id>`) |
| `lease` | `app`, `ttl_seconds` | exclusive write access to your instance of the app |
| `release` | `app` | end your lease on the app |

Every command also takes `agent_id`, the caller. The commands from `runtimes`
down are the session tools of [concepts.md](concepts.md) and
[fleet.md](fleet.md) (`autonomous_runtimes` and so on); `apps` is
`autonomous_apps`. The fleet has several apps, so the per-app ones need `app`
(passed to the tool as its `target`); a command that omits it is refused with
`target/required` and the known app ids.

## Routing

`call app=todo tool=todo_add` and `call tool=todo__todo_add` are the same
call: the addon turns an app and the tool's own name into the fleet name
(`<prefix>__<tool>`, the prefix being the app id with `_` and `.` turned into
`-`), and the fleet routes it to the owning app. Resources are the same
(`autonomous://<app>/<uri>`), and a pending result's id carries its app
(`todo:p7`).

Every app is probed at once, waiting at most `:autonomous/probe-ms` (default
1500) for all of them. An app that is down (no port file, connection refused)
is listed `down` with the reason, its tools disappear from `tools`, and a call
to it is refused at once; a slow app is listed `slow`. Neither delays the
other apps.

## Sessions per agent

Each agent has one session across the fleet, keyed by its `agent_id`
(`hive` without one). The `agent_id` is also the actor every app's journal and
the ledger record. Attaching is per app: attaching on `todo` leaves your pin
on `orders` alone, and `whoami` shows the pin on each app. So a ling swarm can
spread over an app's instances (each ling attaches to its own) or share one
under leases, and the ledger says which ling did what on which app. See
[swarms.md](swarms.md).

The session's scopes are the union of the agent's grants on every app, and
each request is narrowed to the agent's grant on the app it goes to, so a
per-app grant still holds: an agent that may only read `todo` is not offered
`todo`'s write tools, and its writes there are refused.

## The ledger

The fleet keeps one ledger. Every row says its app under `source.target`.

- `app` reads one app's rows;
- `by_actor` reads what one agent did: `ledger by_actor=ling-2` is "what did
  ling-2 do";
- `kind`, `tool`, `since`, `until` and `limit` filter as in
  `autonomous_ledger`.

`agent_id` is always the caller and never a filter, so a coordinator asks
about a ling with its own `agent_id` and the ling's id in `by_actor`.

```
autonomous command=apps
autonomous command=runtimes app=todo agent_id=ling-1
autonomous command=attach   app=todo runtime_id=3 agent_id=ling-1
autonomous command=call     app=todo tool=todo_add args={"text":"milk"} agent_id=ling-1
autonomous command=call     tool=orders__place args={"sku":"a1","qty":2} agent_id=ling-1
autonomous command=ledger   by_actor=ling-1 kind=autonomous/call agent_id=coordinator
```

## Configuring apps

Apps live under the addon's entry in the hive-mcp `config.edn`:

```clojure no-run
:addons {"autonomous" {:autonomous/apps
                       [{:app/id     "todo"
                         :app/dir    "~/PP/todo"                ; finds its nREPL port file
                         :app/build  :app                       ; optional shadow build
                         :app/scopes [:read :write]             ; default grant for every agent
                         :app/grants {"ling-reviewer" [:read]}} ; per-agent override
                        {:app/id     "orders"
                         :app/target {:target/kind :nrepl :nrepl/port 7001 :nrepl/mode :clj}}]
                       :autonomous/ledger   {:ledger/kind :datahike}
                       :autonomous/probe-ms 1500}}
```

| key | meaning | default |
|---|---|---|
| `:app/id` | the name agents pass as `app`, and the app's tool prefix | required |
| `:app/dir` | app directory; the target derives from it as for `serve --dir` (`~` expands) | |
| `:app/build` | shadow-cljs build | the first build in `shadow-cljs.edn` |
| `:app/target` | an explicit target map, instead of `:app/dir` | |
| `:app/scopes` | scopes every agent gets on this app | `[:read :write]` |
| `:app/grants` | per-agent scopes on this app, by agent id | |
| `:app/call-timeout-ms` | how long a call waits before answering "still running" | 30000 |
| `:autonomous/ledger` | the fleet's ledger, `{:ledger/kind :memory}` or `:datahike` | the first `:app/ledger`, else memory |
| `:autonomous/probe-ms` | how long one look at the fleet waits for an app | 1500 |

App ids follow the fleet rules: 1 to 48 of `[A-Za-z0-9._-]`, unique, and no
two sharing a prefix (`a_b` and `a.b` do). A config that breaks them does not
start, and the addon's health says why.

An agent's scopes on an app are its own entry in `:app/grants`, else
`:app/scopes`, else read and write. nREPL connections open lazily, on an
app's first use, and the addon's health lists the configured apps.

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
found again on its new port (it shows `down` in `apps` until then).

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
