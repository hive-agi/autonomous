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

   Surface construction uses the shared Answer railway so a refusal stops
   the next step."
  (:require [autonomous.asyncapi.derive :as derive]
            [autonomous.asyncapi.port :as port]
            [autonomous.asyncapi.read :as read]
            [autonomous.asyncapi.source :as source]
            [autonomous.domain.answer :as answer]))

(def kind derive/kind)

(defn- app-info [opts]
  (or (:app opts)
      {:name (or (some-> (:prefix opts) name) "asyncapi")
       :description "Read-only taps on published events"}))

(defn- subscribed
  "Start the derived taps on the checked bus."
  [bus specs]
  (source/start! bus (into {} (map (juxt :uri identity)) specs)))

(defn- with-app
  "Ok Surface of running surface `live`, named by opts, read by `read-tap`."
  [live opts read-tap]
  (answer/ok {:app       (app-info opts)
              :tools     {}
              :resources (source/resource-specs live read-tap)
              :live      live}))

(defn surface
  "Build and subscribe a read-only surface, or refuse before leaving taps live.
   Keep :live to pass to `stop!` when removing the surface."
  [bundle-or-doc opts]
  (answer/let-answer [bus   (port/check (:port opts))
                      specs (derive/taps bundle-or-doc opts)
                      live  (subscribed bus specs)]
    (with-app live opts read/read-tap)))

(defn- register!
  "Install a running surface and return its registered URIs and live handle."
  [{:keys [live]}]
  (answer/let-answer [live (source/install! live read/read-tap)]
    (answer/ok {:registered (vec (keys (:taps live))) :live live})))

(defn install!
  "Register every derived resource in the current registry. Answers Ok
   {:registered [uri ...] :live live-surface}, or the refusal, and then
   nothing is registered. Hand :live to `stop!` when the surface goes."
  [bundle-or-doc opts]
  (answer/bind (surface bundle-or-doc opts) register!))

(defn stop!
  "Stop running surface `live` (from surface or install!): unsubscribe and
   close the port."
  [live]
  (source/stop! live))
