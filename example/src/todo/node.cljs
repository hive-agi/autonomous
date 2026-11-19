(ns todo.node
  "Headless runtime of the todo app (node). The same app logic a browser
   tab runs, with no DOM: what the e2e suite and a CI swarm drive."
  (:require [re-frame.core :as rf]
            [todo.agentic :as agentic]
            [todo.model]))

(defn main [& _]
  (rf/dispatch-sync [:todo/init])
  (agentic/install! true)
  (js/console.log "todo runtime ready")
  ;; keep the process alive for the REPL runtime connection
  (js/setInterval (fn [] nil) 60000))
