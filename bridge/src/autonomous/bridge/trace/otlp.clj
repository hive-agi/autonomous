(ns autonomous.bridge.trace.otlp
  "OTLP/HTTP JSON span exporter: POSTs an ExportTraceServiceRequest
   (autonomous.domain.trace/->otlp) to an OpenTelemetry collector.

     {:exporter/kind :otlp-http
      :otlp/endpoint \"http://localhost:4318/v1/traces\"   ; the default
      :otlp/headers  {\"authorization\" \"Bearer ...\"}
      :otlp/service  \"autonomous\"                        ; for spans with no app
      :otlp/timeout-ms 5000}

   The network is reached through `*post*`, a seam read at call time: a
   test binds a stub and the adapter needs no redefinition. JVM and
   babashka: only java.net and java.io are named."
  (:require [autonomous.bridge.port :as port]
            [autonomous.bridge.trace.export :as export]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.trace :as dt]
            [autonomous.mcp.json :as json])
  (:import [java.net HttpURLConnection URL]))

(def default-endpoint "http://localhost:4318/v1/traces")

(defn http-post
  "POST `body` (a string) to `url` with `headers`; answers {:status :body}.
   Throws on a connection failure."
  [url headers body timeout-ms]
  (let [^HttpURLConnection c (.openConnection (URL. url))
        bytes (.getBytes ^String body "UTF-8")]
    (.setRequestMethod c "POST")
    (.setDoOutput c true)
    (.setConnectTimeout c (int timeout-ms))
    (.setReadTimeout c (int timeout-ms))
    (.setRequestProperty c "Content-Type" "application/json")
    (doseq [[k v] headers] (.setRequestProperty c (str k) (str v)))
    (with-open [o (.getOutputStream c)] (.write o bytes))
    (let [status (.getResponseCode c)
          in     (if (< status 400) (.getInputStream c) (.getErrorStream c))]
      {:status status :body (if in (slurp in) "")})))

(def ^:dynamic *post*
  "(fn [url headers body timeout-ms] {:status :body}): how a batch reaches
   the collector. Rebind in tests."
  http-post)

(defrecord OtlpHttpExporter [endpoint headers service timeout-ms]
  port/ISpanExporter
  (-export! [_ spans]
    (try
      (let [{:keys [status body]} (*post* endpoint headers (json/write (dt/->otlp spans service)) timeout-ms)]
        (if (and (number? status) (< status 300))
          (answer/ok (count spans))
          (answer/refusal :export/rejected (str "collector answered " status)
                          {:status status :body (subs (str body) 0 (min 500 (count (str body))))})))
      (catch Exception e
        (answer/refusal :export/unreachable (str "collector " endpoint " unreachable: " (ex-message e)))))))

(defmethod export/make :otlp-http [cfg]
  (->OtlpHttpExporter (or (:otlp/endpoint cfg) default-endpoint)
                      (or (:otlp/headers cfg) {})
                      (or (:otlp/service cfg) "autonomous")
                      (or (:otlp/timeout-ms cfg) 5000)))
