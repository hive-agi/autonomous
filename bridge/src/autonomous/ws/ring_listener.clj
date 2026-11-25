(ns autonomous.ws.ring-listener
  "Ring 1.11+ websocket listener for the runtime endpoint. Loaded only when
   a Ring adapter (not http-kit) serves the endpoint, so the bridge does
   not depend on ring-websocket-protocols."
  (:require [autonomous.bridge.ws :as ws]
            [ring.websocket.protocols :as wsp]))

(defn listener
  "A listener driving one autonomous.bridge.ws link."
  [lopts]
  (let [link (atom nil)]
    (reify wsp/Listener
      (on-open [_ sock]
        (reset! link (ws/open-link lopts {:send!  (fn [text] (wsp/-send sock text))
                                          :close! (fn [code reason] (wsp/-close sock code reason))})))
      (on-message [_ _sock msg]
        (when (instance? CharSequence msg) (ws/on-text! lopts @link (str msg))))
      (on-pong [_ _sock _data] nil)
      (on-error [_ _sock _e] nil)
      (on-close [_ _sock _code _reason]
        (some-> @link ws/on-close!)))))
