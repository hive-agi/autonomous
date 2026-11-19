(ns autonomous.hive.apps
  "Apps the hive addon reaches, and who acts on them.

   Collect: `read-dir-facts` looks at each app directory (the port files a
   hive-compose program writes when compose starts it).
   Promote: `->target`, `grant-for`, `fleet-grant`, `narrow` and
   `scope-request` are pure.

   App config (one entry of :autonomous/apps):
     {:app/id     \"todo\"
      :app/dir    \"~/PP/todo\"          ; finds shadow-cljs.edn + nREPL port file
      :app/build  :app                  ; optional shadow build
      :app/target {...}                 ; or an explicit Target instead of :app/dir
      :app/scopes [:read :write]        ; default grant for every hive agent
      :app/grants {\"ling-reviewer\" [:read]}   ; per-agent override
      :app/call-timeout-ms 30000}       ; optional

   Every app is one member of a single fleet (autonomous.bridge.fleet): an
   agent has ONE session across the fleet, whose scopes are the union of its
   grants, and each request is narrowed to the agent's grant on the app it
   goes to (`scope-request`), so a per-app grant still holds."
  (:require [autonomous.bridge.client :as client]
            [autonomous.config :as config]
            [autonomous.domain.scope :as scope]
            [clojure.set :as set]
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
  "Promote: the Target of `app`, from :app/target or its directory facts.
   Its :target/id is always the app id, so the fleet, its tool prefixes and
   the ledger all name the app the way agents do."
  [app dir-facts]
  (assoc (or (:app/target app)
             (into {} (remove (comp nil? val))
                   (config/derive-target {:target-id (:app/id app)
                                          :build     (some-> (:app/build app) name)}
                                         dir-facts)))
         :target/id (str (:app/id app))))

(defn grant-for
  "The grant `actor` acts with on `app`: its own entry in :app/grants, else
   :app/scopes, else read and write."
  [app actor]
  {:actor  actor
   :scopes (scope/normalize (or (get-in app [:app/grants actor])
                                (:app/scopes app)
                                [:read :write]))})

(defn fleet-grant
  "The grant of `actor`'s one session across `apps`: the union of its grants
   on each. What it may do on one app is narrowed per request."
  [apps actor]
  {:actor  actor
   :scopes (into #{} (mapcat #(:scopes (grant-for % actor))) apps)})

(defn narrow
  "Scopes `requested` limited to `granted`. :admin on either side stands for
   every scope, so it keeps the other side whole."
  [requested granted]
  (let [r (scope/normalize requested)
        g (scope/normalize granted)]
    (cond (contains? g scope/admin) r
          (contains? r scope/admin) g
          :else (set/intersection r g))))

(defn scope-request
  "Request `request` bound for `app`, its scopes narrowed to the grant its
   actor holds on that app. The bridge's own journal reader is left alone:
   it only reads, and draining the ledger must not depend on app grants."
  [app request]
  (if (= (:actor client/bridge-grant) (:actor request))
    request
    (update request :scopes narrow (:scopes (grant-for app (:actor request))))))
