(ns autonomous.bb-test-runner
  "`bb test`: the suites that run on babashka (everything but the datahike
   ledger, the JDK HTTP socket and the live e2e). Exit code is the number of
   failures and errors, capped at 1."
  (:require [clojure.test :as t]))

(def suites
  "primitives-trifecta-test needs hive-schemas.test, not yet proven on bb
   (kanban follow-up)."
  '[autonomous.domain.decision-trifecta-test
    autonomous.runtime.rpc-test
    autonomous.re-frame.model-trifecta-test])

(defn -main [& _]
  (apply require suites)
  (let [{:keys [fail error]} (apply t/run-tests suites)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
