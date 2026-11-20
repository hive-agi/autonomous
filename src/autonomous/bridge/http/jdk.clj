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
  (:import (java.io BufferedReader InputStream InputStreamReader Reader)
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

(def max-line-chars
  "The longest SSE line a remote stream may send: 1 MiB of characters. A
   longer line closes the stream; it is never buffered."
  (* 1024 1024))

(defn- after-cr!
  "Boundary: after a \r read from `r`, consume the \n of a \r\n pair; a
   lone \r leaves the next character unread. `r` must support mark."
  [^Reader r]
  (.mark r 1)
  (when-not (= 10 (.read r)) (.reset r)))

(defn- overflow
  "The refusal of a line past `cap` characters."
  [cap]
  (answer/refusal :channel/stream-overflow
                  (str "a remote stream line passed " cap " characters: the stream is closed")))

(defn- line-of
  "Boundary: Ok of the next line of `r` without its end, Ok nil at EOF, or
   a :channel/stream-overflow refusal once the line passes `cap`
   characters. A line ends at \r\n, \n or a lone \r, as SSE allows. Never
   holds more than `cap` characters."
  [^Reader r cap]
  (let [sb (StringBuilder.)]
    (loop []
      (let [c (.read r)]
        (cond
          (neg? c)              (answer/ok (when (pos? (.length sb)) (str sb)))
          (= 10 c)              (answer/ok (str sb))
          (= 13 c)              (do (after-cr! r) (answer/ok (str sb)))
          (>= (.length sb) cap) (overflow cap)
          :else                 (do (.append sb (char c)) (recur)))))))

(defn pump!
  "Boundary: hand reader `r`'s lines to `sink` until EOF, an error, or a
   line past `cap` characters (default max-line-chars), which is reported
   to the sink's optional :failed! as a refusal. The reader and the sink
   are closed once at the end. A stream needs its own thread."
  ([r sink] (pump! r sink max-line-chars))
  ([^Reader r sink cap]
   (try
     (with-open [r r]
       (loop []
         (let [a (line-of r cap)]
           (cond
             (answer/refused? a) (when-let [failed! (:failed! sink)] (failed! a))
             (some? (:ok a))     (do ((:text! sink) (str (:ok a) "\n")) (recur))))))
     (catch Exception _client-gone nil)
     (finally (try ((:close! sink)) (catch Exception _closed nil))))))

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
        (do (future (pump! (BufferedReader. (InputStreamReader. ^InputStream (.body resp) "UTF-8")) sink))
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
