(ns autonomous.bridge.http.channel
  "The channel stratum: a defrecord over one remote app's /mcp.

   A listing is cached per agent (the remote filters it by the actor's
   scopes) until a notifications/tools/list_changed on that agent's event
   stream, or the ttl, says otherwise. Concurrent looks share the one fetch
   in flight, so a down remote costs one attempt, not one per agent.

   An MCP session belongs to one agent and is renewed the single time a
   remote reports it forgotten (a restart). Streams die with their session
   and are reopened on the next look, so a remote that comes back is picked
   up with no restart here.

   Every method answers an Answer; nothing here throws."
  (:require [autonomous.bridge.http :as http]
            [autonomous.bridge.http.session :as ses]
            [autonomous.bridge.port :as port]
            [autonomous.bridge.rpc :as rpc]
            [autonomous.domain.answer :as answer]))

(def default-ttl-ms 5000)

(defn- now [] (System/currentTimeMillis))

(defn new-state
  "Empty channel state: MCP sessions and listings by actor."
  [] {:sessions (atom {}) :slots (atom {})})

(def empty-slot
  "A cache slot with no listing, stale, and no fetch in flight."
  {:manifest nil :at 0 :stale? true :fetching nil})

(defn- slot
  "The cache slot (an atom) of `actor`, created empty."
  [ch actor]
  (get (swap! (:slots ch) #(if (contains? % actor) % (assoc % actor (atom empty-slot)))) actor))

(defn- fresh?
  "True while slot `sl` may be reused: fetched, not stale, within `ttl-ms`."
  [sl ttl-ms]
  (and (some? (:manifest sl)) (not (:stale? sl)) (< (- (now) (:at sl)) ttl-ms)))

;; =============================================================================
;; Session of one agent, and its event stream
;; =============================================================================

(defn- drop-session!
  "Forget and release `actor`'s session; its stream closes with it."
  [ch client target actor]
  (when-let [old (get @(:sessions ch) actor)]
    (swap! (:sessions ch) dissoc actor)
    (when-let [close! (:close! old)] (try (close!) (catch Exception _closed nil)))
    (ses/close-session! client target {:actor actor} old)))

(defn- forget-stream!
  "Forget `actor`'s stream (the adapter is closing it)."
  [ch actor]
  (swap! (:sessions ch) update actor dissoc :close!))

(defn- listen!
  "Hold `ses`'s event stream: a list_changed marks `actor`'s listing stale."
  [ch client target grant ses]
  (let [actor  (:actor grant)
        on-msg (fn [msg] (when (rpc/list-changed? msg) (swap! (slot ch actor) assoc :stale? true)))
        r      (ses/open-stream! client target grant ses on-msg #(forget-stream! ch actor))]
    (when (answer/ok? r) (swap! (:sessions ch) update actor merge (:ok r)))))

(defn- open-session!
  "Ok of a session of `grant` on this remote, its stream held."
  [ch client target grant]
  (let [r (ses/open-session! client target grant (:timeout-ms target http/default-timeout-ms))]
    (when (answer/ok? r)
      (swap! (:sessions ch) assoc (:actor grant) (:ok r))
      (listen! ch client target grant (:ok r)))
    r))

(defn- session!
  "Answer of `grant`'s live session, opened when there is none: Ok of it,
   else the open's refusal (so a down remote keeps its real cause)."
  [ch client target grant]
  (if-let [ses (get @(:sessions ch) (:actor grant))]
    (answer/ok ses)
    (open-session! ch client target grant)))

(defn- renew!
  "Ok of a fresh session for `grant`, the old one dropped: what a remote
   that forgot the session needs."
  [ch client target grant]
  (drop-session! ch client target (:actor grant))
  (open-session! ch client target grant))

;; =============================================================================
;; Listing
;; =============================================================================

(defn- list-on
  "Ok of {:tools :resources} the remote lists for `ses` (Ok of nil
   resources when it has none)."
  [client target grant ses]
  (let [r (ses/round client target grant ses (rpc/list-msg (rpc/next-id))
                     (:timeout-ms target http/default-timeout-ms))]
    (if (answer/refused? r)
      r
      (answer/ok {:tools (vec (get-in r [:ok :result :tools]))
                  :resources (vec (get-in r [:ok :result :resources]))}))))

(defn- store!
  "Record listing `r` as a fresh cache slot for `target`."
  [sl r target]
  (swap! sl assoc :manifest (rpc/manifest-of (:target/id target) (:url target)
                                             (:tools (:ok r)) (:resources (:ok r)))
         :at (now) :stale? false))

;; =============================================================================
;; Calls
;; =============================================================================

(defn- with-session
  "Railway: run `f` of a live session as `grant`, renewed the single time
   the remote reports the session gone (a restart)."
  [ch client target grant f]
  (answer/bind (session! ch client target grant)
               (fn [ses]
                 (let [r (f ses)]
                   (if (= :channel/session-gone (get-in r [:error :type]))
                     (answer/bind (renew! ch client target grant) f)
                     r)))))

(defn- fetch!
  "Ok of the listing for `grant`. A refusal keeps its cause (a down remote,
   a bad token)."
  [ch client target grant]
  (with-session ch client target grant #(list-on client target grant %)))

(defn- ask
  "Answer of message `msg` (a fn of the session) on `target` as `grant`: the
   railway with the session renewal around it."
  [ch client target grant msg]
  (with-session ch client target grant
                #(ses/round client target grant % (msg) (:timeout-ms target http/default-timeout-ms))))

(defn call!
  "Answer of the remote tool `tool` with `args` as `grant`."
  [ch client target grant tool args]
  (answer/bind (ask ch client target grant #(rpc/call-msg (rpc/next-id) tool args))
               rpc/answer-of))

(defn read!
  "Answer of the remote resource `uri` as `grant`: its first content entry."
  [ch client target grant uri]
  (answer/bind (ask ch client target grant #(rpc/read-msg (rpc/next-id) uri))
               #(answer/ok (first (get-in % [:result :contents])))))

;; =============================================================================
;; The agent-facing look
;; =============================================================================

(defn manifest!
  "Answer of `target`'s Manifest for `grant`, reused while fresh."
  [ch client target grant]
  (let [slot* (slot ch (:actor grant))
        st    @slot*
        wait  (:fetching st)]
    (cond
      (fresh? st (:ttl-ms target default-ttl-ms)) (answer/ok (:manifest st))
      (and wait (not (realized? wait)))
      (deref wait (:timeout-ms target http/default-timeout-ms)
             (answer/refusal :channel/timeout "no listing in time"))
      :else
      (let [p (promise)]
        (swap! slot* assoc :fetching p)
        (try
          (deliver p (answer/let-answer [listing (fetch! ch client target grant)]
                       (answer/ok (:manifest (store! slot* {:ok listing} target)))))
          @p
          (finally (swap! slot* assoc :fetching nil)))))))

;; =============================================================================
;; The channel
;; =============================================================================

(defn- grant-of
  "The grant request `req` speaks with ({:actor :scopes})."
  [req]
  {:actor (:actor req) :scopes (:scopes req)})

(defrecord HttpChannel [target client ch]
  port/IRuntimeChannel
  (-send [this req _runtime-id]
    (let [g      (grant-of req)
          op     (:op req)
          remote (get rpc/remote-tools op)]
      (cond
        (= :manifest op) (manifest! ch client target g)
        (= :call op)     (call! ch client target g (:tool req) (:args req))
        (= :read op)     (read! ch client target g (:uri req))
        remote           (call! ch client target g remote (rpc/remote-args op req))
        :else            (answer/refusal :unknown-op
                                         (str "a remote MCP app serves manifest, call, read and the "
                                              "built-in tools; " op " is not one of them")
                                         {:known (vec (keys rpc/remote-tools))}))))

  port/IRuntimeDirectory
  (-runtimes [_]
    (answer/ok [{:runtime-id "remote" :runtime-kind :http
                 :runtime-label (str "remote mcp at " (http/host-label target))}])))

(defn channel
  "An HttpChannel for MCP-over-HTTP `target` ({:target/kind :http :url ..
   :token-env ..}): one MCP session per agent, the listing cached. The HTTP
   port is `client`, else the target's :http/client, else this runtime's."
  ([target] (channel target nil))
  ([target client]
   (->HttpChannel target (or client (:http/client target) (http/default-client)) (new-state))))
