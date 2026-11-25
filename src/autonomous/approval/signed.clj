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
   pinned key approves. No answer within the statement's ttl is a :timeout.

   Run the gate in the APP's own process, never inside hive-mcp: any code
   an agent can evaluate in the gate's JVM can bypass it. Nothing here
   resolves hive-mcp; the hive ask is injected (`hive-solicit` :ask!), the
   pins are a required argument, and the hive-cloak functions are captured
   as values when the asker is built. The gate runs as a uid that no agent
   has (hive-cloak doc/signed-approval.md), and the operator reads the
   request on the gate's console (`console!`), never in the hive ask."
  (:require [autonomous.approval :as approval]
            [clojure.string :as str]))

(defn- cloak
  "L1 host: the VALUE of hive-cloak.api's `sym` (the function itself,
   dereferenced once, never the Var), or nil when hive-cloak is absent. An
   asker built from it keeps that function: rebinding the Var later
   (alter-var-root, a reload) cannot change what the asker verifies with."
  [sym]
  (try (some-> (requiring-resolve (symbol "hive-cloak.api" (name sym))) deref)
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
                  answer  (deref (solicit (assoc pending :request request)) ttl-ms ::timeout)]
              (if (= ::timeout answer)
                (do (on-refusal :timeout)
                    (deliver out ::approval/expired))
                (let [v (judge this pending answer)]
                  (when (:refused v) (on-refusal (:refused v)))
                  (deliver out (select-keys v [:decision :approver])))))
            (catch Throwable _ (deliver out {:decision :deny :approver "unverified"})))))
      out)))

(defn signed-asker
  "L1 adapter (clj): an Asker that approves only on a signature by a pinned
   approval key, verified in this process (see the namespace doc).
   opts:
     :solicit     (fn [{:request :nonce :request-sha256 :expires-ms}]) ->
                  promise of {:statement :signature} or nil; required
     :pins        pinned keys, required, with no config default: load them
                  with hive-cloak.api/approval-pins from the owner- and
                  mode-checked pins file and pass them in
     :ttl-ms      how long a statement may live (default 120000)
     :on-refusal  (fn [reason-kw]) told why an answer was refused
     :now-ms      clock
   Throws :invalid-approval when hive-cloak is absent, :pins is missing or
   empty, or there is no :solicit."
  [{:keys [solicit pins ttl-ms on-refusal now-ms]
    :or   {ttl-ms 120000 on-refusal (fn [_]) now-ms approval/now-ms}}]
  (let [verify (cloak 'verify-approval)
        hash   (cloak 'approval-request-sha256)
        nonce  (cloak 'approval-new-nonce)]
    (when-not (and verify hash nonce)
      (throw (ex-info "the signed asker needs hive-cloak on the classpath"
                      {:autonomous/error :invalid-approval})))
    (when-not (and (map? pins) (seq pins))
      (throw (ex-info "the signed asker needs :pins (hive-cloak.api/approval-pins)"
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

(defn console!
  "L1 Boundary: print on the gate's own console `out` the request it is
   waiting on, rendered per line by `lines` (hive-cloak's
   approval-request-lines, captured by the caller when it was built) from
   the request the gate itself holds. This is the one rendering the
   operator can trust: the hash prefix shown here and by the signer or the
   hive ask only ties the prompts for one request together, and anyone who
   knows the hash can print it beside a different request. Throws
   :invalid-approval, printing nothing, when `lines` is not a function or
   renders no line, so the caller never asks without the console."
  [out lines nonce request request-sha256]
  (let [rows (when (ifn? lines) (seq (lines request request-sha256)))]
    (when-not rows
      (throw (ex-info "the gate console cannot render the request, so it is not asked"
                      {:autonomous/error :invalid-approval})))
    (locking out
      (binding [*out* out]
        (println (str "[" (subs (str nonce) 0 (min 8 (count (str nonce)))) "] waiting for a signed approval;"
                      " this console is the request; the prefix only matches it to the other prompt"))
        (run! println rows)
        (flush)))))

(defn hive-solicit-with
  "L1 adapter (clj): `hive-solicit` over the hive-cloak functions it is
   handed as values, {:statement approval-statement :lines
   approval-request-lines}; `hive-solicit` passes the ones it resolved. The
   same `lines` renders the gate console and the ask, so the console never
   re-resolves anything. Each request first prints on `:console` (see
   `console!`); when that cannot render the request, the solicit's promise
   throws and the ask is never sent, so the asker denies."
  [{:keys [statement lines]} {:keys [ask! agent-id timeout-ms console] :or {timeout-ms 120000 console *err*}}]
  (when-not (and (ifn? statement) (ifn? lines))
    (throw (ex-info "hive-solicit needs hive-cloak on the classpath"
                    {:autonomous/error :invalid-approval})))
  (when-not (ifn? ask!)
    (throw (ex-info "hive-solicit needs an injected :ask! (it never resolves hive-mcp in process)"
                    {:autonomous/error :invalid-approval})))
  (fn [{:keys [request nonce request-sha256 expires-ms]}]
    (future
      (console! console lines nonce request request-sha256)
      (let [text (:ok (statement {:nonce nonce :request-sha256 request-sha256
                                  :decision :approve :expires-ms expires-ms}))]
        (when text
          (let [res (ask! (str agent-id) "Sign this approval statement to approve; anything else denies."
                          nil
                          :timeout-ms timeout-ms :expects :text
                          :context (str (str/join "\n" (lines request request-sha256))
                                        "\n\nRead the request on the gate's console, not here: this text"
                                        " travels through the hive. The prefix only matches the two."
                                        "\n\nStatement (sign exactly these bytes):\n" text))
                sig (signature-in (:answer res))]
            (when sig
              {:statement text :signature sig})))))))

(defn hive-solicit
  "L1 adapter (clj): a :solicit over a hive ask. `:ask!` is REQUIRED and
   injected by the app (a client of the hive's ask, with
   hive-mcp.hivemind.messaging/ask!'s shape); nothing is resolved in this
   process. Before it asks, the request is printed on the gate's own
   `:console` (default *err*, see `console!`): anything in the hive can
   rewrite the ask's text, so the operator reads the request there, and
   when the console cannot render it nothing is asked. The ask carries the
   request (escaped per line), the hash prefix and the exact statement to
   sign; the answer must be the armored detached signature of that
   statement. A plain \"yes\", or any answer without a signature block,
   answers nil, so the asker denies. `:agent-id` names the asker in the
   hive. The hive-cloak functions are resolved once, here."
  [opts]
  (hive-solicit-with {:statement (cloak 'approval-statement) :lines (cloak 'approval-request-lines)} opts))

(defn cloak-solicit
  "L1 adapter (clj): a :solicit that asks the operator through hive-cloak's
   own signer (hive-cloak.api/sign-approval!) in THIS process. The signer
   gets the request itself, hashes it, and shows it (escaped per line) with
   the hash prefix before asking for the passphrase. The gate first prints
   the request on its own `:console` (see `console!`), which is where the
   operator reads it; the prefix only ties the two prompts together. When
   the console cannot render the request, the signer is never asked. No
   signature, no answer."
  ([] (cloak-solicit {}))
  ([{:keys [timeout-ms console] :or {timeout-ms 120000 console *err*}}]
   (let [sign! (cloak 'sign-approval!)
         lines (cloak 'approval-request-lines)]
     (when-not (and sign! lines)
       (throw (ex-info "cloak-solicit needs hive-cloak on the classpath" {:autonomous/error :invalid-approval})))
     (fn [{:keys [request nonce request-sha256 expires-ms]}]
       (future
         (console! console lines nonce request request-sha256)
         (let [res (sign! {:request request :nonce nonce :decision :approve
                           :expires-ms expires-ms :request-sha256 request-sha256}
                          {:timeout-ms timeout-ms})]
           (select-keys (:ok res) [:statement :signature])))))))
