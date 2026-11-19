(ns autonomous.openapi.doc
  "An OpenAPI 3 document as data, and its $ref resolution. Pure.

   A Bundle is the root document plus every document it reaches by $ref:

     {:root \"sisf-payment.yaml\"
      :docs {\"sisf-payment.yaml\" {...} \"common.yaml\" {...}}}

   Documents keep the JSON/YAML shape: string keys, vectors for arrays.
   Document names are paths relative to the root's directory, so
   \"./common.yaml\" from \"sisf-payment.yaml\" is \"common.yaml\". Reading
   files is the loader's job (autonomous.openapi.load); nothing here does IO."
  (:require [clojure.string :as str]))

(defn normalize
  "Parsed YAML/JSON `x` in the shape this namespace reads: map keys as
   strings (YAML may parse `200:` as a number), sequences as vectors."
  [x]
  (cond
    (map? x)        (into {} (map (fn [[k v]] [(if (keyword? k) (subs (str k) 1) (str k)) (normalize v)])) x)
    (sequential? x) (mapv normalize x)
    :else           x))

(defn bundle
  "A Bundle of one in-memory document `doc` named `root` (default \"\")."
  ([doc] (bundle "" doc))
  ([root doc] {:root root :docs {root (normalize doc)}}))

(defn bundle?
  [x]
  (and (map? x) (contains? x :docs) (contains? x :root)))

(defn ->bundle
  "`x` as a Bundle: a Bundle stays one, a bare document becomes one."
  [x]
  (if (bundle? x) x (bundle x)))

;; -----------------------------------------------------------------------------
;; Paths of documents

(defn- dir-of [path]
  (let [i (str/last-index-of path "/")]
    (if i (subs path 0 (inc i)) "")))

(defn join-path
  "Document name of `rel` (a $ref's file part) seen from document `base`:
   \"./common.yaml\" from \"api/payment.yaml\" is \"api/common.yaml\"; \".\"
   and \"..\" segments fold away."
  [base rel]
  (let [segs (str/split (str (dir-of base) rel) #"/")]
    (str/join "/" (reduce (fn [acc s]
                            (case s
                              ("" ".") acc
                              ".." (if (and (seq acc) (not= ".." (peek acc))) (pop acc) (conj acc s))
                              (conj acc s)))
                          [] segs))))

(defn split-ref
  "[file pointer] of $ref string `ref`: \"./common.yaml#/a/b\" is
   [\"./common.yaml\" \"/a/b\"], \"#/a\" is [\"\" \"/a\"]."
  [ref]
  (let [i (str/index-of ref "#")]
    (if i [(subs ref 0 i) (subs ref (inc i))] [ref ""])))

(defn- unescape-token [t]
  (-> t (str/replace "~1" "/") (str/replace "~0" "~")))

(defn pointer-get
  "Value at JSON Pointer `pointer` (\"/components/schemas/X\") in `doc`, or
   ::missing."
  [doc pointer]
  (reduce (fn [node token]
            (let [t (unescape-token token)]
              (cond
                (map? node)    (if (contains? node t) (get node t) (reduced ::missing))
                (vector? node) (let [i (parse-long t)]
                                 (if (and i (< -1 i (count node))) (nth node i) (reduced ::missing)))
                :else          (reduced ::missing))))
          doc
          (rest (str/split pointer #"/" -1))))

;; -----------------------------------------------------------------------------
;; Which documents a document reaches

(defn- refs-in [x]
  (cond
    (map? x)        (concat (when (string? (get x "$ref")) [(get x "$ref")])
                            (mapcat refs-in (vals x)))
    (sequential? x) (mapcat refs-in x)
    :else           nil))

(defn referenced-docs
  "Names of the other documents `doc` (named `name`) refers to by $ref."
  [name doc]
  (into (sorted-set)
        (comp (map (comp first split-ref))
              (remove str/blank?)
              (map #(join-path name %)))
        (refs-in doc)))

(defn missing-docs
  "Document names some document of `bundle` refers to and the bundle lacks."
  [{:keys [docs]}]
  (into (sorted-set)
        (comp (mapcat (fn [[n d]] (referenced-docs n d)))
              (remove #(contains? docs %)))
        docs))

;; -----------------------------------------------------------------------------
;; Resolution

(defn- deref-1
  "[target-doc-name value] the $ref `ref` in document `base` points at, or
   [nil ::missing]."
  [{:keys [docs]} base ref]
  (let [[file pointer] (split-ref ref)
        target         (if (str/blank? file) base (join-path base file))
        doc            (get docs target ::missing)]
    (if (= ::missing doc)
      [nil ::missing]
      [target (pointer-get doc pointer)])))

(defn resolve-node
  "`node` of document `base` with every $ref replaced by what it points at,
   recursively, across documents of `bundle`. Keys beside a $ref (3.1
   allows a description there) win over the target's. A $ref met again
   inside its own expansion (a recursive schema) becomes
   {\"x-autonomous-cycle\" ref}; one that points nowhere becomes
   {\"x-autonomous-unresolved\" ref}. Total: never throws."
  ([bundle base node] (resolve-node bundle base node #{}))
  ([bundle base node seen]
   (cond
     (and (map? node) (string? (get node "$ref")))
     (let [ref          (get node "$ref")
           [target val] (deref-1 bundle base ref)
           k            [target (second (split-ref ref))]
           siblings     (dissoc node "$ref")]
       (cond
         (= ::missing val) (merge {"x-autonomous-unresolved" ref} siblings)
         (contains? seen k) (merge {"x-autonomous-cycle" ref} siblings)
         :else (let [r (resolve-node bundle target val (conj seen k))]
                 (if (map? r)
                   (merge r (resolve-node bundle base siblings seen))
                   r))))

     (map? node)
     (into {} (map (fn [[k v]] [k (resolve-node bundle base v seen)])) node)

     (vector? node)
     (mapv #(resolve-node bundle base % seen) node)

     :else node)))

(defn root-doc
  "The root document of `bundle`."
  [{:keys [root docs]}]
  (get docs root))

(defn resolved
  "`node` of the root document, resolved."
  [bundle node]
  (resolve-node bundle (:root bundle) node))
