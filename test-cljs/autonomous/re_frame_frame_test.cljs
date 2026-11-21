(ns autonomous.re-frame-frame-test
  "autonomous.re-frame on re-frame 1, which has no frames: a :frame is
   refused wherever it can be declared, and without one nothing changes.
   Frame-scoped behaviour is tested against real re-frame2 in
   test-cljs-rf2 (shadow build :rf2-test)."
  (:require [autonomous.core :as core]
            [autonomous.re-frame :as adapter]
            [autonomous.runtime.registry :as registry]
            [autonomous.spi :as spi]
            [cljs.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]))

(rf/reg-event-db :rf1/add (fn [db [_ text]] (update db :items (fnil conj []) text)))
(rf/reg-sub :rf1/items (fn [db _] (get db :items [])))

(def ^:private db-before (atom nil))

(use-fixtures :each {:before (fn [] (reset! db-before @rf-db/app-db) (reset! rf-db/app-db {}))
                     :after  (fn [] (reset! rf-db/app-db @db-before))})

(defn- call [tool args] (core/rpc {:op :call :tool tool :args args :scopes [:read :write]}))

(defn- error-type [thunk]
  (try (thunk) nil (catch :default e (:autonomous/error (ex-data e)))))

(deftest without-a-frame-nothing-changes
  (registry/with-instance (registry/make-runtime)
    (fn []
      (adapter/reg-event-tool :rf1/add {:description "Add" :input [:map [:text :string]]
                                        :args->event (fn [{:keys [text]}] [:rf1/add text])
                                        :result-sub [:rf1/items]})
      (adapter/reg-event-tool :rf1/add {:tool-id :rf1/add-path :description "Add, answer a path"
                                        :input [:map [:text :string]]
                                        :args->event (fn [{:keys [text]}] [:rf1/add text])
                                        :result-path [:items]})
      (adapter/reg-sub-tool :rf1/items {:description "Items"})
      (adapter/expose-db! {:paths [[:items]]})
      (is (= {:ok ["x"]} (call "rf1_add" {:text "x"})))
      (is (= {:ok ["x" "y"]} (call "rf1_add-path" {:text "y"})))
      (is (= {:ok ["x" "y"]} (call "rf1_items" {})))
      (is (= {:items ["x" "y"]}
             (get-in (core/rpc {:op :read :uri "re-frame://app-db" :scopes [:read]}) [:ok :value]))
          "the resource reads re-frame.db/app-db"))))

(deftest a-frame-is-refused-on-re-frame-1
  (testing "at registration, for every entry point"
    (is (= :re-frame/no-frames
           (error-type #(adapter/reg-event-tool :rf1/add {:description "Add" :frame :app/a}))))
    (is (= :re-frame/no-frames
           (error-type #(adapter/reg-sub-tool :rf1/items {:description "Items" :frame :app/a}))))
    (is (= :re-frame/no-frames (error-type #(adapter/expose-db! {:frame :app/a})))))
  (testing "a frame that is not a frame-id keyword is named as such"
    (is (= :invalid-tool
           (error-type #(adapter/reg-event-tool :rf1/add {:description "Add" :frame "app/a"}))))))

(deftest a-declaration-with-a-frame-refuses-the-source
  (let [source {:subs {:rf1/items {:expose true :description "Items"
                                   :args->query (fn [_] [:rf1/items]) :frame :app/a}}}
        bad    {:subs {:rf1/items {:expose true :description "Items"
                                   :args->query (fn [_] [:rf1/items]) :frame "app/a"}}}]
    (is (= :re-frame/no-frames
           (get-in (spi/derive-surface :autonomous.re-frame/declarations source {}) [:error :type])))
    (is (= :invalid-tool
           (get-in (spi/derive-surface :autonomous.re-frame/declarations bad {}) [:error :type])))
    (is (= ":rf1/items"
           (get-in (spi/derive-surface :autonomous.re-frame/declarations bad {}) [:error :data :tool])))))
