(ns autonomous.decorator
  "L1 endomorphisms: decorate a compiled Tool or Surface without changing
   its input or output schema. Compose configured decorators with `comp`."
  (:require [autonomous.approval :as approval]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.journal :as journal]
            [autonomous.domain.wire :as wire]
            [autonomous.thenable :as thenable]
            [malli.core :as m]
            [malli.error :as me]))

(defn require-scope
  "L1 Tool -> Tool: require one additional scope for listing and calling.
   `scope` must be a keyword (autonomous.domain.schema/Scope). Anything
   else throws ex-info :autonomous/error :invalid-scope at construction:
   a decorator that silently required nothing would leave the tool
   callable without the scope its author meant to demand."
  [scope]
  (when-not (keyword? scope)
    (throw (ex-info (str "require-scope needs a keyword scope, got " (pr-str scope))
                    {:autonomous/error :invalid-scope :scope scope})))
  (fn [tool] (update tool :scopes conj scope)))

(def approval-ttl-margin-ms
  "L1: how long before the pending TTL an approval wait must end."
  (* 60 1000))

(def max-approval-ms
  "L1: require-approval's :timeout-ms must stay below this: the runtime's
   pending TTL (autonomous.runtime.registry/pending-ttl-ms, 10 minutes)
   less approval-ttl-margin-ms, so an approved call's result is collectable."
  (- (* 10 60 1000) approval-ttl-margin-ms))

(defn require-approval
  "L1 Tool -> Tool: every call waits for a human's Verdict from `asker`
   (autonomous.approval/Asker) and is Pending meanwhile. Approve runs the
   tool once; deny refuses :approval-denied, no Verdict within
   `timeout-ms` refuses :approval-timeout, and neither runs it. Throws
   ex-info :autonomous/error :invalid-approval on a bad option."
  [{:keys [asker timeout-ms] :as opts}]
  (when-not (and (approval/asker? asker) (pos-int? timeout-ms) (< timeout-ms max-approval-ms))
    (throw (ex-info (str "require-approval needs an Asker :asker and a :timeout-ms in 1.."
                         (dec max-approval-ms))
                    {:autonomous/error :invalid-approval :opts (dissoc opts :asker)})))
  (fn [tool] (assoc tool ::approval {:asker asker :timeout-ms timeout-ms})))

(def ^:private refusals
  "L1: the refusal of each Decision that does not run the tool."
  {:deny    (answer/refusal :approval-denied "a human denied this call")
   :timeout (answer/refusal :approval-timeout "no human answered in time")
   :error   (answer/refusal :approval-error "the approval asker failed")})

(defn- as-answer
  "L1 Promote: a tool's value `v` as an Answer: a complete Answer as is,
   any other value as its Ok."
  [v]
  (if (answer/complete? v) v (answer/ok v)))

(defn- stamped
  "L1 Promote: Answer `a` marked complete, carrying Decision `d` as
   :autonomous/approval metadata."
  [d a]
  (vary-meta (answer/complete a) assoc :autonomous/approval d))

(defn- approved
  "L1 Pipeline: a thenable of the Answer of thunk `run` (the refusal of
   what it throws or fails with), stamped with Decision `d`."
  [run d]
  (thenable/attempt #(thenable/chain (run) (comp (partial stamped d) as-answer))
                    (comp (partial stamped d) answer/from-exception)))

(defn- decided
  "L1 Pipeline: run thunk `run` when Decision `d` approves; else its refusal."
  [run d]
  (if (= :approve (:decision d))
    (approved run d)
    (stamped d (refusals (:decision d) (:deny refusals)))))

(defn- approval-request
  "L1 Promote: what the asker is asked about one call: the tool name, the
   args in wire form with the journal's `redact` paths masked, the actor
   and the session, as data. Rendering them for a human (escaping,
   capping) is the asker's (autonomous.approval/prompt-lines)."
  [tool args grant redact]
  {:tool    (:name tool)
   :args    (journal/redact-paths (wire/->wire (or args {})) redact)
   :actor   (:actor grant)
   :session (:session grant)})

(defn- strip-approval
  "L1 Promote: `v` without :autonomous/approval metadata."
  [v]
  (if (contains? (meta v) :autonomous/approval) (vary-meta v dissoc :autonomous/approval) v))

(defn- unstamped
  "L1 Pipeline: an ungated tool's value `x`, now or once computed, with no
   :autonomous/approval metadata, so only the gate can journal an approval."
  [x]
  (if (thenable/thenable? x) (thenable/chain x strip-approval) (strip-approval x)))

(def ^:private ProviderAllowlist
  "L1: the options of provider-allowlist, after :allowed is made a set."
  [:map
   [:path [:vector {:min 1} [:or :keyword :string :int]]]
   [:providers [:vector {:min 1} [:map [:code [:string {:min 1}]]
                                  [:name {:optional true} [:string {:min 1}]]]]]
   [:allowed [:set {:min 1} [:string {:min 1}]]]])

(declare json-fold)

(defn- allowlist-problem
  "L1: what is wrong with allowlist options `al`, nil when nothing is: a
   shape problem; two providers with one code; two names that fold alike
   (the service's name lookup walks a Go map, so which one it answers is
   unspecified); a name that folds like another provider's code; or an
   :allowed code no provider has."
  [al]
  (if-let [e (m/explain ProviderAllowlist al)]
    (me/humanize e)
    (let [ps    (:providers al)
          codes (map :code ps)
          pairs (for [p ps q ps :when (and (:name p) (not= (:code p) (:code q)))] [p q])]
      (or (not-empty (for [[c n] (frequencies codes) :when (< 1 n)] {:duplicate-code c}))
          (not-empty (for [[p q] pairs :when (= (json-fold (:name p)) (json-fold (:code q)))]
                       {:name (:name p) :folds-like-code (:code q)}))
          (not-empty (for [[p q] pairs :when (and (:name q) (= (json-fold (:name p)) (json-fold (:name q))))]
                       {:names-fold-alike [(:name p) (:name q)]}))
          (not-empty (remove (set codes) (:allowed al)))))))

(defn provider-allowlist
  "L1 Tool -> Tool: the value at arg path `:path` (e.g. [:provider]) must
   name a provider whose code is in `:allowed`. `:providers` lists the
   providers the service resolves, [{:code str :name str}], so the value
   is read the way the service reads it: a code exactly, else a name
   ignoring ASCII case. Whitespace, another case of a code, a number, a
   non-ASCII character or no value at all names no provider.

   A call naming an allowed provider runs at once, past any
   require-approval gate. Any other call goes to the tool's
   require-approval gate when it has one, and is refused
   :provider-not-allowed when it has none. Scopes are checked before
   both, so the order is scope, then allowlist or approval, whatever the
   order of the decorators. Throws ex-info :autonomous/error
   :invalid-allowlist on a bad option or an :allowed code with no
   provider, and when applied to a tool that already carries a
   different allowlist: a later decoration never widens (or silently
   replaces) the first."
  [{:keys [path providers allowed] :as opts}]
  (let [colls?  (and (sequential? path) (sequential? providers) (coll? allowed) (not (map? allowed)))
        al      (when colls? {:path (vec path) :providers (vec providers) :allowed (set allowed)})
        problem (if colls? (allowlist-problem al) "path, providers and allowed must be collections")]
    (when problem
      (throw (ex-info (str "provider-allowlist: invalid options: " (pr-str problem))
                      {:autonomous/error :invalid-allowlist :problem problem :opts opts})))
    (fn [tool]
      (when-let [held (::allowlist tool)]
        (when (not= held al)
          (throw (ex-info (str "provider-allowlist: tool " (:name tool) " already has a provider "
                               "allowlist; a second one would replace it")
                          {:autonomous/error :invalid-allowlist :tool (:name tool)
                           :held (:allowed held) :offered (:allowed al)}))))
      (assoc tool ::allowlist al))))

(def ^:private ascii-lower
  "L1: each ASCII capital letter to its small letter, nothing else."
  (zipmap "ABCDEFGHIJKLMNOPQRSTUVWXYZ" "abcdefghijklmnopqrstuvwxyz"))

(defn- fold-ascii
  "L1: string `s` with its ASCII capitals made small, whatever the locale."
  [s]
  (apply str (map #(get ascii-lower % %) s)))

(defn resolve-provider
  "L1: the :code of the provider of `providers` that value `v` names: the
   one whose code equals `v`, else the one whose name equals it ignoring
   ASCII case; nil when none does or `v` is not a string of printable
   ASCII. Narrower than a Unicode case fold on purpose: a value read
   differently here and at the service can only fail to be allowed."
  [providers v]
  (when (and (string? v) (re-matches #"[\x20-\x7E]+" v))
    (or (some #(when (= v (:code %)) (:code %)) providers)
        (let [folded (fold-ascii v)]
          (some #(when (and (:name %) (= folded (fold-ascii (:name %)))) (:code %)) providers)))))

(defn- allowlisted?
  "L1: does `args` name an allowed provider at the allowlist's path?"
  [{:keys [path providers allowed]} args]
  (contains? allowed (resolve-provider providers (get-in args path))))

(defn- fold-code-point
  "L1 host: code point `cp` as Go's encoding/json folds it to match a key
   to a field: the upper case of its lower case (simple mappings on the
   JVM; on cljs the first code point of the full mappings, which folds at
   least as much)."
  [cp]
  #?(:cljs    (.codePointAt (.toUpperCase (.toLowerCase (js/String.fromCodePoint cp))) 0)
     :default (Character/toUpperCase (Character/toLowerCase (int cp)))))

(defn json-fold
  "L1: string `s` folded code point by code point (fold-code-point), as a
   vector: two keys a case-insensitive JSON decoder matches to one field
   fold equal. \"provider\" and \"PROVIDER\" fold equal, as K (Kelvin
   sign) folds like K and ſ (long s) like S."
  [s]
  (loop [i 0 acc []]
    (if (< i (count s))
      (let [cp #?(:cljs (.codePointAt s i) :default (Character/codePointAt ^CharSequence s (int i)))]
        (recur (+ i (if (> cp 0xFFFF) 2 1)) (conj acc (fold-code-point cp))))
      acc)))

(defn- key-text
  "L1: map key `k` as the JSON key it is sent as; nil for other keys."
  [k]
  (cond (keyword? k) (subs (str k) 1)
        (string? k)  k))

(defn- shadowed-key?
  "L1: does map `m` hold a key other than `k` that folds like it?"
  [m k]
  (when-let [t (and (map? m) (key-text k))]
    (let [f (json-fold t)]
      (boolean (some #(and (not= % k) (= f (some-> (key-text %) json-fold))) (keys m))))))

(defn ambiguous-path?
  "L1: does some map along `path` in `args` hold, beside the path's own
   key, another key (keyword or string) a case-insensitive JSON decoder
   reads as that key? Then the service may read a value the allowlist did
   not, so the call names no provider safely."
  [path args]
  (loop [m args [k & more :as ks] path]
    (cond (empty? ks)           false
          (shadowed-key? m k)   true
          :else                 (recur (get m k) more))))

(defn- ambiguous
  "L1: the complete refusal of a call whose provider path is ambiguous."
  [{:keys [path]}]
  (answer/complete
   (answer/refusal :provider-ambiguous
                   (str "the args carry more than one key a JSON decoder reads as "
                        (pr-str path) "; send the provider once, under that exact key")
                   {:path path})))

(defn- not-allowed
  "L1: the complete refusal of a call whose provider allowlist `al` does
   not allow, naming the path and what it accepts."
  [{:keys [path providers allowed]}]
  (answer/complete
   (answer/refusal :provider-not-allowed
                   (str "the provider at " (pr-str path) " is not one this tool may use")
                   {:path path
                    :allowed (vec (sort (mapcat (juxt :code :name)
                                                (filter (comp allowed :code) providers))))})))

(defn gate
  "L1 Pipeline: (run) for a tool without require-approval; else a thenable
   of the complete Answer the human's Decision leads to (see
   require-approval), carrying that Decision as :autonomous/approval. The
   human sees the args with CallDetail `redact` paths masked. A tool with
   a provider-allowlist runs (run) at once for an allowed provider and
   treats any other call as gated, refused :provider-not-allowed when it
   has no approval gate. Args with two keys along the provider path that
   fold alike (ambiguous-path?) are refused :provider-ambiguous, approval
   gate or not: the human would see one provider and the service read the
   other."
  [tool args grant redact run]
  (let [al       (::allowlist tool)
        approval (::approval tool)]
    (cond
      (and al (ambiguous-path? (:path al) args)) (ambiguous al)
      (and al (allowlisted? al args)) (unstamped (run))
      approval (let [{:keys [asker timeout-ms]} approval]
                 (thenable/chain (approval/ask! asker (approval-request tool args grant redact) timeout-ms)
                                 (partial decided run)))
      al       (not-allowed al)
      :else    (unstamped (run)))))

(defn each-tool
  "L1 lift: Tool -> Tool fn `f` as a Surface -> Surface fn applying it to
   every tool under the tool's existing key. Renaming is a Surface ->
   Surface concern (see `prefix-names`)."
  [f]
  (fn [surface]
    (update surface :tools #(into {} (map (fn [[k tool]] [k (f tool)])) %))))

(defn- prefixed-tool
  "L1 Promote: rekey a tool and its advertised name together."
  [prefix [name tool]]
  (let [new-name (str prefix name)]
    [new-name (assoc tool :name new-name)]))

(defn- prefix-tools
  "L1 Promote: rename all tools. Never truncate or overwrite; a name the
   prefix makes invalid stays in the result for validation to refuse."
  [prefix tools]
  (into {} (map (partial prefixed-tool prefix)) tools))

(defn prefix-names
  "L1 Surface -> Surface: prepend an exact string (e.g. \"fleet_\") to all
   tool names. A name the prefix makes invalid (MCP's ToolName) is kept
   as is, so autonomous.surface/decorate refuses the whole result and a
   registration that would produce it throws, changing nothing. It never
   falls back to the unprefixed names: decorators re-run on every
   registration, and one bad name must not rename the whole surface back.
   Resources retain their URIs and display names."
  [prefix]
  (fn [surface] (update surface :tools (partial prefix-tools prefix))))

(defn- redaction
  "L1: one redaction as data, {:paths :replacement}. The closure that
   applies it is built where it runs (`apply-redactions`), so two equal
   decorations leave equal tools and merge without a clash."
  [paths replacement]
  {:paths (vec paths) :replacement replacement})

(defn- apply-redactions
  "L1: wire-form `value` with every redaction of `redactions` applied in order."
  [value redactions]
  (reduce (fn [v {:keys [paths replacement]}] (journal/redact-paths v paths replacement))
          value redactions))

(defn redact-output
  "L1 Tool -> Tool: mask output paths (vectors of keys/indices) the way
   the journal masks args (autonomous.domain.journal/redact-paths): a
   missing key or index adds nothing, and a path that stops at a string,
   a set or a number masks that whole value. The default replacement is
   the string \"<redacted>\". Choose a different replacement if that
   would violate the tool's declared output schema.

   Masking is deliberately broad, so a field whose schema is a union
   needs a replacement valid for every branch it can hold: with
   [:balance [:or :int [:map [:cents :int]]]] and the path
   [:balance :cents], an output {:balance 0} has its number masked
   whole, and a string replacement then fails :int, refusing the call
   :output/invalid-redaction. Pick a replacement every branch accepts,
   or declare the field in terms that admit it.

   Paths address the output in WIRE form (autonomous.domain.wire/->wire):
   a lazy seq or list is a vector there, so [:accounts 0 :token] reaches
   into {:accounts (map ...)}. The declared :output schema is then checked
   against that wire form, not the raw value: a schema that needs map keys
   other than keywords or strings, or values the wire carries as strings
   (a LocalDate, a URI), fails the check even where no path matched, and
   the call is refused :output/invalid-redaction. Declare such an output
   in wire terms.

   They apply to Ok outputs only, never to a refusal's :data: that is a
   different value (a throw's ex-data, a codec's diagnosis) with its own
   shape, where output paths would mask unrelated keys or miss the ones
   that matter. Mask refusal data with `redact-refusal`.

   The redaction is stored on the tool as data, so applying one decorator
   to two copies of a surface leaves tools that compare equal."
  ([paths] (redact-output paths journal/redacted))
  ([paths replacement]
   (fn [tool]
     (update tool ::output-redactions (fnil conj []) (redaction paths replacement)))))

(defn project-output
  "L1 Pipeline: apply redactions to the wire form of `raw`, so a path
   through a seq or list reaches what the bridge would carry. Refuse
   (:output/invalid-redaction) rather than returning a masked output
   whose wire form does not match the declared :output schema; see
   `redact-output` for why the check sees the wire form. Without
   redactions `raw` passes unchanged."
  [tool raw]
  (if-let [redactions (seq (::output-redactions tool))]
    (let [value (apply-redactions (wire/->wire raw) redactions)]
      (if-let [e (some-> (:output tool) (m/explain value))]
        (answer/refusal :output/invalid-redaction
                        "the output, in wire form after redaction, does not match the declared output schema"
                        {:problems (me/humanize e)})
        (answer/ok value)))
    (answer/ok raw)))

(defn redact-refusal
  "L1 Tool -> Tool, opt in: mask paths (vectors of keys/indices) in the
   :data of every refusal the tool's own invocation answers: what its
   handler or codec refuses with, what it throws (the ex-data minus
   :autonomous/error, autonomous.domain.answer/from-exception), and how an
   async result fails. The path [] replaces the whole :data, and a path
   that stops at a string, a set or a number masks that whole value, as
   in the journal. The default replacement is \"<redacted>\".

   The data is already in wire form when the paths apply. Refusals the
   runtime itself makes before the tool runs (scopes, arguments, leases)
   are not touched. The refusal's :message is never masked: a tool whose
   exceptions can name a secret in their message must catch them."
  ([paths] (redact-refusal paths journal/redacted))
  ([paths replacement]
   (fn [tool]
     (update tool ::refusal-redactions (fnil conj []) (redaction paths replacement)))))

(defn project-refusal
  "L1 Pipeline: Answer `a` with the tool's refusal redactions applied to
   the wire form (autonomous.domain.wire/->wire) of a refusal's :data, so
   a path through a list or seq reaches what the bridge would carry even
   when an adapter built the refusal map itself. Ok, Pending, a refusal
   without :data and a tool without refusal redactions pass unchanged."
  [tool a]
  (let [redactions (seq (::refusal-redactions tool))]
    (if (and redactions (answer/refused? a) (contains? (:error a) :data))
      (update-in a [:error :data] #(apply-redactions (wire/->wire %) redactions))
      a)))
