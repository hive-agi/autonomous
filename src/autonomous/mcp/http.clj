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
   5. bounded sessions: an idle session is forgotten, and the session
      table never holds more than :max-sessions.

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

(defn bind-refusal
  "The refusal for a socket adapter that could not listen on `bind`:`port`
   (taken, not permitted, unknown host), carrying the cause's message."
  [bind port cause]
  (answer/refusal :http/bind-failed
                  (str "cannot listen on " bind ":" port ": " (or (ex-message cause) (str cause)))))

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

(defn- post-mcp [srv state cfg req grant now]
  (let [msg (try (json/read-json (:body req)) (catch Exception _not-json ::unparseable))
        keep! (fn [sid session]
                (swap! state #(-> % (assoc-in [:sessions sid] session) (assoc-in [:touched sid] now))))]
    (cond
      (= ::unparseable msg)
      (respond 400 (p/error nil :parse "body is not JSON"))

      (= "initialize" (:method msg))
      (let [sid ((:new-id cfg #(str (random-uuid))))
            session (s/open-session sid grant)
            {:keys [response] next-session :session} (server/handle srv session msg)]
        (keep! sid next-session)
        (respond 200 response {"Mcp-Session-Id" sid}))

      :else
      (let [sid     (header req "mcp-session-id")
            session (get-in @state [:sessions sid])]
        (if (or (nil? session) (not (s/owned-by? session grant)))
          (respond 404 (p/error (:id msg) :invalid-request "unknown session: initialize first"))
          (let [{:keys [response] next-session :session} (server/handle srv session msg)]
            (keep! sid next-session)
            (if response (respond 200 response) (respond 202 nil))))))))

(def default-session-idle-ms
  "A session unused this long is forgotten (the agent initializes again)."
  (* 60 60 1000))

(def default-max-sessions
  "Most sessions one server keeps; past it the least recently used goes."
  1024)

(defn prune-sessions
  "Session state `st` ({:sessions {sid session} :touched {sid millis}})
   without the sessions idle for `idle-ms` or more at `now`, then, past
   `max-sessions`, without the least recently used. A session with no
   touch time is dropped. Pure."
  [st now idle-ms max-sessions]
  (let [live (into {} (filter (fn [[_ t]] (< (- now t) idle-ms))) (:touched st))
        kept (if (> (count live) max-sessions)
               (into {} (take max-sessions) (sort-by (fn [[sid t]] [(- t) sid]) live))
               live)]
    (assoc st
           :sessions (select-keys (:sessions st) (keys kept))
           :touched  kept)))

(defn route
  "Response map {:status :headers :body} for request map {:method :path
   :headers (lowercase keys) :body}. `cfg`: :tokens, :allowed-origins,
   :anonymous, :new-id, :clock (millis fn), :session-idle-ms,
   :max-sessions. Sessions past their idle time are forgotten before the
   request is served."
  [srv state cfg req]
  (cond
    (= "/health" (:path req))           (respond 200 {:status "ok"})
    (not= "/mcp" (:path req))           (respond 404 {:error "not found"})
    (not (origin-ok? cfg req))          (respond 403 {:error "origin not allowed"})
    :else
    (let [g   (authenticate cfg req)
          now ((:clock cfg #(System/currentTimeMillis)))]
      (if (answer/refused? g)
        (respond 401 {:error (get-in g [:error :message])} {"WWW-Authenticate" "Bearer"})
        (do
          (swap! state prune-sessions now
                 (:session-idle-ms cfg default-session-idle-ms)
                 (:max-sessions cfg default-max-sessions))
          (case (:method req)
            "POST"   (post-mcp srv state cfg req (:ok g) now)
            "DELETE" (let [sid (header req "mcp-session-id")
                           ses (get-in @state [:sessions sid])]
                       (if (and ses (s/owned-by? ses (:ok g)))
                         (do (swap! state #(-> % (update :sessions dissoc sid) (update :touched dissoc sid)))
                             (respond 204 nil))
                         (respond 404 {:error "unknown session"})))
            (respond 405 {:error "use POST (this server offers no event stream)"} {"Allow" "POST, DELETE"})))))))

;; =============================================================================
;; Adapter entry (Boundary): what every socket adapter calls
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
