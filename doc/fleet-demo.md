# Fleet demo: one bridge, a UI and two services

One autonomous bridge drives three apps at once, each hosting its own
agent surface and reached through its own nREPL:

| app | runs on | nREPL | what the agent gets |
|---|---|---|---|
| `todo` | the browser (shadow-cljs build `:app`, page on http://localhost:8280) | shadow's, 7888 | `todo__todo_add`, `todo__todos_visible`, the app-db |
| `orders` | a JVM (`clojure -M:example/orders`) | `example/orders/.nrepl-port` | `orders__orders_place`, `orders__orders_list`, refunds (billing) |
| `inventory` | babashka (`bb --init boot.clj nrepl-server`) | 7872 | `inventory__inventory_reserve`, `inventory__inventory_item`, ... |

The scripted agent workflow (`example/fleet/probe.edn`): customer `lin`
wants two mice. The agent

1. checks stock in **inventory** (6 available) and reserves 2,
2. places the order in **orders**,
3. adds "ship order <id> to lin" to the team's list in the **todo UI**: a
   re-frame event in the browser tab, visible on the page,
4. reads it back through the UI's own subscription and app-db, finds the
   order in `orders`, is refused by the inventory domain when it
   over-promises and by the scope check when it tries a write-off (audit
   scope not granted),
5. reads the one fleet ledger, where every call is attributed to its app.

## Run it with hive-compose

The apps are declared as hive-compose host programs in the repository:
`example/fleet/hive-compose.edn` (the project and its three programs) and
`example/fleet/compose.yaml` (one do-nothing `anchor` service, because a
section starts its programs once its containers are up). hive-compose takes
its projects from the operator's config, so add this once under
`:addons {"hive.compose" {...}}` in `~/.config/hive-mcp/config.edn`
(replace `<repo>` with the absolute path of this checkout):

```clojure
{:compose/projects
 [{:project/id "autonomous-fleet"
   :project/dir "<repo>/example/fleet"
   :project/ttl-minutes 30
   :project/programs
   [{:program/id "todo"
     :program/dir "../.."
     :program/kind :shadow
     :program/command ["clojure" "-J-Xmx2g" "-M:cljs" "-m" "shadow.cljs.devtools.cli" "watch" "app"]
     :program/nrepl-port 7888}
    {:program/id "orders"
     :program/dir "../orders"
     :program/kind :plain
     :program/command "cd ../.. && exec clojure -M:example/orders"}
    {:program/id "inventory"
     :program/dir "../inventory"
     :program/kind :bb
     :program/command ["bb" "--init" "boot.clj" "nrepl-server" "127.0.0.1:7872"]
     :program/nrepl-port 7872}]}]}
```

It is the content of `example/fleet/hive-compose.edn`, which stays the
single source: `bb fleet-demo` reads the same file. Then `compose reload`,
and:

```
compose up target=autonomous-fleet/anchor     # busybox anchor + the three programs
compose programs                              # pid, log, nREPL port, nrepl-ready?
compose claim target=autonomous-fleet/anchor note="fleet demo"   # optional: hold it
```

Open http://localhost:8280 (the todo page is the UI's runtime), then run
the bridge, by hand or registered with Claude Code:

```sh
bb serve --dir . --build app --target-id todo \
         --dir example/orders --clj \
         --port 7872 --target-id inventory \
         --actor fleet-agent --scopes read,write,billing
claude mcp add fleet -- bb --config "$PWD/bb.edn" serve --dir "$PWD" --build app \
  --target-id todo --dir "$PWD/example/orders" --clj --port 7872 --target-id inventory \
  --actor fleet-agent --scopes read,write,billing
```

`compose release target=autonomous-fleet/anchor stop=true` (or `compose
down target=autonomous-fleet/anchor`) stops the programs and the anchor;
otherwise the reaper stops them after 30 idle minutes. Nothing outside
the section is touched.

## Run it without hive-compose

```sh
bb fleet-demo              # start the three programs, print the bridge command; Ctrl-C stops them
bb fleet-demo --headless   # also open the todo page in a headless Chromium
bb fleet-demo --probe      # start, open headless, run the workflow, stop everything
```

`bb fleet-demo` starts exactly the commands of `example/fleet/hive-compose.edn`,
in the same directories, with logs in `target/fleet-demo/`. A program whose
nREPL already answers (say hive-compose runs it) is reused and left running.
Two JVMs are started (shadow `-Xmx2g`, orders `-Xmx512m`), so before each
the launcher

- waits while 3 or more test-run JVMs run on the host
  (`pgrep -fc 'java.*-M:[a-z:]*test'`; services and watchers do not count; limit
  `FLEET_MAX_JVMS`, wait `FLEET_JVM_WAIT_S`, default 1200 s), and
- refuses with less than 15 GiB available (`FLEET_MIN_AVAIL_GB`).

The headless browser is Playwright's `chrome-headless-shell` when
installed, else `chromium` on the PATH, or `FLEET_CHROMIUM`.

## The probe

`bb fleet-demo --probe` reruns the whole demo and exits 0 only when every
step answered as expected. It reuses `example/probe.clj`, which now also
takes `:contains` (text a result must hold) and `[:str ..]` (a string
built from earlier results, e.g. the new order id). With the fleet already
up, the probe alone:

```sh
bb example/probe.clj example/fleet/probe.edn \
  bb serve --dir . --build app --target-id todo --dir example/orders --clj \
  --port 7872 --target-id inventory --actor fleet-agent --scopes read,write,billing
```

The services keep their state, so rerun the probe against a fresh fleet
(the reservation in step 3 grows on each run; step 2 expects the seed).
