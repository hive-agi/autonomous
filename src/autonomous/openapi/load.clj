(ns autonomous.openapi.load
  "Boundary: read an OpenAPI document and the sibling files its $refs
   reach into a Bundle (autonomous.openapi.doc). YAML through clj-yaml
   (built into babashka; a dep on the JVM), JSON through data.json.
   Answers {:ok Bundle} or a refusal, never throws."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.openapi.doc :as doc]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn parse
  "L3 Boundary: document of `text`: JSON when it starts with { or [, else
   YAML. Throws on text that is neither."
  [text]
  (doc/normalize
   (if (re-find #"^\s*[\{\[]" text)
     (json/read-str text)
     ((requiring-resolve 'clj-yaml.core/parse-string) text :keywords false))))

(defn- read-doc
  "L3 Boundary: Ok of document `name` read from `dir`, or a refusal naming
   the missing or unparseable file."
  [dir name]
  (let [f (io/file dir name)]
    (if (.isFile f)
      (try (answer/ok (parse (slurp f)))
           (catch Exception e
             (answer/refusal :openapi/unreadable (str "cannot parse " name ": " (ex-message e)))))
      (answer/refusal :openapi/missing-file (str "no file " (str f) " (a $ref points at it)")))))

(defn- skip?
  "L1 Promote: true for a document name already read or naming a URL,
   which is never fetched."
  [bundle n]
  (or (contains? (:docs bundle) n) (str/includes? n "://")))

(defn- add-doc
  "L2 Pipeline: one read step: Ok of [bundle pending] with document `n`
   read from `dir` and the documents it refers to queued, or a refusal."
  [dir [bundle pending] n]
  (answer/bind (read-doc dir n)
               #(answer/ok [(assoc-in bundle [:docs n] %)
                            (concat pending (doc/referenced-docs n %))])))

(defn- step
  "L2 Pipeline: Ok of the next [bundle pending], `n` taken off `pending`."
  [dir [bundle [n & more]]]
  (if (skip? bundle n) (answer/ok [bundle more]) (add-doc dir [bundle more] n)))

(defn- read-all
  "L2 Pipeline: Ok of the bundle once every pending document is read, or
   the first refusal. Recursion depth is the number of documents."
  [dir [bundle pending :as state]]
  (if (empty? pending)
    (answer/ok bundle)
    (answer/bind (step dir state) #(read-all dir %))))

(defn load-bundle
  "L3 Boundary: Ok Bundle of the OpenAPI document at `path` and every
   document its $refs reach, transitively, read relative to its directory,
   or a refusal. A $ref to a URL is not fetched (it stays unresolved)."
  [path]
  (let [f    (io/file path)
        root (.getName f)]
    (read-all (.getParentFile (.getAbsoluteFile f)) [{:root root :docs {}} [root]])))
