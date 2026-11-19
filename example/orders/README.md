# orders: a JVM service hosting its own autonomous bindings

A small order service (place orders, read them, refund money, build a
customer statement) that makes itself agent-operable. It runs on the JVM,
registers its tools and a resource in its own process, and starts an nREPL
on 127.0.0.1 that the autonomous bridge reaches it through.

| namespace | role |
|---|---|
| `orders.domain` | the bounded context, pure: malli value objects, `place-order`, `refund`, `invoice` |
| `orders.store` | the repository port and an in-memory adapter |
| `orders.service` | the use cases: collect, let the domain decide, store |
| `orders.agentic` | **the whole agent surface, in one namespace** |
| `orders.main` | composition root: wire, seed, register, start the nREPL |

The agent surface (`orders.agentic`) is the only place that knows about
autonomous. The service knows nothing about agents, so making an existing
service agent-operable is this one namespace plus one call at startup.

What it registers:

| tool / resource | scopes | what it shows |
|---|---|---|
| `orders_list`, `orders_get` | `read` | plain reads |
| `orders_place` | `write` | input validated by the domain's malli schema |
| `orders_refund` | **`billing`** | an app-defined scope: an agent granted `read,write` does not even see it. The domain's refusal (`refund/exceeds-paid`) reaches the agent with how much is still refundable. The actor is recorded on the refund. |
| `orders_statement` | `read` | an **asynchronous** tool: the handler returns a `future` (1.5 s). A bridge that waits less answers "still running" with an id; `autonomous_poll` collects it. |
| `orders://summary` | `read` | a resource: order count by status, money paid and refunded |

## Run it

From the repository root:

```sh
clojure -M:example/orders            # writes example/orders/.nrepl-port
```

Then, in another terminal, a bridge over stdio (any host; babashka starts
fastest). Grant the billing scope to let the agent refund:

```sh
bb serve --clj --dir example/orders --scopes read,write,billing
# or on the JVM:  clojure -M:serve --clj --dir example/orders --scopes read,write,billing
```

Register it with Claude Code:

```sh
claude mcp add orders -- bb --config "$PWD/bb.edn" serve --clj \
  --dir "$PWD/example/orders" --actor support-agent --scopes read,write,billing
```

`--clj` matters: without a `shadow-cljs.edn` the bridge would pick it
anyway, but it says what the target is (a Clojure process over
`.nrepl-port`, not a shadow build).

## Verify it end to end

`example/probe.clj` starts a bridge, speaks MCP to it over stdio and checks
every answer. With the service running:

```sh
bb example/probe.clj example/orders/probe.edn \
  bb serve --clj --dir example/orders --scopes read,write,billing --timeout-ms 300
bb example/probe.clj example/orders/probe-no-billing.edn \
  bb serve --clj --dir example/orders --actor clerk --scopes read,write
```

`--timeout-ms 300` makes the bridge answer the statement as "still running"
instead of waiting for it, so the probe exercises `autonomous_poll`.
Abridged output:

```
 4 ok   tools/call orders_refund
     <- {"id":"3b3f...","status":"partially-refunded",...,"refunds":[{...,"by":"ada-support"}]}
 5 ok   tools/call orders_refund
     <- REFUSED {"type":"refund/exceeds-paid","message":"only 10300 cents are still refundable on this order",...}
 7 ok   tools/call orders_statement
     <- still running; collect the result with autonomous_poll id=p2
 8 ok   tools/call autonomous_poll
 9 ok   resources/read orders://summary
     <- {"orders":3,"by-status":{"placed":2,"partially-refunded":1},"paid-cents":18800,"refunded-cents":1000}
probe: every step answered as expected
...
 2 ok   tools/call orders_refund
     <- REFUSED {"type":"forbidden","message":"orders_refund requires scopes [\"billing\"]",...}
```

## Tests

```sh
clojure -M:test:example/orders-dev orders
```

`orders.domain-trifecta-test` locks the domain (golden, property and
mutation facets); `orders.agentic-test` drives the surface through a real
MCP server over the in-process channel: the billing scope, a domain refusal,
Pending then poll, and the resource.
