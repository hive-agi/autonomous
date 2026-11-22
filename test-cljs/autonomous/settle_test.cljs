(ns autonomous.settle-test
  "Settling a parked call on a JS runtime: the answer and its
   :autonomous/settled journal entry land in one state change."
  (:require [autonomous.core :as a]
            [autonomous.runtime.registry :as registry]
            [cljs.test :refer [async deftest is]]))

(defn- settled-of [id entries]
  (first (filter #(and (= :autonomous/settled (:kind %)) (= id (:pending %))) entries)))

(deftest no-state-holds-a-settled-answer-without-its-journal-entry
  (async done
    (a/reset-registry!)
    (a/reg-tool :slow {:description "Slow" :handler (fn [_ _] (js/Promise. (fn [ok _] (js/setTimeout #(ok {:done true}) 5))))})
    (let [st   (:state registry/default-runtime)
          torn (atom [])
          k    ::torn]
      (add-watch st k (fn [_ _ _ s]
                        (doseq [[id {:keys [status]}] (:pending s)
                                :when (and (= :settled status)
                                           (nil? (settled-of id (get-in s [:journal :entries]))))]
                          (swap! torn conj id))))
      (let [id (:pending (a/rpc {:op :call :tool "slow" :actor "agent" :scopes [:write]}))]
        (add-watch st ::settled
                   (fn [_ _ _ s]
                     (when (= :settled (get-in s [:pending id :status]))
                       (remove-watch st ::settled)
                       (remove-watch st k)
                       (is (= [] @torn) "every state that holds the settled answer holds its entry too")
                       (is (some? (settled-of id (get-in s [:journal :entries]))))
                       (is (= {:ok {:done true}} (a/rpc {:op :poll :id id})))
                       (done))))))))
