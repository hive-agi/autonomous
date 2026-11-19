(ns autonomous.bb-test-runner
  "`bb test`: the suites that run on babashka (everything but the datahike
   ledger, the JDK HTTP socket and the live e2e). Exit code is the number of
   failures and errors, capped at 1."
  (:require [clojure.test :as t]))

(def suites
  "Every suite that needs no datahike, JDK HTTP socket or live runtime."
  '[autonomous.domain.primitives-trifecta-test
    autonomous.domain.decision-trifecta-test
    autonomous.domain.fleet-trifecta-test
    autonomous.bridge.fleet-test
    autonomous.runtime.rpc-test
    autonomous.re-frame.model-trifecta-test])

(defn -main [& _]
  (apply require suites)
  (let [{:keys [fail error]} (apply t/run-tests suites)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
