(ns autonomous.approval.signed
  "L1 adapter (clj): an Asker whose answer counts only when the operator's
   approval key signed it. hive-cloak (hive-cloak.api) supplies the
   statement format, the canonical request hash and the pure verifier, and
   is resolved with requiring-resolve at construction; without it on the
   classpath `signed-asker` refuses to build rather than answer.

   One request:
     1. a fresh nonce, the request hash over the redacted request, an expiry
     2. `:solicit` (the transport) is handed {:request :nonce
        :request-sha256 :expires-ms} and answers a promise of the answer:
        {:statement str :signature armor}, or nil for no answer
     3. the answer is verified HERE, against the pinned keys: signed by a
        pin, naming this nonce and this request hash, unexpired, nonce not
        seen before. The approver is the pin's name. Anything else (a bare
        \"yes\", a map carrying :by, a replay, another request, a stale
        statement, an unknown key) is a :deny with no approver change.

   The transport is untrusted on purpose: a hivemind answer, an agent, or
   a forged message can deliver any value, and only a valid signature by a
   pinned key approves."
  (:require [autonomous.approval :as approval]
            [clojure.string :as str]))

(defn- cloak
  "L1 host: hive-cloak.api's `sym`, or nil when hive-cloak is absent."
  [sym]
  (try (requiring-resolve (symbol "hive-cloak.api" (name sym)))
       (catch Throwable _ nil)))

(defn- ok [r] (:ok r))

(def ^:private answer-keys #{:statement :signature})

(defn- refusal
  "L1: why `answer` is not even a candidate, or nil."
  [answer]
  (cond
    (not (map? answer))                       :unsigned
    (contains? answer :by)                    :self-named-approver
    (seq (remove answer-keys (keys answer)))  :unexpected-fields
    (not (and (string? (:statement answer)) (string? (:signature answer)))) :unsigned
    :else nil))

(defn- seen!
  "L1 Boundary: record `nonce` as used; true when it was new. Old entries
   are dropped once past their expiry."
  [seen nonce expires-ms now]
  (let [[before _] (swap-vals! seen (fn [m] (if (contains? m nonce)
                                              m
                                              (assoc (into {} (filter #(> (val %) now)) m)
                                                     nonce expires-ms))))]
    (not (contains? before nonce))))

(defn judge
  "L1: the Verdict for `answer` to the pending request `pending`
   {:nonce :request-sha256 :expires-ms}, plus :refused when it did not
   approve. Verifies with `verify` (hive-cloak.api/verify-approval) against
   `pins`; consumes the nonce from the `seen` atom on a valid signature."
  [{:keys [verify pins seen now-ms]} pending answer]
  (let [deny (fn [why] {:decision :deny :approver "unverified" :refused why})]
    (if-let [why (refusal answer)]
      (deny why)
      (let [now (now-ms)
            v   (verify (:statement answer) (:signature answer) pins
                        {:now-ms now :nonce (:nonce pending) :request-sha256 (:request-sha256 pending)})]
        (cond
          (:error v)
          (deny (or (:reason v) (:error v)))

          (not (seen! seen (:nonce (ok v)) (:expires-ms (ok v)) now))
          (deny :replayed)

          :else
          {:decision (:decision (ok v)) :approver (:approver (ok v)) :fingerprint (:fingerprint (ok v))})))))

(defrecord SignedAsker [solicit verify request-sha256 new-nonce pins ttl-ms seen now-ms on-refusal]
  approval/Asker
  (-ask! [this request]
    (let [out (promise)
          h   (request-sha256 request)]
      (if (:error h)
        (deliver out {:decision :deny :approver "unverified"})
        (future
          (try
            (let [pending {:nonce (new-nonce) :request-sha256 (ok h) :expires-ms (+ (now-ms) ttl-ms)}
                  answer  (deref (solicit (assoc pending :request request)) ttl-ms nil)
                  v       (judge this pending answer)]
              (when (:refused v) (on-refusal (:refused v)))
              (deliver out (select-keys v [:decision :approver])))
            (catch Throwable _ (deliver out {:decision :deny :approver "unverified"})))))
      out)))

(defn signed-asker
  "L1 adapter (clj): an Asker that approves only on a signature by a pinned
   approval key, verified in this process (see the namespace doc).
   opts:
     :solicit     (fn [{:request :nonce :request-sha256 :expires-ms}]) ->
                  promise of {:statement :signature} or nil; required
     :pins        pinned keys (hive-cloak.api/approval-pins); default: read
                  from the seal config
     :ttl-ms      how long a statement may live (default 120000)
     :on-refusal  (fn [reason-kw]) told why an answer was refused
     :now-ms      clock
   Throws :invalid-approval when hive-cloak is absent or no pins resolve."
  [{:keys [solicit pins ttl-ms on-refusal now-ms]
    :or   {ttl-ms 120000 on-refusal (fn [_]) now-ms approval/now-ms}}]
  (let [verify (cloak 'verify-approval)
        hash   (cloak 'approval-request-sha256)
        nonce  (cloak 'approval-new-nonce)
        pins   (or pins (some-> (cloak 'approval-pins) (#(%)) ok))]
    (when-not (and verify hash nonce)
      (throw (ex-info "the signed asker needs hive-cloak on the classpath"
                      {:autonomous/error :invalid-approval})))
    (when-not (and (map? pins) (seq pins))
      (throw (ex-info "the signed asker needs pinned approval keys"
                      {:autonomous/error :invalid-approval})))
    (when-not (ifn? solicit)
      (throw (ex-info "the signed asker needs a :solicit transport"
                      {:autonomous/error :invalid-approval})))
    (->SignedAsker solicit verify hash nonce pins ttl-ms (atom {}) now-ms on-refusal)))

(defn- signature-in
  "L1: the armored signature block in text `s`, or nil."
  [s]
  (when (string? s)
    (re-find #"(?s)-----BEGIN PGP SIGNATURE-----.*?-----END PGP SIGNATURE-----\r?\n?" s)))

(defn hive-solicit
  "L1 adapter (clj): a :solicit over the hive ask (hive-mcp.hivemind.messaging/
   ask! or any fn of the same shape, `:ask!`). The ask carries the exact
   statement to sign; the answer must be the armored detached signature of
   that statement (gpg --armor --detach-sign with the approval key). A
   plain \"yes\", or any answer without a signature block, answers nil, so
   the asker denies. `:agent-id` names the asker in the hive."
  [{:keys [ask! agent-id timeout-ms] :or {timeout-ms 120000}}]
  (let [statement (cloak 'approval-statement)
        ask!      (or ask! (try (requiring-resolve 'hive-mcp.hivemind.messaging/ask!) (catch Throwable _ nil)))]
    (when-not (and statement ask!)
      (throw (ex-info "hive-solicit needs hive-cloak and a hive ask on the classpath"
                      {:autonomous/error :invalid-approval})))
    (fn [{:keys [request nonce request-sha256 expires-ms]}]
      (future
        (let [text (:ok (statement {:nonce nonce :request-sha256 request-sha256
                                    :decision :approve :expires-ms expires-ms}))
              res  (ask! (str agent-id) "Sign this approval statement to approve; anything else denies."
                         nil
                         :timeout-ms timeout-ms :expects :text
                         :context (str (str/join "\n" (approval/prompt-lines nonce request))
                                       "\n\nStatement (sign exactly these bytes):\n" text))
              sig  (signature-in (:answer res))]
          (when (and text sig)
            {:statement text :signature sig}))))))

(defn cloak-solicit
  "L1 adapter (clj): a :solicit that asks the operator through hive-cloak's
   own signer (hive-cloak.api/sign-approval!) in this process: the decision
   is the operator's, by signing or not. `summary-of` renders the one line
   shown above the statement (default: tool and actor). Never answers for
   the operator: no signature, no answer."
  ([] (cloak-solicit {}))
  ([{:keys [summary-of timeout-ms] :or {timeout-ms 120000}}]
   (let [sign! (cloak 'sign-approval!)]
     (when-not sign!
       (throw (ex-info "cloak-solicit needs hive-cloak on the classpath" {:autonomous/error :invalid-approval})))
     (fn [{:keys [request nonce request-sha256 expires-ms]}]
       (future
         (let [line (if summary-of
                      (summary-of request)
                      (str/join "\n" (approval/prompt-lines nonce request)))
               res  (sign! {:nonce nonce :request-sha256 request-sha256 :decision :approve :expires-ms expires-ms}
                           {:summary line :timeout-ms timeout-ms})]
           (:ok res)))))))
