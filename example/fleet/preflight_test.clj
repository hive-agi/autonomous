(ns fleet.preflight-test
  "The pure part of `bb fleet-preflight`: given these facts, these lines."
  (:require [clojure.test :refer [deftest is testing]]
            [fleet.preflight :as pf]))

(def all-tools #{"clojure" "bb" "npm" "chromium"})

(deftest nothing-missing-nothing-taken-prints-nothing
  (is (empty? (pf/findings {:found all-tools :taken #{} :headless? true :node-modules-missing? true}))))

(deftest a-missing-tool-is-one-line
  (is (= ["missing tool: bb (bb) - needed for the inventory service and the fleet bridge"]
         (pf/findings {:found #{"clojure"}}))))

(deftest conditional-tools-are-needed-only-when-their-fact-holds
  (testing "npm and chromium absent but not needed"
    (is (empty? (pf/findings {:found #{"clojure" "bb"}}))))
  (testing "needed: one line each, in list order"
    (is (= ["missing tool: npm (npm) - needed for installing node_modules (react is missing)"
            "missing tool: chromium (chrome-headless-shell or chromium or chromium-browser or google-chrome) - needed for the headless todo page (--headless, --probe; FLEET_CHROMIUM overrides)"]
           (pf/findings {:found #{"clojure" "bb"} :node-modules-missing? true :headless? true}))))
  (testing "any one alternative is enough"
    (is (empty? (pf/findings {:found #{"clojure" "bb" "google-chrome"} :headless? true})))))

(deftest taken-ports-are-one-line-each
  (is (= ["port taken: 7888 - needed for shadow-cljs nREPL (program todo)"
          "port taken: 7872 - needed for inventory nREPL (program inventory)"]
         (pf/findings {:found all-tools :taken #{7888 7872 9999}}))))

(deftest the-dev-http-port-follows-its-env
  (is (= ["port taken: 8380 - needed for the todo page (shadow :dev-http)"]
         (pf/findings {:found all-tools :taken #{8280 8380} :env {"AUTONOMOUS_DEV_HTTP_PORT" "8380"}})))
  (is (= 8280 (pf/port-of {"AUTONOMOUS_DEV_HTTP_PORT" " "} (first pf/ports)))))
