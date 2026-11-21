(ns autonomous.thenable
  "L0 host: values still being computed. A JS promise on cljs; a future or
   promise (and, on the JVM, any java.util.concurrent.Future) elsewhere.
   No JVM-only class is named outside the :clj-only branch, so the
   namespace compiles on every runtime."
  (:require [clojure.string :as str]))

#?(:cljs nil
   :default (def ^:private promise-type
              "The class `promise` instances have on this runtime."
              (type (promise))))

(defn thenable?
  "L0 host: is `x` a value still being computed?"
  [x]
  ;; Why "then" by name: memory 20261120025625-307f0e9a.
  #?(:cljs    (and (some? x) (fn? (unchecked-get x "then")))
     :cljw    (or (future? x) (instance? promise-type x))
     :bb      (or (future? x) (instance? promise-type x))
     :default (or (future? x) (instance? promise-type x)
                  (instance? java.util.concurrent.Future x))))

(defn unwrap-failure
  "L0 host: what a computation threw, given what deref of its future threw.
   Futures wrap a failure in java.util.concurrent.ExecutionException, once
   per future; every layer is removed. Matched by class name."
  [t]
  (if (and (ex-cause t) (str/ends-with? (str (type t)) "java.util.concurrent.ExecutionException"))
    (recur (ex-cause t))
    t))

#?(:cljs nil
   :default
   (defn- settled
     "L0 host (blocking): the value of `x` once every thenable layer has one."
     [x]
     (if (thenable? x) (recur (deref x)) x)))

(defn chain
  "L0 host: a thenable of (f v), v being `x`'s eventual value (a plain `x`
   is its own value). What `f` answers is flattened when it is a thenable.
   Answers at once: a JS promise on cljs, a future elsewhere."
  [x f]
  #?(:cljs    (.then (js/Promise.resolve x) f)
     :default (future (settled (f (settled x))))))

(defn attempt
  "L0 host: a thenable of the eventual value of (thunk), or of (on-error e)
   when the thunk throws e or its value fails with e. Answers at once: a JS
   promise on cljs, a future elsewhere."
  [thunk on-error]
  #?(:cljs    (.catch (js/Promise. (fn [ok _] (ok (thunk)))) on-error)
     :default (future (try (settled (thunk))
                           (catch Throwable t (on-error (unwrap-failure t)))))))
