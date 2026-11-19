(ns autonomous.mcp.http
  "MCP over HTTP (streamable HTTP): the transport for remote agents and
   swarms. POST carries one JSON-RPC message; GET opens the session's
   server stream (Server-Sent Events), on which the server pushes
   notifications/tools/list_changed when the tools the session sees change:
   an app of the fleet goes down, comes back, or changes its tools.

   Security, in order, on every request:
   1. exposure: a non-loopback bind without tokens refuses to START.
   2. Origin: a request carrying an Origin header not in :allowed-origins is
      refused (403), which defeats DNS rebinding from a browser.
   3. authentication: with tokens configured, a bearer token is required and
      names the actor and scopes (401 otherwise). Without tokens (loopback
      only) every caller is the configured anonymous grant.
   4. session ownership: a session id is honoured only for the actor that
      opened it (404 otherwise, as for an unknown session). The same holds
      for its stream.
   5. bounded sessions: an idle session is forgotten, and the session
      table never holds more than :max-sessions.
   6. bounded streams: one stream per session (a new GET replaces the old
      one), at most :max-streams in all (503 past it), and a stream ends
      with its session.

   `route` and `handle-request` are pure over request maps and a state
   atom, shared by every runtime. A stream is answered as data ({:stream
   descriptor}); the socket adapter that holds the connection hands a sink
   ({:send! :close!}) to `attach-stream!`, and `start-pusher!` writes to the
   sinks. The adapters live in autonomous.mcp.http.jdk (JVM) and
   autonomous.mcp.http.kit (babashka, http-kit), chosen by
   autonomous.mcp.http.start; `ring-handler` is the same pipeline as a Ring
   handler for a host that composes it into its own stack."
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

(def default-anonymous
  "The grant of every caller of a server without tokens, unless the
   configuration names another (:anonymous, which `autonomous serve` builds
   from AUTONOMOUS_ACTOR or --actor)."
  {:actor "local" :scopes #{:read :write}})

(defn- authenticate [cfg req]
  (if (seq (:tokens cfg))
    (auth/authenticate (:tokens cfg) (auth/bearer (header req "authorization")))
    (answer/ok (:anonymous cfg default-anonymous))))

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
  "A session unused this long is forgotten (the agent initializes again).
   An open stream counts as use."
  (* 60 60 1000))

(def default-max-sessions
  "Most sessions one server keeps; past it the least recently used goes."
  1024)

(def default-max-streams
  "Most server streams open at once, over all sessions."
  64)

(def default-watch-ms
  "How often the pusher looks at the tools each stream's session sees."
  2000)

(def default-keepalive-ms
  "Longest a stream stays silent: past it the pusher writes an SSE comment,
   which keeps proxies from timing the stream out and finds dead clients."
  15000)

(defn prune-sessions
  "Session state `st` ({:sessions {sid session} :touched {sid millis}
   :streams {sid stream}}) without the sessions idle for `idle-ms` or more
   at `now`, then, past `max-sessions`, without the least recently used. A
   session with no touch time is dropped, and a dropped session's stream
   with it. Pure."
  [st now idle-ms max-sessions]
  (let [live (into {} (filter (fn [[_ t]] (< (- now t) idle-ms))) (:touched st))
        kept (if (> (count live) max-sessions)
               (into {} (take max-sessions) (sort-by (fn [[sid t]] [(- t) sid]) live))
               live)]
    (cond-> (assoc st
                   :sessions (select-keys (:sessions st) (keys kept))
                   :touched  kept)
      (contains? st :streams) (update :streams select-keys (keys kept)))))

;; =============================================================================
;; Streams (GET /mcp): Server-Sent Events, one per session
;; =============================================================================

(def sse-headers
  {"Content-Type"      "text/event-stream"
   "Cache-Control"     "no-cache"
   "X-Accel-Buffering" "no"})

(defn sse-event
  "The SSE text of JSON-RPC message `msg`: one `data:` line per line of its
   JSON, then a blank line."
  [msg]
  (str (str/join (map #(str "data: " % "\n") (str/split-lines (json/write msg)))) "\n"))

(def sse-open ": stream open\n\n")
(def sse-keepalive ": keepalive\n\n")

(def list-changed (p/notification "notifications/tools/list_changed" nil))

(defn- close-sink! [stream]
  (try ((get-in stream [:sink :close!])) (catch Exception _already-closed nil)))

(defn- dropped-streams
  "Streams in state `before` that are gone or replaced in state `after`."
  [before after]
  (keep (fn [[sid st]] (when (not= (:id st) (get-in after [:streams sid :id])) st))
        (:streams before)))

(defn- update-state!
  "swap! `state` with `f` and close the sinks of the streams it dropped."
  [state f & args]
  (let [[before after] (apply swap-vals! state f args)]
    (run! close-sink! (dropped-streams before after))
    after))

(defn- accepts-sse? [req]
  (str/includes? (str (header req "accept")) "text/event-stream"))

(defn- fingerprint-of
  "Tool fingerprint `session` sees now, ::unseen when it cannot be had."
  [srv session]
  (try (server/tools-fingerprint srv session)
       (catch Exception _probe-failed ::unseen)))

(defn- get-stream
  "Response to GET /mcp: the stream descriptor for an owned session, which
   the adapter turns into a held connection with `attach-stream!`."
  [srv state cfg req grant now]
  (let [sid     (header req "mcp-session-id")
        session (get-in @state [:sessions sid])
        others  (count (dissoc (:streams @state) sid))]
    (cond
      (not (accepts-sse? req))
      (respond 406 {:error "GET /mcp opens an event stream: send Accept: text/event-stream"})

      (or (nil? session) (not (s/owned-by? session grant)))
      (respond 404 {:error "unknown session: initialize first"})

      (>= others (:max-streams cfg default-max-streams))
      (respond 503 {:error "too many open streams"} {"Retry-After" "30"})

      :else
      (do (swap! state assoc-in [:touched sid] now)
          {:status  200
           :headers sse-headers
           :stream  {:session sid :actor (:actor grant) :at now
                    :fingerprint (fingerprint-of srv session)}}))))

(defn attach-stream!
  "Hold stream `descriptor` (the :stream of a GET response) open on `sink`
   {:send! (fn [text]) :close! (fn [])}: it replaces the session's earlier
   stream, which is closed. Answers the stream id, or nil (and closes the
   sink) when the session is gone or no longer the descriptor's actor's.
   `send!` may throw; the pusher then drops the stream."
  [state {:keys [session actor fingerprint at]} sink]
  (let [id     (str (random-uuid))
        stream {:id id :sink sink :seen fingerprint :last-at (or at 0)}
        after  (update-state! state
                              (fn [st]
                                (let [ses (get-in st [:sessions session])]
                                  (if (and ses (= actor (:session/actor ses)))
                                    (assoc-in st [:streams session] stream)
                                    st))))]
    (if (= id (get-in after [:streams session :id]))
      (do (try ((:send! sink) sse-open) (catch Exception _gone nil))
          id)
      (do (close-sink! stream) nil))))

(defn detach-stream!
  "Forget stream `id` of session `sid` (its connection ended); a newer
   stream of the session stays."
  [state sid id]
  (update-state! state (fn [st] (if (= id (get-in st [:streams sid :id]))
                                  (update st :streams dissoc sid)
                                  st))))

(defn close-streams!
  "Close every stream (the server is stopping)."
  [state]
  (update-state! state assoc :streams {}))

(defn- fp-key
  "What a session's tool fingerprint depends on: its scopes and pins."
  [session]
  (select-keys session [:session/scopes :session/runtime :session/runtimes]))

(defn push-tick!
  "One look by the pusher: for every open stream, the tool fingerprint its
   session sees (computed once per scopes and pins), and
   notifications/tools/list_changed on the stream when it moved; a comment
   when the stream was silent :keepalive-ms. A stream whose send fails, or
   whose session is gone, is closed. An open stream keeps its session
   alive."
  [srv state cfg]
  (let [now       ((:clock cfg #(System/currentTimeMillis)))
        keepalive (:keepalive-ms cfg default-keepalive-ms)
        st        @state
        cache     (atom {})
        fps       (fn [session]
                    (let [k (fp-key session)]
                      (if (contains? @cache k)
                        (get @cache k)
                        (get (swap! cache assoc k (fingerprint-of srv session)) k))))]
    (doseq [[sid {:keys [id sink seen last-at]}] (:streams st)]
      (if-let [session (get-in st [:sessions sid])]
        (let [fp       (fps session)
              changed? (and (not= ::unseen fp) (not= ::unseen seen) (not= fp seen))
              text     (cond changed?                           (sse-event list-changed)
                             (>= (- now last-at) keepalive)     sse-keepalive)
              sent?    (or (nil? text)
                           (try ((:send! sink) text) true
                                (catch Exception _client-gone false)))]
          (if sent?
            (swap! state (fn [s]
                           (if (= id (get-in s [:streams sid :id]))
                             (-> s
                                 (update-in [:streams sid] merge
                                            (cond-> {}
                                              (not= ::unseen fp) (assoc :seen fp)
                                              text               (assoc :last-at now)))
                                 (assoc-in [:touched sid] now))
                             s)))
            (detach-stream! state sid id)))
        (detach-stream! state sid id)))))

(defn start-pusher!
  "Background loop (a future: every bridge runtime has them) running
   `push-tick!` every :watch-ms. Answers a fn that stops it."
  [srv state cfg]
  (let [running? (atom true)
        watch-ms (:watch-ms cfg default-watch-ms)]
    (future
      (while @running?
        (try
          (Thread/sleep (long watch-ms))
          (when @running? (push-tick! srv state cfg))
          (catch InterruptedException _stopping (reset! running? false))
          (catch Exception e
            (binding [*out* *err*] (println "[autonomous] stream pusher:" (ex-message e)))))))
    (fn [] (reset! running? false))))

;; =============================================================================
;; Routing
;; =============================================================================

(defn route
  "Response map {:status :headers :body} for request map {:method :path
   :headers (lowercase keys) :body}; a GET that opens a stream answers
   {:status 200 :headers sse-headers :stream descriptor} instead of a body.
   `cfg`: :tokens, :allowed-origins, :anonymous, :new-id, :clock (millis
   fn), :session-idle-ms, :max-sessions, :max-streams. Sessions past their
   idle time are forgotten (and their streams closed) before the request is
   served."
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
          (update-state! state prune-sessions now
                         (:session-idle-ms cfg default-session-idle-ms)
                         (:max-sessions cfg default-max-sessions))
          (case (:method req)
            "POST"   (post-mcp srv state cfg req (:ok g) now)
            "GET"    (get-stream srv state cfg req (:ok g) now)
            "DELETE" (let [sid (header req "mcp-session-id")
                           ses (get-in @state [:sessions sid])]
                       (if (and ses (s/owned-by? ses (:ok g)))
                         (do (update-state! state #(-> %
                                                       (update :sessions dissoc sid)
                                                       (update :touched dissoc sid)
                                                       (update :streams dissoc sid)))
                             (respond 204 nil))
                         (respond 404 {:error "unknown session"})))
            (respond 405 {:error "use POST, GET (event stream) or DELETE"}
                     {"Allow" "GET, POST, DELETE"})))))))

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

(defn new-state
  "Empty state of one MCP HTTP endpoint."
  []
  (atom {:sessions {} :touched {} :streams {}}))

;; =============================================================================
;; Ring
;; =============================================================================

(defn ring->request
  "Request map of Ring request `ring-req` (header names lower case, as Ring
   has them)."
  [{:keys [request-method uri headers body]}]
  (let [text (cond (string? body) body
                   body           (slurp body)
                   :else          "")]
    {:method  (str/upper-case (name request-method))
     :path    uri
     :headers (into {} (map (fn [[k v]] [(str/lower-case (name k)) v])) headers)
     :body    text
     :too-big (> (count text) max-body-bytes)}))

(defn ->ring
  "Ring response of a (non-stream) response map."
  [{:keys [status headers body]}]
  {:status status :headers (or headers {}) :body body})

(defn ring-handler
  "The MCP endpoint as a synchronous Ring handler over `srv`, `state` (see
   `new-state`) and `cfg` (as `route`), for a host that serves it from its
   own stack. It answers every path `route` does (/mcp and /health), so a
   host routes those paths to it. A stream needs a held connection, which
   Ring does not standardise: `stream-response` (fn [ring-req response] ->
   Ring response) builds it for the host's server (see
   autonomous.mcp.http.kit for http-kit); without one GET answers 405 and
   the agent works by request/response alone. The caller runs
   `start-pusher!` on the same state."
  ([srv state cfg] (ring-handler srv state cfg nil))
  ([srv state cfg {:keys [stream-response]}]
   (fn [ring-req]
     (let [resp (handle-request srv state cfg (ring->request ring-req))]
       (cond
         (nil? (:stream resp)) (->ring resp)
         stream-response       (stream-response ring-req resp)
         :else                 (->ring (respond 405 {:error "this host offers no event stream: use POST"}
                                                {"Allow" "POST, DELETE"})))))))
