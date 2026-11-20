# The public playground

`https://autonomous.buddhilw.com` runs the [fullstack todo](../../example/fullstack)
for anyone: one shared list, a Rust backend that agents drive over MCP, and an
Elm page that shows what they do. Visitors connect their own agent with the
command the page prints.

`playground.yaml` is the whole deployment. Its header lists the sandbox layers;
in short: its own namespace under Pod Security `restricted`, a 1 CPU / 1 GiB
quota, non-root read-only containers with no capabilities and no service-account
token, NetworkPolicies that allow only Envoy in and the backend to the bridge
(no internet, no other namespace, no Kubernetes API), a bridge that serves one
websocket target (no nREPL, no eval), and backend limits (30 todos, 80
characters, a reset every 10 minutes).

## What a visitor's agent can do

The public agent token grants `read` and `write`. Through it an agent sees:

- `backend__todos_list`, `backend__todos_add`, `backend__todos_toggle`,
  `backend__todos_remove`, `backend__todos_clear-done`: the todo store.
- The built-ins: `autonomous_whoami`, `autonomous_apps`, `autonomous_runtimes`,
  `autonomous_attach`, `autonomous_journal` (what humans did on the page),
  `autonomous_ledger`, `autonomous_poll`, `autonomous_lease` (at most
  5 minutes), `autonomous_release`.

The page's own UI tools (`frontend__ui_*`) are not served here: the page has no
runtime token, so agents reach the backend only.

## Build and publish

From the repository root, with `JOLT_CHEZ_CSV` set (see doc/cabi.md):

```sh
bb cabi                                              # cabi/build/libautonomous.so
(cd example/fullstack/backend && cargo build --release)
bb fullstack-e2e                                     # builds frontend/dist, checks it all
deploy/playground/stage-backend.sh                   # target/playground

docker build -f deploy/playground/bridge/Dockerfile -t ghcr.io/buddhilw/autonomous-bridge:TAG .
docker build -f deploy/playground/backend/Dockerfile -t ghcr.io/buddhilw/autonomous-todo:TAG target/playground
docker push ghcr.io/buddhilw/autonomous-bridge:TAG
docker push ghcr.io/buddhilw/autonomous-todo:TAG
```

Make both packages public on GitHub (package settings, "Change visibility"), so
the cluster pulls without a pull secret. Put the pushed digests into
`playground.yaml` (`image: ...@sha256:...`).

## Tokens

```sh
dir=$(mktemp -d)
bb -cp src:deploy/playground -m tokens "$dir"
kubectl create namespace autonomous-playground --dry-run=client -o yaml | kubectl apply -f -   # or apply playground.yaml first
kubectl -n autonomous-playground create secret generic playground-tokens \
  --from-file=autonomous.edn="$dir/autonomous.edn" \
  --from-file=backend-token="$dir/backend-token" \
  --from-file=agent-token="$dir/agent-token"
rm -rf "$dir"
```

`autonomous.edn` holds only SHA-256 hashes. The backend token stays in the
Secret. The agent token is public by design: the page shows it.

To rotate, mint again, replace the Secret and restart both deployments.

## Deploy

```sh
kubectl apply --dry-run=server -f deploy/playground/playground.yaml
kubectl apply -f deploy/playground/playground.yaml
kubectl -n autonomous-playground rollout status deploy/bridge deploy/todo
```

## Take it down

```sh
kubectl delete namespace autonomous-playground
```
