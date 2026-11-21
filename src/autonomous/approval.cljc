(ns autonomous.approval
  "L1 port: the Asker a human answers approval requests through, its
   built-in adapters, and the timed ask that turns an asker's answer into
   one Decision.

   Verdict   what an Asker answers: {:decision :approve|:deny :approver str}.
   Decision  what `ask!` answers: a Verdict or :timeout, with :ms waited."
  (:require [autonomous.thenable :as thenable]
            [clojure.string :as str]
            [clojure.walk :as walk]
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
;; What a human may be shown
;; =============================================================================

(def max-shown-chars
  "L1: the longest rendering of one request a human is shown."
  400)

(def ^:private unsafe-bmp
  "L1: Cc (C0, DEL, C1), the BMP Cf format characters (bidi controls,
   zero-width characters, BOM) and U+2028/U+2029, as regex class text."
  (str "\\u0000-\\u001F\\u007F-\\u009F\\u00AD\\u0600-\\u0605\\u061C\\u06DD"
       "\\u070F\\u08E2\\u180E\\u200B-\\u200F\\u2028-\\u202E\\u2060-\\u2064"
       "\\u2066-\\u206F\\uFEFF\\uFFF9-\\uFFFB"))

(def ^:private unsafe-re
  "L1 host: one unsafe character, astral Cf (tag characters included) too."
  #?(:cljs    (js/RegExp. (str "[" unsafe-bmp "\\u{110BD}\\u{110CD}\\u{1D173}-\\u{1D17A}"
                               "\\u{E0001}\\u{E0020}-\\u{E007F}]") "gu")
     :default (re-pattern (str "[" unsafe-bmp "\\x{110BD}\\x{110CD}\\x{1D173}-\\x{1D17A}"
                               "\\x{E0001}\\x{E0020}-\\x{E007F}]"))))

(defn- escape-char
  "L1 host: the visible escape of one unsafe character `c` (a string):
   \\uXXXX in the BMP, \\u{X} beyond it."
  [c]
  (let [cp  #?(:cljs (.codePointAt c 0) :default (.codePointAt ^String c 0))
        hex #?(:cljs (.toString cp 16) :default (Integer/toHexString cp))]
    (if (< cp 0x10000) (str "\\u" (subs (str "0000" hex) (count hex))) (str "\\u{" hex "}"))))

(defn safe-text
  "L1: `s` as text with every control, format or line-separator character
   escaped (see escape-char), so it cannot move a cursor, reorder or hide
   text on a terminal."
  [s]
  #?(:cljs    (.replace (str s) unsafe-re escape-char)
     :default (str/replace (str s) unsafe-re escape-char)))

(defn cap
  "L1: text `s` cut to at most `n` characters, ending in an ellipsis when cut."
  [s n]
  (if (> (count s) n) (str (subs s 0 (dec n)) "\u2026") s))

(defn safe-leaves
  "L1: `x` with every string leaf passed through safe-text."
  [x]
  (walk/postwalk #(if (string? %) (safe-text %) %) x))

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

(defn shown-request
  "L1: `request` rendered for a human: every string leaf escaped
   (safe-leaves), the whole rendering escaped again and capped at
   max-shown-chars."
  [request]
  (cap (safe-text (pr-str (safe-leaves request))) max-shown-chars))

(defn prompt-line
  "L1: the line the stdin asker prints for request `id` (see shown-request)."
  [id request]
  (str "[" id "] approve " (shown-request request) "? type \"" id " y\" to approve; anything else denies"))

(defn- say!
  "L1 Boundary: print `line` on writer `out`."
  [out line]
  #?(:cljs    (.write out (str line "\n"))
     :default (binding [*out* out] (println line) (flush))))

(defn- verdict
  "L1 Promote: the Verdict of `approver` for typed `line` on request `id`."
  [approver id line]
  {:decision (if (approves? id line) :approve :deny) :approver approver})

;; A Terminal is one input stream shared by every stdin asker on it: one
;; request counter, one FIFO of waiting requests and at most one reader.

#?(:cljs nil
   :default
   (defrecord Terminal [read-line! out state]))

#?(:cljs nil
   :default
   (defonce ^:private terminals
     (atom {})))

#?(:cljs nil
   :default
   (defn- terminal-for
     "L1 Boundary: the process-wide Terminal of input `stream`, made once
      with `read-line!` and `out`; every asker on the stream shares it."
     [stream read-line! out]
     (get (swap! terminals #(if (contains? % stream) %
                                (assoc % stream (->Terminal read-line! out
                                                            (atom {:next 0 :queue [] :serving? false})))))
          stream)))

(defn- enqueued
  "L1: terminal state `st` with request `entry` queued under the next
   number, which `entry` carries as :id."
  [st entry]
  (let [id (str (inc (:next st)))]
    (-> st (assoc :next (inc (:next st))) (update :queue conj (assoc entry :id id)))))

(defn- dequeued
  "L1: terminal state `st` without its first request; :serving? stays true
   only while requests remain."
  [st]
  (let [q (subvec (:queue st) 1)] (assoc st :queue q :serving? (boolean (seq q)))))

#?(:cljs nil
   :default
   (defn- serve-one!
     "L1 Boundary: prompt and read the first queued request; a request that
      expired while waiting, or while its line was being typed, is told so
      and gets no Verdict from that line."
     [{:keys [read-line! out state]}]
     (let [{:keys [id p approver request]} (first (:queue @state))]
       (when-not (realized? p)
         (say! out (prompt-line id request))
         (let [line (read-line!)]
           (when-not (deliver p (verdict approver id line))
             (say! out (str "request " id " expired; that line answered nothing")))))
       (swap! state dequeued))))

#?(:cljs nil
   :default
   (defn- serve!
     "L1 Boundary: the terminal's one reader: serve queued requests in order
      until none is left."
     [{:keys [state] :as terminal}]
     (try (loop [] (when (:serving? (serve-one! terminal)) (recur)))
          (catch Throwable _ (swap! state assoc :queue [] :serving? false)))))

#?(:cljs nil
   :default
   (defn- queued-id
     "L1: the number of the request answering to promise `p` on `terminal`."
     [{:keys [state]} p]
     (some #(when (identical? p (:p %)) (:id %)) (:queue @state))))

#?(:cljs nil
   :default
   (defn- start-reader!
     "L1 Boundary: queue `entry` on `terminal` and start its reader unless
      one is running. Answers the request's number."
     [{:keys [state] :as terminal} entry]
     (let [[before after] (swap-vals! state #(assoc (enqueued % entry) :serving? true))]
       (when-not (:serving? before) (future (serve! terminal)))
       (:id (peek (:queue after))))))

#?(:cljs nil
   :default
   (defrecord StdinAsker [terminal approver]
     Asker
     (-ask! [_ request]
       (let [p (promise)]
         (start-reader! terminal {:p p :approver approver :request request})
         p))
     Withdraw
     (-withdraw! [_ p]
       (let [id (queued-id terminal p)]
         (when (and id (not (realized? p)) (deliver p ::withdrawn))
           (say! (:out terminal) (str "request " id " expired")))))))

#?(:cljs nil
   :default
   (defn- line-reader
     "L1 host: a zero-argument function reading one line of BufferedReader `in`."
     [in]
     #(binding [*in* in] (read-line))))

#?(:cljs nil
   :default
   (defn stdin-asker
     "L1 adapter (clj): asks on a terminal. Every stdin asker on one input
      stream shares one process-wide queue, counter and reader: requests are
      prompted one at a time in call order, each numbered, and only
      \"<n> y\" approves request n. A timed-out request is withdrawn and the
      terminal says it expired; a line typed for it answers nothing.
      Options :in (a BufferedReader, default *in*), or :read-line (a
      zero-argument function) with :stream (its identity, default the
      function), :out (default *err*), :approver (default \"stdin\")."
     ([] (stdin-asker {}))
     ([{:keys [in read-line stream out approver] :or {approver "stdin"}}]
      (let [in (or in *in*)]
        (->StdinAsker (terminal-for (or stream read-line in) (or read-line (line-reader in))
                                    (or out *err*))
                      approver)))))
