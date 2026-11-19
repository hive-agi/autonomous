(ns autonomous.asyncapi.memory
  "An in-memory bus: the port a test (or a demo) uses with no server. Pure
   apart from the atom it keeps its subscriptions in, so one process can hold
   many.

     (def b (memory/bus))
     (port/connect :asyncapi.memory/bus {:bus b :at (fn [] 1)})
     (memory/publish! b \"sisf.payment.monero.entry\" {:amount \"10.00\"})

   Delivery is SYNCHRONOUS and runs outside the atom's swap, so a handler
   that publishes does not re-enter a swap in progress. A wildcard
   subscription is matched per message with autonomous.asyncapi.subject.

   `:at` is the clock, called per delivery; a test passes (fn [] 7) to make
   timestamps deterministic instead of sleeping."
  (:require [autonomous.asyncapi.port :as port]
            [autonomous.asyncapi.subject :as subject]
            [autonomous.domain.answer :as answer]))

(defn bus
  "A fresh bus, with no subscriptions."
  []
  (atom {:next 0 :subs {} :published []}))

(defn subscribers
  "Subscription ids of the live subscriptions matching `subject`."
  [b subj]
  (into []
        (keep (fn [[id s]] (when (subject/matches? (:filter s) subj) id)))
        (:subs @b)))

(defn subscribers-of
  "Every live subscription as {:id :filter}."
  [b]
  (mapv (fn [[id s]] {:id id :filter (:filter s)}) (:subs @b)))

(defn publish!
  "Deliver `payload` on `subject` to every matching subscriber, in subscribe
   order. Answers the number of handlers run."
  [b subj payload]
  (swap! b update :published conj {:subject subj :payload payload})
  (let [handlers (into [] (comp (map #(get-in @b [:subs % :handler])) (remove nil?))
                       (subscribers b subj))]
    (doseq [h handlers] (h {:subject subj :payload payload}))
    (count handlers)))

(defn publish-async!
  "Like `publish!` but each handler runs in a future, so a test can prove
   ordering does not depend on the sender. Answers the number of handlers."
  [b subj payload]
  #?(:cljs (publish! b subj payload)
     :default
     (let [hs (into [] (comp (map #(get-in @b [:subs % :handler])) (remove nil?))
                    (subscribers b subj))]
       (doseq [h hs] (future (h {:subject subj :payload payload})))
       (count hs))))

(defn subscribe!
  "Register `handler` for `filter`; answers the subscription id."
  [b filter handler]
  (let [id (:next (swap! b (fn [st] (-> st
                                        (update :next inc)
                                        (assoc-in [:subs (:next st)]
                                                  {:filter filter :handler handler})))))]
    id))

(defn unsubscribe!
  [b id]
  (swap! b update :subs dissoc id)
  nil)

(defn published
  "Every message published on this bus, oldest first."
  [b]
  (:published @b))

(defmethod port/connect :asyncapi.memory/bus
  [_ {:keys [bus at]}]
  (if-not (instance? #?(:clj clojure.lang.IAtom :cljs cljs.core/Atom) bus)
    (answer/refusal :asyncapi/bad-bus "opts :bus must be an autonomous.asyncapi.memory/bus")
    (let [clock (or at (fn [] 0))
          live  (atom #{})]
      (answer/ok
       {:subscribe  (fn [filter handler]
                      (let [id (subscribe! bus filter (fn [{:keys [subject payload]}]
                                                        (handler {:subject subject
                                                                  :payload payload
                                                                  :at (clock)})))]
                        (swap! live conj id)
                        id))
        :unsubscribe (fn [id] (swap! live disj id) (unsubscribe! bus id))
        :close       (fn [] (doseq [id @live] (unsubscribe! bus id)) (reset! live #{}))}))))
