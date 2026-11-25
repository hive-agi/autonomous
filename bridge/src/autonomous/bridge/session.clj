(ns autonomous.bridge.session
  "Sessions: one per agent conversation. A session carries the grant the
   agent authenticated with and the runtime it is pinned to, so a swarm can
   spread over many tabs or processes of one app, or share one. Pure."
  (:require [autonomous.domain.scope :as scope]))

(defn open-session
  "A new Session `id` for `grant` ({:actor :scopes}), not pinned."
  [id grant]
  {:session/id      id
   :session/actor   (:actor grant)
   :session/scopes  (scope/normalize (:scopes grant))
   :session/runtime nil})

(defn attach
  "`session` pinned to `runtime-id` (nil unpins: the target's default)."
  [session runtime-id]
  (assoc session :session/runtime runtime-id))

(defn- mode-of [mode & _] mode)

(defmulti attach-on
  "`session` pinned to `runtime-id` of target `target-id` under fleet
   `mode`. :single keeps the one pin it always had; :fleet keeps a pin per
   target, so attaching on one app leaves the others alone."
  mode-of)

(defmethod attach-on :single [_ session _target-id runtime-id]
  (attach session runtime-id))

(defmethod attach-on :fleet [_ session target-id runtime-id]
  (assoc-in session [:session/runtimes target-id] runtime-id))

(defmulti on-target
  "The session the bridge speaks to target `target-id` with: `session`
   pinned to that target's runtime under fleet `mode`."
  mode-of)

(defmethod on-target :single [_ session _target-id] session)

(defmethod on-target :fleet [_ session target-id]
  (attach session (get-in session [:session/runtimes target-id])))

(defn grant
  "The grant `session` speaks with."
  [session]
  {:actor (:session/actor session) :scopes (:session/scopes session)})

(defn ->request
  "Request `op-map` stamped with `session`'s identity. The stamp wins over
   anything the op-map says: an agent never speaks as someone else."
  [session op-map]
  (assoc op-map
         :actor   (:session/actor session)
         :scopes  (:session/scopes session)
         :session (:session/id session)))

(defn owned-by?
  "True when `session` belongs to `grant`'s actor: a session id presented
   with another actor's token is not that actor's session."
  [session grant]
  (= (:session/actor session) (:actor grant)))
