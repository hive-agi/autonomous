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
    (a/reg-tool :slow {:description "Slow"
                       :handler (fn [_ _] (js/Promise. (fn [ok _] (js/setTimeout #(ok {:done true}) 5))))})
    (let [st       (:state registry/default-runtime)
          torn     (atom [])
          finished (atom false)
          deadline (atom nil)
          finish!  (fn [settled]
                     (when (compare-and-set! finished false true)
                       (js/clearTimeout @deadline)
                       (remove-watch st ::torn)
                       (is (some? settled) "the call settled before the deadline")
                       (is (= [] @torn) "every state that holds the settled answer holds its entry too")
                       (when settled
                         (is (= {:ok {:done true}} (a/rpc {:op :poll :id (:id settled)})))
                         (is (some? (settled-of (:id settled) (:entries settled)))))
                       (done)))
          id       (:pending (a/rpc {:op :call :tool "slow" :actor "agent" :scopes [:write]}))]
      (add-watch st ::torn
                 (fn [_ _ _ s]
                   (doseq [[pid {:keys [status]}] (:pending s)
                           :when (and (= :settled status)
                                      (nil? (settled-of pid (get-in s [:journal :entries]))))]
                     (swap! torn conj pid))
                   (when (= :settled (get-in s [:pending id :status]))
                     (let [settled {:id id :entries (get-in s [:journal :entries])}]
                       (js/setTimeout #(finish! settled) 0)))))
      (reset! deadline (js/setTimeout #(finish! nil) 5000)))))
