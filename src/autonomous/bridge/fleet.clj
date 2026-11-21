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

(def default-view-ttl-ms
  "How long an untouched view of a target (its last probe for one actor,
   scopes and runtime) is kept: the MCP session idle time."
  (* 60 60 1000))

(def default-max-views
  "Views kept per target when :max-views is not given: the fleet keeps at
   most this many times its number of targets, in total."
  1024)

(defn make
  "A fleet over `members`, a vector of [target-id client-context] in fleet
   order. opts: :mode (:fleet, or :single for exactly one member),
   :probe-ms, :clock (ms), :view-ttl-ms and :max-views (bounds of :last;
   :max-views is the total over all targets, default 1024 per target).

   :last is {view-key {:probe Probe :up Probe :touched ms}}, a view-key being
   [target-id actor scopes runtime]: :probe the latest look, :up the latest
   look that answered a manifest."
  [members {:keys [mode probe-ms clock view-ttl-ms max-views]}]
  (let [ids  (mapv first members)
        mode (or mode (if (= 1 (count ids)) :single :fleet))]
    {:mode        mode
     :view        (fleet/view mode ids)
     :order       ids
     :members     (into {} members)
     :probe-ms    (or probe-ms default-probe-ms)
     :clock       (or clock #(System/currentTimeMillis))
     :view-ttl-ms (or view-ttl-ms default-view-ttl-ms)
     :max-views   (or max-views (* default-max-views (max 1 (count ids))))
     :inflight    (atom {})
     :last        (atom {})}))

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

(defn- inflight-key
  "Key of a look at target `tid` by on-target session `ses`."
  [tid ses]
  [tid (:session/actor ses) (:session/scopes ses) (:session/runtime ses)])

(defn- probe-future
  "The running look at target `tid` for on-target session `ses`: one
   already in flight for the same actor, target, scopes and runtime, else a
   new future. A settled look leaves :inflight."
  [f tid ses]
  (let [k        (inflight-key tid ses)
        inflight (:inflight f)
        ctx      (member f tid)]
    (or (let [fut (get @inflight k)] (when (and fut (not (realized? fut))) fut))
        (let [self (promise)
              fut  (future
                     (try (client/manifest ctx ses)
                          (catch Exception e (answer/from-exception e))
                          (finally
                            (swap! inflight (fn [m] (if (identical? (get m k) @self) (dissoc m k) m))))))]
          (swap! inflight assoc k fut)
          (deliver self fut)
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

(defn- view-key
  "Key of `session`'s view of target `tid`: per actor, scopes and runtime,
   the same key its looks are shared under."
  [f session tid]
  (inflight-key tid (on f session tid)))

(defn prune-views
  "`views` without those untouched for `ttl-ms` before `now`, then at most
   `cap` of them, the most recently touched kept. Views touched in the same
   millisecond are ordered by their :seq, so the latest write always stays."
  [views now ttl-ms cap]
  (let [live (into {} (filter (fn [[_ v]] (< (- now (:touched v)) ttl-ms))) views)]
    (if (<= (count live) cap)
      live
      (into {} (take cap (sort-by (fn [[_ v]] [(- (:touched v)) (- (:seq v 0))]) live))))))

(def ^:private view-seq
  "A counter that orders view writes made in the same millisecond."
  (atom 0))

(defn- remember
  "`views` with probe `p` recorded under `k` at `now`. A look that did not
   answer a manifest keeps the last one that did."
  [views k p now]
  (let [old (get views k)]
    (assoc views k {:probe   p
                    :up      (if (= :up (:status p)) p (:up old))
                    :touched now
                    :seq     (swap! view-seq inc)})))

(defn- touch
  "`views` with the view under `k`, when there is one, touched at `now`."
  [views k now]
  (if (contains? views k)
    (update views k assoc :touched now :seq (swap! view-seq inc))
    views))

(defn- probe-targets
  "Probes of targets `tids`, in order, for `session`. All are asked at once;
   each answer is awaited until one shared deadline :probe-ms from now. Every
   probe is remembered under `session`'s view of its target, and views past
   their TTL or the cap are forgotten."
  [f session tids]
  (let [now      #(System/currentTimeMillis)
        start    (now)
        deadline (+ start (:probe-ms f))
        futs     (mapv (fn [tid] [tid (probe-future f tid (on f session tid))]) tids)
        probes   (mapv (fn [[tid fut]]
                         (classify tid (deref fut (max 0 (- deadline (now))) ::slow) start))
                       futs)
        t        ((:clock f))]
    (swap! (:last f)
           (fn [views]
             (prune-views (reduce (fn [vs p] (remember vs (view-key f session (:target p)) p t)) views probes)
                          t (:view-ttl-ms f) (:max-views f))))
    probes))

(defn probe
  "Probes of every target, in fleet order, for `session`. All targets are
   asked at once; each answer is awaited until one shared deadline
   :probe-ms from now, so one slow target costs at most that long and a
   dead one nothing."
  [f session]
  (probe-targets f session (:order f)))

(defn last-probes
  "[[target-id probe] ...]: the latest look of every view the fleet keeps."
  [f]
  (map (fn [[[tid] v]] [tid (:probe v)]) @(:last f)))

(defn last-probes-by-view
  "[[view-key probe] ...]: the latest look of every view the fleet keeps, a
   view-key being [target-id actor scopes runtime], for a reader that must
   tell one agent's look from another's."
  [f]
  (map (fn [[k v]] [k (:probe v)]) @(:last f)))

(defn surface
  "What `session` sees of the fleet now: {:app :tools :resources :apps
   :probes :fingerprint}."
  [f session]
  (let [probes (probe f session)]
    (assoc (fleet/surface (:view f) probes)
           :probes probes
           :fingerprint (fleet/fingerprint probes))))

(defn- listed-tools
  "Tool names target `tid` listed in the last manifest `session`'s view of
   it holds (touching that view), or nil when it holds none."
  [f session tid]
  (let [k (view-key f session tid)
        v (get (swap! (:last f) touch k ((:clock f))) k)]
    (some->> (get-in v [:up :manifest :tools]) (map :name))))

(defn- resolve-in
  "The tool of target `tid` that `exposed` names in `tools`, or nil."
  [f tid exposed tools]
  (let [pfx (fleet/prefix tid)
        [kept _] (fleet/split-taken {} pfx (map (fn [n] {:name n}) tools))]
    (fleet/resolve-local pfx exposed (map :name kept))))

(defn route-tool
  "Ok {:target :local} of exposed tool name `exposed` for `session`. In a
   fleet the local name is always resolved against the last manifest the
   target answered `session`'s view (actor, scopes, runtime); a slow or
   refused look does not erase it. With none, the target is probed first,
   and a target that still lists nothing answers its own probe error. A
   name no listed tool exposes, or several do, is refused :unknown-tool,
   never sent as it is."
  [f session exposed]
  (let [r (fleet/route-tool (:view f) exposed)]
    (if (and (answer/ok? r) (= :fleet (:mode f)))
      (let [tid    (get-in r [:ok :target])
            known  (listed-tools f session tid)
            looked (when-not known (first (probe-targets f session [tid])))
            tools  (or known (listed-tools f session tid))]
        (if (nil? tools)
          {:error (or (:error looked)
                      {:type :target/unlisted :message (str "app " tid " listed no tools")})}
          (if-let [local (resolve-in f tid exposed tools)]
            (answer/ok {:target tid :local local})
            (answer/refusal :unknown-tool (str "no tool named " exposed " in app " tid)
                            {:apps (vec (sort (keys (get-in f [:view :prefixes]))))}))))
      r)))

(defn call!
  "Answer of calling exposed tool `exposed` with `args` as `session`, from
   the owning target. A Pending id comes back qualified by the target."
  [f session exposed args]
  (let [r (route-tool f session exposed)]
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
               (remove #(= :down (get-in @(:last f) [(view-key f session %) :probe :status])) (:order f)))
        ctxs (map #(member f %) tids)]
    (if-not (some :ledger ctxs)
      (client/ledger-query (member f (first (:order f))) session q)
      (do (doseq [tid (rest tids)]
            (try (client/drain! (member f tid) (on f session tid))
                 (catch Exception _drain-failed nil)))
          (client/ledger-query (member f (first tids)) (on f session (first tids)) q)))))
