(ns autonomous.approval
  "L1 port: the Asker a human answers approval requests through, its
   built-in adapters, and the timed ask that turns an asker's answer into
   one Decision.

   Verdict   what an Asker answers: {:decision :approve|:deny :approver str}.
   Decision  what `ask!` answers: a Verdict or :timeout, with :ms waited."
  (:require [clojure.string :as str]
            [malli.core :as m]))

(def Verdict
  "What an Asker answers."
  [:map [:decision [:enum :approve :deny]] [:approver :string]])

(def Decision
  "What `ask!` answers: the asker's Verdict, :deny for a malformed one, or
   :timeout, with the milliseconds waited."
  [:map
   [:decision [:enum :approve :deny :timeout]]
   [:approver {:optional true} :string]
   [:ms :int]])

(defprotocol Asker
  "L1 port: put one approval request to a human."
  (-ask! [this request]
    "Answer a promise (clj) or thenable (cljs) of a Verdict for `request`
     {:tool :args :actor :session}."))

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
   nothing came in time). A value that is not a Verdict denies."
  [v t0]
  (cond
    (= ::timeout v)        (stamp {:decision :timeout} t0)
    (m/validate Verdict v) (stamp (select-keys v [:decision :approver]) t0)
    :else                  (stamp {:decision :deny} t0)))

#?(:cljs
   (defn- timeout-thenable
     "L1 host: a thenable resolving to `::timeout` after `ms`."
     [ms]
     (js/Promise. (fn [ok _] (js/setTimeout #(ok ::timeout) ms)))))

(defn ask!
  "L1 Pipeline: put `request` to `asker` and answer a future (clj) or
   thenable (cljs) of a Decision, :timeout when no Verdict arrives within
   `timeout-ms`. What the asker throws or rejects with propagates."
  [asker request timeout-ms]
  (let [t0 (now-ms)
        p  (-ask! asker request)]
    #?(:cljs    (.then (js/Promise.race #js [p (timeout-thenable timeout-ms)])
                       #(->decision % t0))
       :default (future (->decision (deref p timeout-ms ::timeout) t0)))))

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

(defn- yes?
  "L1: is the typed `line` an approval (y or yes, any case)?"
  [line]
  (contains? #{"y" "yes"} (str/lower-case (str/trim (or line "")))))

#?(:cljs nil
   :default
   (defn- read-verdict
     "L1 Boundary: prompt on `out`, read one line from `in`, and answer the
      Verdict of `approver`: y or yes approves, anything else denies."
     [{:keys [in out approver]} request]
     (binding [*out* out] (println "approve call?" (pr-str request) "[y/N]") (flush))
     {:decision (if (yes? (binding [*in* in] (read-line))) :approve :deny)
      :approver approver}))

#?(:cljs nil
   :default
   (defrecord StdinAsker [in out approver]
     Asker
     (-ask! [this request] (future (read-verdict this request)))))

(defn- hive-verdict
  "L1 Promote: the Verdict for hive ask's reply {:decision str :by str};
   its timeout reply is `::timeout`, which `ask!` reads as a timeout."
  [{:keys [timeout decision by]}]
  (if timeout
    ::timeout
    {:decision (if (yes? decision) :approve :deny) :approver (or by "hive")}))

(defn- hive-question
  "L1 Promote: the one-line question hive ask shows for `request`."
  [{:keys [tool actor]}]
  (str "Approve " actor "'s call of " tool "?"))

#?(:cljs nil
   :default
   (defrecord HiveAsker [ask-fn agent-id timeout-ms]
     Asker
     (-ask! [_ request]
       (future (hive-verdict (ask-fn agent-id (hive-question request) ["yes" "no"]
                                     :expects :confirm :timeout-ms timeout-ms
                                     :context (pr-str request)))))))

#?(:cljs nil
   :default
   (defn hive-asker
     "L1 adapter (clj, optional hive): asks the operator through hive's
      human ask (hive-mcp.hivemind.messaging/ask!, resolved when the asker is
      made, so the library loads without hive). Options :agent-id (default
      \"autonomous\"), :timeout-ms (default 300000) and :ask-fn, a function
      of the same signature replacing hive's. Throws when hive is absent."
     ([] (hive-asker {}))
     ([{:keys [ask-fn agent-id timeout-ms] :or {agent-id "autonomous" timeout-ms 300000}}]
      (->HiveAsker (or ask-fn (requiring-resolve 'hive-mcp.hivemind.messaging/ask!))
                   agent-id timeout-ms))))

#?(:cljs nil
   :default
   (defn stdin-asker
     "L1 adapter (clj): asks on a terminal. Options :in (a BufferedReader,
      default *in*), :out (default *err*) and :approver (default \"stdin\")."
     ([] (stdin-asker {}))
     ([{:keys [in out approver] :or {approver "stdin"}}]
      (->StdinAsker (or in *in*) (or out *err*) approver))))
