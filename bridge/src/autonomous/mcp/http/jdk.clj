(ns autonomous.mcp.http.jdk
  "HTTP socket adapter on the JDK's built-in server (JVM). Boundary only:
   every decision is autonomous.mcp.http/handle-request. A stream is a
   chunked response the handler leaves open: the pusher writes to it, and a
   failed write (the client went away) closes it."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.mcp.http :as http]
            [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io OutputStream)
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

(defn- exchange-sink
  "Sink {:send! :close!} writing SSE text to the open exchange `ex`."
  [^HttpExchange ex]
  (let [^OutputStream o (.getResponseBody ex)
        lock (Object.)]
    {:send!  (fn [^String text]
               (locking lock
                 (.write o (.getBytes text "UTF-8"))
                 (.flush o)))
     :close! (fn [] (locking lock (.close ex)))}))

(defn- open-stream! [state ^HttpExchange ex {:keys [status headers stream]}]
  (doseq [[k v] headers] (.add (.getResponseHeaders ex) k v))
  (.sendResponseHeaders ex status 0)
  (http/attach-stream! state stream (exchange-sink ex)))

(defn start!
  "Listen on `bind`:`port` (port 0 picks one). Answers Ok {:port :state
   :stop!} or the exposure refusal. Runs the stream pusher while it
   listens."
  [srv {:keys [bind port tokens] :or {bind "127.0.0.1"} :as cfg}]
  (if-let [r (http/exposure-refusal bind tokens)]
    r
    (try
      (let [state   (http/new-state)
            server  (HttpServer/create (InetSocketAddress. ^String bind (int port)) 0)
            handler (reify HttpHandler
                      (handle [_ ex]
                        (let [resp (http/handle-request srv state cfg (exchange->request ex))]
                          (if (:stream resp)
                            (try (open-stream! state ex resp)
                                 (catch java.io.IOException _client-gone (.close ex)))
                            (send! ex resp)))))]
        (.createContext server "/" handler)
        (.setExecutor server (Executors/newFixedThreadPool 16))
        (.start server)
        (let [stop-pusher! (http/start-pusher! srv state cfg)]
          (answer/ok {:port  (.getPort (.getAddress server))
                      :state state
                      :stop! (fn []
                               (stop-pusher!)
                               (http/close-streams! state)
                               (.stop server 1))})))
      (catch java.io.IOException bind-failure
        (http/bind-refusal bind port bind-failure)))))
