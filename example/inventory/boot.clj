;; Loaded by `bb --init boot.clj nrepl-server ...` before the nREPL starts:
;; the service is up, its tools registered, by the time a bridge connects.
(require 'inventory.system)
(inventory.system/start!)
(binding [*out* *err*]
  (println "inventory: up; bridge it with  bb serve --clj --dir example/inventory"))
