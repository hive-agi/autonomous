(ns autonomous.mcp.json
  "EDN to JSON and back, for MCP. Keywords keep their namespace
   (:todo/add is \"todo/add\"), sets become arrays, non-finite numbers
   become strings (JSON has none). Pure."
  (:require [clojure.data.json :as json]))

(defn- key->str [k]
  (cond (keyword? k) (subs (str k) 1)
        :else        (str k)))

(defn- non-finite? [x]
  (and (double? x) (or (not= x x) (= x ##Inf) (= x ##-Inf))))

(defn ->json-data
  "`x` as data any JSON writer accepts unchanged: keywords keep their
   namespace (:todo/add is \"todo/add\"), sets and seqs become vectors,
   uuids and insts become strings, non-finite numbers become strings (JSON
   has none). All shaping happens here, so the codec runs with no options:
   ClojureWasm's write-str takes none and refuses lists and sets."
  [x]
  (cond
    (nil? x)                   nil
    (keyword? x)               (key->str x)
    (or (symbol? x) (uuid? x)) (str x)
    (inst? x)                  (let [s (pr-str x)] (subs s 7 (dec (count s))))   ; #inst "..." -> ISO-8601
    (non-finite? x)            (str x)
    (map? x)                   (into {} (map (fn [[k v]] [(key->str k) (->json-data v)])) x)
    (coll? x)                  (mapv ->json-data x)
    :else                      x))

(defn write
  "JSON text of EDN `x`."
  [x]
  (json/write-str (->json-data x)))

(defn read-json
  "EDN of JSON text `s`, object keys as keywords."
  [s]
  (json/read-str s :key-fn keyword))

(defn read-data
  "EDN of JSON text `s` exactly as JSON says it: object keys stay strings.
   For JSON documents read as documents (a JSON tool spec, a JSON Schema)."
  [s]
  (json/read-str s))
