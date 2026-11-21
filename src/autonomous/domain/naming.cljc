(ns autonomous.domain.naming
  "Names agents see: tool names of [A-Za-z0-9_-], 1..64 characters, from
   application ids that are namespaced keywords. Pure.")

(def max-length 64)

(def ^:private name-chars
  (set (seq "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-")))

(defn valid-tool-name?
  "True when `s` is a legal MCP tool name."
  [s]
  (and (string? s)
       (<= 1 (count s) max-length)
       (every? name-chars (seq s))))

(defn sanitize
  "`s` with every character (UTF-16 code unit) outside [A-Za-z0-9_-]
   replaced by _. The length is unchanged."
  [s]
  (apply str (map #(if (name-chars %) % "_") (str s))))

(defn tool-name
  "MCP-safe name for a tool id: :todo/add is \"todo_add\", :app.todos/add-item
   is \"app_todos_add-item\". Every character outside [A-Za-z0-9_-] becomes _,
   and the result is cut at 64 characters. An empty id names \"_\". Always a
   valid tool name; a valid name maps to itself."
  [id]
  (let [s   (if (keyword? id) (subs (str id) 1) (str id))
        out (sanitize (apply str (take max-length s)))]
    (if (= "" out) "_" out)))

(def max-target-id-length 48)

(def ^:private target-id-chars (conj name-chars \.))

(defn valid-target-id?
  "True when `s` can name a target of a fleet: 1..48 of [A-Za-z0-9._-]."
  [s]
  (and (string? s)
       (<= 1 (count s) max-target-id-length)
       (every? target-id-chars (seq s))))
