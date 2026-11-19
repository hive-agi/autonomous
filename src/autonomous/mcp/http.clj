(ns autonomous.mcp.http
  "MCP over HTTP (streamable HTTP, request/response; no server stream): the
   transport for remote agents and swarms.

   Security, in order, on every request:
   1. exposure: a non-loopback bind without tokens refuses to START.
   2. Origin: a request carrying an Origin header not in :allowed-origins is
      refused (403), which defeats DNS rebinding from a browser.
   3. authentication: with tokens configured, a bearer token is required and
      names the actor and scopes (401 otherwise). Without tokens (loopback
      only) every caller is the configured anonymous grant.
   4. session ownership: a session id is honoured only for the actor that
      opened it (404 otherwise, as for an unknown session).

   `route` and `handle-request` are pure over request maps and a state
   atom, shared by every runtime; the socket adapters live in
   autonomous.mcp.http.jdk (JVM) and autonomous.mcp.http.kit (babashka),
   chosen by autonomous.mcp.http.start."
  (:require [autonomous.bridge.auth :as auth]
            [autonomous.bridge.session :as s]
            [autonomous.domain.answer :as answer]
            [autonomous.mcp.json :as json]
            [autonomous.mcp.protocol :as p]
            [autonomous.mcp.server :as server]
            [clojure.string :as str]))

(def max-body-bytes (* 1024 1024))

(def loopback-hosts #{"127.0.0.1" "localhost" "::1" "[::1]"})

(defn exposure-refusal
  "nil when binding `bind` with `tokens` is safe, else why it is not."
  [bind tokens]
  (when (and (not (loopback-hosts bind)) (empty? tokens))
    (answer/refusal :http/exposed
                    (str "refusing to listen on " bind " without tokens: anyone who reaches the port "
                         "would act on the app. Configure :tokens, or bind 127.0.0.1 and tunnel."))))

(defn- respond
  ([status body] (respond status body {}))
  ([status body headers]
   {:status status
    :headers (merge (when body {"Content-Type" "application/json"}) headers)
    :body (when body (json/write body))}))

(defn- header [req k] (get (:headers req) (str/lower-case k)))

(defn- authenticate [cfg req]
  (if (seq (:tokens cfg))
    (auth/authenticate (:tokens cfg) (auth/bearer (header req "authorization")))
    (answer/ok (:anonymous cfg {:actor "local" :scopes #{:read :write}}))))

(defn- origin-ok? [cfg req]
  (let [o (header req "origin")]
    (or (nil? o) (contains? (set (:allowed-origins cfg)) o))))

(defn- post-mcp [srv state cfg req grant]
  (let [msg (try (json/read-json (:body req)) (catch Exception _not-json ::unparseable))]
    (cond
      (= ::unparseable msg)
      (respond 400 (p/error nil :parse "body is not JSON"))

      (= "initialize" (:method msg))
      (let [sid ((:new-id cfg #(str (random-uuid))))
            session (s/open-session sid grant)
            {:keys [response] next-session :session} (server/handle srv session msg)]
        (swap! state assoc-in [:sessions sid] next-session)
        (respond 200 response {"Mcp-Session-Id" sid}))

      :else
      (let [sid     (header req "mcp-session-id")
            session (get-in @state [:sessions sid])]
        (if (or (nil? session) (not (s/owned-by? session grant)))
          (respond 404 (p/error (:id msg) :invalid-request "unknown session: initialize first"))
          (let [{:keys [response] next-session :session} (server/handle srv session msg)]
            (swap! state assoc-in [:sessions sid] next-session)
            (if response (respond 200 response) (respond 202 nil))))))))

(defn route
  "Response map {:status :headers :body} for request map {:method :path
   :headers (lowercase keys) :body}. `cfg`: :tokens, :allowed-origins,
   :anonymous, :new-id."
  [srv state cfg req]
  (cond
    (= "/health" (:path req))           (respond 200 {:status "ok"})
    (not= "/mcp" (:path req))           (respond 404 {:error "not found"})
    (not (origin-ok? cfg req))          (respond 403 {:error "origin not allowed"})
    :else
    (let [g (authenticate cfg req)]
      (if (answer/refused? g)
        (respond 401 {:error (get-in g [:error :message])} {"WWW-Authenticate" "Bearer"})
        (case (:method req)
          "POST"   (post-mcp srv state cfg req (:ok g))
          "DELETE" (let [sid (header req "mcp-session-id")
                         ses (get-in @state [:sessions sid])]
                     (if (and ses (s/owned-by? ses (:ok g)))
                       (do (swap! state update :sessions dissoc sid) (respond 204 nil))
                       (respond 404 {:error "unknown session"})))
          (respond 405 {:error "use POST (this server offers no event stream)"} {"Allow" "POST, DELETE"}))))))

;; =============================================================================
;; JDK adapter (Boundary)
;; =============================================================================

(defn handle-request
  "Response map for request map `req` as every HTTP adapter hands it over:
   an oversized body is refused (413), any failure is a 500, never a
   dropped connection."
  [srv state cfg req]
  (try
    (if (:too-big req)
      (respond 413 {:error "request body over 1 MiB"})
      (route srv state cfg req))
    (catch Exception e
      (respond 500 {:error (or (ex-message e) "internal error")}))))
