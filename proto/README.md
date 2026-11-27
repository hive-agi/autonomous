# autonomous protobuf contracts (`autonomous.v1`)

**STATUS: DRAFT and UNPINNED.** These `.proto` files are the first autonomous
contracts written proto-first (Pedro, 2026-11-27, decision
`20261127161621-4586b84e`). Field numbers and wrapper types **may change**:
they are pinned only after the design note of the proto -> `hive-dsl.adt`
transform lands. Do not generate code from them yet, and do not treat them as
a frozen wire.

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
| `autonomous/v1/caller.proto` | `Caller`, `ChatPoint`, `Correlation` | `hive-agent.loop.chat-point/ChatPoint`, `hive-spi.agent.run-control/Correlation` |

The source of truth is the **existing code**. `MAPPING.md` lists, one row per
proto field, the Clojure key it mirrors and whether it is mirrored, renamed,
new or omitted. That table is what the later derivation-drift test checks
against.

## Linting

`buf` 1.75 is installed. From the repository root:

```sh
buf lint                 # STANDARD
buf build                # compiles every file, well-known types included
buf format -d            # diff only; fails when the tree is unformatted
```

`buf.yaml` pins `version: v2`, module path `proto`, `lint: STANDARD` and
`breaking: FILE`. Keep `buf lint` and `buf format -d` clean.
