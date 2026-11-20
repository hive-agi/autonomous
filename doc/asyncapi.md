# Published events as agent-readable resources

`autonomous.asyncapi` turns an AsyncAPI document into **read-only** resources
that show what was published on a subject. The publisher changes nothing; an
agent reads a bounded tap instead of subscribing to the bus itself.

```clojure
(require '[autonomous.asyncapi :as asyncapi]
         '[autonomous.asyncapi.memory :as memory]
         '[autonomous.asyncapi.port :as port]
         '[autonomous.openapi.load :as load])

(def doc (:ok (load/load-bundle "libs/asyncapi/nats-events.yaml")))

(def port (:ok (port/connect :asyncapi.memory/bus {:bus (memory/bus)})))

(def s (:ok (asyncapi/surface doc
              {:port   port
               :expose {"user.events.created" {:scope :events/read}
                        "sisf.payment.*.entry"  {:scope :payments/read
                                                 :capacity 20}}})))

;; agents read asyncapi://sisf.payment.*.entry through the runtime:
;; {:messages [{:seq 41 :subject "sisf.payment.pix.entry"
;;              :payload {...} :conforms? true}]
;;  :cursor 41 :dropped 0}

(asyncapi/stop! (:live s))          ; unsubscribe; the port closes too
```

## What a read answers

`{:messages [...] :cursor n :dropped k}` — the messages published after **that
reader's** last cursor, oldest first, and how many matched messages fell out of
the ring before it got there. The cursor is the server's memory: the reader
passes nothing back and cannot skip or replay by accident.

- A reader is its `:actor`, qualified by its `:session`. Two sessions of one
  agent have separate cursors and never consume each other's messages.
- `:dropped` is per reader and counted once: it is how many messages that
  reader lost, not a bus-wide total, and it is not charged twice.
- The ring is `:capacity` messages (default 50), per subject. A busy subject
  with a slow reader reports a gap instead of quietly hiding one.

## Opt-in, and strict about it

Only the subjects in `:expose` become resources. Refused when the surface is
**built**, never at read time:

| Case | Type |
|------|------|
| `:expose` empty | `:asyncapi/nothing-exposed` |
| `:expose` missing or not a map | `:asyncapi/bad-expose` |
| a filter NATS cannot use (`a..b`, `a.b.`, a `>` that is not last) | `:asyncapi/bad-subject` |
| a **literal** subject the document does not declare | `:asyncapi/unknown-subject` |
| `:port` missing or not a port | `:asyncapi/bad-bus` |

A **wildcard** the document has not caught up on is allowed on purpose:
`sisf.payment.*.entry` is worth watching before the spec names it.

`*` matches exactly one token and `>` one or more, as NATS has it, so `a.>`
matches `a.b` and `a.b.c` but not `a`.

## Payloads are validated, never dropped

The message schema of the channel validates each payload. A non-conforming
payload is **kept** with `:conforms? false` and humanized `:problems`: the
drift is exactly what an agent is watching for, so dropping it would hide the
signal. A subject with no declared payload (every wildcard over unknown
subjects) validates nothing.

## The broker is a port

```clojure
{:subscribe   (fn [filter handler] sub-id)   ; handler gets {:subject :payload :at}
 :unsubscribe (fn [sub-id])
 :close       (fn [])}
```

`port/connect` is an open multimethod on a namespaced kind: a broker adapter
is one `defmethod`, and nothing in the pure strata changes.

- `:asyncapi.memory/bus` — in-memory, synchronous, no server. What the tests
  and a demo use. Pass `:at (fn [] 7)` for deterministic timestamps.
- `:asyncapi.nats/driver` — a real broker, `requiring-resolve`d lazily; no
  NATS client is a dependency of anything here. It needs the four vars named in
  `autonomous.asyncapi.nats/client-vars` on the classpath, and answers
  `:asyncapi/no-nats-client` naming what is missing.

## Strata

| Step | Namespace | Pure? |
|------|-----------|-------|
| read the YAML/JSON and the files its `$ref`s reach | `autonomous.openapi.load` | IO |
| resolve `$ref`s, inline message schemas | `autonomous.openapi.doc`, `autonomous.asyncapi.doc` | yes |
| JSON Schema to malli | `autonomous.openapi.schema` | yes |
| match a subject against a filter | `autonomous.asyncapi.subject` | yes |
| the bounded ring, cursor and dropped count | `autonomous.asyncapi.tap` | yes |
| the reader's cursor and one read | `autonomous.asyncapi.read` | yes |
| derive the exposed taps | `autonomous.asyncapi.derive` | yes |
| subscribe and tear down | `autonomous.asyncapi.source` | IO |
| the port, and the memory / NATS adapters | `autonomous.asyncapi.port`, `.memory`, `.nats` | IO at the edge |
| `derive-surface :asyncapi/doc` | `autonomous.asyncapi.spi` | yes |

## Alpha: what to tap first

The fixture names no `sisf.payment.*` channel — those subjects live in the
sisf-gateway and sisf-ledger configmaps — which is the wildcard case above.
For payment consistency, in this order:

1. `sisf.payment.*.entry` — the `financial.v1.LedgerEntry` the ledger posts,
   `:capacity 20`. Start here.
2. `sisf.payment.*.settled` — which invoice was paid, same ring.
3. `sisf.payment.>` — the whole `SISF_EVENTS` tree, including sync traffic.
   Last, because a wide tap costs context and buries the signal.
