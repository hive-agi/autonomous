(ns autonomous.bridge.client
  "Pipeline stratum of the bridge: every agent-facing operation, over the
   ports only (channel, directory, ledger) with the clock and sleep injected.
   MCP servers, the hive addon and tests all drive the app through here.

   A call runs: stamp the session's identity on the request, send it to the
   session's runtime, collect a Pending answer until the deadline (backing
   off), then drain the runtime's journal into the ledger."
  (:require [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.port :as port]
            [autonomous.bridge.session :as s]
            [autonomous.domain.answer :as answer]))

(def default-timeout-ms 30000)

(def bridge-grant
  "Identity the bridge itself reads journals with."
  {:actor "autonomous/bridge" :scopes #{:read}})

(defn context
  "Pipeline context. `channel` implements IRuntimeChannel and
   IRuntimeDirectory; `ledger` (optional) implements ILedger. With
   :drain :inline every call drains before answering (deterministic, for
   tests); the default :background drains in a coalescing future so a call
   costs one round trip."
  [{:keys [target channel ledger call-timeout-ms clock sleep! drain]}]
  {:target     target
   :channel    channel
   :ledger     ledger
   :timeout-ms (or call-timeout-ms default-timeout-ms)
   :clock      (or clock #(System/currentTimeMillis))
   :sleep!     (or sleep! #(Thread/sleep (long %)))
   :cursors    (atom {})
   :drain      (or drain :background)
   :queued     (atom #{})})

(defn send!
  "Answer of `op-map` sent as `session` to its pinned runtime."
  [ctx session op-map]
  (port/-send (:channel ctx) (s/->request session op-map) (:session/runtime session)))

(defn next-delay-ms
  "Backoff between polls: doubling from 25 ms, capped at 500 ms."
  [delay]
  (min 500 (* 2 delay)))

(defn await!
  "Poll a Pending `a` until it settles or the deadline passes. A result
   still pending at the deadline is answered as Pending, so the agent can
   collect it later with autonomous_poll."
  [ctx session a]
  (let [deadline (+ ((:clock ctx)) (:timeout-ms ctx))]
    (loop [a a delay 25]
      (if (and (answer/pending? a) (< ((:clock ctx)) deadline))
        (do ((:sleep! ctx) delay)
            (recur (send! ctx session {:op :poll :id (:pending a)}) (next-delay-ms delay)))
        a))))

;; =============================================================================
;; Journal drain
;; =============================================================================

(defn resume-from
  "Where to resume reading a runtime's journal: the saved sequence when the
   runtime is in the same epoch, else 0 (it reloaded and started over)."
  [cursor epoch]
  (if (= epoch (:epoch cursor)) (:seq cursor 0) 0))

(defn drain!
  "Move the journal entries of `session`'s runtime the ledger has not seen
   into the ledger. One round trip when nothing reloaded; a reply from a new
   epoch restarts from sequence 0. Answers Ok of the number stored (0
   without a ledger)."
  [ctx session]
  (if-not (:ledger ctx)
    (answer/ok 0)
    (let [rt     (str (or (:session/runtime session) "default"))
          tid    (get-in ctx [:target :target/id] "target")
          k      [tid rt]
          reader (s/attach (s/open-session "bridge" bridge-grant) (:session/runtime session))
          cursor (get @(:cursors ctx) k)]
      (loop [since (:seq cursor 0) expect (:epoch cursor) stored 0 rounds 0]
        (let [j (send! ctx reader {:op :journal :since since :limit 500})
              {:keys [entries epoch]} (:ok j)]
          (cond
            (answer/refused? j) j

            (and expect (not= expect epoch) (pos? since))
            (recur 0 epoch stored rounds)

            :else
            (let [n        (:ok (port/-append! (:ledger ctx) (map #(ledger/->ledger-entry tid rt %) entries)) 0)
                  last-seq (if (seq entries) (:seq (peek entries)) since)]
              (swap! (:cursors ctx) assoc k {:epoch epoch :seq last-seq})
              (if (and (= 500 (count entries)) (< rounds 20))
                (recur last-seq epoch (+ stored n) (inc rounds))
                (answer/ok (+ stored n))))))))))

;; =============================================================================
;; Operations
;; =============================================================================

(def drain-window-ms
  "How long the drainer waits before draining, so a burst of calls shares
   one journal read (they share the runtime connection with it)."
  250)

(defn drain-soon!
  "Drain `session`'s runtime: now when the context drains :inline, else in
   a future after `drain-window-ms`, coalescing every request for a runtime
   made in that window."
  [ctx session]
  (when (:ledger ctx)
    (if (= :inline (:drain ctx))
      (drain! ctx session)
      (let [k (or (:session/runtime session) :default)
            [before _] (swap-vals! (:queued ctx) conj k)]
        (when-not (contains? before k)
          (future
            (try
              ((:sleep! ctx) drain-window-ms)
              (swap! (:queued ctx) disj k)
              (drain! ctx session)
              (catch Exception e
                (binding [*out* *err*]
                  (println "[autonomous] drain failed:" (ex-message e)))))))))))

(defn manifest [ctx session]
  (send! ctx session {:op :manifest}))

(defn call!
  "Call tool `tool-name` with `args` as `session`; wait for a Pending result
   up to the timeout; have the journal drained (see `drain-soon!`). `caller`
   is the optional agent coordinate of the issuing step, sent on the request
   (so the runtime records it on the call entry) only when there is one; nil
   for none, which leaves the request exactly as it was."
  ([ctx session tool-name args] (call! ctx session tool-name args nil))
  ([ctx session tool-name args caller]
   (let [a (await! ctx session (send! ctx session (cond-> {:op :call :tool tool-name :args (or args {})}
                                                  caller (assoc :caller caller))))]
     (drain-soon! ctx session)
     a)))

(defn read-resource [ctx session uri]
  (await! ctx session (send! ctx session {:op :read :uri uri})))

(defn poll [ctx session id]
  (send! ctx session {:op :poll :id id}))

(defn journal [ctx session since limit]
  (send! ctx session {:op :journal :since since :limit limit}))

(defn lease! [ctx session ttl-ms]
  (let [a (send! ctx session {:op :lease :ttl-ms ttl-ms})]
    (drain-soon! ctx session)
    a))

(defn release! [ctx session]
  (let [a (send! ctx session {:op :release})]
    (drain-soon! ctx session)
    a))

(defn runtimes [ctx]
  (port/-runtimes (:channel ctx)))

(defn ledger-query
  "Ok of ledger views matching LedgerQuery `q`, after draining `session`'s
   runtime so the answer includes what just happened."
  [ctx session q]
  (if-not (:ledger ctx)
    (answer/refusal :ledger/none "this bridge runs without a ledger; start it with one (:ledger config)")
    (do (drain! ctx session)
        (let [a (port/-query (:ledger ctx) q)]
          (if (answer/ok? a) (answer/ok (mapv ledger/->view (:ok a))) a)))))
