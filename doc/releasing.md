# Releasing

`autonomous` is released to Clojars as `io.github.hive-agi/autonomous`.
Every library change that reaches `main` releases a new patch version. Nobody
picks the number by hand, and nothing is committed back to `main`.

## Branches

| branch | role | workflow |
|---|---|---|
| feature branches | work | none |
| `staging` | the gate | `staging-gate.yml` |
| `main` | releases | `release.yml` |

Work lands on `staging` first. Only a green `staging` is promoted to `main`,
through a pull request.

## The staging gate

`.github/workflows/staging-gate.yml` runs on every push to `staging`, every
pull request into `main`, and on demand. It runs every portable suite against
the declared classpath (no `local.deps.edn`) and never publishes. Three jobs:

| job | steps |
|---|---|
| JVM unit suite | Java 21; `clojure -P -M:test` (the pinned dependencies must resolve), then `clojure -M:test unit` |
| babashka suite | `bb test` |
| ClojureScript suite | Java 21, Node 22; `npm ci`, `clojure -M:cljs -m shadow.cljs.devtools.cli compile test`, `node target/cljs-test/test.js` |

The `:integration` suite needs live app runtimes and stays local.

## A release per push to main

`.github/workflows/release.yml` runs on a push to `main` that touches the
library: `src/`, `resources/`, `deps.edn`, `version.edn`, `VERSION`, `test/`,
`tests.edn` or `.github/workflows/`. A change to the README or `doc/` mints
nothing, because a published version can never change. It can also be started
by hand (`workflow_dispatch`). One release runs at a time: a run never
cancels the one in progress, and GitHub keeps only the newest run waiting
behind it.

The steps:

1. **Gate.** `clojure -M:test unit`. A red suite stops the run before any
   version exists.
2. **Base.** Take the larger of the `VERSION` file and the newest `v*` tag.
3. **Bump.** `clojure -T:build bump :level :patch` writes base plus one patch
   into `VERSION`, inside the runner only.
4. **Tag.** Create the annotated tag `v<version>` and push the tag alone.
5. **Deploy.** `clojure -T:build deploy` builds the jar and publishes it to
   Clojars.

So the number comes from the tags: the repository has `v0.1.1` and `v0.1.2`,
and Clojars has `0.1.2`.

### VERSION is a floor

The `VERSION` file in the tree is not the current version. It is the lowest
version the next release may have. To open a new minor line, raise it in a
pull request to `staging`, for example from `0.1.0` to `0.2.0`: the next
release on `main` is then `0.2.1`. The release never writes `VERSION` back to
`main`. A bump commit on `main` would be one that `staging` lacks, and every
`staging` to `main` pull request would then conflict on `VERSION`.

### Coordinates

`version.edn` holds what the build needs besides the number:

```clojure no-run
{:lib      io.github.hive-agi/autonomous
 :minor    1
 :license  {:name "MIT" :url "https://opensource.org/licenses/MIT"}
 :scm-url  "https://github.com/hive-agi/autonomous"
 :src-dirs ["src"]
 :publish  :clojars}
```

### Secrets

The deploy step needs two repository (or hive-agi organisation) secrets:

| secret | holds |
|---|---|
| `CLOJARS_USERNAME` | the Clojars user |
| `CLOJARS_DEPLOY_TOKEN` | a Clojars deploy token |

The workflow passes the token to the build as `CLOJARS_PASSWORD`, the name the
deploy tooling reads.

## Build tasks

There is no `build.clj`. The `:build` alias in `deps.edn` points at
[hive-build](https://github.com/hive-agi/hive-build) 0.3.1, the release
library every hive package shares: `clojure -T:build <task>`.

| task | effect |
|---|---|
| `clean` | remove `target/` |
| `jar` | build the source jar under `target/` |
| `install` | jar and install to the local `~/.m2`, offline |
| `bump :level :patch` | rewrite `VERSION` to the next version (also `:minor`, `:major`); no commit, no tag |
| `verify-license` | check that `LICENSE`, `version.edn :license` and any SPDX headers agree; `:strict true` fails on a mismatch |
| `kondo` | clj-kondo gate |
| `changelog` | regenerate `CHANGELOG.md` from the commits between release tags |
| `deploy` | report the license check, build, and publish `VERSION` per `version.edn :publish`; a version already on Clojars is skipped |

To check a release locally before pushing:

```sh
clojure -M:test unit
clojure -T:build verify-license :strict true
clojure -T:build install        # then depend on the version in VERSION from ~/.m2
```

## The hive module

The `hive/` module (the `autonomous` IAddon) has its own `deps.edn`, depends
on the released library, and has no release workflow yet. See
[hive.md](hive.md).
