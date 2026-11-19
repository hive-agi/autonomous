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
   whole body goes under :body instead."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.openapi.doc :as doc]
            [autonomous.openapi.schema :as schema]
            [clojure.string :as str]))

(def kind
  "The tool kind derived tools carry; autonomous.openapi.invoke runs it."
  :autonomous.openapi/http)

(def methods* ["get" "put" "post" "delete" "patch" "head" "options"])

(defn operations
  "Every operation of `bundle`: [{:operation-id :method :path :path-item
   :op}] in spec order, unresolved."
  [bundle]
  (for [[path item] (get (doc/root-doc bundle) "paths")
        m           methods*
        :let        [op (get item m)]
        :when       (map? op)]
    {:operation-id (get op "operationId") :method m :path path :path-item item :op op}))

(defn- json-media? [media]
  (let [m (str/lower-case (str media))]
    (or (= m "application/json") (str/ends-with? m "+json"))))

(defn- params
  "Resolved parameters of an operation: path-level ones overridden by
   operation-level ones of the same name and location."
  [bundle {:keys [path-item op]}]
  (let [ps    (map #(doc/resolved bundle %) (concat (get path-item "parameters") (get op "parameters")))
        k     (fn [p] [(get p "name") (get p "in")])
        final (into {} (map (juxt k identity)) ps)]
    ;; Spec order, first occurrence wins the position, the last its content.
    (first (reduce (fn [[out seen] p]
                     (if (seen (k p)) [out seen] [(conj out (final (k p))) (conj seen (k p))]))
                   [[] #{}] ps))))

(defn- entry
  "Malli map entry [k opts form]."
  [k optional? description form]
  (let [o (cond-> {} optional? (assoc :optional true) description (assoc :description description))]
    (if (empty? o) [k form] [k o form])))

(defn- map-children [form]
  (if (map? (second form)) (drop 2 form) (rest form)))

(defn- optional-entry [[k a b]]
  (if (some? b) [k (assoc a :optional true) b] [k {:optional true} a]))

(defn- body-of
  "{:media :required? :schema form} of the operation's request body, nil
   without one, or a refusal for a media type the codec cannot send."
  [bundle op]
  (when-let [rb (some->> (get op "requestBody") (doc/resolved bundle))]
    (let [content (get rb "content")
          media   (first (filter json-media? (keys content)))]
      (if-not media
        (answer/refusal :openapi/unsupported-media
                        (str "operation " (get op "operationId") " takes "
                             (str/join ", " (keys content)) "; only JSON bodies are supported")
                        {:media (vec (keys content))})
        {:media     media
         :required? (true? (get rb "required"))
         :schema    (schema/->malli (get-in content [media "schema"]) {:input? true})}))))

(defn- responses
  "{status {:media :schema}} of every declared response; :schema is a
   malli form, nil when the response has no JSON body."
  [bundle op]
  (into (sorted-map)
        (map (fn [[status r]]
               (let [r       (doc/resolved bundle r)
                     content (get r "content")
                     media   (first (filter json-media? (keys content)))]
                 [(str status)
                  {:media  (or media (first (keys content)))
                   :schema (when media (some-> (get-in content [media "schema"]) schema/->malli))}])))
        (get op "responses")))

(defn operation
  "The codec's view of operation `o` of `bundle`: what encode and decode
   need, as data. Answers {:ok Operation} or a refusal."
  [bundle o]
  (let [ps     (params bundle o)
        body   (body-of bundle (:op o))
        pkeys  (set (map #(schema/param-key (get % "name")) ps))
        pin    (into [] (map (fn [p] {:name      (get p "name")
                                      :in        (get p "in")
                                      :key       (schema/param-key (get p "name"))
                                      :required? (or (= "path" (get p "in")) (true? (get p "required")))}))
                     ps)]
    (if (answer/refused? body)
      body
      (let [bform  (:schema body)
            bkeys  (when (and bform (= :map (first bform))) (set (map first (map-children bform))))
            mode   (cond (nil? body)                                      :none
                         (and bkeys (empty? (filter pkeys bkeys)))        :flat
                         :else                                            :nested)
            pent   (map (fn [p] (entry (schema/param-key (get p "name"))
                                       (not (or (= "path" (get p "in")) (true? (get p "required"))))
                                       (get p "description")
                                       (schema/->malli (get p "schema") {:input? true})))
                        ps)
            bent   (case mode
                     :none   []
                     :flat   (cond->> (map-children bform) (not (:required? body)) (map optional-entry))
                     :nested [(entry :body (not (:required? body)) nil bform)])]
        (answer/ok {:operation-id (:operation-id o)
                    :method       (:method o)
                    :path         (:path o)
                    :params       pin
                    :body         (when body {:media (:media body) :mode mode :required? (:required? body)
                                              :keys (when (= :flat mode) (vec (sort bkeys)))})
                    :responses    (responses bundle (:op o))
                    :input        (into [:map] (concat pent bent))})))))

(defn- describe [{:keys [op method path]}]
  (let [s (str/trim (str (get op "summary")))
        d (str/trim (str (get op "description")))]
    (str/join "\n\n" (remove str/blank? [s d (str (str/upper-case method) " " path)]))))

(defn- tool-id [prefix op-id]
  (if prefix (keyword (name prefix) op-id) (keyword op-id)))

(defn- ->spec [bundle o {:keys [scope scopes read-only? description]} {:keys [prefix] :as opts}]
  (let [op (operation bundle o)]
    (if (answer/refused? op)
      op
      (let [ro? (if (some? read-only?) (boolean read-only?) (contains? #{"get" "head"} (:method o)))]
        (answer/ok [(tool-id prefix (:operation-id o))
                    (cond-> {:description       (or description (describe o))
                             :kind              kind
                             :input             (:input (:ok op))
                             :read-only?        ro?
                             :openapi/operation (dissoc (:ok op) :input)
                             ;; No default from the spec's `servers`: they may name
                    ;; production. The caller says where requests go.
                    :openapi/client    (select-keys opts [:base-url :http :principal])}
                      scope  (assoc :scopes #{scope})
                      scopes (assoc :scopes (set scopes)))])))))

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
  (let [bundle  (doc/->bundle bundle-or-doc)
        by-id   (into {} (map (juxt :operation-id identity)) (operations bundle))
        unknown (remove #(contains? by-id (name %)) (keys expose))]
    (cond
      (empty? expose)
      (answer/refusal :openapi/nothing-exposed "opts :expose names no operationId; nothing is derived")

      (seq unknown)
      (answer/refusal :openapi/unknown-operation
                      (str "the spec has no operationId " (str/join ", " (map name unknown)))
                      {:unknown (mapv name unknown) :known (vec (sort (keys by-id)))})

      :else
      (reduce (fn [acc [op-id cfg]]
                (let [r (->spec bundle (by-id (name op-id)) cfg opts)]
                  (if (answer/refused? r) (reduced r) (update acc :ok conj (:ok r)))))
              (answer/ok [])
              expose))))
