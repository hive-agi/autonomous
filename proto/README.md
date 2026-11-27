# autonomous protobuf contracts (`autonomous.v1`)

**STATUS: FROZEN for v1 (2026-11-27).** These `.proto` files are autonomous's
first contracts written proto-first (Pedro, 2026-11-27, decision
`20261127161621-4586b84e`). Field numbers, names, presence and the arm policy
are frozen (card `20261127170917-4749e21b`): see the FREEZE section of
`MAPPING.md`. No renumbering; a deleted name or number is `reserved`. A change
to any of them is a `buf breaking` failure and demands a new version.

## Ownership

The transform that turns these protos into Clojure (a plain malli registry in
`.cljc`, plus `defadt` sums for the coordinates where `hive-dsl` is allowed) is
owned by a **separate hivemind** (session `fa8f9f`), not by autonomous. This
directory is autonomous's first real test case for it. See the requirements
brief `20261127161621-4586b84e` and the precedent recipe
`20260724102354-55181003` (`hive-domain`).

## What is here

| file | message | mirrors |
|---|---|---|
| `autonomous/v1/answer.proto` | `Answer` (oneof ok / pending / refusal), `Refusal` | `autonomous.domain.answer`, `autonomous.domain.schema` (`Ok`, `Pending`, `Refusal`) |
| `autonomous/v1/ledger.proto` | `LedgerEntry` (oneof call / settled / app_event), `Call`, `Settled`, `AppEvent`, `Origin`, `Approval` | `autonomous.bridge.ledger/->ledger-entry`, `autonomous.domain.journal/call-entry` + `settled-entry`, `autonomous.re-frame.model/event-entry` |
| `autonomous/v1/call_detail.proto` | `CallDetail` | `autonomous.domain.schema/CallDetail` |
| `autonomous/v1/caller.proto` | `Caller` (flat, S7), `ChatPoint` | `autonomous.domain.schema/Caller` + `ChatPoint` (S7, card `20261127155655-4ebda860`) |

## Fixtures

`fixtures/v1/` holds a redacted good/bad twin (EDN and JSON) per message, plus
`json_name.edn` (the toolchain evidence that slash, kebab and `?` survive in
`json_name`). The named cases (unset envelope, both arms, a spoofed tag,
absent vs present false, zero vs sentinel, a large int64 as a string, unknown
fields) are listed per fixture. `autonomous.contracts.v1.drift-test` loads the
Answer twin and checks it against the hand malli schema; its generator half is
`^:integration` until the transform lands.

The source of truth is the **existing code**. `MAPPING.md` lists, one row per
proto field, the Clojure key it mirrors and whether it is mirrored, renamed,
new or omitted, plus the two wires and the admitted JSON subset.

## Linting

`buf` 1.75 is installed. From the repository root:

```sh
buf lint                 # STANDARD
buf build                # compiles every file, well-known types included
buf format -d            # diff only; fails when the tree is unformatted
```

`buf.yaml` pins `version: v2`, module path `proto`, `lint: STANDARD` and
`breaking: FILE`. Keep `buf lint` and `buf format -d` clean.
