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

(defn- fill-path [path args params]
  (reduce (fn [p {:keys [name key in]}]
            (if (= "path" in)
              (str/replace p (str "{" name "}") (url-encode (text (get args key))))
              p))
          path params))

(defn- query-string [args params]
  (let [pairs (for [{:keys [name key in]} params
                    :when (= "query" in)
                    :let  [v (get args key)]
                    :when (some? v)
                    x     (if (sequential? v) v [v])]
                (str (url-encode name) "=" (url-encode (text x))))]
    (when (seq pairs) (str/join "&" pairs))))

(defn- join-url [base path]
  (str (str/replace (str base) #"/+$" "") path))

(defn- body-of [{:keys [body]} args]
  (case (:mode body)
    :flat   (let [ks (set (:keys body))
                  b  (into {} (filter (fn [[k v]] (and (contains? ks k) (some? v)))) args)]
              (when (or (seq b) (:required? body)) b))
    :nested (:body args)
    nil))

(defn encode
  "Ring-style request for `operation` called with decoded `args`, sent to
   `base-url`: {:method :get :url \"https://...\" :headers {...} :body
   \"json\"|nil}. Header names are lower case."
  [operation base-url args]
  (let [{:keys [method path params body]} operation
        q       (query-string args params)
        payload (body-of operation args)
        headers (into {"accept" "application/json, application/problem+json"}
                      (for [{:keys [name key in]} params
                            :when (and (= "header" in) (some? (get args key)))]
                        [(str/lower-case name) (text (get args key))]))]
    {:method  (keyword method)
     :url     (cond-> (join-url base-url (fill-path path args params)) q (str "?" q))
     :headers (cond-> headers (some? payload) (assoc "content-type" (:media body)))
     :body    (when (some? payload) (write-json payload))}))

;; -----------------------------------------------------------------------------
;; decode

(defn- header [headers k]
  (some (fn [[hk v]] (when (= k (str/lower-case (name hk))) (if (sequential? v) (first v) v))) headers))

(defn- json-type? [ct]
  (let [ct (str/lower-case (str ct))]
    (or (str/includes? ct "application/json") (str/includes? ct "+json"))))

(defn- parse-body
  "{:ok parsed} / {:raw text} / {:bad message} of a response body."
  [{:keys [headers body]}]
  (cond
    (or (nil? body) (and (string? body) (str/blank? body))) {:ok nil}
    (not (string? body))                                     {:ok body}
    (json-type? (header headers "content-type"))
    (try {:ok (read-json body)}
         (catch #?(:cljs :default :default Exception) e {:bad (ex-message e)}))
    :else {:raw body}))

(defn- declared [responses status]
  (let [s (str status)]
    (or (get responses s)
        (get responses (str (first s) "XX"))
        (get responses (str (first s) "xx")))))

(def ^:private refusal-types
  {400 :openapi/bad-request 401 :openapi/unauthenticated 403 :forbidden
   404 :openapi/not-found 409 :openapi/conflict 422 :openapi/bad-request
   429 :openapi/rate-limited})

(defn- problem-refusal [operation status parsed]
  (let [p   (when (map? (:ok parsed)) (:ok parsed))
        msg (or (some-> (:detail p) str not-empty)
                (some-> (:title p) str not-empty)
                (some-> (:message p) str not-empty)
                (some-> (:error p) str not-empty)
                (some-> (:raw parsed) str (subs 0 (min 300 (count (:raw parsed)))))
                (str "HTTP " status))]
    (answer/refusal (or (refusal-types status)
                        (if (<= 500 status) :openapi/server-error :openapi/http-error))
                    (str (:operation-id operation) ": " msg)
                    (cond-> {:status status}
                      p                    (assoc :problem p)
                      (seq (:errors p))    (assoc :errors (:errors p))))))

(defn- check-schema [operation status form value]
  (let [sch (try (m/schema form) (catch #?(:cljs :default :default Exception) _ nil))]
    (cond
      (nil? sch)               (answer/ok value)
      (m/validate sch value)   (answer/ok value)
      :else (answer/refusal :openapi/response-mismatch
                            (str (:operation-id operation) " answered " status
                                 " with a body its spec does not declare")
                            {:status status :problems (me/humanize (m/explain sch value))}))))

(defn decode
  "Answer for `response` ({:status :headers :body}, body a string or
   already parsed) of a call to `operation`. A response with :error (the
   transport failed) is :openapi/unreachable. Never throws."
  [operation response]
  (try
    (let [status (:status response)]
      (cond
        (or (:error response) (not (int? status)))
        (answer/refusal :openapi/unreachable
                        (str (:operation-id operation) ": no HTTP response ("
                             (let [e (:error response)]
                               (cond (nil? e)        "no status"
                                     (ex-message e)  (ex-message e)
                                     :else           (str (type e))))
                             ")"))

        (<= 200 status 299)
        (let [d      (declared (:responses operation) status)
              parsed (parse-body response)]
          (cond
            (nil? d)
            (answer/refusal :openapi/undeclared-response
                            (str (:operation-id operation) " answered " status ", which its spec does not declare")
                            {:status status :declared (vec (keys (:responses operation)))})
            (:bad parsed)
            (answer/refusal :openapi/bad-response
                            (str (:operation-id operation) " answered " status " with malformed JSON")
                            {:status status :cause (:bad parsed)})
            (contains? parsed :raw)
            (answer/ok {:status status :media (header (:headers response) "content-type")
                        :size (count (:raw parsed))})
            (:schema d) (check-schema operation status (:schema d) (:ok parsed))
            :else       (answer/ok (:ok parsed))))

        :else (problem-refusal operation status (parse-body response))))
    (catch #?(:cljs :default :default Throwable) e
      (answer/refusal :openapi/decode-failed (str "could not decode the response: " (ex-message e))))))
