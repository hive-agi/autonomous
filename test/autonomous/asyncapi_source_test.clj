(ns autonomous.asyncapi-source-test
  "The live half, end to end on the in-memory bus: a resource reads what was
   published since ITS OWN last read, the ring is bounded, teardown
   unsubscribes, and the derive-surface seam answers a Surface."
  (:require [autonomous.asyncapi :as asyncapi]
            [autonomous.asyncapi.memory :as memory]
            [autonomous.asyncapi.port :as port]
            [autonomous.asyncapi.spi]
            [autonomous.domain.affordance :as affordance]
            [autonomous.domain.answer :as answer]
            [autonomous.openapi.load :as load]
            [autonomous.runtime.invoke :as invoke]
            [autonomous.runtime.registry :as registry]
            [autonomous.spi :as spi]
            [clojure.test :refer [deftest is testing]]
            [autonomous.asyncapi.read :as read]))

(def bundle (:ok (load/load-bundle "test/fixtures/asyncapi/nats-events.yaml")))

(def expose {"crm.phone.state.v1" {:scope :crm/read :capacity 3}})

(def phone {:Phone "whatsapp" :From "closed" :To "open" :At "2026-11-19T12:00:00Z"})

(defn- connected
  "A bus and a port on it, with a deterministic clock."
  ([] (connected 1))
  ([tick]
   (let [b (memory/bus)
         c (atom tick)]
     [b (:ok (port/connect :asyncapi.memory/bus
                           {:bus b :at (fn [] (swap! c inc))}))])))

(defn- granted [actor] {:actor actor :scopes #{:crm/read :read}})

(defn- with-surface [f]
  (let [[b p] (connected)
        s     (:ok (asyncapi/surface bundle {:port p :expose expose}))
        uri   (str "asyncapi://crm.phone.state.v1")
        res   (get-in s [:resources uri])]
    (try (f b s uri res) (finally (asyncapi/stop! (:live s))))))

(deftest a-resource-answers-what-was-published-since-its-last-read
  (with-surface
    (fn [b _ uri res]
      (memory/publish! b "crm.phone.state.v1" phone)
      (memory/publish! b "crm.phone.state.v1" (assoc phone :To "closed"))
      (let [a ((:read res) (granted "agent-a"))]
        (is (answer/ok? a))
        (is (= 2 (count (get-in a [:ok :messages]))) "both messages, oldest first")
        (is (true? (get-in a [:ok :messages 0 :conforms?])) "the payload fits its schema")
        (is (= 2 (get-in a [:ok :cursor])))
        (is (zero? (get-in a [:ok :dropped])))
        (is (= uri (str "asyncapi://" (get-in a [:ok :subject]))))))))

(deftest a-second-read-answers-only-the-new-messages
  (with-surface
    (fn [b _ _ res]
      (memory/publish! b "crm.phone.state.v1" phone)
      ((:read res) (granted "agent-a"))
      (memory/publish! b "crm.phone.state.v1" (assoc phone :To "unknown"))
      (let [a ((:read res) (granted "agent-a"))]
        (is (= 1 (count (get-in a [:ok :messages]))))
        (is (= "unknown" (get-in a [:ok :messages 0 :payload :To])))
        (is (= 2 (get-in a [:ok :cursor])))))))

(deftest cursors-are-per-reader-and-per-session
  (with-surface
    (fn [b _ _ res]
      (memory/publish! b "crm.phone.state.v1" phone)
      (is (= 1 (count (get-in ((:read res) (granted "a")) [:ok :messages]))))
      (testing "another actor still sees the message"
        (is (= 1 (count (get-in ((:read res) (granted "b")) [:ok :messages])))))
      (testing "another session of the same actor has its own cursor"
        (is (= 1 (count (get-in ((:read res) {:actor "a" :session "s2" :scopes #{:read}})
                                [:ok :messages])))))
      (testing "the same reader twice sees nothing new"
        (is (empty? (get-in ((:read res) (granted "a")) [:ok :messages])))))))

(deftest actor-and-session-names-cannot-alias-a-cursor
  (with-surface
    (fn [b _ _ res]
      (memory/publish! b "crm.phone.state.v1" phone)
      (is (= 1 (get-in ((:read res) (granted "a/s")) [:ok :cursor])))
      (is (= [phone]
             (mapv :payload
                   (get-in ((:read res) (assoc (granted "a") :session "s"))
                           [:ok :messages])))))))

(deftest concurrent-reads-cannot-claim-the-same-page-or-rewind
  (with-surface
    (fn [b s _ res]
      (let [first-page (promise) resume (promise) calls (atom 0)
            uri "asyncapi://crm.phone.state.v1"
            entry (get-in s [:live :taps uri])
            page-fn (fn [store reader t limit]
                      (when (= 1 (swap! calls inc))
                        (deliver first-page true)
                        (deref resume 5000 nil))
                      (read/claim-page store reader t limit))
            read-page #(read/read-tap (:tap entry) (:store entry) entry
                                      (granted "a") page-fn)]
        (memory/publish! b "crm.phone.state.v1" phone)
        (is (some? res) "the registry exposes the same resource")
        (let [delayed (future (read-page))]
          (try
            (is (true? (deref first-page 5000 false)))
            (memory/publish! b "crm.phone.state.v1" phone)
            (is (= [1 2] (mapv :seq (get-in (read-page) [:ok :messages]))))
            (finally (deliver resume true)))
          (is (= {:subject "crm.phone.state.v1" :cursor 2 :messages [] :dropped 0}
                 (:ok (deref delayed 5000 nil))))
          (is (= 2 (get-in (read-page) [:ok :cursor]))))))))

(deftest the-ring-is-bounded-per-resource
  (with-surface
    (fn [b _ _ res]
      (dotimes [i 5] (memory/publish! b "crm.phone.state.v1" (assoc phone :n i)))
      (let [a ((:read res) (granted "a"))]
        (is (= 3 (count (get-in a [:ok :messages]))) "capacity 3")
        (is (= 5 (get-in a [:ok :cursor])))
        (is (= 2 (get-in a [:ok :dropped])) "the slow reader is told what it missed")
        (is (= 2 (get-in a [:ok :messages 0 :payload :n])) "oldest kept first")))))

(deftest dropped-is-reported-once-to-the-reader-that-missed
  (with-surface
    (fn [b _ _ res]
      (dotimes [i 2] (memory/publish! b "crm.phone.state.v1" phone))
      ((:read res) (granted "a"))
      (dotimes [i 5] (memory/publish! b "crm.phone.state.v1" phone))
      (let [first-read  ((:read res) (granted "a"))
            second-read ((:read res) (granted "a"))]
        (is (= 2 (get-in first-read [:ok :dropped])))
        (is (zero? (get-in second-read [:ok :dropped])) "not counted twice")))))

(deftest a-non-conforming-payload-is-kept-and-flagged
  (with-surface
    (fn [b _ _ res]
      (memory/publish! b "crm.phone.state.v1" {:Phone "whatsapp"})
      (memory/publish! b "crm.phone.state.v1" phone)
      (let [ms (get-in ((:read res) (granted "a")) [:ok :messages])]
        (is (= 2 (count ms)) "the bad one is not dropped")
        (is (false? (:conforms? (first ms))))
        (is (true? (:conforms? (second ms))))
        (is (some? (:problems (first ms))) "and it says why")))))

(deftest a-subject-outside-the-filter-never-reaches-the-tap
  (with-surface
    (fn [b _ _ res]
      (memory/publish! b "user.events.created" {:user_id "u"})
      (memory/publish! b "crm.phone.state.v1" phone)
      (is (= 1 (count (get-in ((:read res) (granted "a")) [:ok :messages])))))))

(deftest stop-unsubscribes-and-nothing-arrives-after
  (let [[b p] (connected)
        s     (:ok (asyncapi/surface bundle {:port p :expose expose}))
        res   (get-in s [:resources "asyncapi://crm.phone.state.v1"])]
    (memory/publish! b "crm.phone.state.v1" phone)
    (asyncapi/stop! (:live s))
    (is (empty? (memory/subscribers-of b)) "every subscription is gone")
    (is (= 1 (count (get-in ((:read res) (granted "a")) [:ok :messages])))
        "a read after teardown still answers what the frozen tap holds")))

(deftest a-refused-derivation-subscribes-nothing
  (let [[b p] (connected)]
    (is (answer/refused? (asyncapi/surface bundle {:port p :expose {"nope.subject" {}}})))
    (is (empty? (memory/subscribers-of b)))))

(deftest a-partial-startup-unsubscribes-every-tap-it-started
  (let [[b p] (connected)
        calls (atom 0)
        broken (assoc p :subscribe
                      (fn [filter handler]
                        (if (= 2 (swap! calls inc))
                          (throw (ex-info "broker refused the second tap" {}))
                          ((:subscribe p) filter handler))))]
    (is (answer/refused?
         (asyncapi/surface bundle {:port broken
                                   :expose {"crm.phone.state.v1" {}
                                            "user.events.created" {}}})))
    (is (empty? (memory/subscribers-of b)))))

(deftest a-missing-or-broken-port-is-refused
  (is (= :asyncapi/bad-bus (get-in (asyncapi/surface bundle {:expose expose}) [:error :type])))
  (is (= :asyncapi/bad-bus
         (get-in (asyncapi/surface bundle {:port {:subscribe identity} :expose expose})
                 [:error :type]))))

(deftest install-registers-read-only-resources-in-the-registry
  (let [[_ p] (connected)
        st    (registry/instance)
        a     (registry/with-instance st
                #(asyncapi/install! bundle {:port p :expose expose}))]
    (try
      (is (answer/ok? a))
      (is (= ["asyncapi://crm.phone.state.v1"] (get-in a [:ok :registered])))
      (let [r (get-in @st [:resources "asyncapi://crm.phone.state.v1"])]
        (is (= #{:crm/read} (:scopes r)))
        (is (fn? (:read r)))
        (is (= "application/json" (:mime r))))
      (finally (asyncapi/stop! (get-in a [:ok :live]))))))

(deftest runtime-reads-return-one-page-value
  (let [[b p] (connected)
        st (registry/instance)
        uri "asyncapi://crm.phone.state.v1"
        installed (registry/with-instance st #(asyncapi/install! bundle {:port p :expose expose}))]
    (try
      (memory/publish! b "crm.phone.state.v1" phone)
      (let [value (registry/with-instance st #(invoke/read! uri (granted "a")))]
        (is (= 1 (get-in value [:ok :value :cursor])))
        (is (= phone (get-in value [:ok :value :messages 0 :payload])))
        (is (not (contains? (get-in value [:ok :value]) :ok))))
      (finally (asyncapi/stop! (get-in installed [:ok :live]))))))

(deftest the-spi-seam-answers-a-surface
  (let [[_ p] (connected)
        s     (spi/derive-surface :asyncapi/doc bundle {:port p :expose expose})]
    (try
      (is (answer/ok? s))
      (is (= "asyncapi" (get-in s [:ok :app :name])) "the default app name")
      (is (empty? (get-in s [:ok :tools])) "a source of resources derives no tools")
      (is (= #{"asyncapi://crm.phone.state.v1"} (set (keys (get-in s [:ok :resources])))))
      (testing "the resource spec compiles the way the registry compiles it"
        (is (answer/ok? (affordance/->resource "asyncapi://crm.phone.state.v1"
                                               (get-in s [:ok :resources "asyncapi://crm.phone.state.v1"])))))
      (finally (asyncapi/stop! (get-in s [:ok :live]))))))
