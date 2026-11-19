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
