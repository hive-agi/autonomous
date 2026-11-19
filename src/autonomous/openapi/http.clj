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

(defn request!
  "Send ring-style `req` ({:method :url :headers :body}); answer
   {:status :headers :body} with the body as a string, or {:error e}.
   :timeout-ms (default 30000) bounds the whole exchange."
  [{:keys [method url headers body timeout-ms] :or {timeout-ms 30000}}]
  (try
    (let [b    (-> (HttpRequest/newBuilder (URI/create url))
                   (.timeout (Duration/ofMillis timeout-ms))
                   (.method (str/upper-case (name method))
                            (if body
                              (HttpRequest$BodyPublishers/ofString ^String body)
                              (HttpRequest$BodyPublishers/noBody))))
          _    (doseq [[k v] headers :when (not (restricted (str/lower-case (str k))))]
                 (.header b (str k) (str v)))
          ^HttpResponse resp (.send ^HttpClient @client (.build b) (HttpResponse$BodyHandlers/ofString))]
      {:status  (.statusCode resp)
       :headers (into {} (map (fn [[k vs]] [(str/lower-case k) (first vs)]))
                      (.map (.headers resp)))
       :body    (.body resp)})
    (catch Exception e
      {:error e})))
