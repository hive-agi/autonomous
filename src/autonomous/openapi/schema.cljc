(ns autonomous.openapi.schema
  "JSON Schema (the OpenAPI 3.0 and 3.1 dialect) to malli. Pure and total:
   a construct malli cannot say becomes :any, never an exception.

   Object keys become keywords, because agents' arguments and decoded JSON
   bodies arrive with keyword keys. Defaults are advertised to the agent
   (:json-schema/default) but never injected: the server applies its own.

   Input is a RESOLVED schema (autonomous.openapi.doc/resolve-node), so no
   $ref is left; a cycle or unresolved marker becomes :any."
  (:require [clojure.string :as str]))

(declare ->malli)

(defn- props
  "Malli properties carried over from schema `s`."
  [s]
  (cond-> {}
    (string? (get s "description")) (assoc :description (get s "description"))
    (contains? s "default")         (assoc :json-schema/default (get s "default"))))

(defn- with-props [[tag & more :as form] p]
  (if (empty? p)
    form
    (if (map? (first more))
      (into [tag (merge (first more) p)] (rest more))
      (into [tag p] more))))

(defn- types
  "The schema's type as a set of strings (3.1 allows an array of them)."
  [s]
  (let [t (get s "type")]
    (cond (string? t)     #{t}
          (sequential? t) (set t)
          :else           #{})))

(defn- nullable? [s]
  (or (true? (get s "nullable")) (contains? (types s) "null")))

(defn- bounds [s lo hi]
  (cond-> {}
    (number? (get s lo)) (assoc :min (get s lo))
    (number? (get s hi)) (assoc :max (get s hi))))

(defn- string-schema [s]
  (let [base [:string (bounds s "minLength" "maxLength")]
        base (if (empty? (second base)) [:string] base)]
    (if (string? (get s "pattern"))
      [:and base [:re (get s "pattern")]]
      base)))

(defn- number-schema
  "Integers as :int; numbers as number?, since a JSON number may parse as
   an integer or a double and both are valid."
  [tag s]
  (let [b (bounds s "minimum" "maximum")]
    (if (= :int tag)
      (if (empty? b) [:int] [:int b])
      (cond-> [:and 'number?]
        (:min b) (conj [:>= (:min b)])
        (:max b) (conj [:<= (:max b)])))))

(defn- merge-all-of
  "allOf of object schemas as one object schema (properties and required
   joined); nil when some part is not an object."
  [parts]
  (when (every? #(or (contains? (types %) "object") (contains? % "properties")) parts)
    {"type"       "object"
     "properties" (apply merge (map #(get % "properties") parts))
     "required"   (vec (distinct (mapcat #(get % "required") parts)))}))

(defn- object-schema [s opts]
  (let [ps       (get s "properties")
        required (set (get s "required"))
        skip?    (fn [p] (and (:input? opts) (true? (get p "readOnly"))))
        entries  (for [[k p] (sort-by key ps) :when (not (skip? p))]
                   [(keyword k)
                    (cond-> {} (not (contains? required k)) (assoc :optional true))
                    (->malli p opts)])
        addl     (get s "additionalProperties")]
    (cond
      (seq entries) (into [:map] (map (fn [[k o v]] (if (empty? o) [k v] [k o v]))) entries)
      (map? addl)   [:map-of [:or :keyword :string] (->malli addl opts)]
      :else         [:map])))

(defn- typed [t s opts]
  (case t
    "string"  (string-schema s)
    "integer" (number-schema :int s)
    "number"  (number-schema :double s)
    "boolean" [:boolean]
    "array"   (let [b (bounds s "minItems" "maxItems")]
                (cond-> [:vector]
                  (seq b) (conj b)
                  true    (conj (if (map? (get s "items")) (->malli (get s "items") opts) :any))))
    "object"  (object-schema s opts)
    [:any]))

(defn- untyped [s opts]
  (cond
    (contains? s "x-autonomous-cycle")      [:any]
    (contains? s "x-autonomous-unresolved") [:any]
    ;; A coll literal right after the tag would read as malli properties.
    (contains? s "const")                   (let [c (get s "const")]
                                              (if (coll? c) [:any] [:= c]))
    (sequential? (get s "enum"))            (let [vs (remove #(or (nil? %) (coll? %)) (get s "enum"))]
                                              (if (seq vs) (into [:enum] vs) [:any]))
    (sequential? (get s "allOf"))           (let [parts (get s "allOf")]
                                              (if-let [m (merge-all-of parts)]
                                                (->malli (merge (dissoc s "allOf") m) opts)
                                                (into [:and] (map #(->malli % opts)) parts)))
    (sequential? (get s "oneOf"))           (into [:or] (map #(->malli % opts)) (get s "oneOf"))
    (sequential? (get s "anyOf"))           (into [:or] (map #(->malli % opts)) (get s "anyOf"))
    (contains? s "properties")              (object-schema s opts)
    :else                                   [:any]))

(defn ->malli
  "Malli form of resolved JSON Schema `s`. opts: :input? true drops
   readOnly properties (a request never sends them)."
  ([s] (->malli s {}))
  ([s opts]
   (if-not (map? s)
     [:any]
     (let [ts     (disj (types s) "null")
           enum?  (or (contains? s "enum") (contains? s "const")
                      (contains? s "allOf") (contains? s "oneOf") (contains? s "anyOf"))
           form   (cond
                    enum?          (untyped s opts)
                    (= 1 (count ts)) (typed (first ts) s opts)
                    (seq ts)       (into [:or] (map #(typed % s opts)) (sort ts))
                    :else          (untyped s opts))
           form   (with-props form (props s))]
       (if (nullable? s) [:maybe form] form)))))

(defn map-form?
  "True when malli `form` (from ->malli) describes a map."
  [form]
  (contains? #{:map :map-of} (if (vector? form) (first form) form)))

(defn param-key
  "Keyword an OpenAPI parameter name becomes in the args map."
  [name]
  (keyword (str/trim (str name))))
