(ns autonomous.domain.fleet
  "The Fleet: one bridge serving many targets (UI builds, microservices),
   each hosting its own autonomous registry. Pure: names across apps,
   routing an exposed name or uri back to its target, merging per-target
   probes into the one surface an agent sees, and its fingerprint.

   A target's PREFIX is its id made MCP-safe with every `_` and `.` turned
   into `-`, at most 24 characters. An app tool is exposed as
   <prefix>__<tool>. A prefix never holds `_`, so the first `__` of an
   exposed name ends the prefix and routing needs no manifest. A name that
   is not a valid MCP name (too long, or a character outside
   [A-Za-z0-9_-]) is made safe and ends in `-` and a 12 character digest of
   the original; `resolve-local` finds the tool such a name stands for.
   Tools of one target that expose the same name are all dropped.

   An app resource uri is exposed as autonomous://<prefix>/<uri>."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.naming :as naming]
            [clojure.string :as str]))

(def separator "__")

(def max-prefix-length 24)

(def resource-scheme "autonomous://")

;; =============================================================================
;; Names
;; =============================================================================

(defn prefix
  "Tool-name prefix of `target-id`: MCP-safe, without `_` or `.`, 1..24
   characters."
  [target-id]
  (let [p (apply str (map #(if (#{\_ \.} %) \- %)
                          (take max-prefix-length (naming/tool-name (str target-id)))))]
    (if (= "" p) "-" p)))

(def ^:private base36 "0123456789abcdefghijklmnopqrstuvwxyz")

(defn- char-code [c]
  #?(:cljs (.charCodeAt c 0) :default (int c)))

(defn- poly-hash
  "Polynomial hash of `s` with multiplier `m` modulo prime `p` (< 2^31), so
   every step stays exact in a JS double."
  [m p s]
  (reduce (fn [h c] (mod (+ (* h m) (char-code c)) p)) 7 (seq (str s))))

(defn- base36-of
  "`n` as exactly `width` base-36 characters."
  [n width]
  (loop [n n acc ()]
    (if (= width (count acc))
      (apply str acc)
      (recur (quot n 36) (cons (nth base36 (mod n 36)) acc)))))

(def digest-length 12)

(defn digest
  "Twelve base-36 characters (two independent 31-bit hashes) that depend on
   every character of `s`. The same on the JVM, babashka and JavaScript."
  [s]
  (str (base36-of (poly-hash 31 2147483647 s) 6)
       (base36-of (poly-hash 131 2147483629 s) 6)))

(defn exposed-name
  "MCP name of app tool `tool` of the target with prefix `pfx`, a function
   of `pfx` and `tool` alone. <pfx>__<tool> when that is a valid MCP name;
   otherwise <pfx>__<tool> with every character outside [A-Za-z0-9_-] made
   `_`, cut so that `-` and the 12 character digest of the unchanged
   <pfx>__<tool> fit in 64 characters. Always a valid MCP tool name."
  [pfx tool]
  (let [full (str pfx separator tool)]
    (if (naming/valid-tool-name? full)
      full
      (let [base (str pfx separator (naming/sanitize tool))
            room (- naming/max-length 1 digest-length)]
        (str (subs base 0 (min (count base) room)) "-" (digest full))))))

(defn parse-name
  "{:target id :local tool} for exposed name `exposed` when its prefix names
   a target in `prefixes` ({prefix target-id}), else nil. For a cut name
   :local is not the tool's name: see `cut?` and `resolve-local`."
  [prefixes exposed]
  (when (string? exposed)
    (when-let [i (str/index-of exposed separator)]
      (when-let [tid (get prefixes (subs exposed 0 i))]
        {:target tid :local (subs exposed (+ i (count separator)))}))))

(defn cut?
  "True when exposed name `exposed` may stand for a tool listed under
   another name (it ends in `-` and a digest)."
  [exposed]
  (boolean (and (string? exposed)
                (re-find #"-[0-9a-z]{12}$" exposed))))

(defn resolve-local
  "The one name among app `tool-names` that prefix `pfx` exposes as
   `exposed`, or nil when none does or several do (a collision exposes
   neither)."
  [pfx exposed tool-names]
  (let [ms (filter #(= exposed (exposed-name pfx %)) (distinct tool-names))]
    (when (= 1 (count ms)) (first ms))))

(defn exposed-uri
  "Uri app resource `uri` of the target with prefix `pfx` is listed under."
  [pfx uri]
  (str resource-scheme pfx "/" uri))

(defn parse-uri
  "{:target id :local uri} for exposed resource uri `exposed`, else nil."
  [prefixes exposed]
  (when (and (string? exposed) (str/starts-with? exposed resource-scheme))
    (let [more (subs exposed (count resource-scheme))]
      (when-let [i (str/index-of more "/")]
        (when-let [tid (get prefixes (subs more 0 i))]
          {:target tid :local (subs more (inc i))})))))

(defn prefixes
  "{prefix target-id} of `target-ids`."
  [target-ids]
  (into {} (map (juxt prefix identity)) target-ids))

(defn check-targets
  "Problems (strings) of a fleet of `target-ids`, nil when none: every id
   valid, unique, and owning its prefix."
  [target-ids]
  (let [bad   (remove naming/valid-target-id? target-ids)
        dup   (keep (fn [[id n]] (when (< 1 n) id)) (frequencies target-ids))
        clash (keep (fn [[p ids]] (when (< 1 (count (distinct ids))) [p (vec (distinct ids))]))
                    (group-by prefix target-ids))]
    (not-empty
     (cond-> []
       (seq bad)   (conj (str "a target id is 1..48 of [A-Za-z0-9._-]: " (pr-str (vec bad))))
       (seq dup)   (conj (str "target ids must be unique (name each with --target-id): " (pr-str (vec dup))))
       (seq clash) (conj (str "target ids share a tool prefix: " (pr-str (into (sorted-map) clash))))))))

;; =============================================================================
;; Pending results
;; =============================================================================

(defn qualify-pending
  "Answer `a` with its Pending id qualified by `target-id`, so a poll finds
   the target from the id alone. Other answers unchanged."
  [target-id a]
  (if (answer/pending? a) (answer/pending (str target-id ":" (:pending a))) a))

(defn split-pending
  "[target-id local-id] of a qualified pending `id`, or nil."
  [id]
  (when-let [i (and (string? id) (str/index-of id ":"))]
    [(subs id 0 i) (subs id (inc i))]))

;; =============================================================================
;; Surface
;; =============================================================================

(def statuses
  "What a probe of a target found: :up answered a manifest, :down refused
   (unreachable, not autonomous), :slow did not answer within the probe
   deadline."
  #{:up :down :slow})

(defn- expose-tool [tid pfx t]
  (assoc t
         :name        (exposed-name pfx (:name t))
         :title       (str "[" tid "] " (or (:title t) (:name t)))
         :description (str "[" tid "] " (:description t))))

(defn- expose-resource [tid pfx r]
  (assoc r :uri (exposed-uri pfx (:uri r)) :name (str tid "/" (:name r))))

(defn- app-row [target status manifest error kept dropped]
  (cond-> {:app target :prefix (prefix target) :status status}
    (= :up status) (assoc :tools (count kept) :version (:version manifest))
    (get-in manifest [:app :description]) (assoc :description (get-in manifest [:app :description]))
    (seq dropped)  (assoc :dropped (mapv :name dropped))
    error          (assoc :error error)))

(defn- distinct-by-name
  "`tools` with only the first tool of each listed :name."
  [tools]
  (second (reduce (fn [[seen acc] t]
                    (if (contains? seen (:name t)) [seen acc] [(conj seen (:name t)) (conj acc t)]))
                  [#{} []] tools)))

(defn- split-taken
  "[kept dropped] of `tools` under prefix `pfx`, independent of their
   order: a tool is dropped when its exposed name is in `routes` (an
   earlier target holds it) or when another tool of `tools` exposes the
   same name, in which case every one of them is dropped."
  [routes pfx tools]
  (let [name-of #(exposed-name pfx (:name %))
        counts  (frequencies (map name-of (distinct-by-name tools)))
        taken?  (fn [t] (let [n (name-of t)]
                          (or (contains? routes n) (< 1 (get counts n 0)))))]
    [(vec (remove taken? (distinct-by-name tools)))
     (vec (filter taken? (distinct-by-name tools)))]))

(defn merge-probes
  "The fleet surface of `probes`, a vector of {:target :status :manifest
   :error} in fleet order: {:tools :resources :routes :apps}. Only :up
   targets contribute tools and resources. :routes maps each exposed tool
   name to {:target :tool}. A name an earlier target or an earlier tool of
   the same target already exposes is not listed again; the app reports
   the later tool under :dropped."
  [probes]
  (reduce
   (fn [acc {:keys [target status manifest error]}]
     (let [pfx            (prefix target)
           up?            (= :up status)
           [kept dropped] (split-taken (:routes acc) pfx (when up? (:tools manifest)))]
       (-> acc
           (update :tools into (map #(expose-tool target pfx %)) kept)
           (update :resources into (map #(expose-resource target pfx %)) (when up? (:resources manifest)))
           (update :routes into (map (fn [t] [(exposed-name pfx (:name t)) {:target target :tool (:name t)}])) kept)
           (update :apps conj (app-row target status manifest error kept dropped)))))
   {:tools [] :resources [] :routes {} :apps []}
   probes))

(defn fingerprint
  "What changes when the fleet surface changes: per target its status and,
   when up, its manifest version and tool names."
  [probes]
  (mapv (fn [{:keys [target status manifest]}]
          (if (= :up status)
            [target status (:version manifest) (mapv :name (:tools manifest))]
            [target status]))
        probes))

(defn app-info
  "The AppInfo a fleet introduces itself with: its apps and their state."
  [probes]
  {:name "fleet"
   :description (str "several apps behind one bridge, their tools named <app>__<tool>: "
                     (str/join ", " (map (fn [{:keys [target status manifest]}]
                                           (str target
                                                (when-let [d (get-in manifest [:app :description])] (str " (" d ")"))
                                                (when-not (= :up status) (str " [" (name status) "]"))))
                                         probes)))})

;; =============================================================================
;; Target arguments of built-in tools
;; =============================================================================

(defn resolve-target
  "Ok of the target id that built-in argument `target` names among
   `target-ids` (Ok nil when absent and not `required?`), or a refusal of
   type :target/required or :target/unknown listing the known ids."
  [target-ids target required?]
  (cond
    (and (nil? target) required?)
    (answer/refusal :target/required "this bridge serves several apps: pass target (autonomous_apps lists them)"
                    {:known (vec target-ids)})

    (nil? target) (answer/ok nil)

    (some #{(str target)} target-ids) (answer/ok (str target))

    :else (answer/refusal :target/unknown (str "no app " (pr-str target) " in this fleet")
                          {:known (vec target-ids)})))

;; =============================================================================
;; Modes (open set: one defmethod per naming scheme)
;; =============================================================================

(defn view
  "The routing view of a fleet of `target-ids` in `mode`: :single serves
   one target under its own names (the bridge as it always was), :fleet
   prefixes every app's names."
  [mode target-ids]
  {:fleet/mode mode :ids (vec target-ids) :prefixes (prefixes target-ids)})

(defn- mode-of [v & _] (:fleet/mode v))

(defmulti surface
  "{:app :tools :resources :apps} an agent sees, from `probes` in fleet order."
  mode-of)

(defmethod surface :single [_ [p]]
  (let [m (when (= :up (:status p)) (:manifest p))]
    {:app       (:app m)
     :tools     (vec (:tools m))
     :resources (vec (:resources m))
     :apps      (:apps (merge-probes [p]))}))

(defmethod surface :fleet [_ probes]
  (assoc (dissoc (merge-probes probes) :routes) :app (app-info probes)))

(defmulti route-tool
  "Ok {:target :local} of exposed tool name `exposed`, or an :unknown-tool
   refusal."
  mode-of)

(defmethod route-tool :single [v exposed]
  (answer/ok {:target (first (:ids v)) :local exposed}))

(defmethod route-tool :fleet [v exposed]
  (if-let [r (parse-name (:prefixes v) exposed)]
    (answer/ok r)
    (answer/refusal :unknown-tool (str "no tool named " exposed "; app tools are named <app>__<tool>")
                    {:apps (vec (sort (keys (:prefixes v))))})))

(defmulti route-uri
  "Ok {:target :local} of exposed resource uri `exposed`, or an
   :unknown-resource refusal."
  mode-of)

(defmethod route-uri :single [v exposed]
  (answer/ok {:target (first (:ids v)) :local exposed}))

(defmethod route-uri :fleet [v exposed]
  (if-let [r (parse-uri (:prefixes v) exposed)]
    (answer/ok r)
    (answer/refusal :unknown-resource (str "no resource " exposed "; app resources are "
                                           resource-scheme "<app>/<uri>")
                    {:apps (vec (sort (keys (:prefixes v))))})))

(defmulti route-pending
  "Ok {:target :local} of pending id `id` an agent polls, or a
   :pending/unknown refusal."
  mode-of)

(defmethod route-pending :single [v id]
  (answer/ok {:target (first (:ids v)) :local id}))

(defmethod route-pending :fleet [v id]
  (let [[tid local] (split-pending id)]
    (if (some #{tid} (:ids v))
      (answer/ok {:target tid :local local})
      (answer/refusal :pending/unknown (str "no pending result " (pr-str id) " in this fleet")))))

(defmulti qualify
  "Answer `a` of target `target-id` as the agent receives it."
  mode-of)

(defmethod qualify :single [_ _target-id a] a)

(defmethod qualify :fleet [_ target-id a] (qualify-pending target-id a))

(defmulti target-arg
  "Ok of the target a built-in call addresses given its `target` argument,
   or a refusal. :single always addresses its one target."
  mode-of)

(defmethod target-arg :single [v _target]
  (answer/ok (first (:ids v))))

(defmethod target-arg :fleet [v target]
  (resolve-target (:ids v) target (< 1 (count (:ids v)))))
