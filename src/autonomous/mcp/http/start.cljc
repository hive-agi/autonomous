(ns autonomous.mcp.http.start
  "Start MCP over HTTP with the socket adapter this runtime has: http-kit
   on babashka, the JDK server on the JVM. ClojureWasm has a TCP listener
   (cljw.net/listen) but no HTTP server yet, so it answers a refusal that
   says to use stdio."
  (:require [autonomous.domain.answer :as answer]
            #?(:bb   [autonomous.mcp.http.kit :as adapter]
               :cljw [autonomous.domain.wire]
               :clj  [autonomous.mcp.http.jdk :as adapter])))

(defn start!
  "Ok {:port :state :stop!}, or a refusal."
  [srv cfg]
  #?(:cljw (answer/refusal :http/unsupported
                           "no HTTP server on ClojureWasm yet: serve over stdio, or run the bridge on bb or the JVM")
     :default (adapter/start! srv cfg)))
