(ns autonomous.approval-text-test
  "What a human is shown, on a JS runtime: the same escaping as the JVM."
  (:require [autonomous.approval :as approval]
            [cljs.test :refer [deftest is]]
            [clojure.string]
            [autonomous.domain.naming :as naming]))

(def ^:private hostile
  ["\u001b[2J" "\r" "\n" "\u0007" "\u0085" "\u009b" "\u2028" "\u2029" "\u202e" "\u2066"
   "\u200f" "\ufeff" (js/String.fromCodePoint 0xE0041)])

(deftest safe-text-escapes-every-unsafe-character-on-js
  (doseq [c hostile]
    (let [s (approval/safe-text (str "a" c "b"))]
      (is (not (.includes s c)) (pr-str c))
      (is (re-find #"^a\\u" s) (pr-str c)))))

(deftest safe-text-escapes-the-same-way-on-js
  (is (= "Pix R$ 10,00 \\u2014 a\\u00e7\\u00e3o \\u{1f600}" (approval/safe-text "Pix R$ 10,00 — ação 😀")))
  (is (= "a\\u0022b\\u005cc" (approval/safe-text "a\"b\\c"))))

(deftest shown-actor-is-the-same-on-js
  (is (= "agent:\\u202enamuh" (naming/shown-actor "agent:\u202enamuh")))
  (is (= "agent\\u200b-1" (naming/shown-actor "agent\u200b-1")))
  (is (= "agent-\\ud83d\\ude00" (naming/shown-actor "agent-😀")) "an astral character is its surrogate pair")
  (is (= "a\\u0022\\u005cb" (naming/shown-actor "a\"\\b")))
  (is (= 64 (count (naming/shown-actor (apply str (repeat 80 "x"))))))
  (is (naming/nameless-actor? " \t\u200b\u2028"))
  (is (not (naming/nameless-actor? "a"))))

(deftest the-prompt-shows-every-field-in-printable-ascii-on-js
  (let [text (clojure.string/join "\n" (approval/prompt-lines "1" {:tool "pay" :actor "agent"
                                                                    :args {:memo (str (apply str (repeat 400 "a")) "\u001b\u202e")
                                                                           :amount 1000000 :to "mallory"}}))]
    (is (re-matches #"[\x20-\x7E\n]*" text))
    (is (re-find #"(?m)^    :amount = 1000000$" text))
    (is (re-find #"(?m)^    :to = \"mallory\"$" text))
    (is (re-find #"\(\+\d+ chars\)" text))))
