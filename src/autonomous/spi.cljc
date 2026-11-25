(ns autonomous.spi
  "L3 extension ports. Source and Codec are independent; a host's mount
   is a plain function or a native hook, not a kernel dependency."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.schema :as s]
            [autonomous.runtime.invoke :as invoke]
            [autonomous.surface :as surface]
            [malli.core :as m]
            [malli.error :as me])
  #?(:cljs (:require-macros [autonomous.domain.answer :as answer])))

(defmulti derive-surface
  "L3 Source: (kind source opts) -> Ok Surface or a refusal. Namespace
   each source kind and load its adapter before deriving."
  (fn [kind _source _opts] kind))

(defmethod derive-surface :default [kind _ _]
  (answer/refusal :autonomous/unknown-source
                  (str "no surface derivation for source kind " (pr-str kind)
                       "; load the namespace that provides it")))

(def Codec
  "L3 Codec: pure :encode [tool args] and :decode [tool host-output].
   Both return Ok or a refusal, including on malformed input."
  [:map [:encode fn?] [:decode fn?]])

(def ^:private Kind
  [:and :keyword [:fn {:error/message "must be a namespaced keyword"} qualified-keyword?]])

(defn- paired-ports?
  "L3 Promote: each declared dispatch key must have its port and vice versa."
  [{:keys [source-kind derive kind codec invoke]}]
  (and (= (some? source-kind) (some? derive))
       (= (some? kind) (some? codec))
       (or (some? derive) (some? codec))
       (or (nil? invoke) (some? codec))))

(def AdapterSpec
  "L3 adapter declaration: Source, Codec, or both. An optional :invoke
   port takes [tool host-input grant] and returns Ok output or a refusal."
  [:and
   [:map [:source-kind {:optional true} Kind] [:derive {:optional true} fn?]
    [:kind {:optional true} Kind] [:codec {:optional true} Codec]
    [:invoke {:optional true} fn?]]
   [:fn {:error/message "supply :source-kind with :derive, :kind with :codec, or both"}
    paired-ports?]])

(def guard-marker
  "The :data key every refusal the SPI guard makes for a broken port
   carries, set to true, so a caller tells the guard's refusal from the
   port's own refusal of the same type."
  :autonomous.spi/guard)

(defn guard-refusal?
  "True when Answer `a` is a refusal the SPI guard made for a broken port."
  [a]
  (true? (get-in a [:error :data guard-marker])))

(defn- guard-answer
  "L3 boundary: diagnose a broken port without letting it throw. The
   refusal carries `guard-marker`."
  [type message schema f]
  (try
    (let [a (f)]
      (if (and (m/validate schema a) (= 1 (count (select-keys a [:ok :error :pending]))))
        a
        (answer/refusal type message {guard-marker true})))
    (catch #?(:clj Throwable :cljs :default) e
      (answer/refusal type message {guard-marker true :cause (ex-message e)}))))

(defn encode-call
  "L3 Codec: encode a call, containing exceptions and non-Answers."
  [{:keys [encode]} tool args]
  (guard-answer :codec/encode "adapter could not encode call" [:or s/Ok s/Refusal]
                #(encode tool args)))

(defn decode-result
  "L3 Codec: decode host output, containing exceptions and non-Answers."
  [{:keys [decode]} tool output]
  (guard-answer :codec/decode "adapter could not decode host output" [:or s/Ok s/Refusal]
                #(decode tool output)))

(defn- mounted-invoke
  "L3 boundary: cross the host port carried by a mounted tool."
  [tool input grant]
  (if-let [host (:host/invoke tool)]
    (host input grant)
    (answer/refusal :adapter/not-mounted "the tool has no mounted host port")))

(defn- host-answer
  "L3 boundary: run the host's own path; keep typed host refusals."
  [host tool input grant]
  (guard-answer :adapter/host "host port must return Ok or a refusal" [:or s/Ok s/Refusal]
                #(try (host tool input grant)
                      (catch #?(:clj Throwable :cljs :default) e (answer/from-exception e)))))

(defn codec->invoke
  "L3 Pipeline: build an invoke-kind method from a Codec. The only effect
   is the host port, supplied here or on each tool under :host/invoke."
  ([codec] (codec->invoke codec mounted-invoke))
  ([codec host]
   (fn [tool args grant]
     (invoke/return-answer
      (answer/let-answer [input (encode-call codec tool args)
                          output (host-answer host tool input grant)]
        (decode-result codec tool output))))))

(defn- source-method
  "L3 boundary: run a Source and require an Answer containing a Surface."
  [derive]
  (fn [_ source opts]
    (answer/bind (guard-answer :adapter/derive "adapter could not derive surface" [:or s/Ok s/Refusal]
                               #(derive source opts))
                 surface/->surface)))

(defn- install-method!
  "L3 boundary: install a method using the host's multimethod API."
  [mm k f]
  #?(:cljs (cljs.core/-add-method mm k f)
     :clj (.addMethod ^clojure.lang.MultiFn mm k f)))

(defn- install-adapter!
  "L3 boundary: install each declared port of a validated AdapterSpec."
  [{:keys [source-kind derive kind codec invoke] :as spec}]
  (when derive (install-method! derive-surface source-kind (source-method derive)))
  (when codec (install-method! invoke/invoke-kind kind (codec->invoke codec (or invoke mounted-invoke))))
  (answer/ok spec))

(defn register-adapter!
  "L3 boundary: validate the entire AdapterSpec before installing its
   methods. Returns Ok of the declaration or an :invalid-adapter refusal.
   Installing the same dispatch key replaces that method on hot reload."
  [spec]
  (if-let [e (m/explain AdapterSpec spec)]
    (answer/refusal :invalid-adapter "adapter spec is invalid" (me/humanize e))
    (install-adapter! spec)))
