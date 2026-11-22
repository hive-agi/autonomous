(ns autonomous.bridge.endpoint
  "Where an nREPL target listens. Portable (JVM, babashka, ClojureWasm):
   only slurp, parse-long and java.io.File existence, which every bridge
   runtime carries."
  (:require [autonomous.domain.answer :as answer]
            [clojure.string :as str]))

(defn- file-exists? [^String path]
  (.exists (java.io.File. path)))

(defn resolve-port
  "The nREPL port of `target`: :nrepl/port, else the number in
   :nrepl/port-file (shadow-cljs writes .shadow-cljs/nrepl.port, a deps
   nREPL writes .nrepl-port). Answers Ok port or a :channel/no-port
   refusal. Read on every call, so a restarted app with a new port is
   found again."
  [{:nrepl/keys [port port-file]}]
  (cond
    port (answer/ok port)
    (and port-file (file-exists? port-file))
    (if-let [p (parse-long (str/trim (slurp port-file)))]
      (answer/ok p)
      (answer/refusal :channel/no-port (str "unreadable port file " port-file)))
    :else (answer/refusal :channel/no-port
                          (str "no nREPL port: set :nrepl/port, or start the app so it writes "
                               (or port-file "its port file")))))
