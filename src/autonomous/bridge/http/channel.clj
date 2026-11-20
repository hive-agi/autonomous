(ns autonomous.bridge.http.channel
  "Remote MCP channel: one session, event stream and listing cache per
   calling actor. tools/list_changed invalidates only that actor's cache.
   Agent-facing operations answer an Answer through the injected ports."
  (:require [autonomous.bridge.http :as http]
            [autonomous.bridge.http.session :as ses]
            [autonomous.bridge.port :as port]
            [autonomous.bridge.rpc :as rpc]
            [autonomous.domain.answer :as answer]))

(defn- now [] (System/currentTimeMillis))

(defn new-state
  "Empty channel state: actor sessions, listing slots and session locks."
  []
  {:sessions (atom {}) :slots (atom {}) :locks (atom {})})

(def empty-slot
  "An empty, stale listing slot at revision zero."
  {:manifest nil :at 0 :stale? true :revision 0 :fetching nil})

(defn- slot
  "The listing cache atom of `actor`, created empty."
  [channel actor]
  (let [slots (get-in channel [:runtime-state :slots])]
    (get (swap! slots #(if (contains? % actor) % (assoc % actor (atom empty-slot)))) actor)))

(defn- fresh?
  "True when `slot` has a fresh listing within `ttl-ms`."
  [slot ttl-ms]
  (and (some? (:manifest slot))
       (not (:stale? slot))
       (< (- (now) (:at slot)) ttl-ms)))

;; =============================================================================
;; Session of one agent, and its event stream
;; =============================================================================

(defn- drop-session!
  "Boundary: forget and close the session currently owned by `actor`."
  [{:keys [client credentials target] :as channel} actor]
  (let [sessions (get-in channel [:runtime-state :sessions])]
    (when-let [old (get @sessions actor)]
      (swap! sessions dissoc actor)
      (when-let [close! (:close! old)] (close!))
      (ses/close-session! client credentials target {:actor actor} old))))

(defn- forget-stream!
  "Mark `actor`'s stream closed and its cached listing stale."
  [channel actor]
  (swap! (get-in channel [:runtime-state :sessions]) update actor dissoc :close!)
  (swap! (slot channel actor) #(-> % (assoc :stale? true) (update :revision inc))))

(defn- invalidate!
  "Advance `actor`'s listing revision after a remote tools/list_changed."
  [channel actor msg]
  (when (rpc/list-changed? msg)
    (swap! (slot channel actor) #(-> % (assoc :stale? true) (update :revision inc)))))

(defn- listen!
  "Boundary: attach `session`'s stream to its actor's cache invalidation."
  [{:keys [client credentials target] :as channel} grant session]
  (let [actor (:actor grant)]
    (ses/open-stream! client credentials target grant session
                      #(invalidate! channel actor %)
                      #(forget-stream! channel actor))))

(declare open-session!)

(defn- remember-stream!
  "Hold the newly opened stream only while `session` is still current."
  [channel actor session answer*]
  (answer/bind answer*
               (fn [{:keys [close!]}]
                 (let [sessions (get-in channel [:runtime-state :sessions])]
                   (when close! (swap! sessions assoc-in [actor :close!] close!))
                   (answer/ok session)))))

(defn- ensure-stream!
  "Boundary: reopen a live session's closed stream, without changing identity."
  [channel grant]
  (let [sessions (get-in channel [:runtime-state :sessions])
        session  (get @sessions (:actor grant))]
    (when-not (:close! session)
      (remember-stream! channel (:actor grant) session
                        (listen! channel grant session)))))

(defn- open-session!
  "Ok of `grant`'s newly opened session and its held event stream."
  [{:keys [client credentials target] :as channel} grant]
  (answer/let-answer
   [session (ses/open-session! client credentials target grant
                               (:timeout-ms target http/default-timeout-ms))]
   (swap! (get-in channel [:runtime-state :sessions]) assoc (:actor grant) session)
   (ensure-stream! channel grant)
   (answer/ok session)))

(defn- actor-lock
  "The mutual-exclusion value of one actor's session transitions."
  [channel actor]
  (let [locks (get-in channel [:runtime-state :locks])]
    (get (swap! locks #(if (contains? % actor) % (assoc % actor (Object.)))) actor)))

(defn- session!
  "Ok of `grant`'s existing session, or one opened under its actor lock."
  [channel grant]
  (let [actor (:actor grant)]
    (locking (actor-lock channel actor)
      (if-let [session (get-in channel [:runtime-state :sessions actor])]
        (do (ensure-stream! channel grant) (answer/ok session))
        (open-session! channel grant)))))

(defn- renew!
  "Ok of `grant`'s new session after the remote forgot its predecessor."
  [channel grant]
  (locking (actor-lock channel (:actor grant))
    (drop-session! channel (:actor grant))
    (open-session! channel grant)))

;; =============================================================================
;; Listing
;; =============================================================================

(defn- list-on
  "Ok of the remote tools and resources visible in `session`."
  [{:keys [client credentials target]} grant session]
  (answer/bind
   (ses/round client credentials target grant session (rpc/list-msg (rpc/next-id))
              (:timeout-ms target http/default-timeout-ms))
   (fn [reply]
     (answer/ok {:tools (vec (get-in reply [:result :tools]))
                 :resources (vec (get-in reply [:result :resources]))}))))

(defn- store!
  "Cache `listing` fresh only if its slot revision still matches `revision`."
  [{:keys [target]} slot revision listing]
  (swap! slot
         #(if (= revision (:revision %))
            (assoc % :manifest (rpc/manifest-of (:target/id target) (:url target)
                                                (:tools listing) (:resources listing))
                   :at (now) :stale? false)
            %))
  (:manifest @slot))

;; =============================================================================
;; Calls
;; =============================================================================

(defn- retry-forgotten
  "Answer of `f`, renewed once after a :channel/session-gone refusal."
  [channel grant f answer*]
  (if (= :channel/session-gone (get-in answer* [:error :type]))
    (answer/bind (renew! channel grant) f)
    answer*))

(defn- with-session
  "Answer railway over `grant`'s live session, with one renewal."
  [channel grant f]
  (answer/bind (session! channel grant)
               #(retry-forgotten channel grant f (f %))))

(defn- fetch!
  "Ok of `grant`'s listing from the channel's remote."
  [channel grant]
  (with-session channel grant #(list-on channel grant %)))

(defn- ask
  "Answer of `message-fn` on the remote as `grant`."
  [{:keys [client credentials target] :as channel} grant message-fn]
  (with-session channel grant
    #(ses/round client credentials target grant % (message-fn)
                (:timeout-ms target http/default-timeout-ms))))

(defn- call!
  "Answer of remote `tool` called with `args` as `grant`."
  [channel grant tool args]
  (answer/bind (ask channel grant #(rpc/call-msg (rpc/next-id) tool args))
               rpc/answer-of))

(defn- read!
  "Answer of remote resource `uri` read as `grant`."
  [channel grant uri]
  (answer/bind (ask channel grant #(rpc/read-msg (rpc/next-id) uri))
               #(answer/ok (first (get-in % [:result :contents])))))

;; =============================================================================
;; The agent-facing look
;; =============================================================================

(defn- cached-manifest
  "Answer of a fresh listing in `slot`, or nil when it must be refreshed."
  [target slot]
  (when (fresh? slot (:ttl-ms target http/manifest-ttl-ms))
    (answer/ok (:manifest slot))))

(defn- refresh-manifest!
  "Boundary: fetch and cache `grant`'s listing at the observed revision."
  [channel grant slot*]
  (let [revision (:revision @slot*)
        session  (session! channel grant)]
    (ensure-stream! channel grant)
    (answer/let-answer [session session
                        listing (list-on channel grant session)]
      (answer/ok (store! channel slot* revision listing)))))

(defn- await-manifest
  "Answer of the actor's existing fetch, bounded by the target timeout."
  [{:keys [target]} fetch]
  (deref fetch (:timeout-ms target http/default-timeout-ms)
         (answer/refusal :channel/timeout "no listing in time")))

(defn manifest!
  "Answer of the remote Manifest visible to `grant`, with one shared fetch
   per actor and revision-safe invalidation on list_changed."
  [{:keys [target] :as channel} grant]
  (let [slot* (slot channel (:actor grant))
        state @slot*]
    (or (cached-manifest target state)
        (if-let [fetch (:fetching state)]
          (await-manifest channel fetch)
          (locking slot*
            (or (cached-manifest target @slot*)
                (if-let [fetch (:fetching @slot*)]
                  (await-manifest channel fetch)
                  (let [fetch (promise)]
                    (swap! slot* assoc :fetching fetch)
                    (try
                      (let [result (refresh-manifest! channel grant slot*)]
                        (deliver fetch result)
                        result)
                      (finally (swap! slot* assoc :fetching nil)))))))))))

;; =============================================================================
;; The channel
;; =============================================================================

(defn- grant-of
  "The grant request `req` speaks with ({:actor :scopes})."
  [req]
  {:actor (:actor req) :scopes (:scopes req)})

(defn- dispatch
  "Answer of channel request `req`, using the remote's MCP operation mapping."
  [channel {:keys [op tool args uri] :as req}]
  (let [grant  (grant-of req)
        remote (get rpc/remote-tools op)]
    (cond
      (= :manifest op) (manifest! channel grant)
      (= :call op)     (call! channel grant tool args)
      (= :read op)     (read! channel grant uri)
      remote           (call! channel grant remote (rpc/remote-args op req))
      :else            (answer/refusal :unknown-op
                                       "a remote MCP app serves manifest, call, read and built-in tools"
                                       {:op op :known (vec (keys rpc/remote-tools))}))))

(defrecord HttpChannel [target client credentials runtime-state]
  port/IRuntimeChannel
  (-send [this req _runtime-id] (dispatch this req))

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
