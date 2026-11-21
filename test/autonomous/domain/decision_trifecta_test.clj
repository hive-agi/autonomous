(ns autonomous.domain.decision-trifecta-test
  "Trifectas over the Promote stratum: compiling an author spec into a Tool,
   planning a call, projecting a manifest. Outputs carrying compiled
   functions are projected with :xf so the golden stays EDN."
  (:require [autonomous.domain.affordance :as affordance]
            [autonomous.domain.decision :as decision]
            [autonomous.domain.manifest :as manifest]
            [autonomous.domain.naming :as naming]
            [autonomous.domain.schema :as s]
            [autonomous.domain.scope :as scope]
            [clojure.test.check.clojure-test :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]))

(def ^:private plan-call* decision/plan-call)
(def ^:private host-grant* decision/host-grant)
(def ^:private ->tool* affordance/->tool)

(defn- tool! [id spec] (:ok (affordance/->tool id spec)))

(def ^:private noop (fn [_ _] nil))

(def snapshot
  {:version 7
   :app {:name "todo"}
   :lease nil
   :resources {"app://db" {:uri "app://db" :name "db" :description "" :mime "application/json"
                           :scopes #{:read} :read (constantly {})}
               "app://secrets" {:uri "app://secrets" :name "secrets" :description "" :mime "application/json"
                                :scopes #{:admin} :read (constantly {})}}
   :tools (into {} (map (juxt :name identity))
                [(tool! :todo/add {:description "Add a todo"
                                   :input [:map [:text [:string {:min 1}]]
                                           [:priority {:optional true} [:enum :low :high]]]
                                   :handler noop})
                 (tool! :todo/list {:description "List todos" :read-only? true :handler noop})
                 (tool! :todo/purge {:description "Delete everything" :scopes [:admin] :handler noop})])})

(def leased (assoc snapshot :lease {:actor "b" :until 1000}))

(def rw {:actor "a" :scopes #{:read :write}})

(defn plan-shape
  "A plan without compiled functions: what was decided, on what."
  [p]
  (case (:plan/action p)
    :invoke [:invoke (get-in p [:tool :name]) (:args p)]
    :read   [:read (get-in p [:resource :uri])]
    :refuse [:refuse (get-in p [:refusal :error :type]) (get-in p [:refusal :error :data])]))

;; -----------------------------------------------------------------------------
;; plan-call

(deftrifecta plan-call-trifecta
  autonomous.domain.decision/plan-call
  {:golden-path "test/golden/domain/plan-call.edn"
   :apply? true
   :xf plan-shape
   :cases {:invokes            [snapshot "todo_add" {:text "milk"} rw 0]
           :decodes-enum       [snapshot "todo_add" {:text "milk" :priority "high"} rw 0]
           :unknown            [snapshot "todo_nope" {} rw 0]
           :forbidden-write    [snapshot "todo_add" {:text "x"} {:actor "a" :scopes #{:read}} 0]
           :admin-only         [snapshot "todo_purge" {} rw 0]
           :admin-passes       [snapshot "todo_purge" {} {:actor "a" :scopes #{:admin}} 0]
           :invalid-args       [snapshot "todo_add" {:text ""} rw 0]
           :missing-arg        [snapshot "todo_add" {} rw 0]
           :leased-write       [leased "todo_add" {:text "x"} rw 10]
           :leased-read-open   [leased "todo_list" {} rw 10]
           :holder-writes      [leased "todo_add" {:text "x"} {:actor "b" :scopes #{:write}} 10]
           :lease-expired      [leased "todo_add" {:text "x"} rw 1000]}
   :gen (gen/tuple (gen/elements [snapshot leased])
                   (gen/elements ["todo_add" "todo_list" "todo_purge" "nope"])
                   (gen/one-of [(gen/return {}) (gen/hash-map :text gen/string-alphanumeric)])
                   (gen/hash-map :actor (gen/elements ["a" "b"])
                                 :scopes (gen/set (gen/elements [:read :write :admin])))
                   (gen/choose 0 2000))
   :pred (m/validator s/Plan)
   :num-tests 300
   :mutations [["skips the scope check"
                (fn [snap n a g now] (plan-call* snap n a (assoc g :scopes #{:admin}) now))]
               ["ignores leases"
                (fn [snap n a _g now] (plan-call* (dissoc snap :lease) n a {:actor "b" :scopes #{:read :write :admin}} now))]
               ["skips arg validation"
                (fn [snap n a g now]
                  (let [p (plan-call* snap n a g now)]
                    (if (= :invalid-args (get-in p [:refusal :error :type]))
                      {:plan/action :invoke :tool (get-in snap [:tools n]) :args a}
                      p)))]
               ["passes raw args"
                (fn [snap n a g now]
                  (let [p (plan-call* snap n a g now)]
                    (if (= :invoke (:plan/action p)) (assoc p :args a) p)))]]})

;; -----------------------------------------------------------------------------
;; ->tool

(defn tool-shape [a]
  (if-let [t (:ok a)]
    (select-keys t [:name :kind :scopes :read-only? :json-schema :annotations :output-schema])
    [:refused (get-in a [:error :type])]))

(deftrifecta ->tool-trifecta
  autonomous.domain.affordance/->tool
  {:golden-path "test/golden/domain/to-tool.edn"
   :apply? true
   :xf tool-shape
   :cases {:minimal        [:ping {:description "Ping" :handler noop}]
           :read-only      [:todo/list {:description "List" :read-only? true :handler noop}]
           :scoped         [:bill/charge {:description "Charge" :scopes [:billing] :handler noop}]
           :typed-input    [:todo/add {:description "Add" :handler noop
                                       :input [:map [:text :string] [:due {:optional true} :int]]
                                       :output [:map [:id :int]]}]
           :custom-kind    [:rf/evt {:description "Event" :kind :re-frame/event}]
           :named          [:x {:description "X" :name "custom_name" :handler noop}]
           :no-handler     [:x {:description "X"}]
           :no-description [:x {:handler noop}]
           :input-not-map  [:x {:description "X" :handler noop :input :string}]
           :input-not-schema [:x {:description "X" :handler noop :input [:not-a-schema]}]}
   :gen (gen/tuple gen/keyword-ns
                   (gen/hash-map :description (gen/not-empty gen/string-alphanumeric)
                                 :handler (gen/return noop)
                                 :read-only? gen/boolean))
   :pred (fn [a] (or (m/validate s/Tool (:ok a)) (contains? a :error)))
   :mutations [["read-only needs write" (fn [id spec] (update-in (->tool* id spec) [:ok :scopes] #(if % #{:write} %)))]
               ["scopes ignored" (fn [id spec] (->tool* id (dissoc spec :scopes)))]
               ["accepts anything" (fn [id spec] (->tool* id (merge {:description "d" :handler noop} spec {:input [:map]})))]]})

;; -----------------------------------------------------------------------------
;; manifest projection

(deftrifecta manifest-trifecta
  autonomous.domain.manifest/project
  {:golden-path "test/golden/domain/manifest.edn"
   :apply? true
   :cases {:anonymous [snapshot {:actor "x" :scopes #{:read}}]
           :writer    [snapshot rw]
           :admin     [snapshot {:actor "x" :scopes #{:admin}}]
           :nothing   [snapshot {:actor "x" :scopes #{}}]}
   :gen (gen/tuple (gen/return snapshot)
                   (gen/hash-map :actor (gen/return "x")
                                 :scopes (gen/set (gen/elements [:read :write :admin]))))
   :pred (m/validator s/Manifest)
   :mutations [["shows everything" (fn [snap _] (manifest/project snap {:scopes #{:admin}}))]
               ["hides resources" (fn [snap g] (assoc (manifest/project snap g) :resources []))]]})

(def ^:private nasty-code-points
  "Code points an actor may smuggle in: ASCII, quote and backslash, C0, DEL,
   C1, NBSP, zero-width, bidi controls and isolates, line and paragraph
   separators, the BOM and astral emoji."
  (gen/one-of [(gen/choose 0x20 0x7E)
               (gen/elements [0x22 0x5C 0x00 0x07 0x0A 0x1B 0x7F 0x85 0x9F 0xA0
                              0x200B 0x200C 0x200D 0x200E 0x200F 0x202A 0x202E
                              0x2066 0x2069 0x2028 0x2029 0xFEFF 0xE9 0x4E2D])
               (gen/choose 0x1F300 0x1FAFF)
               (gen/choose 0x10000 0x10FFFF)]))

(def ^:private nasty-actor
  (gen/fmap (fn [cps] (apply str (map #(String. (Character/toChars (int %))) cps)))
            (gen/vector nasty-code-points 0 90)))

(def ^:private grant-gen
  (gen/hash-map :actor (gen/one-of [nasty-actor gen/string gen/string-ascii (gen/return nil)
                                    (gen/elements [" " "\t\n" (str (char 0x200B) (char 0x2028))])])
                :scopes (gen/set (gen/elements [:read :write :admin :billing]))))

(defn- printable-ascii? [s] (every? #(<= 0x20 (int %) 0x7E) s))

(deftrifecta host-grant-trifecta
  autonomous.domain.decision/host-grant
  {:golden-path "test/golden/domain/host-grant.edn"
   :cases {:writer          {:actor "agent-1" :scopes #{:write :read}}
           :anonymous       {:actor "anonymous" :scopes #{:read}}
           :no-scopes       {:actor "a" :scopes #{}}
           :many-scopes     {:actor "a" :scopes #{:write :read :admin :billing :zeta :audit :runtime :ops}}
           :control-chars   {:actor (str "ag" (char 27) "[31m" (char 10) "ent" (char 127)) :scopes #{:read}}
           :c1              {:actor (str "a" (char 0x85) "b" (char 0x9B)) :scopes #{:read}}
           :bidi            {:actor (str "agent:" (char 0x202E) "namuh") :scopes #{:write}}
           :zero-width      {:actor (str "agent" (char 0x200B) "-1") :scopes #{:write}}
           :astral          {:actor (str "agent-" (String. (Character/toChars 0x1F600))) :scopes #{:write}}
           :quote-injection {:actor "a\",\"scopes\":[\"admin\"],\"x\":\"" :scopes #{:read}}
           :backslash       {:actor "a\\u0041" :scopes #{:read}}
           :long            {:actor (apply str (repeat 200 "x")) :scopes #{:admin}}
           :long-escapes    {:actor (apply str (repeat 30 (char 0x202E))) :scopes #{:read}}
           :cap-at-escape   {:actor (str "abc" (apply str (repeat 12 "é"))) :scopes #{:read}}
           :empty           {:actor "" :scopes #{:write}}
           :whitespace-only {:actor " \t\n " :scopes #{:write}}
           :only-invisible  {:actor (str (char 0) (char 7) (char 0x85) (char 0x200B) (char 0x2028)) :scopes #{:write}}
           :nil-actor       {:actor nil :scopes #{:write}}}
   :gen grant-gen
   :pred (fn [g] (or (nil? g)
                     (and (string? (:actor g))
                          (<= 1 (count (:actor g)) naming/max-shown-actor)
                          (printable-ascii? (:actor g))
                          (= (:scopes g) (sort (:scopes g))))))
   :num-tests 300
   :mutations [["raw actor" (fn [g] (some-> (host-grant* g) (assoc :actor (:actor g))))]
               ["drops instead of escaping"
                (fn [g] (some-> (host-grant* g)
                                (assoc :actor (apply str (filter #(<= 0x20 (int %) 0x7E) (str (:actor g)))))))]
               ["no length cut" (fn [g] (when-not (naming/nameless-actor? (:actor g))
                                          {:actor (apply str (filter #(<= 0x20 (int %) 0x7E) (str (:actor g))))
                                           :scopes (scope/names (:scopes g))}))]
               ["empty grant passes" (fn [g] (or (host-grant* g) {:actor "" :scopes []}))]
               ["blank grant passes" (fn [g] (if (seq (str (:actor g)))
                                               {:actor (str (:actor g)) :scopes ["read"]}
                                               (host-grant* g)))]
               ["unsorted scopes" (fn [g] (some-> (host-grant* g) (assoc :scopes (mapv name (:scopes g)))))]
               ["adds admin" (fn [g] (some-> (host-grant* g)
                                             (update :scopes #(vec (sort (distinct (conj % "admin")))))))]]})

(tc/defspec host-grant-keeps-the-callers-scopes 300
  (prop/for-all [g grant-gen]
    (let [out (decision/host-grant g)]
      (if (naming/nameless-actor? (:actor g))
        (nil? out)
        (and (= (:scopes out) (vec (sort (map name (:scopes g)))))
             (= (:actor out) (naming/shown-actor (:actor g))))))))

(tc/defspec shown-actor-keeps-distinct-actors-distinct 300
  (prop/for-all [a nasty-actor b nasty-actor]
    (let [sa (naming/shown-actor a) sb (naming/shown-actor b)]
      (or (= a b) (not= sa sb)
          (>= (count sa) (- naming/max-shown-actor 5))))))
