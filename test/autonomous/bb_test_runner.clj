(ns autonomous.bb-test-runner
  "`bb test`: the unit suites on babashka, the same namespaces the JVM
   kaocha :unit suite runs. Exits 0 when every test passes, else 1."
  (:require [clojure.test :as t]))

(def suites
  "Every unit suite. The datahike ledger contract is gated to the JVM
   inside bridge-trifecta-test; the live e2e runs as `bb e2e`."
  '[autonomous.domain.decision-trifecta-test
    autonomous.domain.primitives-trifecta-test
    autonomous.domain.fleet-trifecta-test
    autonomous.bridge.bridge-trifecta-test
    autonomous.bridge.fleet-test
    autonomous.bridge.ws-test
    autonomous.mcp.transport-test
    autonomous.ring-test
    autonomous.e2e.check-trifecta-test
    autonomous.demo.plan-test
    autonomous.runtime.rpc-test
    autonomous.re-frame.model-trifecta-test
    autonomous.openapi-test
    autonomous.openapi-codec-test
    autonomous.openapi-invoke-test
    autonomous.bb-mcp.mount-test])

(defn -main [& _]
  (apply require suites)
  (let [{:keys [fail error]} (apply t/run-tests suites)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
