(ns autonomous.domain.primitives-trifecta-test
  "Trifectas over the small pure rules everything else stands on: naming,
   scope algebra, leases, the journal ring, wire coercion, answer outcomes."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.journal :as journal]
            [autonomous.domain.lease :as lease]
            [autonomous.domain.naming :as naming]
            [autonomous.domain.schema :as s]
            [autonomous.domain.scope :as scope]
            [autonomous.domain.wire :as wire]
            [clojure.test.check.generators :as gen]
            [hive-schemas.test :refer [deftrifecta-from-schema]]
            [hive-test.trifecta :refer [deftrifecta]]))

;; Originals captured before any mutant rebinds the vars.
(def ^:private tool-name* naming/tool-name)
(def ^:private allowed?* scope/allowed?)
(def ^:private acquire* lease/acquire)

;; -----------------------------------------------------------------------------
;; naming

(deftrifecta tool-name-trifecta
  autonomous.domain.naming/tool-name
  {:golden-path "test/golden/domain/tool-name.edn"
   :cases {:namespaced :todo/add
           :dotted     :app.todos/add-item
           :string     "plain"
           :empty      ""
           :spaces     "add a todo"
           :unicode    "ação/é"
           :long       (keyword (apply str (repeat 80 "a")))}
   :gen (gen/one-of [gen/keyword-ns gen/keyword gen/string])
   :pred naming/valid-tool-name?
   :num-tests 300
   :mutations [["keeps the colon" str]
               ["no length cut" (fn [id] (let [s (if (keyword? id) (subs (str id) 1) (str id))]
                                           (apply str (map #(if (= \/ %) \_ %) s))))]
               ["slash survives" (fn [id] (subs (tool-name* (str id)) 0))]
               ["empty stays empty" (fn [id] (if (= "" (str id)) "" (tool-name* id)))]]})

;; -----------------------------------------------------------------------------
;; scope algebra

(deftrifecta allowed-trifecta
  autonomous.domain.scope/allowed?
  {:golden-path "test/golden/domain/allowed.edn"
   :apply? true
   :cases {:read-covers-read    [#{:read} #{:read}]
           :read-not-write      [#{:write} #{:read}]
           :admin-covers-all    [#{:write :billing} #{:admin}]
           :needs-every-scope   [#{:read :write} #{:write}]
           :nothing-required    [#{} #{}]
           :app-defined-scope   [#{:billing} #{:billing :read}]}
   :gen (gen/tuple (gen/set (gen/elements [:read :write :admin :billing]))
                   (gen/set (gen/elements [:read :write :admin :billing])))
   :pred boolean?
   :mutations [["always allows" (constantly true)]
               ["admin is not special" (fn [r g] (every? #(contains? g %) r))]
               ["any one scope suffices" (fn [r g] (or (empty? r) (boolean (some g r))))]]})

;; -----------------------------------------------------------------------------
;; leases

(deftrifecta acquire-trifecta
  autonomous.domain.lease/acquire
  {:golden-path "test/golden/domain/lease-acquire.edn"
   :apply? true
   :cases {:free              [nil "a" 1000 0]
           :other-live        [{:actor "b" :until 500} "a" 1000 100]
           :other-expired     [{:actor "b" :until 50} "a" 1000 100]
           :own-renewal       [{:actor "a" :until 500} "a" 1000 100]
           :default-ttl       [nil "a" nil 10]}
   :gen (gen/tuple (gen/one-of [(gen/return nil)
                                (gen/hash-map :actor (gen/elements ["a" "b"])
                                              :until gen/nat)])
                   (gen/elements ["a" "b"])
                   (gen/one-of [(gen/return nil) gen/s-pos-int])
                   gen/nat)
   :pred (fn [[verdict l]] (and (#{:granted :refused} verdict) (map? l)))
   :mutations [["always grants" (fn [_ a ttl now] [:granted {:actor a :until (+ now (or ttl 60000))}])]
               ["refuses own renewal" (fn [l a ttl now]
                                        (if (and l (= a (:actor l)) (> (:until l) now))
                                          [:refused l]
                                          (acquire* l a ttl now)))]
               ["expired still blocks" (fn [l a ttl now]
                                         (if (and l (not= a (:actor l))) [:refused l] (acquire* l a ttl now)))]]})

(deftrifecta blocks-trifecta
  autonomous.domain.lease/blocks?
  {:golden-path "test/golden/domain/lease-blocks.edn"
   :apply? true
   :cases {:no-lease        [nil {:read-only? false} {:actor "a"} 0]
           :other-writes    [{:actor "b" :until 100} {:read-only? false} {:actor "a"} 10]
           :other-reads     [{:actor "b" :until 100} {:read-only? true} {:actor "a"} 10]
           :holder-writes   [{:actor "a" :until 100} {:read-only? false} {:actor "a"} 10]
           :expired         [{:actor "b" :until 100} {:read-only? false} {:actor "a"} 100]}
   :gen (gen/tuple (gen/one-of [(gen/return nil)
                                (gen/hash-map :actor (gen/elements ["a" "b"]) :until gen/nat)])
                   (gen/hash-map :read-only? gen/boolean)
                   (gen/hash-map :actor (gen/elements ["a" "b"]))
                   gen/nat)
   :pred boolean?
   :mutations [["never blocks" (constantly false)]
               ["blocks reads too" (fn [l _ g now] (and (lease/live? l now) (not= (:actor l) (:actor g))))]
               ["blocks the holder" (fn [l t _ now] (and (lease/live? l now) (not (:read-only? t))))]]})

;; -----------------------------------------------------------------------------
;; journal ring

(def ^:private NewEntry [:map [:kind :keyword] [:actor {:optional true} :string]])

(deftrifecta-from-schema journal-append-trifecta
  autonomous.domain.journal/append
  {:in [:cat s/Journal NewEntry s/Millis [:int {:min 1 :max 4}]]
   :out s/Journal
   :rel (fn [[j e at cap] out]
          (and (= (:seq out) (inc (:seq j)))
               (<= (count (:entries out)) cap)
               (= (assoc e :seq (:seq out) :at at) (peek (:entries out)))))
   :golden-path "test/golden/domain/journal-append.edn"
   :num-tests 200
   :seed 11
   :n-cases 6})

(deftrifecta journal-since-trifecta
  autonomous.domain.journal/since
  {:golden-path "test/golden/domain/journal-since.edn"
   :apply? true
   :cases {:from-start [{:seq 3 :entries [{:seq 1} {:seq 2} {:seq 3}]} nil nil]
           :resume     [{:seq 3 :entries [{:seq 1} {:seq 2} {:seq 3}]} 1 nil]
           :limited    [{:seq 3 :entries [{:seq 1} {:seq 2} {:seq 3}]} 0 2]
           :caught-up  [{:seq 3 :entries [{:seq 1} {:seq 2} {:seq 3}]} 3 nil]}
   :gen (gen/tuple (gen/return {:seq 3 :entries [{:seq 1} {:seq 2} {:seq 3}]})
                   (gen/one-of [(gen/return nil) (gen/choose 0 4)])
                   (gen/one-of [(gen/return nil) (gen/choose 1 4)]))
   :pred (fn [{:keys [entries]}] (apply < 0 (map :seq entries)))
   :mutations [["ignores since" (fn [j _ l] (journal/since j nil l))]
               ["ignores limit" (fn [j s _] {:seq (:seq j) :entries (filterv #(> (:seq %) (or s 0)) (:entries j))})]]})

;; -----------------------------------------------------------------------------
;; wire values

(defrecord Point [x y])

(deftrifecta wire-trifecta
  autonomous.domain.wire/->wire
  {:golden-path "test/golden/domain/wire.edn"
   :cases {:scalar   42
           :nested   {:a [1 #{:k}] "s" nil}
           :record   (->Point 1 2)
           :lazy     (map inc (range 3))
           :object   (java.net.URI. "http://example.test/x")
           :odd-key  {(java.net.URI. "u:k") 1}}
   :gen gen/any-printable-equatable
   :pred (fn [v] (= v (wire/read-edn (wire/print-edn v))))
   :num-tests 200
   :mutations [["identity" identity]
               ["drops maps" (fn [x] (if (map? x) {} x))]]})

;; -----------------------------------------------------------------------------
;; answers

(deftrifecta outcome-trifecta
  autonomous.domain.answer/outcome
  {:golden-path "test/golden/domain/outcome.edn"
   :cases {:ok        {:ok nil}
           :pending   {:pending "p1"}
           :refused   {:error {:type :forbidden :message "no"}}
           :malformed {}}
   :gen (gen/one-of [(gen/fmap answer/ok gen/any-printable-equatable)
                     (gen/fmap answer/pending gen/string-alphanumeric)
                     (gen/fmap #(answer/refusal % "m") gen/keyword)])
   :pred keyword?
   :mutations [["ok whenever truthy" (fn [a] (if (:ok a) :ok :pending))]
               ["refusal type lost" (fn [a] (cond (answer/ok? a) :ok (answer/pending? a) :pending :else :error))]]})
