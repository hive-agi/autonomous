(ns autonomous.bridge.local
  "In-process adapter of IRuntimeChannel: the runtime is this JVM. A Clojure
   backend that registers its own tools (autonomous.core runs on the JVM
   too) embeds the bridge and serves them with no REPL in between.

   Requests still travel as printed EDN, so the local path exercises exactly
   the wire every remote runtime uses."
  (:require [autonomous.bridge.port :as port]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.wire :as wire]
            [autonomous.runtime.registry :as registry]
            [autonomous.runtime.rpc :as rpc]))

(defn- send-edn [request]
  (try (wire/read-edn (rpc/rpc-str (wire/print-edn request)))
       (catch Exception e (answer/from-exception e))))

(defrecord LocalChannel [target state]
  port/IRuntimeChannel
  (-send [_ request _runtime-id]
    (if state
      (registry/with-instance state #(send-edn request))
      (send-edn request)))

  port/IRuntimeDirectory
  (-runtimes [_]
    (answer/ok [{:runtime/id "local" :runtime/kind :clj :runtime/label "this process"}])))

(defn channel
  "The in-process channel of `target`. With registry instance `state`
   (autonomous.runtime.registry/instance) every request is answered by that
   instance instead of the process-wide registry: how a middleware serves
   its own surface without touching the global one."
  ([] (channel {:target/kind :local :target/id "local"}))
  ([target] (channel target nil))
  ([target state] (->LocalChannel target state)))
