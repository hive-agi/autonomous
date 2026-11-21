(ns autonomous.approval-test
  (:require [autonomous.approval :as approval]
            [autonomous.thenable :as thenable]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m])
  (:import [java.io BufferedReader StringReader]))

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

(declare typist screen text shows? typed-asker type-at!)

(defn- answered
  "The Decision of one request on a fresh terminal where `line` is typed
   once the prompt shows, with its terminal screen."
  [line]
  (let [t (typist) out (screen) asker (typed-asker t out "op" 1000)
        d (approval/ask! asker request 2000)]
    (type-at! t out "1" line)
    [(dissoc (deref d 2000 ::hung) :ms) out]))

(deftest the-stdin-asker-approves-only-its-own-numbered-yes
  (testing "\"1 y\" approves request 1, the prompt shows its number and tool, and the outcome is said"
    (let [[d out] (answered "1 Y")]
      (is (= {:decision :approve :approver "op"} d))
      (is (re-find #"\[1\] approval requested: tool \"pay\"" (text out)))
      (is (shows? out #"request 1 approved"))))
  (testing "a bare y, another number or anything else typed denies"
    (doseq [line ["y" "yes" "2 y" "1 y please" "n" ""]]
      (is (= :deny (:decision (first (answered line)))) line))))

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

(deftest a-key-cannot-fake-an-argument-line
  (doseq [fake [(keyword "amount = 10   #") (symbol "amount = 10") (keyword "amount\u00a0= 10")]]
    (let [text (prompt {:tool "pay" :actor "agent" :args {:amount 1000000 fake "x"}})]
      (is (= 1 (count (re-seq #"(?m)^    :amount =" text))) (pr-str fake))
      (is (re-find #"(?m)^    :amount = 1000000$" text) "the real amount is there")
      (is (re-find #"(?m)^    \((keyword|symbol) \"amount\\u00(20|a0)\\u003d\\u0020" text)
          "the fake key is shown quoted, with space and = escaped")))
  (testing "a plain or qualified key prints as is"
    (is (= ":amount" (approval/shown-key :amount)))
    (is (= ":pay/to" (approval/shown-key :pay/to)))
    (is (= "0" (approval/shown-key 0))))
  (testing "a plain symbol key is always quoted, so it cannot sit bare beside its keyword twin"
    (is (= "'amount" (approval/shown-key 'amount)))
    (is (= "'pay/to" (approval/shown-key 'pay/to)))
    (let [text (prompt {:tool "pay" :actor "agent" :args {:amount 1000000 'amount 3}})]
      (is (re-find #"(?m)^    :amount = 1000000$" text))
      (is (re-find #"(?m)^    'amount = 3$" text))
      (is (not (re-find #"(?m)^    amount =" text)) "no key is shown bare"))))

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
  "A fake terminal: read-line blocks until a line is typed with `type!`.
   `:reads` counts the read-line calls, so a test can wait until the reader
   has queued every line typed so far (it is back for the next one)."
  []
  (let [q (java.util.concurrent.LinkedBlockingQueue.) reads (atom 0)]
    {:read-line #(do (swap! reads inc) (.take q)) :type! #(.put q %) :reads reads}))

(defn- screen
  "A fake terminal screen: `:w` a Writer whose text so far is the `:text` atom."
  []
  (let [text (atom "")
        add! #(swap! text str %)]
    {:text text
     :w    (proxy [java.io.Writer] []
             (write
               ([x] (add! (cond (string? x) x (int? x) (str (char x)) :else (String. ^chars x))))
               ([b off len] (add! (if (string? b)
                                    (subs b off (+ off len))
                                    (String. ^chars b (int off) (int len))))))
             (flush [])
             (close []))}))

(defn- text [s] @(:text s))

(defn- await-ref
  "Does (pred @r) hold within 5 s? Waits on a watch, not a clock: it
   answers as soon as `r` holds a value satisfying `pred`."
  [r pred]
  (let [k (Object.) ok (promise)]
    (add-watch r k (fn [_ _ _ v] (when (pred v) (deliver ok true))))
    (when (pred @r) (deliver ok true))
    (try (deref ok 5000 false) (finally (remove-watch r k)))))

(defn- shows?
  "Does screen `s` show text matching `re` within 5 s?"
  [s re]
  (boolean (await-ref (:text s) #(re-find re %))))

(defn- await-reads
  "Has typist `t` been asked for line `n` (every earlier line queued) within 5 s?"
  [t n]
  (await-ref (:reads t) #(<= n %)))

(defn- typed-asker
  ([t] (typed-asker t (screen) "op"))
  ([t out approver] (typed-asker t out approver 2000))
  ([{:keys [read-line]} out approver wait-ms]
   (approval/stdin-asker {:read-line read-line :out (:w out) :approver approver :wait-ms wait-ms})))

(defn- decision [d] (:decision (deref d 2000 ::hung)))

(defn- shown-ids
  "The request numbers prompted on screen `out`, in order."
  [out]
  (mapv second (re-seq #"(?m)^\[(\d+)\] approval requested" (text out))))

(defn- type-at!
  "Type `line` on typist `t` once request `id` is prompted on screen `out`."
  [t out id line]
  (is (await-ref (:text out) #(some #{id} (mapv second (re-seq #"(?m)^\[(\d+)\] approval requested" %))))
      (str "request " id " was prompted"))
  ((:type! t) line))

(defn- idle?
  "Has the terminal of `asker` no request left within 5 s?"
  [asker]
  (await-ref (:state (:terminal asker)) #(empty? (:queue %))))


(deftest concurrent-calls-are-asked-one-at-a-time-in-order
  (let [t     (typist)
        out   (screen)
        asker (typed-asker t out "op")
        a     (approval/ask! asker (assoc request :tool "a") 10000)
        b     (approval/ask! asker (assoc request :tool "b") 10000)]
    (type-at! t out "1" "2 y")
    (type-at! t out "2" "2 y")
    (is (= :deny (decision a)) "request 1 read the first line, meant for 2")
    (is (= :approve (decision b)) "request 2 is approved by its own line")))

(deftest a-line-typed-before-its-prompt-is-discarded
  (let [t     (typist)
        out   (screen)
        asker (typed-asker t out "op")
        a     (approval/ask! asker (assoc request :tool "a") 10000)]
    (type-at! t out "1" "1 y")
    (is (= :approve (decision a)))
    ((:type! t) "2 y")
    (is (await-reads t 3) "the reader holds \"2 y\" before request 2 is asked")
    (let [b (approval/ask! asker (assoc request :tool "b") 10000)]
      (type-at! t out "2" "nope")
      (is (= :deny (decision b)) "the pre-typed \"2 y\" did not approve request 2 unseen")
      (is (re-find #"\(1 line\(s\) typed before this prompt were discarded\)" (text out))))))

(deftest a-line-buffered-before-the-first-request-is-discarded
  (let [t (typist)]
    ((:type! t) "1 y")
    (let [out   (screen)
          asker (typed-asker t out "op")]
      (is (await-reads t 2) "building the asker starts its reader: \"1 y\" is queued before any ask")
      (let [a (approval/ask! asker (assoc request :tool "a") 10000)]
        (type-at! t out "1" "nope")
        (is (= :deny (decision a)) "the buffered \"1 y\" did not approve the first request of the stream")
        (is (re-find #"\(1 line\(s\) typed before this prompt were discarded\)" (text out)))))))

(deftest a-late-line-for-an-expired-request-cannot-answer-the-next
  (let [t     (typist)
        out   (screen)
        asker (typed-asker t out "op" 100)
        a     (approval/ask! asker (assoc request :tool "a") 10000)]
    (is (= :timeout (decision a)))
    ((:type! t) "2 y")
    (is (await-reads t 2) "the reader holds \"2 y\" before request 2 is asked")
    (let [b (approval/ask! asker (assoc request :tool "b") 10000)]
      (is (= :timeout (decision b)) "\"2 y\", typed before 2 was shown, is discarded")
      (is (re-find #"discarded" (text out))))))

(deftest two-askers-on-one-stream-share-its-queue-and-numbers
  (let [t   (typist)
        out (screen)
        pay (typed-asker t out "ana")
        rfd (typed-asker t out "bob")
        a   (approval/ask! pay (assoc request :tool "pay") 10000)
        b   (approval/ask! rfd (assoc request :tool "refund") 10000)]
    (type-at! t out "1" "1 y")
    (type-at! t out "2" "2 n")
    (is (= :approve (decision a)) "request 1 got the line typed for it")
    (is (= :deny (decision b)) "request 2 got its own line, not 1's")
    (is (= ["1" "2"] (shown-ids out)) "one counter: no two requests share a number")))

(deftest a-timed-out-request-expires-and-a-late-line-answers-nothing
  (let [t     (typist)
        out   (screen)
        asker (typed-asker t out "op" 60000)
        a     (approval/ask! asker (assoc request :tool "a") 50)]
    (is (= :timeout (decision a)))
    (is (shows? out #"request 1 expired, not run") "the terminal says so")
    (let [b (approval/ask! asker (assoc request :tool "b") 10000)]
      (type-at! t out "2" "1 y")
      (is (= :deny (decision b)) "a line typed after 1 expired answers 2, and \"1 y\" is not 2's yes")
      (is (shows? out #"request 2 denied, not run")))))

(deftest a-live-request-behind-an-expired-one-is-prompted-at-once
  (let [t     (typist)
        out   (screen)
        asker (typed-asker t out "op" 60000)
        a     (approval/ask! asker (assoc request :tool "a") 50)
        b     (approval/ask! asker (assoc request :tool "b") 30000)]
    (is (= :timeout (decision a)))
    (type-at! t out "2" "2 y")
    (is (= :approve (decision b)) "the head's expiry freed the terminal long before its 60 s wait")))

(deftest a-request-withdrawn-before-its-turn-is-dropped-unprompted
  (let [t     (typist)
        out   (screen)
        asker (typed-asker t out "op")
        a     (approval/ask! asker (assoc request :tool "a") 10000)
        b     (approval/ask! asker (assoc request :tool "b") 30)]
    (is (= :timeout (decision b)))
    (type-at! t out "1" "1 y")
    (is (= :approve (decision a)))
    (is (idle? asker) "the server has moved past request 2")
    (is (= ["1"] (shown-ids out)) "2 expired while 1 was being answered")
    (is (not (re-find #"request 2" (text out))) "nothing is said about a request never shown")
    (let [c (approval/ask! asker (assoc request :tool "c") 10000)]
      (type-at! t out "3" "3 y")
      (is (= :approve (decision c)) "no reader is left parked on the expired request"))))

(deftest mass-withdrawal-leaves-the-queue-empty
  (let [t      (typist)
        out    (screen)
        asker  (typed-asker t out "op")
        head   (approval/ask! asker (assoc request :tool "head") 10000)
        others (doall (for [i (range 200)] (approval/ask! asker (assoc request :tool (str "t" i)) 20)))]
    (is (every? #(= :timeout (decision %)) others))
    (is (= 1 (count (:queue @(:state (:terminal asker))))) "only the prompted head is left")
    (type-at! t out "1" "1 y")
    (is (= :approve (decision head)))
    (is (idle? asker))
    (is (= ["1"] (shown-ids out)))
    (is (empty? (re-seq #"request \d+ expired" (text out))) "no expiry is said for unprompted requests")))

(deftest the-outcome-is-said-after-every-answer
  (let [t     (typist)
        out   (screen)
        asker (typed-asker t out "op")
        a     (approval/ask! asker (assoc request :tool "a") 10000)
        b     (approval/ask! asker (assoc request :tool "b") 10000)]
    (type-at! t out "1" "1 yes")
    (type-at! t out "2" "nope")
    (is (= [:approve :deny] [(decision a) (decision b)]))
    (is (shows? out #"(?s)request 1 approved.*request 2 denied, not run"))))

(deftest the-outcome-line-always-matches-the-decision
  (testing "a line that answers before the timeout: approved, and said so"
    (let [t (typist) out (screen) asker (typed-asker t out "op" 60000)
          d (approval/ask! asker request 30000)]
      (type-at! t out "1" "1 y")
      (is (= :approve (decision d)))
      (is (shows? out #"request 1 approved"))
      (is (not (re-find #"request 1 expired" (text out))))))
  (testing "a line typed once the timeout has claimed the request: a timeout, and said expired"
    (let [t (typist) out (screen) asker (typed-asker t out "op" 60000)
          d (approval/ask! asker request 50)]
      (is (await-ref (:text out) #(re-find #"\[1\] approval requested" %)))
      (is (= :timeout (decision d)) "the Decision is made before the line is typed")
      ((:type! t) "1 y")
      (is (shows? out #"request 1 expired, not run"))
      (is (not (re-find #"request 1 approved" (text out))))))
  (testing "a Verdict and the timeout claim released at the same instant agree on how it ended"
    (dotimes [i 500]
      (let [p     (promise)
            go    (java.util.concurrent.CountDownLatch. 1)
            asker (future (.await go) (deliver p {:decision :approve :approver "op"}))
            claim (future (.await go) (thenable/claim! p ::timeout))]
        (.countDown go)
        @asker
        (is (= @p (deref claim 5000 ::hung))
            (str "run " i ": the asker's ending (what its outcome line says) is the Decision's"))))))


(deftest at-end-of-input-every-waiting-request-is-denied-at-once
  (let [out   (screen)
        eof   (promise)
        asker (approval/stdin-asker {:read-line #(do @eof nil) :out (:w out) :approver "op" :wait-ms 60000})
        a     (approval/ask! asker (assoc request :tool "a") 50000)
        b     (approval/ask! asker (assoc request :tool "b") 50000)]
    (deliver eof true)
    (is (= [:deny :deny] [(decision a) (decision b)]) "denied at once, not left to wait 50 s")
    (is (shows? out #"request 2 denied, not run: input has ended"))))


(deftest default-askers-under-different-in-bindings-share-system-in
  (let [a (binding [*in* (BufferedReader. (StringReader. ""))] (approval/stdin-asker {:out *err*}))
        b (binding [*in* (BufferedReader. (StringReader. ""))] (approval/stdin-asker {:out *err*}))]
    (is (identical? (:terminal a) (:terminal b)) "one System/in, one terminal")
    (is (identical? System/in (:stream a)))
    (testing "an explicit :in that is the root *in* is keyed on System/in too"
      (let [c (approval/stdin-asker {:in (approval/root-in) :out *err*})]
        (is (identical? (:terminal a) (:terminal c)))))
    (testing "the root reader is resolved when the asker is built, never a bound *in*"
      (let [bound (BufferedReader. (StringReader. ""))]
        (binding [*in* bound]
          (is (not (identical? bound (approval/root-in))))
          (is (identical? (:terminal a) (:terminal (approval/stdin-asker {:in (approval/root-in) :out *err*}))))
          (is (not (identical? System/in (:stream (approval/stdin-asker {:in bound :out *err*}))))
              "a bound reader that is not the root is its own stream"))))))

(deftest a-second-asker-with-other-ports-is-refused
  (let [t (typist)]
    (typed-asker t (screen) "op")
    (is (= :invalid-approval
           (try (typed-asker t (screen) "op") nil
                (catch clojure.lang.ExceptionInfo e (:autonomous/error (ex-data e))))))))

(deftest an-unshowable-request-is-denied-unshown
  (let [t     (typist)
        out   (screen)
        asker (typed-asker t out "op")
        many  (assoc request :args (zipmap (map #(keyword (str "k" %)) (range 40)) (range)))]
    (is (= :deny (decision (approval/ask! asker many 10000))))
    (is (shows? out #"request 1 denied, not run: too many argument values"))
    (is (empty? (shown-ids out)))))
