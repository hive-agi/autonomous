(ns autonomous.approval.signed-test
  "The signed asker, with hive-cloak and real gpg in throwaway GNUPGHOMEs
   under the temp directory (never ~/.gnupg). Skipped as a whole when
   hive-cloak is not on the classpath (it is an optional dependency: run
   with -Sdeps '{:deps {io.github.hive-agi/hive-cloak {:local/root ...}}}')
   or gpg is absent."
  (:require [autonomous.approval :as approval]
            [autonomous.approval.signed :as signed]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *k* nil)

(defn- cloak [sym] (try (requiring-resolve (symbol "hive-cloak.api" (name sym))) (catch Throwable _ nil)))

(defn- gpg-available? [] (try (zero? (:exit (sh/sh "gpg" "--version"))) (catch Exception _ false)))

(defn- temp-dir [prefix]
  (str (java.nio.file.Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- gpg! [home & args]
  (let [{:keys [exit out]} (apply sh/sh "gpg" "--homedir" home "--batch" "--yes"
                                  "--pinentry-mode" "loopback" "--passphrase" "" args)]
    (when-not (zero? exit) (throw (ex-info "gpg failed in a test keyring" {:args (vec args)})))
    out))

(def ^:private safe-agent-conf
  "ignore-cache-for-signing\nno-allow-loopback-pinentry\npinentry-program /usr/bin/pinentry-tty\n")

(defn- make-key! [label]
  (let [home (temp-dir (str "auto-approval-" label "-"))]
    (gpg! home "--quick-gen-key" (str label " <" label "@approval.test.invalid>") "ed25519" "cert,sign" "never")
    (let [fpr (->> (str/split-lines (gpg! home "--with-colons" "--list-keys"))
                   (some #(when (str/starts-with? % "fpr:") (nth (str/split % #":") 9))))
          pub (io/file home "pub.asc")]
      (spit pub (gpg! home "--armor" "--export" fpr))
      (spit (io/file home "gpg-agent.conf") safe-agent-conf)
      {:home home :fpr fpr :pub (str pub)})))

(defn- delete-tree! [path]
  (doseq [f (reverse (file-seq (io/file path)))] (io/delete-file f true)))

(defmacro ^:private with-keys
  "Run `body` with the throwaway keys; without them, assert why there are
   none (hive-cloak or gpg really absent), so a skip is never silent."
  [& body]
  `(if *k*
     (do ~@body)
     (is (not (and (cloak '~'verify-approval) (gpg-available?)))
         "skipped only because hive-cloak (an optional dependency) or gpg is absent")))

(use-fixtures :once
  (fn [t]
    (if-not (and (cloak 'verify-approval) (gpg-available?))
      (do (println "SKIP the signature cases of autonomous.approval.signed-test: hive-cloak or gpg is absent")
          (t))
      (let [ks {:pedro (make-key! "pedro") :other (make-key! "other")}]
        (try (binding [*k* ks] (t))
             (finally (doseq [{:keys [home]} (vals ks)]
                        (sh/sh "gpgconf" "--homedir" home "--kill" "gpg-agent")
                        (delete-tree! home))))))))

(defn- pins []
  (:ok ((cloak 'approval-pins) {:approval-pins (str (get-in *k* [:pedro :fpr]) "=pedro")
                                :approval-public-keys (get-in *k* [:pedro :pub])})))

(defn- sign-as
  "A detached signature by `who`'s key over `text`, made the way the
   operator's gpg makes one."
  [who text]
  (:out (sh/sh "gpg" "--homedir" (get-in *k* [who :home]) "--batch" "--yes" "--pinentry-mode" "loopback"
               "--passphrase" "" "--armor" "--detach-sign" "--local-user" (get-in *k* [who :fpr])
               :in text)))

(defn- statement [{:keys [nonce request-sha256 expires-ms]} & {:keys [decision] :or {decision :approve}}]
  (:ok ((cloak 'approval-statement) {:nonce nonce :request-sha256 request-sha256
                                     :decision decision :expires-ms expires-ms})))

(def request {:tool "sim.pix.pay" :args {:amount 100 :to "chave-x"} :actor "ling-1" :session "s-1"})

(defn- asker-with
  "A signed asker whose transport answers `(answer pending)`, and the
   refusal reasons it reported."
  [answer & {:as opts}]
  (let [why (atom [])]
    [(signed/signed-asker (merge {:pins (pins)
                                  :solicit (fn [pending] (doto (promise) (deliver (answer pending))))
                                  :on-refusal #(swap! why conj %)}
                                 opts))
     why]))

(defn- decide [asker] (dissoc (deref (approval/ask! asker request 5000) 6000 ::hung) :ms))

(deftest judge-never-trusts-the-transport-s-word
  (testing "no hive-cloak needed: anything that is not a statement+signature pair is refused before verify runs"
    (let [verified (atom 0)
          ctx      {:verify (fn [& _] (swap! verified inc) {:ok {:decision :approve :approver "pin" :nonce "n" :expires-ms 0}})
                    :pins {"F" {}} :seen (atom {}) :now-ms (constantly 0)}
          pending  {:nonce "n" :request-sha256 "h" :expires-ms 1}]
      (doseq [[answer why] [["yes" :unsigned]
                            [nil :unsigned]
                            [{:decision :approve :approver "human"} :unexpected-fields]
                            [{:statement "s" :signature "sig" :by "human"} :self-named-approver]
                            [{:statement "s"} :unsigned]]]
        (let [v (signed/judge ctx pending answer)]
          (is (= :deny (:decision v)) (pr-str answer))
          (is (= why (:refused v)) (pr-str answer))
          (is (not= "human" (:approver v)))))
      (is (zero? @verified) "the verifier is never even consulted for those")
      (testing "the approver comes from verify's pin, never from the answer"
        (is (= "pin" (:approver (signed/judge ctx pending {:statement "s" :signature "sig"}))))))))

(deftest a-valid-signature-approves-and-the-approver-is-the-pin
  (with-keys
    (let [[asker why] (asker-with (fn [p] (let [s (statement p)] {:statement s :signature (sign-as :pedro s)})))]
      (is (= {:decision :approve :approver "pedro"} (decide asker)))
      (is (empty? @why)))))

(deftest a-signed-deny-denies
  (with-keys
    (let [[asker _] (asker-with (fn [p] (let [s (statement p :decision :deny)] {:statement s :signature (sign-as :pedro s)})))]
      (is (= {:decision :deny :approver "pedro"} (decide asker))))))

(deftest an-agent-side-yes-through-the-hivemind-is-refused
  (with-keys
    (testing "the hive ask is answered \"yes\" (hivemind_respond by any agent): no signature, denied"
      (let [asked (atom nil)
            ask!  (fn [agent-id question _options & {:as opts}]
                    (reset! asked {:agent-id agent-id :question question :context (:context opts)})
                    {:decision "yes" :answer "yes" :by "human" :ask-id "a-1"})
            why   (atom [])
            asker (signed/signed-asker {:pins (pins) :on-refusal #(swap! why conj %)
                                        :solicit (signed/hive-solicit {:ask! ask! :agent-id "ling-1"})})]
        (is (= :deny (:decision (decide asker))))
        (is (not= "human" (:approver (decide asker))) "the defaulted :by \"human\" never becomes the approver")
        (is (str/includes? (:context @asked) "hive-approval/1") "the ask carried the statement to sign")
        (is (every? #{:unsigned} @why))))
    (testing "the same hive ask answered with a real signature approves"
      (let [ask!  (fn [_ _ _ & {:keys [context]}]
                    (let [text (second (str/split context #"sign exactly these bytes\):\n" 2))]
                      {:answer (sign-as :pedro text) :by "human"}))
            asker (signed/signed-asker {:pins (pins)
                                        :solicit (signed/hive-solicit {:ask! ask! :agent-id "ling-1"})})]
        (is (= {:decision :approve :approver "pedro"} (decide asker)))))))

(deftest every-forgery-is-refused-with-its-reason
  (with-keys
    (let [cases
          {:unsigned            (fn [_] "yes")
           :self-named-approver (fn [p] (let [s (statement p)] {:statement s :signature (sign-as :pedro s) :by "pedro"}))
           :unknown-key         (fn [p] (let [s (statement p)] {:statement s :signature (sign-as :other s)}))
           :nonce-mismatch      (fn [p] (let [s (statement (assoc p :nonce (apply str (repeat 32 "0"))))]
                                          {:statement s :signature (sign-as :pedro s)}))
           :request-mismatch    (fn [p] (let [s (statement (assoc p :request-sha256 (apply str (repeat 64 "0"))))]
                                          {:statement s :signature (sign-as :pedro s)}))
           :expired             (fn [p] (let [s (statement (assoc p :expires-ms 1))]
                                          {:statement s :signature (sign-as :pedro s)}))
           :bad-signature       (fn [p] (let [s (statement p)]
                                          {:statement s :signature (sign-as :pedro (str/replace s "approve" "deny"))}))}]
      (doseq [[reason answer] cases]
        (let [[asker why] (asker-with answer)
              d (decide asker)]
          (is (= :deny (:decision d)) (name reason))
          (is (not= "pedro" (:approver d)) (name reason))
          (is (= [reason] @why) (name reason)))))))

(deftest a-replayed-signature-is-refused
  (with-keys
    (testing "a signed statement captured once and replayed onto the next request: a nonce mismatch"
      (let [captured (atom nil)
            [asker why] (asker-with (fn [p]
                                      (or @captured
                                          (let [s (statement p)]
                                            (reset! captured {:statement s :signature (sign-as :pedro s)})))))]
        (is (= :approve (:decision (decide asker))))
        (is (= :deny (:decision (decide asker))))
        (is (= [:nonce-mismatch] @why))))
    (testing "the same nonce answered twice to one pending request is consumed once"
      (let [pending {:nonce (apply str (repeat 32 "a")) :request-sha256 (apply str (repeat 64 "b"))
                     :expires-ms (+ (approval/now-ms) 60000)}
            s       (statement pending)
            answer  {:statement s :signature (sign-as :pedro s)}
            ctx     {:verify (cloak 'verify-approval) :pins (pins) :seen (atom {}) :now-ms approval/now-ms}]
        (is (= :approve (:decision (signed/judge ctx pending answer))))
        (is (= :replayed (:refused (signed/judge ctx pending answer))))))))

(deftest no-answer-in-time-is-a-timeout
  (with-keys
    (let [asker (signed/signed-asker {:pins (pins) :solicit (fn [_] (promise))})]
      (is (= :timeout (:decision (deref (approval/ask! asker request 100) 2000 ::hung)))))))

(deftest it-refuses-to-build-without-pins-or-transport
  (let [refused? #(try (%) false (catch clojure.lang.ExceptionInfo e
                                   (= :invalid-approval (:autonomous/error (ex-data e)))))]
    (if-not (cloak 'verify-approval)
      (testing "without hive-cloak there is no verifier, so no signed asker is built at all"
        (is (refused? #(signed/signed-asker {:pins {"F" {}} :solicit (fn [_] (promise))}))))
      (do
        (is (refused? #(signed/signed-asker {:pins {} :solicit (fn [_] (promise))})))
        (is (refused? #(signed/signed-asker {:pins {"F" {}}})))))))
