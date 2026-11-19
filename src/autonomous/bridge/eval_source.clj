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

(defn- safe-read [s]
  (try {:value (wire/read-edn s)} (catch Exception e {:failed (ex-message e)})))

(defn- answer-or-refusal
  "An Answer read from text `s`, or a :channel/not-autonomous refusal."
  [s raw]
  (let [{:keys [value failed]} (if (string? s) (safe-read s) {:value s})]
    (if (and (map? value)
             (= 1 (count (select-keys value [:ok :pending :error]))))
      value
      (answer/refusal :channel/not-autonomous missing-runtime-hint
                      {:raw (subs (str raw) 0 (min 400 (count (str raw)))) :read-error failed}))))

(defn parse-value
  "The Answer carried by nREPL `printed` value (the :value text of the
   eval) in `mode`. :clj prints the rpc-str string once; :shadow prints a
   {:results [..] :err ..} map whose last result is that string printed."
  [mode printed]
  (let [{outer :value failed :failed} (safe-read printed)]
    (cond
      failed
      (answer/refusal :channel/unreadable "the REPL value is not EDN" {:raw printed :read-error failed})

      (= :clj mode)
      (answer-or-refusal outer printed)

      (and (map? outer) (seq (:results outer)))
      (let [{inner :value} (safe-read (peek (vec (:results outer))))]
        (answer-or-refusal inner printed))

      (and (map? outer) (seq (:err outer)))
      (answer/refusal :channel/runtime-error (str (:err outer)) {:raw printed})

      :else
      (answer/refusal :channel/no-runtime
                      "no runtime answered: is the app loaded in a browser or node process connected to this build?"
                      {:raw printed}))))

(defn parse-runtimes
  "[{:runtime/id :runtime/kind :runtime/label}] from shadow runtime maps."
  [runtimes]
  (mapv (fn [{:keys [client-id host user-agent desc]}]
          {:runtime/id    client-id
           :runtime/kind  (or host :unknown)
           :runtime/label (or desc user-agent (str (name (or host :runtime)) " " client-id))})
        runtimes))
