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

## FREEZE: field numbers, presence, default and unknown policy (v1)

**Frozen 2026-11-27.** Field numbers below are final for `autonomous.v1`. No
renumbering; a deleted name or number is `reserved` in the `.proto` so it can
never be reused. The wire is pinned by the descriptor in
`proto/fixtures/v1/`, and a change to any number, name, presence or arm is a
`buf breaking` failure (rollout step 6) and demands a new version.

### Field numbers (final)

| message | field | number | label |
|---|---|---|---|
| `Answer` | `ok` / `pending` / `refusal` | 1 / 2 / 3 | oneof `answer` |
| `Refusal` | `type` / `message` / `data` | 1 / 2 / 3 | |
| `LedgerEntry` | `target` / `runtime` / `at` / `seq` / `actor` | 1 / 2 / 3 / 4 / 5 | |
| `LedgerEntry` | `call` / `settled` / `app_event` | 6 / 7 / 8 | oneof `body` |
| `LedgerEntry` | (reserved) | 9 to 19 | names `entry_key`, `entry_data` |
| `Call` | `tool` / `outcome` / `ms` / `pending` / `session` | 1 / 2 / 3 / 4 / 5 | |
| `Call` | `args` / `answer` / `answer_truncated` / `error` / `approval` / `caller` | 6 / 7 / 8 / 9 / 10 / 11 | |
| `Settled` | `pending` / `outcome` / `answer` / `error` / `approval` | 1 / 2 / 3 / 4 / 5 | |
| `AppEvent` | `event_id` / `origin` / `payload` | 1 / 2 / 3 | |
| `Origin` | `ORIGIN_UNSPECIFIED` / `ORIGIN_AGENT` / `ORIGIN_APP` | 0 / 1 / 2 | enum |
| `Approval` | `decision` / `approver` / `ms` | 1 / 2 / 3 | |
| `CallDetail` | `args` / `answers` / `errors` / `redact` | 1 / 2 / 3 / 4 | |
| `Caller` | `chat_point` / `run_task_id` / `run_attempt` / `traceparent` / `tracestate` | 1 / 2 / 3 / 4 / 5 | |
| `ChatPoint` | `run_id` / `seq` / `turn` | 1 / 2 / 3 | |

### Reserved

- `LedgerEntry`: numbers **9 to 19** and names **`entry_key`**, **`entry_data`**.
  `entry_key` is derived (`"<target>|<runtime>|<at>|<seq>"`) and `entry_data` is
  the EDN-string body the `body` oneof replaces. Reserving the range keeps a
  future envelope field above the frozen numbers.
- `autonomous.v1` file scope: the message name **`Correlation`** is removed (its
  fields are inlined on `Caller`). Proto has no file-scope `reserved` for a
  message *name*, so the removal is pinned here and by the frozen numbers; a
  future `Caller` field starts at 6.

### Presence policy

1. **Explicit optional and singular messages use key absence, never a
   nil-valued key.** A field is absent from the map when it has no value; it is
   never present with `nil`. `Caller.run_task_id`, `Caller.run_attempt`,
   `Caller.traceparent`, `Caller.tracestate`, `LedgerEntry.actor`,
   `Call.args`, `Call.answer`, `Call.answer_truncated`, `Call.error`,
   `Call.approval`, `Call.caller`, `Settled.answer`, `Settled.error`,
   `Settled.approval`, `AppEvent.payload`, `Approval.decision`,
   `Approval.approver`, `Approval.ms`, `CallDetail.args`, `CallDetail.answers`,
   `CallDetail.errors`, `CallDetail.redact`, `Refusal.data` are all `optional`
   and absent when unset. `Caller.chat_point` is a singular message, so its
   absence is key absence too (an absent chat point is no key, not
   `{:chat_point nil}`).
2. **Implicit proto3 scalars normalize to their wire default.** A scalar with
   no presence bit (`ChatPoint.run_id`, `ChatPoint.seq`, `ChatPoint.turn`,
   `Refusal.type`, `Refusal.message`, `Call.tool`, `Call.outcome`, `Call.ms`,
   `Settled.pending`, `Settled.outcome`, `AppEvent.event_id`,
   `LedgerEntry.target`, `LedgerEntry.runtime`, `LedgerEntry.at`,
   `LedgerEntry.seq`) reads as `""` / `0` when absent on the wire. A codec
   emits the domain's value, which for these is always present; it does not
   invent a sentinel.
3. **Exactly one arm for `Answer` and `LedgerEntry`.** `Answer` carries exactly
   one of `ok` / `pending` / `refusal`; `LedgerEntry` carries exactly one of
   `call` / `settled` / `app_event`. An **unset envelope is refused on
   admission** (`:contract/unset-answer` / `:contract/unset-body`) and so is a
   map that carries **two arms** (`:contract/both-arms`). The bridge already
   enforces the Answer half: `autonomous.bridge.ws/answer-shape?` is
   `(= 1 (count (select-keys a [:ok :pending :error])))`.
4. **An unknown future arm or number gives an explicit unsupported result.**
   There is no silent fallback to `ok` or `0`. A oneof case the reader does not
   know, or a field number it does not know, yields
   `{:error {:type :contract/unsupported ...}}` carrying the raw input for
   relay. The design note's rule: "Unknown future numbers/arms are not new
   domain variants: return an explicit unsupported result with raw input for
   relay if needed."
5. **Open sets stay strings.** `Refusal.type` (the `:invalid-args`,
   `:unknown-tool`, `:tool-threw`, `:forbidden`, `:leased`, `:ledger/unavailable`
   family and any type an app invents), `Call.outcome` / `Settled.outcome`
   (`:ok` / `:pending` / a refusal type), tool names and sources are **strings**,
   never enums. The transform emits a `defadt` only for a **real oneof** and for
   **enums** (fa8f9f, card answer Q2), so these stay open.
6. **`ORIGIN_UNSPECIFIED` is a wire-only sentinel.** `Origin` is a closed enum
   whose `= 0` value is `ORIGIN_UNSPECIFIED`; it is recorded on the wire but
   **refused on domain admission** rather than admitted as a business value
   (`:contract/unspecified-enum`). `AppEvent.origin` in the domain is only ever
   `:agent` or `:app`.

### Unknown-field policy

- **A reader ignores unknown field numbers** (proto3 semantics) but a reader
  that promises to **relay** an unknown arm keeps the **original wire
  envelope** and tests that separately; it does not decode and re-encode.
- **A generator or admission refuses an unsupported shape by name** (the
  qualified location), never with `:any` by accident.
- No renumber, no reuse. Deleting a field means `reserved`, above.

---

## The two wires

`json_name` alone cannot fix an envelope, a discriminator, or an EDN-vs-JSON
mismatch. There are **two** wires, and a codec must be chosen per peer and
release. The proto is the shared vocabulary; the wire is the encoding.

### Wire A: MCP JSON, agent <-> bridge

- **Who:** agents speak MCP JSON to the bridge (`autonomous.mcp.server`,
  `autonomous.mcp.json`, `autonomous.mcp.stdio`, `autonomous.mcp.http`). A
  tools/call request's `params._meta` carries the Caller coordinate.
- **Codec:** JSON, shaped by `autonomous.mcp.json/->json-data`: keywords keep
  their namespace (`:todo/add` -> `"todo/add"`), **sets and seqs become
  vectors, uuids and insts become strings, non-finite numbers become strings**.
  Object keys are strings; `read-json` returns keyword keys.
- **json_name:** every proto field's `json_name` is the exact JSON key this
  wire emits, so a codec can be derived from the descriptor. The `_meta` Caller
  keys are the reverse-DNS spellings (`io.github.hive-agi/chat-point`,
  `io.github.hive-agi/run-task-id`, `io.github.hive-agi/run-attempt`) plus the
  W3C spec's own unprefixed `traceparent` / `tracestate`.
- **Release note:** the JSON wire is **v1 today** and stays v1. A codec derived
  from `json_name` must reproduce today's keys byte-for-byte, or the change is a
  v2 wire with v1 readers kept (design note R3).

### Wire B: runtime <-> bridge websocket, EDN with `:ws/type`

- **Who:** a runtime (`autonomous.ws`, `.cljc`: browser, node, JVM, bb) dials
  the bridge (`autonomous.bridge.ws`, `autonomous.ws.ring`,
  `autonomous.ws.server`). One **EDN map per text frame**.
- **Codec:** **EDN, not JSON.** `autonomous.ws/read-frame` reads text with
  `autonomous.domain.wire/read-edn` and keeps it only when it is a map with a
  keyword `:ws/type`. `autonomous.ws/answer!` writes a reply with
  `autonomous.domain.wire/print-edn`. The bridge mirrors it:
  `autonomous.bridge.ws/read-frame` and `/frame-text` are `wire/read-edn` and
  `wire/print-edn`. **Reading EDN evaluates nothing** (the reader never evals).
- **Envelope (the four frames, doc/websocket.md and `autonomous.bridge.ws`):**

  ```
  runtime -> bridge  {:ws/type :hello    :token "aut_.." :runtime/id .. :runtime/kind .. :runtime/label ..}
                     {:ws/type :response :id n :answer "<EDN Answer>"}
  bridge  -> runtime {:ws/type :welcome  :target .. :runtime/id ..}
                     {:ws/type :refused  :error {:type .. :message ..}}   then close
                     {:ws/type :request  :id n :request "<EDN Request>"}
  ```

  Close codes: 1000 normal, 4400 bad-frame, 4401 unauthorized, 4404
  unknown-target, 4408 hello-timeout, 4409 replaced.
- **The `:ws/type` envelope is NOT a proto message.** It is EDN with a keyword
  discriminator. `json_name` cannot express it; a JSON codec cannot read it.
  This is why the frame protos (hello/welcome/request/reply/refused/fatal) are
  **out of scope** for this freeze (card: "frames and telemetry protos" not in
  scope) and land in a later step with their own codec decision.
- **Embedded payloads are EDN text, not JSON.** `:request` and `:answer` are
  **strings of EDN** (`wire/print-edn`), so the inner Answer/Request keeps
  keyword values, symbols, uuids and sets that the outer frame carries
  verbatim. The proto `Answer` describes that inner value's *vocabulary*; the
  bridge reads it back with `read-edn` (`autonomous.bridge.ws/as-answer`,
  which enforces `answer-shape?` = exactly one arm).
- **Release note:** the websocket wire is **EDN v1** and stays v1. A runtime
  and a bridge must agree on the codec; a JSON frame on this socket fails
  `read-frame` (it is not a map with a keyword `:ws/type`) and is refused as
  `:ws/bad-frame`.

### Which peer, which codec

| peer / direction | release | codec | envelope | Caller spelling |
|---|---|---|---|---|
| agent -> bridge (MCP tools/call) | v1 | JSON | MCP JSON-RPC, `params._meta` | `io.github.hive-agi/*` + `traceparent` |
| bridge -> runtime (websocket) | v1 | EDN | `{:ws/type :request :request "<EDN>"}` | inside the EDN Request |
| runtime -> bridge (websocket) | v1 | EDN | `{:ws/type :response :answer "<EDN>"}` | inside the EDN Answer |
| bridge ledger (durable) | v1 | EDN string in `:entry/data` today | durable record | n/a |

### The admitted JSON subset for opaque payloads

Opaque payloads are `Answer.ok`, `Refusal.data`, `Call.args`, `Call.answer`,
`Settled.answer`, `AppEvent.payload`, `CallDetail.redact` (the `:any` fields).
They are carried as `google.protobuf.Value`, which maps to JSON data. The
**admitted subset** is exactly what JSON can represent, plus the mappings
`autonomous.mcp.json/->json-data` already applies:

| EDN | admitted JSON | rule |
|---|---|---|
| `nil` | `null` | Value null |
| string | string | |
| number (finite) | number | `int64` > 2^53 crosses as a **string** (see below) |
| boolean | boolean | |
| keyword | string | namespace kept: `:todo/add` -> `"todo/add"` |
| symbol | string | `str` |
| uuid | string | `str` |
| inst | string | ISO-8601 |
| set | array | order not significant; a codec must not promise order |
| vector / seq / list | array | |
| map | object | keys stringified |

**The ACL REFUSES what JSON cannot represent; it never stringifies silently**
(fa8f9f, card answer Q1). On the **proto/JSON** wire:

- **A keyword in VALUE position is refused** (`:contract/edn-only-value`,
  `:what :keyword`). A keyword as a *map key* is fine (stringified); a keyword
  as a *value* has no JSON type and is refused, not coerced.
- **A uuid is refused** (`:what :uuid`).
- **A set is refused** (`:what :set`). (Note: `autonomous.mcp.json` today DOES
  stringify these for the legacy JSON wire; that is the v1 MCP behaviour and is
  kept for MCP, but the **proto** ACL refuses them rather than repeat a silent
  coercion. The two rules are named so neither is guessed.)
- **`int64` beyond 2^53 is refused as a number and must travel as a string**
  (`:what :int64-range`); a JSON reader must not silently narrow it to a
  double. `Call.ms`, `LedgerEntry.at`, `LedgerEntry.seq`, `ChatPoint.seq`,
  `ChatPoint.turn` are `int64`.
- **bytes** are canonical base64 in the portable/JSON view.

When one field truly needs EDN fidelity, the per-field option is a oneof
`{google.protobuf.Value json = 1; bytes edn = 2;}` whose `edn` arm is marked
**Clojure-only**. That is **never the default**, because bytes-EDN breaks Rust,
TS and Elm. On the **EDN websocket** wire (Wire B) there is no such refusal:
the payload is EDN text and carries keywords, uuids and sets natively.

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
