# inventory: a babashka microservice hosting its own autonomous bindings

A warehouse stock service (receive, reserve, release, write off, cycle
count) on babashka. It starts in well under a second, registers its tools
and a resource in its own process, and serves them over `bb nrepl-server`,
which the autonomous bridge reaches through `.nrepl-port`. No JVM.

| file | role |
|---|---|
| `src/inventory/domain.cljc` | the bounded context, pure: available = on hand minus reserved, never promise more than you hold |
| `src/inventory/service.clj` | the stock table (an atom) and the use cases |
| `src/inventory/agentic.clj` | **the whole agent surface, in one namespace** |
| `src/inventory/system.clj` | composition root: seed and register |
| `boot.clj` | loaded by `bb --init` before the nREPL starts |
| `bb.edn` | `bb nrepl` and `bb test` |

What it registers:

| tool / resource | scopes | what it shows |
|---|---|---|
| `inventory_items`, `inventory_item` | `read` | reads, with `available` and `low?` derived |
| `inventory_receive`, `inventory_reserve`, `inventory_release`, `inventory_add_item` | `write` | the domain refuses a reservation beyond what is available, saying how much is |
| `inventory_write_off` | **`audit`** | an app-defined scope; reserved units cannot be written off |
| `inventory_count` | `read` | an **asynchronous** tool: the handler returns a `future` (1.5 s); "still running" then `autonomous_poll` |
| `inventory://low-stock` | `read` | a resource: the skus at or below their reorder point |

## Run it

```sh
cd example/inventory
bb nrepl                     # = bb --init boot.clj nrepl-server 127.0.0.1:0
```

`bb nrepl-server` writes `.nrepl-port` in the directory it starts in. From
the repository root, a bridge over stdio:

```sh
bb serve --clj --dir example/inventory --scopes read,write,audit
```

Register it with Claude Code:

```sh
claude mcp add inventory -- bb --config "$PWD/bb.edn" serve --clj \
  --dir "$PWD/example/inventory" --actor stock-agent --scopes read,write,audit
```

## Verify it end to end

With the service running, from the repository root:

```sh
bb example/probe.clj example/inventory/probe.edn \
  bb serve --clj --dir example/inventory --scopes read,write,audit --timeout-ms 300
bb example/probe.clj example/inventory/probe-no-audit.edn \
  bb serve --clj --dir example/inventory --actor clerk --scopes read,write
```

Abridged output:

```
 1 ok   resources/read inventory://low-stock
     <- ["mouse-v"]
 4 ok   tools/call inventory_reserve
     <- REFUSED {"type":"stock/insufficient","message":"only 6 units of mouse-v are available",...}
 6 ok   resources/read inventory://low-stock
     <- []
 9 ok   tools/call inventory_count
     <- still running; collect the result with autonomous_poll id=p1
10 ok   tools/call autonomous_poll
     <- {"counted-by":"stock-agent","skus":4,"on-hand":274,"reserved":10}
probe: every step answered as expected
...
 2 ok   tools/call inventory_write_off
     <- REFUSED {"type":"forbidden","message":"inventory_write_off requires scopes [\"audit\"]",...}
```

## Both services behind one bridge

With the orders service (`clojure -M:example/orders`) and this one running,
one bridge serves both as a fleet; tools become `<app>__<tool>`:

```sh
bb example/probe.clj example/fleet-probe.edn \
  bb serve --clj --dir example/orders --dir example/inventory --clj --scopes read,write,billing
```

## Tests

```sh
cd example/inventory && bb test
```

`inventory.domain-trifecta-test` locks `reserve` and `write-off` (golden,
property and mutation facets); `inventory.agentic-test` drives the surface
through a real MCP server over the in-process channel.
