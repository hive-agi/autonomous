(ns autonomous.bridge.nrepl
  "nREPL adapter of IRuntimeChannel and IRuntimeDirectory: reaches the
   runtimes of a shadow-cljs build (mode :shadow) or a Clojure process
   (mode :clj) through the nREPL the development setup already runs.

   Boundary stratum. The source text and the parsing are pure
   (autonomous.bridge.eval-source); this namespace only owns the socket.
   Calls on one channel are serialized (an nREPL session evaluates one form
   at a time anyway), and a broken connection is reopened once per call.

   Security: an nREPL is remote code execution by design. Keep it bound to
   localhost; let only the scoped MCP endpoint cross a network."
  (:require [autonomous.bridge.eval-source :as src]
            [autonomous.bridge.port :as port]
            [autonomous.domain.answer :as answer]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nrepl.core :as nrepl]
            [autonomous.domain.wire :as wire]))

(def default-timeout-ms 30000)

(defn resolve-port
  "The nREPL port of `target`: :nrepl/port, else the number in
   :nrepl/port-file (shadow-cljs writes .shadow-cljs/nrepl.port, a deps
   nREPL writes .nrepl-port). Answers Ok port or a :channel/no-port refusal."
  [{:nrepl/keys [port port-file]}]
  (cond
    port (answer/ok port)
    (and port-file (.exists (io/file port-file)))
    (try (answer/ok (Long/parseLong (str/trim (slurp port-file))))
         (catch Exception e
           (answer/refusal :channel/no-port (str "unreadable port file " port-file) {:cause (ex-message e)})))
    :else (answer/refusal :channel/no-port
                          (str "no nREPL port: set :nrepl/port, or start the app so it writes "
                               (or port-file "its port file")))))

(defn- open! [target]
  (let [p (resolve-port target)]
    (if (answer/refused? p)
      p
      (try
        (let [transport (nrepl/connect :host (:nrepl/host target "localhost") :port (:ok p))
              client    (nrepl/client transport (:timeout-ms target default-timeout-ms))]
          (answer/ok {:transport transport :session (nrepl/client-session client)}))
        (catch Exception e
          (answer/refusal :channel/connect-failed
                          (str "cannot reach nREPL on port " (:ok p)) {:cause (ex-message e)}))))))

(defn- close!
  "Close the connection's transport. A transport that fails to close is
   already dead, which is the state close! exists to reach."
  [conn]
  (when-let [t (:transport conn)]
    (try (.close ^java.io.Closeable t)
         (catch Exception _already-dead nil))))

(defn- eval-once
  "Ok of the last printed :value of evaluating `code`, or a refusal."
  [conn code timeout-ms]
  (let [msgs   (doall (nrepl/message (:session conn) {:op "eval" :code code :timeout timeout-ms}))
        values (keep :value msgs)
        errs   (str/join (keep :err msgs))]
    (cond
      (some :ex msgs)     (answer/refusal :channel/eval-threw (str/trim errs) {:code (subs code 0 (min 200 (count code)))})
      (empty? values)     (answer/refusal :channel/no-value (if (str/blank? errs) "the eval produced no value" (str/trim errs)))
      :else               (answer/ok (last values)))))

(defn- eval!
  "Evaluate `code` on the channel's connection, reconnecting once."
  [{:keys [target conn]} code]
  (locking conn
    (letfn [(attempt []
              (when-not @conn
                (let [c (open! target)]
                  (if (answer/refused? c) (throw (ex-info "connect" {::refusal c})) (reset! conn (:ok c)))))
              (eval-once @conn code (:timeout-ms target default-timeout-ms)))]
      (try
        (try (attempt)
             (catch java.net.SocketException _
               (close! @conn) (reset! conn nil) (attempt)))
        (catch clojure.lang.ExceptionInfo e
          (or (::refusal (ex-data e)) (answer/from-exception e)))
        (catch Exception e
          (close! @conn) (reset! conn nil)
          (answer/refusal :channel/transport-failed (or (ex-message e) (str e))))))))

(defrecord NreplChannel [target conn]
  port/IRuntimeChannel
  (-send [this request runtime-id]
    (let [mode (:nrepl/mode target)
          res  (eval! this (src/request-source mode (:shadow/build target) request runtime-id))]
      (if (answer/refused? res) res (src/parse-value mode (:ok res)))))

  port/IRuntimeDirectory
  (-runtimes [this]
    (case (:nrepl/mode target)
      :clj    (answer/ok [{:runtime/id "jvm" :runtime/kind :clj :runtime/label "Clojure process"}])
      :shadow (let [res (eval! this (src/runtimes-source (:shadow/build target)))]
                (if (answer/refused? res)
                  res
                  (answer/ok (src/parse-runtimes (wire/read-edn (:ok res))))))))

  java.io.Closeable
  (close [_] (close! @conn) (reset! conn nil)))

(defn channel
  "An NreplChannel for nREPL `target` (autonomous.bridge.schema/NreplTarget).
   Connects lazily, on the first call."
  [target]
  (->NreplChannel target (atom nil)))
