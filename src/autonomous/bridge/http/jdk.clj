(ns autonomous.bridge.http.jdk
  "Boundary of the remote MCP channel: IHttpClient on java.net.http, which
   the JVM and babashka both ship. Every decision is above it
   (autonomous.bridge.http); a failure is an Answer, never a throw.

   Redirect-free (a token is not resent to a stranger) and bounded: a
   request times out, a stream timeout of 0 is the stream's own lifetime."
  (:require [autonomous.bridge.failure :as failure]
            [autonomous.bridge.port :as port]
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

(defn- body-of
  "The body of `req` as text: a string, or a map written as JSON."
  [req]
  (let [b (:body req)] (if (map? b) (rpc/json-text b) b)))

(defn- outcome-unknown
  "The refusal of a request that may have reached the remote: it may have
   acted on it, so a caller must not simply retry."
  [why]
  (answer/refusal :channel/outcome-unknown
                  (str "the request may have reached the remote but its reply " why
                       ": the outcome is unknown")))

(defn- transport-failed
  "The refusal of exception `e` thrown while building or sending a request,
   in the allowlisted text of autonomous.bridge.failure (an exception's own
   message may quote a header value or a url). :channel/transport-failed
   only when the failure precedes the request, so a retry is safe; any
   other is :channel/outcome-unknown."
  [^Exception e]
  (if (failure/before-request? e)
    (answer/refusal :channel/transport-failed (failure/message e))
    (outcome-unknown (str "failed (" (failure/message e) ")"))))

(def max-body-chars
  "The most characters a remote reply body may hold: 4 MiB."
  (* 4 1024 1024))

(defn- too-long
  "The refusal of a reply body past `cap` characters: its headers came, so
   the remote may have acted."
  [cap]
  (outcome-unknown (str "passed " cap " characters")))

(defn read-capped
  "Boundary: Ok of the text of `r`, or a :channel/outcome-unknown refusal
   once it passes `cap` characters. Never holds more than `cap` plus one
   read buffer."
  [^Reader r cap]
  (let [sb (StringBuilder.) buf (char-array 8192)]
    (loop []
      (let [n (.read r buf)]
        (cond
          (neg? n)                   (answer/ok (str sb))
          (< cap (+ (.length sb) n)) (too-long cap)
          :else                      (do (.append sb buf 0 n) (recur)))))))

(defn within
  "Boundary: the Answer of `read!` run on its own thread. Past `ms`, or when
   the reply breaks off, a :channel/outcome-unknown refusal after `close!`
   stops it: the headers arrived, so the request may have run."
  [ms read! close!]
  (let [f (future (try (read!) (catch Exception _closed (outcome-unknown "broke off"))))
        a (deref f ms ::late)]
    (if (= ::late a)
      (do (close!) (future-cancel f)
          (outcome-unknown (str "took longer than " ms " ms")))
      a)))

(declare line-of)

(defn- reply-event?
  "True when the SSE event `lines` holds a message answering request `id`."
  [lines id]
  (some #(= id (:id %)) (rpc/read-messages (str/join "\n" lines))))

(defn- sse-step
  "Pure: what reading `line` does to the SSE reply being gathered, whose
   current event holds `ev`, for request `id`: :done once a blank line ends
   the event answering `id`, :next-event at any other blank line, else
   :more."
  [ev line id]
  (cond
    (not (str/blank? line)) :more
    (reply-event? ev id)    :done
    :else                   :next-event))

(defn read-sse-reply
  "Boundary: Ok of SSE text read from `r` up to and including the event
   that answers request `id`, so a stream the remote keeps open after its
   reply is not waited on. The stream ending first, or passing `cap`
   characters, is a :channel/outcome-unknown refusal: the headers came, so
   the remote may have acted."
  [^Reader r cap id]
  (let [sb (StringBuilder.) cr? (volatile! false)]
    (loop [ev []]
      (let [a (line-of r cap cr?) l (:ok a)]
        (cond
          (answer/refused? a)                (too-long cap)
          (nil? l)                           (outcome-unknown "ended before its reply")
          (< cap (+ (.length sb) (count l))) (too-long cap)
          :else (do (.append (.append sb l) "\n")
                    (case (sse-step ev l id)
                      :done       (answer/ok (str sb))
                      :next-event (recur [])
                      :more       (recur (conj ev l)))))))))

(defn- headers-of
  "The headers of `resp`, names lower case, the first value of each."
  [^HttpResponse resp]
  (into {} (map (fn [[k vs]] [(str/lower-case k) (first vs)])) (.map (.headers resp))))

(defn- body-reader
  "The reading of a reply body from `r` for request `req` with headers `h`:
   up to the request's own message when it is SSE, else the whole capped
   body."
  [^Reader r req h]
  (if (and (rpc/sse-type? (get h "content-type")) (some? (:rpc-id req)))
    (read-sse-reply r max-body-chars (:rpc-id req))
    (read-capped r max-body-chars)))

(defn- read-body
  "Answer of the body of `resp` to `req` read within `ms`; a request with
   no :rpc-id (a notification) has no reply to wait for, so its body is
   closed unread and answers \"\"."
  [^InputStream in req h ms]
  (if (some? (:rpc-id req))
    (within ms #(with-open [r (BufferedReader. (InputStreamReader. in "UTF-8"))] (body-reader r req h))
            #(.close in))
    (do (.close in) (answer/ok ""))))

(defn- response-of
  "Answer {:status :headers :body} of `resp` to `req`, header names lower
   case, its body read within `ms` and max-body-chars."
  [^HttpResponse resp req ms]
  (let [h (headers-of resp)]
    (answer/let-answer [body (read-body (.body resp) req h ms)]
      (answer/ok {:status (.statusCode resp) :headers h :body body}))))

(defn- send
  "Answer of sending `req`, its body taken as text or JSON, the reply
   bounded in size and in time by its :timeout-ms."
  [req]
  (try
    (let [body (body-of req)
          ms   (long (or (:timeout-ms req) default-timeout-ms))
          pub  (if body (HttpRequest$BodyPublishers/ofString ^String body)
                   (HttpRequest$BodyPublishers/noBody))
          b    (builder req (:method req) pub ms)]
      (response-of (.send ^HttpClient @shared (.build b) (HttpResponse$BodyHandlers/ofInputStream)) req ms))
    (catch Exception e
      (transport-failed e))))

;; =============================================================================
;; The stream: a GET held open, its lines handed to a sink
;; =============================================================================

(def max-line-chars
  "The longest SSE line a remote stream may send: 1 MiB of characters. A
   longer line closes the stream; it is never buffered."
  (* 1024 1024))

(defn- skip-lf?
  "Boundary: true when character `c` is the \n of a \r\n pair, i.e. the
   previous line ended at \r (flag `cr?`); clears the flag either way."
  [cr? c]
  (let [skip? (and @cr? (= 10 c))]
    (vreset! cr? false)
    skip?))

(defn- overflow
  "The refusal of a line past `cap` characters."
  [cap]
  (answer/refusal :channel/stream-overflow
                  (str "a remote stream line passed " cap " characters: the stream is closed")))

(defn- line-of
  "Boundary: Ok of the next line of `r` without its end, Ok nil at EOF, or
   a :channel/stream-overflow refusal once the line passes `cap`
   characters. A line ends at \r\n, \n or a lone \r, as SSE allows: a line
   ending at \r sets `cr?`, and the next line then skips a leading \n, so
   nothing ever waits on a character after \r. Never holds more than `cap`
   characters."
  [^Reader r cap cr?]
  (let [sb (StringBuilder.)]
    (loop []
      (let [c (.read r)]
        (cond
          (neg? c)              (answer/ok (when (pos? (.length sb)) (str sb)))
          (skip-lf? cr? c)      (recur)
          (= 10 c)              (answer/ok (str sb))
          (= 13 c)              (do (vreset! cr? true) (answer/ok (str sb)))
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
       (loop [cr? (volatile! false)]
         (let [a (line-of r cap cr?)]
           (cond
             (answer/refused? a) (when-let [failed! (:failed! sink)] (failed! a))
             (some? (:ok a))     (do ((:text! sink) (str (:ok a) "\n")) (recur cr?))))))
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
            (answer/refusal :channel/stream-refused (str "the remote stream answered " (.statusCode resp))
                            {:status (.statusCode resp)}))
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
