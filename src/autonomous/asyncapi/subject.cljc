(ns autonomous.asyncapi.subject
  "NATS subjects and filters, pure. A subject is dot-separated tokens;
   `*` in a filter matches exactly one token and `>` matches the rest.

     (matches? \"sisf.payment.*.entry\" \"sisf.payment.monero.entry\") ;=> true
     (matches? \"sisf.payment.*.entry\" \"sisf.payment.monero\")        ;=> false
     (matches? \"a.>\" \"a.b.c\")                                      ;=> true

   `>` matches one token or more, as NATS does, so \"a.>\" does not match
   \"a\". A filter that `valid?` accepts is the only thing a resource may
   subscribe with: an empty token (\"a..b\"), a blank subject, or a `>`
   anywhere but last would otherwise subscribe to something that never
   fires and look like silence."
  (:require [autonomous.domain.answer :as answer]
            [clojure.string :as str]))

(def wildcard-one "*")

(def wildcard-rest ">")

(defn tokens
  "Token vector of `subject`, or nil when it is not a non-blank string."
  [subject]
  (when (and (string? subject) (not (str/blank? subject)))
    (str/split subject #"\." -1)))

(defn- bad-token? [t] (str/blank? t))

(defn- whitespace-token? [t]
  (boolean (re-find #"[\s\x00]" t)))

(defn- partial-wildcard? [wildcard t]
  (and (str/includes? t wildcard) (not= wildcard t)))

(defn problems
  "Why `filter` is not a usable NATS filter: a vector of keywords, empty when
   it is one. Tokens are nonblank, `*` and `>` occupy a whole token, and `>`
   must be last."
  [filter]
  (let [ts (tokens filter)]
    (cond
      (nil? ts) [:blank]
      (some bad-token? ts) [:empty-token]
      (some whitespace-token? ts) [:whitespace-or-null]
      (some #(partial-wildcard? wildcard-one %) ts) [:wildcard-not-a-token]
      (some #(partial-wildcard? wildcard-rest %) ts) [:wildcard-not-a-token]
      (some #{wildcard-rest} (butlast ts)) [:wildcard-rest-not-last]
      :else [])))

(defn valid? [filter] (empty? (problems filter)))

(defn check
  "Ok `filter` when it is a usable filter, else a refusal naming what is
   wrong. Never throws."
  [filter]
  (let [ps (problems filter)]
    (if (empty? ps)
      (answer/ok filter)
      (answer/refusal :asyncapi/bad-subject
                      (str (pr-str filter) " is not a NATS subject filter: "
                           (str/join ", " (map name ps)))
                      {:problems ps}))))

(defn- match-tokens [pats subs]
  (loop [p pats s subs]
    (cond
      (empty? p)                  (empty? s)
      (= wildcard-rest (first p)) (boolean (seq s))
      (empty? s)                  false
      (= wildcard-one (first p))  (recur (rest p) (rest s))
      (= (first p) (first s))     (recur (rest p) (rest s))
      :else                       false)))

(defn matches?
  "True when concrete `subject` is published under valid `filter`. A subject
   is concrete only when it is valid and contains no wildcard token."
  [filter subject]
  (and (valid? filter)
       (valid? subject)
       (not-any? #{wildcard-one wildcard-rest} (tokens subject))
       (match-tokens (tokens filter) (tokens subject))))

(defn literal?
  "True when `filter` is valid and names one concrete subject, with no
   wildcard token."
  [filter]
  (and (valid? filter)
       (not-any? #{wildcard-one wildcard-rest} (tokens filter))))
