(ns autonomous.bridge.trace.file
  "File span exporter: what a test run leaves behind for later analysis.

     {:exporter/kind :file
      :file/dir    \"target/traces\"     ; the default
      :file/format :otlp-json          ; or :jsonl, :edn
      :file/prefix \"spans\"}

   :otlp-json writes one OTLP/JSON document per batch
   (<prefix>-<millis>-<n>.otlp.json), the form `otelcol`'s file receiver and
   Jaeger's upload read. :jsonl and :edn append one span per line to
   <prefix>.jsonl / <prefix>.edn, the forms jq, DuckDB and a REPL read.
   File names come from config only; nothing an agent sends reaches a path."
  (:require [autonomous.bridge.port :as port]
            [autonomous.bridge.trace.export :as export]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.trace :as dt]
            [autonomous.domain.wire :as wire]
            [autonomous.mcp.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn render
  "Text of `spans` in `format`, for one batch (the n-th, at `at`). Pure.
   Answers [file-suffix text append?]."
  [format spans service]
  (case format
    :jsonl     [".jsonl" (str (str/join "\n" (map json/write spans)) "\n") true]
    :edn       [".edn" (str (str/join "\n" (map wire/print-edn spans)) "\n") true]
    :otlp-json [".otlp.json" (json/write (dt/->otlp spans service)) false]))

(defrecord FileExporter [dir format prefix service counter]
  port/ISpanExporter
  (-export! [_ spans]
    (try
      (let [[suffix text append?] (render format spans service)
            n    (swap! counter inc)
            file (io/file dir (if append?
                                (str prefix suffix)
                                (str prefix "-" (System/currentTimeMillis) "-" n suffix)))]
        (io/make-parents file)
        (spit file text :append append?)
        (answer/ok (count spans)))
      (catch Exception e
        (answer/refusal :export/io (str "cannot write spans under " dir ": " (ex-message e)))))))

(defmethod export/make :file [cfg]
  (let [fmt (keyword (or (:file/format cfg) :otlp-json))]
    (when-not (#{:otlp-json :jsonl :edn} fmt)
      (throw (ex-info (str "unknown span file format " fmt "; use otlp-json, jsonl or edn")
                      {:autonomous/error :export/bad-format})))
    (->FileExporter (or (:file/dir cfg) "target/traces")
                    fmt
                    (or (:file/prefix cfg) "spans")
                    (or (:otlp/service cfg) "autonomous")
                    (atom 0))))
