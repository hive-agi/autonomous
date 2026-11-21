(ns autonomous.frames-rf2-test
  "autonomous.re-frame2 against REAL re-frame2 (day8/re-frame2 at the sha
   fluxo-de-caixa-fe pins). Runs in the :rf2-test shadow build on the
   :cljs-rf2 classpath, which carries re-frame2 in place of re-frame 1."
  (:require [autonomous.core :as core]
            [autonomous.re-frame2 :as adapter]
            [autonomous.runtime.registry :as registry]
            [autonomous.spi :as spi]
            [cljs.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                                    :ambient-frame nil}))

(def ^:private charges
  "An effect outside every frame's app-db: what :rf2/charge has done."
  (atom []))

(defn- register-handlers! []
  (reset! charges [])
  (rf/reg-event :rf2/add (fn [{:keys [db]} [_ text]] {:db (update db :items (fnil conj []) text)}))
  (rf/reg-event :rf2/boom (fn [_ _] (throw (ex-info "boom" {}))))
  (rf/reg-event :rf2/leak (fn [_ _] (throw (js/Error. "token=s3cr3t-leak from https://remote.example"))))
  (rf/reg-event :rf2/commit-then-boom
                (fn [{:keys [db]} [_ text]] {:db (assoc db :committed text) :fx [[:dispatch [:rf2/boom]]]}))
  (rf/reg-fx :rf2/explode (fn [_ _] (throw (ex-info "fx says s3cr3t" {}))))
  (rf/reg-event :rf2/commit-then-fx-throw
                (fn [{:keys [db]} [_ text]] {:db (assoc db :committed text) :fx [[:rf2/explode true]]}))
  (rf/reg-fx :rf2/charge (fn [_ amount] (swap! charges conj amount)))
  (rf/reg-event :rf2/pay
                (fn [_ [_ text]]
                  (if (= "again" text)
                    (throw (ex-info "declined s3cr3t" {}))
                    {:fx [[:rf2/charge text] [:dispatch [:rf2/pay "again"]]]})))
  (rf/reg-event :rf2/later (fn [{:keys [db]} [_ text]] {:db (assoc db :later text)}))
  (rf/reg-fx :rf2/destroy-frame (fn [_ frame] (rf/destroy-frame! frame)))
  (rf/reg-event :rf2/self-destroy (fn [_ [_ frame]] {:fx [[:rf2/destroy-frame frame]]}))
  (rf/reg-sub :rf2/items (fn [db _] (get db :items [])))
  (rf/make-frame {:id :app/a})
  (rf/make-frame {:id :app/b}))

(def ^:private writer {:scopes [:read :write]})

(defn- call [tool args] (core/rpc (merge writer {:op :call :tool tool :args args})))

(defn- read-uri [uri] (core/rpc {:op :read :uri uri :scopes [:read]}))

(defn- in-runtime [f]
  (register-handlers!)
  (registry/with-instance (registry/make-runtime) f))

(defn- add-tool! [tool-id frame & {:as more}]
  (adapter/reg-event-tool :rf2/add (merge {:tool-id tool-id :description "Add"
                                           :input [:map [:text :string]]
                                           :args->event (fn [{:keys [text]}] [:rf2/add text])
                                           :frame frame}
                                          more)))

(deftest a-frame-scoped-event-dispatches-and-answers-in-its-frame
  (in-runtime
   (fn []
     (add-tool! :a/add :app/a :result-sub [:rf2/items])
     (add-tool! :b/add :app/b :result-path [:items])
     (add-tool! :b/db :app/b :result (fn [db _] (count (:items db))))
     (is (= {:ok ["milk"]} (call "a_add" {:text "milk"})) "the result sub is read in frame a")
     (is (= {:ok ["eggs"]} (call "b_add" {:text "eggs"})) "the result path is frame b's app-db")
     (is (= {:ok 2} (call "b_db" {:text "jam"})) ":result gets frame b's app-db")
     (is (= {:items ["milk"]} (rf/app-db-value :app/a)))
     (is (= {:items ["eggs" "jam"]} (rf/app-db-value :app/b))))))

(deftest a-frame-scoped-sub-reads-its-frame
  (in-runtime
   (fn []
     (rf/dispatch-sync [:rf2/add "a1"] {:frame :app/a})
     (rf/dispatch-sync [:rf2/add "b1"] {:frame :app/b})
     (adapter/reg-sub-tool :rf2/items {:tool-id :a/items :description "a" :frame :app/a})
     (adapter/reg-sub-tool :rf2/items {:tool-id :b/items :description "b" :frame :app/b})
     (is (= {:ok ["a1"]} (call "a_items" {})))
     (is (= {:ok ["b1"]} (call "b_items" {}))))))

(deftest a-frame-scoped-app-db-is-read-through-app-db-value
  (in-runtime
   (fn []
     (rf/dispatch-sync [:rf2/add "a1"] {:frame :app/a})
     (adapter/expose-db! {:uri "re-frame://app-db/a" :frame :app/a :paths [[:items]]})
     (adapter/expose-db! {:uri "re-frame://app-db/b" :frame :app/b})
     (is (= {:items ["a1"]} (get-in (read-uri "re-frame://app-db/a") [:ok :value])))
     (is (= {} (get-in (read-uri "re-frame://app-db/b") [:ok :value]))))))

(deftest an-unknown-frame-is-refused-not-read-as-empty
  (in-runtime
   (fn []
     (add-tool! :typo/add :app/typo)
     (adapter/reg-sub-tool :rf2/items {:tool-id :typo/items :description "typo" :frame :app/typo})
     (adapter/expose-db! {:uri "re-frame://app-db/typo" :frame :app/typo})
     (is (nil? (rf/app-db-value :app/typo)) "re-frame2 itself answers nil for it")
     (is (= :re-frame/unknown-frame (get-in (call "typo_add" {:text "x"}) [:error :type])))
     (is (= :re-frame/unknown-frame (get-in (call "typo_items" {}) [:error :type])))
     (is (= :re-frame/unknown-frame (get-in (read-uri "re-frame://app-db/typo") [:error :type])))
     (testing "a frame destroyed after registration is refused too"
       (add-tool! :a/add :app/a)
       (rf/destroy-frame! :app/a)
       (is (= :re-frame/unknown-frame (get-in (call "a_add" {:text "x"}) [:error :type])))))))

(deftest a-frame-destroyed-by-its-own-handler-is-refused-not-read-as-empty
  (in-runtime
   (fn []
     (doseq [[tool-id result] [[:a/path {:result-path [:items]}]
                               [:a/db {:result (fn [db _] {:db db})}]
                               [:a/sub {:result-sub [:rf2/items]}]]]
       (rf/make-frame {:id :app/a})
       (adapter/reg-event-tool :rf2/self-destroy
                               (merge {:tool-id tool-id :description "Destroy my frame" :frame :app/a
                                       :args->event (fn [_] [:rf2/self-destroy :app/a])}
                                      result))
       (let [answer (call (str (namespace tool-id) "_" (name tool-id)) {})]
         (is (not (contains? (set (rf/frame-ids)) :app/a)) "the handler's fx destroyed frame a")
         (is (= :re-frame/unknown-frame (get-in answer [:error :type])) (pr-str tool-id)))))))

(deftest a-throwing-handler-leaves-no-frame-behind
  (in-runtime
   (fn []
     (adapter/reg-event-tool :rf2/boom {:tool-id :a/boom :description "Boom" :frame :app/a})
     (add-tool! :b/add :app/b :result-sub [:rf2/items])
     (is (contains? (call "a_boom" {}) :error) "the throwing call answers a refusal")
     (is (= {:ok ["after"]} (call "b_add" {:text "after"})) "the next call lands in its own frame")
     (is (nil? (:items (rf/app-db-value :app/a)))))))

(defn- tool! [event-id tool-id frame]
  (adapter/reg-event-tool event-id {:tool-id tool-id :description (name event-id) :frame frame
                                    :input [:map [:text {:optional true} :string]]
                                    :args->event (fn [{:keys [text]}] [event-id (or text "x")])}))

(def ^:private secret #"s3cr3t|remote\.example|token=|declined|boom")

(deftest a-handler-that-throws-before-commit-is-refused-as-app-db-unchanged
  (in-runtime
   (fn []
     (tool! :rf2/boom :a/boom :app/a)
     (let [{:keys [type message data]} (:error (call "a_boom" {}))]
       (is (= :re-frame/handler-error type))
       (is (= "app-db-unchanged" (name (:state data))))
       (is (re-find #"the target frame's app-db is unchanged; effects may have run" message))
       (is (nil? (:committed (rf/app-db-value :app/a))))))))

(deftest a-cascade-that-commits-and-then-errors-is-refused-as-maybe-applied
  (in-runtime
   (fn []
     (testing "the event commits, then an event its fx dispatched throws"
       (tool! :rf2/commit-then-boom :a/cascade :app/a)
       (let [{:keys [type message data]} (:error (call "a_cascade" {:text "kept"}))]
         (is (= :re-frame/handler-error type))
         (is (= "maybe-applied" (name (:state data))))
         (is (re-find #"the dispatch may have changed app-db" message))
         (is (= "kept" (:committed (rf/app-db-value :app/a))) "the commit stood")))
     (testing "the event commits, then its own fx handler throws"
       (tool! :rf2/commit-then-fx-throw :b/fx :app/b)
       (let [answer (call "b_fx" {:text "kept"})
             {:keys [type message data]} (:error answer)]
         (is (= :re-frame/handler-error type))
         (is (= "maybe-applied" (name (:state data))))
         (is (re-find #"may have changed app-db" message))
         (is (not (re-find secret (pr-str answer))) (pr-str answer))
         (is (= "kept" (:committed (rf/app-db-value :app/b)))))))))

(deftest an-effect-that-re-dispatches-the-same-event-is-refused-as-maybe-applied
  (in-runtime
   (fn []
     (testing "no :db change, an effect outside app-db runs, then the same event id throws"
       (tool! :rf2/pay :a/pay :app/a)
       (let [before (rf/app-db-value :app/a)
             answer (call "a_pay" {:text "10.00"})
             {:keys [type message data]} (:error answer)]
         (is (= :re-frame/handler-error type))
         (is (= :rf2/pay (:event-id data)) "the error names the dispatched event id")
         (is (identical? before (rf/app-db-value :app/a)) "the target frame's app-db did not change")
         (is (= ["10.00"] @charges) "and yet the charge ran")
         (is (= "maybe-applied" (name (:state data)))
             "the error belongs to the re-dispatch, not the root dispatch")
         (is (re-find #"may have changed app-db" message))
         (is (not (re-find secret (pr-str answer))) (pr-str answer)))))))

(deftest a-refusal-carries-the-exception-class-never-its-message
  (in-runtime
   (fn []
     (tool! :rf2/leak :a/leak :app/a)
     (tool! :rf2/boom :a/boom :app/a)
     (let [leak-answer (call "a_leak" {})
           boom-answer (call "a_boom" {})
           leak        (:error leak-answer)
           boom        (:error boom-answer)]
       (is (= :re-frame/handler-error (:type leak)))
       (is (= (str "re-frame2 reported :rf.error/handler-exception for :rf2/leak (Error);"
                   " the target frame's app-db is unchanged; effects may have run")
              (:message leak))
           "fixed text and the class, never the handler's own message")
       (is (not (re-find #"s3cr3t|remote\.example|token=" (pr-str leak-answer))) (pr-str leak-answer))
       (is (= "Error" (:exception-class (:data leak))))
       (is (= (str "re-frame2 reported :rf.error/handler-exception for :rf2/boom (ExceptionInfo);"
                   " the target frame's app-db is unchanged; effects may have run")
              (:message boom)))
       (is (not (re-find #"\"boom\"" (pr-str boom-answer))) (pr-str boom-answer))
       (is (= "ExceptionInfo" (:exception-class (:data boom))))))))

(deftest a-frame-call-inside-another-frame-scope-targets-its-own-frame
  (in-runtime
   (fn []
     (add-tool! :b/add :app/b :result-sub [:rf2/items])
     (rf/with-frame :app/a
       (is (= {:ok ["nested"]} (call "b_add" {:text "nested"}))))
     (is (= {:items ["nested"]} (rf/app-db-value :app/b)))
     (is (nil? (:items (rf/app-db-value :app/a))) "the ambient frame a is untouched"))))

(deftest an-async-call-is-enqueued-into-its-frame
  (in-runtime
   (fn []
     (adapter/reg-event-tool :rf2/later {:tool-id :a/later :description "Later" :frame :app/a
                                         :input [:map [:text :string]] :async? true
                                         :args->event (fn [{:keys [text]}] [:rf2/later text])})
     (is (= {:ok {:queued [:rf2/later "q"]}} (call "a_later" {:text "q"})))
     (rf/dispatch-sync [:rf2/add "flush"] {:frame :app/a})
     (is (= "q" (:later (rf/app-db-value :app/a))) "the queued event drained in frame a")
     (is (nil? (:later (rf/app-db-value :app/b)))))))

(deftest without-a-frame-the-ambient-frame-is-used
  (in-runtime
   (fn []
     (adapter/reg-sub-tool :rf2/items {:description "Items"})
     (rf/dispatch-sync [:rf2/add "amb"] {:frame :app/a})
     (is (= {:ok ["amb"]} (rf/with-frame :app/a (call "rf2_items" {}))))
     (is (= :rf.error/no-frame-context
            (get-in (call "rf2_items" {}) [:error :data :rf.error/id]))
         "re-frame2's own refusal outside any scope reaches the agent"))))

(deftest declarations-are-validated-and-run-in-their-frame
  (in-runtime
   (fn []
     (let [spec   {:expose true :description "Items" :args->query (fn [_] [:rf2/items])}
           good   {:subs {:rf2/items (assoc spec :frame :app/a)}}
           bad    {:subs {:rf2/items (assoc spec :frame "app/a")}}
           answer (spi/derive-surface :autonomous.re-frame2/declarations good {})]
       (is (= :invalid-tool
              (get-in (spi/derive-surface :autonomous.re-frame2/declarations bad {}) [:error :type])))
       (is (= :app/a (get-in answer [:ok :tools "rf2_items" :frame])))
       (rf/dispatch-sync [:rf2/add "decl"] {:frame :app/a})
       (registry/with-instance (registry/make-runtime (:ok answer))
         (fn [] (is (= {:ok ["decl"]} (call "rf2_items" {})))))))))

(deftest a-frame-must-be-a-frame-id-keyword
  (doseq [f [#(adapter/reg-event-tool :rf2/add {:description "Add" :frame "app/a"})
             #(adapter/reg-sub-tool :rf2/items {:description "Items" :frame [:app/a]})
             #(adapter/expose-db! {:frame 'app/a})]]
    (is (= :invalid-tool (try (f) nil (catch :default e (:autonomous/error (ex-data e))))))))
