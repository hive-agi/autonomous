(ns autonomous.ring
  "Agent-operability as Ring middleware: one line in the composition the app
   already has.

     ;; reitit (primary): mount a handler, opt routes in with route data
     [\"/mcp\" {:handler (autonomous.ring/mcp-handler {:app {:name \"todo\"}})}]
     [\"/todos\" {:post {:parameters {:body [:map [:text :string]]}
                         :autonomous {:expose true :scope :write}
                         :handler create-todo}}]

     ;; plain Ring: wrap, with explicit tools or a route-data seq
     (autonomous.ring/wrap-autonomous handler {:routes [[\"/todos\" {...}]]})

   Either serves MCP (autonomous.mcp.server/http, unchanged) at its path.
   A route tool is a dimap over the app's handler: args -> Ring request
   (autonomous.ring.exchange/->request, pure), the app's real handler chain,
   Ring response -> Answer (exchange/response->answer, pure, overridable per
   route with :autonomous {:result f}). Calls are planned by the same
   decision code as every tool (scopes, lease, malli args), journalled and
   drained into the ledger, attributed to the agent's actor.

   No global registry is written: the surface is a VALUE derived once
   (autonomous.spi/derive-surface) and held, with its own runtime state
   (journal, lease, pending), by the engine this namespace builds. Removing
   the wrap or the route removes the agent surface; two wrapped apps in one
   process share nothing.

   JVM and babashka (http-kit): no class beyond java.io and java.net is
   named, and reitit is resolved lazily, only when a router is the source."
  (:require [autonomous.bridge.client :as client]
            [autonomous.bridge.ledger :as ledger]
            [autonomous.bridge.local :as local]
            [autonomous.domain.answer :as answer]
            [autonomous.mcp.http :as http]
            [autonomous.mcp.json :as json]
            [autonomous.mcp.server :as server]
            [autonomous.ring.exchange :as exchange]
            [autonomous.ring.surface]
            [autonomous.runtime.registry :as registry]
            [autonomous.spi :as spi]
            [clojure.string :as str]))

(def default-path "/mcp")

;; =============================================================================
;; Invocation: the route tool kind (open seam of autonomous.runtime.invoke)
;; =============================================================================

(defn run-route
  "L3 Pipeline: run a mounted Ring tool through its registered codec."
  [tool args grant]
  ((spi/codec->invoke exchange/route-codec exchange/invoke-request) tool args grant))

;; =============================================================================
;; Engine: one surface, its own runtime state, an MCP server over it
;; =============================================================================

(defn- bind-routes
  "Surface `s` with every route tool carrying the app `handler` it calls and
   the `principal` port."
  [s handler principal]
  (update s :tools update-vals
          #(if (= :ring/route (:kind %))
             (assoc % :ring/handler handler :ring/principal principal)
             %)))

(defn engine
  "Ok of an engine {:srv :http-state :cfg :registry} serving surface value
   `surface` for app `handler`, or a refusal. opts: :principal (fn [grant
   request] request), :ledger (an ILedger, or config such as
   {:ledger/kind :memory}, the default), :target-id, plus the HTTP config
   autonomous.mcp.http/route reads (:tokens :allowed-origins :anonymous
   :session-idle-ms :max-sessions)."
  [surface handler opts]
  (let [st   (registry/instance (bind-routes surface handler (:principal opts exchange/default-principal)))
        tid  (or (:target-id opts) (get-in surface [:app :name]) "app")
        led  (let [l (:ledger opts {:ledger/kind :memory})]
               (if (and (map? l) (:ledger/kind l)) (ledger/open l) l))
        ctx  (client/context {:target  {:target/kind :local :target/id tid}
                              :channel (local/channel {:target/kind :local :target/id tid} st)
                              :ledger  led})]
    (answer/ok {:srv        (server/server ctx)
                :http-state (atom {:sessions {}})
                :cfg        (select-keys opts [:tokens :allowed-origins :anonymous :new-id :clock
                                               :session-idle-ms :max-sessions :grant-fn])
                :registry   st})))

;; =============================================================================
;; HTTP crossing: the app's Ring request to autonomous.mcp.http's request map
;; =============================================================================

(def loopback-addrs #{"127.0.0.1" "0:0:0:0:0:0:0:1" "::1" "localhost"})

(defn- read-body [body]
  (cond (nil? body) ""
        (string? body) body
        :else (slurp body)))

(defn- caller-refusal
  "nil when `request` may reach the MCP surface, else the Ring response
   refusing it. With no :tokens and no :grant-fn, only a loopback caller is
   served, the same rule autonomous.mcp.http enforces at bind time."
  [cfg request]
  (cond
    (seq (:tokens cfg)) nil
    (:grant-fn cfg)     (when-not ((:grant-fn cfg) request)
                          {:status 401 :headers {"Content-Type" "application/json"}
                           :body "{\"error\":\"not authenticated\"}"})
    (loopback-addrs (:remote-addr request)) nil
    :else {:status 403 :headers {"Content-Type" "application/json"}
           :body "{\"error\":\"configure :tokens or :grant-fn to serve agents beyond loopback\"}"}))

(defn serve
  "Ring response of engine `eng` for Ring `request` aimed at the MCP path."
  [eng request]
  (or (caller-refusal (:cfg eng) request)
      (let [raw  (read-body (:body request))
            ;; body middleware the app mounted above (muuntaja, wrap-json-body)
            ;; may have consumed the stream: take what it parsed
            text (if (and (str/blank? raw) (some? (:body-params request)))
                   (json/write (:body-params request))
                   raw)
            cfg  (cond-> (:cfg eng)
                   (and (:grant-fn (:cfg eng)) (empty? (:tokens (:cfg eng))))
                   (assoc :anonymous ((:grant-fn (:cfg eng)) request)))]
        (http/handle-request (:srv eng) (:http-state eng) cfg
                             {:method  (str/upper-case (name (:request-method request)))
                              :path    "/mcp"
                              :headers (into {} (map (fn [[k v]] [(str/lower-case (name k)) v]))
                                             (:headers request))
                              :body    text
                              :too-big (> (count text) http/max-body-bytes)}))))

;; =============================================================================
;; Plain Ring: the wrap
;; =============================================================================

(defn- source-of
  "[kind source] of wrap opts: :router, :routes, else explicit :tools only.
   :source-kind with :source picks any autonomous.spi/derive-surface method."
  [opts]
  (cond (:source-kind opts) [(:source-kind opts) (:source opts)]
        (:router opts)      [:autonomous.reitit/router (:router opts)]
        (:routes opts)      [:autonomous.ring/routes (:routes opts)]
        :else               [:autonomous/tools nil]))

(defn wrap-autonomous
  "Ring middleware: `handler` plus an MCP surface at (:path opts \"/mcp\").
   Every other request passes through untouched. Route tools call
   `handler` itself (the chain inside the wrap), so place the wrap
   OUTSIDE the middleware a human request needs (body parsing, auth
   adapters): agent calls then run exactly that chain.

   opts: :app (AppInfo), :routes ([path data] seq) or :router (reitit) or
   :source-kind/:source, :tools ({id spec}, e.g. :kind :fn), :path,
   :principal, :ledger, :tokens, :grant-fn (fn [request] grant-or-nil, the
   app's own auth deciding the agent's identity), :allowed-origins.

   Throws ex-info at wrap time when the surface cannot be derived (an
   author error should fail while the app loads, as reg-tool does)."
  [handler opts]
  (let [path         (:path opts default-path)
        [kind src]   (source-of opts)
        surface      (spi/derive-surface kind src opts)
        eng          (when (answer/ok? surface) (engine (:ok surface) handler opts))]
    (when (answer/refused? surface)
      (throw (ex-info (str "wrap-autonomous: " (get-in surface [:error :message]))
                      {:autonomous/error (get-in surface [:error :type]) :refusal surface})))
    (fn
      ([request]
       (if (= path (:uri request))
         (serve (:ok eng) request)
         (handler request)))
      ([request respond raise]
       (if (= path (:uri request))
         (try (respond (serve (:ok eng) request)) (catch Exception e (raise e)))
         (handler request respond raise))))))

;; =============================================================================
;; reitit: the mounted handler projecting the router's opted-in routes
;; =============================================================================

(defn- router-handler
  "The full reitit-ring handler of `router` (route middleware included),
   reitit.ring resolved lazily."
  [router]
  ((requiring-resolve 'reitit.ring/ring-handler) router))

(defn mcp-handler
  "A Ring handler to mount at a reitit route (like reitit-swagger's
   create-swagger-handler): it reads :reitit.core/router from the request
   and PROJECTS the routes that opt in (`:autonomous {:expose true}`) into
   an MCP surface, derived once per router. A call runs the route through
   (:handler opts) when given (the app's whole handler, outer middleware
   included), else through reitit.ring/ring-handler of the router.

   opts as wrap-autonomous (no :routes/:router: the request carries it)."
  [opts]
  (let [engines (atom {})
        engine-for
        (fn [router]
          (or (get @engines router)
              (let [s (spi/derive-surface :autonomous.reitit/router router opts)
                    h (or (:handler opts)
                          (let [d (delay (router-handler router))] (fn [req] (@d req))))
                    e (if (answer/ok? s) (engine (:ok s) h opts) s)]
                (swap! engines (fn [m] (if (contains? m router) m (assoc m router e))))
                (get @engines router))))
        sync
        (fn [request]
          (if-let [router (:reitit.core/router request)]
            (let [e (engine-for router)]
              (if (answer/ok? e)
                (serve (:ok e) request)
                {:status 500 :headers {"Content-Type" "application/json"}
                 :body (json/write (:error e))}))
            {:status 500 :headers {"Content-Type" "application/json"}
             :body (json/write {:error "mcp-handler must be mounted in a reitit-ring router"})}))]
    (fn
      ([request] (sync request))
      ([request respond raise]
       (try (respond (sync request)) (catch Exception e (raise e)))))))
