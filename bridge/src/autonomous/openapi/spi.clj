(ns autonomous.openapi.spi
  "The :autonomous.openapi/spec source kind of autonomous.spi/derive-surface.

   autonomous.spi is grown on another branch (feat/middleware). Until it
   lands this namespace registers nothing and says so; once it does, the
   method installs itself. After the rebase this can become a plain
   (defmethod spi/derive-surface :autonomous.openapi/spec ...).

   source: a Bundle or a bare document; opts: as autonomous.openapi/surface."
  (:require [autonomous.openapi :as openapi]))

(def source-kind :autonomous.openapi/spec)

(defn derive-surface
  "L3 Source: the method body: Ok Surface of `source` with `opts`, or a
   refusal."
  [_kind source opts]
  (openapi/surface source opts))

(defn register!
  "L3 Boundary: install the method on autonomous.spi/derive-surface when
   autonomous.spi is on the classpath. Answers true when it did."
  []
  (if-let [mm (try (some-> (requiring-resolve 'autonomous.spi/derive-surface) deref)
                   (catch Exception _absent nil))]
    (do (.addMethod ^clojure.lang.MultiFn mm source-kind derive-surface) true)
    false))

(def registered?
  "Whether loading this namespace installed the method."
  (register!))
