(ns autonomous.re-frame.model-trifecta-test
  "Trifectas over the pure half of the re-frame adapter, on the JVM."
  (:require [autonomous.re-frame.model :as model]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]))

(def ^:private db-view* model/db-view)

(def db {:todos {1 {:id 1 :text "milk"}} :filter :all :user {:email "a@b.c" :name "Ana"} :next-id 2})

(deftrifecta db-view-trifecta
  autonomous.re-frame.model/db-view
  {:golden-path "test/golden/re-frame/db-view.edn"
   :apply? true
   :cases {:everything     [db nil nil]
           :paths          [db [[:todos] [:filter]] nil]
           :redacted       [db nil [[:user :email]]]
           :paths+redact   [db [[:user]] [[:user :email]]]
           :absent-path    [db [[:nope]] nil]
           :absent-redact  [db [[:filter]] [[:user :email]]]}
   ;; redaction always requested: a redacted value never leaks, whatever the selection
   :gen (gen/tuple (gen/return db)
                   (gen/one-of [(gen/return nil) (gen/vector (gen/elements [[:todos] [:filter] [:user] [:nope]]) 1 3)])
                   (gen/return [[:user :email]]))
   :pred (fn [v] (not= "a@b.c" (get-in v [:user :email])))
   :mutations [["ignores redaction" (fn [d p _] (db-view* d p nil))]
               ["ignores paths" (fn [d _ r] (db-view* d nil r))]
               ["redacts absent paths" (fn [d p r] (reduce #(assoc-in %1 %2 model/redacted) (db-view* d p nil) r))]]})

(deftrifecta event-entry-trifecta
  autonomous.re-frame.model/event-entry
  {:golden-path "test/golden/re-frame/event-entry.edn"
   :apply? true
   :cases {:human         [[:todo/add "milk"] nil false]
           :agent         [[:todo/add "milk"] {:autonomous/actor "agent-1"} false]
           :with-payload  [[:todo/add "milk" {:n 1}] {:autonomous/actor "agent-1"} true]
           :no-args       [[:todo/init] nil true]}
   :gen (gen/tuple (gen/fmap #(into [:e] %) (gen/vector gen/small-integer 0 3))
                   (gen/one-of [(gen/return nil) (gen/return {:autonomous/actor "x"})])
                   gen/boolean)
   :pred (fn [e] (#{:agent :app} (:origin e)))
   :mutations [["always app" (fn [ev _ p] (model/event-entry ev nil p))]
               ["leaks payloads" (fn [ev m _] (model/event-entry ev m true))]]})

(deftrifecta default-event-trifecta
  autonomous.re-frame.model/default-event
  {:golden-path "test/golden/re-frame/default-event.edn"
   :apply? true
   :cases {:no-args [:todo/init {}] :nil-args [:todo/init nil] :args [:todo/add {:text "x"}]}
   :gen (gen/tuple gen/keyword (gen/map gen/keyword gen/small-integer {:max-elements 2}))
   :pred vector?
   :mutations [["always wraps args" (fn [id a] [id a])]
               ["drops args" (fn [id _] [id])]]})
