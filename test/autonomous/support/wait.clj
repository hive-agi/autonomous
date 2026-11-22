(ns autonomous.support.wait
  "Test support (JVM and bb): wait on a reference's changes, not on a
   clock. A watch answers on the change that makes the predicate hold; the
   deadline only bounds a hang."
  (:require [autonomous.runtime.registry :as registry]))

(def default-ms
  "How long a wait may take before it gives up."
  5000)

(defn await-ref
  "(pred v) for the first value `v` reference `r` holds that satisfies
   `pred`, read now and on every change of `r`; nil when none does within
   `ms` (default `default-ms`)."
  ([r pred] (await-ref r pred default-ms))
  ([r pred ms]
   (let [k (Object.) hit (promise)]
     (add-watch r k (fn [_ _ _ v] (when-let [x (pred v)] (deliver hit x))))
     (when-let [x (pred @r)] (deliver hit x))
     (try (deref hit ms nil) (finally (remove-watch r k))))))

(defn await!
  "(pred v) as `await-ref` answers it; throws an ex-info naming `what` was
   awaited when nothing satisfies `pred` within `ms`, so a timeout fails as
   itself and not as the assertion after it."
  ([r what pred] (await! r what pred default-ms))
  ([r what pred ms]
   (let [x (await-ref r pred ms)]
     (when (nil? x)
       (throw (ex-info (str "timed out after " ms " ms waiting for " what)
                       {:autonomous.test/wait what :ms ms})))
     x)))

(defn state-of
  "The state atom of Runtime `rt` (registry/make-runtime, or
   registry/default-runtime for the facade API's process-wide one)."
  [rt]
  (if (registry/runtime? rt)
    (:state rt)
    (throw (ex-info (str "not a Runtime: " (pr-str rt)) {:autonomous.test/wait :not-a-runtime}))))

(defn bound-state
  "The state atom of the Runtime registry/*state* is bound to. Throws an
   ex-info saying so when it is unbound, where add-watch would throw a
   bare NullPointerException."
  []
  (if (some? registry/*state*)
    (state-of registry/*state*)
    (throw (ex-info (str "registry/*state* is unbound: bind a Runtime "
                         "(registry/with-instance) before waiting on its state")
                    {:autonomous.test/wait :unbound-state}))))

(defn settled-of
  "The :autonomous/settled journal entry of parked `id` among `entries`."
  [id entries]
  (first (filter #(and (= :autonomous/settled (:kind %)) (= id (:pending %))) entries)))

(defn settled?
  "Has parked `id` settled in runtime state `st`?"
  [id st]
  (= :settled (get-in st [:pending id :status])))

(defn await-settled!
  "Wait until parked `id` has settled in runtime state atom `st`; throws
   when it has not within `ms` (default `default-ms`)."
  ([st id] (await-settled! st id default-ms))
  ([st id ms] (await! st (str "parked call " id " to settle") (partial settled? id) ms)))

(defn await-settled-entry!
  "The :autonomous/settled journal entry of parked `id` once runtime state
   atom `st` holds it, collected or not; throws when it does not within
   `ms` (default `default-ms`)."
  ([st id] (await-settled-entry! st id default-ms))
  ([st id ms] (await! st (str "the settled entry of parked call " id)
                      #(settled-of id (get-in % [:journal :entries])) ms)))
