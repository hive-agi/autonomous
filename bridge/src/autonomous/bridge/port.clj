(ns autonomous.bridge.port
  "Ports of the bridge, one per effect and sized to its role (ISP). The
   pipeline (autonomous.bridge.client) depends on these protocols only; the
   adapters (nrepl, local, ledger.*) depend on nothing above them (DIP).

   Every method answers an Answer map ({:ok} {:pending} {:error}); a
   transport failure is a refusal whose type is namespaced :channel/*,
   never an exception.")

(defprotocol IRuntimeChannel
  "Carries one Request to a runtime and brings its Answer back."
  (-send [channel request runtime-id]
    "Answer for EDN `request` from runtime `runtime-id` (nil: the default
     runtime of the target)."))

(defprotocol IRuntimeDirectory
  "Which runtimes a target currently has (browser tabs, processes)."
  (-runtimes [directory]
    "Ok of [{:runtime/id :runtime/kind :runtime/label}]."))

(defprotocol ILedger
  "Durable record of journal entries across runtimes, sessions and reloads."
  (-append! [ledger entries]
    "Store ledger `entries`; an entry already stored (same :entry/key) is
     not stored twice. Answers Ok of the number stored.")
  (-query [ledger q]
    "Ok of entries matching `q`: {:actor :kind :tool :target :since :until
     :limit}, newest first."))

(defprotocol IHttpClient
  "One HTTP exchange, or a held event stream. The remote MCP channel
   (autonomous.bridge.http) depends on this and nothing below it, so a test
   drives it with a stub record instead of a network."
  (-request! [client req]
    "Answer of sending ring-style `req` ({:method :url :headers :body
     :timeout-ms :rpc-id}): {:ok {:status :headers :body}}, a
     :channel/transport-failed refusal only when the failure precedes the
     request reaching the remote (connect, resolve, route, TLS handshake,
     building it), so a retry is safe, else :channel/outcome-unknown (the
     remote may have acted). An answered request is no proof either way: a
     5xx to tools/call (the channel's :channel/remote-refused with its
     :status) may have run, so only :channel/transport-failed is safe to
     retry. An SSE reply is read up to the message whose id is :rpc-id.
     Never throws.")
  (-open-stream! [client req sink]
    "Answer of a held GET to `req` whose event text arrives at `sink`
     ({:text! (fn [s]) :close! (fn [])}), or a refusal. Ok of
     {:close! (fn [])} when the stream was opened; the caller closes it."))

(defprotocol ICredentialSource
  "Resolve a remote credential reference at a secret-store boundary."
  (-resolve-token! [source reference]
    "Ok of the secret string named by `reference`, or Ok nil when unset.
     The reference, not the secret, belongs in configuration and logs."))

(defprotocol ISpanExporter
  "Hands collected spans (autonomous.domain.trace/Span, with :source) to a
   telemetry backend: an OpenTelemetry collector, a file, a test double.
   One method, so every provider is substitutable for every other (LSP):
   the collector calls it the same way for each, and none may throw."
  (-export! [exporter spans]
    "Deliver `spans` (a batch, oldest first). Answers Ok of the number
     delivered, or a refusal typed :export/* when the backend refused or
     could not be reached; the spans stay collected either way."))
