(ns autonomous.decorator
  "L1 endomorphisms: decorate a compiled Tool or Surface without changing
   its input or output schema. Compose configured decorators with `comp`."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.journal :as journal]
            [autonomous.domain.naming :as naming]
            [malli.core :as m]))

(defn require-scope
  "L1 Tool -> Tool: require one additional scope for listing and calling."
  [scope]
  (fn [tool] (cond-> tool (keyword? scope) (update :scopes conj scope))))

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
  "L1 Promote: rename all tools, or keep the original map if any name
   would exceed MCP's name constraints. Never truncate or overwrite."
  [prefix tools]
  (let [renamed (into {} (map (partial prefixed-tool prefix)) tools)]
    (if (every? naming/valid-tool-name? (keys renamed)) renamed tools)))

(defn prefix-names
  "L1 Surface -> Surface: prepend an exact string (e.g. \"fleet_\") to all
   tool names. If any name would be invalid, leave the whole surface
   unchanged. Resources retain their URIs and display names."
  [prefix]
  (fn [surface] (update surface :tools (partial prefix-tools prefix))))

(defn redact-output
  "L1 Tool -> Tool: mask existing output paths (vectors of keys/indices)
   the way the journal masks args (autonomous.domain.journal/redact-paths).
   The default replacement is the string \"<redacted>\". Choose a different
   replacement if that would violate the tool's declared output schema."
  ([paths] (redact-output paths journal/redacted))
  ([paths replacement]
   (fn [tool]
     (update tool ::output-filters (fnil conj [])
             #(journal/redact-paths % paths replacement)))))

(defn project-output
  "L1 Pipeline: apply redactions before wire normalization. Refuse rather
   than returning a masked output that violates its declared schema."
  [tool raw]
  (if-let [filters (seq (::output-filters tool))]
    (let [value (reduce (fn [v f] (f v)) raw filters)]
      (if (and (:output tool) (not (m/validate (:output tool) value)))
        (answer/refusal :output/invalid-redaction "redaction violates the output schema")
        (answer/ok value)))
    (answer/ok raw)))
