(ns autonomous.e2e.agents
  "Adapters of the scenario's Agent port: an agent inside this process
   (an MCP server value and a session) and an agent reaching a bridge
   process over MCP HTTP with its own bearer token. Boundary stratum; the
   HTTP client is java.net.http, which the JVM and babashka both carry."
  (:require [autonomous.bridge.session :as s]
            [autonomous.e2e.scenario :as scenario]
            [autonomous.mcp.json :as json]
            [autonomous.mcp.server :as server])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(defn- tool-call [id tool args]
  {:jsonrpc "2.0" :id id :method "tools/call" :params {:name tool :arguments args}})

;; -----------------------------------------------------------------------------
;; In process

(defrecord LocalAgent [srv session]
  scenario/Agent
  (-call [_ tool args]
    (let [{:keys [response] next-session :session} (server/handle srv @session (tool-call 1 tool args))]
      (reset! session next-session)
      (scenario/call-outcome response))))

(defn local-agent-on
  "(fn [actor runtime-id] Agent) over MCP server `srv`: each agent gets a
   fresh read/write session attached to the runtime."
  [srv]
  (fn [actor runtime-id]
    (let [a (->LocalAgent srv (atom (s/open-session actor {:actor actor :scopes #{:read :write}})))]
      (scenario/-call a "autonomous_attach" {:runtime_id runtime-id})
      a)))

;; -----------------------------------------------------------------------------
;; Over MCP HTTP

(defn- post!
  "Status, session header and decoded body of POSTing JSON-RPC `msg` to
   `url` with bearer `token` and session id `sid` (nil before initialize)."
  [^HttpClient client url token sid msg]
  (let [b    (cond-> (-> (HttpRequest/newBuilder (URI. url))
                         (.timeout (Duration/ofSeconds 60))
                         (.header "Content-Type" "application/json")
                         (.header "Authorization" (str "Bearer " token))
                         (.POST (HttpRequest$BodyPublishers/ofString (json/write msg))))
               sid (.header "Mcp-Session-Id" sid))
        resp (.send client (.build b) (HttpResponse$BodyHandlers/ofString))
        body (.body resp)]
    {:status (.statusCode resp)
     :sid    (.orElse (.firstValue (.headers resp) "mcp-session-id") nil)
     :body   (when (seq body) (json/read-json body))}))

(defrecord HttpAgent [client url token sid ids]
  scenario/Agent
  (-call [_ tool args]
    (let [{:keys [status body]} (post! client url token sid (tool-call (swap! ids inc) tool args))]
      (if (= 200 status)
        (scenario/call-outcome body)
        {:value nil :error? true :status status}))))

(defn http-client
  "One HTTP client for every agent of a run."
  []
  (HttpClient/newHttpClient))

(defn http-session!
  "An HttpAgent with a fresh MCP session on the bridge at `url` for
   `token`, or throws ex-info carrying the HTTP status."
  [client url token]
  (let [init (post! client url token nil {:jsonrpc "2.0" :id 0 :method "initialize"
                                          :params {:protocolVersion "2025-06-18"}})]
    (when-not (and (= 200 (:status init)) (:sid init))
      (throw (ex-info (str "initialize failed with HTTP " (:status init)) init)))
    (post! client url token (:sid init) {:jsonrpc "2.0" :method "notifications/initialized"})
    (->HttpAgent client url token (:sid init) (atom 0))))

(defn http-agent-on
  "(fn [actor runtime-id] Agent) for a bridge at `url`, where `tokens` maps
   each actor to its bearer token: the actor is whoever the token names,
   so every agent is authenticated exactly as in production."
  [client url tokens]
  (fn [actor runtime-id]
    (let [a (http-session! client url (get tokens actor))]
      (scenario/-call a "autonomous_attach" {:runtime_id runtime-id})
      a)))
