(ns autonomous.mcp.builtins
  "Session tools every autonomous MCP server offers, beside the app's own.
   Each is DATA: an id, an author spec (compiled by the same domain code as
   app tools, so scopes and malli validation apply unchanged) and a run fn
   (fn [ctx session args] {:answer a :session session'}).

   Extend by passing more entries to autonomous.mcp.server/server; the
   prefix autonomous_ is reserved."
  (:require [autonomous.bridge.client :as client]
            [autonomous.bridge.session :as s]
            [autonomous.domain.answer :as answer]
            [autonomous.bridge.fleet :as fleet]
            [autonomous.bridge.trace :as trace]))

(defn- same [session a] {:answer a :session session})

(defn- with-target
  "Pipeline: give an Answer-producing step the selected member, its
   view of the session, and the target id."
  [f session target k]
  (answer/let-answer [tid (fleet/target-of f target)]
    (k (fleet/member f tid) (fleet/on f session tid) tid)))

(defn- matching-runtime
  "Promote: Ok of the id in `rows` spelled as `runtime-id`, its original
   type kept; else an :unknown-runtime refusal naming the known ids."
  [rows runtime-id]
  (let [ids (set (map :runtime/id rows))]
    (or (some #(when (and % (= (str %) (str runtime-id))) (answer/ok %)) ids)
        (answer/refusal :unknown-runtime (str "no runtime " runtime-id) {:known (vec ids)}))))

(defn- directory-rows
  "Promote: directory answer `a` as an Answer of rows: a refusal as is,
   anything else (Ok, nil, a Pending) as Ok of its :ok rows."
  [a]
  (if (answer/refused? a) a (answer/ok (:ok a))))

(defn- requested-pin
  "Boundary: resolve a requested id against the current directory; nil unpins."
  [ctx runtime-id]
  (if (nil? runtime-id)
    (answer/ok nil)
    (answer/bind (directory-rows (client/runtimes ctx)) #(matching-runtime % runtime-id))))

(defn- pinned
  "Promote: Ok of the {:answer :session} result of pinning `session` to
   runtime `id` of target `tid` under fleet `mode`."
  [mode session [tid id]]
  (answer/ok (same (s/attach-on mode session tid id) (answer/ok {:attached id}))))

(defn- pin-result
  "Promote: the {:answer :session} result of target/runtime `choice`: the
   pinned session on Ok, else `session` unchanged answering `choice`."
  [mode session choice]
  (get (answer/bind choice #(pinned mode session %)) :ok (same session choice)))

(defn- attach-run
  "Pipeline: resolve the pin before applying it to the session."
  [f session {:keys [runtime_id target]}]
  (pin-result (:mode f) session
              (with-target f session target
                (fn [ctx _ tid]
                  (answer/bind (requested-pin ctx runtime_id) #(answer/ok [tid %]))))))

(def attach
  {:id :autonomous/attach
   :spec {:description (str "Pin this session to one running instance of the app (a browser tab or "
                            "process) by runtime_id from autonomous_runtimes. Omit runtime_id to unpin. "
                            "In a swarm, give each agent its own instance to work in parallel.")
          :read-only? true
          :input [:map [:runtime_id {:optional true} [:maybe [:or :int :string]]]]}
   :run attach-run})

(defn- runtimes-run
  "Boundary: list the selected member's runtimes and its current pin."
  [f session {:keys [target]}]
  (same session
        (with-target f session target
          (fn [ctx ses _]
            (answer/let-answer [rows (client/runtimes ctx)]
              (answer/ok {:runtimes rows :attached (:session/runtime ses)}))))))

(def runtimes
  {:id :autonomous/runtimes
   :spec {:description "List the running instances of the app this server reaches, and which one this session is attached to."
          :read-only? true}
   :run runtimes-run})

(def whoami
  {:id :autonomous/whoami
   :spec {:description "Who this session acts as: actor, granted scopes and attached runtime."
          :read-only? true}
   :run (fn [f session _]
          (same session (cond-> {:actor (:session/actor session)
                                 :scopes (vec (sort (map name (:session/scopes session))))}
                          (= :single (:mode f)) (assoc :runtime (:session/runtime session))
                          (= :fleet (:mode f))  (assoc :runtimes (or (:session/runtimes session) {}))
                          true answer/ok)))})

(def journal
  {:id :autonomous/journal
   :spec {:description (str "Recent events of the attached instance: tool calls by every agent (with "
                            "outcome), leases, and events the app records (what the human did). Pass "
                            "since = the last seq you saw to read only what is new.")
          :read-only? true
          :input [:map [:since {:optional true} [:maybe [:int {:min 0}]]]
                  [:limit {:optional true} [:maybe [:int {:min 1 :max 500}]]]]}
   :run (fn [f session {:keys [since limit target]}]
          (same session
                (with-target f session target
                  (fn [ctx ses _] (client/journal ctx ses since limit)))))})

(defn- checked-ledger-query
  "Promote: drop absent filters and validate a named target without defaulting it."
  [f args]
  (let [q (into {} (remove (comp nil? val)) args)]
    (if-let [target (:target q)]
      (answer/bind (fleet/target-of f target) (fn [_] (answer/ok q)))
      (answer/ok q))))

(defn- ledger-run
  "Boundary: query the shared ledger only after validating its filters."
  [f session args]
  (same session (answer/let-answer [q (checked-ledger-query f args)]
                  (fleet/ledger-query f session q))))

(def ledger
  {:id :autonomous/ledger
   :spec {:description (str "Query the durable record of what happened across all instances, reloads and "
                            "agents. Filter by actor, kind (e.g. autonomous/call), tool, and a time window "
                            "in epoch milliseconds. Newest first.")
          :read-only? true
          :input [:map
                  [:actor {:optional true} :string]
                  [:kind {:optional true} :keyword]
                  [:tool {:optional true} :string]
                  [:since {:optional true} :int]
                  [:until {:optional true} :int]
                  [:limit {:optional true} [:int {:min 1 :max 1000}]]]}
   :run ledger-run})

(def poll
  {:id :autonomous/poll
   :spec {:description "Collect the result of a call that answered 'still running'."
          :read-only? true
          :input [:map [:id :string]]}
   :run (fn [f session {:keys [id]}]
          (same session (fleet/poll f session id)))})

(def lease
  {:id :autonomous/lease
   :spec {:description (str "Take exclusive write access to the attached instance for ttl_seconds "
                            "(default 60, renewable). Other agents keep reading; their writes are "
                            "refused until you release or the lease ends.")
          :input [:map [:ttl_seconds {:optional true} [:int {:min 1 :max 3600}]]]}
   :run (fn [f session {:keys [ttl_seconds target]}]
          (same session
                (with-target f session target
                  (fn [ctx ses _] (client/lease! ctx ses (* 1000 (or ttl_seconds 60)))))))})

(def release
  {:id :autonomous/release
   :spec {:description "Release your write lease on the attached instance."}
   :run (fn [f session {:keys [target]}]
          (same session
                (with-target f session target
                  (fn [ctx ses _] (client/release! ctx ses)))))})

(def apps
  {:id :autonomous/apps
   :spec {:description (str "The apps of this fleet: id, tool prefix, status (up, down, slow), tool "
                            "count and manifest version. A down or slow app's tools are not listed "
                            "until it answers again.")
          :read-only? true}
   :run (fn [f session _]
          (same session (answer/ok {:apps (:apps (fleet/surface f session))})))})

;; =============================================================================
;; Telemetry
;; =============================================================================

(defn collector-of
  "The span collector of fleet `f`, made on first use when the server was
   built without one (no exporters then)."
  [f]
  (or @(:trace f)
      (swap! (:trace f) #(or % (trace/collector)))))

(def trace
  {:id :autonomous/trace
   :spec {:description
          (str "Telemetry of the run, as OpenTelemetry spans collected from every instance (and, in a "
               "fleet, every app): clicks, re-frame events with what they changed and dispatched, HTTP "
               "requests and the server spans that answered them, agent tool calls, uncaught errors. "
               "view = traces (one summary per root cause, default), trace (one trace as an outline "
               "and a tree; pass trace_id, a prefix is enough), timeline (every span through time), "
               "findings (errors, failed requests, slow spans, errors with no cause, each with its "
               "chain of causes) or metrics (count, errors, p50/p95 duration per span). Spans are "
               "recorded only once the app enables tracing (autonomous_trace_config or its own code).")
          :read-only? true
          :input [:map
                  [:view {:optional true} [:maybe [:enum "traces" "trace" "timeline" "findings" "metrics"]]]
                  [:trace_id {:optional true} [:maybe :string]]
                  [:target {:optional true} [:maybe :string]]
                  [:since {:optional true} [:maybe :int]]
                  [:limit {:optional true} [:maybe [:int {:min 1 :max 5000}]]]
                  [:slow_ms {:optional true} [:maybe [:int {:min 1}]]]]}
   :run (fn [f session {:keys [view trace_id target since limit slow_ms]}]
          (let [coll (collector-of f)
                r    (trace/collect! coll f)
                a    (trace/view (:spans @(:state coll))
                                 {:view view :trace-id trace_id :target target :since since
                                  :limit limit :slow-ms slow_ms})]
            (same session (if (answer/ok? a)
                            (answer/ok (cond-> (:ok a) (seq (:errors r)) (assoc :collect-errors (:errors r))))
                            a))))})

(def trace-mark
  {:id :autonomous/trace_mark
   :spec {:description (str "Mark a boundary in the telemetry (\"test: checkout happy path\"), so "
                            "what follows reads as one story in autonomous_trace and in exported "
                            "traces. Answers the mark's trace id.")
          :read-only? true
          :input [:map [:label [:string {:min 1 :max 200}]]]}
   :run (fn [f session {:keys [label]}]
          (let [coll (collector-of f)]
            (trace/collect! coll f)
            (same session (answer/ok {:trace-id (trace/mark! coll label (:session/actor session) nil)}))))})

(def trace-export
  {:id :autonomous/trace_export
   :spec {:description (str "Send the collected spans (all, or one trace_id) to the exporters this "
                            "bridge was configured with: an OpenTelemetry collector over OTLP/HTTP, "
                            "or files (OTLP JSON, JSON Lines, EDN). Omit exporter for all of them. "
                            "Answers what each delivered and the collector's status.")
          :scopes [:read]
          :input [:map
                  [:exporter {:optional true} [:maybe :string]]
                  [:trace_id {:optional true} [:maybe :string]]]}
   :run (fn [f session {:keys [exporter trace_id]}]
          (let [coll (collector-of f)]
            (trace/collect! coll f)
            (same session
                  (if (and (empty? (:exporters coll)) (nil? exporter))
                    (answer/refusal :export/none
                                    (str "this bridge has no span exporter; start it with --trace-otlp URL "
                                         "or --trace-dir DIR (or :trace {:exporters [...]} in autonomous.edn)"))
                    (answer/ok {:delivered (update-vals (trace/export-now! coll exporter trace_id)
                                                        #(if (answer/ok? %) (:ok %) (:error %)))
                                :status (trace/status coll)})))))})

(def trace-config
  {:id :autonomous/trace_config
   :spec {:description (str "Turn span recording on or off in the attached instance, keep payloads "
                            "(event arguments, request and response bodies: they may hold what a "
                            "person typed) or not, and size its span ring. Admin only.")
          :scopes [:admin]
          :input [:map
                  [:enabled {:optional true} [:maybe :boolean]]
                  [:payloads {:optional true} [:maybe :boolean]]
                  [:cap {:optional true} [:maybe [:int {:min 1 :max 100000}]]]]}
   :run (fn [f session {:keys [enabled payloads cap target]}]
          (with-target f session target
            (fn [ctx ses _]
              (same session (client/send! ctx ses (cond-> {:op :trace-config}
                                                    (some? enabled)  (assoc :enabled? enabled)
                                                    (some? payloads) (assoc :payloads? payloads)
                                                    cap              (assoc :cap cap)))))))})

(def all
  "The built-ins of a single-target server."
  [whoami runtimes attach journal ledger poll lease release trace trace-mark trace-export trace-config])

(def targeted
  "Built-ins that act on one app of a fleet, and so take a target."
  #{:autonomous/runtimes :autonomous/attach :autonomous/journal :autonomous/ledger
    :autonomous/lease :autonomous/release :autonomous/trace_config})

(defn with-target-arg
  "Built-in `b` with an optional string `target` argument (the app id) and
   a description that says so."
  [b]
  (-> b
      (update-in [:spec :input] #(conj (or % [:map]) [:target {:optional true} [:maybe :string]]))
      (update-in [:spec :description] str
                 " Fleet: pass target = the app id (autonomous_apps lists them); "
                 "required when the fleet has several apps"
                 (when (= :autonomous/ledger (:id b)) ", except for the ledger, which spans every app")
                 ".")))

(defmulti for-mode
  "The built-ins a server in fleet `mode` offers. Open: a new mode adds a
   method."
  identity)

(defmethod for-mode :single [_] all)

(defmethod for-mode :fleet [_]
  (into [apps] (map #(if (targeted (:id %)) (with-target-arg %) %)) all))
