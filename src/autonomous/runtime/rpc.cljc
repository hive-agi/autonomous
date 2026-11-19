(ns autonomous.runtime.rpc
  "Boundary stratum of the runtime: the one entry every bridge speaks.

   A Request (autonomous.domain.schema/Request) comes in as data or EDN text,
   is validated, dispatched on :op through `handle` (open: an adapter may add
   an op) and answered with an Answer. `rpc` never throws."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.decision :as decision]
            [autonomous.domain.journal :as journal]
            [autonomous.domain.manifest :as manifest]
            [autonomous.domain.schema :as s]
            [autonomous.domain.scope :as scope]
            [autonomous.domain.wire :as wire]
            [autonomous.runtime.invoke :as invoke]
            [autonomous.runtime.registry :as registry]
            [malli.core :as m]
            [malli.error :as me]))

(defmulti handle
  "Answer request `req` (already validated) for `grant`. Dispatches on :op."
  (fn [req _grant] (:op req)))

(defn- requires [grant required f]
  (if (scope/allowed? required (:scopes grant))
    (f)
    (answer/refusal :forbidden (str "requires scopes " (scope/names required)))))

(defmethod handle :ping [_ _]
  (answer/ok {:pong true
              :protocol manifest/protocol-version
              :version (:version (registry/snapshot))}))

(defmethod handle :manifest [_ grant]
  (answer/ok (manifest/project (registry/snapshot) grant)))

(defmethod handle :call [req grant]
  (invoke/call! (:tool req) (or (:args req) {}) grant))

(defmethod handle :read [req grant]
  (invoke/read! (:uri req) grant))

(defmethod handle :poll [req _]
  (registry/take-settled! (:id req)))

(defmethod handle :journal [req grant]
  (requires grant #{:read}
            #(let [snap (registry/snapshot)]
               (answer/ok (assoc (journal/since (:journal snap) (:since req) (:limit req))
                                 :epoch (:epoch snap))))))

(defmethod handle :lease [req grant]
  (requires grant #{:write}
            #(let [a (registry/acquire-lease! (:actor grant) (:ttl-ms req))]
               (registry/record! {:kind :autonomous/lease :actor (:actor grant)
                                  :outcome (answer/outcome a)})
               a)))

(defmethod handle :release [_ grant]
  (registry/record! {:kind :autonomous/release :actor (:actor grant)})
  (registry/release-lease! (:actor grant)))

(defmethod handle :default [req _]
  (answer/refusal :unknown-op (str "unknown op " (pr-str (:op req)))))

(def ^:private request-explainer (m/explainer s/Request))

(defn rpc
  "Answer one Request map. Never throws."
  [req]
  (try
    (if-let [e (and (contains? (methods handle) (:op req)) (request-explainer req))]
      (answer/refusal :bad-request "request does not match its op's schema" (me/humanize e))
      (handle req (decision/grant-of req)))
    (catch #?(:clj Throwable :cljs :default) e
      (answer/from-exception e))))

(defn ^:export rpc-str
  "`rpc` over text: read an EDN Request, answer printed EDN. A bridge
   evaluates this through a REPL, so neither side depends on how that REPL
   prints values."
  [s]
  (wire/print-edn
   (try (rpc (wire/read-edn s))
        (catch #?(:clj Throwable :cljs :default) e
          (answer/refusal :bad-request (or (ex-message e) (str e)))))))
