# sim: agents play the payer against a simulated bank

| file | role |
|---|---|
| `src/sim/control.clj` | `sim_pix_pay`, `sim_boleto_pay`, `sim_state` over the simulator's `/__sim/` control plane (never reset, balance or waf) |
| `src/sim/persona.clj` | configuration: personas may name only `sicoob-sim`, the real bank waits for a human |
| `test/sim/control_test.clj` | against a stub simulator on 127.0.0.1 |

```sh
kubectl -n funeraria-francana-staging port-forward svc/staging-sicoob-sim 18099:8099
```

```clojure
(sim.control/install! {:base-url "http://127.0.0.1:18099"})
(autonomous.core/decorate! (sim.persona/money-guard asker)) ; after the payment tools are registered
```

See [doc/sim.md](../../doc/sim.md) for the allowlist rules, the
port-forward setup and the payer-paid flow.
