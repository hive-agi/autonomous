(ns autonomous.domain.affordance
  "From what an author writes to what the registry holds. `->tool` and
   `->resource` validate a spec against its malli schema and compile it: the
   tool's malli :input becomes the JSON Schema agents read, plus a decoder
   (JSON-ish args to Clojure values) and an explainer. Pure: answers
   {:ok value} or a refusal, never throws."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.naming :as naming]
            [autonomous.domain.schema :as s]
            [autonomous.domain.scope :as scope]
            [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as mjs]
            [malli.transform :as mt]))

(def ^:private args-transformer
  "Agents send JSON, and language models often quote numbers and booleans.
   Decode leniently: JSON rules (keywords, enums, uuids), then string rules
   (\"2\" to 2, \"true\" to true), then malli defaults. Validation still runs
   on the decoded value, so leniency never admits a wrong type."
  (mt/transformer mt/json-transformer mt/string-transformer mt/default-value-transformer))

(defn- compile-input
  "{:ok {:json-schema :decode :explain}} for malli `input`, or a refusal when
   it is not a malli schema of an object."
  [id input json-override]
  (let [schema (try (m/schema (or input [:map])) (catch #?(:clj Exception :cljs :default) e e))]
    (if-not (m/schema? schema)
      (answer/refusal :invalid-tool (str "tool " id " :input is not a malli schema")
                      {:cause (ex-message schema)})
      (let [json (or json-override (mjs/transform schema))]
        (if-not (= "object" (:type json))
          (answer/refusal :invalid-tool
                          (str "tool " id " :input must describe a map (an MCP tool takes an object)"))
          (answer/ok {:json-schema json
                      :decode      (m/decoder schema args-transformer)
                      :explain     (m/explainer schema)}))))))

(defn- spec-problems [schema spec]
  (some-> (m/explain schema spec) me/humanize))

(defn ->tool
  "Validate and compile author `spec` for tool `id`. Answers {:ok Tool} or a
   refusal of type :invalid-tool naming the problems."
  [id spec]
  (let [read-only? (boolean (:read-only? spec))
        kind       (or (:kind spec) :fn)]
    (if-let [problems (spec-problems s/ToolSpec spec)]
      (answer/refusal :invalid-tool (str "tool " id " spec is invalid") problems)
      (if (and (= :fn kind) (not (fn? (:handler spec))))
        (answer/refusal :invalid-tool (str "tool " id " of kind :fn needs a :handler fn"))
        (let [compiled (compile-input id (:input spec) (:json-schema spec))]
          (if (answer/refused? compiled)
            compiled
            (answer/ok
             (cond-> (merge spec
                            (:ok compiled)
                            {:id          id
                             :name        (or (:name spec) (naming/tool-name id))
                             :kind        kind
                             :scopes      (if (contains? spec :scopes)
                                            (scope/normalize (:scopes spec))
                                            (scope/default-for read-only?))
                             :read-only?  read-only?
                             :annotations (merge {:readOnlyHint read-only?} (:annotations spec))})
               (:output spec) (assoc :output-schema (mjs/transform (:output spec)))))))))))

(defn ->resource
  "Validate author `spec` for resource `uri`. Answers {:ok Resource} or a
   refusal of type :invalid-resource."
  [uri spec]
  (if-let [problems (spec-problems s/ResourceSpec spec)]
    (answer/refusal :invalid-resource (str "resource " uri " spec is invalid") problems)
    (answer/ok (merge {:name uri :description "" :mime "application/json"}
                      spec
                      {:uri    uri
                       :scopes (if (contains? spec :scopes)
                                 (scope/normalize (:scopes spec))
                                 #{:read})}))))

(defn conform-args
  "Decode `args` with `tool`'s input schema. Answers {:ok decoded-args} or a
   refusal of type :invalid-args with humanized problems."
  [tool args]
  (let [decoded ((:decode tool) (or args {}))]
    (if-let [e ((:explain tool) decoded)]
      (answer/refusal :invalid-args
                      (str "arguments do not match " (:name tool) "'s input schema")
                      (me/humanize e))
      (answer/ok decoded))))
