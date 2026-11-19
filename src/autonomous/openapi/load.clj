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
  "Document of `text`: JSON when it starts with { or [, else YAML."
  [text]
  (doc/normalize
   (if (re-find #"^\s*[\{\[]" text)
     (json/read-str text)
     ((requiring-resolve 'clj-yaml.core/parse-string) text :keywords false))))

(defn- read-doc [dir name]
  (let [f (io/file dir name)]
    (if (.isFile f)
      (try (answer/ok (parse (slurp f)))
           (catch Exception e
             (answer/refusal :openapi/unreadable (str "cannot parse " name ": " (ex-message e)))))
      (answer/refusal :openapi/missing-file (str "no file " (str f) " (a $ref points at it)")))))

(defn load-bundle
  "Bundle of the OpenAPI document at `path` and every document its $refs
   reach, transitively, read relative to its directory. A $ref to a URL is
   not fetched (it stays unresolved)."
  [path]
  (let [f    (io/file path)
        dir  (.getParentFile (.getAbsoluteFile f))
        root (.getName f)]
    (loop [b       {:root root :docs {}}
           pending [root]]
      (if-let [n (first pending)]
        (if (or (contains? (:docs b) n) (str/includes? n "://"))
          (recur b (rest pending))
          (let [d (read-doc dir n)]
            (if (answer/refused? d)
              d
              (recur (assoc-in b [:docs n] (:ok d))
                     (concat (rest pending) (doc/referenced-docs n (:ok d)))))))
        (answer/ok b)))))
