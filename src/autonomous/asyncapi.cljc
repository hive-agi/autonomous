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
   non-conforming one is kept and flagged :conforms? false with :problems.

   Every step answers an Answer and chains on autonomous.asyncapi.rail, so a
   refusal short-circuits and nothing here hand-checks refused?."
  (:require [autonomous.asyncapi.derive :as derive]
            [autonomous.asyncapi.port :as port]
            [autonomous.asyncapi.rail :as rail]
            [autonomous.asyncapi.read :as read]
            [autonomous.asyncapi.source :as source]
            [autonomous.domain.answer :as answer]))

(def kind derive/kind)

(defn- app-info [opts]
  (or (:app opts)
      {:name (or (some-> (:prefix opts) name) "asyncapi")
       :description "Read-only taps on published events"}))

(defn- subscribed
  "Ok running surface of the derived `specs`, on the port opts names."
  [opts specs]
  (source/start! (:port opts) (into {} (map (juxt :uri identity)) specs)))

(defn- with-app
  "Ok Surface of running surface `live`, named by opts, read by `read-tap`."
  [live opts read-tap]
  (answer/ok {:app       (app-info opts)
              :tools     {}
              :resources (source/resource-specs live read-tap)
              :live      live}))

(defn surface
  "Ok Surface {:app :tools {} :resources {...} :live ...} of the exposed
   subjects, subscribed on opts :port, or a refusal (and then nothing is
   subscribed). opts: :port [required], :expose, :app, :prefix.

   :live is the running surface to hand to `stop!` when the surface is
   removed; it is not part of the surface contract."
  [bundle-or-doc opts]
  (rail/bind (port/check (:port opts))
             (fn [p]
               (rail/bind (derive/taps bundle-or-doc opts)
                          (fn [specs]
                            (rail/bind (subscribed opts specs)
                                       #(with-app % opts read/read-tap)))))))

(defn- register!
  "Register every resource of surface `s`; Ok of what was registered, or the
   first refusal."
  [s]
  (let [live (:live s)]
    (rail/bind (source/install! live read/read-tap)
               (fn [l] (answer/ok {:registered (vec (keys (:taps l))) :live l})))))

(defn install!
  "Register every derived resource in the current registry. Answers Ok
   {:registered [uri ...] :live live-surface}, or the refusal, and then
   nothing is registered. Hand :live to `stop!` when the surface goes."
  [bundle-or-doc opts]
  (rail/bind (surface bundle-or-doc opts) register!))

(defn stop!
  "Stop running surface `live` (from surface or install!): unsubscribe and
   close the port."
  [live]
  (source/stop! live))
