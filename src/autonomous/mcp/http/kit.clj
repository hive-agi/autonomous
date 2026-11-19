(ns autonomous.mcp.http.kit
  "HTTP socket adapter on http-kit, which babashka carries built in.
   Boundary only: every decision is autonomous.mcp.http/handle-request."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.mcp.http :as http]
            [clojure.string :as str]
            [org.httpkit.server :as kit]))

(defn- ring->request [{:keys [request-method uri headers body]}]
  (let [text (if body (slurp body) "")]
    {:method  (str/upper-case (name request-method))
     :path    uri
     :headers headers                       ; http-kit lower-cases header names
     :body    text
     :too-big (> (count text) http/max-body-bytes)}))

(defn- ->ring [{:keys [status headers body]}]
  {:status status :headers (or headers {}) :body body})

(defn start!
  "Listen on `bind`:`port` (port 0 picks one). Answers Ok {:port :state
   :stop!} or the exposure refusal."
  [srv {:keys [bind port tokens] :or {bind "127.0.0.1"} :as cfg}]
  (if-let [r (http/exposure-refusal bind tokens)]
    r
    (let [state  (atom {:sessions {}})
          server (kit/run-server (fn [req] (->ring (http/handle-request srv state cfg (ring->request req))))
                                 {:ip bind :port port :legacy-return-value? false})]
      (answer/ok {:port  (kit/server-port server)
                  :state state
                  :stop! (fn [] @(kit/server-stop! server))}))))
