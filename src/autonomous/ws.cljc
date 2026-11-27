(ns autonomous.ws
  "The runtime side of the websocket channel: one call at app start dials
   the bridge and keeps dialing.

     (autonomous.ws/connect! {:url   \"wss://bridge.example/autonomous/ws\"
                              :token \"aut_...\"})   ; a runtime token

   The app (a browser page, a node process, a JVM or babashka service)
   opens the socket, so production needs no nREPL and no inbound port.
   The bridge sends EDN requests; each is answered by
   autonomous.runtime.rpc/rpc-str and nothing else, so only the tools the
   app declared are reachable. A dropped socket is redialed with
   exponential backoff, under the same runtime id, so agents attached to
   this runtime stay attached. A refusal a retry cannot fix (bad token,
   unknown target) stops it.

   Options of `connect!`:
     :url         ws:// or wss:// URL of the bridge endpoint   [required]
     :token       runtime token (scope :runtime)               [required]
     :runtime-id  id in the bridge's directory   [default: random, per connect!]
     :label       human-readable label           [default: by platform]
     :kind        runtime kind keyword           [default: :browser :node :clj :bb]
     :backoff     {:base-ms 500 :max-ms 30000}
     :heartbeat   {:ping-ms 20000 :idle-ms 60000}: JVM and babashka ping a
                  quiet link and redial one silent for :idle-ms (a half-open
                  socket never closes on its own). Browsers cannot ping.
     :dial-ms     JVM and babashka: give up on a dial (TCP connect plus the
                  websocket upgrade) after this long and redial, so a
                  bridge that accepts the socket but never answers does not
                  hang the runtime                         [default: 10000]
     :on-status   (fn [status info]) on every change of status:
                  :connecting :hello :open :reconnecting :refused :stopped
     :websocket   ClojureScript: the WebSocket constructor  [default: global]"
  (:require [autonomous.domain.wire :as wire]
            [autonomous.runtime.rpc :as rpc])
  #?(:clj (:import (java.net URI)
                   (java.net.http HttpClient WebSocket WebSocket$Listener))))

(def default-backoff {:base-ms 500 :max-ms 30000})

(def default-heartbeat {:ping-ms 20000 :idle-ms 60000})

(def default-dial-ms 10000)

;; =============================================================================
;; Pure
;; =============================================================================

(defn backoff-ms
  "Delay before redial number `attempt` (0-based): `base-ms` doubling, at
   most `max-ms`, scaled by `jitter` in [0.5, 1] so a fleet that lost the
   bridge at once does not redial in lockstep."
  [attempt {:keys [base-ms max-ms]} jitter]
  (let [d (min max-ms (* base-ms (Math/pow 2 (min attempt 30))))]
    (long (* d (max 0.5 (min 1.0 jitter))))))

(defn idle-action
  "What a link needs at `now` (ms), given when it last heard a frame and
   last sent a ping: :dead after :idle-ms of silence, :ping after :ping-ms
   without a frame either way, else :idle."
  [now last-heard last-ping {:keys [ping-ms idle-ms]}]
  (cond
    (>= (- now last-heard) idle-ms)                  :dead
    (>= (- now (max last-heard last-ping)) ping-ms)  :ping
    :else                                            :idle))

(defn default-kind []
  #?(:bb   :bb
     :clj  :clj
     :cljs (if (exists? js/document) :browser :node)))

(defn- random-id []
  (str "rt-" (subs (str (random-uuid)) 0 8)))

(defn normalize
  "Options of `connect!` with defaults applied; throws ex-info when :url or
   :token is missing."
  [opts]
  (when-not (and (string? (:url opts)) (string? (:token opts)))
    (throw (ex-info "autonomous.ws/connect! needs :url and :token" {:keys (vec (keys opts))})))
  (merge {:backoff default-backoff
          :dial-ms default-dial-ms
          :kind    (default-kind)
          :label   (str (name (default-kind)) " runtime")
          :on-status (fn [_ _] nil)}
         opts
         {:runtime-id (or (:runtime-id opts) (random-id))
          :heartbeat  (merge default-heartbeat (:heartbeat opts))}))

(defn hello
  "The first frame: who this runtime is, and its token."
  [opts]
  {:ws/type :hello :token (:token opts) :runtime/id (:runtime-id opts)
   :runtime/kind (:kind opts) :runtime/label (:label opts)})

(defn reply
  "The frame answering bridge frame `frame`, or nil. A :request is answered
   by rpc-str alone: the bridge reaches declared tools, never eval."
  [frame]
  (when (= :request (:ws/type frame))
    {:ws/type :response :id (:id frame)
     :answer (if (string? (:request frame))
               (rpc/rpc-str (:request frame))
               (wire/print-edn {:error {:type :bad-request :message "a request is EDN text"}}))}))

(defn fatal?
  "True for a :refused frame a redial would only repeat."
  [frame]
  (and (= :refused (:ws/type frame))
       (contains? #{:auth/unauthorized :ws/unknown-target} (get-in frame [:error :type]))))

(defn read-frame
  "The frame map in `text`, or nil."
  [text]
  (let [f (try (wire/read-edn text) (catch #?(:clj Exception :cljs :default) _not-edn nil))]
    (when (and (map? f) (keyword? (:ws/type f))) f)))

;; =============================================================================
;; Platform sockets: (dial url {:on-open :on-text :on-close}) -> {:send! :close!}
;; =============================================================================

#?(:clj
   (defn- dial
     "java.net.http websocket (JVM and babashka). Text arriving in parts is
      joined; sends are serialized, as the API requires. Every frame,
      control frames included, calls `on-heard`; `:ping!` sends a ping. The
      TCP connect and the upgrade together are bounded by `dial-ms`: a dial
      still pending then closes (code 1006) so the caller redials, and a
      socket that opens after that is aborted."
     [url {:keys [on-open on-text on-close on-heard dial-ms]
           :or   {on-heard (fn [] nil) dial-ms default-dial-ms}}]
     (let [buf        (StringBuilder.)
           sock       (atom nil)
           closed?    (atom false)
           close-once (fn [code reason] (when (compare-and-set! closed? false true) (on-close code reason)))
           send!      (fn [text] (when-let [^WebSocket w @sock]
                                   (locking w (.join (.sendText w ^CharSequence text true)))))
           listener   (reify WebSocket$Listener
                        (onOpen [_ ws]
                          (if @closed?
                            (.abort ws)
                            (do (reset! sock ws) (.request ws 1) (on-open send!))))
                        (onPing [_ ws _message] (on-heard) (.request ws 1) nil)
                        (onPong [_ ws _message] (on-heard) (.request ws 1) nil)
                        (onText [_ ws data last?]
                          (on-heard)
                          (.append buf (str data))
                          (when last?
                            (let [t (str buf)] (.setLength buf 0) (on-text t)))
                          (.request ws 1)
                          nil)
                        (onClose [_ _ws code reason] (close-once code reason) nil)
                        (onError [_ _ws e] (close-once 1006 (or (ex-message e) (str e)))))]
       (let [client (-> (HttpClient/newBuilder)
                        (.connectTimeout (java.time.Duration/ofMillis (long dial-ms)))
                        (.build))]
         (-> client (.newWebSocketBuilder) (.buildAsync (URI. url) listener)
             (.orTimeout (long dial-ms) java.util.concurrent.TimeUnit/MILLISECONDS)
             (.exceptionally
              (reify java.util.function.Function
                (apply [_ e]
                  (let [cause (if (instance? java.util.concurrent.CompletionException e) (ex-cause e) e)]
                    ;; a stuck upgrade still holds its socket: shutting the
                    ;; client down (JDK 21+) releases it
                    (try (.shutdownNow client) (catch Throwable _old-jdk nil))
                    (close-once 1006 (if (instance? java.util.concurrent.TimeoutException cause)
                                       (str "no upgrade answer in " dial-ms " ms")
                                       (or (ex-message cause) (ex-message e) "could not connect"))))
                  nil)))))
       {:send!  send!
        :ping!  (fn [] (when-let [^WebSocket w @sock]
                         (locking w (.sendPing w (java.nio.ByteBuffer/allocate 0)))))
        :close! (fn [] (when-let [^WebSocket w @sock]
                         (try (.get (.sendClose w 1000 "bye") 2000 java.util.concurrent.TimeUnit/MILLISECONDS)
                              (catch Exception _ nil))
                         (.abort w)))}))

   :cljs
   (defn- dial
     "The platform WebSocket (browser; node 22+ has one built in)."
     [url {:keys [on-open on-text on-close]} ctor]
     (let [ws (new ctor url)]
       (set! (.-onopen ws) (fn [_] (on-open (fn [text] (.send ws text)))))
       (set! (.-onmessage ws) (fn [e] (on-text (str (.-data e)))))
       (set! (.-onclose ws) (fn [e] (on-close (.-code e) (.-reason e))))
       {:send!  (fn [text] (.send ws text))
        :close! (fn [] (.close ws 1000 "bye"))})))

(defn- later! [ms f]
  #?(:clj  (future (Thread/sleep (long ms)) (f))
     :cljs (js/setTimeout f ms)))

(defn- answer! [send! frame]
  #?(:clj  (future (when-let [r (reply frame)] (send! (wire/print-edn r))))
     :cljs (when-let [r (reply frame)] (send! (wire/print-edn r)))))

;; =============================================================================
;; The connection loop
;; =============================================================================

(defn- status! [state opts status info]
  (swap! state assoc :status status)
  (try ((:on-status opts) status info) (catch #?(:clj Exception :cljs :default) _ nil)))

(declare ^:private dial!)

(defn- redial! [state opts info]
  (when-not (:stopped? @state)
    (let [n  (:attempt (swap! state update :attempt inc))
          ms (backoff-ms (dec n) (:backoff opts) (+ 0.5 (* 0.5 (rand))))]
      (status! state opts :reconnecting (assoc info :in-ms ms :attempt n))
      (later! ms #(dial! state opts)))))

(defn- now-ms []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn- watch!
  "While connection `gen` is current, every quarter of :ping-ms: ping a
   quiet link, and redial one silent for :idle-ms. Only a socket that can
   ping is watched."
  [state opts gen]
  (let [hb (:heartbeat opts)]
    (later! (max 50 (quot (:ping-ms hb) 4))
            (fn []
              (let [s @state]
                (when (and (= gen (:gen s)) (not (:stopped? s)))
                  (let [now  (now-ms)
                        sock (:socket s)]
                    (case (idle-action now (:heard s) (:pinged s) hb)
                      :dead (do (swap! state update :gen inc)
                                (try ((:close! sock)) (catch #?(:clj Exception :cljs :default) _gone nil))
                                (redial! state opts {:code 4000 :reason "bridge silent"
                                                     :silent-ms (- now (:heard s))}))
                      :ping (do (try ((:ping! sock)) (catch #?(:clj Exception :cljs :default) _gone nil))
                                (swap! state assoc :pinged now)
                                (watch! state opts gen))
                      :idle (watch! state opts gen)))))))))

(defn- dial! [state opts]
  (when-not (:stopped? @state)
    (status! state opts :connecting {:url (:url opts)})
    (let [gen  (:gen (swap! state update :gen inc))
          mine #(= gen (:gen @state))
          sock (atom nil)
          _    (swap! state assoc :heard (now-ms) :pinged (now-ms))
          handlers
          {:dial-ms  (:dial-ms opts)
           :on-heard (fn [] (when (mine) (swap! state assoc :heard (now-ms))))
           :on-open  (fn [send!]
                       (status! state opts :hello {:runtime-id (:runtime-id opts)})
                       (send! (wire/print-edn (hello opts))))
           :on-text  (fn [text]
                       (when-let [f (read-frame text)]
                         (case (:ws/type f)
                           :welcome (do (swap! state assoc :attempt 0)
                                        (status! state opts :open (select-keys f [:target :runtime/id])))
                           :request (answer! (:send! @sock) f)
                           :refused (when (fatal? f)
                                      (swap! state assoc :stopped? true)
                                      (status! state opts :refused (:error f)))
                           nil)))
           :on-close (fn [code reason]
                       (when (mine) (redial! state opts {:code code :reason reason})))}]
      (reset! sock #?(:clj  (dial (:url opts) handlers)
                      :cljs (dial (:url opts) handlers (or (:websocket opts) js/WebSocket))))
      (swap! state assoc :socket @sock)
      (when (:ping! @sock) (watch! state opts gen)))))

(defn connect!
  "Dial the bridge and keep the connection up (see the namespace doc).
   Answers a handle: {:runtime-id .. :status (fn []) :stop! (fn [])}."
  [opts]
  (let [opts  (normalize opts)
        state (atom {:attempt 0 :gen 0 :status :idle :stopped? false})]
    (dial! state opts)
    {:runtime-id (:runtime-id opts)
     :status     #(:status @state)
     :stop!      (fn []
                   (swap! state assoc :stopped? true)
                   (when-let [s (:socket @state)] ((:close! s)))
                   (status! state opts :stopped {}))}))

(defn disconnect!
  "Stop the connection `handle` (from `connect!`); it is not redialed."
  [handle]
  ((:stop! handle)))
