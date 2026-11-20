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
     :timeout-ms}): {:ok {:status :headers :body}} or a
     :channel/transport-failed refusal. Never throws.")
  (-open-stream! [client req sink]
    "Answer of a held GET to `req` whose event text arrives at `sink`
     ({:text! (fn [s]) :close! (fn [])}), or a refusal. Ok of
     {:close! (fn [])} when the stream was opened; the caller closes it."))

(defprotocol ICredentialSource
  "Resolve a remote credential reference at a secret-store boundary."
  (-resolve-token! [source reference]
    "Ok of the secret string named by `reference`, or Ok nil when unset.
     The reference, not the secret, belongs in configuration and logs."))
