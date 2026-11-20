(ns autonomous.re-frame-contract-test
  "The re-frame Source and both Codecs against real host handlers."
  (:require [autonomous.contract :as contract]
            [autonomous.core :as core]
            [autonomous.re-frame :as adapter]
            [autonomous.runtime.registry :as registry]
            [cljs.test :refer-macros [deftest is]]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]))

(def event-spec
  {:expose true :description "Add text"
   :input [:map [:text :string]]
   :output [:map [:text :string]]
   :args->event (fn [{:keys [text]}] [:contract/add text])
   :result-sub [:contract/last]})

(def sub-spec
  {:expose true :description "Read last text"
   :args->query (fn [_] [:contract/last])})

(rf/reg-sub :contract/last (fn [db _] {:text (:last db)}))

(def source
  {:events {:contract/add event-spec :contract/hidden (assoc event-spec :expose false)}
   :subs {:contract/last sub-spec}})

(defn fixture
  "The host's human path dispatches the same event without agent metadata."
  []
  {:source source
   :unexposed-source {:events {:contract/hidden (assoc event-spec :expose false)}}
   :empty-source {}
   :exposed-tools #{"contract_add" "contract_last"}
   :samples {"contract_add" {:text "from agent"} "contract_last" {}}
   :human-call (fn []
                 (rf/dispatch-sync [:contract/add "human"])
                 @rf-db/app-db)
   :mount (fn [] :re-frame-mounted)})

(rf/reg-event-db :contract/add (fn [db [_ text]] (assoc db :last text)))

(deftest re-frame-event-codec-satisfies-the-contract
  (let [report (contract/check-adapter
                (merge adapter/re-frame-adapter adapter/event-adapter) (fixture))]
    (contract/assert-contract report)))

(deftest re-frame-subscription-codec-satisfies-the-contract
  (let [report (contract/check-adapter
                (merge adapter/re-frame-adapter adapter/subscription-adapter) (fixture))]
    (contract/assert-contract report)))

(deftest existing-registration-uses-the-codec-path
  (registry/with-instance (registry/make-runtime)
    (fn []
      (adapter/reg-event-tool :contract/add (dissoc event-spec :expose))
      (is (= {:ok {:text "registered"}}
             (core/rpc {:op :call :tool "contract_add" :args {:text "registered"}
                        :scopes [:write]}))))))
