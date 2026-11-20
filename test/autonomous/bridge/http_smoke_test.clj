(ns autonomous.bridge.http-smoke-test
  "Live smoke of the remote-MCP channel on babashka: real sockets, no stubs.

   Two in-process Ring apps listen on 127.0.0.1 (port 0) under http-kit,
   each with one explicit tool:
   - `wrapped`: the app's handler wrapped by autonomous.ring/wrap-autonomous,
     the one line an app adds;
   - `streamed`: the same engine (autonomous.ring/engine) served through
     autonomous.mcp.http/ring-handler with autonomous.mcp.http.kit's
     stream-response and the stream pusher, so the bridge's event stream is
     a held SSE connection.
   A real bridge fleet (autonomous.cli/build-server, :target/kind :http)
   lists both through autonomous.mcp.server/list-tools and calls each
   through server/call-tool: the names are <app>__<tool> and the value
   crosses the wire end to end. Servers and pusher stop in a finally.

   Credentials are not exercised here: the apps serve loopback without
   tokens, as their anonymous grant."
  (:require [autonomous.bridge.session :as s]
            [autonomous.cli :as cli]
            [autonomous.mcp.http :as http]
            [autonomous.mcp.http.kit :as kit]
            [autonomous.mcp.server :as server]
            [autonomous.ring :as ar]
            [autonomous.spi :as spi]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as hk]))

(def ^:private agent-grant {:actor "smoke-agent" :scopes #{:read :write}})

(defn- app-opts
  "wrap-autonomous / engine options of app `app-name`: one explicit tool."
  [app-name]
  {:app       {:name app-name :description (str "smoke app " app-name)}
   :anonymous agent-grant
   :tools     {:greet {:description "Greet someone by name"
                       :read-only?  true
                       :input       [:map [:name :string]]
                       :handler     (fn [args grant]
                                      {:hello (:name args) :from app-name :by (:actor grant)})}}})

(defn- base-handler
  "The app's own Ring handler: what a human request reaches."
  [_req]
  {:status 200 :headers {"Content-Type" "text/plain"} :body "human page"})

(defn- listen!
  "Serve Ring `handler` on 127.0.0.1, an ephemeral port. Answers
   {:port :stop!}."
  [handler]
  (let [srv (hk/run-server handler {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    {:port  (hk/server-port srv)
     :stop! (fn [] @(hk/server-stop! srv {:timeout 1000}))}))

(defn- start-wrapped!
  "The `wrapped` app: base-handler under wrap-autonomous."
  []
  (listen! (ar/wrap-autonomous base-handler (app-opts "wrapped"))))

(defn- start-streamed!
  "The `streamed` app: the ring engine's MCP endpoint with a held event
   stream (kit/stream-response) and the list_changed pusher."
  []
  (let [opts   (app-opts "streamed")
        eng    (:ok (ar/engine (:ok (spi/derive-surface :autonomous/tools nil opts)) base-handler opts))
        state  (http/new-state)
        cfg    (assoc (:cfg eng) :watch-ms 100)
        mcp    (http/ring-handler (:srv eng) state cfg {:stream-response (kit/stream-response state)})
        stop-pusher! (http/start-pusher! (:srv eng) state cfg)
        {:keys [port stop!]} (listen! (fn [req] (if (= "/mcp" (:uri req)) (mcp req) (base-handler req))))]
    {:port  port
     :stop! (fn []
              (stop-pusher!)
              (http/close-streams! state)
              (stop!))}))

(defn- http-target [tid port]
  {:target/kind :http :target/id tid :url (str "http://127.0.0.1:" port) :timeout-ms 5000})

(deftest a-live-fleet-reaches-ring-apps-over-http
  (let [wrapped  (start-wrapped!)
        streamed (start-streamed!)]
    (try
      (let [srv   (cli/build-server {:targets         [(http-target "wrapped" (:port wrapped))
                                                       (http-target "streamed" (:port streamed))]
                                     :ledger          {:ledger/kind :memory}
                                     :probe-ms        5000
                                     :call-timeout-ms 5000})
            ses   (s/open-session "smoke" agent-grant)
            names (set (map :name (server/list-tools srv ses)))]
        (testing "each app's tool is listed under its own prefix"
          (is (contains? names "wrapped__greet") (pr-str names))
          (is (contains? names "streamed__greet") (pr-str names))
          (is (contains? names "autonomous_apps") "the fleet built-ins are there too"))
        (testing "a call crosses the wire to the app and back"
          (is (= {:ok {:hello "ada" :from "wrapped" :by "smoke-agent"}}
                 (:answer (server/call-tool srv ses "wrapped__greet" {:name "ada"}))))
          (is (= {:ok {:hello "bob" :from "streamed" :by "smoke-agent"}}
                 (:answer (server/call-tool srv ses "streamed__greet" {:name "bob"})))))
        (testing "the app's own routes still pass through the wrap"
          (is (= "human page" (slurp (str "http://127.0.0.1:" (:port wrapped) "/")))))
        (testing "a refused argument is a refusal, not a value"
          (is (contains? (:answer (server/call-tool srv ses "wrapped__greet" {:name 42})) :error))))
      (finally
        ((:stop! streamed))
        ((:stop! wrapped))))))
