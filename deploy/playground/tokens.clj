(ns tokens
  "`bb -cp src:deploy/playground -m tokens OUT-DIR`: mint the playground's
   tokens and write, into OUT-DIR (created mode 0700):

     autonomous.edn  the bridge config: one websocket target \"backend\",
                     the runtime token and the public agent token, stored
                     as SHA-256 only
     backend-token   the backend's runtime token (secret)
     agent-token     the agent token every visitor gets (public by design:
                     it grants read and write on the todo tools, nothing more)

   Then create the Secret from that directory (see README.md) and delete it."
  (:require [autonomous.bridge.auth :as auth]
            [autonomous.domain.wire :as wire]
            [babashka.fs :as fs]))

(defn config
  "The bridge's autonomous.edn for runtime token entry `backend` and agent
   token entry `agent`."
  [backend agent]
  {:targets [{:target/kind :ws :target/id "backend"}]
   :ws      {:bind "0.0.0.0" :port 7890}
   :tokens  [backend agent]})

(defn -main [& [out]]
  (when-not out
    (binding [*out* *err*] (println "usage: tokens OUT-DIR"))
    (System/exit 2))
  (let [backend (auth/mint)
        agent   (auth/mint)
        dir     (fs/create-dirs out)]
    (fs/set-posix-file-permissions dir "rwx------")
    (spit (str (fs/path dir "autonomous.edn"))
          (wire/print-edn (config (auth/token-entry backend "backend" [:runtime])
                                  (auth/token-entry agent "playground-agent" [:read :write]))))
    (spit (str (fs/path dir "backend-token")) backend)
    (spit (str (fs/path dir "agent-token")) agent)
    (doseq [f ["autonomous.edn" "backend-token" "agent-token"]]
      (fs/set-posix-file-permissions (fs/path dir f) "rw-------"))
    (println "wrote" (str dir) ": autonomous.edn backend-token agent-token")))
