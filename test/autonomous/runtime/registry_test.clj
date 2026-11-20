(ns autonomous.runtime.registry-test
  "The Surface/Runtime split: a Runtime is a per-instance record behind
   IRuntime, the default runtime is the facade's only global, and the
   surface value carries no runtime state."
  (:require [autonomous.core :as a]
            [autonomous.domain.answer :as answer]
            [autonomous.runtime.registry :as registry]
            [autonomous.surface :as surface]
            [clojure.test :refer [deftest is testing]]))

(def ^:private rw {:actor "agent-1" :scopes #{:read :write}})

(defn- spec [v]
  {:description (str v) :handler (fn [_ _] v)})

(deftest make-runtime-holds-a-surface-and-empty-state
  (let [rt (registry/make-runtime)]
    (is (registry/runtime? rt))
    (is (= surface/empty-surface (registry/-surface rt)))
    (let [snap (registry/-snapshot rt)]
      (is (= {} (:tools snap)))
      (is (= {} (:pending snap)))
      (is (nil? (:lease snap)))
      (is (= 0 (get-in snap [:journal :seq]))))
    (is (= (registry/-snapshot rt) @rt) "deref answers the snapshot")))

(deftest make-runtime-validates-the-surface-loudly
  (is (thrown? clojure.lang.ExceptionInfo
               (registry/make-runtime {:tools {"bad" {:name "bad"}}}))))

(deftest two-runtimes-share-nothing
  (let [ra (registry/make-runtime)
        rb (registry/make-runtime)]
    (registry/with-instance ra #(registry/add-tool! :a/only (spec :a)))
    (registry/with-instance rb #(registry/add-tool! :b/only (spec :b)))
    (is (= #{"a_only"} (set (keys (:tools (registry/-surface ra))))))
    (is (= #{"b_only"} (set (keys (:tools (registry/-surface rb))))))
    (testing "journal, lease and pending are per instance too"
      (registry/with-instance ra #(registry/record! {:kind :t/e}))
      (is (= 1 (get-in (registry/-snapshot ra) [:journal :seq])))
      (is (= 0 (get-in (registry/-snapshot rb) [:journal :seq])))
      (registry/with-instance ra #(registry/acquire-lease! "a" 60000))
      (is (= "a" (:actor (:lease (registry/-snapshot ra)))))
      (is (nil? (:lease (registry/-snapshot rb))))
      (let [id (registry/with-instance ra #(registry/park!))]
        (is (answer/pending? (registry/with-instance ra #(registry/take-settled! id))))
        (is (answer/refused? (registry/with-instance rb #(registry/take-settled! id))))))))

(deftest surface-value-carries-no-runtime-state
  (a/reset-registry!)
  (a/reg-tool :s/one (spec 1))
  (a/journal! {:kind :t/e})
  (let [s (registry/surface-value)]
    (is (surface/surface? s))
    (is (empty? (select-keys s [:journal :lease :pending :epoch :version :next-id])))
    (is (= #{:app :tools :resources :interceptors} (set (keys s))))))

(deftest the-facade-acts-on-the-one-default-runtime
  (a/reset-registry!)
  (a/reg-tool :f/one (spec 1))
  (is (contains? (:tools (registry/-surface registry/default-runtime)) "f_one"))
  (is (= {:ok 1} (a/rpc {:op :call :tool "f_one" :actor "a" :scopes [:write]})))
  (testing "a bound instance never leaks into the default"
    (let [rt (registry/make-runtime)]
      (registry/with-instance rt #(a/reg-tool :g/one (spec 2)))
      (is (not (contains? (:tools (registry/surface-value)) "g_one")))
      (is (contains? (:tools (registry/-surface rt)) "g_one"))))
  (a/reset-registry!))

(deftest instance-is-make-runtime-and-clear-resets-both-halves
  (let [rt (registry/instance {:app {:name "x"} :tools {} :resources {} :interceptors []})]
    (is (registry/runtime? rt))
    (registry/with-instance rt
      (fn []
        (registry/add-tool! :x/t (spec :x))
        (registry/record! {:kind :t/e})
        (registry/clear!)
        (is (= surface/empty-surface (registry/surface-value)))
        (is (= 0 (get-in (registry/snapshot) [:journal :seq])))))))