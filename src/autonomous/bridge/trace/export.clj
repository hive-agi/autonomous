(ns autonomous.bridge.trace.export
  "Span exporters: providers of autonomous.bridge.port/ISpanExporter, made
   from configuration by the open multimethod `make` (OCP: a provider adds
   one method, nothing here changes), exactly as ledgers are.

   Shipped providers, each {:exporter/kind ... :exporter/name ...}:

     :memory     keeps every batch in an atom (tests, a REPL)
     :file       autonomous.bridge.trace.file: OTLP JSON, JSON Lines or EDN
                 under a directory
     :otlp-http  autonomous.bridge.trace.otlp: OTLP/HTTP JSON to an
                 OpenTelemetry collector (/v1/traces), so Jaeger, Tempo,
                 Grafana, Honeycomb and the rest read the run

   Every provider answers -export! with an Answer and never throws (LSP);
   `export-all` relies on it and still guards, so one broken backend never
   stops the others or the collection."
  (:require [autonomous.bridge.port :as port]
            [autonomous.domain.answer :as answer]))

(defmulti make
  "An ISpanExporter for config {:exporter/kind ...}. Providers add a
   method."
  :exporter/kind)

(def ^:private provider-ns
  {:file      'autonomous.bridge.trace.file
   :otlp-http 'autonomous.bridge.trace.otlp})

(defn open
  "Load the namespace of the provider this library ships for `cfg`'s kind
   (a third party loads its own), then `make` it. Answers Ok of the
   exporter, or a refusal :export/unavailable."
  [cfg]
  (try
    (when-let [ns-sym (provider-ns (:exporter/kind cfg))] (require ns-sym))
    (answer/ok (make cfg))
    (catch Exception e
      (answer/refusal :export/unavailable
                      (str "span exporter " (pr-str (:exporter/kind cfg)) " cannot start: " (ex-message e))
                      {:exporter/kind (:exporter/kind cfg)}))))

(defn exporter-name
  "The name an exporter config is addressed by: :exporter/name, else its
   kind."
  [cfg]
  (or (:exporter/name cfg) (name (:exporter/kind cfg))))

;; =============================================================================
;; Memory provider
;; =============================================================================

(defrecord MemoryExporter [batches]
  port/ISpanExporter
  (-export! [_ spans]
    (swap! batches conj (vec spans))
    (answer/ok (count spans))))

(defmethod make :memory [cfg]
  (->MemoryExporter (or (:memory/atom cfg) (atom []))))

(defmethod make :default [cfg]
  (throw (ex-info (str "no span exporter of kind " (pr-str (:exporter/kind cfg))
                       "; known: memory, file, otlp-http")
                  {:autonomous/error :export/unknown-kind})))

;; =============================================================================
;; Fan-out
;; =============================================================================

(defn export-all
  "Hand `spans` to every exporter in `named` ([[name exporter] ...]).
   Answers {name answer}. Nothing is called for an empty batch."
  [named spans]
  (if (empty? spans)
    {}
    (into {}
          (map (fn [[nm e]]
                 [nm (try (port/-export! e spans)
                          (catch Exception ex
                            (answer/refusal :export/threw (or (ex-message ex) (str ex)))))]))
          named)))
