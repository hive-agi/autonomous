Copied read-only from funeraria `libs/asyncapi` (that submodule at commit
82a8b58bb01cb4497e5c4e66982a2465377cf8af, "Merge pull request
'feat/rb-61d1e927-first-access-contract-lwt' (#4)"; the parent superproject
commit that pins it is 0084578d27ee1c8a23c8099609cd1c164b3a4942), Pedro's own
repo, as fixtures for the autonomous.asyncapi tests.

`nats-events.yaml` and `schemas/` are byte-for-byte copies, mode 444. Do not
edit them here; refresh by copying again and bumping the commit in this
README. The funeraria repo is never edited from this branch.

The spec has no `sisf.payment.*` channel yet: the payment subjects are named
by the sisf-gateway and sisf-ledger configmaps, not by this document. The
tests subscribe with `sisf.payment.*.entry` against the document anyway, with
no schema to validate against, which is exactly the shape of the alpha tap.
