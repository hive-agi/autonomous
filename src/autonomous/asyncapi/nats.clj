(ns autonomous.asyncapi.nats
  "OPTIONAL NATS adapter: the port over a real broker, with no hard
   dependency. Nothing here is required (no :require of a NATS client); the
   client is `requiring-resolve`d the moment a connection is opened, so a
   pure user and the whole bb suite never load it.

   The client is the CLJS-shaped `@nats-io/nats-core` API a JVM/bb build of
   nats exposes as `nats` namespaces. Whichever build is on the classpath
   must answer the four vars named in `client-vars`; anything else is
   reported as :asyncapi/no-nats-client, not as a compile failure.

   Never started by the tests: connecting is the integrator's call.

     (autonomous.asyncapi.port/connect :asyncapi.nats/driver
       {:url \"nats://localhost:4222\"})
     ;; => Ok port; then hand the port to autonomous.asyncapi/surface"
  (:require [autonomous.asyncapi.port :as port]
            [autonomous.domain.answer :as answer]))

(def client-vars
  "vars the loaded client build must provide, by key."
  {:connect 'nats.core/connect
   :sub     'nats.core/subscribe
   :unsub   'nats.core/unsubscribe
   :close   'nats.core/close})

(defn- resolve-var [sym]
  (try (some-> (requiring-resolve sym) deref) (catch Throwable _ nil)))

(defn client
  "The resolved client {key fn}, or a refusal naming what is missing."
  []
  (let [cs (into {} (map (fn [[k s]] [k (resolve-var s)])) client-vars)
        missing (keep (fn [[k f]] (when-not (fn? f) k)) cs)]
    (if (seq missing)
      (answer/refusal :asyncapi/no-nats-client
                      (str "no NATS client on the classpath (missing "
                           (mapv name missing) "); add one, or use the "
                           ":asyncapi.memory/bus port") {:missing (vec missing)})
      (answer/ok cs))))

(defn port-of
  "Ok port over an already connected client `nc` and its API `cs`."
  [nc cs]
  (answer/ok
   {:subscribe  (fn [filter handler]
                  ((:sub cs) nc filter
                   (fn [_err msg]
                     (handler {:subject (:subject msg) :payload (:data msg)}))))
    :unsubscribe (fn [sid] ((:unsub cs) nc sid))
    :close       (fn [] ((:close cs) nc))}))

(defmethod port/connect :asyncapi.nats/driver [_ {:keys [url opts]}]
  (let [cs (client)]
    (if (answer/refused? cs)
      cs
      (try
        (port-of ((get-in cs [:ok :connect]) (or url "nats://localhost:4222") (or opts {}))
                 (:ok cs))
        (catch Throwable e
          (answer/from-exception e))))))
