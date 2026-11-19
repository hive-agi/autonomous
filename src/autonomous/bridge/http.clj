(ns autonomous.bridge.http
  "Remote-MCP adapter of IRuntimeChannel and IRuntimeDirectory: the app is
   reached over its own /mcp endpoint by URL (the handler autonomous.ring
   mounts), so an app behind a firewall needs no nREPL and no inbound port.

   One MCP session per AGENT: a remote binds a session to the actor that
   opened it, so agents never share one. Each exchange carries the agent's
   own identity from the target's :identity port, a fn of the grant
   returning headers (a bearer from a token in the environment by default).
   The token rides a header and nothing else: never the URL, never a log.

   notifications/tools/list_changed on the remote's event stream marks the
   cached listing stale, so the next look re-reads it; a remote that is down
   refuses, and the fleet then hides only its own tools.

   Strata: autonomous.bridge.rpc is the pure dialogue, the port is
   autonomous.bridge.port/IHttpClient, autonomous.bridge.http.jdk is the
   network."
  (:require [autonomous.bridge.port :as port]
            [autonomous.bridge.rpc :as rpc]
            [autonomous.domain.answer :as answer]
            [clojure.string :as str]))

(def default-timeout-ms 30000)

(def manifest-ttl-ms
  "How long a listing is reused without a list_changed from the remote."
  5000)

(def remote-op-tools
  "Request ops that are one call of a remote built-in tool."
  {:poll "autonomous_poll" :journal "autonomous_journal" :lease "autonomous_lease"
   :release "autonomous_release"})

;; =============================================================================
;; Identity
;; =============================================================================

(defn token-of
  "The bearer token of `target`, from its :token-env (\"X_TOKEN\") or :token."
  [target]
  (or (:token target) (some-> (:token-env target) System/getenv str not-empty)))

(defn bearer-headers
  "Identity headers of the default port: the target's bearer, read per call
   so a new environment needs no rebuild. Empty when no token is set."
  [target]
  (if-let [t (token-of target)] {"authorization" (str "Bearer " t)} {}))

(defn identity
  "The identity port of `target`: (fn [grant] headers). Default is the
   target's bearer; an app with its own auth passes :identity."
  [target]
  (or (:identity target) (fn [_grant] (bearer-headers target))))

;; =============================================================================
;; Addressing
;; =============================================================================

(defn endpoint
  "The absolute /mcp URL of `target`: its :url, with the MCP path appended
   when the url is a bare origin or directory."
  [target]
  (let [u (str/replace (str (:url target)) #"/+$" "")]
    (if (str/ends-with? u "/mcp") u (str u "/mcp"))))

(defn host-label
  "A short token-free label of `target`'s remote: its url without scheme."
  [target]
  (let [u (str (:url target))
        i (str/index-of u "://")
        s (if i (subs u (+ i 3)) u)]
    (subs s 0 (min 80 (count s)))))

(def client
  "The IHttpClient of this runtime, resolved once. Pure users never load the
   adapter: autonomous.bridge.http.jdk needs java.net.http."
  (delay ((requiring-resolve 'autonomous.bridge.http.jdk/client))))

(defn default-client
  "The injected HTTP port of a channel when the caller names none."
  []
  (force client))
