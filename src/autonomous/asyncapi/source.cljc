(ns autonomous.asyncapi.source
  "A derived surface's live half: the taps of the resources, and teardown.

   `start!` subscribes a tap per resource and answers the running surface:

     {:port port :taps {uri {:tap atom :store atom}} :subs [id ...]}

   Resources are registered into a registry instance by `install!`, which is
   the only function here that writes. `stop!` unsubscribes and closes the
   port; a surface removed from a runtime must be stopped or its subscription
   leaks.

   Nothing is registered for a subject that could not be derived: the caller
   derives first (autonomous.asyncapi.derive/taps) and gets a refusal before
   any port is opened."
  (:require [autonomous.asyncapi.port :as port]
            [autonomous.asyncapi.subject :as subject]
            [autonomous.asyncapi.tap :as tap]
            [autonomous.domain.answer :as answer]
            [malli.core :as m]))

(defn- validator
  "Fn of a payload answering a malli explanation or nil, for malli form
   `schema`; nil when there is no schema."
  [schema]
  (when schema
    (let [schema (try (m/schema schema) (catch #?(:clj Throwable :cljs :default) _ nil))]
      (when schema (m/explainer schema)))))

(defn- handle
  "Handler of tap atom `ta`: admit into it, ignoring a non-matching subject."
  [ta filter]
  (fn [{:keys [subject payload at]}]
    (when (subject/matches? filter subject)
      (swap! ta (fn [t] (first (tap/admit t {:subject subject :payload payload :at at})))))))

(defn subscribe!
  "Subscribe every resource of `surface` on `port`; answers Ok of the
   running surface with each resource's tap atom and cursor store."
  [port surface]
  (try
    (answer/ok
     (reduce (fn [acc [uri desc]]
               (let [ta (atom (tap/tap (:subject desc) (:capacity desc) (validator (:schema desc))))
                     id ((:subscribe port) (:subject desc) (handle ta (:subject desc)))]
                 (-> acc
                     (assoc-in [:taps uri] {:tap ta :store (atom {})})
                     (update :subs conj id))))
             {:port port :taps {} :subs []}
             (:resources surface)))
    (catch #?(:clj Throwable :cljs :default) e
      (answer/from-exception e))))

(defn stop!
  "Unsubscribe every subscription of running surface `live`, then close the
   port. Answers true."
  [live]
  (doseq [id (:subs live)] (try ((:unsubscribe (:port live)) id)
                                (catch #?(:clj Throwable :cljs :default) _ nil)))
  (port/close (:port live))
  true)
