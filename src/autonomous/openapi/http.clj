(ns autonomous.openapi.http
  "Boundary: the default HTTP fn, (fn [request] response), on
   java.net.http (JVM and babashka both ship it). A transport failure is
   answered as {:error e}, never thrown, so the codec can refuse it.

   Inject your own fn with derive-tools opts :http (a stub in tests, a
   client with mTLS or a proxy in a cluster)."
  (:require [clojure.string :as str])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers)
           (java.time Duration)))

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 10))
             (.build))))

(def ^:private restricted
  "Headers java.net.http sets itself and refuses from callers."
  #{"host" "content-length" "connection" "upgrade" "expect"})

(defn- body-publisher
  "L3 Boundary: the publisher of request body text `body`, none when nil."
  [body]
  (if body
    (HttpRequest$BodyPublishers/ofString ^String body)
    (HttpRequest$BodyPublishers/noBody)))

(defn- http-request
  "L3 Boundary: the java.net.http request of ring-style `req`; headers the
   client sets itself are left out."
  ^HttpRequest [{:keys [method url headers body timeout-ms]}]
  (let [b (-> (HttpRequest/newBuilder (URI/create url))
              (.timeout (Duration/ofMillis timeout-ms))
              (.method (str/upper-case (name method)) (body-publisher body)))]
    (doseq [[k v] headers :when (not (restricted (str/lower-case (str k))))]
      (.header b (str k) (str v)))
    (.build b)))

(defn- ring-response
  "L3 Boundary: {:status :headers :body} of `resp`, header names lower-cased."
  [^HttpResponse resp]
  {:status  (.statusCode resp)
   :headers (into {} (map (fn [[k vs]] [(str/lower-case k) (first vs)])) (.map (.headers resp)))
   :body    (.body resp)})

(defn request!
  "L3 Boundary: send ring-style `req` ({:method :url :headers :body});
   answer {:status :headers :body} with the body as a string, or {:error e}.
   :timeout-ms (default 30000) bounds the whole exchange."
  [{:keys [timeout-ms] :or {timeout-ms 30000} :as req}]
  (try
    (ring-response (.send ^HttpClient @client (http-request (assoc req :timeout-ms timeout-ms))
                          (HttpResponse$BodyHandlers/ofString)))
    (catch Exception e
      {:error e})))
