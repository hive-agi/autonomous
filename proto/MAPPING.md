# `autonomous.v1` field mapping

One row per proto field: the existing Clojure key it mirrors, the namespace/fn
that owns it, and its status.

**Status legend**

- **mirrored** — the proto field carries the same value the Clojure code already
  produces; the only change is the transport encoding (EDN/JSON -> protobuf).
- **renamed** — same value, a different field name; the reason is given.
- **new** — no existing field; introduced by this draft.
- **omitted** — an existing field deliberately left out of the contract.

The existing wire is JSON/EDN, so every field sets `json_name` to the exact key
the codec must emit: kebab-case stays kebab (`run-id`), namespaced keys keep
their slash (`entry/target`), and the `?` predicate keys keep the question mark
(`args?`). Proto3's own JSON would lower-case those; `json_name` overrides it.

The source of truth is the code, not this table: `src/autonomous/domain/*.cljc`,
`bridge/src/autonomous/bridge/ledger.clj`,
`bridge/src/autonomous/re_frame/model.cljc`.

---

## `answer.proto`

### `Answer` (oneof `answer`)
| proto field | json_name | existing Clojure key | owner | status |
|---|---|---|---|---|
| `ok` | `ok` | `:ok` | `autonomous.domain.answer/ok`, `schema/Ok` | mirrored |
| `pending` | `pending` | `:pending` | `autonomous.domain.answer/pending`, `schema/Pending` | mirrored |
| `refusal` | `error` | `:error` | `autonomous.domain.answer/refusal`, `schema/Refusal` | **renamed**: the internal member is `:error` (`{:error {:type ..}}`), so `json_name = "error"` keeps the wire identical; the proto name says what it is. |

- **oneof `answer` -> the three-way sum.** `autonomous.domain.schema/Answer` is
  `[:or Ok Pending Refusal]` and `autonomous.domain.answer/outcome` folds it to
  `:ok` / `:pending` / a refusal type. A closed sum, so it becomes a `defadt`.
- **`ok` value encoding.** `:ok` is `:any`. Carried as
  `google.protobuf.Value`. See "Open design questions" below.

### `Refusal`
| proto field | json_name | existing Clojure key | owner | status |
|---|---|---|---|---|
| `type` | `type` | `:type` | `schema/Refusal` (`[:map [:error [:map [:type ErrorType] ...]]]`) | mirrored |
| `message` | `message` | `:message` | `schema/Refusal` | mirrored |
| `data` | `data` | `:data` | `schema/Refusal` | mirrored |

- **`type` is a string, not an enum.** `schema/ErrorType` is `:keyword` and the
  set is open: `:invalid-args`, `:unknown-tool`, `:tool-threw`, `:forbidden`,
  `:leased`, `:ledger/unavailable`, and any type an app invents
  (`autonomous.domain.answer/from-exception` reads `:autonomous/error`). Open
  sets stay open. See "Open design questions".
- **`data` is `:any` -> `google.protobuf.Value`.**

---

## `ledger.proto`

### `LedgerEntry`
Mirrors `autonomous.bridge.ledger/->ledger-entry` (durable record) and
`/->view` (reader projection). The two agree on these promoted fields; `->view`
renests target/runtime under `:source` and the rest under `:data`.

| proto field | json_name | existing Clojure key | owner | status |
|---|---|---|---|---|
| `target` | `entry/target` | `:entry/target` | `ledger/->ledger-entry` | mirrored (see note) |
| `runtime` | `entry/runtime` | `:entry/runtime` | `ledger/->ledger-entry` | mirrored (see note) |
| `at` | `entry/at` | `:entry/at` | `ledger/->ledger-entry`, `journal/append` | mirrored |
| `seq` | `entry/seq` | `:entry/seq` | `ledger/->ledger-entry`, `journal/append` | mirrored |
| `actor` | `entry/actor` | `:entry/actor` | `ledger/->ledger-entry` | mirrored |
| `call` | `call` | `:entry/kind = :autonomous/call` | `journal/call-entry` | **renamed**: the oneof tag replaces the `:entry/kind` discriminator. |
| `settled` | `settled` | `:entry/kind = :autonomous/settled` | `journal/settled-entry` | **renamed**: as above. |
| `app_event` | `app-event` | `:entry/kind = :re-frame/event` | `re_frame.model/event-entry` | **renamed**: as above. |

Notes:
- **`entry/target` + `entry/runtime` -> top-level.** `->ledger-entry` emits
  `:entry/target` / `:entry/runtime`; `->view` nests them under `:source`. The
  draft takes the durable record's top-level spelling, since `LedgerEntry` is
  the durable record. The `json_name` is the durable key.
- **`:entry/key` omitted.** `ledger/entry-key` builds
  `"<target>|<runtime>|<at>|<seq>"`. It is derived, so it is not carried: a
  codec recomputes it from `target`, `runtime`, `at`, `seq`.
- **`:entry/data` omitted.** The durable record keeps the entry's own fields as
  an **EDN string** (`wire/print-edn`). The oneof `body` replaces that string
  with structured fields. See "Open design questions".
- **`:entry/kind` -> the oneof tag.** `->view` still exposes `:kind`
  (`:autonomous/call`, `:re-frame/event`); a codec derives it from the oneof
  case.
- **`:entry/tool` and `:entry/outcome`** are promoted in the durable record but
  in the draft they live on the `Call` body (`tool`, `outcome`), since only a
  call has them.

### `Call`
Mirrors `autonomous.domain.journal/call-entry`.

| proto field | json_name | existing Clojure key | owner | status |
|---|---|---|---|---|
| `tool` | `tool` | `:tool` | `journal/call-entry` | mirrored |
| `outcome` | `outcome` | `:outcome` | `journal/call-entry`, `answer/outcome` | mirrored (see note) |
| `ms` | `ms` | `:ms` | `journal/call-entry` | mirrored |
| `pending` | `pending` | `:pending` | `journal/call-entry` | mirrored |
| `session` | `session` | `:session` | `journal/call-entry` | mirrored |
| `args` | `args` | `:args` | `journal/call-entry` | mirrored |
| `answer` | `answer` | `:answer` | `journal/call-entry`, `journal/with-answer` (S6) | mirrored |
| `answer_truncated` | `answer/truncated?` | `:answer/truncated?` | `journal/with-answer` (S6) | mirrored |
| `error` | `error` | `:error` | `journal/call-entry` | mirrored |
| `approval` | `approval` | `:approval` | `journal/call-entry`, `with-approval` | mirrored |
| `caller` | `caller` | `:caller` | S7 (card `20261127155655-4ebda860`) | **new** (in flight; shape pinned later) |

Notes:
- **`outcome`**: `answer/outcome` returns the keyword `:ok`, `:pending`, or the
  refusal type; it becomes a string. See "Open design questions".
- **`answer/truncated?`**: the S6 predicate key; `json_name` keeps the `?`.
- **`caller`** is not yet on staging; it is S6/S7 work (base branch
  `feat/journal-answers`). Marked **new** so the drift test expects it only
  after S7 lands.

### `Settled`
Mirrors `autonomous.domain.journal/settled-entry`.

| proto field | json_name | existing Clojure key | owner | status |
|---|---|---|---|---|
| `pending` | `pending` | `:pending` | `journal/settled-entry` | mirrored |
| `outcome` | `outcome` | `:outcome` | `journal/settled-entry` | mirrored |
| `answer` | `answer` | `:answer` | `journal/settled-entry` (S6) | mirrored |
| `error` | `error` | `:error` | `journal/settled-entry` | mirrored |
| `approval` | `approval` | `:approval` | `journal/settled-entry` | mirrored |

- `settled-entry` has no `:answer/truncated?` in the S6 code (only `call-entry`
  sets it, via the shared `with-answer`); the draft keeps the field off
  `Settled` to match.

### `AppEvent`
Mirrors `autonomous.re-frame.model/event-entry`.

| proto field | json_name | existing Clojure key | owner | status |
|---|---|---|---|---|
| `event_id` | `event-id` | `:event-id` | `re_frame.model/event-entry` | mirrored |
| `origin` | `origin` | `:origin` | `re_frame.model/event-entry` | mirrored (enum) |
| `payload` | `payload` | `:payload` | `re_frame.model/event-entry` | mirrored |

- **`origin` enum.** `event-entry` sets `:origin` to `:agent` or `:app`
  (`(if actor :agent :app)`), a closed two-value set, so an enum:
  `ORIGIN_AGENT`, `ORIGIN_APP`, with the `ORIGIN_UNSPECIFIED = 0` sentinel.
  A string `:origin` (`"agent"`) is an alternative; see "Open design
  questions".
- **`payload`** is `wire/->wire` of the rest of the event vector, kept only
  when `payloads?`; `:any` -> `google.protobuf.Value`. Absent when not kept.
- **`:actor` on an app event** is not a field of `AppEvent` in this draft: the
  `:actor` it carries is promoted to the `LedgerEntry.actor` envelope field
  (`->ledger-entry` promotes `:actor`). See "Open design questions".

### `Origin` (enum)
| proto value | existing Clojure value | owner | status |
|---|---|---|---|
| `ORIGIN_UNSPECIFIED` | — | — | **new**: the required `= 0` sentinel. |
| `ORIGIN_AGENT` | `:agent` | `re_frame.model/event-entry` | mirrored |
| `ORIGIN_APP` | `:app` | `re_frame.model/event-entry` | mirrored |

### `Approval`
Mirrors `autonomous.domain.journal/with-approval` and `/unrecorded-decision`
(`{:decision :approver :ms}`).

| proto field | json_name | existing Clojure key | owner | status |
|---|---|---|---|---|
| `decision` | `decision` | `:decision` | `journal/unrecorded-decision` | mirrored |
| `approver` | `approver` | `:approver` | `journal/unrecorded-decision` | mirrored |
| `ms` | `ms` | `:ms` | `journal/unrecorded-decision` | mirrored |

- `:decision` is a keyword when present -> string.
- `:approver` is always the `redacted` marker (`"<redacted>"`) in the journaled
  form.

---

## `call_detail.proto`

### `CallDetail`
Mirrors `autonomous.domain.schema/CallDetail` (a `{:closed true}` map).

| proto field | json_name | existing Clojure key | owner | status |
|---|---|---|---|---|
| `args` | `args?` | `:args?` | `schema/CallDetail` | mirrored (predicate key; `json_name` keeps `?`) |
| `answers` | `answers?` | `:answers?` | `schema/CallDetail` (S6) | mirrored |
| `errors` | `errors?` | `:errors?` | `schema/CallDetail` | mirrored |
| `redact` | `redact` | `:redact` | `schema/CallDetail` | mirrored (see note) |

- **`redact`**: `[:sequential [:vector {:min 1} :any]]` — a list of paths, each
  a vector of keys or indices. Carried as `repeated google.protobuf.ListValue`.
- **Field names drop the `?`** (`args`, not `args?`): a proto field name is
  `[a-z][a-z0-9_]*`, so `?` is illegal there. `json_name = "args?"` keeps the
  wire key exact. This is the `?` predicate-key seam the hive-domain recipe
  calls out (`:confident?` -> `confident`).

---

## `caller.proto`

### Caller reconcile (S7 landed)

S7 landed on branch `feat/caller-coordinate` @ `633ece9` (card
`20261127155655-4ebda860`). The EDN shape there is **FLAT**:
`autonomous.domain.schema/Caller` is

```clojure
[:map
 [:chat-point {:optional true} ChatPoint]
 [:run/task-id {:optional true} :string]
 [:run/attempt {:optional true} [:int {:min 0}]]
 [:trace/traceparent {:optional true} Traceparent]
 [:trace/tracestate {:optional true} :string]]
```

and `autonomous.domain.caller/from-meta` reads exactly those keys. The MCP
`_meta` wire keys (`autonomous.domain.caller/wire-keys`, MCP 2026-07-28,
SEP-414) are:

| `_meta` wire key | EDN key | value |
|---|---|---|
| `traceparent` | `:trace/traceparent` | W3C traceparent (spec's own unprefixed key) |
| `tracestate` | `:trace/tracestate` | W3C tracestate (unprefixed) |
| `io.github.hive-agi/chat-point` | `:chat-point` | `{:run-id :seq :turn}` |
| `io.github.hive-agi/run-task-id` | `:run/task-id` | the task |
| `io.github.hive-agi/run-attempt` | `:run/attempt` | the try |

**Decision: the proto mirrors the FLAT EDN shape.** The earlier draft nested a
`Correlation` message; that is dropped and its two fields are inlined. Reasons:

1. The EDN shape the drift test compares against is flat. A nested proto would
   force the ACL to invent a `:correlation` key that no Clojure code produces.
2. `Caller` is a **product**, not a sum: nothing about it selects between
   alternatives, so nesting buys no exhaustiveness (the "Cardinality Decides
   the Construct" rule generates an ADT only for a real oneof or enum). A
   product stays a product.
3. The host-neutral run-control interface (the hive-spi side) is a *type* the
   autonomous domain deliberately does not require at the wire: the schema
   copies the two key names, not the type. Mirroring the EDN keeps that seam:
   the names are what matter, and they are carried whole.

### `Caller`
| proto field | json_name (EDN key) | `_meta` wire key | owner | status |
|---|---|---|---|---|
| `chat_point` | `chat-point` | `io.github.hive-agi/chat-point` | S7 | **new** |
| `run_task_id` | `run/task-id` | `io.github.hive-agi/run-task-id` | S7 | **new** |
| `run_attempt` | `run/attempt` | `io.github.hive-agi/run-attempt` | S7 | **new** |
| `traceparent` | `trace/traceparent` | `traceparent` | S7 (W3C, SEP-414) | **new** |
| `tracestate` | `trace/tracestate` | `tracestate` | S7 (W3C, SEP-414) | **new** |

- **json_name is the EDN key**, not the `_meta` key, for the hive coordinates:
  a proto-to-EDN codec emits `run/task-id` (namespaced), while the MCP JSON
  codec (`autonomous.mcp.json`) emits `io.github.hive-agi/run-task-id` from the
  same value. Both spellings are pinned in the table so neither is guessed. The
  trace keys are identical in both wires (`traceparent` / `tracestate`), so
  their json_name is `trace/traceparent` / `trace/tracestate` for the EDN side
  and the plain spec key for the `_meta` side.
- `traceparent` here carries the EDN key `:trace/traceparent`; the `_meta` side
  uses the W3C spec's unprefixed `traceparent` (SEP-414). A `Caller` value is
  validated by `autonomous.domain.trace/parse-traceparent` on admission.

### `ChatPoint`
Mirrors `autonomous.domain.schema/ChatPoint` (S7) and the chat-point type
`[:map [:run-id :string] [:seq [:int {:min 0}]] [:turn [:int {:min 0}]]]`.

| proto field | json_name | existing Clojure key | owner | status |
|---|---|---|---|---|
| `run_id` | `run-id` | `:run-id` | `hive-agent.loop.chat-point` | mirrored |
| `seq` | `seq` | `:seq` | `hive-agent.loop.chat-point` | mirrored |
| `turn` | `turn` | `:turn` | `hive-agent.loop.chat-point` | mirrored |

- `run_id` keeps the kebab spelling from ChatPoint (`:run-id`), **not** the
  snake `run_id` the ledger's `->view` would suggest. The join key uses the
  existing chat-point names (Pedro's correction on the card), so the ChatPoint
  spelling wins.
- **`Correlation` message is REMOVED** from this draft; its two fields are
  inlined as `Caller.run_task_id` and `Caller.run_attempt`. Field numbers 2 and
  3 are reserved below so the removed nested message can never be reintroduced
  at a colliding number.

---

## Open design questions

1. **Value encoding of open maps (`ok`, `data`, `args`, `answer`, `payload`).**
   `google.protobuf.Value` is chosen (portable, matches today's JSON). The
   alternative is **bytes of EDN**, which round-trips keyword/symbol/uuid/set
   scalars that `Value` cannot (the current JSON wire already stringifies
   them, so `Value` matches today). If the transform prefers EDN fidelity over
   cross-language portability, these five fields switch to `bytes`.
2. **`Refusal.type`: string vs enum.** String, because the set is open
   (`ErrorType` is `:keyword`, apps invent types). An enum would freeze it.
   Confirm with the transform hivemind that open keyword sets stay strings.
3. **`LedgerEntry` body: oneof vs the current `:entry/data` EDN string.** The
   draft structures it; a codec must still be able to read the old EDN string
   (v1 readers) if the durable record is not migrated at once.
4. **`AppEvent.origin`: enum vs string.** Enum here (`:agent`/`:app` closed);
   a string is possible if the transform prefers all keyword values as strings.
5. **`Call.answer` truncation on `Settled`.** S6 sets `:answer/truncated?` on a
   call entry only; the draft mirrors that, but a symmetric `Settled` field may
   be wanted once S6/S7 settle.
6. **`Caller` field spelling.** Pinned only after the S7 plan pass fixes the
   MCP `_meta` keys; `run-id` here follows ChatPoint, `run/task-id` follows
   hive-spi. A single namespace may be chosen for both.
