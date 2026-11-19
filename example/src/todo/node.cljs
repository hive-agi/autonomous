(ns todo.node
  "Headless runtime of the todo app (node). The same app logic a browser
   tab runs, with no DOM: what the e2e suite and a CI swarm drive."
  (:require [autonomous.ws :as aws]
            [goog.object :as gobj]
            [re-frame.core :as rf]
            [todo.agentic :as agentic]
            [todo.model]))

(defn main [& _]
  (rf/dispatch-sync [:todo/init])
  (agentic/install! true)
  ;; Production shape: with AUTONOMOUS_WS_URL and AUTONOMOUS_WS_TOKEN set,
  ;; the runtime dials the bridge itself (no nREPL needed).
  (let [env #(gobj/get js/process.env %)]
    (when-let [url (env "AUTONOMOUS_WS_URL")]
      (aws/connect! {:url url :token (env "AUTONOMOUS_WS_TOKEN")
                     :runtime-id (env "AUTONOMOUS_WS_RUNTIME_ID")
                     :label "todo node runtime"
                     :on-status (fn [st info] (js/console.log "autonomous ws" (name st) (pr-str info)))})))
  (js/console.log "todo runtime ready")
  ;; keep the process alive for the REPL runtime connection
  (js/setInterval (fn [] nil) 60000))
