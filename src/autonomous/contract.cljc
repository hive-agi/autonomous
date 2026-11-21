(ns autonomous.contract
  "L3 adapter contract kit: checks over a Source, its Codec and host ports
   supplied by a fixture. `check-adapter` answers data; assertion reporting
   is a separate clojure.test boundary."
  (:require [autonomous.decorator :as decorator]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.schema :as s]
            [autonomous.spi :as spi]
            [autonomous.surface :as surface]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            #?(:clj [clojure.test :as t] :cljs [cljs.test :as t :include-macros true]))
  #?(:cljs (:require-macros [autonomous.domain.answer :as answer])))

(def Check
  "One named contract result, with data describing any failure."
  [:map [:check :keyword] [:ok? :boolean] [:detail :any]])

(def Contract
  "The result of checking every applicable port of an adapter."
  [:map [:ok? :boolean] [:checks [:vector Check]]])

(defn- result
  "L3 Contract: make one check result."
  ([id ok?] (result id ok? nil))
  ([id ok? detail] {:check id :ok? (boolean ok?) :detail detail}))

(defn- from-results
  "L3 Contract: one check succeeds exactly when all its sub-results do."
  [id results]
  (result id (every? (comp :ok? val) results)
          (not-empty (into {} (remove (comp :ok? val)) results))))

(defn- answer-receipt->check
  "L3 Promote: accept a Check, unwrap Ok, or make a refusal a failed Check."
  [id a]
  (cond
    (m/validate Check a) a
    (answer/ok? a) (:ok a)
    :else (result id false a)))

(defn- declaration
  "L3 Collect: read an AdapterSpec or its source dispatch key."
  [adapter]
  (if (qualified-keyword? adapter) {:source-kind adapter} adapter))

(defn- derive-from
  "L3 Boundary: derive one Surface through the SPI."
  [adapter fixture source]
  (spi/derive-surface (or (:source-kind fixture) (:source-kind adapter))
                      source (or (:opts fixture) {})))

(defn- surface-answer
  "L3 Collect: reuse the derivation shared by all checks in one run."
  [adapter fixture]
  (if-let [cached (::surface fixture)] @cached
      (derive-from adapter fixture (:source fixture))))

(defn- no-affordances
  "L3 Contract: a derived value has no tools or resources."
  [a]
  (answer/bind a #(answer/ok (and (empty? (:tools %)) (empty? (:resources %))))))

(defn opt-in-only
  "L3 Contract: no opt-in derives no affordances; the opted-in source
   derives exactly the fixture's :exposed-tools names."
  [adapter fixture]
  (let [unexposed (no-affordances (derive-from adapter fixture (:unexposed-source fixture)))
        exposed (surface-answer adapter fixture)
        names (answer/bind exposed #(answer/ok (set (keys (:tools %)))))]
    (from-results :opt-in-only
                  {:without-opt-in (result :opt-in/none (true? (:ok unexposed)) unexposed)
                   :exposed (result :opt-in/exact (= (set (:exposed-tools fixture)) (:ok names))
                                    {:expected (set (:exposed-tools fixture)) :actual names})})))

(defn human-code-path
  "L3 Contract: the fixture's :human-call zero-argument port returns equal
   values before and after its :mount zero-argument port, when supplied."
  [_adapter fixture]
  (let [human (:human-call fixture)
        before (when human (human))
        mounted (when-let [mount (:mount fixture)] (mount))
        after (when human (human))]
    (result :human-code-path (and (fn? human) (= before after))
            {:before before :after after :mounted mounted :human-port? (fn? human)})))

(defn unmount-empty
  "L3 Contract: the fixture's :empty-source derives exactly empty-surface,
   including empty tool and resource maps."
  [adapter fixture]
  (let [a (derive-from adapter fixture (:empty-source fixture))]
    (result :unmount-empty (= (answer/ok surface/empty-surface) a)
            {:expected surface/empty-surface :actual a})))

(defn- schema-receipt
  "L3 Contract: validate one tool value against its declared schema."
  [id tool-name value schema]
  (if-let [e (m/explain schema value)]
    (result id false {:tool tool-name :value value :problems (me/humanize e)})
    (result id true)))

(defn- sample-args
  "L3 Collect: a fixture sample keyed by tool name or id."
  [fixture tool]
  (let [samples (:samples fixture {})]
    (or (get samples (:name tool)) (get samples (:id tool)) (:default samples))))

(defn- input-schema
  "L3 Collect: a tool's input schema; an omitted :input is [:map], as
   autonomous.domain.affordance compiles it."
  [tool]
  (or (:input tool) [:map]))

(defn- codec-tool?
  "L3 Collect: the adapter's Codec serves exactly the tools of its :kind,
   as spi/register-adapter! installs it on invoke-kind."
  [adapter tool]
  (and (some? (:codec adapter)) (= (:kind adapter) (:kind tool))))

(defn- host-port
  "L3 Collect: the fixture's :host, else the AdapterSpec's :invoke port."
  [adapter fixture]
  (or (:host fixture) (:invoke adapter)
      (fn [_ _ _] (answer/refusal :contract/no-host
                                  "fixture supplies no :host and the AdapterSpec no :invoke"))))

(defn- invoke-sample
  "L3 Boundary: one sample crosses codec, host and decoder as Answers."
  [adapter fixture tool args]
  (answer/let-answer [input (spi/encode-call (:codec adapter) tool args)
                      output ((host-port adapter fixture) tool input (or (:grant fixture) {}))]
    (spi/decode-result (:codec adapter) tool output)))

(defn- output-receipt
  "L3 Contract: an Ok output satisfies a declared output schema."
  [tool-name tool a]
  (if-let [output (:output tool)]
    (if (answer/ok? a)
      (schema-receipt :schema/output tool-name (:ok a) output)
      (result :schema/output false {:tool tool-name :answer a :expected "Ok output"}))
    (result :schema/output true {:tool tool-name :output-schema :undeclared})))

(defn- sample-round-trip
  "L3 Contract: one tool's sample completes encode -> host -> decode."
  [adapter fixture [tool-name tool]]
  (let [args (sample-args fixture tool)
        input (schema-receipt :schema/input tool-name args (input-schema tool))
        codec? (codec-tool? adapter tool)
        a (when (and codec? (:ok? input)) (invoke-sample adapter fixture tool args))]
    [tool-name
     (cond
       (not (:ok? input)) input
       (not (:codec adapter)) (result :schema/round-trip true {:tool tool-name :codec :undeclared})
       (not codec?) (result :schema/round-trip true
                            {:tool tool-name :kind (:kind tool) :codec :other-kind})
       (answer/refused? a) (result :schema/round-trip false {:tool tool-name :answer a})
       :else (output-receipt tool-name tool a))]))

(defn- round-trip-receipts
  "L3 Contract: every tool's sample crosses its declared ports."
  [adapter fixture surf]
  (into {} (map #(sample-round-trip adapter fixture %)) (:tools surf)))

(defn schema-round-trip
  "L3 Contract: the Surface passes ->surface, fixture samples validate
   against tool inputs, and each codec-backed sample answers Ok with an
   output valid against a declared :output schema."
  [adapter fixture]
  (answer-receipt->check :schema-round-trip
    (answer/bind (surface-answer adapter fixture)
      (fn [surf]
        (answer/bind (surface/->surface surf)
                     #(answer/ok (from-results :schema-round-trip
                                              (round-trip-receipts adapter fixture %))))))))

(defn- schemas-of
  "L3 Collect: schemas keyed by tool name."
  [surf]
  (into {} (map (fn [[n t]] [n (select-keys t [:input :output])])) (:tools surf)))

(defn- decorate-tool
  "L3 Promote: apply both tool decorators to every tool."
  [surf]
  (update surf :tools update-vals (comp (decorator/require-scope :contract/read)
                                        (decorator/redact-output [[:secret]]))))

(defn- decorated-surface
  "L3 Promote: apply tool decorators, then the surface decorator."
  [surf]
  ((decorator/prefix-names "contract_") (decorate-tool surf)))

(defn- decorator-receipt
  "L3 Contract: compare original and decorated schemas by renamed tool."
  [[n schemas] decorated]
  (let [actual (get (schemas-of decorated) (str "contract_" n))]
    [n (result :decorators/schema (and (surface/surface? decorated) (= schemas actual))
               {:tool n :expected schemas :actual actual})]))

(defn decorators-preserve-schemas
  "L3 Contract: require-scope, redact-output and prefix-names preserve a
   valid Surface and every tool's :input and :output schemas."
  [adapter fixture]
  (answer-receipt->check :decorators-preserve-schemas
    (answer/bind (surface-answer adapter fixture)
      (fn [surf]
        (let [decorated (decorated-surface surf)
              results (into {} (map #(decorator-receipt % decorated)) (schemas-of surf))]
          (answer/ok (from-results :decorators-preserve-schemas results)))))))

(def malformed-args
  "Malformed argument values every codec must contain."
  [nil {} "not an argument map"])

(defn- generated-args
  "L3 Generate: deterministic valid inputs for one tool schema."
  [tool fixture]
  (mapv #(mg/generate (input-schema tool) {:seed % :size 20})
        (range (or (:generated-samples fixture) 8))))

(defn- argument-cases
  "L3 Generate: generated, fixture and malformed argument cases."
  [tool fixture]
  (vec (distinct (concat (generated-args tool fixture)
                         (when-let [sample (sample-args fixture tool)] [sample])
                         malformed-args))))

(defn- answer-receipt
  "L3 Contract: require one codec crossing to answer Ok or the codec's own
   refusal, never one the SPI guard had to make for it (spi/guard-refusal?,
   which reads the guard's marker, so a codec may refuse with the type
   :codec/encode itself)."
  [stage tool-name args a]
  (result :codecs/total (and (m/validate [:or s/Ok s/Refusal] a)
                             (not (spi/guard-refusal? a)))
          {:stage stage :tool tool-name :args args :answer a}))

(defn- encode-receipt
  "L3 Boundary: encode one argument case through the SPI."
  [adapter tool-name tool args]
  (let [a (spi/encode-call (:codec adapter) tool args)]
    [a (answer-receipt :encode tool-name args a)]))

(defn- decode-receipt
  "L3 Boundary: decode one fixture host output."
  [adapter tool-name tool args output]
  (answer-receipt :decode tool-name args (spi/decode-result (:codec adapter) tool output)))

(defn- tool-codec-receipts
  "L3 Contract: encode all argument cases and decode all fixture outputs."
  [adapter fixture [tool-name tool]]
  (for [args (argument-cases tool fixture)
        :let [[encoded encoded-ok] (encode-receipt adapter tool-name tool args)]
        receipt (cons encoded-ok
                      (when (answer/ok? encoded)
                        (map #(decode-receipt adapter tool-name tool args %)
                             (:decode-outputs fixture [nil {} "malformed"]))))]
    receipt))

(defn- codec-receipts
  "L3 Contract: collect codec results for every tool in the Surface."
  [adapter fixture surf]
  (let [tools (filter (comp #(codec-tool? adapter %) val) (:tools surf))]
    (cond
      (not (:codec adapter)) {:codec (result :codecs/total false "AdapterSpec declares no :codec")}
      (empty? tools) {:codec (result :codecs/total false
                                     {:kind (:kind adapter) :problem "no derived tool has the Codec's :kind"})}
      :else (into {} (map (fn [[tool-name :as entry]]
                            [tool-name (from-results :codecs/total
                                                     (into {} (map-indexed (fn [i r] [i r]))
                                                           (tool-codec-receipts adapter fixture entry)))]))
                  tools))))

(defn codecs-total
  "L3 Contract: generated, fixture and malformed arguments encode to Ok or
   a refusal; every fixture decode output decodes to Ok or a refusal. The
   refusal must be the codec's own: a throw, a missing port, Pending or a
   non-Answer, which the SPI guard turns into :codec/encode or
   :codec/decode, fails the check."
  [adapter fixture]
  (answer-receipt->check :codecs-total
    (answer/bind (surface-answer adapter fixture)
      #(answer/ok (from-results :codecs-total (codec-receipts adapter fixture %))))))

(def checks
  "The ordered adapter contract, one function per independent obligation."
  [[:opt-in-only opt-in-only]
   [:human-code-path human-code-path]
   [:unmount-empty unmount-empty]
   [:schema-round-trip schema-round-trip]
   [:decorators-preserve-schemas decorators-preserve-schemas]
   [:codecs-total codecs-total]])

(defn- run-check
  "L3 Boundary: contain a fixture or check failure as a failed Check."
  [[id f] adapter fixture]
  (try
    (answer-receipt->check id (f adapter fixture))
    (catch #?(:clj Throwable :cljs :default) e
      (result id false (answer/from-exception e)))))

(defn check-adapter
  "L3 Contract: check an AdapterSpec (or source-kind dispatch key) against
   a fixture. Answers {:ok? bool :checks [Check]}. Fixture ports are
   :source, :unexposed-source, :empty-source, :exposed-tools, :samples,
   :human-call, optional :mount, :host (default: the AdapterSpec's :invoke
   port), :grant, :opts, :decode-outputs and :generated-samples. The Codec
   is checked against the derived tools of the AdapterSpec's :kind. No
   global registry is read or written."
  [adapter fixture]
  (let [spec (declaration adapter)
        shared (assoc fixture ::surface (delay (derive-from spec fixture (:source fixture))))
        results (mapv #(run-check % spec shared) checks)]
    {:ok? (every? :ok? results) :checks results}))

(defn assert-contract
  "clojure.test / cljs.test boundary: assert every Check with its full
   detail. Answers the original Contract value."
  [contract]
  (doseq [{:keys [check ok? detail]} (:checks contract)]
    (t/is ok? (str check " " (pr-str detail))))
  contract)
