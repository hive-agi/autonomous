(ns autonomous.approval-text-test
  "What a human is shown, on a JS runtime: the same escaping as the JVM."
  (:require [autonomous.approval :as approval]
            [cljs.test :refer [deftest is]]))

(def ^:private hostile
  ["\u001b[2J" "\r" "\n" "\u0007" "\u0085" "\u009b" "\u2028" "\u2029" "\u202e" "\u2066"
   "\u200f" "\ufeff" (js/String.fromCodePoint 0xE0041)])

(deftest safe-text-escapes-every-unsafe-character-on-js
  (doseq [c hostile]
    (let [s (approval/safe-text (str "a" c "b"))]
      (is (not (.includes s c)) (pr-str c))
      (is (re-find #"^a\\u" s) (pr-str c)))))

(deftest safe-text-keeps-ordinary-text-on-js
  (is (= "Pix R$ 10,00 — ação ✓ 😀" (approval/safe-text "Pix R$ 10,00 — ação ✓ 😀"))))

(deftest the-prompt-escapes-args-and-is-capped-on-js
  (let [line (approval/prompt-line "1" {:tool "pay" :args {:memo "x\u001b[2Jy\u202e"}})]
    (is (not (.includes line "\u001b")))
    (is (not (.includes line "\u202e"))))
  (is (= approval/max-shown-chars
         (count (approval/shown-request {:args {:memo (apply str (repeat 5000 "x"))}})))))
