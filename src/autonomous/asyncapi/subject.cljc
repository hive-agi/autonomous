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

(defn problems
  "Why `filter` is not a usable NATS filter: a vector of keywords, empty when
   it is one. `*` may sit in any position; `>` must be last."
  [filter]
  (let [ts (tokens filter)]
    (cond
      (nil? ts)                     [:blank]
      (some bad-token? ts)          [:empty-token]
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
  "True when concrete `subject` is published under `filter`."
  [filter subject]
  (let [ps (tokens filter) s (tokens subject)]
    (and (seq ps) (seq s) (not-any? bad-token? ps) (not-any? bad-token? s)
         (match-tokens ps s))))

(defn literal?
  "True when `filter` names one subject and no token is a wildcard."
  [filter]
  (let [ts (tokens filter)]
    (and (seq ts) (not-any? #{wildcard-one wildcard-rest} ts))))
