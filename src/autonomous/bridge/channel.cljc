(ns autonomous.bridge.channel
  "Channel construction by target kind (OCP: a new transport, e.g. a
   websocket the page dials out on, is one defmethod).

   The nREPL adapter is chosen per runtime: the JVM speaks nrepl.core;
   babashka and ClojureWasm speak bencode through bb-mcp's byte channel."
  (:require [autonomous.bridge.local :as local]
            #?(:bb   [autonomous.bridge.nrepl-wire :as nrepl]
               :cljw [autonomous.bridge.nrepl-wire :as nrepl]
               :clj  [autonomous.bridge.nrepl :as nrepl])))

(defmulti make
  "An IRuntimeChannel + IRuntimeDirectory for `target`."
  :target/kind)

(defmethod make :nrepl [target] (nrepl/channel target))

(defmethod make :local [target] (local/channel target))

;; The runtime dials out to the bridge (production, no nREPL). Loaded on
;; demand: token hashing needs SHA-256, which ClojureWasm does not carry.
(defmethod make :ws [target]
  ((requiring-resolve 'autonomous.bridge.ws/channel) target))

;; A remote app over its own /mcp by URL: no nREPL, no inbound port. Loaded
;; on demand: it needs java.net.http, which ClojureWasm does not carry.
(defmethod make :http [target]
  ((requiring-resolve 'autonomous.bridge.http.channel/channel) target))

(defmethod make :default [target]
  (throw (ex-info (str "no channel for target kind " (:target/kind target))
                  {:target/kind (:target/kind target)})))
