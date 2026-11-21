# Paying a simulated bank: provider allowlist and sim tools

Agents can drive payments end to end against a simulated bank. Two pieces
make that safe:

- `autonomous.decorator/provider-allowlist` lets a money-moving tool name
  a simulator provider without a human, and puts every other provider to
  one.
- The sim tools (`example/sim`): `sim_pix_pay`, `sim_boleto_pay` and
  `sim_state` play the payer over the simulator's control plane, reached
  by `kubectl port-forward`.

Both pieces are generic. The provider ids, tool names and URLs below come
from one deployment (a payment service with a real Sicoob, bank code 756,
and its simulator `sicoob-sim`). They are configuration data.

## provider-allowlist

```clojure
(require '[autonomous.core :as a] '[autonomous.decorator :as d])

(def providers                       ; as the payment service resolves them
  [{:code "756"        :name "Sicoob"}
   {:code "sicoob-sim" :name "SicoobSim"}])

(a/decorate!
  (a/each-tool
    (comp (d/require-scope :payment/money)
          (d/provider-allowlist {:path [:provider] :providers providers
                                 :allowed ["sicoob-sim"]})
          (d/require-approval {:asker asker :timeout-ms 300000}))))
```

A call is decided in this order:

1. **Scopes.** A caller without the tool's scopes is refused `:forbidden`
   and no human is asked. This holds whatever the order of the decorators.
2. **Allowlist.** If the value at `:path` names an allowed provider, the
   tool runs at once, past the approval gate.
3. **Approval.** Any other value goes to the human through
   require-approval. Without an approval gate it is refused
   `:provider-not-allowed`, with `:data {:path :allowed}`.

The value is read the way the payment service resolves a provider: a
**code exactly**, else a **name ignoring ASCII case**. So `sicoob-sim`,
`SicoobSim` and `SICOOBSIM` are the simulator. None of the following names
an allowed provider, so each goes to the human (or is refused):

- `756`, `Sicoob`, `sicoob`
- the number `756`
- `" sicoob-sim"` and any other padding
- `SICOOB-SIM` (a code is matched exactly)
- `ſicoobsim` or any other non-ASCII look-alike
- a missing value

The service decodes JSON with Go's `encoding/json`, which matches keys
case-insensitively (with Unicode simple folding: `K` is `k`, `ſ` is
`s`). Keys are compared as the encoder writes them: a keyword or symbol
by its name only, so `:x/Provider` is sent as `"Provider"`. The call is
refused `:provider-ambiguous` when any map along `:path` holds either of
the following:
- a second key whose wire text equals the path key's, ignoring ASCII
  case (`{:provider "sicoob-sim" :Provider "756"}`, `:x/provider`,
  `'Provider`);
- a key with no agreed wire text (a number, nil, a vector), or a
  non-ASCII one.

The second rule fails closed instead of copying Go's whole fold table.
The refusal applies whether or not there is an approval gate, because
the human would be shown one provider while the service read the other.
Provider codes and names must be printable ASCII for the same reason.

So a non-ASCII key anywhere along the provider path refuses the call
outright, even when the key looks unrelated (`{:provider "sicoob-sim"
:memö "x"}` with `:path [:provider]` is refused, and so is a non-ASCII
key in any map the path passes through). The call never reaches the
human or the service. This is deliberate: it is safe, and a caller fixes
it by sending ASCII keys only.

The match is narrower than the service's on purpose: a value read
differently on the two sides can only fail to be allowed, never let the
real bank through.

Options are checked when the decorator is built. A bad path, no
providers, or an `:allowed` code that no provider has throws
`:invalid-allowlist`. The decorator is in the
`decorators-preserve-schemas` law (`autonomous.contract/tool-decorators`).

### The persona configuration

`example/sim/src/sim/persona.clj` holds the configuration:

- `providers`: what the service registers.
- `persona-allowed`: `["sicoob-sim"]`. Add `card-sim` here, and to
  `providers`, once the service has it.
- `money-tools`: the MCP names that move money.
- `money-guard`: a Surface -> Surface decorator that applies the
  composition above to those tools only. It throws
  `:sim/money-tool-missing` when a listed name is not on the surface, so
  a renamed or unregistered tool is never left unguarded while the guard
  looks installed. Install it after the payment tools are registered.

```clojure
(a/decorate! (sim.persona/money-guard asker))
```

`providers` must match the payment service's `knownProviders` (code and
name) exactly. A provider the service registers but this list omits
fails closed: the allowlist resolves nothing for it, so a call naming it
goes to the human. sisf-payment does not register `sicoob-sim` yet
(main 57d290d). Until it does, a call naming `sicoob-sim` passes the
allowlist and is refused by the service.

The real bank, `756`/`Sicoob`, stays behind a human, for a real canary
payment.

## The sim tools

| tool | request | scopes |
|---|---|---|
| `sim_pix_pay` | `POST /__sim/pix/{txid}/pay`, optional `{valor, payerName, payerCpf}` | `:write`, `:sim/pay` |
| `sim_boleto_pay` | `POST /__sim/boleto/{nossoNumero}/pay`, optional `{valorPago}` | `:write`, `:sim/pay` |
| `sim_state` | `GET /__sim/state` | `:read` |

- **A closed set.** The simulator's `/__sim/reset`, `/__sim/balance` and
  `/__sim/waf` are never tools. Reset wipes every open charge under a
  running swarm. `sim.control-test` fails if any tool reaches one of them.
- **Identifiers.** A `txid` is 1..64 characters of `[A-Za-z0-9_-]`, and a
  `nossoNumero` is 1..20 digits. Anything else (`/`, `..`, `%`, spaces,
  non-ASCII) is refused `:invalid-args` before a request is sent. The
  segment is checked again and percent-encoded when the path is built.
- **Answers.** A 2xx answers the simulator's JSON, where `delivered` says
  whether its webhook reached the payment service. Any other status is
  refused `:sim/refused` with `{:status :body}`. No answer at all is
  refused `:sim/unreachable`, usually because the port-forward is down.

### Port-forward setup

The simulator has no public route, and none must be added. Reach it from
the machine the bridge runs on:

```sh
kubectl -n funeraria-francana-staging port-forward svc/staging-sicoob-sim 18099:8099
```

Then install the tools with the local end of the forward:

```clojure
(require 'sim.control)
(sim.control/install! {:base-url "http://127.0.0.1:18099"})
```

`:base-url` must be `http(s)` on this host (`localhost`, `127.0.0.0/8`
or `::1`), with no path, userinfo, query or fragment. An in-cluster or
public address is refused `:invalid-sim-config`. Under Cilium the
simulator's NetworkPolicy may need `fromEntities: [host]` before a
port-forward reaches it.

### The payer paid

1. Create the charge through the payment service with
   `provider: "sicoob-sim"`. It answers the txid, or the nossoNumero for a
   boleto.
2. Call `sim_pix_pay {txid}` (or `sim_boleto_pay {nossoNumero}`). A
   different `valor` exercises the discrepancy path. A second call
   exercises redelivery.
3. The simulator settles the charge and delivers the webhook. The payment
   service confirms the payment with the simulator and books it.
4. Call `sim_state` to see what the simulated bank holds.

### Tests

`sim.control-test` runs against a stub simulator on `127.0.0.1` (http-kit,
port 0). It is part of `bb test` and the kaocha `:unit` suite. The real
simulator is never called.
