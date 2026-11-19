(ns inventory.test-runner
  "`bb test` in example/inventory: exits 0 when every suite passes."
  (:require [clojure.test :as t]))

(def suites '[inventory.domain-trifecta-test inventory.agentic-test])

(defn -main [& _]
  (apply require suites)
  (let [{:keys [fail error]} (apply t/run-tests suites)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
