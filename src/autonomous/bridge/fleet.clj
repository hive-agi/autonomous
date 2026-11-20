(ns autonomous.bridge.fleet
  "Pipeline stratum of a fleet: one client context (autonomous.bridge.client)
   per target, all sharing one ledger, and the operations the MCP server
   needs, routed to the owning target. A single-target bridge is a fleet of
   mode :single; names are prefixed per app only in mode :fleet.

   Isolation: every look at the targets (probe) runs one future per target
   and waits at most :probe-ms for each. A target that is down answers a
   refusal at once; one that is slow is reported :slow while its probe keeps
   running, and the next look reuses that probe instead of piling up more.
   Neither delays the other targets. Portable: futures, atoms and deref with
   a timeout, which the JVM, babashka and ClojureWasm all carry."
  (:require [autonomous.bridge.client :as client]
            [autonomous.bridge.session :as s]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.fleet :as fleet]))

(def default-probe-ms
  "How long one look waits for a target's manifest before calling it slow."
  1500)

(defn make
  "A fleet over `members`, a vector of [target-id client-context] in fleet
   order. opts: :mode (:fleet, or :single for exactly one member), :probe-ms."
  [members {:keys [mode probe-ms]}]
  (let [ids  (mapv first members)
        mode (or mode (if (= 1 (count ids)) :single :fleet))]
    {:mode     mode
     :view     (fleet/view mode ids)
     :order    ids
     :members  (into {} members)
     :probe-ms (or probe-ms default-probe-ms)
     :inflight (atom {})
     :last     (atom {})}))

(defn from-context
  "The single-mode fleet of client context `ctx` (the bridge as it always
   was)."
  [ctx]
  (make [[(get-in ctx [:target :target/id] "app") ctx]] {:mode :single}))

(defn member
  "The client context of target `target-id`."
  [f target-id]
  (get-in f [:members target-id]))

(defn on
  "`session` as it speaks to target `target-id`."
  [f session target-id]
  (s/on-target (:mode f) session target-id))

(defn- probe-future
  "The running look at target `tid` for session `ses`: one already in flight
   for the same actor, target, scopes and runtime, else a new future."
  [f tid ses]
  (let [k [tid (:session/actor ses) (:session/scopes ses) (:session/runtime ses)]
        ctx (member f tid)]
    (or (let [fut (get @(:inflight f) k)] (when (and fut (not (realized? fut))) fut))
        (let [fut (future (try (client/manifest ctx ses)
                               (catch Exception e (answer/from-exception e))))]
          (swap! (:inflight f) assoc k fut)
          fut))))

(defn classify
  "The Probe of target `tid` whose manifest answer is `a` (::slow when it
   did not arrive in time), taken at `at`."
  [tid a at]
  (cond
    (= ::slow a)      {:target tid :status :slow :at at
                       :error {:type :target/slow :message "no manifest within the probe deadline"}}
    (answer/ok? a)    {:target tid :status :up :at at :manifest (:ok a)}
    :else             {:target tid :status :down :at at :error (:error a)}))

(defn probe
  "Probes of every target, in fleet order, for `session`. All targets are
   asked at once; each answer is awaited until one shared deadline
   :probe-ms from now, so one slow target costs at most that long and a
   dead one nothing."
  [f session]
  (let [now      #(System/currentTimeMillis)
        start    (now)
        deadline (+ start (:probe-ms f))
        futs     (mapv (fn [tid] [tid (probe-future f tid (on f session tid))]) (:order f))
        probes   (mapv (fn [[tid fut]]
                         (classify tid (deref fut (max 0 (- deadline (now))) ::slow) start))
                       futs)]
    (swap! (:last f) merge (into {} (map (juxt :target identity)) probes))
    probes))

(defn surface
  "What `session` sees of the fleet now: {:app :tools :resources :apps
   :probes :fingerprint}."
  [f session]
  (let [probes (probe f session)]
    (assoc (fleet/surface (:view f) probes)
           :probes probes
           :fingerprint (fleet/fingerprint probes))))

(defn- known-tools
  "Tool names target `tid` had at its last probe."
  [f tid]
  (map :name (get-in @(:last f) [tid :manifest :tools])))

(defn route-tool
  "Ok {:target :local} of exposed tool name `exposed`. A name cut at 64
   characters is resolved against the target's tools at its last probe."
  [f exposed]
  (let [r (fleet/route-tool (:view f) exposed)]
    (if (and (answer/ok? r) (= :fleet (:mode f)) (fleet/cut? exposed))
      (let [tid (get-in r [:ok :target])]
        (answer/ok {:target tid
                    :local (or (fleet/resolve-local (fleet/prefix tid) exposed (known-tools f tid))
                               (get-in r [:ok :local]))}))
      r)))

(defn call!
  "Answer of calling exposed tool `exposed` with `args` as `session`, from
   the owning target. A Pending id comes back qualified by the target."
  [f session exposed args]
  (let [r (route-tool f exposed)]
    (if (answer/refused? r)
      r
      (let [{:keys [target local]} (:ok r)]
        (fleet/qualify (:view f) target
                       (client/call! (member f target) (on f session target) local args))))))

(defn read-resource
  "Answer of reading exposed resource `uri` as `session`."
  [f session uri]
  (let [r (fleet/route-uri (:view f) uri)]
    (if (answer/refused? r)
      r
      (let [{:keys [target local]} (:ok r)]
        (client/read-resource (member f target) (on f session target) local)))))

(defn poll
  "Answer of polling (qualified, in a fleet) pending `id` as `session`."
  [f session id]
  (let [r (fleet/route-pending (:view f) id)]
    (if (answer/refused? r)
      r
      (let [{:keys [target local]} (:ok r)]
        (fleet/qualify (:view f) target
                       (client/poll (member f target) (on f session target) local))))))

(defn target-of
  "Ok of the target a built-in call with argument `target` addresses."
  [f target]
  (fleet/target-arg (:view f) target))

(defn runtimes
  "Answer of the runtimes of target `tid`."
  [f tid]
  (client/runtimes (member f tid)))

(defn ledger-query
  "Ok of ledger views matching LedgerQuery `q` across the fleet (one shared
   ledger), after draining `session`'s runtime on the queried target, or on
   every target that is not known to be down when `q` names none. A target
   that fails to drain does not fail the query."
  [f session q]
  (let [tids (if (:target q)
               [(:target q)]
               (remove #(= :down (get-in @(:last f) [% :status])) (:order f)))
        ctxs (map #(member f %) tids)]
    (if-not (some :ledger ctxs)
      (client/ledger-query (member f (first (:order f))) session q)
      (do (doseq [tid (rest tids)]
            (try (client/drain! (member f tid) (on f session tid))
                 (catch Exception _drain-failed nil)))
          (client/ledger-query (member f (first tids)) (on f session (first tids)) q)))))
