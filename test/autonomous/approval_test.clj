(ns autonomous.approval-test
  (:require [autonomous.approval :as approval]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m])
  (:import [java.io BufferedReader StringReader StringWriter]))

(def ^:private request {:tool "pay" :args {:amount 10} :actor "agent"})

(defn- ask [asker timeout-ms]
  (deref (approval/ask! asker request timeout-ms) 2000 ::hung))

(deftest a-queue-asker-answers-its-verdicts-in-order-and-records-requests
  (let [asker (approval/queue-asker [{:decision :approve :approver "ana"}
                                     {:decision :deny :approver "bob"}])]
    (is (approval/asker? asker))
    (is (= {:decision :approve :approver "ana"} (dissoc (ask asker 500) :ms)))
    (is (= {:decision :deny :approver "bob"} (dissoc (ask asker 500) :ms)))
    (is (= [request request] @(:requests asker)))))

(deftest no-verdict-in-time-is-a-timeout
  (let [d (ask (approval/queue-asker []) 30)]
    (is (= :timeout (:decision d)))
    (is (<= 30 (:ms d)))))

(deftest a-malformed-verdict-denies
  (is (= :deny (:decision (ask (approval/queue-asker [{:decision :maybe}]) 500))))
  (is (= :deny (:decision (ask (approval/queue-asker [:yes]) 500)))))

(deftest every-decision-matches-the-decision-schema
  (doseq [v [{:decision :approve :approver "a"} {:decision :deny :approver "b"} nil]]
    (is (m/validate approval/Decision (ask (approval/queue-asker (if v [v] [])) 20)))))

(defn- stdin-asker [typed out]
  (approval/stdin-asker {:in (BufferedReader. (StringReader. typed)) :out out :approver "op"
                         :wait-ms 300}))

(deftest the-stdin-asker-approves-only-its-own-numbered-yes
  (testing "\"1 y\" approves request 1, the prompt shows its number and tool, and the outcome is said"
    (let [out (StringWriter.)]
      (is (= {:decision :approve :approver "op"} (dissoc (ask (stdin-asker "1 Y\n" out) 500) :ms)))
      (is (re-find #"\[1\] approval requested: tool \"pay\"" (str out)))
      (Thread/sleep 50)
      (is (re-find #"request 1 approved" (str out)))))
  (testing "a bare y, another number, anything else or end of input denies or expires"
    (doseq [typed ["y\n" "yes\n" "2 y\n" "1 y please\n" "n\n" ""]]
      (is (#{:deny :timeout} (:decision (ask (stdin-asker typed (StringWriter.)) 500))) typed))))

(def ^:private hostile
  "Characters that move a cursor, end a line, reorder or hide text, or look
   like a quote."
  ["\u001b[2J" "\r" "\n" "\u0007" "\u0085" "\u009b" "\u2028" "\u2029" "\u202e" "\u2066" "\u200f"
   "\ufeff" "\u0890" "\u115f" "\u3164" "\u00a0" "\u2800" "\u034f" "\ufe0f" "\u02ba" "\u201d" "\""
   (String. (Character/toChars 0xE0041)) (String. (Character/toChars 0x13430))])

(defn- prompt [request] (str/join "\n" (approval/prompt-lines "1" request)))

(defn- printable-ascii? [s] (boolean (re-matches #"[\x20-\x7E\n]*" s)))

(deftest every-leaf-is-escaped-to-printable-ascii
  (doseq [c hostile]
    (let [text (prompt {:tool (str "pay" c) :actor (str "a" c) :session (str "s" c)
                        :args {:memo (str "ok" c "x") (str "k" c) [(str "v" c)]}})]
      (is (printable-ascii? text) (pr-str c))
      (is (not (.contains ^String text (str "ok" c "x"))) (pr-str c))
      (is (re-find #"\\u" text) "the character is shown escaped, not dropped"))))

(deftest keyword-and-symbol-leaves-are-escaped-too
  (let [text (prompt {:tool "pay" :args {:to (keyword "m\u001b[2Jallory") :via (symbol "x\u202ey")}})]
    (is (printable-ascii? text))
    (is (re-find #":m\\u001b\[2Jallory" text))
    (is (re-find #"x\\u202ey" text))))

(deftest a-look-alike-quote-cannot-fake-a-field
  (let [memo (str "x\u02ba :amount 10 :to \u02baalice")
        text (prompt {:tool "pay" :actor "agent" :args {:memo memo :amount 1000000 :to "mallory"}})]
    (is (re-find #"(?m)^    :amount = 1000000$" text))
    (is (re-find #"(?m)^    :to = \"mallory\"$" text))
    (is (re-find #"\\u02ba" text) "the look-alike is shown as an escape")))

(deftest a-long-memo-before-amount-hides-no-field
  (let [text (prompt {:tool "pay" :actor "agent-7" :session "s1"
                      :args (array-map :memo (apply str (repeat 400 "a")) :amount 1000000 :to "mallory")})]
    (is (re-find #"tool \"pay\" actor \"agent-7\" session \"s1\"" text) "who and what come first")
    (is (re-find #"(?m)^    :amount = 1000000$" text))
    (is (re-find #"(?m)^    :to = \"mallory\"$" text))
    (is (re-find #"\(\+\d+ chars\)" text) "the cut memo says how much was cut")))

(deftest an-escape-flood-cannot-push-out-a-field
  (let [text (prompt {:tool "pay" :actor "agent"
                      :args {:memo (apply str (repeat 60 "\u001b")) :amount 1000000 :to "mallory"}})]
    (is (re-find #"(?m)^    :amount = 1000000$" text))
    (is (re-find #"(?m)^    :to = \"mallory\"$" text))
    (is (every? #(<= (count %) 200) (str/split-lines text)) "every line stays bounded")))

(deftest too-many-values-are-not-showable
  (is (approval/showable? {:args (zipmap (range approval/max-shown-args) (repeat 1))}))
  (is (not (approval/showable? {:args (zipmap (range (inc approval/max-shown-args)) (repeat 1))}))))

(deftest safe-text-escapes-everything-outside-printable-ascii
  (is (= "Pix R$ 10,00 \\u2014 a\\u00e7\\u00e3o \\u{1f600}" (approval/safe-text "Pix R$ 10,00 — ação 😀")))
  (is (= "a\\u0022b\\u005cc" (approval/safe-text "a\"b\\c"))))

(defn- typist
  "A fake terminal: read-line blocks until a line is typed with `type!`."
  []
  (let [q (java.util.concurrent.LinkedBlockingQueue.)]
    {:read-line #(.take q) :type! #(.put q %)}))

(defn- typed-asker
  ([t] (typed-asker t (StringWriter.) "op"))
  ([t out approver] (typed-asker t out approver 2000))
  ([{:keys [read-line]} out approver wait-ms]
   (approval/stdin-asker {:read-line read-line :out out :approver approver :wait-ms wait-ms})))

(defn- decision [d] (:decision (deref d 2000 ::hung)))

(defn- shown-ids
  "The request numbers prompted on `out`, in order."
  [out]
  (mapv second (re-seq #"(?m)^\[(\d+)\] approval requested" (str out))))

(defn- eventually
  "Does (f) become true within a second?"
  [f]
  (loop [i 0] (cond (f) true (< i 100) (do (Thread/sleep 10) (recur (inc i))) :else false)))

(deftest concurrent-calls-are-asked-one-at-a-time-in-order
  (let [t     (typist)
        asker (typed-asker t)
        a     (approval/ask! asker (assoc request :tool "a") 2000)
        b     (approval/ask! asker (assoc request :tool "b") 2000)]
    ((:type! t) "2 y")
    ((:type! t) "2 y")
    (is (= :deny (decision a)) "request 1 read the first line, meant for 2")
    (is (= :approve (decision b)) "request 2 is approved by its own line")))

(deftest two-askers-on-one-stream-share-its-queue-and-numbers
  (let [t   (typist)
        out (StringWriter.)
        pay (typed-asker t out "ana")
        rfd (typed-asker t out "bob")
        a   (approval/ask! pay (assoc request :tool "pay") 2000)
        b   (approval/ask! rfd (assoc request :tool "refund") 2000)]
    ((:type! t) "1 y")
    ((:type! t) "2 n")
    (is (= :approve (decision a)) "request 1 got the line typed for it")
    (is (= :deny (decision b)) "request 2 got its own line, not 1's")
    (is (= ["1" "2"] (shown-ids out)) "one counter: no two requests share a number")))

(deftest a-timed-out-request-expires-and-a-late-line-answers-nothing
  (let [t     (typist)
        out   (StringWriter.)
        asker (typed-asker t out "op")
        a     (approval/ask! asker (assoc request :tool "a") 50)]
    (is (= :timeout (decision a)))
    (is (eventually #(re-find #"request 1 expired, not run" (str out))) "the terminal says so")
    (let [b (approval/ask! asker (assoc request :tool "b") 2000)]
      (is (eventually #(= ["1" "2"] (shown-ids out))))
      ((:type! t) "1 y")
      (is (= :deny (decision b)) "a line typed after 1 expired answers 2, and \"1 y\" is not 2's yes")
      (is (eventually #(re-find #"request 2 denied, not run" (str out)))))))

(deftest a-live-request-behind-an-expired-one-is-prompted-at-once
  (let [t     (typist)
        out   (StringWriter.)
        asker (typed-asker t out "op" 2000)
        a     (approval/ask! asker (assoc request :tool "a") 50)
        b     (approval/ask! asker (assoc request :tool "b") 500)]
    (is (= :timeout (decision a)))
    (is (eventually #(= ["1" "2"] (shown-ids out))) "the head's expiry frees the terminal now")
    ((:type! t) "2 y")
    (is (= :approve (decision b)) "the live request was shown and answered in time")))

(deftest a-request-withdrawn-before-its-turn-is-dropped-unprompted
  (let [t     (typist)
        out   (StringWriter.)
        asker (typed-asker t out "op")
        a     (approval/ask! asker (assoc request :tool "a") 2000)
        b     (approval/ask! asker (assoc request :tool "b") 30)]
    (is (= :timeout (decision b)))
    ((:type! t) "1 y")
    (is (= :approve (decision a)))
    (Thread/sleep 50)
    (is (= ["1"] (shown-ids out)) "2 expired while 1 was being answered")
    (is (not (re-find #"request 2" (str out))) "nothing is said about a request never shown")
    (let [c (approval/ask! asker (assoc request :tool "c") 2000)]
      ((:type! t) "3 y")
      (is (= :approve (decision c)) "no reader is left parked on the expired request"))))

(deftest mass-withdrawal-leaves-the-queue-empty
  (let [t      (typist)
        out    (StringWriter.)
        asker  (typed-asker t out "op")
        head   (approval/ask! asker (assoc request :tool "head") 2000)
        others (doall (for [i (range 200)] (approval/ask! asker (assoc request :tool (str "t" i)) 20)))]
    (is (every? #(= :timeout (decision %)) others))
    (is (= 1 (count (:queue @(:state (:terminal asker))))) "only the prompted head is left")
    (is (= ["1"] (shown-ids out)))
    (is (empty? (re-seq #"request \d+ expired" (str out))) "no expiry is said for unprompted requests")
    ((:type! t) "1 y")
    (is (= :approve (decision head)))
    (is (eventually #(empty? (:queue @(:state (:terminal asker))))))))

(deftest the-outcome-is-said-after-every-answer
  (let [t     (typist)
        out   (StringWriter.)
        asker (typed-asker t out "op")
        a     (approval/ask! asker (assoc request :tool "a") 2000)
        b     (approval/ask! asker (assoc request :tool "b") 2000)]
    ((:type! t) "1 yes")
    ((:type! t) "nope")
    (is (= [:approve :deny] [(decision a) (decision b)]))
    (is (eventually #(re-find #"(?s)request 1 approved.*request 2 denied, not run" (str out))))))

(deftest default-askers-under-different-in-bindings-share-system-in
  (let [a (binding [*in* (BufferedReader. (StringReader. ""))] (approval/stdin-asker {:out *err*}))
        b (binding [*in* (BufferedReader. (StringReader. ""))] (approval/stdin-asker {:out *err*}))]
    (is (identical? (:terminal a) (:terminal b)) "one System/in, one terminal")
    (is (identical? System/in (:stream a)))))

(deftest a-second-asker-with-other-ports-is-refused
  (let [t (typist)]
    (typed-asker t (StringWriter.) "op")
    (is (= :invalid-approval
           (try (typed-asker t (StringWriter.) "op") nil
                (catch clojure.lang.ExceptionInfo e (:autonomous/error (ex-data e))))))))

(deftest an-unshowable-request-is-denied-unshown
  (let [t     (typist)
        out   (StringWriter.)
        asker (typed-asker t out "op")
        many  (assoc request :args (zipmap (map #(keyword (str "k" %)) (range 40)) (range)))]
    (is (= :deny (decision (approval/ask! asker many 2000))))
    (is (empty? (shown-ids out)))
    (is (eventually #(re-find #"request 1 denied, not run: too many argument values" (str out))))))
