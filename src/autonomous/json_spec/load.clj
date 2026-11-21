(ns autonomous.json-spec.load
  "Boundary: read JSON tool specs (autonomous.json-spec) from text or a file
   and register them, or print them as the EDN spec text the C ABI's
   autonomous_reg_tool takes. Answers {:ok ...} or a refusal, never throws.

     (register-file! \"tools.json\" {:inventory/receive (fn [args grant] ...)})

     bb tool-spec tools.json      ; one EDN spec per tool, for libautonomous"
  (:require [autonomous.core :as a]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.wire :as wire]
            [autonomous.json-spec :as js]
            [autonomous.mcp.json :as json]
            [clojure.java.io :as io]))

(defn parse
  "L3 Boundary: Ok [[id spec] ...] of JSON document text `text`, or a
   refusal naming why it is unreadable or invalid."
  [text]
  (let [d (try (json/read-data text) (catch Exception e e))]
    (if (instance? Exception d)
      (answer/refusal :invalid-tool (str "JSON spec is not readable JSON: " (ex-message d)))
      (js/document->specs d))))

(defn read-file
  "L3 Boundary: Ok [[id spec] ...] of the JSON spec document at `path`."
  [path]
  (let [f (io/file path)]
    (if (.isFile f)
      (parse (slurp f))
      (answer/refusal :invalid-tool (str "no JSON spec file " path)))))

(defn- missing-handlers
  "L1 Promote: the ids of `specs` that `handlers` has no fn for."
  [specs handlers]
  (vec (remove #(fn? (get handlers %)) (map first specs))))

(defn- register-one
  "L3 Boundary: Ok of the MCP name of tool [id spec] registered with
   `handler`, or the :invalid-tool refusal reg-tool threw."
  [[id spec] handler]
  (try (answer/ok (a/reg-tool id (assoc spec :handler handler)))
       (catch Exception e (answer/from-exception e))))

(defn register!
  "L3 Boundary: register every tool of `specs` with its fn in `handlers`
   (tool id keyword -> (fn [args grant] value)). Refuses before
   registering anything when a tool has no handler. Answers Ok of the MCP
   names in document order; a later refusal leaves earlier tools
   registered."
  [specs handlers]
  (let [missing (missing-handlers specs handlers)]
    (if (seq missing)
      (answer/refusal :invalid-tool (str "no handler for " (pr-str missing)) {:missing missing})
      (answer/reduce-answer (fn [names [id :as s]]
                              (answer/bind (register-one s (get handlers id)) #(answer/ok (conj names %))))
                            [] specs))))

(defn register-file!
  "L3 Boundary: read the JSON spec document at `path` and register its
   tools with `handlers`; see register!."
  [path handlers]
  (answer/bind (read-file path) #(register! % handlers)))

(defn edn-text
  "L1 Promote: the EDN spec text of ToolSpec `spec`, as autonomous_reg_tool
   reads it."
  [spec]
  (wire/print-edn spec))

(defn run
  "L3 Boundary: `bb tool-spec FILE.json` without the exit: print each tool
   as `id<TAB>edn-spec`, one per line, and answer 0; on a missing file, an
   invalid spec or a spec that does not compile as a tool, print the
   refusal on stderr, print nothing on stdout, and answer 1."
  [& [path]]
  (let [a (if path (read-file path) (answer/refusal :usage "usage: bb tool-spec FILE.json"))]
    (if (answer/ok? a)
      (do (doseq [[id spec] (:ok a)]
            (println (str (subs (str id) 1) "\t" (edn-text spec))))
          0)
      (do (binding [*out* *err*] (println (wire/print-edn a)))
          1))))

(defn -main
  "`bb tool-spec FILE.json`: see run; exits with its code."
  [& args]
  (System/exit (apply run args)))
