(ns autonomous.decorator
  "L1 endomorphisms: decorate a compiled Tool or Surface without changing
   its input or output schema. Compose configured decorators with `comp`."
  (:require [autonomous.approval :as approval]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.journal :as journal]
            [autonomous.domain.wire :as wire]
            [autonomous.thenable :as thenable]
            [malli.core :as m]
            [malli.error :as me]))

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

(def approval-ttl-margin-ms
  "L1: how long before the pending TTL an approval wait must end."
  (* 60 1000))

(def max-approval-ms
  "L1: require-approval's :timeout-ms must stay below this: the runtime's
   pending TTL (autonomous.runtime.registry/pending-ttl-ms, 10 minutes)
   less approval-ttl-margin-ms, so an approved call's result is collectable."
  (- (* 10 60 1000) approval-ttl-margin-ms))

(defn require-approval
  "L1 Tool -> Tool: every call waits for a human's Verdict from `asker`
   (autonomous.approval/Asker) and is Pending meanwhile. Approve runs the
   tool once; deny refuses :approval-denied, no Verdict within
   `timeout-ms` refuses :approval-timeout, and neither runs it. Throws
   ex-info :autonomous/error :invalid-approval on a bad option."
  [{:keys [asker timeout-ms] :as opts}]
  (when-not (and (approval/asker? asker) (pos-int? timeout-ms) (< timeout-ms max-approval-ms))
    (throw (ex-info (str "require-approval needs an Asker :asker and a :timeout-ms in 1.."
                         (dec max-approval-ms))
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

(defn- approval-request
  "L1 Promote: what the asker is asked about one call: the tool name, the
   args in wire form with the journal's `redact` paths masked, the actor
   and the session, as data. Rendering them for a human (escaping,
   capping) is the asker's (autonomous.approval/prompt-lines)."
  [tool args grant redact]
  {:tool    (:name tool)
   :args    (journal/redact-paths (wire/->wire (or args {})) redact)
   :actor   (:actor grant)
   :session (:session grant)})

(defn- strip-approval
  "L1 Promote: `v` without :autonomous/approval metadata."
  [v]
  (if (contains? (meta v) :autonomous/approval) (vary-meta v dissoc :autonomous/approval) v))

(defn- unstamped
  "L1 Pipeline: an ungated tool's value `x`, now or once computed, with no
   :autonomous/approval metadata, so only the gate can journal an approval."
  [x]
  (if (thenable/thenable? x) (thenable/chain x strip-approval) (strip-approval x)))

(defn gate
  "L1 Pipeline: (run) for a tool without require-approval; else a thenable
   of the complete Answer the human's Decision leads to (see
   require-approval), carrying that Decision as :autonomous/approval. The
   human sees the args with CallDetail `redact` paths masked."
  [tool args grant redact run]
  (if-let [{:keys [asker timeout-ms]} (::approval tool)]
    (thenable/chain (approval/ask! asker (approval-request tool args grant redact) timeout-ms)
                    (partial decided run))
    (unstamped (run))))

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

(defn- redaction
  "L1: one redaction as data, {:paths :replacement}. The closure that
   applies it is built where it runs (`apply-redactions`), so two equal
   decorations leave equal tools and merge without a clash."
  [paths replacement]
  {:paths (vec paths) :replacement replacement})

(defn- apply-redactions
  "L1: wire-form `value` with every redaction of `redactions` applied in order."
  [value redactions]
  (reduce (fn [v {:keys [paths replacement]}] (journal/redact-paths v paths replacement))
          value redactions))

(defn redact-output
  "L1 Tool -> Tool: mask existing output paths (vectors of keys/indices)
   the way the journal masks args (autonomous.domain.journal/redact-paths).
   The default replacement is the string \"<redacted>\". Choose a different
   replacement if that would violate the tool's declared output schema.

   Paths address the output in WIRE form (autonomous.domain.wire/->wire):
   a lazy seq or list is a vector there, so [:accounts 0 :token] reaches
   into {:accounts (map ...)}. The declared :output schema is then checked
   against that wire form, not the raw value: a schema that needs map keys
   other than keywords or strings, or values the wire carries as strings
   (a LocalDate, a URI), fails the check even where no path matched, and
   the call is refused :output/invalid-redaction. Declare such an output
   in wire terms.

   They apply to Ok outputs only, never to a refusal's :data: that is a
   different value (a throw's ex-data, a codec's diagnosis) with its own
   shape, where output paths would mask unrelated keys or miss the ones
   that matter. Mask refusal data with `redact-refusal`.

   The redaction is stored on the tool as data, so applying one decorator
   to two copies of a surface leaves tools that compare equal."
  ([paths] (redact-output paths journal/redacted))
  ([paths replacement]
   (fn [tool]
     (update tool ::output-redactions (fnil conj []) (redaction paths replacement)))))

(defn project-output
  "L1 Pipeline: apply redactions to the wire form of `raw`, so a path
   through a seq or list reaches what the bridge would carry. Refuse
   (:output/invalid-redaction) rather than returning a masked output
   whose wire form does not match the declared :output schema; see
   `redact-output` for why the check sees the wire form. Without
   redactions `raw` passes unchanged."
  [tool raw]
  (if-let [redactions (seq (::output-redactions tool))]
    (let [value (apply-redactions (wire/->wire raw) redactions)]
      (if-let [e (some-> (:output tool) (m/explain value))]
        (answer/refusal :output/invalid-redaction
                        "the output, in wire form after redaction, does not match the declared output schema"
                        {:problems (me/humanize e)})
        (answer/ok value)))
    (answer/ok raw)))

(defn redact-refusal
  "L1 Tool -> Tool, opt in: mask paths (vectors of keys/indices) in the
   :data of every refusal the tool's own invocation answers: what its
   handler or codec refuses with, what it throws (the ex-data minus
   :autonomous/error, autonomous.domain.answer/from-exception), and how an
   async result fails. The path [] replaces the whole :data. The default
   replacement is \"<redacted>\".

   The data is already in wire form when the paths apply. Refusals the
   runtime itself makes before the tool runs (scopes, arguments, leases)
   are not touched. The refusal's :message is never masked: a tool whose
   exceptions can name a secret in their message must catch them."
  ([paths] (redact-refusal paths journal/redacted))
  ([paths replacement]
   (fn [tool]
     (update tool ::refusal-redactions (fnil conj []) (redaction paths replacement)))))

(defn project-refusal
  "L1 Pipeline: Answer `a` with the tool's refusal redactions applied to
   the wire form (autonomous.domain.wire/->wire) of a refusal's :data, so
   a path through a list or seq reaches what the bridge would carry even
   when an adapter built the refusal map itself. Ok, Pending, a refusal
   without :data and a tool without refusal redactions pass unchanged."
  [tool a]
  (let [redactions (seq (::refusal-redactions tool))]
    (if (and redactions (answer/refused? a) (contains? (:error a) :data))
      (update-in a [:error :data] #(apply-redactions (wire/->wire %) redactions))
      a)))
