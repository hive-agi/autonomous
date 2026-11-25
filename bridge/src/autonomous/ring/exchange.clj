(ns autonomous.ring.exchange
  "The two crossings between an agent call and the app's Ring handler, as
   pure functions: a tool call becomes the synthetic Ring request a browser
   or curl would have sent (`->request`), and the Ring response becomes an
   Answer (`response->answer`). Runs on the JVM and on babashka: only
   java.io and java.net classes both carry."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.mcp.json :as json]
            [clojure.string :as str])
  (:import (java.io ByteArrayInputStream InputStream)
           (java.net URLEncoder)))

;; =============================================================================
;; Call to request
;; =============================================================================

(defn- encode [x]
  (str/replace (URLEncoder/encode (str (if (keyword? x) (name x) x)) "UTF-8") "+" "%20"))

(defn- param-str [v]
  (cond (keyword? v) (name v)
        (nil? v)     ""
        :else        (str v)))

(defn fill-path
  "Route `template` with its :param / {param} / *rest segments replaced by
   the URL-encoded values of `path-args` (keyword keys)."
  [template path-args]
  (str/join "/"
            (map (fn [seg]
                   (let [k (cond (str/starts-with? seg ":") (subs seg 1)
                                 (and (str/starts-with? seg "{") (str/ends-with? seg "}"))
                                 (subs seg 1 (dec (count seg)))
                                 (str/starts-with? seg "*") (subs seg 1))]
                     (if (and k (contains? path-args (keyword k)))
                       (encode (get path-args (keyword k)))
                       seg)))
                 (str/split template #"/" -1))))

(defn- form-pairs
  "[[k v] ...] of `m`, a sequential value repeated once per element."
  [m]
  (for [[k v] m
        x (if (sequential? v) v [v])]
    [(name k) (param-str x)]))

(defn- url-encoded [m]
  (str/join "&" (map (fn [[k v]] (str (encode k) "=" (encode v))) (form-pairs m))))

(defn- string-params [m]
  (reduce (fn [acc [k v]]
            (update acc k #(cond (nil? %) v (vector? %) (conj % v) :else [% v])))
          {} (form-pairs m)))

(defn split-args
  "{location {arg value}} of decoded `args` by `placement` ({arg
   location}); an argument with no placement is dropped (the input schema
   already refused unknown keys it closes over)."
  [placement args]
  (reduce (fn [acc [k v]]
            (if-let [loc (get placement k)] (assoc-in acc [loc k] v) acc))
          {} args))

(defn- bytes-of [^String s] (.getBytes s "UTF-8"))

(defn- payload
  "L3 Codec: choose the content type and serialized body."
  [{:keys [form body body-whole]}]
  (cond
    (seq form)                   ["application/x-www-form-urlencoded" (url-encoded form)]
    (contains? body-whole :body) ["application/json" (json/write (:body body-whole))]
    (seq body)                   ["application/json" (json/write body)]))

(defn- with-payload
  "L3 Codec: attach the body and content headers for Ring middleware."
  [request params]
  (if-let [[ctype text] (payload params)]
    (let [n (alength ^bytes (bytes-of text))]
      (-> request
          (assoc :body text :content-type ctype :content-length n :character-encoding "UTF-8")
          (update :headers assoc "content-type" ctype "content-length" (str n))))
    request))

(defn request-data
  "L3 Codec: the Ring request as data, with any encoded body still text."
  [tool args]
  (let [{:keys [path query form] :as params} (split-args (:ring/placement tool) args)
        qs (when (seq query) (url-encoded query))]
    (cond-> {:request-method (:ring/method tool)
             :uri (fill-path (:ring/path tool) (or path {}))
             :scheme :http :server-name "autonomous" :server-port 0
             :remote-addr "autonomous" :protocol "HTTP/1.1"
             :headers {"accept" "application/json"}
             :query-params (string-params query) :form-params (string-params form)
             :params (merge (string-params query) (string-params form))
             :autonomous/call {:tool (:name tool) :args args}}
      qs (assoc :query-string qs)
      true (with-payload params))))

(defn- stream-request
  "L3 boundary: materialize Ring's one-shot request body stream."
  [request]
  (cond-> request (:body request) (update :body #(ByteArrayInputStream. (bytes-of %)))))

(defn ->request
  "L3 Ring compatibility: prepare a request with a Ring body stream."
  [tool args]
  (stream-request (request-data tool args)))

(defn default-principal
  "The default identity crossing: the grant rides on the request as
   :autonomous/grant ({:actor :scopes}); the app's own auth decides what it
   means."
  [grant request]
  (assoc request :autonomous/grant grant))

;; =============================================================================
;; Response to answer
;; =============================================================================

(defn- header [headers k]
  (some (fn [[hk v]] (when (= (str/lower-case (name hk)) k) v)) headers))

(defn- parse-text [ctype ^String s]
  (cond
    (str/blank? s) nil
    (and ctype (str/includes? ctype "json"))
    (try (json/read-json s) (catch Exception _not-json s))
    :else s))

(defn body-value
  "The value of Ring response `body` with content type `ctype`: text is
   parsed as JSON when the type says JSON, streams and bytes are read,
   data (a handler that returns maps with no encoder) passes through."
  [ctype body]
  (cond
    (nil? body)                 nil
    (string? body)              (parse-text ctype body)
    (instance? InputStream body) (parse-text ctype (slurp body))
    (bytes? body)               (parse-text ctype (String. ^bytes body "UTF-8"))
    (coll? body)                body
    :else                       (str body)))

(defn- error-message [status v]
  (or (when (map? v) (some #(let [x (get v %)] (when (string? x) x)) [:message :error :detail]))
      (when (string? v) v)
      (str "the app answered HTTP " status)))

(defn response->answer
  "The default Answer of Ring `response`: Ok of the body for a 2xx, a
   refusal of type :ring/http-<status> (with the body as data) for
   anything else. Routes whose 2xx can still mean failure (an error
   envelope) override it with `:autonomous {:result f}`."
  [{:keys [status headers body]}]
  (let [status (or status 200)
        v      (body-value (header headers "content-type") body)]
    (if (<= 200 status 299)
      (answer/ok v)
      (answer/refusal (keyword "ring" (str "http-" status))
                      (error-message status v)
                      {:status status :body v}))))

(defn invoke-request
  "L3 boundary: apply the principal port, run the human handler, and read
   any response stream before passing the result to the pure decoder.
   A custom :ring/result retains the raw Ring response for compatibility."
  [tool input grant]
  (let [principal (:ring/principal tool default-principal)
        response ((:ring/handler tool) (principal grant (stream-request input)))]
    (answer/ok (cond-> response
                 (and (not (:ring/result tool)) (instance? InputStream (:body response)))
                 (update :body slurp)))))

(def route-codec
  "L3 Codec for an in-process Ring route."
  {:encode (fn [tool args]
             (if (and (map? args) (string? (:ring/path tool)) (keyword? (:ring/method tool)))
               (answer/ok (request-data tool args))
               (answer/refusal :ring/invalid-call "expected route metadata and a map of arguments")))
   :decode (fn [tool response]
             (if (and (map? response) (int? (get response :status 200)))
               ((:ring/result tool response->answer) response)
               (answer/refusal :ring/invalid-response "expected a Ring response map")))})
