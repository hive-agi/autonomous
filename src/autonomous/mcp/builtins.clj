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
            [autonomous.bridge.fleet :as fleet]))

(defn- same [session a] {:answer a :session session})

(defn- with-target
  "Call `k` with the client context, the session as that target sees it,
   and the target id that built-in argument `target` addresses; a refusal
   when it addresses none."
  [f session target k]
  (let [t (fleet/target-of f target)]
    (if (answer/refused? t)
      (same session t)
      (let [tid (:ok t)]
        (k (fleet/member f tid) (fleet/on f session tid) tid)))))

(def attach
  {:id :autonomous/attach
   :spec {:description (str "Pin this session to one running instance of the app (a browser tab or "
                            "process) by runtime_id from autonomous_runtimes. Omit runtime_id to unpin. "
                            "In a swarm, give each agent its own instance to work in parallel.")
          :read-only? true
          :input [:map [:runtime_id {:optional true} [:maybe [:or :int :string]]]]}
   :run (fn [f session {:keys [runtime_id target]}]
          (with-target f session target
            (fn [ctx _ tid]
              (if (nil? runtime_id)
                {:answer (answer/ok {:attached nil}) :session (s/attach-on (:mode f) session tid nil)}
                (let [rts (client/runtimes ctx)
                      ids (set (map :runtime/id (:ok rts)))
                      id  (or (some #(when (= (str %) (str runtime_id)) %) ids) ::none)]
                  (cond
                    (answer/refused? rts) (same session rts)
                    (= ::none id) (same session (answer/refusal :unknown-runtime
                                                                (str "no runtime " runtime_id)
                                                                {:known (vec ids)}))
                    :else {:answer (answer/ok {:attached id})
                           :session (s/attach-on (:mode f) session tid id)}))))))})

(def runtimes
  {:id :autonomous/runtimes
   :spec {:description "List the running instances of the app this server reaches, and which one this session is attached to."
          :read-only? true}
   :run (fn [f session {:keys [target]}]
          (with-target f session target
            (fn [ctx ses _]
              (let [a (client/runtimes ctx)]
                (same session (if (answer/ok? a)
                                (answer/ok {:runtimes (:ok a) :attached (:session/runtime ses)})
                                a))))))})

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
          (with-target f session target
            (fn [ctx ses _] (same session (client/journal ctx ses since limit)))))})

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
   :run (fn [f session q]
          (let [q (into {} (remove (comp nil? val)) q)
                t (when (:target q) (fleet/target-of f (:target q)))]
            (same session (if (and t (answer/refused? t))
                            t
                            (fleet/ledger-query f session q)))))})

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
          (with-target f session target
            (fn [ctx ses _] (same session (client/lease! ctx ses (* 1000 (or ttl_seconds 60)))))))})

(def release
  {:id :autonomous/release
   :spec {:description "Release your write lease on the attached instance."}
   :run (fn [f session {:keys [target]}]
          (with-target f session target
            (fn [ctx ses _] (same session (client/release! ctx ses)))))})

(def apps
  {:id :autonomous/apps
   :spec {:description (str "The apps of this fleet: id, tool prefix, status (up, down, slow), tool "
                            "count and manifest version. A down or slow app's tools are not listed "
                            "until it answers again.")
          :read-only? true}
   :run (fn [f session _]
          (same session (answer/ok {:apps (:apps (fleet/surface f session))})))})

(def all
  "The built-ins of a single-target server."
  [whoami runtimes attach journal ledger poll lease release])

(def targeted
  "Built-ins that act on one app of a fleet, and so take a target."
  #{:autonomous/runtimes :autonomous/attach :autonomous/journal :autonomous/ledger
    :autonomous/lease :autonomous/release})

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
