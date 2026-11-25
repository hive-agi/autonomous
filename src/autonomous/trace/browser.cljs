(ns autonomous.trace.browser
  "Spans for a page, whatever framework draws it: what the person did
   (click, submit, change, Enter, navigation), what the page asked the
   network (fetch, XMLHttpRequest, with the W3C `traceparent` header on
   same-origin requests so the server's spans join the trace), and what
   went wrong (uncaught errors, unhandled rejections, console.error).

     (autonomous.trace.browser/install! {})

   A UI span is the current context while the page reacts to it: from the
   capture-phase listener until the next macrotask, so the handlers the
   event runs (and the events they dispatch) are its children. A response
   is the current context while the code that awaited it runs, by the same
   window. Each window restores the context only if it is still the one it
   set, so overlapping windows never clobber each other. Attribution across
   such windows is best effort; within an event it is exact.

   Values typed into fields are never recorded (only their length), and
   password fields not even that. Request and response bodies only with
   :payloads? true. Pure parts are in autonomous.trace.dom."
  (:require [autonomous.runtime.trace :as trace]
            [autonomous.trace.dom :as dom]
            [clojure.string :as str]))

(defonce ^:private !installed (atom #{}))

(defn- window!
  "Make `ctx` current until the next macrotask."
  [ctx]
  (set! trace/*context* ctx)
  (js/setTimeout #(when (identical? trace/*context* ctx) (set! trace/*context* nil)) 0))

;; =============================================================================
;; DOM
;; =============================================================================

(defn- attr [^js el k] (when (.-getAttribute el) (.getAttribute el k)))

(defn element-props
  "The plain facts autonomous.trace.dom/describe reads from DOM element `el`."
  [^js el]
  (when (and el (.-tagName el))
    {:tag        (.-tagName el)
     :id         (not-empty (.-id el))
     :classes    (let [c (.-className el)] (when (string? c) c))
     :text       (some-> (.-textContent el) str)
     :name       (attr el "name")
     :type       (attr el "type")
     :role       (attr el "role")
     :aria-label (attr el "aria-label")
     :testid     (or (attr el "data-testid") (attr el "data-test"))
     :href       (attr el "href")
     :parents    (loop [p (.-parentElement el) acc [] n 0]
                   (if (and p (< n 3))
                     (recur (.-parentElement p) (conj acc {:tag (.-tagName p) :id (not-empty (.-id p))}) (inc n))
                     acc))}))

(defn- ui-handler [type]
  (fn [^js e]
    (when (trace/enabled?)
      (let [el    (.-target e)
            props (element-props el)
            extra (case type
                    "change"  (dom/change-attrs props (some-> el .-value str) (some-> el .-checked))
                    "keydown" {:ui.key (.-key e)}
                    {})]
        (when (or (not= type "keydown") (#{"Enter" "Escape"} (.-key e)))
          (let [s (trace/event! (dom/ui-span-name type props) (keyword "ui" type)
                                {:parent nil :attrs (merge (dom/describe props) extra)})]
            (window! (trace/context-of s))))))))

(defn- install-dom! []
  (when (exists? js/document)
    (doseq [t ["click" "submit" "change" "keydown"]]
      (.addEventListener js/document t (ui-handler t) #js {:capture true}))
    (doseq [t ["popstate" "hashchange"]]
      (.addEventListener js/window t
                         (fn [_] (when (trace/enabled?)
                                   (window! (trace/context-of
                                             (trace/event! (str "navigate " (.. js/window -location -pathname))
                                                           :ui/navigation
                                                           {:parent nil :attrs {:url.full (.. js/window -location -href)}})))))))))

;; =============================================================================
;; Network
;; =============================================================================

(defn- same-origin? [url]
  (try (let [u (js/URL. url (.. js/window -location -href))]
         (= (.-origin u) (.. js/window -location -origin)))
       (catch :default _ false)))

(defn- propagate? [opts url]
  (if-let [p (:propagate? opts)] (p url) (and (exists? js/window) (same-origin? url))))

(defn- client-span [method url]
  (trace/start (str method " " (dom/url-path url)) :http/client
               {:kind :client :attrs {:http.request.method method :url.full url}}))

(defn- client-end! [s status extra]
  (trace/finish! s (merge-with merge (dom/client-outcome status) extra)))

(defn- install-fetch! [opts]
  (when (exists? js/fetch)
    (let [orig js/fetch]
      (set! js/globalThis.fetch
            (fn [input init]
              (if-not (trace/enabled?)
                (orig input init)
                (let [url    (if (string? input) input (str (.-url ^js input)))
                      method (str/upper-case (or (some-> init .-method) (some-> ^js input .-method) "GET"))
                      s      (client-span method url)
                      ctx    (trace/context-of s)
                      init'  (if (propagate? opts url)
                               (let [h (js/Headers. (or (some-> init .-headers)
                                                        (when (instance? js/Request input) (.-headers ^js input))))]
                                 (.set h "traceparent" (trace/traceparent-of ctx))
                                 (js/Object.assign #js {} init #js {:headers h}))
                               init)]
                  (-> (orig input init')
                      (.then (fn [^js resp]
                               (client-end! s (.-status resp) {})
                               (window! ctx)
                               resp)
                             (fn [err]
                               (trace/finish! s {:status :error :error (trace/error-info err)
                                                 :attrs {:error.type "network"}})
                               (window! ctx)
                               (throw err)))))))))))

(defn- install-xhr! [opts]
  (when (exists? js/XMLHttpRequest)
    (let [proto (.-prototype js/XMLHttpRequest)
          open  (.-open proto)
          send  (.-send proto)]
      (set! (.-open proto)
            (fn [method url & _]
              (this-as ^js xhr
                (set! (.-__autonomous xhr) #js {:method (str/upper-case (str method)) :url (str url)})
                (.apply open xhr (js-arguments)))))
      (set! (.-send proto)
            (fn [_body]
              (this-as ^js xhr
                (let [info (.-__autonomous xhr)]
                  (when (and info (trace/enabled?))
                    (let [s   (client-span (.-method info) (.-url info))
                          ctx (trace/context-of s)]
                      (when (propagate? opts (.-url info))
                        (.setRequestHeader xhr "traceparent" (trace/traceparent-of ctx)))
                      (doseq [k ["onreadystatechange" "onload"]]
                        (when-let [f (unchecked-get xhr k)]
                          (unchecked-set xhr k (fn [& args] (trace/with-context ctx #(.apply f xhr (to-array args)))))))
                      (.addEventListener xhr "loadend"
                                         (fn [_] (if (zero? (.-status xhr))
                                                   (trace/finish! s {:status :error :error {:message "network error or aborted"}
                                                                     :attrs {:error.type "network"}})
                                                   (client-end! s (.-status xhr) {}))))))
                  (.apply send xhr (js-arguments)))))))))

;; =============================================================================
;; Errors
;; =============================================================================

(defn- install-errors! [{:keys [console?] :or {console? true}}]
  (when (exists? js/window)
    (.addEventListener js/window "error"
                       (fn [^js e] (when (trace/enabled?)
                                     (trace/event! (str "uncaught: " (.-message e)) :error/uncaught
                                                   {:status :error
                                                    :error (if (.-error e) (trace/error-info (.-error e)) {:message (.-message e)})
                                                    :attrs {:code.filepath (.-filename e) :code.lineno (.-lineno e)}}))))
    (.addEventListener js/window "unhandledrejection"
                       (fn [^js e] (when (trace/enabled?)
                                     (trace/event! "unhandled rejection" :error/unhandled-rejection
                                                   {:status :error :error (trace/error-info (.-reason e))})))))
  (when (and console? (exists? js/console))
    (let [orig (.-error js/console)]
      (set! (.-error js/console)
            (fn [& args]
              (when (trace/enabled?)
                (trace/event! "console.error" :console/error
                              {:status :error
                               :error {:message (dom/clip (str/join " " (map str args)) 1000)}}))
              (.apply orig js/console (to-array args)))))))

(defn install!
  "Capture UI, network and error spans in this page and turn tracing on.
   opts: :dom? :fetch? :xhr? :errors? (each default true), :console?
   (console.error, default true), :propagate? (fn [url] bool) which
   requests carry `traceparent` (default same-origin), plus what
   autonomous.runtime.trace/enable! takes (:cap :payloads?). Each part is
   installed once per page."
  ([] (install! {}))
  ([opts]
   (trace/enable! (select-keys opts [:cap :payloads?]))
   (doseq [[k f] [[:dom install-dom!] [:fetch #(install-fetch! opts)] [:xhr #(install-xhr! opts)]
                  [:errors #(install-errors! opts)]]]
     (when (and (not= false (get opts (keyword (str (name k) "?")))) (not (@!installed k)))
       (f)
       (swap! !installed conj k)))
   nil))

(defn ^:export dump-json
  "Every span this page holds, as JSON text: what a test runner
   (Playwright's page.evaluate) saves at the end of a run."
  []
  (js/JSON.stringify (clj->js (trace/dump))))
