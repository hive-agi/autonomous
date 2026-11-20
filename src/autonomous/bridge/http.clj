(ns autonomous.bridge.http
  "Remote-MCP identity, addressing and adapter selection.

   Credential configuration contains environment REFERENCES, selected per
   calling actor with an optional target default. ICredentialSource resolves
   them at the Boundary; a secret never enters a request URL or a log."
  (:require [autonomous.bridge.port :as port]
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

(defn credential-reference
  "Pure policy: the environment reference for `grant` on `target`, selecting
   its actor before the target default, or the legacy target :token-env."
  [target grant]
  (let [credentials (:credentials target)]
    (or (get credentials (:actor grant))
        (get credentials :default)
        (some-> (:token-env target) (hash-map :token-env)))))

(defn- resolve-token!
  "Boundary: Ok of the token named by `reference` from `source`, or nil
   without one. The credential value is never part of configuration data."
  [source reference]
  (if reference
    (answer/let-answer [token (port/-resolve-token! source reference)]
      (answer/ok (some-> token str not-empty)))
    (answer/ok nil)))

(defn bearer-headers
  "Pure projection: authorization headers carrying `token`, empty without one."
  [token]
  (if token {"authorization" (str "Bearer " token)} {}))

(defn identity
  "Boundary: Answer of identity headers for `grant`, using the target's
   configured credential reference and the injected `source`."
  [target source grant]
  (answer/bind (resolve-token! source (credential-reference target grant))
               #(answer/ok (bearer-headers %))))

(def credential-source
  "The ICredentialSource of this runtime, resolved at the network boundary."
  (delay ((requiring-resolve 'autonomous.bridge.http.jdk/credential-source))))

(defn default-credential-source
  "The injected credential source, or the runtime environment source."
  [target]
  (or (:credential-source target) (force credential-source)))



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
