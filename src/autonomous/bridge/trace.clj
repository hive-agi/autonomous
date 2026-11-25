(ns autonomous.bridge.trace
  "Pipeline stratum of telemetry on the bridge: the span collector.

   Every runtime keeps its spans in a bounded ring (autonomous.runtime.trace).
   The collector reads each runtime of each target from where it last
   stopped (per epoch, so a reloaded page starts over), stamps every span
   with its :source {:target :runtime}, keeps them in one bounded store that
   survives page reloads, and hands each new batch to the configured
   exporters (autonomous.bridge.trace.export). A trace that crossed apps (a
   click in the UI, the request it sent, the server that answered) joins
   here by :trace-id.

   Collect: read runtimes over the client ports. Promote: dedupe and bound,
   pure. Boundary: the store atom and the exporters. `view` is pure over a
   snapshot of the store."
  (:require [autonomous.bridge.client :as client]
            [autonomous.bridge.session :as s]
            [autonomous.bridge.trace.export :as export]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.trace :as dt]
            [autonomous.runtime.trace :as rt]
            [clojure.string :as str]))

(def default-cap
  "Spans the bridge keeps across every runtime."
  100000)

(def page 1000)

(defn collector
  "A collector. opts: :cap (default `default-cap`), :exporters (a vector of
   exporter configs, opened now; one that cannot start is reported by
   `status` and skipped)."
  ([] (collector {}))
  ([{:keys [cap exporters]}]
   (let [opened (mapv (fn [c] [(export/exporter-name c) (export/open c)]) exporters)]
     {:cap       (or cap default-cap)
      :exporters (into [] (keep (fn [[n a]] (when (answer/ok? a) [n (:ok a)]))) opened)
      :failed    (into {} (keep (fn [[n a]] (when (answer/refused? a) [n a]))) opened)
      :state     (atom {:cursors {} :spans [] :seen #{} :exports {}})})))

;; =============================================================================
;; Promote (pure)
;; =============================================================================

(defn stamp
  "`spans` as collected from `runtime` of `target`."
  [target runtime spans]
  (mapv #(assoc % :source {:target (str target) :runtime (str runtime)}) spans))

(defn admit
  "Store `st` with the spans of `incoming` it has not seen appended, the
   oldest dropped past `cap`. Answers [st' admitted]."
  [st incoming cap]
  (let [fresh (into [] (comp (remove #((:seen st) (dt/span-key %))) ) (dt/dedupe-spans incoming))
        all   (into (:spans st) fresh)
        kept  (if (> (count all) cap) (subvec all (- (count all) cap)) all)
        seen  (if (identical? kept all)
                (into (:seen st) (map dt/span-key) fresh)
                (into #{} (map dt/span-key) kept))]
    [(assoc st :spans kept :seen seen) fresh]))

;; =============================================================================
;; Collect
;; =============================================================================

(defn- read-runtime
  "Spans of runtime `rid` of client context `ctx` after `cursor`
   {:epoch :seq}. Answers Ok {:spans :cursor} or the first refusal."
  [ctx rid cursor]
  (let [reader (s/attach (s/open-session "bridge" client/bridge-grant) rid)]
    (loop [since (:seq cursor 0) expect (:epoch cursor) acc [] rounds 0]
      (let [a (client/send! ctx reader {:op :trace :since since :limit page})
            {:keys [entries epoch]} (:ok a)]
        (cond
          (answer/refused? a) a
          (and expect (not= expect epoch) (pos? since)) (recur 0 epoch [] rounds)
          :else
          (let [acc  (into acc entries)
                last (if (seq entries) (:seq (peek entries)) since)]
            (if (and (= page (count entries)) (< rounds 50))
              (recur last epoch acc (inc rounds))
              (answer/ok {:spans acc :cursor {:epoch epoch :seq last}}))))))))

(defn collect-target!
  "Collect every runtime of target `tid` (client context `ctx`) into
   `coll`. Answers {:collected n :errors [...]}; never throws."
  [coll tid ctx]
  (let [rts (try (client/runtimes ctx) (catch Exception e (answer/from-exception e)))]
    (if (answer/refused? rts)
      {:collected 0 :errors [{:target tid :error (:error rts)}]}
      (reduce
       (fn [acc rid]
         (let [k      [tid (str rid)]
               cursor (get-in @(:state coll) [:cursors k])
               a      (try (read-runtime ctx rid cursor) (catch Exception e (answer/from-exception e)))]
           (if (answer/refused? a)
             (update acc :errors conj {:target tid :runtime (str rid) :error (:error a)})
             (let [{:keys [spans cursor]} (:ok a)
                   fresh (volatile! [])]
               (swap! (:state coll)
                      (fn [st] (let [[st' f] (admit st (stamp tid rid spans) (:cap coll))]
                                 (vreset! fresh f)
                                 (assoc-in st' [:cursors k] cursor))))
               (when (seq @fresh)
                 (let [results (export/export-all (:exporters coll) @fresh)]
                   (swap! (:state coll) update :exports merge results)))
               (update acc :collected + (count @fresh))))))
       {:collected 0 :errors []}
       (map :runtime/id (:ok rts))))))

(defn collect!
  "Collect every target of fleet `f` that is not known to be down.
   Answers {:collected n :errors [...]}."
  [coll f]
  (let [tids (remove #(= :down (get-in @(:last f) [% :status])) (:order f))]
    (reduce (fn [acc tid]
              (let [r (collect-target! coll tid (get-in f [:members tid]))]
                (-> acc (update :collected + (:collected r)) (update :errors into (:errors r)))))
            {:collected 0 :errors []}
            tids)))

(defn mark!
  "Record a bridge-side mark (a root :test/mark span, source \"bridge\")
   between steps of a run, and export it. Answers its trace id."
  [coll label actor attrs]
  (let [s (-> (rt/start label :test/mark {:parent nil :actor actor :attrs attrs})
              (as-> s (assoc s :end (:start s) :status :ok :source {:target "bridge" :runtime "bridge"})))]
    (swap! (:state coll) #(first (admit % [s] (:cap coll))))
    (swap! (:state coll) update :exports merge (export/export-all (:exporters coll) [s]))
    (:trace-id s)))

(defn export-now!
  "Hand every collected span (or those of `trace-id`) to the exporter
   named `nm`, or to all when `nm` is nil. Answers {name answer}."
  [coll nm trace-id]
  (let [spans (cond->> (:spans @(:state coll)) trace-id (filter #(= trace-id (:trace-id %))))
        named (if nm (filter #(= nm (first %)) (:exporters coll)) (:exporters coll))]
    (if (and nm (empty? named))
      {nm (answer/refusal :export/unknown (str "no exporter named " nm)
                          {:known (mapv first (:exporters coll))})}
      (export/export-all named (vec spans)))))

(defn status
  "What the collector holds and how its exporters fared."
  [coll]
  (let [st @(:state coll)]
    {:spans     (count (:spans st))
     :cap       (:cap coll)
     :runtimes  (vec (sort (map #(str/join "/" %) (keys (:cursors st)))))
     :exporters (mapv first (:exporters coll))
     :exports   (update-vals (:exports st) #(if (answer/ok? %) {:ok (:ok %)} (select-keys % [:error])))
     :failed    (update-vals (:failed coll) :error)}))

(defn start-poller!
  "Collect fleet `f` every `poll-ms` in a background future, so spans
   reach the exporters while a run is still going and survive a page that
   reloads between agent calls. Answers a stop function."
  [coll f poll-ms]
  (let [run? (atom true)
        fut  (future
               (while @run?
                 (try (collect! coll f)
                      (catch Exception e
                        (binding [*out* *err*] (println "[autonomous] span collection failed:" (ex-message e)))))
                 (Thread/sleep (long poll-ms))))]
    (fn [] (reset! run? false) (future-cancel fut) nil)))

;; =============================================================================
;; Views (pure)
;; =============================================================================

(defn- find-trace [spans id]
  (let [ids (distinct (map :trace-id spans))]
    (or (some #{id} ids) (first (filter #(str/starts-with? % id) ids)))))

(defn view
  "What an agent reads of collected `spans` for query `q`:
     :view      traces (default) | trace | timeline | findings | metrics
     :trace-id  the trace (an id prefix is enough) for view trace
     :target    only spans from this app
     :since     only spans that started at or after (epoch ms)
     :limit     rows (default 50 traces, 300 timeline rows)
     :slow-ms   the slow threshold of findings
   Answers an Answer."
  [spans {:keys [view trace-id target since limit slow-ms]}]
  (let [spans (cond->> spans
                target (filter #(= target (get-in % [:source :target])))
                since  (filter #(>= (:start %) since)))
        spans (vec spans)
        lim   (fn [n xs] (vec (take-last (or limit n) xs)))]
    (case (or view "traces")
      "traces"   (answer/ok {:traces (lim 50 (dt/summaries spans)) :spans (count spans)})
      "timeline" (answer/ok {:rows (lim 300 (dt/timeline spans))})
      "findings" (answer/ok {:findings (lim 100 (dt/findings spans {:slow-ms slow-ms}))})
      "metrics"  (answer/ok {:metrics (dt/metrics spans)})
      "trace"    (if-let [id (and trace-id (find-trace spans trace-id))]
                   (let [ss (filter #(= id (:trace-id %)) spans)]
                     (answer/ok {:summary (dt/summary id ss)
                                 :outline (dt/outline ss)
                                 :tree    (dt/tree ss)}))
                   (answer/refusal :trace/unknown (str "no collected trace " (pr-str trace-id))))
      (answer/refusal :trace/bad-view (str "unknown view " (pr-str view))))))
