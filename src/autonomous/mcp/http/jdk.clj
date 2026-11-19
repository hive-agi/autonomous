(ns autonomous.mcp.http.jdk
  "HTTP socket adapter on the JDK's built-in server (JVM). Boundary only:
   every decision is autonomous.mcp.http/handle-request."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.mcp.http :as http]
            [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.util.concurrent Executors)))

(defn- exchange->request [^HttpExchange ex]
  (let [in   (.getRequestBody ex)
        body (String. (.readNBytes in (inc http/max-body-bytes)) "UTF-8")]
    {:method  (.getRequestMethod ex)
     :path    (.getPath (.getRequestURI ex))
     :headers (into {} (map (fn [[k vs]] [(str/lower-case k) (first vs)])) (.getRequestHeaders ex))
     :body    body
     :too-big (> (count body) http/max-body-bytes)}))

(defn- send! [^HttpExchange ex {:keys [status headers body]}]
  (let [bytes (when body (.getBytes ^String body "UTF-8"))]
    (doseq [[k v] headers] (.add (.getResponseHeaders ex) k v))
    (.sendResponseHeaders ex status (if bytes (alength bytes) -1))
    (when bytes (with-open [o (.getResponseBody ex)] (.write o bytes)))
    (.close ex)))

(defn start!
  "Listen on `bind`:`port` (port 0 picks one). Answers Ok {:port :state
   :stop!} or the exposure refusal."
  [srv {:keys [bind port tokens] :or {bind "127.0.0.1"} :as cfg}]
  (if-let [r (http/exposure-refusal bind tokens)]
    r
    (try
      (let [state   (atom {:sessions {}})
            server  (HttpServer/create (InetSocketAddress. ^String bind (int port)) 0)
            handler (reify HttpHandler
                      (handle [_ ex]
                        (send! ex (http/handle-request srv state cfg (exchange->request ex)))))]
        (.createContext server "/" handler)
        (.setExecutor server (Executors/newFixedThreadPool 16))
        (.start server)
        (answer/ok {:port  (.getPort (.getAddress server))
                    :state state
                    :stop! (fn [] (.stop server 1))}))
      (catch java.io.IOException bind-failure
        (http/bind-refusal bind port bind-failure)))))
