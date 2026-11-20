(ns autonomous.bridge.http.channel
  "The channel stratum: a defrecord over one remote app's /mcp.

   A listing is cached per agent (the remote filters it by the actor's
   scopes) until a notifications/tools/list_changed on that agent's event
   stream, or the ttl, says otherwise. Concurrent looks by the SAME agent
   share its one fetch in flight; each agent fetches on its own, so a down
   remote costs one attempt per agent looking.

   An MCP session belongs to one agent and is renewed the single time a
   remote reports it forgotten (a restart); the renewed session opens a new
   stream. A stream that ends while its session lives is not reopened:
   until the session is renewed that agent's listing refreshes on the ttl
   only.

   Every method answers an Answer; nothing here throws."
  (:require [autonomous.bridge.http :as http]
            [autonomous.bridge.http.session :as ses]
            [autonomous.bridge.port :as port]
            [autonomous.bridge.rpc :as rpc]
            [autonomous.domain.answer :as answer]))

(def default-ttl-ms 5000)

(defn- now [] (System/currentTimeMillis))

(defn new-state
  "Empty channel state: MCP sessions, listings and session locks by actor."
  [] {:sessions (atom {}) :slots (atom {}) :locks (atom {})})

(defn- actor-lock
  "The lock object of `actor`, created once: one session open or renew of
   an actor runs at a time."
  [ch actor]
  (get (swap! (:locks ch) #(if (contains? % actor) % (assoc % actor (Object.)))) actor))

(def empty-slot
  "A cache slot with no listing, stale, and no fetch in flight. :revision
   counts the list_changed events seen, so a fetch knows one arrived
   while it ran."
  {:manifest nil :at 0 :stale? true :fetching nil :revision 0})

(defn mark-stale
  "Pure: slot `sl` after a list_changed: stale, its revision moved on."
  [sl]
  (-> sl (assoc :stale? true) (update :revision inc)))

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
  [ch client credentials target actor]
  (when-let [old (get @(:sessions ch) actor)]
    (swap! (:sessions ch) dissoc actor)
    (when-let [close! (:close! old)] (try (close!) (catch Exception _closed nil)))
    (ses/close-session! client credentials target {:actor actor} old)))

(defn- forget-stream!
  "Forget `actor`'s stream (the adapter is closing it)."
  [ch actor]
  (swap! (:sessions ch) update actor dissoc :close!))

(defn- listen!
  "Hold `ses`'s event stream: a list_changed marks `actor`'s listing stale."
  [ch client credentials target grant ses]
  (let [actor  (:actor grant)
        on-msg (fn [msg] (when (rpc/list-changed? msg) (swap! (slot ch actor) mark-stale)))
        r      (ses/open-stream! client credentials target grant ses on-msg #(forget-stream! ch actor))]
    (when (answer/ok? r) (swap! (:sessions ch) update actor merge (:ok r)))))

(defn- open-session!
  "Ok of a session of `grant` on this remote, its stream held."
  [ch client credentials target grant]
  (let [r (ses/open-session! client credentials target grant (:timeout-ms target http/default-timeout-ms))]
    (when (answer/ok? r)
      (swap! (:sessions ch) assoc (:actor grant) (:ok r))
      (listen! ch client credentials target grant (:ok r)))
    r))

(defn- current-session
  "Ok of `actor`'s live session, or nil when it has none."
  [ch actor]
  (some-> (get @(:sessions ch) actor) answer/ok))

(defn- session!
  "Answer of `grant`'s live session, opened when there is none. The open
   runs under the actor's lock and re-checks there, so concurrent looks by
   one actor open one session."
  [ch client credentials target grant]
  (or (current-session ch (:actor grant))
      (locking (actor-lock ch (:actor grant))
        (or (current-session ch (:actor grant))
            (open-session! ch client credentials target grant)))))

(defn- renew!
  "Ok of a fresh session for `grant` in place of `gone`, the session the
   remote forgot. Under the actor's lock: when another look already
   renewed it, that session is reused, never renewed twice."
  [ch client credentials target grant gone]
  (locking (actor-lock ch (:actor grant))
    (let [now-ses (get @(:sessions ch) (:actor grant))]
      (if (and now-ses (not= (:id now-ses) (:id gone)))
        (answer/ok now-ses)
        (do (drop-session! ch client credentials target (:actor grant))
            (open-session! ch client credentials target grant))))))

;; =============================================================================
;; Listing
;; =============================================================================

(defn- list-on
  "Ok of {:tools :resources} the remote lists for `ses`."
  [client credentials target grant ses]
  (answer/bind
   (ses/round client credentials target grant ses (rpc/list-msg (rpc/next-id))
              (:timeout-ms target http/default-timeout-ms))
   (fn [reply]
     (answer/ok {:tools (vec (get-in reply [:result :tools]))
                 :resources (vec (get-in reply [:result :resources]))}))))

(defn stored
  "Pure: slot `sl` holding `manifest`, fetched at `at` from revision `rev`.
   It is fresh only when no list_changed moved the revision meanwhile."
  [sl manifest at rev]
  (assoc sl :manifest manifest :at at :stale? (not= rev (:revision sl))))

(defn- store!
  "Record `listing`, fetched from revision `rev`, in slot `sl`; answers the
   Manifest of that listing, the one this fetch saw."
  [sl listing target rev]
  (let [m (rpc/manifest-of (:target/id target) (http/shown-url target)
                           (:tools listing) (:resources listing))]
    (swap! sl stored m (now) rev)
    m))

;; =============================================================================
;; Calls
;; =============================================================================

(defn- with-session
  "Railway: run `f` of a live session as `grant`, renewed the single time
   the remote reports the session gone."
  [ch client credentials target grant f]
  (answer/bind (session! ch client credentials target grant)
               (fn [ses]
                 (let [r (f ses)]
                   (if (= :channel/session-gone (get-in r [:error :type]))
                     (answer/bind (renew! ch client credentials target grant ses) f)
                     r)))))

(defn- fetch!
  "Ok of the listing for `grant`; a remote refusal keeps its cause."
  [ch client credentials target grant]
  (with-session ch client credentials target grant #(list-on client credentials target grant %)))

(defn- ask
  "Answer of message `msg` on `target` as `grant`, renewing a forgotten session."
  [ch client credentials target grant msg]
  (with-session ch client credentials target grant
                #(ses/round client credentials target grant % (msg) (:timeout-ms target http/default-timeout-ms))))

(defn call!
  "Answer of the remote tool `tool` with `args` as `grant`."
  [ch client credentials target grant tool args]
  (answer/bind (ask ch client credentials target grant #(rpc/call-msg (rpc/next-id) tool args))
               rpc/answer-of))

(defn read!
  "Answer of the remote resource `uri` as `grant`: its first content entry."
  [ch client credentials target grant uri]
  (answer/bind (ask ch client credentials target grant #(rpc/read-msg (rpc/next-id) uri))
               #(answer/ok (first (get-in % [:result :contents])))))

;; =============================================================================
;; The agent-facing look
;; =============================================================================

(defn- fetch-into!
  "Answer of a fresh listing for `grant` stored in slot `slot*`, shared
   with concurrent looks through promise `p`. The revision is read before
   the fetch, so a list_changed during it leaves the slot stale."
  [ch client credentials target grant slot* p]
  (let [rev (:revision @slot*)]
    (try
      (deliver p (answer/let-answer [listing (fetch! ch client credentials target grant)]
                   (answer/ok (store! slot* listing target rev))))
      @p
      (finally (swap! slot* assoc :fetching nil)))))

(defn manifest!
  "Answer of `target`'s Manifest for `grant`, reused while fresh; a look
   during a fetch waits for that fetch."
  [ch client credentials target grant]
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
        (fetch-into! ch client credentials target grant slot* p)))))

;; =============================================================================
;; The channel
;; =============================================================================

(defn- grant-of
  "The grant request `req` speaks with ({:actor :scopes})."
  [req]
  {:actor (:actor req) :scopes (:scopes req)})

(defrecord HttpChannel [target client credentials ch]
  port/IRuntimeChannel
  (-send [this req _runtime-id]
    (let [g      (grant-of req)
          op     (:op req)
          remote (get rpc/remote-tools op)]
      (cond
        (= :manifest op) (manifest! ch client credentials target g)
        (= :call op)     (call! ch client credentials target g (:tool req) (:args req))
        (= :read op)     (read! ch client credentials target g (:uri req))
        remote           (call! ch client credentials target g remote (rpc/remote-args op req))
        :else            (answer/refusal :unknown-op
                                         (str "a remote MCP app serves manifest, call, read and the "
                                              "built-in tools; " op " is not one of them")
                                         {:known (vec (keys rpc/remote-tools))}))))

  port/IRuntimeDirectory
  (-runtimes [_]
    (answer/ok [{:runtime-id "remote" :runtime-kind :http
                 :runtime-label (str "remote mcp at " (http/host-label target))}])))

(defn channel
  "An HttpChannel for MCP-over-HTTP `target`: one session and listing per
   agent. `client` overrides its IHttpClient; :credential-source overrides
   the environment boundary that resolves configured token references."
  ([target] (channel target nil))
  ([target client]
   (->HttpChannel target (or client (:http/client target) (http/default-client))
                  (http/default-credential-source target) (new-state))))
