(ns autonomous.ring.surface
  "Collect and Promote strata of the Ring adapter: from route data the app
   already declares to a SURFACE VALUE {:app :tools :resources}, compiled by
   the same domain code as reg-tool (autonomous.domain.affordance), held by
   whoever derived it and never written to the process-wide registry.

   Derivation is open: methods of autonomous.spi/derive-surface. Shipped
   here: :autonomous.ring/routes (a seq of [path data], the shape reitit's
   `reitit.core/routes` answers, so no reitit is needed),
   :autonomous.reitit/router (a router, reitit resolved lazily) and
   :autonomous/tools (explicit tools only). Another router adds a method.

   Opt-in is mandatory: only a route (or one method of it) whose data says
   `:autonomous {:expose true}` becomes a tool. Pure apart from the lazy
   resolution of reitit; answers {:ok surface} or a refusal, never throws."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.domain.schema :as s]
            [autonomous.spi :as spi]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [autonomous.surface :as surface]
            [autonomous.ring.exchange :as exchange]))

(def http-methods
  "Method keys of reitit-ring route data."
  [:get :head :post :put :patch :delete :options])

(def read-only-methods #{:get :head :options})

(def parameter-locations
  "Where a route's :parameters can carry an argument, in merge order. A
   location outside this set (e.g. :header, :multipart) is refused rather
   than silently dropped."
  [:path :query :form :body])

;; =============================================================================
;; Collect: route data to one entry per (route, method)
;; =============================================================================

(defn- method-data
  "Route data of `method` on a route whose data is `data`: the route-level
   keys merged under the method's own (the method wins; :autonomous merges
   key by key)."
  [data method]
  (let [top (apply dissoc data http-methods)
        own (get data method)]
    (when own
      (-> (merge top own)
          (assoc :autonomous (merge (:autonomous top) (:autonomous own)))))))

(defn route-entries
  "One entry {:path :method :data} per method a route declares. A route
   with no method keys is one entry of method :any (a plain :handler)."
  [routes]
  (for [[path data] routes
        :let [data (or data {})
              ms   (filter #(contains? data %) http-methods)]
        entry (if (seq ms)
                (map (fn [mth] {:path path :method mth :data (method-data data mth)}) ms)
                [{:path path :method :any :data data}])]
    entry))

(defn exposed?
  "True when route data opts in: `:autonomous {:expose true}`."
  [data]
  (true? (get-in data [:autonomous :expose])))

;; =============================================================================
;; Promote: parameters to one input schema and a placement
;; =============================================================================

(defn- ->schema
  "Malli schema of route parameter model `x`: a schema or its form, or a
   lite map ({:text string?}) as reitit-malli accepts it."
  [x]
  (cond
    (and (map? x) (not (m/schema? x)))
    ((requiring-resolve 'malli.experimental.lite/schema) x)

    ;; reitit (0.7+) collects a location's models from every route level
    ;; into a vector of models; they merge into one map schema
    (and (vector? x) (seq x) (every? #(or (vector? %) (m/schema? %) (map? %)) x))
    (reduce (requiring-resolve 'malli.util/merge) (map ->schema x))

    :else (m/schema x)))

(defn- map-entries [schema]
  (when (= :map (m/type schema))
    (map (fn [[k props child]] [k props (m/form child)]) (m/children schema))))

(defn- entry-form [[k props form]]
  (if (seq props) [k props form] [k form]))

(defn- location-entries
  "[entries whole?] of parameter location `loc` with schema `schema`: its
   map keys, or for a body that is not a map the one argument :body."
  [route-id loc schema]
  (if-let [es (seq (map-entries schema))]
    [es false]
    (if (= :body loc)
      [[[:body nil (m/form schema)]] true]
      (throw (ex-info (str route-id " :parameters " loc " must be a map schema")
                      {:autonomous/error :ring/unsupported-parameters})))))

(defn compile-parameters
  "Ok {:input malli-form :placement {arg-key location}} for reitit
   `parameters` ({:path :query :form :body}): every location's map keys
   become one flat argument map, so an agent passes {:id 1 :text \"x\"}
   and the request builder puts each key back where the route reads it.
   A body that is not a map is carried whole as argument :body (location
   :body-whole). Refuses a key two locations both declare, or a location
   it cannot place."
  [route-id parameters]
  (let [unknown (remove (set parameter-locations) (keys parameters))]
    (if (seq unknown)
      (answer/refusal :ring/unsupported-parameters
                      (str route-id " declares parameters in " (vec unknown)
                           "; only " parameter-locations " can be derived"))
      (try
        (loop [[loc & more] (filter #(contains? parameters %) parameter-locations)
               entries [] placement {}]
          (if-not loc
            (answer/ok {:input (into [:map] (map entry-form) entries) :placement placement})
            (let [[es whole?] (location-entries route-id loc (->schema (get parameters loc)))
                  clash       (some #(when (contains? placement (first %)) (first %)) es)]
              (if clash
                (answer/refusal :ring/parameter-clash
                                (str route-id " declares argument " clash " in both "
                                     (name (get placement clash)) " and " (name loc)))
                (recur more (into entries es)
                       (into placement (map (fn [[k]] [k (if whole? :body-whole loc)])) es))))))
        (catch Exception e (answer/from-exception e))))))

;; =============================================================================
;; Promote: one route entry to a tool spec
;; =============================================================================

(defn route-id
  "Tool id of a route entry: `:autonomous {:id ..}` when given, else the
   route's :name (suffixed by the method when the route exposes several),
   else method and path (\"post /todos/:id\" names post_todos__id)."
  [{:keys [path method data]} several-methods?]
  (or (get-in data [:autonomous :id])
      (when-let [n (:name data)]
        (if several-methods? (keyword (namespace n) (str (name n) "-" (name method))) n))
      (str (name method) "_" (str/replace path #"^/" ""))))

(defn- scopes-of [auto]
  (let [x (or (:scopes auto) (:scope auto))]
    (cond (nil? x) nil
          (or (keyword? x) (string? x)) #{(keyword x)}
          :else (set (map keyword x)))))

(defn route->spec
  "Ok of the ToolSpec of route entry `e` (kind :ring/route), or a refusal.
   The description comes from `:autonomous {:description}`, the route's
   :description or :summary, else the method and path. `:autonomous
   {:scope s}` (or :scopes) replaces the default scopes (read for GET,
   write otherwise); `:autonomous {:result f}` replaces the default
   response-to-answer fn for this route."
  [e several-methods?]
  (let [{:keys [path method data]} e
        auto (:autonomous data)
        id   (route-id e several-methods?)
        ps   (compile-parameters id (:parameters data))]
    (if (answer/refused? ps)
      ps
      (answer/ok
       (cond-> {:id             id
                :kind           :ring/route
                :description    (or (:description auto) (:description data) (:summary data)
                                    (str (str/upper-case (name method)) " " path))
                :input          (get-in ps [:ok :input])
                :read-only?     (if (contains? auto :read-only?)
                                  (boolean (:read-only? auto))
                                  (contains? read-only-methods method))
                :ring/method    (if (= :any method)
                                  (if (get-in data [:parameters :body]) :post :get)
                                  method)
                :ring/path      path
                :ring/placement (get-in ps [:ok :placement])}
         (scopes-of auto) (assoc :scopes (scopes-of auto))
         (:title auto)    (assoc :title (:title auto))
         (:name auto)     (assoc :name (:name auto))
         (:result auto)   (assoc :ring/result (:result auto)))))))

;; =============================================================================
;; The surface value
;; =============================================================================

(defn compile-surface
  "L3 Promote: compile tool specs for a Ring surface."
  ([app specs] (compile-surface app specs []))
  ([app specs interceptors]
   (if-let [e (m/explain s/AppInfo app)]
     (answer/refusal :invalid-app "invalid :app info" (me/humanize e))
     (answer/let-answer [tools (surface/compile-tools specs)]
       (surface/->surface {:app app :tools tools :interceptors interceptors})))))

(defn routes->specs
  "L3 Promote: Ok of [id ToolSpec] pairs for the opted-in routes."
  [routes]
  (let [entries (filter (comp exposed? :data) (route-entries routes))
        per (frequencies (map :path entries))]
    (answer/reduce-answer
     (fn [specs entry]
       (answer/let-answer [spec (route->spec entry (< 1 (per (:path entry))))]
         (answer/ok (conj specs [(:id spec) spec]))))
     [] entries)))

(defn- explicit-surface
  "L3 Promote: compile the derived and explicit tools together."
  [opts derived]
  (compile-surface (:app opts {:name "app"})
                   (concat derived (:tools opts))
                   (:interceptors opts [])))

(defn- route-surface
  "L3 Source: project an explicit sequence of Ring routes."
  [routes opts]
  (answer/bind (routes->specs routes) #(explicit-surface opts %)))

(defn- router-surface
  "L3 Collect: read the optional reitit router, then project its routes."
  [router opts]
  (if-let [routes (try (requiring-resolve 'reitit.core/routes) (catch Exception _absent nil))]
    (route-surface (routes router) opts)
    (answer/refusal :ring/no-reitit
                    "add metosin/reitit-core, or pass :routes [[path data] ...]")))

(def reitit-adapter
  "L3 reitit projection plus Ring's in-process codec and host port."
  {:source-kind :autonomous.reitit/router :derive router-surface
   :kind :ring/route :codec exchange/route-codec :invoke exchange/invoke-request})

(spi/register-adapter! reitit-adapter)
(spi/register-adapter! {:source-kind :autonomous.ring/routes :derive route-surface})
(spi/register-adapter! {:source-kind :autonomous/tools :derive (fn [_ opts] (explicit-surface opts []))})
