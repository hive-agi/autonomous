(ns autonomous.hive.apps
  "Apps the hive addon reaches, and who acts on them.

   Collect: `read-dir-facts` looks at each app directory (the port files a
   hive-compose program writes when compose starts it).
   Promote: `->target` and `grant-for` are pure.

   App config (one entry of :autonomous/apps):
     {:app/id     \"todo\"
      :app/dir    \"~/PP/todo\"          ; finds shadow-cljs.edn + nREPL port file
      :app/build  :app                  ; optional shadow build
      :app/target {...}                 ; or an explicit Target instead of :app/dir
      :app/scopes [:read :write]        ; default grant for every hive agent
      :app/grants {\"ling-reviewer\" [:read]}   ; per-agent override
      :app/ledger {:ledger/kind :datahike}}    ; optional, default memory"
  (:require [autonomous.config :as config]
            [autonomous.domain.scope :as scope]
            [clojure.string :as str]))

(defn expand-home [path]
  (if (and (string? path) (str/starts-with? path "~"))
    (str (System/getProperty "user.home") (subs path 1))
    path))

(defn read-dir-facts
  "Collect: the app directory facts a target derives from, or nil."
  [app]
  (some-> (:app/dir app) expand-home config/read-app-dir))

(defn ->target
  "Promote: the Target of `app`, from :app/target or its directory facts."
  [app dir-facts]
  (or (:app/target app)
      (into {} (remove (comp nil? val))
            (config/derive-target {:target-id (:app/id app)
                                   :build     (some-> (:app/build app) name)}
                                  dir-facts))))

(defn grant-for
  "The grant `actor` acts with on `app`: its own entry in :app/grants, else
   :app/scopes, else read and write."
  [app actor]
  {:actor  actor
   :scopes (scope/normalize (or (get-in app [:app/grants actor])
                                (:app/scopes app)
                                [:read :write]))})
