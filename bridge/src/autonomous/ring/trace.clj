(ns autonomous.ring.trace
  "Server spans as Ring middleware: the backend half of a full-stack trace.

     (-> handler
         (autonomous.ring.trace/wrap-trace {:route-of (fn [req] (:template (reitit/get-match req)))}))

   A request that carries a W3C `traceparent` (the browser adapter adds it
   to same-origin fetch and XHR) continues that trace: its :http/server span
   is a child of the browser's :http/client span, and anything the handler
   records (trace/in-span, a call to another service with trace/headers) is
   a child of the server span. A request without one starts a trace.

   Attributes follow the OpenTelemetry HTTP semantic conventions
   (http.request.method, url.path, http.route, http.response.status_code;
   a 5xx or a throw is an error). Query strings and bodies are kept only
   with :payloads? true.

   opts:
     :route-of  (fn [request] route template) for the span name and
                http.route (default: none, the name is the method and path)
     :payloads? keep url.query (default false)
     :registry  a registry instance (autonomous.runtime.registry/instance)
                to record into instead of the process-wide one
     :skip?     (fn [request] bool): requests not traced (default: none)

   Spans are recorded only while tracing is enabled in that registry
   (autonomous.runtime.trace/enable!). Sync and async (3-arity) Ring."
  (:require [autonomous.runtime.registry :as registry]
            [autonomous.runtime.trace :as trace]))

(defn request-attrs
  "Server span attributes of Ring `request`. Pure."
  [request route payloads?]
  (cond-> {:http.request.method (some-> (:request-method request) name .toUpperCase)
           :url.path            (:uri request)
           :url.scheme          (some-> (:scheme request) name)}
    route                                     (assoc :http.route route)
    (:server-name request)                    (assoc :server.address (:server-name request))
    (get-in request [:headers "user-agent"])  (assoc :user_agent.original (get-in request [:headers "user-agent"]))
    (and payloads? (:query-string request))   (assoc :url.query (:query-string request))))

(defn response-outcome
  "How a server span ends for Ring `response`. Pure."
  [response]
  (let [status (:status response)]
    (cond-> {:attrs {:http.response.status_code status}}
      (and (number? status) (>= status 500))
      (assoc :status :error :error {:message (str "HTTP " status)}))))

(defn- span-name [request route]
  (str (some-> (:request-method request) name .toUpperCase) " " (or route (:uri request))))

(defn wrap-trace
  "Ring middleware recording one :http/server span per request. See the
   namespace doc for `opts`."
  ([handler] (wrap-trace handler {}))
  ([handler {:keys [route-of payloads? registry skip?]}]
   (let [in-registry (fn [f] (if registry (registry/with-instance registry f) (f)))
         start       (fn [request]
                       (let [route  (when route-of (route-of request))
                             parent (trace/incoming (get-in request [:headers "traceparent"]))]
                         (trace/start (span-name request route) :http/server
                                      {:parent parent :kind :server
                                       :attrs (request-attrs request route payloads?)})))
         traced?     (fn [request] (and (trace/enabled?) (not (and skip? (skip? request)))))]
     (fn
       ([request]
        (in-registry
         (fn []
           (if-not (traced? request)
             (handler request)
             (let [s (start request)]
               (try
                 (let [resp (trace/with-context (trace/context-of s) #(handler request))]
                   (trace/finish! s (response-outcome resp))
                   resp)
                 (catch Throwable t
                   (trace/finish! s {:status :error :error (trace/error-info t)
                                     :attrs {:error.type (str (type t))}})
                   (throw t))))))))
       ([request respond raise]
        (in-registry
         (fn []
           (if-not (traced? request)
             (handler request respond raise)
             (let [s   (start request)
                   ctx (trace/context-of s)
                   reg (or registry nil)
                   end (fn [extra] (if reg (registry/with-instance reg #(trace/finish! s extra)) (trace/finish! s extra)))]
               (trace/with-context ctx
                 #(handler request
                           (fn [resp] (end (response-outcome resp)) (respond resp))
                           (fn [t] (end {:status :error :error (trace/error-info t)
                                         :attrs {:error.type (str (type t))}})
                             (raise t)))))))))))))
