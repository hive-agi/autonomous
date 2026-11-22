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
  "Promote: the text of frame map `frame`."
  [frame]
  (wire/print-edn frame))

(defn- read-edn-safely
  "Collect: Ok of the EDN value in `text`, or a :ws/not-edn refusal when it
   does not read. Reading evaluates nothing."
  [text]
  (try (answer/ok (wire/read-edn text))
       (catch Exception _not-edn (answer/refusal :ws/not-edn "not EDN"))))

(defn- frame?
  "Promote: a frame is one EDN map with a keyword :ws/type."
  [f]
  (and (map? f) (keyword? (:ws/type f))))

(defn- answer-shape?
  "Promote: an Answer carries exactly one of :ok, :pending, :error."
  [a]
  (and (map? a) (= 1 (count (select-keys a [:ok :pending :error])))))

(def ^:private bad-frame
  (answer/refusal :ws/bad-frame "a frame is one EDN map with a keyword :ws/type"))

(defn- bounded-text
  "Promote: Ok of `text` when it is a string of at most max-frame-chars,
   else the :ws/bad-frame refusal."
  [text]
  (if (and (string? text) (<= (count text) max-frame-chars)) (answer/ok text) bad-frame))

(defn read-frame
  "Promote: Ok of the frame in `text` (an EDN map with a keyword :ws/type),
   or a :ws/bad-frame refusal. Reading EDN evaluates nothing."
  [text]
  (answer/let-answer [t (bounded-text text)]
    (let [f (read-edn-safely t)]
      (if (frame? (:ok f)) f bad-frame))))

(defn valid-runtime-id?
  "Promote: true for 1..64 characters of [A-Za-z0-9._-]."
  [s]
  (boolean (and (string? s) (re-matches #"[A-Za-z0-9._-]{1,64}" s))))

(defn- label-of [frame]
  (let [l (str (or (:runtime/label frame) "websocket runtime"))]
    (subs l 0 (min 120 (count l)))))

(defn- unauthorized
  "Promote: the :auth/unauthorized refusal of a hello whose token does not
   grant the runtime scope."
  []
  (answer/refusal :auth/unauthorized "this token does not grant the runtime scope"))

(defn- authenticated
  "Promote: authenticate result `a` as an Answer: its refusal alone when it
   carries :error (even beside :ok), else `a` when Ok, else the
   :auth/unauthorized refusal."
  [a]
  (cond (answer/refused? a) {:error (:error a)}
        (answer/ok? a)      a
        :else               (unauthorized)))

(defn- runtime-grant
  "Promote: Ok of the grant whose scopes include :runtime itself, else an
   :auth/unauthorized refusal; :admin alone does not qualify."
  [g]
  (if (contains? (set (:scopes g)) runtime-scope)
    (answer/ok g)
    (unauthorized)))

(defn- runtime-of
  "Promote: the directory row {:runtime/id :runtime/kind :runtime/label}
   `frame` proposes; an invalid or absent id takes `fresh-id`."
  [frame fresh-id]
  (let [rid (:runtime/id frame)]
    {:runtime/id    (if (valid-runtime-id? rid) rid (fresh-id))
     :runtime/kind  (if (keyword? (:runtime/kind frame)) (:runtime/kind frame) :unknown)
     :runtime/label (label-of frame)}))

(defn hello-grant
  "Promote: Ok {:target :runtime {:runtime/id :runtime/kind :runtime/label}}
   for `frame`, the first frame a runtime sends, checked by `authenticate`
   (fn [token] Answer of a grant {:actor :scopes}); else a refusal. The
   token must grant :runtime itself, and the runtime joins the target its
   token's actor names, so a token for one app cannot serve another.
   `fresh-id` (fn [] id) names a runtime that proposes no valid id."
  [authenticate frame fresh-id]
  (if (not= :hello (:ws/type frame))
    (answer/refusal :ws/bad-frame "the first frame must be :hello")
    (answer/let-answer [g (authenticated (authenticate (:token frame)))
                        g (runtime-grant g)]
      (answer/ok {:target  (:actor g)
                  :runtime (runtime-of frame fresh-id)}))))

(defn as-answer
  "Promote: the Answer that text `text` from a runtime reads as, or a
   :channel/bad-answer refusal."
  [text]
  (let [a (:ok (read-edn-safely text))]
    (if (answer-shape? a)
      a
      (answer/refusal :channel/bad-answer "the runtime answered something that is not an Answer"))))

(defn- ordered
  "Promote: the connections of `conns` ({runtime-id conn}), oldest first."
  [conns]
  (sort-by (juxt :since :runtime/id) (vals conns)))

(defn directory
  "Promote: directory rows of the connections `conns` ({runtime-id conn}),
   oldest connection first."
  [conns]
  (mapv #(select-keys % [:runtime/id :runtime/kind :runtime/label]) (ordered conns)))

(defn- conn-named
  "Promote: Ok of the connection `runtime-id` names among `rows`, or a
   :channel/unknown-runtime refusal listing the runtimes there are."
  [rows runtime-id]
  (or (some #(when (= (str runtime-id) (:runtime/id %)) (answer/ok %)) rows)
      (answer/refusal :channel/unknown-runtime (str "no runtime " runtime-id)
                      {:known (mapv :runtime/id rows)})))

(defn pick
  "Promote: Ok of the connection among `conns` that `runtime-id` names
   (nil: the oldest), or a refusal naming the runtimes there are."
  [target-id conns runtime-id]
  (let [rows (ordered conns)]
    (cond
      (empty? rows)
      (answer/refusal :channel/no-runtime
                      (str "no runtime of " target-id " is connected: start the app, it dials the bridge"))

      (nil? runtime-id) (answer/ok (first rows))
      :else             (conn-named rows runtime-id))))

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
  "Boundary: an empty hub for target `target-id`: its connections by runtime id, the
   requests awaiting an answer, and the request counter."
  [target-id]
  {:target-id target-id :conns (atom {}) :pending (atom {}) :seq (atom 0)})

(defonce ^{:doc "Hubs of this process by target id: `channel` adds one per :ws
  target, the socket endpoint finds them here."}
  default-hubs
  (atom {}))

(defn hub-for!
  "Boundary: the hub of `target-id` in `hubs` (an atom), created when missing."
  [hubs target-id]
  (get (swap! hubs #(if (contains? % target-id) % (assoc % target-id (hub target-id)))) target-id))

(defn- fail-pending!
  "Boundary: settle every request awaiting connection `link` with refusal `a`."
  [hub link a]
  (doseq [[_ {l :link p :promise}] @(:pending hub)]
    (when (identical? l link) (deliver p a))))

(defn- left [rid why]
  (answer/refusal :channel/runtime-left (str "runtime " rid " disconnected: " why)))

(defn- register!
  "Boundary: add `conn` to `hub`; a connection already holding its runtime id (a
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
  "Boundary: remove connection `link` of runtime `rid` from `hub` (unless a newer
   connection already holds the id) and fail what it still owed."
  [hub rid link]
  (swap! (:conns hub) (fn [m] (if (identical? link (get-in m [rid :link])) (dissoc m rid) m)))
  (fail-pending! hub link (left rid "socket closed")))

(defn- request-frame
  "Promote: the :request frame carrying `request` under request `id`."
  [id request]
  {:ws/type :request :id id :request (wire/print-edn request)})

(defn- settled-answer
  "Promote: what promise value `a` settles as: the runtime's Answer, or a
   :channel/timeout refusal when nothing arrived in time."
  [a conn timeout-ms]
  (if (= ::timeout a)
    (answer/refusal :channel/timeout
                    (str "runtime " (:runtime/id conn) " did not answer within " timeout-ms " ms"))
    a))

(defn- exchange!
  "Boundary: register a promise for request `id` to `conn`, send the
   frame, and wait within `timeout-ms`. Cleans the pending entry whatever
   happens; a failed send or wait is a refusal, never a throw."
  [hub conn id request timeout-ms]
  (let [p (promise)]
    (swap! (:pending hub) assoc id {:link (:link conn) :promise p})
    (try
      ((:send! conn) (frame-text (request-frame id request)))
      (settled-answer (deref p timeout-ms ::timeout) conn timeout-ms)
      (catch Exception e
        (answer/refusal :channel/transport-failed (failure/message e)))
      (finally (swap! (:pending hub) dissoc id)))))

(defn send-request!
  "Boundary: Answer of `request` from runtime `runtime-id` (nil: the
   oldest) of `hub`, within `timeout-ms`. Only the connection the request
   went to may answer it. Never throws."
  [hub runtime-id request timeout-ms]
  (answer/let-answer [conn (pick (:target-id hub) @(:conns hub) runtime-id)]
    (exchange! hub conn (swap! (:seq hub) inc) request timeout-ms)))

;; =============================================================================
;; Links: one socket connection, driven by a socket adapter
;; =============================================================================

(defn- now-ms [] (System/currentTimeMillis))

(defn- fresh-id [] (str "ws-" (subs (str (random-uuid)) 0 8)))

(defn- refuse!
  "Boundary: tell the peer why, then close with the code `kind` names."
  [{:keys [send! close!]} kind error]
  (try (send! (frame-text {:ws/type :refused :error error}))
       (catch Exception _peer-gone nil))
  (close! (close-codes kind 4400) (:message error "refused")))

(defn- hello-watchdog!
  "Boundary: close `link` when it has not said :hello within `wait` ms."
  [link io wait]
  (future
    (Thread/sleep (long wait))
    (when (= :hello (:state @link))
      (swap! link assoc :state :closed)
      (refuse! io :hello-timeout {:type :ws/hello-timeout :message "no :hello in time"}))))

(defn open-link
  "Boundary: the state of one new socket connection. `io` is what the
   adapter can do: {:send! (fn [text]) :close! (fn [code reason])}. `opts`:
   :authenticate (fn [token] Answer of a grant), :hubs (default
   `default-hubs`), :hello-timeout-ms (0 disables). A connection that does
   not say :hello in time is closed."
  [opts io]
  (let [link (atom {:state :hello :io io})
        wait (:hello-timeout-ms opts hello-timeout-ms)]
    (when (pos? wait) (hello-watchdog! link io wait))
    link))

(defn- welcome-conn
  "Promote: the directory connection of granted runtime `rt` on `link`,
   with the socket's io fns, stamped at `since`."
  [rt link io since]
  (merge rt {:since since :link link :send! (:send! io) :close! (:close! io)}))

(defn- welcome-frame
  "Promote: the :welcome frame naming the hub's target and runtime."
  [h rt]
  {:ws/type :welcome :target (:target-id h) :runtime/id (:runtime/id rt)})

(defn- hub-of-grant
  "Boundary: Ok of the hub the granted target names, or a
   :ws/unknown-target refusal when this bridge serves none such."
  [opts g]
  (let [target (:target g)
        h      (get @(:hubs opts default-hubs) target)]
    (if h
      (answer/ok h)
      (answer/refusal :ws/unknown-target
                      (str "this bridge serves no websocket target " target)))))

(defn- grant-close-kind
  "Promote: the close-code kind a hello-grant refusal `error` names:
   :unauthorized for :auth/unauthorized, else :bad-frame."
  [error]
  (if (= :auth/unauthorized (:type error)) :unauthorized :bad-frame))

(defn- closing
  "Promote: Answer `a` with its refusal tagged by the close-code kind
   `kind-of` names for it; an Ok or a Pending unchanged."
  [kind-of a]
  (if-let [e (:error a)] (assoc a :ws/close (kind-of e)) a))

(defn- refuse-hello!
  "Boundary: close a refused hello `a` with the code its :ws/close kind
   names; nil."
  [io a]
  (refuse! io (:ws/close a) (:error a))
  nil)

(defn- welcome!
  "Boundary: register the granted runtime on its hub and say welcome.
   Answers Ok when the runtime has joined."
  [link h rt]
  (let [io (:io @link)]
    (swap! link assoc :state :open :hub h :runtime/id (:runtime/id rt))
    (register! h (welcome-conn rt link io (now-ms)))
    ((:send! io) (frame-text (welcome-frame h rt)))
    (answer/ok nil)))

(defn- hello!
  "Boundary: authenticate the :hello frame and join the runtime to its
   target's hub; any refusal on the way closes the socket. nil."
  [opts link frame]
  (let [a (answer/let-answer
            [g (closing grant-close-kind (hello-grant (:authenticate opts) frame fresh-id))
             h (closing (constantly :unknown-target) (hub-of-grant opts g))]
            (welcome! link h (:runtime g)))]
    (when (:error a) (refuse-hello! (:io @link) a))
    nil))

(defn- response-answer
  "Promote: the Answer a :response frame carries — its EDN text read, or
   a :channel/bad-answer refusal when it carries none."
  [answer-text]
  (if (string? answer-text)
    (as-answer answer-text)
    (answer/refusal :channel/bad-answer "a response carries its Answer as EDN text")))

(defn- settle!
  "Boundary: deliver the :response frame's Answer to the promise of
   request `id`, when `link` itself still owes it."
  [hub link {:keys [id answer]}]
  (let [{l :link p :promise} (get @(:pending hub) id)]
    (when (and p (identical? l link))
      (deliver p (response-answer answer)))))

(defn on-text!
  "Boundary: handle text frame `text` arriving on `link`. Before :hello
   only :hello is accepted; after it, :response frames settle requests and
   unknown frame types are ignored (room for :ping and friends)."
  [opts link text]
  (let [{:keys [state io hub]} @link
        f (read-frame text)]
    (cond
      (= :closed state)   nil
      (answer/refused? f) (do (swap! link assoc :state :closed)
                              (refuse! io :bad-frame (:error f)))
      (= :hello state)    (hello! opts link (:ok f))
      (= :response (get-in f [:ok :ws/type])) (settle! hub link (:ok f))
      :else nil)))

(defn on-close!
  "Boundary: the socket of `link` closed: its runtime leaves the directory
   and every request it still owed is refused."
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
