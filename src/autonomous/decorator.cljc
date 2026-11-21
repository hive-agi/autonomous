(ns autonomous.decorator
  "L1 endomorphisms: decorate a compiled Tool or Surface without changing
   its input or output schema. Compose configured decorators with `comp`."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.journal :as journal]
            [autonomous.domain.wire :as wire]
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
   a refusal's :data. Ok, Pending, a refusal without :data and a tool
   without refusal redactions pass unchanged."
  [tool a]
  (let [redactions (seq (::refusal-redactions tool))]
    (if (and redactions (answer/refused? a) (contains? (:error a) :data))
      (update-in a [:error :data] apply-redactions redactions)
      a)))
