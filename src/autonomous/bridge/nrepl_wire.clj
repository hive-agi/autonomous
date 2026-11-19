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
            [autonomous.domain.wire :as wire]
            [bb-mcp.tools.nrepl :as bb-nrepl]))

(def default-timeout-ms 30000)

(defn- eval!
  "Ok of the printed value of `code` on `target`'s nREPL, or a refusal."
  [target code]
  (let [p (endpoint/resolve-port target)]
    (if (answer/refused? p)
      p
      (let [{:keys [result error? connect-failed?]}
            (bb-nrepl/eval-code {:host       (:nrepl/host target "localhost")
                                 :port       (:ok p)
                                 :code       code
                                 :timeout-ms (:timeout-ms target default-timeout-ms)})]
        (cond
          connect-failed? (answer/refusal :channel/connect-failed
                                          (str "cannot reach nREPL on port " (:ok p)) {:cause result})
          error?          (answer/refusal :channel/eval-threw (str result))
          :else           (answer/ok result))))))

(defrecord WireChannel [target]
  port/IRuntimeChannel
  (-send [_ request runtime-id]
    (let [mode (:nrepl/mode target)
          res  (eval! target (src/request-source mode (:shadow/build target) request runtime-id))]
      (if (answer/refused? res) res (src/parse-value mode (:ok res)))))

  port/IRuntimeDirectory
  (-runtimes [_]
    (if (= :clj (:nrepl/mode target))
      (answer/ok [{:runtime/id "jvm" :runtime/kind :clj :runtime/label "Clojure process"}])
      (let [res (eval! target (src/runtimes-source (:shadow/build target)))]
        (if (answer/refused? res)
          res
          (answer/ok (src/parse-runtimes (wire/read-edn (:ok res)))))))))

(defn channel
  "A WireChannel for nREPL `target`. Opens a connection per call."
  [target]
  (->WireChannel target))
