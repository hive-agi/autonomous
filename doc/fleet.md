# Fleet mode: one bridge, many apps

A fleet is one autonomous bridge serving several apps at once: UI builds
(a shadow-cljs nREPL) and microservices (a JVM or babashka nREPL). Each
app hosts its own autonomous registry and registers its own tools; the
bridge reaches each through its nREPL and shows the agent one MCP surface.
A babashka bridge costs a few tens of megabytes, so one per developer (or
per swarm) covers every service of a system.

## Start a fleet

Repeat `--dir`, `--port` or `--port-file`; each one starts the next app.
Options after it (`--build`, `--clj`, `--host`, `--target-id`) belong to
that app.

```sh
# the todo UI (shadow-cljs) and two services (Clojure / babashka nREPLs)
bb serve --dir ../todo \
         --dir ../orders --clj \
         --port 7002 --target-id stock
```

The same with the JVM: `clojure -M:serve ...`. ClojureWasm runs the same
namespaces over stdio.

Or list the apps in `autonomous.edn` (or the file named by `--config`):

```clojure
{:targets [{:dir "../todo"}                         ; derived like --dir
           {:dir "../orders" :clj? true}
           {:port "7002" :target-id "stock"}
           {:target/kind :nrepl :target/id "billing"  ; or a full Target
            :nrepl/port 7010 :nrepl/mode :clj}]
 :probe-ms 1500}
```

An app's id is its directory name unless `--target-id` names it; an app
reached by port alone is `app-<port>`. Ids are 1 to 48 of `[A-Za-z0-9._-]`,
unique, and must not share a tool prefix (`a_b` and `a.b` do: both are
`a-b`). A bad fleet refuses to start and says which ids collide.

One app with `--fleet` serves in fleet mode too (prefixed names), so an
agent's names do not change when a second app joins later. Without
`--fleet`, a single app is served exactly as before: same tool names, same
built-ins, no `target` argument.

## Names

| what | single app | fleet |
|---|---|---|
| app tool `todo_add` of app `todo` | `todo_add` | `todo__todo_add` |
| tool of app `orders_api` | `refund` | `orders-api__refund` |
| resource `app://db` of `todo` | `app://db` | `autonomous://todo/app://db` |
| pending result `p7` of `orders` | `p7` | `orders:p7` |

The prefix is the app id with `_` and `.` turned into `-`, at most 24
characters, so the first `__` of a name always ends the prefix. Names stay
valid MCP names (at most 64 of `[A-Za-z0-9_-]`): a name that is not one is
made safe, cut to fit, and ends in `-` and a 12 character digest of the
original name, and the bridge still finds the tool (see
[naming](naming.md)). Each fleet tool's title and description start with `[app]`.

## Built-in tools

| tool | in a fleet |
|---|---|
| `autonomous_apps` | every app: id, prefix, status (`up`, `down`, `slow`), tool count, manifest version, error |
| `autonomous_runtimes`, `autonomous_attach`, `autonomous_journal`, `autonomous_lease`, `autonomous_release` | take `target` (the app id); required when the fleet has several apps |
| `autonomous_attach` | pins the session per app: attaching on `orders` leaves `todo` alone |
| `autonomous_ledger` | one ledger for the whole fleet; pass `target` to read one app; every row says its app under `source.target` |
| `autonomous_poll` | takes the qualified id a call answered, e.g. `orders:p7` |
| `autonomous_whoami` | actor, scopes and the runtime pinned on each app |

## Isolation

The bridge asks every app for its manifest at once and waits at most
`--probe-ms` (default 1500) for all of them together:

- an app that is down (no port file, connection refused, not autonomous)
  answers at once; its tools are not listed, and `autonomous_apps` shows it
  `down` with the reason;
- an app that does not answer in time is `slow`; its look keeps running in
  the background and the next one reuses it, so a hung app never piles up
  work or delays the others;
- a call to a down app's tool is refused with the channel's reason; every
  other app keeps working.

Over stdio the bridge watches the fleet fingerprint (each app's status,
manifest version and tool names) and sends
`notifications/tools/list_changed` when an app goes away, comes back or
changes its tools.

## A service that hosts its own tools

Any process that runs `autonomous.core` and an nREPL is a fleet member. For
a babashka service:

```clojure
;; orders/src/orders/agentic.clj
(ns orders.agentic (:require [autonomous.core :as a]))

(defn install! []
  (a/set-app-info! {:name "orders" :description "Order service"})
  (a/reg-tool :orders/place
    {:description "Place an order. Answers its id."
     :input [:map [:sku :string] [:qty [:int {:min 1}]]]
     :handler (fn [{:keys [sku qty]} grant] (place! sku qty (:actor grant)))}))
```

Start it with `bb nrepl-server 7001` (autonomous on its classpath), call
`(orders.agentic/install!)`, and give the bridge `--port 7001 --target-id
orders`. A JVM service writes `.nrepl-port`, so `--dir ../orders --clj`
finds it.

## Embedding

```clojure
(require '[autonomous.bridge.client :as client]
         '[autonomous.bridge.channel :as channel]
         '[autonomous.bridge.fleet :as fleet]
         '[autonomous.bridge.ledger :as ledger]
         '[autonomous.mcp.server :as server])

(let [l   (ledger/open {:ledger/kind :memory})
      ctx (fn [t] (client/context {:target t :channel (channel/make t) :ledger l}))
      ts  [{:target/kind :nrepl :target/id "orders" :nrepl/port 7001 :nrepl/mode :clj}
           {:target/kind :nrepl :target/id "stock"  :nrepl/port 7002 :nrepl/mode :clj}]]
  (server/fleet-server (fleet/make (mapv (juxt :target/id ctx) ts) {:probe-ms 1500})))
```

`autonomous.cli/build-server` does the same from a validated config.

## Design, in one table

| stratum | namespace | role |
|---|---|---|
| domain (pure, cljc) | `autonomous.domain.fleet` | prefixes, exposed names and uris, routing, probe merge, fingerprint; naming modes as open multimethods (`:single`, `:fleet`) |
| bridge schema | `autonomous.bridge.schema` | `TargetId`, `Probe`, `ProbeStatus`, `AppRow`, `FleetMode`, `Config` with `:targets` |
| promote | `autonomous.config` | target groups from the command line, `:targets` from the file, fleet checks |
| pipeline | `autonomous.bridge.fleet` | a client context per app over one ledger, probes with a shared deadline, routed call, read, poll and ledger |
| MCP | `autonomous.mcp.server`, `autonomous.mcp.builtins` | one server for one app or a fleet; built-ins per mode (`for-mode`) |

The effects stay in the channel adapters and in the probe futures; every
decision about a name, a route or a surface is a pure function.
