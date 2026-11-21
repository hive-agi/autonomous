(ns autonomous.surface
  "L1 Surface: what an app lets agents do and see, as a PURE VALUE
   {:app :tools :resources :interceptors}. No runtime state lives here
   (journal, leases, pending results belong to a Runtime instance,
   autonomous.runtime.registry). Derivation builds a surface, decorators
   transform one, `merge-surfaces` composes them; a name two different
   affordances would share is an ERROR VALUE, never last-writer-wins.

   Pure: every function answers the value or {:ok v} / a refusal
   (autonomous.domain.answer), never throws."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.schema :as s]
            [malli.core :as m]
            [malli.error :as me]
            [autonomous.domain.affordance :as affordance]
            [malli.registry :as mr])
  #?(:cljs (:require-macros [autonomous.domain.answer :as answer])))

(def Interceptor
  "L1 call interceptor. :enter and :leave take context; :error takes
   [context refusal]. Each returns context. Entry can short-circuit by
   setting :answer; later stages can transform or recover that Answer."
  [:map [:name :keyword]
   [:enter {:optional true} fn?]
   [:leave {:optional true} fn?]
   [:error {:optional true} fn?]])

(def Surface
  "The L1 value: compiled affordances (autonomous.domain.affordance) plus
   the call chain. Tools are keyed by MCP name, resources by URI."
  [:map
   [:app s/AppInfo]
   [:tools [:map-of s/ToolName s/Tool]]
   [:resources [:map-of :string s/Resource]]
   [:interceptors [:vector Interceptor]]])

(def empty-surface
  "The surface of an app that exposes nothing: what unmounting leaves."
  {:app {:name "app"} :tools {} :resources {} :interceptors []})

(defn surface?
  "True when `x` is a valid Surface value."
  [x]
  (nil? (m/explain Surface x)))

(defn ->surface
  "L1 Promote: fill omitted parts and validate a Surface map."
  [value]
  (if (or (nil? value) (map? value))
    (let [s (merge empty-surface value)]
      (if-let [e (m/explain Surface s)]
        (answer/refusal :invalid-surface "not a valid surface" (me/humanize e))
        (answer/ok s)))
    (answer/refusal :invalid-surface "a surface must be a map")))

(defn- add-compiled-tool
  "L1 Promote: add a compiled tool; duplicate declarations are errors."
  [tools {:keys [id name] :as tool}]
  (if-let [other (get tools name)]
    (answer/refusal :name-clash (str "tool name " name " is derived twice")
                    {:name name :ids [(:id other) id]})
    (answer/ok (assoc tools name tool))))

(defn compile-tools
  "L1 Promote: compile [id ToolSpec] pairs into a uniquely keyed tool map."
  [specs]
  (answer/reduce-answer
   (fn [tools [id spec]]
     (answer/bind (affordance/->tool id spec) #(add-compiled-tool tools %)))
   {} specs))

;; =============================================================================
;; Composition: a monoid on Surface. merge-part is OPEN (OCP): a new part
;; of the surface is a new defmethod; merge-surfaces never changes.
;; =============================================================================

(defn- differing-keys
  "L1: the keys whose values differ between maps `a` and `b` (a key one
   lacks counts), sorted; nil unless both are maps."
  [a b]
  (when (and (map? a) (map? b))
    (->> (distinct (concat (keys a) (keys b)))
         (remove #(= (get a % ::absent) (get b % ::absent)))
         (sort-by str)
         vec)))

(defn- clash
  "L1: the refusal of one name two different values claim. :differs names
   the keys of the compared identities that differ, when they are maps:
   the labels alone (a tool's :id) are often equal on both sides."
  [what n left right differs]
  (answer/refusal :name-clash
                  (str what " " n " is declared by two different values")
                  (cond-> {:name n :left left :right right}
                    (seq differs) (assoc :differs differs))))

(defn- keyed-merge
  "L1: THE keyed merge, one shape for every part: same key with the same
   identity keeps the first (first-writer-wins on whatever `ident`
   leaves out, such as a tool's handler); a different identity is a clash
   refusal. `label` is a (fn [v] described-identity) for the refusal."
  [what ident label a b]
  (answer/reduce-answer
   (fn [acc [k v]]
     (let [other (get acc k)]
       (cond
         (nil? other)                  (answer/ok (assoc acc k v))
         (= (ident other) (ident v))   (answer/ok acc)
         :else (clash what k (label other) (label v)
                      (differing-keys (ident other) (ident v))))))
   a
   b))

(defmulti merge-part
  "L1: merge part `k` of surfaces `a` and `b` into one value. Answers Ok
   or a refusal. Open: a new surface part adds a method, not an edit."
  (fn [k _a _b] k))

(defmethod merge-part :app [_ a b]
  (let [a (:app a) b (:app b)]
    (cond
      (= a b)                    (answer/ok a)
      (= a (:app empty-surface)) (answer/ok b)
      (= b (:app empty-surface)) (answer/ok a)
      (= (:name a) (:name b))    (answer/ok (merge a b))
      :else (answer/refusal :app-clash "two surfaces name different apps"
                            {:left a :right b}))))

(def ^:private regex-type
  "The type of a regex literal on this runtime."
  (type #""))

(defn- regex-data
  "L1: regex `re` as [source flags], the same on every runtime, so two
   patterns that differ only in their flags never compare equal. On
   ClojureWasm a Pattern exposes no flags (no .flags, no flag argument
   to re-pattern): a flag exists only as a leading inline group such as
   (?i), which stays in the source, so the source alone keeps two such
   patterns apart and the flags slot is nil."
  [re]
  #?(:cljs    [(.-source re) (.-flags re)]
     :cljw    [(str re) nil]
     :default [(.pattern ^java.util.regex.Pattern re) (.flags ^java.util.regex.Pattern re)]))

(defn- path-prefix?
  "L1: true when schema path `p` is `q` or one of its ancestors."
  [p q]
  (and (<= (count p) (count q)) (= p (subvec (vec q) 0 (count p)))))

(defn- foreign-registry?
  "L1: true when registry `reg` differs from the default registry `dflt`
   (a map of type to schema) in anything the keys of `own` do not name:
   a default type it lacks, redefines or adds. A key of `own` accounts
   only for a schema the form rebuilds: an IntoSchema object under it (a
   type such as m/-simple-schema, whose predicate the form hides) stays
   foreign. A registry whose types cannot be listed (nil, or one that is
   not a malli registry) is foreign: nothing says it matches the
   defaults, and reading it must never throw out of merge-surfaces."
  [reg own dflt]
  (let [sch (when (some? reg)
              (try (mr/-schemas (mr/registry reg))
                   (catch #?(:clj Throwable :cljs :default) _ nil)))]
    (boolean
     (or (not (map? sch))
         (some (fn [[k v]] (and (or (not (contains? own k)) (m/into-schema? v))
                                (not (identical? v (get dflt k ::absent)))))
               sch)
         (some #(not (contains? sch %)) (keys dflt))))))

(declare carries-options*)

(def ^:private malli-options
  "Options malli sets on the schemas it builds for its own bookkeeping,
   which carry nothing the form does not say."
  [:registry ::m/allow-invalid-refs])

(defn- accounted-value?
  "L1: true when property-registry value `v`, in scope of the accounted
   registry keys `own`, is rebuilt equal by recompiling its form: plain
   form data holding no compiled schema, IntoSchema or fn, or a compiled
   schema (malli compiles the value in place) whose own tree carries no
   options beyond those keys. An IntoSchema prints as a bare type its
   predicate hides, and a compiled value built with options of its own
   prints the same form for different validators, so neither is."
  [v own]
  (cond
    (m/schema? v)      (not (carries-options* v own))
    (m/into-schema? v) false
    (fn? v)            false
    (coll? v)          (every? #(accounted-value? % own) v)
    :else              true))

(defn- carries-options*
  "L1: `carries-options?` for `schema` nested where the registry keys in
   `inherited` are already accounted for."
  [schema inherited]
  (let [dflt  (mr/-schemas (m/-registry {}))
        nodes (volatile! [])]
    (m/walk schema (fn [s path _ _] (vswap! nodes conj [path s]) nil))
    (let [regs  (mapv (fn [[p s]] [p (:registry (m/properties s))]) @nodes)
          own   (fn [path] (into (set inherited)
                                 (comp (filter #(path-prefix? (first %) path)) (mapcat (comp keys second)))
                                 regs))]
      (boolean
       (or (some (fn [[p reg]] (not-every? #(accounted-value? % (own p)) (vals reg))) regs)
           (some (fn [[path s]]
                   (let [o (m/options s)]
                     (and (seq o)
                          (or (seq (apply dissoc o malli-options))
                              (foreign-registry? (:registry o) (own path) dflt)))))
                 @nodes))))))

(defn- carries-options?
  "L1: true when any schema in compiled `schema`'s tree was built with
   options its form does not account for: an option other than :registry
   (or malli's own ref bookkeeping), or a registry that adds, redefines
   or drops a type beyond the :registry properties the schema or its
   ancestors declare. Such a form names types the options define, so two
   schemas can share it and still validate differently. A property
   registry is accounted for when every value is (`accounted-value?`):
   malli copies it into the options of the schemas it builds, and
   recompiling the form rebuilds the same registry. m/walk does not enter
   registry-defined schemas, so their values are checked here,
   recursively."
  [schema]
  (carries-options* schema #{}))

(defn- schema-data
  "L1: `x` with every compiled malli schema replaced by its form and every
   regex by [source flags], recursively, so two compilations of one schema
   compare equal. A compiled schema that carries options its form does not
   account for (`carries-options?`) keeps its own identity: its form does
   not say what that registry means. Anything else is kept."
  [x]
  (cond
    (m/schema? x)                    (if (carries-options? x) x (schema-data (m/form x)))
    (identical? regex-type (type x)) (regex-data x)
    (map? x)                         (into {} (map (fn [[k v]] [(schema-data k) (schema-data v)])) x)
    (vector? x)                      (mapv schema-data x)
    (set? x)                         (into #{} (map schema-data) x)
    (seq? x)                         (apply list (map schema-data x))
    :else                            x))

(defn- tool-data
  "L1: a tool's identity for merging: every field but the fn-valued ones
   (handler, compiled decode/explain), which no two compilations share,
   with schemas compared by form (`schema-data`). Two tools of one name
   and id that differ in scopes, schemas or any other data are different
   affordances."
  [tool]
  (into {} (keep (fn [[k v]] (when-not (fn? v) [k (schema-data v)]))) tool))

(defmethod merge-part :tools [_ a b]
  (keyed-merge "tool" tool-data :id (:tools a) (:tools b)))

(defmethod merge-part :resources [_ a b]
  (keyed-merge "resource" #(dissoc % :read) :name (:resources a) (:resources b)))

(defmethod merge-part :interceptors [_ a b]
  ;; The vector defines execution order. A hash-map's vals do not.
  (let [entries (concat (:interceptors a) (:interceptors b))
        names (distinct (map :name entries))]
    (answer/bind (keyed-merge "interceptor" identity #(vec (sort (keys %)))
                              {} (map (juxt :name identity) entries))
                 #(answer/ok (mapv % names)))))

(defmethod merge-part :default [k a b]
  (cond
    (not (contains? a k)) (answer/ok (get b k))
    (not (contains? b k)) (answer/ok (get a k))
    (= (get a k) (get b k)) (answer/ok (get a k))
    :else (clash "surface part" k (get a k) (get b k) (differing-keys (get a k) (get b k)))))

(defn- merge-parts
  "L1 Promote: merge every part supplied by either surface."
  [a b]
  (answer/reduce-answer
   (fn [acc k] (answer/bind (merge-part k a b) #(answer/ok (assoc acc k %))))
   {} (distinct (concat (keys a) (keys b)))))

(defn- merge-two
  "L1: validate both surfaces, then merge part by part. One railway."
  [a b]
  (answer/let-answer [a (->surface a)
                      b (->surface b)]
    (merge-parts a b)))

(defn merge-surfaces
  "L1: the monoid fold of surfaces, left to right: ALWAYS Ok of the
   composition, or the first refusal (:invalid-surface, :name-clash,
   :app-clash). Identity: `empty-surface`. Associative on disjoint names.

   Two tools under one name are one affordance when they agree on every
   field that is not a fn; malli schemas (:input, :output) are compared
   by form, regexes by source and flags. A :registry the form declares in
   its own properties is part of the form, so two compilations of it
   merge. A compiled schema built with options the form does not account
   for (a custom options registry, any other option) is compared by
   identity, because its form names types the options define: two such
   schemas of one form may validate differently, so they clash unless
   they are the same value.
   The fn-valued fields are first-writer-wins: the merged tool keeps the
   leftmost surface's handler. A schema whose form holds a fn, such as an
   inline [:fn #(...)], is equal only to itself, because two evaluations
   of that fn are different values; name the predicate (a var) to make
   two compilations agree. A :name-clash refusal names the differing keys
   under :differs."
  [& surfaces]
  (answer/reduce-answer merge-two empty-surface surfaces))

;; =============================================================================
;; Transformation: a surface's chain grows by one link; decorators map it.
;; =============================================================================

(defn- chain-names
  "L1: the :name of every link in `surface`'s call chain."
  [surface]
  (into #{} (map :name) (:interceptors surface)))

(defn- valid-interceptor
  "L1 Promote: Ok of `ic` when it is an Interceptor, else :invalid-interceptor."
  [ic]
  (if-let [e (m/explain Interceptor ic)]
    (answer/refusal :invalid-interceptor "not a valid interceptor" (me/humanize e))
    (answer/ok ic)))

(defn- unique-link
  "L1 Promote: Ok of `ic` when no link of `surface` has its :name, else :name-clash."
  [surface ic]
  (if (contains? (chain-names surface) (:name ic))
    (answer/refusal :name-clash (str "interceptor " (:name ic) " is already in the chain")
                    {:name (:name ic)})
    (answer/ok ic)))

(defn add-interceptor
  "L1: Ok of `surface` with Interceptor `ic` appended to its call chain.
   Refuses :invalid-interceptor (schema) or :name-clash (a link of that
   :name is already in the chain)."
  [surface ic]
  (answer/let-answer [ic (valid-interceptor ic)
                      ic (unique-link surface ic)]
    (answer/ok (update surface :interceptors conj ic))))

(defn- keyed-by-name?
  "L1: true when every tool is keyed by its own :name."
  [surface]
  (every? (fn [[k tool]] (= k (:name tool))) (:tools surface)))

(defn- whole
  "L1 Promote: Ok of validated `s` when `value` needed no part filled in;
   else an :invalid-surface refusal naming the missing parts."
  [value s]
  (if (= s value)
    (answer/ok s)
    (answer/refusal :invalid-surface "a decorator dropped a part of the surface"
                    {:missing (vec (remove #(contains? value %) (keys empty-surface)))})))

(defn- rekeyed
  "L1 Promote: Ok of `s` when every tool is keyed by its own :name; else
   an :invalid-surface refusal naming the mismatched keys."
  [s]
  (if (keyed-by-name? s)
    (answer/ok s)
    (answer/refusal :invalid-surface "a decorator renamed a tool without rekeying it"
                    {:mismatched (vec (sort (for [[k t] (:tools s) :when (not= k (:name t))] k)))})))

(defn- decorated
  "L1 Promote: Ok of a decorator's result when it is a whole, valid
   Surface whose tools are keyed by name; else :invalid-surface."
  [value]
  (answer/let-answer [s (->surface value)
                      s (whole value s)]
    (rekeyed s)))

(defn decorate
  "L1: Ok of `surface` passed through each Surface -> Surface fn of
   `decorators`, left to right, every intermediate result validated. The
   first invalid result is an :invalid-surface refusal."
  [surface decorators]
  (answer/reduce-answer (fn [s d] (decorated (d s))) surface decorators))

;; =============================================================================
;; Registration: one affordance in or out of a surface.
;; =============================================================================

(defn put-tool
  "L1: Ok of `surface` with compiled `tool` under its :name, replacing a
   tool of the same :id there. A different tool holding that name is a
   :name-clash refusal."
  [surface {:keys [id name] :as tool}]
  (let [other (get-in surface [:tools name])]
    (if (and other (not= (:id other) id))
      (answer/refusal :name-clash (str "tool name " name " is already used by " (:id other))
                      {:name name})
      (answer/ok (assoc-in surface [:tools name] tool)))))

(defn drop-tools
  "L1: `surface` without every tool whose MCP name or id is in `ks`."
  [surface ks]
  (let [gone? (set ks)]
    (update surface :tools
            #(into {} (remove (fn [[n t]] (or (gone? n) (gone? (:id t))))) %))))

(defn put-resource
  "L1: `surface` with compiled resource `r` at `uri`, replacing any there."
  [surface uri r]
  (assoc-in surface [:resources uri] r))

(defn drop-resource
  "L1: `surface` without the resource at `uri`."
  [surface uri]
  (update surface :resources dissoc uri))

(defn set-app
  "L1: Ok of `surface` describing the app as `info`; an :invalid-app
   refusal when `info` is not an AppInfo."
  [surface info]
  (if-let [e (m/explain s/AppInfo info)]
    (answer/refusal :invalid-app "invalid app info" (me/humanize e))
    (answer/ok (assoc surface :app info))))