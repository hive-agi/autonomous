(ns autonomous.mcp.builtins
  "Session tools every autonomous MCP server offers, beside the app's own.
   Each is DATA: an id, an author spec (compiled by the same domain code as
   app tools, so scopes and malli validation apply unchanged) and a run fn
   (fn [ctx session args] {:answer a :session session'}).

   Extend by passing more entries to autonomous.mcp.server/server; the
   prefix autonomous_ is reserved."
  (:require [autonomous.bridge.client :as client]
            [autonomous.bridge.session :as s]
            [autonomous.domain.answer :as answer]))

(defn- same [session a] {:answer a :session session})

(def attach
  {:id :autonomous/attach
   :spec {:description (str "Pin this session to one running instance of the app (a browser tab or "
                            "process) by runtime_id from autonomous_runtimes. Omit runtime_id to unpin. "
                            "In a swarm, give each agent its own instance to work in parallel.")
          :read-only? true
          :input [:map [:runtime_id {:optional true} [:maybe [:or :int :string]]]]}
   :run (fn [ctx session {:keys [runtime_id]}]
          (if (nil? runtime_id)
            {:answer (answer/ok {:attached nil}) :session (s/attach session nil)}
            (let [rts (client/runtimes ctx)
                  ids (set (map :runtime/id (:ok rts)))
                  id  (or (some #(when (= (str %) (str runtime_id)) %) ids) ::none)]
              (cond
                (answer/refused? rts) (same session rts)
                (= ::none id) (same session (answer/refusal :unknown-runtime
                                                            (str "no runtime " runtime_id)
                                                            {:known (vec ids)}))
                :else {:answer (answer/ok {:attached id}) :session (s/attach session id)}))))})

(def runtimes
  {:id :autonomous/runtimes
   :spec {:description "List the running instances of the app this server reaches, and which one this session is attached to."
          :read-only? true}
   :run (fn [ctx session _]
          (let [a (client/runtimes ctx)]
            (same session (if (answer/ok? a)
                            (answer/ok {:runtimes (:ok a) :attached (:session/runtime session)})
                            a))))})

(def whoami
  {:id :autonomous/whoami
   :spec {:description "Who this session acts as: actor, granted scopes and attached runtime."
          :read-only? true}
   :run (fn [_ session _]
          (same session (answer/ok {:actor (:session/actor session)
                                    :scopes (vec (sort (map name (:session/scopes session))))
                                    :runtime (:session/runtime session)})))})

(def journal
  {:id :autonomous/journal
   :spec {:description (str "Recent events of the attached instance: tool calls by every agent (with "
                            "outcome), leases, and events the app records (what the human did). Pass "
                            "since = the last seq you saw to read only what is new.")
          :read-only? true
          :input [:map [:since {:optional true} [:maybe [:int {:min 0}]]]
                  [:limit {:optional true} [:maybe [:int {:min 1 :max 500}]]]]}
   :run (fn [ctx session {:keys [since limit]}]
          (same session (client/journal ctx session since limit)))})

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
   :run (fn [ctx session q]
          (same session (client/ledger-query ctx session (into {} (remove (comp nil? val)) q))))})

(def poll
  {:id :autonomous/poll
   :spec {:description "Collect the result of a call that answered 'still running'."
          :read-only? true
          :input [:map [:id :string]]}
   :run (fn [ctx session {:keys [id]}]
          (same session (client/poll ctx session id)))})

(def lease
  {:id :autonomous/lease
   :spec {:description (str "Take exclusive write access to the attached instance for ttl_seconds "
                            "(default 60, renewable). Other agents keep reading; their writes are "
                            "refused until you release or the lease ends.")
          :input [:map [:ttl_seconds {:optional true} [:int {:min 1 :max 3600}]]]}
   :run (fn [ctx session {:keys [ttl_seconds]}]
          (same session (client/lease! ctx session (* 1000 (or ttl_seconds 60)))))})

(def release
  {:id :autonomous/release
   :spec {:description "Release your write lease on the attached instance."}
   :run (fn [ctx session _]
          (same session (client/release! ctx session)))})

(def all
  [whoami runtimes attach journal ledger poll lease release])
