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

(def max-leaf-chars
  "L1: the longest rendering of one shown value; the rest is counted, not shown."
  120)

(def max-shown-args
  "L1: the most argument values one prompt shows; a request with more is
   not shown at all (see showable?)."
  32)

(def ^:private unsafe-re
  "L1 host: one character outside printable ASCII (a whole code point), or
   a double quote or backslash."
  #?(:cljs    (js/RegExp. "[^\\x20\\x21\\x23-\\x5B\\x5D-\\x7E]" "gu")
     :default #"[^\x20\x21\x23-\x5B\x5D-\x7E]"))

(defn- escape-char
  "L1 host: the visible escape of one unsafe character `c` (a string):
   \\uXXXX in the BMP, \\u{X} beyond it."
  [c]
  (let [cp  #?(:cljs (.codePointAt c 0) :default (.codePointAt ^String c 0))
        hex #?(:cljs (.toString cp 16) :default (Integer/toHexString cp))]
    (if (< cp 0x10000) (str "\\u" (subs (str "0000" hex) (count hex))) (str "\\u{" hex "}"))))

(defn safe-text
  "L1: `s` with every character outside printable ASCII, and \" and \\,
   escaped (see escape-char): only printable ASCII is ever shown."
  [s]
  #?(:cljs    (.replace (str s) unsafe-re escape-char)
     :default (str/replace (str s) unsafe-re escape-char)))

(defn cap
  "L1: text `s` cut to `n` characters, with a visible note of how many were cut."
  [s n]
  (if (> (count s) n) (str (subs s 0 n) " (+" (- (count s) n) " chars)") s))

(defn shown-value
  "L1: one value as a human is shown it: a string quoted, any other leaf
   printed, escaped (safe-text), and capped at max-leaf-chars (cap)."
  [v]
  (cap (if (string? v) (str "\"" (safe-text v) "\"") (safe-text (pr-str v))) max-leaf-chars))

(defn- leaves
  "L1: [path leaf] for every leaf of wire value `x` under `path`; an empty
   collection is a leaf."
  [path x]
  (cond
    (and (coll? x) (empty? x)) [[path x]]
    (map? x)                   (mapcat (fn [[k v]] (leaves (conj path k) v)) x)
    (coll? x)                  (mapcat (fn [i v] (leaves (conj path i) v)) (range) x)
    :else                      [[path x]]))

(def ^:private strict-re
  "L1 host: one character a non-plain key name is not shown as."
  #?(:cljs    (js/RegExp. "[^A-Za-z0-9_.\\-]" "gu")
     :default #"[^A-Za-z0-9_.\-]"))

(defn- strict-text
  "L1: `s` with every character but [A-Za-z0-9_.-] escaped (see escape-char)."
  [s]
  #?(:cljs    (.replace (str s) strict-re escape-char)
     :default (str/replace (str s) strict-re escape-char)))

(defn- plain-name?
  "L1: is `s` a plain identifier, optionally ns-qualified (no space, =, :, # ...)?"
  [s]
  (boolean (re-matches #"([A-Za-z_*+!?<>-][A-Za-z0-9_*+!?<>.-]*/)?[A-Za-z_*+!?<>-][A-Za-z0-9_*+!?<>.-]*" s)))

(defn shown-key
  "L1: one path element as a human is shown it: a plain keyword or symbol
   as printed; any other as (keyword \"...\") or (symbol \"...\") with
   every character but [A-Za-z0-9_.-] escaped; any other key as shown-value."
  [k]
  (let [text (cond (keyword? k) (subs (str k) 1) (symbol? k) (str k))]
    (cond (nil? text)        (shown-value k)
          (plain-name? text) (pr-str k)
          :else              (str "(" (if (keyword? k) "keyword" "symbol") " \"" (strict-text text) "\")"))))

(defn- arg-line
  "L1: the prompt line of the argument leaf `v` at `path`."
  [[path v]]
  (str "    " (cap (str/join " " (map shown-key path)) max-leaf-chars) " = " (shown-value v)))

(defn showable?
  "L1: can every argument value of `request` be shown, one per line
   (at most max-shown-args of them)?"
  [request]
  (<= (count (leaves [] (:args request))) max-shown-args))

(defn prompt-lines
  "L1: what a human is shown for request `id`: tool, actor and session
   first, then every argument value on its own line (sorted by path), then
   how to answer. No value is dropped; a long one is capped with a note."
  [id {:keys [tool actor session args]}]
  (concat [(str "[" id "] approval requested: tool " (shown-value tool)
                " actor " (shown-value actor) " session " (shown-value session))]
          (sort (map arg-line (leaves [] args)))
          [(str "[" id "] type \"" id " y\" to approve; anything else denies")]))

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
    (#{::timeout ::expired ::withdrawn} v) (stamp {:decision :timeout} t0)
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

(defn- say!
  "L1 Boundary: print `lines` on writer `out` as one block no other say!
   on `out` can interleave with."
  [out & lines]
  #?(:cljs    (.write out (str (str/join "\n" lines) "\n"))
     :default (locking out (binding [*out* out] (run! println lines) (flush)))))

(defn- verdict
  "L1 Promote: the Verdict of `approver` for typed `line` on request `id`."
  [approver id line]
  {:decision (if (approves? id line) :approve :deny) :approver approver})

;; Terminal design: memory 20261120025625-307f0e9a.

#?(:cljs nil
   :default
   (defrecord Terminal [read-line! out state lines pumping]))

#?(:cljs nil
   :default
   (defonce ^:private terminals
     (atom {})))

#?(:cljs nil
   :default
   (defn- new-terminal
     "L1: a Terminal reading with `read-line!` and prompting on `out`."
     [read-line! out]
     (->Terminal read-line! out (atom {:next 0 :queue [] :serving? false})
                 (java.util.concurrent.LinkedBlockingQueue.) (atom false))))

#?(:cljs nil
   :default
   (defn- same-ports!
     "L1 Boundary: `terminal`, refusing ex-info :invalid-approval when a
      given `read-line!` or `out` is not the one it already has."
     [terminal read-line! out]
     (when (or (and read-line! (not= read-line! (:read-line! terminal)))
               (and out (not (identical? out (:out terminal)))))
       (throw (ex-info "a stdin asker on this input stream already exists with another :read-line or :out"
                       {:autonomous/error :invalid-approval})))
     terminal))

#?(:cljs nil
   :default
   (defn- terminal-for
     "L1 Boundary: the process-wide Terminal of input `stream`, made once
      (reading with `read-line!` or `default-read`, prompting on `out` or
      *err*); a later asker on the stream must pass the same ports or none."
     [stream read-line! default-read out]
     (-> (swap! terminals #(if (contains? % stream) %
                               (assoc % stream (new-terminal (or read-line! default-read) (or out *err*)))))
         (get stream)
         (same-ports! read-line! out))))

(defn- enqueued
  "L1: terminal state `st` with request `entry` queued under the next
   number, which `entry` carries as :id."
  [st entry]
  (let [id (str (inc (:next st)))]
    (-> st (assoc :next (inc (:next st))) (update :queue conj (assoc entry :id id)))))

(defn- without
  "L1: terminal state `st` without the request answering to promise `p`;
   :serving? stays true only while requests remain."
  [st p]
  (let [q (filterv #(not (identical? p (:p %))) (:queue st))]
    (assoc st :queue q :serving? (boolean (seq q)))))

#?(:cljs nil
   :default
   (defn- daemon!
     "L1 host: run thunk `f` on a new daemon thread, so it never keeps a host
      JVM alive."
     [f]
     (doto (Thread. ^Runnable f) (.setDaemon true) (.start))))

(defn- outcome-line
  "L1: what the terminal says once request `id` ended with `v` (a Verdict,
   or the ::expired / ::withdrawn / ::unshowable marks)."
  [id v]
  (str "request " id " "
       (case (if (map? v) (:decision v) v)
         :approve     "approved"
         :deny        "denied, not run"
         ::unshowable "denied, not run: too many argument values to show"
         ::eof        "denied, not run: input has ended"
         "expired, not run")))

#?(:cljs nil
   :default
   (defn- pump!
     "L1 Boundary: once per terminal, a daemon thread moving every line read
      into the terminal's `lines` queue, so no request waits on a read. It
      reads until end of input, for the life of the process; at end of
      input it marks the terminal :eof (every request still waiting, and
      every later one, is then denied) and lets the stream's terminal go."
     [{:keys [read-line! lines pumping state]} stream]
     (when (compare-and-set! pumping false true)
       (daemon! #(do (loop [] (when-let [l (read-line!)] (.put lines l) (recur)))
                     (swap! state assoc :eof? true)
                     (swap! terminals dissoc stream))))))

#?(:cljs nil
   :default
   (defn- await-line
     "L1 Boundary: the first line typed within `ms` while promise `p` is still
      undelivered, ::eof once input has ended, or nil; looks again every 50 ms."
     [{:keys [lines state]} p ms]
     (let [deadline (+ (now-ms) ms)]
       (loop []
         (let [left (- deadline (now-ms))]
           (when (and (pos? left) (not (realized? p)))
             (or (.poll lines (min 50 left) java.util.concurrent.TimeUnit/MILLISECONDS)
                 (when (:eof? @state) ::eof)
                 (recur))))))))

#?(:cljs nil
   :default
   (defn- discarded-lines!
     "L1 Boundary: empty the terminal's typed-line queue; answer the line
      saying how many lines it held, or nothing when it was empty."
     [{:keys [lines]}]
     (let [n (.drainTo ^java.util.concurrent.LinkedBlockingQueue lines (java.util.ArrayList.))]
       (when (pos? n) [(str "(" n " line(s) typed before this prompt were discarded)")]))))

#?(:cljs nil
   :default
   (defn- answer!
     "L1 Boundary: discard every line typed before this prompt (saying how
      many), prompt request `entry` on `terminal`, and deliver its Verdict
      for the next line: ::eof once input has ended, ::expired when no line
      comes in its :wait-ms or it is withdrawn first."
     [{:keys [out] :as terminal} {:keys [id p approver request wait-ms]}]
     (apply say! out (concat (discarded-lines! terminal) (prompt-lines id request)))
     (let [line (await-line terminal p wait-ms)]
       (deliver p (case line nil ::expired ::eof ::eof (verdict approver id line))))))

#?(:cljs nil
   :default
   (defn- serve-one!
     "L1 Boundary: decide the head request (denied unshown when not
      showable?), say how it ended, and answer the state without it."
     [{:keys [out state] :as terminal}]
     (let [{:keys [id p request] :as entry} (first (:queue @state))]
       (when-not (realized? p)
         (if (showable? request) (answer! terminal entry) (deliver p ::unshowable))
         (say! out (outcome-line id @p)))
       (swap! state without p))))

#?(:cljs nil
   :default
   (defn- serve!
     "L1 Boundary: the terminal's one server: serve queued requests in order
      until none is left."
     [{:keys [state] :as terminal}]
     (try (loop [] (when (:serving? (serve-one! terminal)) (recur)))
          (catch Throwable _ (swap! state assoc :queue [] :serving? false)))))

#?(:cljs nil
   :default
   (defn- start-server!
     "L1 Boundary: queue `entry` on `terminal` and start its server on a
      daemon thread unless one is running."
     [{:keys [state] :as terminal} stream entry]
     (pump! terminal stream)
     (let [[before _] (swap-vals! state #(assoc (enqueued % entry) :serving? true))]
       (when-not (:serving? before) (daemon! #(serve! terminal))))))

#?(:cljs nil
   :default
   (defn- withdraw-entry!
     "L1 Boundary: end the queued request whose ask! answer is `answer`:
      deliver it ::withdrawn and drop it unless it is the head, whose
      server ends it and says it expired. Nothing is printed for a request
      that was never prompted."
     [{:keys [state]} answer]
     (when-let [{:keys [p]} (some #(when (identical? answer (:p %)) %) (:queue @state))]
       (deliver p ::withdrawn)
       (swap! state (fn [st] (if (identical? p (:p (first (:queue st)))) st (without st p)))))))

#?(:cljs nil
   :default
   (defrecord StdinAsker [terminal stream approver wait-ms]
     Asker
     (-ask! [_ request]
       (let [p (promise)]
         (start-server! terminal stream {:p p :approver approver :request request :wait-ms wait-ms})
         p))
     Withdraw
     (-withdraw! [_ answer]
       (withdraw-entry! terminal answer))))

#?(:cljs nil
   :default
   (defn- line-reader
     "L1 host: a zero-argument function reading one line of BufferedReader `in`."
     [in]
     #(binding [*in* in] (read-line))))

#?(:cljs nil
   :default
   (defonce ^{:doc "L1 host: *in* as this namespace first loaded, outside any binding."}
     root-in
     *in*))

#?(:cljs nil
   :default
   (defn- stream-key
     "L1: the Terminal key of an asker: an explicit `stream`, else its
      `read-line` function, else System/in for no `in` or an `in` that is
      the process's own *in* (root-in, which reads System/in), else `in`."
     [stream read-line in]
     (or stream read-line (if (or (nil? in) (identical? in root-in)) System/in in))))

#?(:cljs nil
   :default
   (defn stdin-asker
     "L1 adapter (clj): asks on a terminal. Every stdin asker on one input
      stream (default System/in) shares one process-wide queue, counter and
      reader: requests are prompted one at a time in call order, tool, actor
      and session first and each argument on its own line, and only
      \"<n> y\" approves request n. A request waits up to :wait-ms (default
      60000) from its prompt; the terminal then says it expired, and says
      how every request ended. Give require-approval a :timeout-ms that
      covers the queue wait too. A request that times out before its turn is
      dropped unprompted. Refuses (ex-info :invalid-approval) a :read-line
      or :out other than the stream's first asker's, and a request with
      more than max-shown-args argument values is denied unshown.
      Options :in (a BufferedReader that does NOT read System/in: one over
      System/in, or the root *in*, is keyed on System/in like the default),
      or :read-line (a zero-argument function) with :stream (its identity,
      default the function), :out (default *err*), :approver (default
      \"stdin\"). The reader reads for the life of the process.
      Pass :stream System/in with a custom :read-line over System/in."
     ([] (stdin-asker {}))
     ([{:keys [in read-line stream out approver wait-ms] :or {approver "stdin" wait-ms 60000}}]
      (let [stream (stream-key stream read-line in)]
        (->StdinAsker (terminal-for stream read-line (line-reader (or in *in*)) out)
                      stream approver wait-ms)))))
