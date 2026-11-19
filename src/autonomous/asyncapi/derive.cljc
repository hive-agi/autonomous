(ns autonomous.asyncapi.derive
  "Collect + Promote of the :asyncapi Source: the subjects an author OPTS IN
   to, as tap descriptions. Pure: answers {:ok [tap-desc ...]} or a refusal.

   Opt-in only, and strict about it. A subject the :expose map does not name
   is never a resource. Three things are refused here rather than looking
   like silence later:

     an empty :expose            nothing to derive (:asyncapi/nothing-exposed)
     a filter NATS cannot use    (\"a..b\", a trailing dot) (:asyncapi/bad-subject)
     a LITERAL subject the doc   a typo; a wildcard the document does not
       does not declare           name is allowed on purpose, since a tap may
                                  watch subjects the doc has not caught up on

   The tap description carries the document's payload schema so a read can
   flag a non-conforming payload; nil when the subject has none. Each entry is
   validated on its own and the entries are collected on the railway, so a
   refusal is the earliest bad subject, never a partial surface."
  (:require [autonomous.asyncapi.doc :as doc]
            [autonomous.asyncapi.rail :as rail]
            [autonomous.asyncapi.subject :as subject]
            [autonomous.asyncapi.tap :as tap]
            [autonomous.domain.answer :as answer]
            [autonomous.openapi.doc :as odoc]))

(def kind
  "The Resource kind a derived tap carries."
  :asyncapi/doc)

(def default-limit
  "Messages one read answers when the resource names none."
  100)

(defn- positive [x] (when (and (integer? x) (pos? x)) x))

(defn- scopes-of
  "Scopes `cfg` asks for as a set, nil when it asks for none."
  [cfg]
  (let [x (or (:scopes cfg) (:scope cfg))]
    (cond (nil? x)                  nil
          (or (keyword? x) (string? x)) #{(keyword x)}
          :else                     (set (map keyword x)))))

(defn- description [subject cfg]
  (or (:description cfg)
      (str "Messages published on " subject
           ". Read-only: a bounded tap of the last few, plus a cursor that "
           "says what is new since your last read.")))

(defn- tap-desc
  "The tap description of `subject` with options `cfg`."
  [bundle subject cfg]
  (cond-> {:uri         (str "asyncapi://" subject)
           :subject     subject
           :description (description subject cfg)
           :capacity    (or (positive (:capacity cfg)) tap/default-capacity)
           :limit       (or (positive (:limit cfg)) default-limit)
           :schema      (doc/schema-of bundle subject)}
    (scopes-of cfg) (assoc :scopes (scopes-of cfg))))

(defn- unknown-subject
  "Refusal for a literal `subject` the document does not declare."
  [subject docs]
  (answer/refusal :asyncapi/unknown-subject
                  (str "the document declares no channel " (pr-str subject)
                       "; a typo here would tap nothing")
                  {:unknown subject :known (vec (sort docs))}))

(defn- bad-options [subject]
  (answer/refusal :asyncapi/bad-expose
                  (str (pr-str subject) " must map to an options map, e.g. "
                       "{:scope :payments/read}")
                  {:subject subject}))

(defn- one
  "Ok tap-desc of `subject` cfg, or a refusal."
  [bundle subject cfg]
  (let [docs (set (doc/addresses bundle))]
    (cond
      (not (map? cfg))                                  (bad-options subject)
      (not (subject/valid? subject))                    (subject/check subject)
      (and (subject/literal? subject)
           (not (contains? docs subject)))              (unknown-subject subject docs)
      :else (answer/ok (tap-desc bundle subject cfg)))))

(defn- expose-checks
  "Refusal for an unusable `expose`, nil when it is usable."
  [expose]
  (cond
    (not (map? expose)) (answer/refusal :asyncapi/bad-expose
                                       ":expose must be a map of subject to options"
                                       {:got (some-> expose type str)})
    (empty? expose)     (answer/refusal :asyncapi/nothing-exposed
                                       "opts :expose names no subject; nothing is derived")))

(defn taps
  "Ok [tap-desc ...] of the subjects opts :expose names, in :expose order, or
   a refusal. `bundle-or-doc` is an AsyncAPI Bundle or a bare document.

   opts:
     :expose  {subject {:scope kw :scopes [kw] :capacity n :limit n
                        :description str}}   [required]"
  [bundle-or-doc {:keys [expose]}]
  (if-not bundle-or-doc
    (answer/refusal :asyncapi/no-document "no AsyncAPI document to derive from")
    (if-let [bad (expose-checks expose)]
      bad
      (let [bundle (odoc/->bundle bundle-or-doc)]
        (rail/collect (map (fn [[subject cfg]] (one bundle subject cfg)) expose))))))
