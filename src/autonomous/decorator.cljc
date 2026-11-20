(ns autonomous.decorator
  "L1 endomorphisms: decorate a compiled Tool or Surface without changing
   its input or output schema. Compose configured decorators with `comp`."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.journal :as journal]
            [autonomous.domain.wire :as wire]
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
