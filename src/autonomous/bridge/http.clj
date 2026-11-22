(ns autonomous.bridge.http
  "Remote-MCP identity, addressing and adapter selection.

   Credential configuration contains environment REFERENCES, selected per
   calling actor with an optional target default. ICredentialSource resolves
   them at the Boundary; a secret never enters a request URL or a log."
  (:require [autonomous.bridge.port :as port]
            [autonomous.bridge.url :as url]
            [autonomous.domain.answer :as answer]
            [clojure.string :as str]
            [autonomous.domain.naming :as naming]))

(defn host-label
  "A short secret-free label of `target`'s remote: its url without scheme,
   userinfo, query or fragment."
  [target]
  (let [u (url/redacted (:url target))
        i (str/index-of u "://")
        s (if i (subs u (+ i 3)) u)]
    (subs s 0 (min 80 (count s)))))

(defn shown-url
  "The url of `target` as a manifest, a label or a log may carry it."
  [target]
  (url/redacted (:url target)))

(def default-timeout-ms 30000)

(def manifest-ttl-ms
  "How long a listing is reused without a list_changed from the remote."
  5000)

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

(defn- visible-ascii?
  "True when every character of `s` is visible ASCII (0x21-0x7E), the only
   characters a bearer token may hold."
  [s]
  (every? #(<= 0x21 (int %) 0x7E) s))

(defn- resolve-token!
  "Boundary: Ok of the token named by `reference` from `source`, or nil
   when no reference is configured. A configured reference whose value is
   unset or blank is refused, never sent anonymously. The credential value
   is never part of configuration data,
   and a refusal names only the reference, never the value: a token with a
   control character (a trailing newline from an env file) is refused here,
   before an HTTP library can echo it in an error."
  [source reference]
  (if reference
    (answer/let-answer [token (port/-resolve-token! source reference)]
      (let [t   (some-> token str)
            env (:token-env reference)]
        (cond
          (str/blank? t)          (answer/refusal :channel/credential-unset
                                                  (str "the credential " env " is configured for this"
                                                       " agent but unset or empty: refusing to go anonymous")
                                                  {:token-env env})
          (not (visible-ascii? t)) (answer/refusal :channel/credential-invalid
                                                   (str "the credential in " env " holds a character"
                                                        " outside visible ASCII (a trailing newline?)")
                                                   {:token-env env})
          :else                    (answer/ok t))))
    (answer/ok nil)))

(defn bearer-headers
  "Pure projection: authorization headers carrying `token`, empty without one."
  [token]
  (if token {"authorization" (str "Bearer " token)} {}))

(defn- selected-reference
  "Ok of `grant`'s credential reference on `target`, nil for a target with
   no credential configuration at all. A target that carries the
   :credentials key fails closed, even when it is empty: an actor matching
   no entry, with no :default and no legacy :token-env, is refused naming
   only the target id and the actor as naming/shown-actor shows it (quoted
   in the message by naming/quoted-actor), so a control, bidi or zero-width
   character is escaped, never repeated, and distinct actors stay distinct."
  [target grant]
  (let [reference (credential-reference target grant)]
    (if (and (nil? reference) (some? (:credentials target)))
      (answer/refusal :channel/credential-missing
                      (str "no credential is configured for agent " (naming/quoted-actor (:actor grant))
                           " on " (:target/id target) ", and no :default: refusing to go anonymous")
                      {:actor (naming/shown-actor (:actor grant)) :target (:target/id target)})
      (answer/ok reference))))

(defn identity
  "Boundary: Answer of identity headers for `grant`, using the target's
   configured credential reference and the injected `source`."
  [target source grant]
  (answer/bind (answer/bind (selected-reference target grant) #(resolve-token! source %))
               (fn [token]
                 (if (and token (not (url/bearer-allowed? (:url target))))
                   (answer/refusal :channel/insecure-transport
                                   (str "refusing to send a bearer token to " (host-label target)
                                        ": use https, or plain http to this host only"))
                   (answer/ok (bearer-headers token))))))

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

(def client
  "The IHttpClient of this runtime, resolved once. Pure users never load the
   adapter: autonomous.bridge.http.jdk needs java.net.http."
  (delay ((requiring-resolve 'autonomous.bridge.http.jdk/client))))

(defn default-client
  "The injected HTTP port of a channel when the caller names none."
  []
  (force client))
