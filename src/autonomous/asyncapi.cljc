(ns autonomous.asyncapi
  "The :asyncapi Source: READ-ONLY resources that show what was PUBLISHED on
   a subject, derived from an AsyncAPI document. No change to the service.

     (def b (:ok (autonomous.openapi.load/load-bundle \"libs/asyncapi/nats-events.yaml\")))
     (def s (:ok (autonomous.asyncapi/surface b
                   {:port  (:ok (autonomous.asyncapi.port/connect :asyncapi.memory/bus
                                  {:bus (autonomous.asyncapi.memory/bus)}))
                    :expose {\"user.events.created\" {:scope :events/read}
                             \"sisf.payment.*.entry\"  {:scope :payments/read :capacity 20}}})))

   Each exposed subject is a resource at `asyncapi://<subject>` answering
   {:messages [...] :cursor n :dropped k}: the messages published since the
   READER's last read, oldest first, and how many fell out of the ring before
   it got there. Nothing is written back to the bus, so every resource is
   read-only by construction.

   Opt-in and strict: a filter NATS cannot use, an empty :expose, or a
   literal subject the document does not declare is refused when the surface
   is BUILT, never at read time. A wildcard the document has not caught up on
   is allowed, since watching `sisf.payment.*.entry` before the spec names it
   is the point.

   Payloads are validated against the channel's message schema; a
   non-conforming one is kept and flagged :conforms? false with :problems."
  (:require [autonomous.asyncapi.derive :as derive]
            [autonomous.asyncapi.port :as port]
            [autonomous.asyncapi.read :as read]
            [autonomous.asyncapi.source :as source]
            [autonomous.core :as core]
            [autonomous.domain.answer :as answer]))

(def kind derive/kind)

(defn- app-info [opts]
  (or (:app opts)
      {:name (or (some-> (:prefix opts) name) "asyncapi")
       :description "Read-only taps on published events"}))

(defn- resource-spec
  "ResourceSpec of one derived tap over live tap atom `ta` and store `store`."
  [desc ta store]
  (cond-> {:read        (fn [grant] (read/read-tap ta store desc grant))
           :name        (:uri desc)
           :description (:description desc)
           :mime        "application/json"}
    (:scopes desc) (assoc :scopes (:scopes desc))))

(defn- live-surface
  "Running surface of `specs` subscribed on `port`, or a refusal."
  [port specs]
  (let [live (source/subscribe! port
                                {:resources (into {} (map (juxt :uri identity)) specs)})]
    (if (answer/refused? live)
      live
      (let [l  (:ok live)
            rs (into {}
                     (map (fn [desc]
                            (let [{:keys [tap store]} (get-in l [:taps (:uri desc)])]
                              [(:uri desc) (resource-spec desc tap store)])))
                     specs)]
        (answer/ok {:app (app-info {}) :tools {} :resources rs :live l})))))

(defn surface
  "Ok Surface {:app :tools {} :resources {...} :live ...} of the exposed
   subjects, subscribed on opts :port, or a refusal (and then nothing is
   subscribed). opts: :port [required], :expose, :app, :prefix.

   :live is the running surface to hand to `stop!` when the surface is
   removed; it is not part of the surface contract."
  [bundle-or-doc opts]
  (let [p     (port/check (:port opts))
        descs (derive/taps bundle-or-doc opts)]
    (cond
      (answer/refused? p)     p
      (answer/refused? descs) descs
      :else
      (let [live (live-surface (:port opts) (:ok descs))]
        (if (answer/refused? live)
          live
          (update-in live [:ok :app] merge (app-info opts)))))))

(defn install!
  "Register every derived resource in the current registry. Answers Ok
   {:registered [uri ...] :live live-surface}, or the refusal, and then
   nothing is registered. Hand :live to `stop!` when the surface goes."
  [bundle-or-doc opts]
  (let [s (surface bundle-or-doc opts)]
    (if (answer/refused? s)
      s
      (let [uris (mapv (fn [[uri spec]] (core/reg-resource uri spec))
                       (:resources (:ok s)))]
        (answer/ok {:registered uris :live (:live (:ok s))})))))

(defn stop!
  "Stop running surface `live` (from surface or install!): unsubscribe and
   close the port."
  [live]
  (source/stop! live))
