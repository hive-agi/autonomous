(ns autonomous.openapi.codec
  "Codec (Pipeline): a pure pair over one Operation (autonomous.openapi.derive/operation).

     encode  args -> ring-style request {:method :url :headers :body}
     decode  response {:status :headers :body} -> Answer

   decode is total: success only for a 2xx the operation DECLARES whose body
   matches the declared schema; problem+json and every 4xx/5xx become a
   refusal carrying the server's message; anything else a refusal too.
   Neither function throws."
  (:require #?(:clj [clojure.data.json :as json] :bb [clojure.data.json :as json] :cljw [clojure.data.json :as json])
            [autonomous.domain.answer :as answer]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

;; -----------------------------------------------------------------------------
;; JSON and URLs, per runtime

(defn- write-json [x]
  #?(:cljs    (js/JSON.stringify (clj->js x))
     :default (json/write-str x)))

(defn- read-json [s]
  #?(:cljs    (js->clj (js/JSON.parse s) :keywordize-keys true)
     :default (json/read-str s :key-fn keyword)))

(defn url-encode
  "Percent-encoding of `s` for a path segment or query component."
  [s]
  #?(:cljs    (js/encodeURIComponent (str s))
     :default (-> (java.net.URLEncoder/encode (str s) "UTF-8")
                  (str/replace "+" "%20")
                  (str/replace "%7E" "~"))))

(defn- text [v]
  (cond (keyword? v) (name v)
        (boolean? v) (if v "true" "false")
        :else        (str v)))

;; -----------------------------------------------------------------------------
;; encode

(defmulti place-parameter
  "Pure: place one argument in the request parts, by OpenAPI parameter location."
  (fn [_parts parameter _args] (:in parameter)))

(defmethod place-parameter "path" [parts {:keys [name key]} args]
  (update parts :path str/replace (str "{" name "}") (url-encode (text (get args key)))))

(defmethod place-parameter "query" [parts {:keys [name key]} args]
  (if-some [v (get args key)]
    (update parts :query into
            (map #(str (url-encode name) "=" (url-encode (text %))))
            (if (sequential? v) v [v]))
    parts))

(defmethod place-parameter "header" [parts {:keys [name key]} args]
  (if-some [v (get args key)]
    (assoc-in parts [:headers (str/lower-case name)] (text v))
    parts))

(defmethod place-parameter :default [parts _ _] parts)

(defn- request-parts
  "Pure: fold the parameters in declaration order into path, query and headers."
  [{:keys [path params]} args]
  (reduce #(place-parameter %1 %2 args)
          {:path path :query [] :headers {"accept" "application/json, application/problem+json"}}
          params))

(defn- join-url [base path]
  (str (str/replace (str base) #"/+$" "") path))

(defn- flat-body
  "L1 Promote: the non-nil args the flat body declares, nil when there are
   none and the body is optional."
  [{:keys [keys required?]} args]
  (let [ks (set keys)
        b  (into {} (filter (fn [[k v]] (and (contains? ks k) (some? v)))) args)]
    (when (or (seq b) required?) b)))

(defn- body-of
  "L1 Promote: the request payload of `args` for the operation's body mode
   (a closed set); nil without a body."
  [{:keys [body]} args]
  (case (:mode body)
    :flat   (flat-body body args)
    :nested (:body args)
    nil))

(defn- request-url
  "L1 Promote: `base-url` joined with `path`, then the query string if any."
  [base-url path query]
  (cond-> (join-url base-url path) (seq query) (str "?" (str/join "&" query))))

(defn encode
  "L2 Pipeline: Ring-style request for `operation` with `args` at `base-url`."
  [operation base-url args]
  (let [{:keys [path query headers]} (request-parts operation args)
        payload (body-of operation args)]
    {:method  (keyword (:method operation))
     :url     (request-url base-url path query)
     :headers (cond-> headers (some? payload) (assoc "content-type" (get-in operation [:body :media])))
     :body    (when (some? payload) (write-json payload))}))

;; -----------------------------------------------------------------------------
;; decode

(defn- header [headers k]
  (some (fn [[hk v]] (when (= k (str/lower-case (name hk))) (if (sequential? v) (first v) v))) headers))

(defn- json-type? [ct]
  (let [ct (str/lower-case (str ct))]
    (or (str/includes? ct "application/json") (str/includes? ct "+json"))))

(defn- parse-json-body
  "Pure: parsed JSON or its reader diagnostic."
  [body]
  (try {:kind :value :value (read-json body)}
       (catch #?(:cljs :default :default Exception) e
         (if-let [cause (ex-message e)]
           {:kind :bad-json :cause cause}
           {:kind :value :value nil}))))

(defn- parse-body
  "Pure: classify a response body as a value, opaque text or bad JSON."
  [{:keys [headers body]}]
  (cond
    (not (string? body)) {:kind :value :value body}
    (str/blank? body) {:kind :value :value nil}
    (json-type? (header headers "content-type")) (parse-json-body body)
    :else {:kind :raw :text body}))

(defn- declared [responses status]
  (let [s (str status)]
    (or (get responses s)
        (get responses (str (first s) "XX"))
        (get responses (str (first s) "xx")))))

(def ^:private refusal-types
  {400 :openapi/bad-request 401 :openapi/unauthenticated 403 :forbidden
   404 :openapi/not-found 409 :openapi/conflict 422 :openapi/bad-request
   429 :openapi/rate-limited})

(defn- problem-message
  "Pure: first nonempty problem field, else a bounded excerpt or HTTP status."
  [problem text status]
  (or (some #(some-> (get problem %) str not-empty) [:detail :title :message :error])
      (when text (subs text 0 (min 300 (count text))))
      (str "HTTP " status)))

(defn- refusal-type
  "L1 Promote: the refusal type of HTTP `status`: a known one, else a
   server or HTTP error by class."
  [status]
  (or (refusal-types status) (if (<= 500 status) :openapi/server-error :openapi/http-error)))

(defn- problem-data
  "L1 Promote: refusal data of `status` and the problem map, when there is one."
  [status problem]
  (cond-> {:status status}
    problem (assoc :problem problem)
    (seq (:errors problem)) (assoc :errors (:errors problem))))

(defn- problem-refusal
  "L1 Promote: translate a non-success status and parsed body to a refusal."
  [operation status {:keys [value text]}]
  (let [problem (when (map? value) value)]
    (answer/refusal (refusal-type status)
                    (str (:operation-id operation) ": " (problem-message problem text status))
                    (problem-data status problem))))

(defn- check-schema
  "Pure: validate a decoded value, treating an unusable schema as absent."
  [operation status form value]
  (let [sch (try (m/schema form) (catch #?(:cljs :default :default Exception) _ nil))]
    (if (or (nil? sch) (m/validate sch value))
      (answer/ok value)
      (answer/refusal :openapi/response-mismatch
                      (str (:operation-id operation) " answered " status " with a body its spec does not declare")
                      {:status status :problems (me/humanize (m/explain sch value))}))))

(defn- checked-declaration
  "Pure: accept a declared response or refuse with the known status keys."
  [operation status declaration]
  (if (nil? declaration)
    (answer/refusal :openapi/undeclared-response
                    (str (:operation-id operation) " answered " status ", which its spec does not declare")
                    {:status status :declared (vec (keys (:responses operation)))})
    (answer/ok declaration)))

(defn- malformed-body
  "Pure: the refusal for malformed JSON in a declared success response."
  [operation status cause]
  (answer/refusal :openapi/bad-response
                  (str (:operation-id operation) " answered " status " with malformed JSON")
                  {:status status :cause cause}))

(defn- success-body
  "Pure: interpret a declared success according to its parsed body tag."
  [operation {:keys [status headers]} declaration {:keys [kind value text cause]}]
  (case kind
    :bad-json (malformed-body operation status cause)
    :raw (answer/ok {:status status :media (header headers "content-type") :size (count text)})
    :value (if-let [form (:schema declaration)]
             (check-schema operation status form value)
             (answer/ok value))))

(defn- unreachable-reason
  "Pure: transport diagnostic when there is no usable HTTP response."
  [e]
  (if (nil? e) "no status" (or (ex-message e) (str (type e)))))

(defmulti decode-response
  "Pure: interpret the response by HTTP status class, or :unreachable."
  (fn [_ response]
    (let [status (:status response)]
      (if (or (:error response) (not (int? status))) :unreachable (quot status 100)))))

(defmethod decode-response :unreachable [operation response]
  (answer/refusal :openapi/unreachable
                  (str (:operation-id operation) ": no HTTP response (" (unreachable-reason (:error response)) ")")))

(defmethod decode-response 2 [operation {:keys [status] :as response}]
  (let [declaration (declared (:responses operation) status)
        parsed (parse-body response)]
    (answer/bind (checked-declaration operation status declaration)
                 #(success-body operation response % parsed))))

(defmethod decode-response :default [operation response]
  (problem-refusal operation (:status response) (parse-body response)))

(defn decode
  "Pure: total Answer for a ring-style response, whose body may be predecoded."
  [operation response]
  (try (decode-response operation response)
       (catch #?(:cljs :default :default Throwable) e
         (answer/refusal :openapi/decode-failed (str "could not decode the response: " (ex-message e))))))
