(ns orders.main
  "Composition root of the orders service: wires the in-memory repository,
   the application service and the agent surface, seeds two demo orders,
   and starts the nREPL the autonomous bridge reaches the service through.

     clojure -M:example/orders [DIR]

   writes DIR/.nrepl-port (default example/orders, relative to the repo
   root) and serves until killed. The nREPL binds 127.0.0.1 only."
  (:require [clojure.java.io :as io]
            [nrepl.server :as nrepl]
            [orders.agentic :as agentic]
            [orders.service :as svc]
            [orders.store :as store]))

(def seed
  "Demo orders placed at startup, so an agent has something to read."
  [{:customer "ada" :lines [{:sku "kbd-60" :qty 1 :unit-cents 8900}
                            {:sku "cable-c" :qty 2 :unit-cents 1200}]}
   {:customer "grace" :lines [{:sku "mouse-v" :qty 1 :unit-cents 4500}]}])

(defn system
  "A running orders application: {:env env}, surface installed, seeded."
  []
  (let [env (svc/env {:repo (store/memory)})]
    (run! #(svc/place! env %) seed)
    (agentic/install! env)
    {:env env}))

(defn start-nrepl!
  "Start an nREPL on 127.0.0.1 at a free port and write it to
   `dir`/.nrepl-port; the file is removed when the JVM exits. Answers
   {:server s :port p :port-file path}."
  [dir]
  (let [server    (nrepl/start-server :bind "127.0.0.1" :port 0)
        port      (:port server)
        port-file (io/file dir ".nrepl-port")]
    (io/make-parents port-file)
    (spit port-file (str port))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (io/delete-file port-file true))))
    {:server server :port port :port-file (str port-file)}))

(defn -main
  "Start the service and its nREPL; serve until the process is killed."
  [& [dir]]
  (system)
  (let [{:keys [port port-file]} (start-nrepl! (or dir "example/orders"))]
    (println (str "orders: nREPL on 127.0.0.1:" port " (" port-file ")"))
    (println "orders: bridge it with  clojure -M:serve --clj --dir example/orders")
    @(promise)))
