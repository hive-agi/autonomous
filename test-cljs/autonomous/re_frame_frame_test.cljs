(ns autonomous.re-frame-frame-test
  "The :frame option of reg-event-tool, reg-sub-tool and expose-db!.

   re-frame2 is not on this classpath (it ships its own re-frame.core, so it
   cannot sit beside re-frame 1). The test installs a stand-in for the two
   re-frame2 entry points the adapter reaches: re-frame.frame/*current-frame*
   (what re-frame.core/with-frame binds) and re-frame.core/app-db-value. The
   handlers below keep one app-db per frame under ::frames, keyed by the
   frame that is current when they run, as a re-frame2 frame would."
  (:require [autonomous.core :as core]
            [autonomous.re-frame :as adapter]
            [autonomous.runtime.registry :as registry]
            [cljs.test :refer [deftest is testing use-fixtures]]
            [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]))

(defn- current-frame []
  (gobj/getValueByKeys js/goog.global "re_frame" "frame" "_STAR_current_frame_STAR_"))

(defn- install-re-frame2-stand-in! []
  (let [rf-obj (gobj/get js/goog.global "re_frame")]
    (when-not (gobj/get rf-obj "frame") (gobj/set rf-obj "frame" #js {}))
    (gobj/set (gobj/get rf-obj "frame") "_STAR_current_frame_STAR_" nil)
    (gobj/set (gobj/get rf-obj "core") "app_db_value"
              (fn [frame] (get-in @rf-db/app-db [::frames frame])))))

(defn- remove-re-frame2-stand-in! []
  (let [rf-obj (gobj/get js/goog.global "re_frame")]
    (gobj/remove rf-obj "frame")
    (gobj/remove (gobj/get rf-obj "core") "app_db_value")))

(rf/reg-event-db :rf2/add
  (fn [db [_ text]]
    (if-let [f (current-frame)]
      (update-in db [::frames f :items] (fnil conj []) text)
      (update db :items (fnil conj []) text))))

(rf/reg-sub :rf2/items
  (fn [db _]
    (if-let [f (current-frame)]
      (get-in db [::frames f :items] [])
      (get db :items []))))

(defn- fresh! []
  (reset! rf-db/app-db {})
  (install-re-frame2-stand-in!))

(use-fixtures :each {:before fresh!})

(def writer {:scopes [:read :write]})

(defn- call [tool args] (core/rpc (merge writer {:op :call :tool tool :args args})))

(defn- in-runtime [f] (registry/with-instance (registry/make-runtime) f))

(deftest a-frame-scoped-event-dispatches-and-answers-in-its-frame
  (in-runtime
   (fn []
     (adapter/reg-event-tool :rf2/add {:tool-id :a/add :description "Add to frame a"
                                       :input [:map [:text :string]]
                                       :args->event (fn [{:keys [text]}] [:rf2/add text])
                                       :result-sub [:rf2/items]
                                       :frame :app/a})
     (adapter/reg-event-tool :rf2/add {:tool-id :b/add :description "Add to frame b"
                                       :input [:map [:text :string]]
                                       :args->event (fn [{:keys [text]}] [:rf2/add text])
                                       :result-path [:items]
                                       :frame :app/b})
     (is (= {:ok ["milk"]} (call "a_add" {:text "milk"})) "the result sub is read in frame a")
     (is (= {:ok ["eggs"]} (call "b_add" {:text "eggs"})) "the result path is read from frame b's app-db")
     (is (= {:app/a {:items ["milk"]} :app/b {:items ["eggs"]}} (::frames @rf-db/app-db)))
     (is (nil? (:items @rf-db/app-db)) "nothing reached the frameless app-db")
     (is (nil? (current-frame)) "the frame pin is undone after the call"))))

(deftest a-frame-scoped-sub-reads-its-frame
  (in-runtime
   (fn []
     (swap! rf-db/app-db assoc ::frames {:app/a {:items ["a1"]} :app/b {:items ["b1" "b2"]}})
     (adapter/reg-sub-tool :rf2/items {:tool-id :a/items :description "Frame a items" :frame :app/a})
     (adapter/reg-sub-tool :rf2/items {:tool-id :b/items :description "Frame b items" :frame :app/b})
     (is (= {:ok ["a1"]} (call "a_items" {})))
     (is (= {:ok ["b1" "b2"]} (call "b_items" {})))
     (is (nil? (current-frame))))))

(deftest a-frame-scoped-app-db-is-read-through-app-db-value
  (in-runtime
   (fn []
     (swap! rf-db/app-db assoc
            :items ["global"]
            ::frames {:app/a {:items ["a1"] :user {:email "a@b.c"}}})
     (adapter/expose-db! {:uri "re-frame://app-db/a" :frame :app/a :redact [[:user :email]]})
     (is (= {:items ["a1"] :user {:email "<redacted>"}}
            (get-in (core/rpc {:op :read :uri "re-frame://app-db/a" :scopes [:read]}) [:ok :value]))))))

(deftest without-a-frame-nothing-changes
  (in-runtime
   (fn []
     (let [seen (atom ::unset)]
       (rf/reg-event-db :rf2/probe (fn [db _] (reset! seen (current-frame)) db))
       (adapter/reg-event-tool :rf2/probe {:description "Probe"})
       (adapter/reg-event-tool :rf2/add {:description "Add" :input [:map [:text :string]]
                                         :args->event (fn [{:keys [text]}] [:rf2/add text])
                                         :result-sub [:rf2/items]})
       (adapter/reg-sub-tool :rf2/items {:description "Items"})
       (adapter/expose-db! {:paths [[:items]]})
       (is (= {:ok {:dispatched [:rf2/probe]}} (call "rf2_probe" {})))
       (is (nil? @seen) "no frame is pinned for a tool without :frame")
       (is (= {:ok ["x"]} (call "rf2_add" {:text "x"})))
       (is (= {:ok ["x"]} (call "rf2_items" {})))
       (is (= {:items ["x"]}
              (get-in (core/rpc {:op :read :uri "re-frame://app-db" :scopes [:read]}) [:ok :value]))
           "the resource reads re-frame.db/app-db")
       (testing "and the default path needs no re-frame2 at all"
         (remove-re-frame2-stand-in!)
         (is (= {:ok ["x" "y"]} (call "rf2_add" {:text "y"})))
         (is (= {:items ["x" "y"]}
                (get-in (core/rpc {:op :read :uri "re-frame://app-db" :scopes [:read]}) [:ok :value]))))))))

(deftest a-frame-without-re-frame2-is-refused
  (in-runtime
   (fn []
     (adapter/reg-sub-tool :rf2/items {:description "Items" :frame :app/a})
     (adapter/expose-db! {:frame :app/a})
     (remove-re-frame2-stand-in!)
     (is (= :re-frame/no-frames (get-in (call "rf2_items" {}) [:error :type])))
     (is (= :re-frame/no-frames
            (get-in (core/rpc {:op :read :uri "re-frame://app-db" :scopes [:read]}) [:error :type]))))))

(deftest a-frame-must-be-a-frame-id-keyword
  (is (thrown-with-msg? js/Error #"frame-id keyword"
                        (adapter/reg-event-tool :rf2/add {:description "Add" :frame "app/a"})))
  (is (thrown-with-msg? js/Error #"frame-id keyword"
                        (adapter/reg-sub-tool :rf2/items {:description "Items" :frame [:app/a]})))
  (is (thrown-with-msg? js/Error #"frame-id keyword"
                        (adapter/expose-db! {:frame 'app/a}))))
