(ns autonomous.trace-test
  "Telemetry on the JVM: the pure span model (W3C traceparent, findings,
   metrics, OTLP JSON), the runtime ring behind the :trace ops, agent-call
   spans, the bridge collector with its exporters, and Ring server spans.
   Every effect is reached through a port or a dynamic seam; nothing is
   redefined. Portable: runs on the JVM and on babashka."
  (:require [autonomous.bridge.client :as client]
            [autonomous.bridge.port :as port]
            [autonomous.bridge.trace :as bt]
            [autonomous.bridge.trace.export :as export]
            [autonomous.bridge.trace.otlp :as otlp]
            [autonomous.core :as a]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.trace :as dt]
            [autonomous.mcp.json :as json]
            [autonomous.ring.trace :as ring-trace]
            [autonomous.runtime.trace :as trace]
            [autonomous.trace.dom :as dom]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]))

(defn- fresh! []
  (a/reset-registry!)
  (a/set-app-info! {:name "fixture" :description "test app"})
  (a/reg-tool :echo/args {:description "Echo" :input [:map [:text :string]] :handler (fn [args _] args)})
  (a/reg-tool :boom {:description "Throws" :handler (fn [_ _] (throw (ex-info "kaboom" {})))}))

(use-fixtures :each (fn [t] (fresh!) (t) (trace/disable!)))

(def agent {:actor "agent-1" :scopes [:read :write]})
(def admin {:actor "dev" :scopes [:admin]})

(defn- span [id parent nm & {:as m}]
  (merge {:trace-id "t1" :span-id id :name nm :type :app/step :kind :internal :start 0 :end 1 :status :ok}
         (when parent {:parent-id parent})
         m))

;; =============================================================================
;; Domain: W3C Trace Context
;; =============================================================================

(def ^:private hex32 (gen/fmap #(apply str %) (gen/vector (gen/elements "0123456789abcdef") 32)))
(def ^:private hex16 (gen/fmap #(apply str %) (gen/vector (gen/elements "0123456789abcdef") 16)))

(deftrifecta parse-traceparent-trifecta
  autonomous.domain.trace/parse-traceparent
  {:golden-path "test/golden/trace/parse-traceparent.edn"
   :apply? true
   :cases {:valid      ["00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"]
           :upper-case ["00-4BF92F3577B34DA6A3CE929D0E0E4736-00F067AA0BA902B7-01"]
           :padded     ["  00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 "]
           :zero-trace ["00-00000000000000000000000000000000-00f067aa0ba902b7-01"]
           :zero-span  ["00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01"]
           :version-ff ["ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"]
           :short      ["00-4bf92f-00f067aa0ba902b7-01"]
           :absent     [nil]}
   ;; every header this adapter writes is one it reads back to the same context
   :gen (gen/fmap (fn [[t s]] [(dt/traceparent {:trace-id (str "a" (subs t 1)) :span-id (str "b" (subs s 1))})])
                  (gen/tuple hex32 hex16))
   :pred (fn [ctx] (and (= 32 (count (:trace-id ctx))) (= 16 (count (:span-id ctx)))))
   :mutations [["accepts nothing" (fn [_] nil)]
               ["drops the span id" (fn [s] (dissoc (dt/parse-traceparent s) :span-id))]]})

(deftest hex-ids-are-never-all-zero
  (is (= "0000001" (dt/hex-id 7 (constantly 0))))
  (is (= 32 (count (dt/hex-id 32 #(rand-int 16))))))

;; =============================================================================
;; Domain: reading spans
;; =============================================================================

(deftest findings-name-the-chain-of-causes
  (let [spans [(span "a" nil "click Save" :type :ui/click)
               (span "b" "a" "POST /api/save" :type :http/client :kind :client
                     :attrs {:http.response.status_code 500} :status :error)
               (span "c" nil "uncaught: x" :type :error/uncaught :status :error)
               (span "d" "a" ":todo/saved" :end 900)]
        fs    (dt/findings spans)]
    (is (= #{:http-server-error :error-without-cause :slow} (set (map :finding fs))))
    (is (= ["click Save" "POST /api/save"] (:path (first (filter #(= :http-server-error (:finding %)) fs)))))
    (is (not-any? #(= :slow (:finding %)) (dt/findings spans {:slow-ms 5000})) "the slow threshold is an option")))

(deftest metrics-are-red-per-span-name
  (let [m (dt/metrics [(span "a" nil "GET /x" :end 10) (span "b" nil "GET /x" :end 30 :status :error)
                       (span "c" nil "other" :end 5)])
        x (first m)]
    (is (= ["GET /x" 2 1 0.5] ((juxt :name :count :errors :error-rate) x)))
    (is (= 10 (get-in x [:ms :min])))
    (is (= 30 (get-in x [:ms :max])))))

(deftest the-tree-nests-children-and-marks-orphans
  (let [t (dt/tree [(span "a" nil "root") (span "b" "a" "child") (span "c" "zz" "orphan")])]
    (is (= #{"root" "orphan"} (set (map :name t))))
    (is (= ["child"] (map :name (:children (first (filter #(= "root" (:name %)) t))))))
    (is (:orphan? (first (filter #(= "orphan" (:name %)) t))))))

;; =============================================================================
;; Domain: OTLP JSON
;; =============================================================================

(deftest nanos-keeps-every-digit
  (is (= "1700000000123500000" (dt/nanos 1700000000123.5)) "a double of epoch ms carries sub-ms digits")
  (is (= "1795614066691816000" (dt/nanos 1795614066691.816)) "as performance.timeOrigin + now() gives them")
  (is (= "1000000" (dt/nanos 1)))
  (is (= "1001000000" (dt/nanos 1000.9999999))))

(deftest otlp-follows-the-collector-wire-format
  (let [req   (dt/->otlp [(span "aaaaaaaaaaaaaaaa" nil "GET /x" :trace-id (apply str (repeat 32 "1"))
                                :kind :server :attrs {:http.request.method "GET"} :source {:target "api"})
                          (span "bbbbbbbbbbbbbbbb" "aaaaaaaaaaaaaaaa" "boom" :trace-id (apply str (repeat 32 "1"))
                                :status :error :error {:message "x" :class "E"})]
                         "fallback")
        rs    (:resourceSpans req)
        spans (mapcat #(-> % :scopeSpans first :spans) rs)
        attrs (fn [s] (into {} (map (juxt :key :value)) (:attributes s)))]
    (is (= #{"api" "fallback"} (set (map #(-> % :resource :attributes first :value :stringValue) rs)))
        "one resource per app, service.name from the span's source")
    (is (= 2 (:kind (first (filter #(= "GET /x" (:name %)) spans)))) "SERVER is 2")
    (is (= {:stringValue "GET"} ((attrs (first spans)) "http.request.method")))
    (let [b (first (filter #(= "boom" (:name %)) spans))]
      (is (= {:code 2 :message "x"} (:status b)))
      (is (= "aaaaaaaaaaaaaaaa" (:parentSpanId b)))
      (is (= {:stringValue "E"} ((attrs b) "exception.type"))))
    (is (string? (json/write req)) "it serialises")))

;; =============================================================================
;; Runtime: ring, :trace ops, agent calls
;; =============================================================================

(defn- rpc [grant m] (a/rpc (merge grant m)))

(deftest tracing-is-off-until-an-admin-turns-it-on
  (rpc agent {:op :call :tool "echo_args" :args {:text "x"}})
  (is (empty? (:entries (:ok (rpc agent {:op :trace})))) "nothing recorded while off")
  (is (= :forbidden (get-in (rpc agent {:op :trace-config :enabled? true}) [:error :type])))
  (is (answer/ok? (rpc admin {:op :trace-config :enabled? true})))
  (is (true? (:enabled? (:ok (rpc agent {:op :trace}))))))

(deftest an-agent-call-is-a-root-span-and-its-work-its-children
  (trace/enable! {})
  (a/reg-tool :nested {:description "Records a child span"
                       :handler (fn [_ _] (trace/in-span "inner" :app/step {} (fn [_] :done)))})
  (rpc agent {:op :call :tool "nested" :args {}})
  (rpc agent {:op :call :tool "boom" :args {}})
  (let [{:keys [entries epoch]} (:ok (rpc agent {:op :trace}))
        call  (first (filter #(= "nested" (get-in % [:attrs :autonomous.tool])) entries))
        inner (first (filter #(= "inner" (:name %)) entries))
        boom  (first (filter #(= "boom" (get-in % [:attrs :autonomous.tool])) entries))]
    (is (some? epoch))
    (is (= :agent/call (:type call)))
    (is (= "agent-1" (:actor call)))
    (is (= (:span-id call) (:parent-id inner)) "work inside the call is its child")
    (is (= (:trace-id call) (:trace-id inner)))
    (is (= :error (:status boom)) "a failed call ends as an error")
    (testing "the cursor reads only what is new"
      (let [last-seq (:seq (peek entries))]
        (is (empty? (:entries (:ok (rpc agent {:op :trace :since last-seq})))))))))

(deftest a-mark-is-a-root-span
  (trace/enable! {})
  (let [id (:trace-id (:ok (rpc agent {:op :trace-mark :label "test: one"})))
        m  (first (filter #(= :test/mark (:type %)) (trace/dump)))]
    (is (= id (:trace-id m)))
    (is (nil? (:parent-id m)))
    (is (= "test: one" (:name m)))))

(deftest the-ring-is-bounded
  (trace/enable! {:cap 3})
  (dotimes [i 10] (trace/event! (str "e" i) :app/step))
  (is (= ["e7" "e8" "e9"] (map :name (trace/dump)))))

;; =============================================================================
;; Bridge: collector and exporters
;; =============================================================================

(defrecord SpanChannel [spans epoch]
  port/IRuntimeChannel
  (-send [_ {:keys [op since limit]} _]
    (case op
      :trace {:ok {:epoch @epoch :entries (vec (take (or limit 1000) (filter #(> (:seq %) (or since 0)) @spans)))}}
      {:ok {}}))
  port/IRuntimeDirectory
  (-runtimes [_] {:ok [{:runtime/id 1}]}))

(defn- seqd [n] (mapv #(assoc (span (format "%016x" (inc %)) nil (str "s" %)) :seq (inc %)) (range n)))

(deftest the-collector-reads-once-and-fans-out-to-every-exporter
  (let [mem    (atom [])
        posted (atom [])
        spans  (atom (seqd 3))
        epoch  (atom 1)
        ctx    (client/context {:target "todo" :channel (->SpanChannel spans epoch) :drain :inline})
        coll   (bt/collector {:exporters [{:exporter/kind :memory :memory/atom mem}
                                          {:exporter/kind :otlp-http :otlp/endpoint "http://collector/v1/traces"}
                                          {:exporter/kind :no-such-provider}]})]
    (binding [otlp/*post* (fn [url _ body _] (swap! posted conj [url body]) {:status 200 :body "{}"})]
      (is (= 3 (:collected (bt/collect-target! coll "todo" ctx))))
      (is (= 0 (:collected (bt/collect-target! coll "todo" ctx))) "a second read sees nothing new")
      (swap! spans conj (assoc (span "ffffffffffffffff" nil "s3") :seq 4))
      (is (= 1 (:collected (bt/collect-target! coll "todo" ctx)))))
    (is (= [3 1] (map count @mem)) "the memory exporter got each new batch, every span once")
    (is (= {:target "todo" :runtime "1"} (:source (ffirst @mem))))
    (is (= 2 (count @posted)) "one OTLP POST per new batch")
    (is (= "http://collector/v1/traces" (ffirst @posted)))
    (is (= 1 (count (:failed (bt/status coll)))) "an unknown provider is reported, not fatal")
    (testing "a reloaded page (new epoch) is read from the start"
      (reset! epoch 2)
      (reset! spans [(assoc (span "eeeeeeeeeeeeeeee" nil "after reload") :seq 1)])
      (binding [otlp/*post* (fn [& _] {:status 200 :body ""})]
        (is (= 1 (:collected (bt/collect-target! coll "todo" ctx))))))))

(deftest an-exporter-that-fails-answers-a-refusal-and-never-throws
  (doseq [[why post] [[:export/rejected (fn [& _] {:status 503 :body "busy"})]
                      [:export/unreachable (fn [& _] (throw (java.net.ConnectException. "refused")))]]]
    (binding [otlp/*post* post]
      (let [e (:ok (export/open {:exporter/kind :otlp-http}))]
        (is (= why (get-in (port/-export! e [(span "a" nil "x")]) [:error :type])))))))

(deftest views-answer-what-an-agent-asks
  (let [spans [(span "a" nil "click" :type :ui/click :source {:target "ui"})
               (span "b" "a" "GET /x" :status :error :source {:target "ui"})]]
    (is (= 1 (count (:traces (:ok (bt/view spans {}))))))
    (is (= ["click" "GET /x"] (map :name (:rows (:ok (bt/view spans {:view "timeline"}))))))
    (is (seq (:outline (:ok (bt/view spans {:view "trace" :trace-id "t"})))) "an id prefix is enough")
    (is (= :trace/bad-view (get-in (bt/view spans {:view "nope"}) [:error :type])))))

;; =============================================================================
;; Ring: server spans
;; =============================================================================

(def ^:private incoming "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")

(deftest a-request-continues-the-browser-trace
  (trace/enable! {})
  (let [h (ring-trace/wrap-trace (fn [_] (trace/in-span "db query" :app/step {} (fn [_] {:status 201 :body "ok"})))
                                 {:route-of (constantly "/todos/:id")})]
    (is (= 201 (:status (h {:request-method :post :uri "/todos/7" :headers {"traceparent" incoming}}))))
    (let [server (first (filter #(= :http/server (:type %)) (trace/dump)))
          child  (first (filter #(= "db query" (:name %)) (trace/dump)))]
      (is (= "4bf92f3577b34da6a3ce929d0e0e4736" (:trace-id server)))
      (is (= "00f067aa0ba902b7" (:parent-id server)) "child of the browser's client span")
      (is (= "POST /todos/:id" (:name server)))
      (is (= {:http.request.method "POST" :url.path "/todos/7" :http.route "/todos/:id" :http.response.status_code 201}
             (select-keys (:attrs server) [:http.request.method :url.path :http.route :http.response.status_code])))
      (is (= (:span-id server) (:parent-id child))))))

(deftest a-5xx-or-a-throw-ends-the-server-span-as-an-error
  (trace/enable! {})
  ((ring-trace/wrap-trace (fn [_] {:status 502})) {:request-method :get :uri "/a"})
  (is (thrown? Exception ((ring-trace/wrap-trace (fn [_] (throw (ex-info "x" {})))) {:request-method :get :uri "/b"})))
  (is (= [:error :error] (map :status (filter #(= :http/server (:type %)) (trace/dump))))))

(deftest an-async-handler-that-throws-still-ends-its-span
  (trace/enable! {})
  (let [h (ring-trace/wrap-trace (fn [_ _ _] (throw (ex-info "sync throw" {}))))]
    (is (thrown? Exception (h {:request-method :get :uri "/t"} identity identity)))
    (is (= :error (:status (first (filter #(= :http/server (:type %)) (trace/dump))))))))

(deftest private-data-stays-out-without-payloads
  (testing "ex-data only with payloads"
    (trace/enable! {})
    (is (nil? (:data (trace/error-info (ex-info "x" {:password "p"})))))
    (trace/enable! {:payloads? true})
    (is (= {:password "p"} (:data (trace/error-info (ex-info "x" {:password "p"}))))))
  (testing "urls lose their query and fragment"
    (is (= "https://a.b/cb" (dom/safe-url "https://a.b/cb?token=s3cret#access_token=t" false)))
    (is (= "https://a.b/cb?q=1" (dom/safe-url "https://a.b/cb?q=1" true)))
    (is (= "https://a.b/x" (:url.full (dom/describe {:tag "A" :href "https://a.b/x?t=1"}))))))

(deftest async-ring-records-the-span-when-the-response-is-sent
  (trace/enable! {})
  (let [p (promise)
        h (ring-trace/wrap-trace (fn [_ respond _] (future (respond {:status 200}))))]
    (h {:request-method :get :uri "/async"} #(deliver p %) #(deliver p %))
    (is (= 200 (:status (deref p 2000 :timeout))))
    (let [s (first (filter #(= :http/server (:type %)) (trace/dump)))]
      (is (= "GET /async" (:name s)))
      (is (= 200 (get-in s [:attrs :http.response.status_code]))))))
