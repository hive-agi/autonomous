(ns autonomous.domain.naming
  "Names agents see. MCP tool names allow [A-Za-z0-9_-] and at most 64
   characters, while application ids are namespaced keywords. Pure."
  (:require [clojure.string :as str]))

(def max-length 64)

(def ^:private name-chars
  (set (seq "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-")))

(defn valid-tool-name?
  "True when `s` is a legal MCP tool name."
  [s]
  (and (string? s)
       (<= 1 (count s) max-length)
       (every? name-chars (seq s))))

(def max-shown-actor
  "The most characters of a caller-supplied actor the library shows."
  64)

(defn shown-actor
  "`actor` as the library shows it to a human or to a host: control
   characters (C0, DEL and C1) dropped, then cut at max-shown-actor
   characters. nil shows as \"\"."
  [actor]
  (let [clean (str/replace (str actor) #"[\x00-\x1f\x7f-\x9f]" "")]
    (subs clean 0 (min max-shown-actor (count clean)))))

(defn tool-name
  "MCP-safe name for a tool id: :todo/add is \"todo_add\", :app.todos/add-item
   is \"app_todos_add-item\". Every character outside [A-Za-z0-9_-] becomes _,
   and the result is cut at 64 characters. An empty id names \"_\"."
  [id]
  (let [s   (if (keyword? id) (subs (str id) 1) (str id))
        out (apply str (map #(if (name-chars %) % "_") (take max-length s)))]
    (if (= "" out) "_" out)))

(def max-target-id-length 48)

(def ^:private target-id-chars (conj name-chars \.))

(defn valid-target-id?
  "True when `s` can name a target of a fleet: 1..48 of [A-Za-z0-9._-]."
  [s]
  (and (string? s)
       (<= 1 (count s) max-target-id-length)
       (every? target-id-chars (seq s))))
