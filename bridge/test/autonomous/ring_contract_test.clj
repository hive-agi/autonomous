(ns autonomous.ring-contract-test
  "Run the shipped Ring routes Source and Codec through the contract kit."
  (:require [autonomous.contract :as contract]
            [autonomous.ring.exchange :as exchange]
            [autonomous.ring.surface :as ring]
            [clojure.test :refer [deftest is]]
            [autonomous.spi :as spi]
            [autonomous.ring :as ring-handler]
            [autonomous.mcp.json :as json]))

(def todo-schema
  [:map [:id :int] [:text :string]])

(defn- hidden-route
  "A human-only route with a body that can be served without parsing."
  []
  ["/todos" {:post {:parameters {:body todo-schema}
                    :handler (fn [request]
                               {:status 201 :headers {"content-type" "application/json"}
                                :body (:body-params request)})}}])

(defn- todo-route
  "The sole opted-in route, with a declared output schema."
  []
  ["/todos" {:post {:parameters {:body [:map [:text :string]]}
                    :autonomous {:expose true :id :todos/create :scope :write
                                 :output [:map [:text :string]]}
                    :handler (fn [request]
                               {:status 201 :headers {"content-type" "application/json"}
                                :body {:text (get-in request [:body-params :text])}})}}])

(def route-adapter
  "The Source/Codec pair under contract, using Ring's route dispatch kind."
  (assoc ring/reitit-adapter :source-kind :autonomous.ring/routes))

(def ring-output-tool
  {:description "Create a todo with a declared result"
   :kind :ring/route :ring/method :post :ring/path "/todos"
   :ring/placement {:text :body}
   :input [:map [:text :string]] :output [:map [:text :string]]})

(defn fixture
  "An in-process Ring host; its human port is the handler, not the codec.
   `wrap` is what :mount puts in front of the handler (default
   wrap-autonomous); :human-call sends the human's request to whatever
   app is serving, the bare handler before :mount and the wrapped app
   after it."
  ([] (fixture #(ring-handler/wrap-autonomous % {:routes [(todo-route) (hidden-route)]})))
  ([wrap]
   (let [handler (get-in (todo-route) [1 :post :handler])
         parse-body (fn [request]
                      (cond-> request (:body request)
                        (assoc :body-params (json/read-json (slurp (:body request))))))
         app (comp handler parse-body)
         human-request {:uri "/todos" :request-method :post :body-params {:text "human"}}
         host (fn [tool input grant]
                (exchange/invoke-request (assoc tool :ring/handler app) input grant))
         serving (atom handler)]
     {:source [(todo-route) (hidden-route)]
      :unexposed-source [(hidden-route)]
      :empty-source []
      :exposed-tools #{"todos_create"}
      :samples {"todos_create" {:text "milk"}}
      :human-call #(@serving human-request)
      :mount #(reset! serving (wrap handler))
      :host host
      :grant {:actor "contract" :scopes #{:write}}
      :generated-samples 4})))

(deftest ring-routes-adapter-satisfies-the-contract
  (let [report (contract/check-adapter route-adapter (fixture))]
    (contract/assert-contract report)
    (is (:ok? report))))

(deftest a-mount-that-changes-the-human-path-fails-the-contract
  (let [hijack (fn [_] (fn [_] {:status 403 :body "agents only"}))
        report (contract/check-adapter route-adapter (fixture hijack))]
    (is (false? (:ok? (first (filter #(= :human-code-path (:check %)) (:checks report)))))
        "human-code-path calls the human path after the mount, not a value taken before it")))

(deftest ring-codec-refuses-non-answers-and-exceptions
  (let [broken {:encode (fn [_ _] (throw (ex-info "bad encode" {})))
                :decode (fn [_ _] 17)}
        tool {:ring/method :get :ring/path "/x"}]
    (is (= :codec/encode (get-in (spi/encode-call broken tool {}) [:error :type])))
    (is (= :codec/decode (get-in (spi/decode-result broken tool {}) [:error :type])))))
