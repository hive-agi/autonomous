(ns autonomous.ws.server
  "A standalone listener for the runtime endpoint (`autonomous serve --ws
   [HOST:]PORT`), on http-kit: built into babashka, the :ws alias on the
   JVM. An app with its own Ring stack does not need this: it wraps its
   handler with autonomous.ws.ring/wrap-ws instead."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.ws.ring :as ws-ring]))

(defn runtime-tokens
  "The entries of `tokens` that may register a runtime (scope :runtime)."
  [tokens]
  (filterv #(some #{:runtime "runtime"} (:scopes %)) tokens))

(defn start!
  "Listen for runtimes on `bind`:`port` (port 0 picks one). Answers Ok
   {:port :stop!} or a refusal: no runtime token configured, no http-kit,
   or the port cannot be bound."
  [{:keys [bind port tokens] :or {bind "127.0.0.1"} :as opts}]
  (let [run-server (try (requiring-resolve 'org.httpkit.server/run-server)
                        (catch Exception _no-http-kit nil))]
    (cond
      (empty? (runtime-tokens tokens))
      (answer/refusal :ws/no-runtime-token
                      (str "no token grants scope runtime: mint one per app with "
                           "`autonomous token --actor <target-id> --scopes runtime`"))

      (nil? run-server)
      (answer/refusal :ws/unsupported "no http-kit on the classpath: run on babashka, or add the :ws alias")

      :else
      (try
        (let [h      (ws-ring/wrap-ws (fn [_] {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"})
                                      (assoc opts :tokens (runtime-tokens tokens)))
              server (run-server h {:ip bind :port port :legacy-return-value? false})]
          (answer/ok {:port  ((requiring-resolve 'org.httpkit.server/server-port) server)
                      :stop! (fn [] @((requiring-resolve 'org.httpkit.server/server-stop!) server {:timeout 2000}))}))
        (catch Exception bind-failure
          (answer/refusal :ws/bind-failed (str "cannot listen on " bind ":" port ": " (ex-message bind-failure))))))))
