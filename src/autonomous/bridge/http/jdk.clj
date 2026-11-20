(ns autonomous.bridge.http.jdk
  "Boundary of the remote MCP channel: IHttpClient on java.net.http, which
   the JVM and babashka both ship. Every decision is above it
   (autonomous.bridge.http); a failure is an Answer, never a throw.

   Redirect-free (a token is not resent to a stranger) and bounded: a
   request times out, a stream timeout of 0 is the stream's own lifetime."
  (:require [autonomous.bridge.port :as port]
            [autonomous.bridge.rpc :as rpc]
            [autonomous.domain.answer :as answer]
            [clojure.string :as str])
  (:import (java.io BufferedReader InputStreamReader)
           (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$Builder HttpRequest$BodyPublisher
                          HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers)
           (java.time Duration)))

(def default-timeout-ms 30000)

(def ^:private shared
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 10))
             (.followRedirects HttpClient$Redirect/NEVER)
             (.build))))

(def ^:private restricted
  "Headers java.net.http sets itself and refuses from callers."
  #{"host" "content-length" "connection" "upgrade" "expect"})

(defn- builder
  "Build an HTTP request, bounding the wait for response headers."
  ^HttpRequest$Builder [{:keys [url headers]} method ^HttpRequest$BodyPublisher publisher ms]
  (let [b (-> (HttpRequest/newBuilder (URI/create url))
              (.timeout (Duration/ofMillis (long (or ms default-timeout-ms))))
              (.method (str/upper-case (name method)) publisher))]
    (doseq [[k v] headers :when (not (restricted (str/lower-case (str k))))]
      (.header b (str k) (str v)))
    b))

(defn- response-of
  "Ok {:status :headers :body} of `resp`, header names lower case."
  [^HttpResponse resp]
  (answer/ok {:status  (.statusCode resp)
              :headers (into {} (map (fn [[k vs]] [(str/lower-case k) (first vs)])) (.map (.headers resp)))
              :body    (.body resp)}))

(defn- body-of
  "The body of `req` as text: a string, or a map written as JSON."
  [req]
  (let [b (:body req)] (if (map? b) (rpc/json-text b) b)))

(defn- transport-failed
  "The refusal of exception `e` thrown while building or sending a request.
   An IllegalArgumentException comes from building it (a header or url the
   JDK refuses) and its message quotes the offending value, which may be a
   credential: only its class is reported."
  [^Exception e]
  (answer/refusal :channel/transport-failed
                  (if (instance? IllegalArgumentException e)
                    (str "the request could not be built (" (.getName (class e)) ")")
                    (or (ex-message e) (str e)))))

(defn- send
  "Answer of sending `req`, its body taken as text or JSON."
  [req]
  (try
    (let [body (body-of req)
          pub  (if body (HttpRequest$BodyPublishers/ofString ^String body)
                   (HttpRequest$BodyPublishers/noBody))
          b    (builder req (:method req) pub (:timeout-ms req))]
      (response-of (.send ^HttpClient @shared (.build b) (HttpResponse$BodyHandlers/ofString))))
    (catch Exception e
      (transport-failed e))))

;; =============================================================================
;; The stream: a GET held open, its lines handed to a sink
;; =============================================================================

(defn- pump!
  "Read the stream body of `resp` into `sink` line by line until EOF or an
   error, then close it. A stream needs its own thread."
  [^HttpResponse resp sink]
  (try
    (with-open [r (BufferedReader. (InputStreamReader. (.body resp) "UTF-8"))]
      (loop []
        (when-let [l (.readLine r)]
          ((:text! sink) (str l "\n"))
          (recur))))
    (catch Exception _client-gone nil)
    (finally (try ((:close! sink)) (catch Exception _closed nil)))))

(defn- open-stream
  "Answer Ok {:close! (fn [])} of a held GET `req`; `sink` gets the text as
   it arrives."
  [req sink]
  (try
    (let [b (builder req :get (HttpRequest$BodyPublishers/noBody) (:stream-timeout-ms req))
          resp (.send ^HttpClient @shared (.build b) (HttpResponse$BodyHandlers/ofInputStream))]
      (if (<= 400 (.statusCode resp))
        (do (.close (.body resp))
            (answer/refusal :channel/stream-refused (str "the remote stream answered " (.statusCode resp))))
        (do (future (pump! resp sink))
            (answer/ok {:close! (fn [] (try (.close (.body resp)) (catch Exception _closed nil)))}))))
    (catch Exception e
      (transport-failed e))))

(defrecord JdkClient []
  port/IHttpClient
  (-request! [_ req] (send req))
  (-open-stream! [_ req sink] (open-stream req sink)))

(defrecord EnvironmentCredentials []
  port/ICredentialSource
  (-resolve-token! [_ {:keys [token-env]}]
    (answer/ok (System/getenv token-env))))

(defn credential-source
  "An ICredentialSource reading only the environment variable a reference names."
  []
  (->EnvironmentCredentials))

(defn client
  "The default IHttpClient of this runtime."
  [] (->JdkClient))
