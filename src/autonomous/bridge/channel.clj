(ns autonomous.bridge.channel
  "Channel construction by target kind (OCP: a new transport, e.g. a
   websocket the page dials out on, is one defmethod)."
  (:require [autonomous.bridge.local :as local]
            [autonomous.bridge.nrepl :as nrepl]))

(defmulti make
  "An IRuntimeChannel + IRuntimeDirectory for `target`."
  :target/kind)

(defmethod make :nrepl [target] (nrepl/channel target))

(defmethod make :local [target] (local/channel target))

(defmethod make :default [target]
  (throw (ex-info (str "no channel for target kind " (:target/kind target))
                  {:target target})))
