(ns autonomous.bridge.local
  "In-process adapter of IRuntimeChannel: the runtime is this JVM. A Clojure
   backend that registers its own tools (autonomous.core runs on the JVM
   too) embeds the bridge and serves them with no REPL in between.

   Requests still travel as printed EDN, so the local path exercises exactly
   the wire every remote runtime uses."
  (:require [autonomous.bridge.port :as port]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.wire :as wire]
            [autonomous.runtime.rpc :as rpc]))

(defrecord LocalChannel [target]
  port/IRuntimeChannel
  (-send [_ request _runtime-id]
    (try (wire/read-edn (rpc/rpc-str (wire/print-edn request)))
         (catch Exception e (answer/from-exception e))))

  port/IRuntimeDirectory
  (-runtimes [_]
    (answer/ok [{:runtime/id "local" :runtime/kind :clj :runtime/label "this process"}])))

(defn channel
  ([] (channel {:target/kind :local :target/id "local"}))
  ([target] (->LocalChannel target)))
