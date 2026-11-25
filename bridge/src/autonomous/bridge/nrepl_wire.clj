(ns autonomous.bridge.nrepl-wire
  "nREPL adapter of IRuntimeChannel and IRuntimeDirectory for babashka and
   ClojureWasm, over bb-mcp's bencode client (bb-mcp.tools.nrepl): one short
   TCP connection per eval through bb-mcp's host byte channel, so the same
   code runs on every runtime bb-mcp runs on. Cheap enough that one bridge
   holds a target per microservice.

   Source text and parsing are autonomous.bridge.eval-source (pure), shared
   with the JVM adapter.

   Security: an nREPL is remote code execution by design. Keep it bound to
   localhost; let only the scoped MCP endpoint cross a network."
  (:require [autonomous.bridge.endpoint :as endpoint]
            [autonomous.bridge.eval-source :as src]
            [autonomous.bridge.port :as port]
            [autonomous.domain.answer :as answer]
            [bb-mcp.tools.nrepl :as bb-nrepl]))

(def default-timeout-ms 30000)

(defn- wire-answer
  "L1 Promote: the Answer of bb-mcp's eval result map, for nREPL `port`."
  [port {:keys [result error? connect-failed?]}]
  (cond
    connect-failed? (answer/refusal :channel/connect-failed
                                    (str "cannot reach nREPL on port " port) {:cause result})
    error?          (answer/refusal :channel/eval-threw (str result))
    :else           (answer/ok result)))

(defn- eval-on-port
  "L3 Boundary: the Answer of evaluating `code` on the nREPL at `port`."
  [target port code]
  (wire-answer port (bb-nrepl/eval-code {:host       (:nrepl/host target "localhost")
                                         :port       port
                                         :code       code
                                         :timeout-ms (:timeout-ms target default-timeout-ms)})))

(defn- eval!
  "L3 Boundary: Ok of the printed value of `code` on `target`'s nREPL, or a
   refusal."
  [target code]
  (answer/bind (endpoint/resolve-port target) #(eval-on-port target % code)))

(defrecord WireChannel [target]
  port/IRuntimeChannel
  (-send [_ request runtime-id]
    (let [mode (:nrepl/mode target)]
      (answer/bind (eval! target (src/request-source mode (:shadow/build target) request runtime-id))
                   #(src/parse-value mode %))))

  port/IRuntimeDirectory
  (-runtimes [_]
    (if (= :clj (:nrepl/mode target))
      src/clj-runtimes
      (src/runtimes-answer (eval! target (src/runtimes-source (:shadow/build target)))))))

(defn channel
  "A WireChannel for nREPL `target`. Opens a connection per call."
  [target]
  (->WireChannel target))
