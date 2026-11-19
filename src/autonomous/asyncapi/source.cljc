(ns autonomous.asyncapi.source
  "A derived surface's live half: the taps of the resources, and teardown.

   `start!` subscribes a tap per resource and answers the running surface:

     {:port port :taps {uri {:tap atom :store atom}} :subs [id ...]}

   `install!` registers the resources into a registry instance and hands back
   the same running surface, so the caller has one thing to `stop!`.

   Nothing is registered for a subject that could not be derived: the caller
   derives first (autonomous.asyncapi.derive/taps) and gets a refusal before
   any port is opened."
  (:require [autonomous.asyncapi.port :as port]
            [autonomous.asyncapi.rail :as rail]
            [autonomous.asyncapi.subject :as subject]
            [autonomous.asyncapi.tap :as tap]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.affordance :as affordance]
            [autonomous.runtime.registry :as registry]
            [malli.core :as m]))

(defn- validator
  "Fn of a payload answering a malli explanation or nil, for malli form
   `schema`; nil when there is no schema."
  [schema]
  (when schema
    (when-let [s (try (m/schema schema) (catch #?(:clj Throwable :cljs :default) _ nil))]
      (m/explainer s))))

(defn- admit!
  "Admit `msg` into tap atom `ta` when its subject matches `filter`."
  [ta filter {:keys [subject payload at]}]
  (when (subject/matches? filter subject)
    (swap! ta (fn [t] (first (tap/admit t {:subject subject :payload payload :at at}))))))

(defn- subscribe-one
  "Entry [uri desc+live] of resource `desc` subscribed on `port`."
  [port {:keys [subject uri capacity schema] :as desc}]
  (let [ta (atom (tap/tap subject capacity (validator schema)))]
    [uri (assoc desc
                :tap ta
                :store (atom {})
                :sub (port/subscribe port subject (partial admit! ta subject)))]))

(defn- unsubscribe-one
  "Stop resource entry {:sub} on `port`, swallowing a transport failure."
  [port {:keys [sub]}]
  (try (port/unsubscribe port sub) (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn stop!
  "Unsubscribe every subscription of running surface `live`, then close the
   port. Answers true."
  [live]
  (run! (partial unsubscribe-one (:port live)) (vals (:taps live)))
  (port/close (:port live))
  true)

(defn- close-on-refusal
  "Refusal `r`, with `port` closed: a half-subscribed port must not leak."
  [port r]
  (port/close port)
  r)

(defn start!
  "Ok running surface of `resources` ({uri desc}) subscribed on `port`; on a
   refusal the port is closed again. Never throws."
  [port resources]
  (try
    (let [entries (into {} (map (partial subscribe-one port)) (vals resources))]
      (answer/ok {:port port :taps entries}))
    (catch #?(:clj Throwable :cljs :default) e
      (close-on-refusal port (answer/from-exception e)))))

(defn- resource-spec
  "ResourceSpec of live entry `e` (a tap desc plus its atom and store), read
   by the 4-arity `read-tap` with the entry's own tap, store and desc bound."
  [read-tap {:keys [uri description scopes tap-a store-a] :as e}]
  (cond-> {:read (partial read-tap tap-a store-a e) :name uri
           :description description :mime "application/json"}
    scopes (assoc :scopes scopes)))

(defn resource-specs
  "ResourceSpecs of running surface `live` by uri, read by `read-tap`.
   Each resource reads its OWN tap and store; `desc` is the tap description."
  [live read-tap]
  (into {}
        (map (fn [[uri e]]
               [uri (resource-spec read-tap (assoc e :tap-a (:tap e) :store-a (:store e)))]))
        (:taps live)))

(defn install!
  "Register every resource of running surface `live` in the current registry,
   read by `read-tap`. Answers Ok `live`, or the first refusal."
  [live read-tap]
  (rail/bind
   (rail/collect (map (fn [[uri spec]] (answer/ok (registry/add-resource! uri spec)))
                      (resource-specs live read-tap)))
   (fn [_] (answer/ok live))))
