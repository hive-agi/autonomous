(ns autonomous.domain.wire
  "Values that cross a bridge. Whatever a tool returns must print as EDN and
   read back on the other side of a REPL, a socket or a process boundary.
   Pure."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])))

(def max-items
  "Longest sequence carried; an infinite seq must not hang a call."
  10000)

(defn- wire-key [k]
  (if (or (keyword? k) (string? k)) k (str k)))

(defn ->wire
  "`x` as data every bridge can print and read back.

   Keeps nil, strings, numbers, booleans, keywords, symbols, uuids, insts and
   collections of them. Records become maps, seqs become vectors of at most
   `max-items`, JavaScript arrays and objects become ClojureScript data, and
   anything else becomes its `str`."
  [x]
  (cond
    (or (nil? x) (string? x) (number? x) (boolean? x) (keyword? x)
        (symbol? x) (uuid? x) (inst? x)) x
    (map? x)        (into {} (map (fn [[k v]] [(wire-key k) (->wire v)])) x)
    (set? x)        (into #{} (map ->wire) x)
    (sequential? x) (into [] (comp (take max-items) (map ->wire)) x)
    #?@(:cljs [(array? x) (->wire (vec x))
               (object? x) (->wire (js->clj x))])
    :else           (str x)))

(defn read-edn
  "One EDN value read from string `s`."
  [s]
  #?(:clj (edn/read-string s) :cljs (reader/read-string s)))

(defn print-edn
  "`x` printed as EDN text, unabridged and without metadata or namespace-map
   shorthand, so any EDN reader takes it back."
  [x]
  (binding [*print-length* nil
            *print-level* nil
            *print-meta* false
            *print-namespace-maps* false]
    (pr-str x)))
