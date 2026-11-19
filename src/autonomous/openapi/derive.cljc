(ns autonomous.openapi.derive
  "Collect + Promote: the operations of an OpenAPI 3 Bundle an author OPTS
   IN to, as tool specs. Pure: answers {:ok [[id spec] ...]} or a refusal.

   Opt-in only. An operation the :expose map does not name is never a tool,
   so a new endpoint in the spec never reaches an agent by accident. A name
   in :expose the spec does not have is refused (a typo must not silently
   drop a tool).

   The input schema is one flat malli map: path, query and header
   parameters, plus the JSON request body's properties. When a body
   property shares a parameter's name, or the body is not an object, the
   whole body goes under :body instead.

   Strata, one per section below: read the spec, decide how a body joins
   the args (a closed set), assemble one Operation, then guard and fold the
   exposure. Composition rides the answer railway."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.openapi.doc :as doc]
            [autonomous.openapi.schema :as schema]
            [clojure.string :as str]))

(def kind
  "The tool kind derived tools carry; autonomous.openapi.invoke runs it."
  :autonomous.openapi/http)

(def methods* ["get" "put" "post" "delete" "patch" "head" "options"])

;; -----------------------------------------------------------------------------
;; Reading the spec

(defn operations
  "Every operation of `bundle`: [{:operation-id :method :path :path-item
   :op}] in spec order, unresolved."
  [bundle]
  (for [[path item] (get (doc/root-doc bundle) "paths")
        m           methods*
        :let        [op (get item m)]
        :when       (map? op)]
    {:operation-id (get op "operationId") :method m :path path :path-item item :op op}))

(defn- param-id
  "What makes two parameters the same one: name and location together."
  [p]
  [(get p "name") (get p "in")])

(defn- params
  "Resolved parameters of an operation, path-level ones overridden by the
   operation-level ones of the same name and location. Spec order: the
   first occurrence keeps the position, the last one its content."
  [bundle {:keys [path-item op]}]
  (let [resolved (map #(doc/resolved bundle %)
                      (concat (get path-item "parameters") (get op "parameters")))
        by-id    (into {} (map (juxt param-id identity)) resolved)]
    (mapv by-id (distinct (map param-id resolved)))))

(defn- entry
  "Pure: Malli entry, omitting the options map when empty."
  [k optional? description form]
  (let [opts (cond-> {} optional? (assoc :optional true) description (assoc :description description))]
    (if (empty? opts) [k form] [k opts form])))

(defprotocol ParameterProjection
  "Pure: the codec and argument-schema views of one resolved parameter."
  (codec-parameter [p] "Plain data used to place the argument on the wire.")
  (parameter-entry [p] "Malli entry advertising the same argument."))

(defrecord SpecParameter [name in key required? description schema]
  ParameterProjection
  (codec-parameter [_] {:name name :in in :key key :required? required?})
  (parameter-entry [_] (entry key (not required?) description schema)))

(defn- spec-parameter
  "Pure: compile one resolved OpenAPI parameter to both views."
  [{n "name" in "in" required "required" description "description" form "schema"}]
  (->SpecParameter n in (schema/param-key n) (or (= "path" in) (true? required))
                   description (schema/->malli form {:input? true})))

(defn- json-media?
  "Pure: spec content keys that designate a JSON schema."
  [media]
  (let [m (str/lower-case (str media))]
    (or (= m "application/json") (str/ends-with? m "+json"))))

(defn- json-media
  "Pure: first JSON media type in the document's iteration order."
  [content]
  (first (filter json-media? (keys content))))

;; -----------------------------------------------------------------------------
;; The request body

(defn- unsupported-body
  "Pure: refusal listing the content types the operation actually offers."
  [op-id content]
  (answer/refusal :openapi/unsupported-media
                  (str "operation " op-id " takes " (str/join ", " (keys content))
                       "; only JSON bodies are supported")
                  {:media (vec (keys content))}))

(defn- request-body
  "Pure: compile a resolved request body to its JSON media and schema."
  [op-id {content "content" required "required"}]
  (if-let [media (json-media content)]
    (answer/ok {:media media :required? (true? required)
                :schema (schema/->malli (get-in content [media "schema"]) {:input? true})})
    (unsupported-body op-id content)))

(defn- body-of
  "Pure: resolve and compile an operation's optional request body."
  [bundle op]
  (if-let [body (some->> (get op "requestBody") (doc/resolved bundle))]
    (request-body (get op "operationId") body)
    (answer/ok nil)))

(defn- map-children
  "The property entries of malli map `schema`, nil when it is not a map form."
  [schema]
  (when (and (vector? schema) (= :map (first schema)))
    (subvec schema (if (map? (second schema)) 2 1))))

(defn- body-mode
  "Pure: choose the argument layout. Even an empty object can be flat."
  [body claimed]
  (let [entries (map-children (:schema body))]
    (cond
      (nil? body) :none
      (and entries (not-any? claimed (map first entries))) :flat
      :else :nested)))

(defn- optional-entry
  "`entry` forced optional, whatever malli opts it already carries."
  [[k a b]]
  (if (some? b) [k (assoc a :optional true) b] [k {:optional true} a]))

(defn- body-entries
  "Pure: Malli entries for the selected body layout (a closed set)."
  [mode {:keys [schema required?]}]
  (case mode
    :none []
    :flat (cond->> (map-children schema) (not required?) (mapv optional-entry))
    :nested [(entry :body (not required?) nil schema)]))

;; -----------------------------------------------------------------------------
;; Assembling one Operation

(defn- response-content
  "Pure: prefer JSON for schema checking; otherwise advertise the first media."
  [content]
  (let [media (json-media content)]
    {:media (or media (first (keys content)))
     :schema (when media (some-> (get-in content [media "schema"]) schema/->malli))}))

(defn- responses
  "Pure: resolved and compiled response declarations keyed by status string."
  [bundle op]
  (into (sorted-map)
        (map (fn [[status r]] [(str status) (response-content (get (doc/resolved bundle r) "content"))]))
        (get op "responses")))

(defn- body-field
  "Pure: codec body descriptor, nil without a body."
  [{:keys [media required? schema] :as body} mode]
  (when body
    {:media media :mode mode :required? required?
     :keys (when (= :flat mode) (vec (sort (map first (map-children schema)))))}))

(defn- build-operation
  "Pure: assemble the plain Operation map from its compiled parts."
  [{:keys [operation-id method path]} projections body responses]
  (let [params (mapv codec-parameter projections)
        mode (body-mode body (set (map :key params)))]
    {:operation-id operation-id :method method :path path
     :params params :body (body-field body mode) :responses responses
     :input (into [:map] (concat (map parameter-entry projections) (body-entries mode body)))}))

(defn operation
  "Pure: compile an Operation for the codec and its input schema, or refuse it."
  [bundle {:keys [op] :as o}]
  (let [resolved (params bundle o)]
    (answer/bind (body-of bundle op)
                 (fn [body]
                   (answer/ok (build-operation o (mapv spec-parameter resolved)
                                               body (responses bundle op)))))))

;; -----------------------------------------------------------------------------
;; Promoting an Operation to a tool spec

(defn- describe [{:keys [op method path]}]
  (let [s (str/trim (str (get op "summary")))
        d (str/trim (str (get op "description")))]
    (str/join "\n\n" (remove str/blank? [s d (str (str/upper-case method) " " path)]))))

(defn- tool-id [prefix op-id]
  (if prefix (keyword (name prefix) op-id) (keyword op-id)))

(defn- read-only?
  "Whether the tool is read-only: the caller's word, else only GET and HEAD."
  [o {:keys [read-only?]}]
  (if (some? read-only?) (boolean read-only?) (contains? #{"get" "head"} (:method o))))

(defn- scopes
  "Pure: explicit :scopes overrides :scope, even when the list is empty."
  [{:keys [scope scopes]}]
  (cond scopes (set scopes) scope #{scope}))

(defn- tool-spec
  "Pure: advertise a compiled Operation. Only caller opts supply the client."
  [op o cfg opts]
  (let [required-scopes (scopes cfg)]
    (cond-> {:description (or (:description cfg) (describe o))
             :kind kind :input (:input op) :read-only? (read-only? o cfg)
             :openapi/operation (dissoc op :input)
             :openapi/client (select-keys opts [:base-url :http :principal])}
      required-scopes (assoc :scopes required-scopes))))

(defn- ->spec
  "Pure: Ok [id spec] of an exposed operation."
  [bundle o cfg opts]
  (answer/bind (operation bundle o)
               #(answer/ok [(tool-id (:prefix opts) (:operation-id o)) (tool-spec % o cfg opts)])))

;; -----------------------------------------------------------------------------
;; Guarding and folding the exposure

(defn- checked-exposure
  "Pure: Ok of the exposure map, or its refusal before any operation is compiled."
  [expose by-id]
  (let [unknown (remove #(contains? by-id (name %)) (keys expose))]
    (cond
      (empty? expose)
      (answer/refusal :openapi/nothing-exposed "opts :expose names no operationId; nothing is derived")

      (seq unknown)
      (answer/refusal :openapi/unknown-operation
                      (str "the spec has no operationId " (str/join ", " (map name unknown)))
                      {:unknown (mapv name unknown) :known (vec (sort (keys by-id)))})
      :else (answer/ok expose))))

(defn- append-spec
  "Pure: one exposure fold step; append only if the operation compiles."
  [bundle by-id opts specs [op-id cfg]]
  (answer/bind (->spec bundle (by-id (name op-id)) cfg opts)
               #(answer/ok (conj specs %))))

(defn derive-tools
  "Tool specs of the operations of `bundle-or-doc` that opts :expose names.

   opts:
     :expose     {operationId {:scope kw :scopes [kw] :read-only? bool
                               :description str}}       [required]
     :prefix     namespace of the tool ids (\"payment\" gives
                 :payment/listPayments, MCP name payment_listPayments)
     :base-url   where requests go (the gateway), e.g. \"https://h/api/payment\"
     :http       (fn [request] response), default autonomous.openapi.http
     :principal  (fn [grant request] request): adds the persona's identity

   :read-only? defaults to true for GET and HEAD only. Answers
   {:ok [[id spec] ...]} in :expose order, or a refusal."
  [bundle-or-doc {:keys [expose] :as opts}]
  (let [bundle (doc/->bundle bundle-or-doc)
        by-id  (into {} (map (juxt :operation-id identity)) (operations bundle))]
    (answer/bind (checked-exposure expose by-id)
                 #(answer/reduce-answer (partial append-spec bundle by-id opts) [] %))))
