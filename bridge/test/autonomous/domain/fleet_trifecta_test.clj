(ns autonomous.domain.fleet-trifecta-test
  "Trifectas over the pure fleet planning: prefixes and exposed names,
   routing a name back to its target, merging probes into the surface, and
   fleet config promotion. Portable: runs on the JVM and on babashka.
   Mutants call captured fn values, never the subject var."
  (:require [autonomous.config :as config]
            [autonomous.domain.fleet :as fleet]
            [autonomous.domain.naming :as naming]
            [clojure.test.check.generators :as gen]
            [hive-schemas.test :refer [deftrifecta-from-schema]]
            [hive-test.trifecta :refer [deftrifecta]]))

(def ^:private prefix* fleet/prefix)
(def ^:private exposed-name* fleet/exposed-name)
(def ^:private parse-name* fleet/parse-name)
(def ^:private merge-probes* fleet/merge-probes)
(def ^:private promote* config/promote)

(def ^:private id-gen
  (gen/fmap #(apply str %)
            (gen/vector (gen/elements (seq "abcXYZ019._-")) 1 40)))

(deftrifecta prefix-trifecta
  autonomous.domain.fleet/prefix
  {:golden-path "test/golden/fleet/prefix.edn"
   :cases {:plain      "todo"
           :underscore "orders_api"
           :dotted     "svc.billing"
           :spaces     "my app"
           :empty      ""
           :long       (apply str (repeat 40 "k"))}
   :gen id-gen
   :pred (fn [p] (and (naming/valid-tool-name? p)
                      (<= 1 (count p) fleet/max-prefix-length)
                      (not-any? #{\_ \.} p)))
   :num-tests 300
   :mutations [["keeps underscores" (fn [id] (naming/tool-name id))]
               ["no length cut" (fn [id] (apply str (map #(if (#{\_ \.} %) \- %) (naming/tool-name id))))]
               ["empty stays empty" (fn [id] (if (= "" id) "" (prefix* id)))]]})

(deftrifecta-from-schema exposed-name-trifecta
  autonomous.domain.fleet/exposed-name
  {:in [:cat [:string {:min 1 :max 24}] [:string {:min 1 :max 80}]]
   :out [:string {:min 1 :max 64}]
   :rel (fn [[pfx tool] out]
          (let [full (str pfx "__" tool)]
            (if (naming/valid-tool-name? full)
              (= full out)
              (and (naming/valid-tool-name? out)
                   (clojure.string/ends-with? out (str "-" (fleet/digest full)))
                   (clojure.string/starts-with?
                    (str pfx "__" (naming/sanitize tool))
                    (subs out 0 (- (count out) 13)))))))
   :golden-path "test/golden/fleet/exposed-name.edn"
   :num-tests 200
   :seed 7
   :n-cases 6})

(deftrifecta parse-name-trifecta
  autonomous.domain.fleet/parse-name
  {:golden-path "test/golden/fleet/parse-name.edn"
   :apply? true
   :cases {:routes      [{"todo" "todo" "orders-api" "orders_api"} "orders-api__refund"]
           :tool-has-__ [{"todo" "todo"} "todo__a__b"]
           :builtin     [{"todo" "todo"} "autonomous_whoami"]
           :unknown-app [{"todo" "todo"} "shop__buy"]
           :no-name     [{"todo" "todo"} nil]}
   :gen (gen/tuple (gen/return {"a" "a" "b-c" "b_c"})
                   (gen/fmap (fn [[p t]] (str p "__" t))
                             (gen/tuple (gen/elements ["a" "b-c"]) gen/string-alphanumeric)))
   ;; every exposed name of a known prefix routes back to that prefix's target
   :pred (fn [r] (contains? #{"a" "b_c"} (:target r)))
   :mutations [["splits at the last separator"
                (fn [ps n] (when-let [i (clojure.string/last-index-of (str n) "__")]
                             (when-let [t (get ps (subs n 0 i))] {:target t :local (subs n (+ 2 i))})))]
               ["trusts any prefix" (fn [ps n] (or (parse-name* ps n) (when n {:target "?" :local n})))]
               ["forgets the local name" (fn [ps n] (some-> (parse-name* ps n) (assoc :local nil)))]]})

(defn- manifest [v & names]
  {:version v :app {:name "x" :description "an app"}
   :tools (mapv (fn [n] {:name n :description n :input-schema {:type "object"}}) names)
   :resources [{:uri "app://db" :name "db"}]})

(def ^:private up-todo   {:target "todo" :status :up :manifest (manifest 3 "todo_add" "todo_list")})
(def ^:private up-api    {:target "api" :status :up :manifest (manifest 1 "refund")})
(def ^:private down-api  {:target "api" :status :down :error {:type :channel/connect-failed}})
(def ^:private slow-api  {:target "api" :status :slow :error {:type :target/slow}})
(def ^:private twin      {:target "todo" :status :up :manifest (manifest 9 "todo_add")})

(defn- surface-shape [s]
  {:tools (mapv :name (:tools s)) :resources (mapv :uri (:resources s))
   :routes (:routes s) :apps (mapv #(dissoc % :description) (:apps s))})

(def ^:private probe-gen
  (gen/vector (gen/elements [up-todo up-api down-api slow-api]) 1 4))

(deftrifecta merge-probes-trifecta
  autonomous.domain.fleet/merge-probes
  {:golden-path "test/golden/fleet/merge-probes.edn"
   :xf surface-shape
   :cases {:two-up      [up-todo up-api]
           :one-down    [up-todo down-api]
           :one-slow    [slow-api up-todo]
           :all-down    [down-api]
           :shadowed    [up-todo twin]}
   :gen probe-gen
   ;; only up targets contribute, every listed tool routes, one row per target
   :pred (fn [s] (and (= (count (:tools s)) (count (:routes s)))
                      (every? #(= :up (:status %)) (filter :tools (:apps s)))
                      (every? (fn [t] (contains? (:routes s) (:name t))) (:tools s))))
   :mutations [["down targets keep their tools"
                (fn [ps] (merge-probes* (mapv #(if (= :up (:status %)) % (assoc up-api :target (:target %))) ps)))]
               ["no routes" (fn [ps] (assoc (merge-probes* ps) :routes {}))]
               ["unprefixed" (fn [ps] (update (merge-probes* ps) :tools
                                              (fn [ts] (mapv #(update % :name subs (inc (clojure.string/index-of (:name %) "_"))) ts))))]]})

(def ^:private dirs {"/w/todo" {:dir "/w/todo" :shadow? true :builds [:app]}
                     "/w/api"  {:dir "/w/api" :shadow? false}})

(defn- fleet-shape [r]
  (if-let [c (:ok r)]
    {:single (:target/id (:target c)) :fleet (mapv (juxt :target/id :nrepl/mode :nrepl/port) (:targets c))}
    [:error (:message (:error r))]))

(deftrifecta promote-fleet-trifecta
  autonomous.config/promote
  {:golden-path "test/golden/fleet/promote.edn"
   :apply? true
   :xf fleet-shape
   :cases {:two-dirs    [{} {} {:targets [{:dir "/w/todo"} {:dir "/w/api" :clj? true}]} nil dirs]
           :two-ports   [{} {} {:targets [{:port "7001"} {:port "7002" :target-id "orders"}]} nil {}]
           :file        [{:targets [{:target/kind :local :target/id "local"} {:port "7003" :target-id "x"}]} {} {} nil {}]
           :fleet-flag  [{} {} {:dir "/w/api" :fleet? true} (dirs "/w/api") dirs]
           :single      [{} {} {:dir "/w/todo"} (dirs "/w/todo") dirs]
           :duplicate   [{} {} {:targets [{:dir "/w/todo"} {:dir "/w/todo"}]} nil dirs]
           :prefix-clash [{} {} {:targets [{:port "1" :target-id "a_b"} {:port "2" :target-id "a.b"}]} nil {}]}
   :gen (gen/tuple (gen/return {}) (gen/return {})
                   (gen/fmap (fn [ports] {:targets (mapv (fn [p] {:port (str p)}) ports)})
                             (gen/vector-distinct (gen/choose 1 65535) {:min-elements 2 :max-elements 4}))
                   (gen/return nil) (gen/return {}))
   ;; distinct ports always make a valid fleet with one target per port
   :pred (fn [r] (and (:ok r) (<= 2 (count (:targets (:ok r)))) (nil? (:target (:ok r)))))
   :mutations [["first target only"
                (fn [f e o d ds] (promote* f e (-> o (assoc :port (:port (first (:targets o)))) (dissoc :targets)) d ds))]
               ["no fleet check" (fn [f e o d ds]
                                   (let [r (promote* f e o d ds)]
                                     (if (= "invalid fleet" (get-in r [:error :message])) {:ok {:targets []}} r)))]]})
