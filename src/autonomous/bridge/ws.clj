(ns autonomous.bridge.ws
  "Websocket adapter of IRuntimeChannel and IRuntimeDirectory: the runtime
   dials OUT to the bridge, so a deployed app needs no nREPL and opens no
   port of its own.

   Protocol: one EDN map per text frame (doc/websocket.md).

     runtime -> bridge  {:ws/type :hello :token \"aut_..\" :runtime/id .. :runtime/kind .. :runtime/label ..}
                        {:ws/type :response :id n :answer \"<EDN Answer>\"}
     bridge -> runtime  {:ws/type :welcome :target .. :runtime/id ..}
                        {:ws/type :refused :error {:type .. :message ..}}, then close
                        {:ws/type :request :id n :request \"<EDN Request>\"}

   A runtime answers a :request by handing its text to
   autonomous.core/rpc-str and to nothing else: the socket carries the
   app's declared tools, never code to evaluate. The nREPL stays on the box.

   A runtime authenticates with a runtime token (autonomous.bridge.auth): a
   TokenEntry granting scope :runtime whose actor is the id of the target
   it serves. It appears in that target's directory once welcomed and
   leaves it when its socket closes.

   Strata: the protocol functions are pure; hubs (one per target) hold the
   live connections; a socket adapter (autonomous.ws.server) drives a
   connection through `open-link`, `on-text!` and `on-close!`."
  (:require [autonomous.bridge.auth :as auth]
            [autonomous.bridge.failure :as failure]
            [autonomous.bridge.port :as port]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.wire :as wire]))

(def default-timeout-ms 30000)

(def hello-timeout-ms
  "A connection that has not said :hello by then is closed."
  10000)

(def max-frame-chars
  "Longest text frame read; an answer larger than this is refused."
  (* 4 1024 1024))

(def runtime-scope
  "The scope a token needs to register a runtime."
  :runtime)

(def close-codes
  "Websocket close codes the bridge uses (4000-4999 belong to the application)."
  {:normal 1000 :bad-frame 4400 :unauthorized 4401 :unknown-target 4404
   :hello-timeout 4408 :replaced 4409})

;; =============================================================================
;; Protocol (pure)
;; =============================================================================

(defn frame-text
  "The text of frame map `frame`."
  [frame]
  (wire/print-edn frame))

(defn read-frame
  "Ok of the frame in `text` (an EDN map with a keyword :ws/type), or a
   :ws/bad-frame refusal. Reading EDN evaluates nothing."
  [text]
  (let [f (if (and (string? text) (<= (count text) max-frame-chars))
            (try (wire/read-edn text) (catch Exception _not-edn ::bad))
            ::bad)]
    (if (and (map? f) (keyword? (:ws/type f)))
      (answer/ok f)
      (answer/refusal :ws/bad-frame "a frame is one EDN map with a keyword :ws/type"))))

(defn valid-runtime-id?
  "True for 1..64 characters of [A-Za-z0-9._-]."
  [s]
  (boolean (and (string? s) (re-matches #"[A-Za-z0-9._-]{1,64}" s))))

(defn- label-of [frame]
  (let [l (str (or (:runtime/label frame) "websocket runtime"))]
    (subs l 0 (min 120 (count l)))))

(defn hello-grant
  "Ok {:target :runtime {:runtime/id :runtime/kind :runtime/label}} for
   `frame`, the first frame a runtime sends, checked by `authenticate` (fn
   [token] Answer of a grant {:actor :scopes}); else a refusal. The token
   must grant :runtime itself (:admin does not stand in: an agent's token
   does not make its holder a runtime), and the runtime joins the target
   its token's actor names, so a token for one app cannot serve another.
   `fresh-id` (fn [] id) names a runtime that proposes no valid id."
  [authenticate frame fresh-id]
  (if (not= :hello (:ws/type frame))
    (answer/refusal :ws/bad-frame "the first frame must be :hello")
    (let [g (authenticate (:token frame))]
      (cond
        (answer/refused? g) g

        (not (contains? (set (get-in g [:ok :scopes])) runtime-scope))
        (answer/refusal :auth/unauthorized "this token does not grant the runtime scope")

        :else
        (let [rid (:runtime/id frame)]
          (answer/ok {:target  (get-in g [:ok :actor])
                      :runtime {:runtime/id    (if (valid-runtime-id? rid) rid (fresh-id))
                                :runtime/kind  (if (keyword? (:runtime/kind frame)) (:runtime/kind frame) :unknown)
                                :runtime/label (label-of frame)}}))))))

(defn as-answer
  "The Answer that text `text` from a runtime reads as, or a
   :channel/bad-answer refusal."
  [text]
  (let [a (try (wire/read-edn text) (catch Exception _not-edn nil))]
    (if (and (map? a) (= 1 (count (select-keys a [:ok :pending :error]))))
      a
      (answer/refusal :channel/bad-answer "the runtime answered something that is not an Answer"))))

(defn directory
  "Directory rows of the connections `conns` ({runtime-id conn}), oldest
   connection first."
  [conns]
  (->> (vals conns)
       (sort-by (juxt :since :runtime/id))
       (mapv #(select-keys % [:runtime/id :runtime/kind :runtime/label]))))

(defn pick
  "Ok of the connection among `conns` that `runtime-id` names (nil: the
   oldest), or a refusal naming the runtimes there are."
  [target-id conns runtime-id]
  (let [rows (sort-by (juxt :since :runtime/id) (vals conns))]
    (cond
      (empty? rows)
      (answer/refusal :channel/no-runtime
                      (str "no runtime of " target-id " is connected: start the app, it dials the bridge"))

      (nil? runtime-id) (answer/ok (first rows))

      :else
      (or (some #(when (= (str runtime-id) (:runtime/id %)) (answer/ok %)) rows)
          (answer/refusal :channel/unknown-runtime (str "no runtime " runtime-id)
                          {:known (mapv :runtime/id rows)})))))

(defn authenticator
  "(fn [token] Answer of a grant) over TokenEntry `entries`: the same
   tokens and hashing as the HTTP transport (autonomous.bridge.auth). Mint
   a runtime token with `autonomous token --actor <target-id> --scopes runtime`."
  [entries]
  (fn [token] (auth/authenticate entries token)))

;; =============================================================================
;; Hubs: the live connections of one target
;; =============================================================================

(defn hub
  "An empty hub for target `target-id`: its connections by runtime id, the
   requests awaiting an answer, and the request counter."
  [target-id]
  {:target-id target-id :conns (atom {}) :pending (atom {}) :seq (atom 0)})

(defonce ^{:doc "Hubs of this process by target id: `channel` adds one per :ws
  target, the socket endpoint finds them here."}
  default-hubs
  (atom {}))

(defn hub-for!
  "The hub of `target-id` in `hubs` (an atom), created when missing."
  [hubs target-id]
  (get (swap! hubs #(if (contains? % target-id) % (assoc % target-id (hub target-id)))) target-id))

(defn- fail-pending!
  "Settle every request awaiting connection `link` with refusal `a`."
  [hub link a]
  (doseq [[_ {l :link p :promise}] @(:pending hub)]
    (when (identical? l link) (deliver p a))))

(defn- left [rid why]
  (answer/refusal :channel/runtime-left (str "runtime " rid " disconnected: " why)))

(defn- register!
  "Add `conn` to `hub`; a connection already holding its runtime id (a
   reconnect the bridge has not noticed is closed yet) is replaced."
  [hub conn]
  (let [rid      (:runtime/id conn)
        [old _]  (swap-vals! (:conns hub) assoc rid conn)
        previous (get old rid)]
    (when previous
      (fail-pending! hub (:link previous) (left rid "replaced by a new connection"))
      (try ((:close! previous) (:replaced close-codes) "replaced by a new connection")
           (catch Exception _already-closed nil)))))

(defn- unregister!
  "Remove connection `link` of runtime `rid` from `hub` (unless a newer
   connection already holds the id) and fail what it still owed."
  [hub rid link]
  (swap! (:conns hub) (fn [m] (if (identical? link (get-in m [rid :link])) (dissoc m rid) m)))
  (fail-pending! hub link (left rid "socket closed")))

(defn send-request!
  "Answer of `request` from runtime `runtime-id` (nil: the oldest) of
   `hub`, within `timeout-ms`. Only the connection the request went to may
   answer it. Never throws."
  [hub runtime-id request timeout-ms]
  (let [c (pick (:target-id hub) @(:conns hub) runtime-id)]
    (if (answer/refused? c)
      c
      (let [conn (:ok c)
            id   (swap! (:seq hub) inc)
            p    (promise)]
        (swap! (:pending hub) assoc id {:link (:link conn) :promise p})
        (try
          ((:send! conn) (frame-text {:ws/type :request :id id :request (wire/print-edn request)}))
          (let [a (deref p timeout-ms ::timeout)]
            (if (= ::timeout a)
              (answer/refusal :channel/timeout
                              (str "runtime " (:runtime/id conn) " did not answer within " timeout-ms " ms"))
              a))
          (catch Exception e
            (answer/refusal :channel/transport-failed (failure/message e)))
          (finally (swap! (:pending hub) dissoc id)))))))

;; =============================================================================
;; Links: one socket connection, driven by a socket adapter
;; =============================================================================

(defn- now-ms [] (System/currentTimeMillis))

(defn- fresh-id [] (str "ws-" (subs (str (random-uuid)) 0 8)))

(defn- refuse!
  "Tell the peer why, then close with the code `kind` names."
  [{:keys [send! close!]} kind error]
  (try (send! (frame-text {:ws/type :refused :error error}))
       (catch Exception _peer-gone nil))
  (close! (close-codes kind 4400) (:message error "refused")))

(defn open-link
  "The state of one new socket connection. `io` is what the adapter can
   do: {:send! (fn [text]) :close! (fn [code reason])}. `opts`:
   :authenticate (fn [token] Answer of a grant), :hubs (default
   `default-hubs`), :hello-timeout-ms (0 disables). A connection that does
   not say :hello in time is closed."
  [opts io]
  (let [link (atom {:state :hello :io io})
        wait (:hello-timeout-ms opts hello-timeout-ms)]
    (when (pos? wait)
      (future
        (Thread/sleep (long wait))
        (when (= :hello (:state @link))
          (swap! link assoc :state :closed)
          (refuse! io :hello-timeout {:type :ws/hello-timeout :message "no :hello in time"}))))
    link))

(defn- hello! [opts link frame]
  (let [io (:io @link)
        g  (hello-grant (:authenticate opts) frame fresh-id)
        h  (when (answer/ok? g) (get @(:hubs opts default-hubs) (get-in g [:ok :target])))]
    (cond
      (answer/refused? g)
      (refuse! io (if (= :auth/unauthorized (get-in g [:error :type])) :unauthorized :bad-frame) (:error g))

      (nil? h)
      (refuse! io :unknown-target {:type :ws/unknown-target
                                   :message (str "this bridge serves no websocket target "
                                                 (get-in g [:ok :target]))})

      :else
      (let [rt   (get-in g [:ok :runtime])
            conn (merge rt {:since (now-ms) :link link :send! (:send! io) :close! (:close! io)})]
        (swap! link assoc :state :open :hub h :runtime/id (:runtime/id rt))
        (register! h conn)
        ((:send! io) (frame-text {:ws/type :welcome :target (:target-id h) :runtime/id (:runtime/id rt)}))))))

(defn on-text!
  "Handle text frame `text` arriving on `link`. Before :hello only :hello
   is accepted; after it, :response frames settle requests and unknown
   frame types are ignored (room for :ping and friends)."
  [opts link text]
  (let [{:keys [state io hub]} @link
        f (read-frame text)]
    (cond
      (= :closed state)   nil
      (answer/refused? f) (do (swap! link assoc :state :closed)
                              (refuse! io :bad-frame (:error f)))
      (= :hello state)    (hello! opts link (:ok f))

      (= :response (get-in f [:ok :ws/type]))
      (let [{:keys [id answer]} (:ok f)
            {l :link p :promise} (get @(:pending hub) id)]
        (when (and p (identical? l link))
          (deliver p (if (string? answer)
                       (as-answer answer)
                       (answer/refusal :channel/bad-answer "a response carries its Answer as EDN text")))))

      :else nil)))

(defn on-close!
  "The socket of `link` closed: its runtime leaves the directory and every
   request it still owed is refused."
  [link]
  (let [[{:keys [state hub] :as before} _] (swap-vals! link assoc :state :closed)]
    (when (= :open state)
      (unregister! hub (:runtime/id before) link))))

;; =============================================================================
;; The channel
;; =============================================================================

(defrecord WsChannel [target hub]
  port/IRuntimeChannel
  (-send [_ request runtime-id]
    (send-request! hub runtime-id request (:timeout-ms target default-timeout-ms)))

  port/IRuntimeDirectory
  (-runtimes [_]
    (answer/ok (directory @(:conns hub)))))

(defn channel
  "A WsChannel for websocket `target` ({:target/kind :ws :target/id ..}):
   its runtimes are whatever has dialed in under that target id."
  ([target] (channel target default-hubs))
  ([target hubs] (->WsChannel target (hub-for! hubs (:target/id target)))))
