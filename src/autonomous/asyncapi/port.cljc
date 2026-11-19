(ns autonomous.asyncapi.port
  "The message-bus port: what a tap needs from a broker, and nothing else.

   A port is a map:

     {:subscribe  (fn [filter handler] sub-id)   start the tap, answer an id
      :unsubscribe (fn [sub-id] nil)            stop it
      :close      (fn [] nil)                   drop everything it opened}

   `handler` is called with {:subject :payload}. Which broker backs a port is
   an OPEN set: `connect` is a multimethod on a namespaced kind, so a broker
   adapter is one defmethod and no namespace here changes. Tests use
   :asyncapi.memory/bus, which needs no server; :asyncapi.nats/driver loads
   the client lazily and is never a dependency of the pure strata."
  (:require [autonomous.domain.answer :as answer]))

(defmulti connect
  "Ok port of `kind` connected with `opts`, or a refusal. Effects live here:
   everything a caller does with the port afterwards is I/O too."
  (fn [kind _opts] kind))

(defmethod connect :default [kind _]
  (answer/refusal :asyncapi/unknown-bus
                  (str "no bus port for kind " (pr-str kind)
                       "; load the namespace that provides it")))

(defn port?
  [x]
  (and (map? x) (fn? (:subscribe x)) (fn? (:unsubscribe x))))

(defn problems
  "Why `port` is not a usable port, empty when it is."
  [port]
  (cond
    (not (map? port))            [:not-a-map]
    (not (fn? (:subscribe port))) [:no-subscribe]
    (not (fn? (:unsubscribe port))) [:no-unsubscribe]
    :else []))

(defn check
  "Ok `port`, or a refusal naming what it lacks."
  [port]
  (let [ps (problems port)]
    (if (empty? ps)
      (answer/ok port)
      (answer/refusal :asyncapi/bad-bus
                      (str "bus port lacks " (mapv name ps))
                      {:problems ps}))))

(defn close
  "Close `port` when it has a :close, else nil. Never throws."
  [port]
  (try (when-let [c (:close port)] (c)) nil
       (catch #?(:clj Throwable :cljs :default) _ nil)))
