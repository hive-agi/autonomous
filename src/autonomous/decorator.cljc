(ns autonomous.decorator
  "L1 endomorphisms: decorate a compiled Tool or Surface without changing
   its input or output schema. Compose configured decorators with `comp`."
  (:require [autonomous.approval :as approval]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.journal :as journal]
            [autonomous.domain.wire :as wire]
            [autonomous.thenable :as thenable]
            [clojure.string :as str]
            [malli.core :as m]))

(defn require-scope
  "L1 Tool -> Tool: require one additional scope for listing and calling.
   `scope` must be a keyword (autonomous.domain.schema/Scope). Anything
   else throws ex-info :autonomous/error :invalid-scope at construction:
   a decorator that silently required nothing would leave the tool
   callable without the scope its author meant to demand."
  [scope]
  (when-not (keyword? scope)
    (throw (ex-info (str "require-scope needs a keyword scope, got " (pr-str scope))
                    {:autonomous/error :invalid-scope :scope scope})))
  (fn [tool] (update tool :scopes conj scope)))

(defn require-approval
  "L1 Tool -> Tool: every call waits for a human's Verdict from `asker`
   (autonomous.approval/Asker) and is Pending meanwhile. Approve runs the
   tool once; deny refuses :approval-denied, no Verdict within
   `timeout-ms` refuses :approval-timeout, and neither runs it. Throws
   ex-info :autonomous/error :invalid-approval on a bad option."
  [{:keys [asker timeout-ms] :as opts}]
  (when-not (and (approval/asker? asker) (pos-int? timeout-ms))
    (throw (ex-info "require-approval needs an Asker :asker and a positive :timeout-ms"
                    {:autonomous/error :invalid-approval :opts (dissoc opts :asker)})))
  (fn [tool] (assoc tool ::approval {:asker asker :timeout-ms timeout-ms})))

(def ^:private refusals
  "L1: the refusal of each Decision that does not run the tool."
  {:deny    (answer/refusal :approval-denied "a human denied this call")
   :timeout (answer/refusal :approval-timeout "no human answered in time")
   :error   (answer/refusal :approval-error "the approval asker failed")})

(defn- as-answer
  "L1 Promote: a tool's value `v` as an Answer: a complete Answer as is,
   any other value as its Ok."
  [v]
  (if (answer/complete? v) v (answer/ok v)))

(defn- stamped
  "L1 Promote: Answer `a` marked complete, carrying Decision `d` as
   :autonomous/approval metadata."
  [d a]
  (vary-meta (answer/complete a) assoc :autonomous/approval d))

(defn- approved
  "L1 Pipeline: a thenable of the Answer of thunk `run` (the refusal of
   what it throws or fails with), stamped with Decision `d`."
  [run d]
  (thenable/attempt #(thenable/chain (run) (comp (partial stamped d) as-answer))
                    (comp (partial stamped d) answer/from-exception)))

(defn- decided
  "L1 Pipeline: run thunk `run` when Decision `d` approves; else its refusal."
  [run d]
  (if (= :approve (:decision d))
    (approved run d)
    (stamped d (refusals (:decision d) (:deny refusals)))))

(defn- shown-actor
  "L1 Promote: `actor` fit to show a human: no control characters, at most
   64 characters."
  [actor]
  (let [clean (str/replace (str actor) #"\p{Cntrl}" "")]
    (subs clean 0 (min 64 (count clean)))))

(defn- approval-request
  "L1 Promote: what the asker is asked about one call: the tool name, the
   args in wire form with the journal's `redact` paths masked, the actor
   (see shown-actor) and the session."
  [tool args grant redact]
  {:tool    (:name tool)
   :args    (journal/redact-paths (wire/->wire (or args {})) redact)
   :actor   (shown-actor (:actor grant))
   :session (:session grant)})

(defn gate
  "L1 Pipeline: (run) for a tool without require-approval; else a thenable
   of the complete Answer the human's Decision leads to (see
   require-approval), carrying that Decision as :autonomous/approval. The
   human sees the args with CallDetail `redact` paths masked."
  [tool args grant redact run]
  (if-let [{:keys [asker timeout-ms]} (::approval tool)]
    (thenable/chain (approval/ask! asker (approval-request tool args grant redact) timeout-ms)
                    (partial decided run))
    (run)))

(defn each-tool
  "L1 lift: Tool -> Tool fn `f` as a Surface -> Surface fn applying it to
   every tool under the tool's existing key. Renaming is a Surface ->
   Surface concern (see `prefix-names`)."
  [f]
  (fn [surface]
    (update surface :tools #(into {} (map (fn [[k tool]] [k (f tool)])) %))))

(defn- prefixed-tool
  "L1 Promote: rekey a tool and its advertised name together."
  [prefix [name tool]]
  (let [new-name (str prefix name)]
    [new-name (assoc tool :name new-name)]))

(defn- prefix-tools
  "L1 Promote: rename all tools. Never truncate or overwrite; a name the
   prefix makes invalid stays in the result for validation to refuse."
  [prefix tools]
  (into {} (map (partial prefixed-tool prefix)) tools))

(defn prefix-names
  "L1 Surface -> Surface: prepend an exact string (e.g. \"fleet_\") to all
   tool names. A name the prefix makes invalid (MCP's ToolName) is kept
   as is, so autonomous.surface/decorate refuses the whole result and a
   registration that would produce it throws, changing nothing. It never
   falls back to the unprefixed names: decorators re-run on every
   registration, and one bad name must not rename the whole surface back.
   Resources retain their URIs and display names."
  [prefix]
  (fn [surface] (update surface :tools (partial prefix-tools prefix))))

(defn redact-output
  "L1 Tool -> Tool: mask existing output paths (vectors of keys/indices)
   the way the journal masks args (autonomous.domain.journal/redact-paths).
   The default replacement is the string \"<redacted>\". Choose a different
   replacement if that would violate the tool's declared output schema.

   Paths address the output in WIRE form (autonomous.domain.wire/->wire):
   a lazy seq or list is a vector there, so [:accounts 0 :token] reaches
   into {:accounts (map ...)}. They apply to Ok outputs only, never to a
   refusal's :data: that is a different value (a throw's ex-data, a
   codec's diagnosis) with its own shape, where output paths would mask
   unrelated keys or miss the ones that matter. A tool that can refuse
   with a secret in its data must keep the secret out of the refusal."
  ([paths] (redact-output paths journal/redacted))
  ([paths replacement]
   (fn [tool]
     (update tool ::output-filters (fnil conj [])
             #(journal/redact-paths % paths replacement)))))

(defn project-output
  "L1 Pipeline: apply redactions to the wire form of `raw`, so a path
   through a seq or list reaches what the bridge would carry. Refuse
   rather than returning a masked output that violates its declared
   schema. Without redactions `raw` passes unchanged."
  [tool raw]
  (if-let [filters (seq (::output-filters tool))]
    (let [value (reduce (fn [v f] (f v)) (wire/->wire raw) filters)]
      (if (and (:output tool) (not (m/validate (:output tool) value)))
        (answer/refusal :output/invalid-redaction "redaction violates the output schema")
        (answer/ok value)))
    (answer/ok raw)))
