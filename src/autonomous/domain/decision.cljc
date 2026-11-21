(ns autonomous.domain.decision
  "Promote stratum: every decision about a request, made on a registry
   SNAPSHOT with time passed in. No state is read and nothing is run here;
   the runtime pipeline carries out the Plan this namespace returns.

   A Plan is {:plan/action :invoke :tool t :args decoded-args} or
   {:plan/action :refuse :refusal r}."
  (:require [autonomous.domain.affordance :as affordance]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.lease :as lease]
            [autonomous.domain.naming :as naming]
            [autonomous.domain.scope :as scope]))

(defn refuse [refusal] {:plan/action :refuse :refusal refusal})

(defn- forbidden [what required granted]
  (answer/refusal :forbidden
                  (str what " requires scopes " (scope/names required))
                  {:granted (scope/names granted)}))

(defn plan-call
  "Plan a call of tool `tool-name` with `args` by `grant` at `now`.
   Checks, in order: the tool exists, the grant's scopes cover it, no other
   actor's live lease blocks it, the args conform to its input schema."
  [snapshot tool-name args grant now]
  (let [tool (get-in snapshot [:tools tool-name])
        held (:lease snapshot)]
    (cond
      (nil? tool)
      (refuse (answer/refusal :unknown-tool (str "no tool named " tool-name)
                              {:known (vec (sort (keys (:tools snapshot))))}))

      (not (scope/allowed? (:scopes tool) (:scopes grant)))
      (refuse (forbidden tool-name (:scopes tool) (:scopes grant)))

      (lease/blocks? held tool grant now)
      (refuse (answer/refusal :leased
                              (str "runtime is leased by " (:actor held)
                                   "; writes by other actors are refused until the lease ends")
                              held))

      :else
      (let [conformed (affordance/conform-args tool args)]
        (if (answer/refused? conformed)
          (refuse conformed)
          {:plan/action :invoke :tool tool :args (:ok conformed)})))))

(defn plan-read
  "Plan a read of resource `uri` by `grant`: {:plan/action :read :resource r}
   or a refusal plan."
  [snapshot uri grant]
  (let [r (get-in snapshot [:resources uri])]
    (cond
      (nil? r)
      (refuse (answer/refusal :unknown-resource (str "no resource " uri)
                              {:known (vec (sort (keys (:resources snapshot))))}))

      (not (scope/allowed? (:scopes r) (:scopes grant)))
      (refuse (forbidden uri (:scopes r) (:scopes grant)))

      :else {:plan/action :read :resource r})))

(defn grant-of
  "The Grant a request carries. No scopes means least privilege: #{:read}."
  [req]
  {:actor   (or (:actor req) "anonymous")
   :scopes  (if (contains? req :scopes) (scope/normalize (:scopes req)) #{:read})
   :session (:session req)})

(defn host-grant
  "The grant a native host's dispatcher is shown for `grant`: {:actor
   :scopes}, the shape autonomous.js hands its handlers. The actor is shown
   as naming/shown-actor shows it, and the scopes are sorted names.

   nil when the actor names no one (naming/nameless-actor?: nil, empty,
   whitespace-only or invisible-only), so a host that attributes changes is
   never handed an empty or blank grant."
  [grant]
  (when-not (naming/nameless-actor? (:actor grant))
    {:actor (naming/shown-actor (:actor grant)) :scopes (scope/names (:scopes grant))}))
