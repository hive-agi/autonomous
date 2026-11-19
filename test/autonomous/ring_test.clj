(ns autonomous.ring-test
  "The Ring adapter: the pure crossings (args -> request, response ->
   answer), surface derivation from route data, MCP through the wrapped
   handler, and the reitit mounted handler (JVM, when reitit is present).
   Never touches the process-wide registry."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.mcp.json :as json]
            [autonomous.ring :as ar]
            [autonomous.ring.exchange :as ex]
            [autonomous.ring.surface]
            [autonomous.runtime.registry :as registry]
            [autonomous.spi :as spi]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; -----------------------------------------------------------------------------
;; A small app: its own handler, its own body parsing and auth adapter

(defn- json-body
  "What the app's own body middleware would do."
  [handler]
  (fn [req]
    (handler (cond-> req
               (and (:body req) (some-> (:content-type req) (str/includes? "json")))
               (assoc :body-params (json/read-json (slurp (:body req))))))))

(defn- app-routes [db]
  [["/todos" {:get  {:summary "List todos"
                     :parameters {:query [:map [:done {:optional true} :boolean]]}
                     :autonomous {:expose true}
                     :handler (fn [_] {:status 200 :headers {"Content-Type" "application/json"}
                                       :body (json/write {:todos @db})})}
              :post {:summary "Add a todo"
                     :parameters {:body [:map [:text [:string {:min 1}]]]}
                     :autonomous {:expose true :scope :write}
                     :handler (fn [req]
                                (let [by (or (get-in req [:autonomous/grant :actor]) "human")]
                                  (swap! db conj {:text (get-in req [:body-params :text]) :by by})
                                  {:status 201 :body {:added (get-in req [:body-params :text]) :by by}}))}}]
   ["/todos/:id" {:delete {:parameters {:path [:map [:id :int]]}
                           :handler (fn [_] {:status 204})}}]
   ["/admin/wipe" {:post {:summary "Wipe"
                          :autonomous {:expose true :scope :admin}
                          :handler (fn [_] (reset! db []) {:status 200 :body {:wiped true}})}}]
   ["/fail" {:post {:summary "Always 409"
                    :autonomous {:expose true}
                    :handler (fn [_] {:status 409 :body {:message "conflict"}})}}]])

(defn- dispatch
  "A router-free dispatcher over route data, as an app without reitit has."
  [routes]
  (fn [req]
    (or (some (fn [[path data]]
                (let [re (re-pattern (str "^" (str/replace path #":[^/]+" "[^/]+") "$"))]
                  (when (re-matches re (:uri req))
                    (when-let [h (get-in data [(:request-method req) :handler])] (h req)))))
              routes)
        {:status 404 :body "not found"})))

;; -----------------------------------------------------------------------------
;; Pure crossings

(def post-tool {:name "todos_post" :ring/method :post :ring/path "/todos/:id"
                :ring/placement {:id :path :text :body :q :query}})

(deftest args->request-is-the-request-a-human-sends
  (let [r (ex/->request post-tool {:id 7 :text "milk" :q "a b"})]
    (is (= :post (:request-method r)))
    (is (= "/todos/7" (:uri r)))
    (is (= "q=a%20b" (:query-string r)))
    (is (= {"q" "a b"} (:query-params r)))
    (is (= "application/json" (:content-type r)))
    (is (= {:text "milk"} (json/read-json (slurp (:body r)))))
    (is (= {:tool "todos_post" :args {:id 7 :text "milk" :q "a b"}} (:autonomous/call r))))
  (testing "a body that is not a map travels whole"
    (let [r (ex/->request {:ring/method :put :ring/path "/x" :ring/placement {:body :body-whole}}
                          {:body [1 2]})]
      (is (= [1 2] (json/read-json (slurp (:body r)))))))
  (testing "the principal port decides how identity crosses"
    (is (= {:actor "a"} (:autonomous/grant (ex/default-principal {:actor "a"} {}))))))

(deftest response->answer-maps-status-not-just-body
  (is (= {:ok {:a 1}} (ex/response->answer {:status 200 :headers {"Content-Type" "application/json"} :body "{\"a\":1}"})))
  (is (= {:ok {:a 1}} (ex/response->answer {:status 201 :body {:a 1}})))
  (is (= {:ok nil} (ex/response->answer {:status 204})))
  (let [a (ex/response->answer {:status 409 :body {:message "conflict"}})]
    (is (= :ring/http-409 (get-in a [:error :type])))
    (is (= "conflict" (get-in a [:error :message]))))
  (is (answer/refused? (ex/response->answer {:status 500 :body "boom"}))))

;; -----------------------------------------------------------------------------
;; Derivation

(deftest only-opted-in-routes-become-tools
  (let [s (:ok (spi/derive-surface :autonomous.ring/routes (app-routes (atom [])) {:app {:name "todo"}}))
        ts (:tools s)]
    (is (= #{"get_todos" "post_todos" "post_admin_wipe" "post_fail"} (set (keys ts)))
        "DELETE /todos/:id has no :autonomous and is not listed")
    (is (not-any? #(str/includes? % "todos__id") (keys ts)))
    (let [post (some #(when (= :post (:ring/method %)) (when (= "/todos" (:ring/path %)) %)) (vals ts))]
      (is (= #{:write} (:scopes post)))
      (is (= {:text :body} (:ring/placement post)))
      (is (= ["text"] (map name (get-in post [:json-schema :required])))))
    (is (= #{:admin} (:scopes (some #(when (= "/admin/wipe" (:ring/path %)) %) (vals ts)))))
    (is (= {} (:tools @(registry/instance))) "a fresh instance holds nothing")
    (is (not (contains? (:tools (registry/snapshot)) "todos_post")) "the global registry is untouched")))

(deftest derivation-refuses-instead-of-guessing
  (is (= :ring/parameter-clash
         (get-in (spi/derive-surface :autonomous.ring/routes
                                     [["/x/:id" {:post {:parameters {:path [:map [:id :int]] :body [:map [:id :int]]}
                                                        :autonomous {:expose true} :handler identity}}]] {})
                 [:error :type])))
  (is (= :name-clash
         (get-in (spi/derive-surface :autonomous.ring/routes
                                     [["/a" {:post {:autonomous {:expose true :id :same} :handler identity}}]
                                      ["/b" {:post {:autonomous {:expose true :id :same} :handler identity}}]] {})
                 [:error :type])))
  (is (= :autonomous/unknown-source (get-in (spi/derive-surface :nope/kind nil {}) [:error :type]))))

;; -----------------------------------------------------------------------------
;; MCP through the wrapped handler (no socket: the app's own Ring stack)

(defn- rpc [id method params] (cond-> {:jsonrpc "2.0" :method method} id (assoc :id id) params (assoc :params params)))

(defn- mcp! [h sid msg]
  (let [r (h {:request-method :post :uri "/mcp" :remote-addr "127.0.0.1"
              :headers (cond-> {"content-type" "application/json"} sid (assoc "mcp-session-id" sid))
              :body (java.io.ByteArrayInputStream. (.getBytes (json/write msg) "UTF-8"))})]
    (assoc r :json (some-> (:body r) json/read-json))))

(defn- session! [h]
  (get-in (mcp! h nil (rpc 1 "initialize" {:protocolVersion "2025-06-18"})) [:headers "Mcp-Session-Id"]))

(defn- tool-names [h sid]
  (set (map :name (get-in (mcp! h sid (rpc 2 "tools/list" nil)) [:json :result :tools]))))

(deftest mcp-through-the-wrapped-handler
  (let [db  (atom [])
        app (json-body (dispatch (app-routes db)))
        h   (ar/wrap-autonomous app {:app {:name "todo" :description "A todo list"}
                                     :routes (app-routes db)
                                     :anonymous {:actor "agent-1" :scopes #{:read :write}}})
        sid (session! h)
        names (tool-names h sid)
        post  "post_todos"
        call  #(mcp! h sid (rpc 3 "tools/call" {:name %1 :arguments %2}))]
    (is (string? sid))
    (testing "listing: opted-in routes the grant may call, plus the session built-ins"
      (is (some #{"autonomous_ledger"} names))
      (is (some #{post} names))
      (is (not-any? #(str/includes? % "admin") names) "an :admin route is hidden from a read/write grant")
      (is (not-any? #(str/includes? % "todos__id") names) "a route without opt-in is not listed"))
    (testing "a call runs the human code path, attributed to the agent"
      (let [r (call post {:text "milk"})]
        (is (= {:added "milk" :by "agent-1"} (get-in r [:json :result :structuredContent])))
        (is (= [{:text "milk" :by "agent-1"}] @db))))
    (testing "the route's malli :parameters validate before the handler runs"
      (is (true? (get-in (call post {:text ""}) [:json :result :isError])))
      (is (= 1 (count @db))))
    (testing "a non-2xx is a refusal, not a success"
      (let [r (call "post_fail" {})]
        (is (true? (get-in r [:json :result :isError])))))
    (testing "the call is in the ledger with actor and tool"
      (let [rows (get-in (call "autonomous_ledger" {:kind "autonomous/call"}) [:json :result :structuredContent :result])]
        (is (some #(and (= "agent-1" (:actor %)) (= post (:tool %))) rows))))
    (testing "other requests pass through untouched"
      (is (= 200 (:status (h {:request-method :get :uri "/todos"})))))
    (is (not (contains? (:tools (registry/snapshot)) post)) "nothing reached the global registry")))

(deftest unwrapped-handler-exposes-nothing
  (let [db (atom [])
        app (json-body (dispatch (app-routes db)))]
    (is (= 404 (:status (app {:request-method :post :uri "/mcp" :headers {}}))))
    (testing "two wraps in one process share nothing"
      (let [a (ar/wrap-autonomous app {:app {:name "a"} :routes (app-routes db)})
            b (ar/wrap-autonomous app {:app {:name "b"} :tools {:b/only {:description "only b" :read-only? true
                                                                          :handler (fn [_ _] :b)}}})]
        (is (some #{"b_only"} (tool-names b (session! b))))
        (is (not (some #{"b_only"} (tool-names a (session! a)))))
        (is (not-any? #(str/starts-with? % "post_") (tool-names b (session! b))))))))

(deftest beyond-loopback-needs-tokens-or-the-apps-auth
  (let [h (ar/wrap-autonomous (fn [_] {:status 404}) {:routes []})]
    (is (= 403 (:status (h {:request-method :post :uri "/mcp" :remote-addr "10.0.0.9" :headers {} :body "{}"}))))))

;; -----------------------------------------------------------------------------
;; reitit: the mounted handler (JVM test classpath)

(def ^:private reitit? (try (requiring-resolve 'reitit.ring/router) true (catch Exception _ false)))

(deftest reitit-mcp-handler-projects-the-router
  (when reitit?
    (let [db      (atom [])
          router  ((requiring-resolve 'reitit.ring/router)
                   (conj (app-routes db)
                         ["/mcp" {:handler (ar/mcp-handler {:app {:name "todo"}
                                                            :anonymous {:actor "agent-r" :scopes #{:read :write}}})}])
                   {:data {:middleware [json-body]}})
          h       ((requiring-resolve 'reitit.ring/ring-handler) router)
          sid     (session! h)
          names   (tool-names h sid)
          post    "post_todos"]
      (is (some #{post} names)
          (pr-str (select-keys (mcp! h nil (rpc 1 "initialize" {})) [:status :body])))
      (is (not-any? #(str/includes? % "todos__id") names))
      (is (not-any? #(str/includes? % "mcp") names) "the MCP route itself is not a tool")
      (is (= {:added "x" :by "agent-r"}
             (get-in (mcp! h sid (rpc 3 "tools/call" {:name post :arguments {:text "x"}}))
                     [:json :result :structuredContent]))
          "the call ran the router's own middleware (json-body) and handler"))))
