(ns autonomous.mcp.json
  "EDN to JSON and back, for MCP. Keywords keep their namespace
   (:todo/add is \"todo/add\"), sets become arrays, non-finite numbers
   become strings (JSON has none). Pure."
  (:require [clojure.data.json :as json]))

(defn- key->str [k]
  (cond (keyword? k) (subs (str k) 1)
        :else        (str k)))

(defn- value-fn [_k v]
  (cond
    (keyword? v) (subs (str v) 1)
    (symbol? v)  (str v)
    (uuid? v)    (str v)
    (inst? v)    (str (.toInstant ^java.util.Date v))
    (set? v)     (vec v)
    (and (double? v) (or (Double/isNaN v) (Double/isInfinite v))) (str v)
    :else        v))

(defn write
  "JSON text of EDN `x`."
  [x]
  (json/write-str x :key-fn key->str :value-fn value-fn :escape-slash false))

(defn read-json
  "EDN of JSON text `s`, object keys as keywords."
  [s]
  (json/read-str s :key-fn keyword))
