(ns autonomous.approval
  "L1 port: the Asker a human answers approval requests through, its
   built-in adapters, and the timed ask that turns an asker's answer into
   one Decision.

   Verdict   what an Asker answers: {:decision :approve|:deny :approver str}.
   Decision  what `ask!` answers: a Verdict or :timeout, with :ms waited."
  (:require [autonomous.thenable :as thenable]
            [clojure.string :as str]
            [malli.core :as m]))

(def Verdict
  "What an Asker answers."
  [:map [:decision [:enum :approve :deny]] [:approver :string]])

(def Decision
  "What `ask!` answers: the asker's Verdict, :deny for a malformed one,
   :timeout, or :error when the asker failed, with the milliseconds waited."
  [:map
   [:decision [:enum :approve :deny :timeout :error]]
   [:approver {:optional true} :string]
   [:ms :int]])

(defprotocol Asker
  "L1 port: put one approval request to a human."
  (-ask! [this request]
    "Answer a promise (clj) or thenable (cljs) of a Verdict for `request`
     {:tool :args :actor :session}."))

(defprotocol Withdraw
  "L1 optional role of an Asker: hear that a request is no longer awaited."
  (-withdraw! [this answer]
    "`answer` (what -ask! answered) timed out: no later Verdict may reach it."))

(defn asker?
  "L1: does `x` implement the Asker port?"
  [x]
  (satisfies? Asker x))

(defn now-ms
  "L1 host: the wall clock in milliseconds."
  []
  #?(:cljs (js/Date.now) :default (System/currentTimeMillis)))

(defn resolved
  "L1 host: a promise (clj) or thenable (cljs) already holding `v`."
  [v]
  #?(:cljs (js/Promise.resolve v) :default (doto (promise) (deliver v))))

(defn unresolved
  "L1 host: a promise (clj) or thenable (cljs) that never settles."
  []
  #?(:cljs (js/Promise. (fn [_ _] nil)) :default (promise)))

;; =============================================================================
;; Timed ask
;; =============================================================================

(defn- stamp
  "L1 Promote: decision `d` with the milliseconds since `t0`."
  [d t0]
  (assoc d :ms (- (now-ms) t0)))

(defn- ->decision
  "L1 Promote: the Decision for what the asker answered (`::timeout` when
   nothing came in time, `::error` when it failed). Any other value that
   is not a Verdict denies."
  [v t0]
  (cond
    (= ::timeout v)        (stamp {:decision :timeout} t0)
    (= ::error v)          (stamp {:decision :error} t0)
    (m/validate Verdict v) (stamp (select-keys v [:decision :approver]) t0)
    :else                  (stamp {:decision :deny} t0)))

(defn- withdraw!
  "L1 Boundary: tell `asker` that its `answer` timed out, when it listens."
  [asker answer v]
  (when (and (= ::timeout v) (satisfies? Withdraw asker))
    (-withdraw! asker answer))
  v)

(defn- awaited
  "L1 host: the asker's answer `p`, or `::timeout` after `timeout-ms`; on
   cljs a thenable that clears its timer when `p` settles first."
  [p timeout-ms]
  #?(:cljs    (let [timer (atom nil)
                    t     (js/Promise. (fn [ok _] (reset! timer (js/setTimeout #(ok ::timeout) timeout-ms))))]
                (.finally (js/Promise.race #js [p t]) #(js/clearTimeout @timer)))
     :default (deref p timeout-ms ::timeout)))

(defn- asked
  "L1 host: what `asker` answers for `request`, `::error` when it throws or
   answers something other than a promise or thenable."
  [asker request]
  (let [p (try (-ask! asker request) (catch #?(:cljs :default :default Throwable) _ ::error))]
    (if (thenable/thenable? p) p (resolved ::error))))

(defn ask!
  "L1 Pipeline: put `request` to `asker` and answer a future (clj) or
   thenable (cljs) of a Decision: :timeout when no Verdict arrives within
   `timeout-ms` (the asker hears -withdraw!), :error when the asker fails."
  [asker request timeout-ms]
  (let [t0 (now-ms)
        p  (asked asker request)]
    (thenable/attempt #(thenable/chain (awaited p timeout-ms)
                                       (fn [v] (->decision (withdraw! asker p v) t0)))
                      (fn [_] (stamp {:decision :error} t0)))))

;; =============================================================================
;; Adapters
;; =============================================================================

(defn- pop-verdict!
  "L1 Boundary: take the first Verdict from the `queue` atom, nil when empty."
  [queue]
  (first (first (swap-vals! queue (comp vec rest)))))

(defrecord QueueAsker [verdicts requests]
  Asker
  (-ask! [_ request]
    (swap! requests conj request)
    (if-let [v (pop-verdict! verdicts)] (resolved v) (unresolved))))

(defn queue-asker
  "L1 adapter (tests): answers `verdicts` in order and records every request
   in the `:requests` atom; with the queue empty it never answers."
  [verdicts]
  (->QueueAsker (atom (vec verdicts)) (atom [])))

(defn approves?
  "L1: does the typed `line` approve the request numbered `id`? Only
   \"<id> y\" or \"<id> yes\" (any case, any spacing) does."
  [id line]
  (let [[typed answer & more] (str/split (str/trim (or line "")) #"\s+")]
    (and (= id typed) (contains? #{"y" "yes"} (str/lower-case (or answer ""))) (empty? more))))

(defn prompt-line
  "L1: the line the stdin asker prints for request `id`."
  [id request]
  (str "[" id "] approve " (pr-str request) "? type \"" id " y\" to approve; anything else denies"))

#?(:cljs nil
   :default
   (defn- read-verdict
     "L1 Boundary: prompt request `id` on `out`, read one line, and answer
      the Verdict of `approver` (see approves?)."
     [{:keys [read-line! out approver]} id request]
     (binding [*out* out] (println (prompt-line id request)) (flush))
     {:decision (if (approves? id (read-line!)) :approve :deny) :approver approver}))

#?(:cljs nil
   :default
   (defn- serve!
     "L1 Boundary: once request `prev` is done, deliver `p` the Verdict for
      request `id` unless it was withdrawn first; then mark `done`."
     [{:keys [withdrawn] :as asker} [prev done] p id request]
     (try
       (deref prev)
       (when-not (contains? @withdrawn p) (deliver p (read-verdict asker id request)))
       (finally (swap! withdrawn disj p) (deliver done true)))))

#?(:cljs nil
   :default
   (defn- enqueue!
     "L1 Boundary: [the previous request's done promise, this one's], so
      requests are served one at a time in the order they were asked."
     [tail]
     (let [done (promise)]
       [(first (swap-vals! tail (constantly done))) done])))

#?(:cljs nil
   :default
   (defrecord StdinAsker [read-line! out approver counter tail withdrawn]
     Asker
     (-ask! [this request]
       (let [p (promise) turn (enqueue! tail) id (str (swap! counter inc))]
         (future (serve! this turn p id request))
         p))
     Withdraw
     (-withdraw! [_ p] (swap! withdrawn conj p))))

#?(:cljs nil
   :default
   (defn- line-reader
     "L1 host: a zero-argument function reading one line of BufferedReader `in`."
     [in]
     #(binding [*in* in] (read-line))))

#?(:cljs nil
   :default
   (defn stdin-asker
     "L1 adapter (clj): asks on a terminal, one request at a time in call
      order, each numbered; only \"<n> y\" approves request n. A timed-out
      request is withdrawn: never prompted once withdrawn, never answered.
      Options :in (a BufferedReader, default *in*) or :read-line (a
      zero-argument function), :out (default *err*), :approver (default
      \"stdin\")."
     ([] (stdin-asker {}))
     ([{:keys [in read-line out approver] :or {approver "stdin"}}]
      (->StdinAsker (or read-line (line-reader (or in *in*))) (or out *err*) approver
                    (atom 0) (atom (resolved true)) (atom #{})))))
