(ns autonomous.bridge.auth
  "Bearer tokens for the network transport. A token names an actor and
   grants scopes; configuration stores only its SHA-256, so a leaked config
   file grants nothing. One token per agent gives every swarm member its own
   identity in the journal and ledger, and lets one be revoked alone.

   `mint` is the only effect (randomness); everything else is pure."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.scope :as scope]
            [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.util Base64)))

(def token-prefix "aut_")

(defn mint
  "A fresh bearer token: the prefix and 32 random bytes, base64url."
  []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (str token-prefix (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))))

(defn sha256-hex
  "Lowercase hex SHA-256 of string `s` (UTF-8)."
  [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn token-entry
  "The configuration entry for `token`: its hash, actor and scopes."
  [token actor scopes]
  {:sha256 (sha256-hex token) :actor actor :scopes (vec (sort (scope/normalize scopes)))})

(defn bearer
  "The token of an Authorization header value \"Bearer <token>\", or nil.
   The scheme is case-insensitive; surrounding blanks are ignored."
  [header]
  (when (string? header)
    (let [h   (str/trim header)
          cut (str/index-of h " ")]
      (when cut
        (let [scheme (subs h 0 cut)
              token  (str/trim (subs h cut))]
          (when (and (= "bearer" (str/lower-case scheme)) (not (str/blank? token)))
            token))))))

(defn- same-hash?
  "Constant-time comparison of two hex digests."
  [^String a ^String b]
  (MessageDigest/isEqual (.getBytes a StandardCharsets/UTF_8) (.getBytes b StandardCharsets/UTF_8)))

(defn authenticate
  "Ok grant {:actor :scopes} for `presented` token against TokenEntry
   `entries`, or an :auth/unauthorized refusal. Every entry is compared, so
   the time taken does not reveal which one matched."
  [entries presented]
  (if (str/blank? presented)
    (answer/refusal :auth/unauthorized "missing bearer token")
    (let [h   (sha256-hex presented)
          hit (reduce (fn [found e] (if (same-hash? h (:sha256 e)) e found)) nil entries)]
      (if hit
        (answer/ok {:actor (:actor hit) :scopes (scope/normalize (:scopes hit))})
        (answer/refusal :auth/unauthorized "unknown bearer token")))))
