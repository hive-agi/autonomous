(ns autonomous.bridge.eval-source
  "How a Request becomes REPL source, and how REPL output becomes an Answer.
   Pure, so the trickiest part of the nREPL channel (quoting, double
   printing, shadow's result envelope) is tested without any REPL.

   Modes:
     :shadow  evaluate in a ClojureScript runtime through shadow-cljs:
              (shadow.cljs.devtools.api/cljs-eval build \"<cljs source>\" opts)
     :clj     evaluate in the Clojure process the nREPL serves (backend)."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.wire :as wire]))

(defn runtime-source
  "Source the RUNTIME evaluates for `request`: a call of
   autonomous.core/rpc-str on the printed request."
  [request]
  (wire/print-edn (list 'autonomous.core/rpc-str (wire/print-edn request))))

(defn request-source
  "Source the nREPL server evaluates for `request` in `mode`. In :shadow mode
   `build` names the shadow build and a non-nil `runtime-id` pins the eval to
   one connected runtime."
  [mode build request runtime-id]
  (case mode
    :clj    (runtime-source request)
    :shadow (wire/print-edn
             (list 'shadow.cljs.devtools.api/cljs-eval
                   (keyword build)
                   (runtime-source request)
                   (if runtime-id {:runtime-id runtime-id} {})))))

(defn runtimes-source
  "Source listing the runtimes connected to shadow `build`."
  [build]
  (wire/print-edn
   (list 'mapv
         (list 'fn ['r] (list 'select-keys 'r [:client-id :host :user-agent :desc :since]))
         (list 'shadow.cljs.devtools.api/repl-runtimes (keyword build)))))

(def ^:private missing-runtime-hint
  "the runtime did not answer like autonomous: require autonomous.core (and your registrations) in the app build, and make sure the app has loaded")

(defn- safe-read
  "L3 Boundary: {:value v} of reading EDN text `s`, or {:failed message}."
  [s]
  (try {:value (wire/read-edn s)} (catch Exception e {:failed (ex-message e)})))

(defn- answer-shaped?
  "L1 Promote: true when `value` is a map with exactly one Answer variant key."
  [value]
  (and (map? value) (= 1 (count (select-keys value [:ok :pending :error])))))

(defn- excerpt
  "L1 Promote: the first 400 characters of `raw` printed as a string."
  [raw]
  (let [s (str raw)] (subs s 0 (min 400 (count s)))))

(defn- not-autonomous
  "L1 Promote: the :channel/not-autonomous refusal for REPL output `raw`."
  [raw read-error]
  (answer/refusal :channel/not-autonomous missing-runtime-hint
                  {:raw (excerpt raw) :read-error read-error}))

(defn- answer-or-refusal
  "L2 Pipeline: the Answer read from text (or value) `s`, else a
   :channel/not-autonomous refusal quoting `raw`."
  [s raw]
  (let [{:keys [value failed]} (if (string? s) (safe-read s) {:value s})]
    (if (answer-shaped? value) value (not-autonomous raw failed))))

(defn- read-printed
  "L3 Boundary: Ok of the EDN value nREPL printed, or a :channel/unreadable
   refusal."
  [printed]
  (let [{:keys [value failed]} (safe-read printed)]
    (if failed
      (answer/refusal :channel/unreadable "the REPL value is not EDN" {:raw printed :read-error failed})
      (answer/ok value))))

(defn- no-runtime
  "L1 Promote: the :channel/no-runtime refusal for REPL output `printed`."
  [printed]
  (answer/refusal :channel/no-runtime
                  "no runtime answered: is the app loaded in a browser or node process connected to this build?"
                  {:raw printed}))

(defn- shadow-answer
  "L2 Pipeline: the Answer inside shadow's {:results [..] :err ..} envelope
   `outer`: the last result read as an Answer, else the error, else none."
  [printed outer]
  (cond
    (not (map? outer))     (no-runtime printed)
    (seq (:results outer)) (answer-or-refusal (:value (safe-read (peek (vec (:results outer))))) printed)
    (seq (:err outer))     (answer/refusal :channel/runtime-error (str (:err outer)) {:raw printed})
    :else                  (no-runtime printed)))

(defn- answer-of
  "L2 Pipeline: the Answer REPL value `outer` carries in `mode`; every mode
   but :clj reads the shadow envelope."
  [mode printed outer]
  (case mode
    :clj (answer-or-refusal outer printed)
    (shadow-answer printed outer)))

(defn parse-value
  "L2 Pipeline: the Answer carried by nREPL `printed` value (the :value
   text of the eval) in `mode`. :clj prints the rpc-str string once;
   :shadow prints a {:results [..] :err ..} map whose last result is that
   string printed. Always an Answer."
  [mode printed]
  (answer/bind (read-printed printed) #(answer-of mode printed %)))

(defn- runtime-view
  "L1 Promote: the Runtime view of one shadow runtime map."
  [{:keys [client-id host user-agent desc]}]
  {:runtime/id    client-id
   :runtime/kind  (or host :unknown)
   :runtime/label (or desc user-agent (str (name (or host :runtime)) " " client-id))})

(defn parse-runtimes
  "L1 Promote: [{:runtime/id :runtime/kind :runtime/label}] from shadow
   runtime maps."
  [runtimes]
  (mapv runtime-view runtimes))

(def clj-runtimes
  "L1 Promote: Ok of the one runtime a :clj nREPL serves, its own process."
  (answer/ok [{:runtime/id "jvm" :runtime/kind :clj :runtime/label "Clojure process"}]))

(defn runtimes-answer
  "L2 Pipeline: Ok of the Runtime views in Answer `printed` (the printed
   runtimes-source value); a refusal passes through."
  [printed]
  (answer/bind printed #(answer/ok (parse-runtimes (wire/read-edn %)))))
