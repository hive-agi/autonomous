(ns autonomous.bb-test-runner
  "`bb test`: the unit suites on babashka, the same namespaces the JVM
   kaocha :unit suite runs. Exits 0 when every test passes, else 1."
  (:require [clojure.test :as t]))

(def suites
  "Every unit suite. The datahike ledger contract is gated to the JVM
   inside bridge-trifecta-test; the live e2e runs as `bb e2e`."
  '[autonomous.domain.answer-railway-test
    autonomous.approval-test
    autonomous.require-approval-test
    autonomous.surface-test
    autonomous.spi-test
    autonomous.contract-test
    autonomous.ring-contract-test
    autonomous.domain.decision-trifecta-test
    autonomous.domain.primitives-trifecta-test
    autonomous.domain.fleet-trifecta-test
    autonomous.bridge.bridge-trifecta-test
    autonomous.bridge.sources-strata-test
    autonomous.bridge.fleet-test
    autonomous.bridge.ws-test
    autonomous.bridge.http-test
    autonomous.bridge.http-smoke-test
    autonomous.mcp.transport-test
    autonomous.mcp.protocol-strata-test
    autonomous.ring-test
    autonomous.e2e.check-trifecta-test
    autonomous.demo.plan-test
    autonomous.runtime.rpc-test
    autonomous.runtime.registry-test
    autonomous.runtime.chain-test
    autonomous.runtime.intercept-test
    autonomous.surface-transform-test
    autonomous.re-frame.model-trifecta-test
    autonomous.openapi-test
    autonomous.openapi-derive-test
    autonomous.openapi-surface-test
    autonomous.openapi-codec-test
    autonomous.openapi-codec-strata-test
    autonomous.openapi-invoke-test
    autonomous.openapi-boundary-strata-test
    autonomous.openapi-pure-strata-test
    autonomous.asyncapi-core-test
    autonomous.asyncapi-source-test
    autonomous.json-spec-test
    autonomous.bb-mcp.mount-test])

(defn -main [& _]
  (apply require suites)
  (let [{:keys [fail error]} (apply t/run-tests suites)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
