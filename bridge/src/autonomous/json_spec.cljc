(ns autonomous.json-spec
  "A tool spec written as JSON, for authors who do not write Clojure. Pure:
   JSON data in (string keys, as clojure.data.json reads it), ToolSpec out.

     {\"id\":          \"inventory/receive\",
      \"description\": \"Receive qty units of sku into stock.\",
      \"input\":       {\"type\": \"object\",
                       \"properties\": {\"sku\": {\"type\": \"string\", \"minLength\": 1},
                                      \"qty\": {\"type\": \"integer\", \"minimum\": 1}},
                       \"required\": [\"sku\", \"qty\"]},
      \"output\":      {\"type\": \"object\"},
      \"readOnly\":    false,
      \"scopes\":      [\"write\"]}

   `input` and `output` take either of the two schema forms the repo already
   reads: a JSON Schema object (converted by autonomous.openapi.schema, the
   OpenAPI source's converter) or a string of malli EDN, the form
   autonomous.js and the C ABI take (\"[:map [:sku :string]]\"). A $ref in a
   JSON Schema resolves against the document the tool came from, so a file
   can share {\"$defs\": ...} between its tools.

   The field names follow autonomous.js (readOnly, camelCase), and no other
   key is accepted: a misspelt one is refused, naming the key meant. A document
   is one tool object, an array of them, or {\"tools\": [...]} beside shared
   definitions. Answers {:ok ...} or a refusal of type :invalid-tool."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.wire :as wire]
            [autonomous.openapi.doc :as doc]
            [autonomous.openapi.schema :as schema]
            [malli.core :as m]
            [malli.error :as me]
            [autonomous.domain.affordance :as affordance]
            [clojure.string :as str]))

(def SchemaForm
  "A JSON Schema object, or malli as EDN text."
  [:fn {:error/message "should be a JSON Schema object or a string of malli EDN"}
   (fn [x] (or (string? x) (and (map? x) (every? string? (keys x)))))])

(def JsonToolSpec
  "One tool as JSON data. `id` may be absent when the caller names the tool.
   Closed: an unknown key is refused, since a misspelt scopes or readOnly
   would otherwise leave the tool with the default scopes. $defs and
   definitions hold schemas $refs reach; $schema and $comment are notes."
  [:map {:closed true}
   ["id" {:optional true} [:string {:min 1}]]
   ["description" [:string {:min 1}]]
   ["title" {:optional true} :string]
   ["name" {:optional true} :string]
   ["input" {:optional true} SchemaForm]
   ["output" {:optional true} SchemaForm]
   ["readOnly" {:optional true} :boolean]
   ["scopes" {:optional true} [:sequential [:string {:min 1}]]]
   ["annotations" {:optional true} [:map-of :string :any]]
   ["$defs" {:optional true} [:map-of :string :any]]
   ["definitions" {:optional true} [:map-of :string :any]]
   ["$schema" {:optional true} :string]
   ["$comment" {:optional true} :string]])

(def ^:private look-alikes
  "Keys authors write for a real key: the refusal names the one to use."
  {"scope" "scopes" "readonly" "readOnly" "read_only" "readOnly" "readOnly?" "readOnly"
   "read-only" "readOnly" "read-only?" "readOnly" "inputSchema" "input" "input_schema" "input"
   "outputSchema" "output" "output_schema" "output" "desc" "description"})

(defn- unknown-keys
  "L1 Promote: {key hint} of the keys of `spec` JsonToolSpec does not know,
   each hint naming the key meant when it is a known look-alike."
  [spec]
  (let [known (set (map first (m/children JsonToolSpec)))]
    (into (sorted-map)
          (comp (remove known)
                (map (fn [k] [k (if-let [real (look-alikes k)]
                                  (str "unknown key; did you mean \"" real "\"?")
                                  "unknown key")])))
          (keys spec))))

(defn- invalid
  "L1 Promote: the :invalid-tool refusal for tool `id`."
  ([id message] (invalid id message nil))
  ([id message data]
   (answer/refusal :invalid-tool (str "tool " (or id "(no id)") " " message) data)))

(def property-keys
  "The malli property keys a JSON spec may use: plain data malli reads as
   data. Any other key (:decode/*, :encode/*, :gen/*, :error/fn, :registry,
   :default-fn, ...) may carry code malli hands to sci, so it is refused.
   Keys in the json-schema namespace are advertised data and allowed."
  #{:optional :min :max :title :description :closed :default :error/message :dispatch})

(defn- allowed-property?
  "L1 Promote: true when property `k` = `v` is plain data a JSON spec may
   carry: a key of property-keys or the json-schema namespace, holding no
   code. :dispatch is a keyword only, :error/message a string only."
  [[k v]]
  (and (keyword? k)
       (or (contains? property-keys k) (= "json-schema" (namespace k)))
       (case k
         :dispatch      (keyword? v)
         :error/message (string? v)
         (not (some seq? (tree-seq coll? seq v))))))

(defn- bad-properties
  "L1 Promote: [key value] of the first property in `props` a JSON spec
   may not carry, or nil."
  [props]
  (first (remove allowed-property? props)))

(defn- code-in
  "L1 Promote: the first piece of `form` that is code rather than a schema,
   or nil. Fails closed: a :fn schema, a list (EDN's form for a call), and
   any property outside property-keys (a code-bearing one like :dispatch
   with a string, :decode/string) all count, the property as {key value}.
   :enum and := children are values, not schemas."
  [form]
  (let [props (fn [m] (when-let [[k v] (bad-properties m)] {k v}))]
    (cond
      (= :fn form)                                    :fn
      (seq? form)                                     form
      (map? form)                                     (props form)
      (and (vector? form) (= :fn (first form)))       form
      (and (vector? form) (#{:enum :=} (first form))) (when (map? (second form)) (props (second form)))
      (coll? form)                                    (some code-in form)
      :else                                           nil)))

(defn- edn-schema
  "L1 Promote: Ok malli form read from EDN text `s`, or a refusal; blank
   text is refused rather than read as no schema, and so is a form that
   carries code unless opts :allow-fn? is true (the host's choice: a JSON
   file cannot grant it to itself)."
  [id field s opts]
  (if (str/blank? s)
    (invalid id (str field " is blank: write \"[:map]\" or leave " field " out"))
    (let [form (try (wire/read-edn s) (catch #?(:clj Exception :cljs :default) e e))
          code (when-not (or (:allow-fn? opts) #?(:clj (instance? Exception form) :cljs (instance? js/Error form)))
                 (code-in form))]
      (cond
        #?(:clj (instance? Exception form) :cljs (instance? js/Error form))
        (invalid id (str field " is not readable EDN") {:cause (ex-message form)})
        code
        (invalid id (str field " carries code (" (pr-str code) "); a JSON spec may use only plain-data properties "
                         (pr-str (sort property-keys)) " and :json-schema/*, and no functions")
                 {:code (pr-str code)})
        :else
        (answer/ok form)))))

(defn- problem-text
  "L1 Promote: one schema problem (autonomous.openapi.schema/problems) as
   words for the author."
  [{:keys [problem ref type path]}]
  (let [at (if (seq path) (str " at " (pr-str path)) "")]
    (case problem
      :unresolved-ref (str "$ref " ref " does not resolve within the document" at)
      :cycle          (str "$ref " ref " refers to itself" at)
      :unknown-type   (str "type " (pr-str (if (= 1 (count type)) (first type) type)) " is not a JSON Schema type" at))))

(defn- json-schema
  "L1 Promote: Ok malli form of JSON Schema `x` resolved against `bundle`,
   or a refusal naming the first place ->malli would read as :any (a $ref
   that does not resolve, a cycle, an unknown type)."
  [bundle id field x opts]
  (let [resolved (doc/resolved bundle x)
        problems (schema/problems resolved)]
    (if (seq problems)
      (invalid id (str field " " (problem-text (first problems))) {:problems problems})
      (answer/ok (schema/->malli resolved opts)))))

(defn- ->malli-form
  "L1 Promote: Ok malli form of schema `x` of `field`, JSON Schema resolved
   against `bundle`; nil `x` stays nil."
  [bundle id field x opts]
  (cond
    (nil? x)    (answer/ok nil)
    (string? x) (edn-schema id field x opts)
    :else       (json-schema bundle id field x opts)))

(defn- checked
  "L1 Promote: Ok `spec` when it is a JsonToolSpec, else a refusal with the
   humanized problems; an unknown key names the key it looks like."
  [id spec]
  (let [unknown (unknown-keys spec)]
    (cond
      (seq unknown)
      (invalid id (str "JSON spec has unknown key" (when (> (count unknown) 1) "s") " "
                       (str/join ", " (map pr-str (keys unknown))))
               unknown)
      :else
      (if-let [e (m/explain JsonToolSpec spec)]
        (invalid id "JSON spec is invalid" (me/humanize e))
        (answer/ok spec)))))

(defn- assemble
  "L1 Promote: the ToolSpec of checked JSON `spec` and its compiled schemas."
  [spec input output]
  (cond-> {:description (get spec "description")}
    (contains? spec "title")       (assoc :title (get spec "title"))
    (contains? spec "name")        (assoc :name (get spec "name"))
    input                          (assoc :input input)
    output                         (assoc :output output)
    (contains? spec "readOnly")    (assoc :read-only? (get spec "readOnly"))
    (contains? spec "scopes")      (assoc :scopes (set (map keyword (get spec "scopes"))))
    (contains? spec "annotations") (assoc :annotations (update-keys (get spec "annotations") keyword))))

(defn- compiled
  "L1 Promote: Ok [id spec] when ToolSpec `spec` compiles as a tool (its
   malli schemas are schemas, its input describes a map), checked with a
   stub handler; else the :invalid-tool refusal affordance/->tool gives."
  [id spec]
  (answer/bind (affordance/->tool id (assoc spec :handler (fn [_ _] nil)))
               (fn [_] (answer/ok [id spec]))))

(defn ->tool-spec
  "L2 Pipeline: Ok [id spec] of JSON tool `spec`, a ToolSpec without a
   :handler (the caller supplies how the tool runs), already checked to
   compile as a tool. `id` overrides the spec's own \"id\"; `bundle` is the
   document $refs resolve against (default: the spec itself). Host `opts`:
   :allow-fn? true admits malli EDN that carries code ([:fn ...]); off by
   default, since a JSON spec may come from an agent."
  ([spec] (->tool-spec nil spec))
  ([id spec] (->tool-spec id spec (doc/bundle spec)))
  ([id spec bundle] (->tool-spec id spec bundle {}))
  ([id spec bundle opts]
   (let [id (or id (when (map? spec) (get spec "id")))]
     (cond
       (not (map? spec)) (invalid id "JSON spec must be an object")
       (nil? id)         (invalid id "JSON spec needs an \"id\"")
       :else
       (answer/let-answer [spec   (checked id spec)
                           input  (->malli-form bundle id "input" (get spec "input") (assoc opts :input? true))
                           output (->malli-form bundle id "output" (get spec "output") (dissoc opts :input?))]
         (compiled (keyword id) (assemble spec input output)))))))

(defn- tools-of
  "L1 Promote: the tool objects of document `d` (one, an array, or under
   \"tools\"), or nil when `d` has none of those shapes."
  [d]
  (cond
    (sequential? d)                   d
    (sequential? (get d "tools"))     (get d "tools")
    (and (map? d) (contains? d "id")) [d]
    :else                             nil))

(defn- first-duplicate
  "L1 Promote: the first id declared more than once in `specs`, or nil."
  [specs]
  (some (fn [[id n]] (when (> n 1) id)) (frequencies (map first specs))))

(defn document->specs
  "L2 Pipeline: Ok [[id spec] ...] of JSON document `d`, in document order,
   or the first refusal. Two tools with one id are refused. Host `opts` as
   for ->tool-spec."
  ([d] (document->specs d {}))
  ([d opts]
   (if-let [tools (tools-of d)]
     (let [bundle (doc/bundle d)]
       (answer/bind
        (answer/reduce-answer (fn [acc t] (answer/bind (->tool-spec nil t bundle opts) #(answer/ok (conj acc %))))
                              [] tools)
        (fn [specs]
          (if-let [dup (first-duplicate specs)]
            (invalid (subs (str dup) 1) "is declared twice")
            (answer/ok specs)))))
     (answer/refusal :invalid-tool "a JSON spec document is a tool object, an array of them, or {\"tools\": [...]}"))))
