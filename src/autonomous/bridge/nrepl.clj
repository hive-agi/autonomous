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
            [autonomous.bridge.failure :as failure]
            [autonomous.bridge.port :as port]
            [autonomous.domain.answer :as answer]
            [clojure.string :as str]
            [nrepl.core :as nrepl]
            [autonomous.bridge.endpoint :as endpoint]))

(def default-timeout-ms 30000)

(defn- connect
  "L3 Boundary: Ok {:transport :session} of a fresh nREPL connection to
   `port`, or a :channel/connect-failed refusal."
  [target port]
  (try
    (let [transport (nrepl/connect :host (:nrepl/host target "localhost") :port port)
          client    (nrepl/client transport (:timeout-ms target default-timeout-ms))]
      (answer/ok {:transport transport :session (nrepl/client-session client)}))
    (catch Exception e
      (answer/refusal :channel/connect-failed
                      (str "cannot reach nREPL on port " port) {:cause (failure/message e)}))))

(defn- open!
  "L3 Boundary: Ok of a connection to `target`'s nREPL, or a refusal."
  [target]
  (answer/bind (endpoint/resolve-port target) #(connect target %)))

(defn- close!
  "L3 Boundary: close the connection's transport. A transport that fails to
   close is already dead, which is the state close! exists to reach."
  [conn]
  (when-let [t (:transport conn)]
    (try (.close ^java.io.Closeable t)
         (catch Exception _already-dead nil))))

(defn- forget!
  "L3 Boundary: close and drop the connection held in atom `conn`."
  [conn]
  (close! @conn)
  (reset! conn nil))

(defn- eval-answer
  "L1 Promote: Ok of the last printed :value among nREPL messages `msgs`
   for `code`, or the refusal they amount to."
  [code msgs]
  (let [values (keep :value msgs)
        errs   (str/join (keep :err msgs))]
    (cond
      (some :ex msgs) (answer/refusal :channel/eval-threw (str/trim errs) {:code (subs code 0 (min 200 (count code)))})
      (empty? values) (answer/refusal :channel/no-value (if (str/blank? errs) "the eval produced no value" (str/trim errs)))
      :else           (answer/ok (last values)))))

(defn- eval-once
  "L3 Boundary: Ok of the last printed :value of evaluating `code`, or a
   refusal."
  [conn code timeout-ms]
  (eval-answer code (doall (nrepl/message (:session conn) {:op "eval" :code code :timeout timeout-ms}))))

(defn- connection!
  "L3 Boundary: Ok of the channel's open connection, opening one when
   there is none."
  [{:keys [target conn]}]
  (if-let [c @conn]
    (answer/ok c)
    (answer/bind (open! target) #(answer/ok (reset! conn %)))))

(defn- attempt!
  "L3 Boundary: one evaluation of `code` on the channel's connection."
  [{:keys [target] :as channel} code]
  (answer/bind (connection! channel)
               #(eval-once % code (:timeout-ms target default-timeout-ms))))

(defn- attempt-twice!
  "L3 Boundary: evaluate `code`, reconnecting once when the socket broke."
  [channel code]
  (try (attempt! channel code)
       (catch java.net.SocketException _
         (forget! (:conn channel))
         (attempt! channel code))))

(defn- eval!
  "L3 Boundary: the Answer of evaluating `code` on the channel's
   connection, serialized, reconnecting once; never throws. A transport
   exception shows only the allowlisted text of autonomous.bridge.failure.
   An ex-info is exempt and keeps its message and data: it is the app's
   own deliberate refusal (an :autonomous/error), raised in the runtime
   this bridge evaluates in, not text a remote echoed back."
  [{:keys [conn] :as channel} code]
  (locking conn
    (try (attempt-twice! channel code)
         (catch clojure.lang.ExceptionInfo e (answer/from-exception e))
         (catch Exception e
           (forget! conn)
           (answer/refusal :channel/transport-failed (failure/message e))))))

(defrecord NreplChannel [target conn]
  port/IRuntimeChannel
  (-send [this request runtime-id]
    (let [mode (:nrepl/mode target)]
      (answer/bind (eval! this (src/request-source mode (:shadow/build target) request runtime-id))
                   #(src/parse-value mode %))))

  port/IRuntimeDirectory
  (-runtimes [this]
    (case (:nrepl/mode target)
      :clj    src/clj-runtimes
      :shadow (src/runtimes-answer (eval! this (src/runtimes-source (:shadow/build target))))))

  java.io.Closeable
  (close [_] (forget! conn)))

(defn channel
  "An NreplChannel for nREPL `target` (autonomous.bridge.schema/NreplTarget).
   Connects lazily, on the first call."
  [target]
  (->NreplChannel target (atom nil)))
