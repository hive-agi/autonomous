(ns autonomous.ws.ring
  "The bridge's websocket endpoint as Ring: a handler, and a middleware
   that composes it into an existing stack at one path.

     (-> app
         (autonomous.ws.ring/wrap-ws {:tokens runtime-token-entries}))

   Runtimes (autonomous.ws/connect!) dial this path. Two socket flavours
   are served without a hard dependency on either:
     http-kit   the request carries :async-channel (JVM and babashka)
     Ring 1.11+ websocket listeners (Jetty and friends), when
                ring.websocket.protocols is on the classpath.

   The decisions (authentication, directory, requests) are
   autonomous.bridge.ws; this namespace only adapts sockets."
  (:require [autonomous.bridge.ws :as ws]
            [clojure.string :as str]))

(def default-path "/autonomous/ws")

(defn upgrade?
  "True for a websocket upgrade request."
  [req]
  (or (true? (:websocket? req))
      (some-> (get-in req [:headers "upgrade"]) str/lower-case (= "websocket"))))

(defn- origin-ok? [opts req]
  (let [allowed (:allowed-origins opts)
        o       (get-in req [:headers "origin"])]
    (or (nil? allowed) (nil? o) (contains? (set allowed) o))))

(defn link-opts
  "The options autonomous.bridge.ws/open-link takes, from handler `opts`:
   :tokens (TokenEntry vector) or :authenticate, :hubs, :hello-timeout-ms."
  [opts]
  {:authenticate     (or (:authenticate opts) (ws/authenticator (:tokens opts)))
   :hubs             (:hubs opts ws/default-hubs)
   :hello-timeout-ms (:hello-timeout-ms opts ws/hello-timeout-ms)})

(def ^:private open-wait-ms
  "How long a frame waits for the link `on-open` creates."
  5000)

(defn- kit-response
  "http-kit: take over the request's channel. http-kit may hand the first
   frame to :on-receive before :on-open has run (a runtime sends :hello the
   moment the socket opens), so the link is a promise frames wait for, not
   an atom they could read while it is still nil."
  [lopts req]
  (let [as-channel (requiring-resolve 'org.httpkit.server/as-channel)
        send!      (requiring-resolve 'org.httpkit.server/send!)
        close      (requiring-resolve 'org.httpkit.server/close)
        link       (promise)]
    (as-channel req
                {:on-open    (fn [ch]
                               (deliver link (ws/open-link lopts {:send!  (fn [text] (send! ch text false))
                                                                  :close! (fn [_code _reason] (close ch))})))
                 :on-receive (fn [_ch msg]
                               (when-let [l (and (string? msg) (deref link open-wait-ms nil))]
                                 (ws/on-text! lopts l msg)))
                 :on-close   (fn [_ch _status] (some-> (deref link 0 nil) ws/on-close!))})))

(defn- ring-response
  "Ring 1.11+: a websocket listener response."
  [lopts]
  (if-let [listener (try (requiring-resolve 'autonomous.ws.ring-listener/listener)
                         (catch Exception _no-ring-websocket nil))]
    {:ring.websocket/listener (listener lopts)}
    {:status 501 :headers {"Content-Type" "text/plain"}
     :body "this server has no websocket support (use http-kit, or Ring 1.11+ with ring-websocket-protocols)"}))

(defn handler
  "Ring handler of the runtime endpoint. `opts`: :tokens (runtime
   TokenEntries, see autonomous.bridge.ws/authenticator) or :authenticate,
   :allowed-origins (default: any; the token is what authenticates), :hubs."
  [opts]
  (let [lopts (link-opts opts)]
    (fn [req]
      (cond
        (not (upgrade? req))     {:status 426 :headers {"Upgrade" "websocket" "Content-Type" "text/plain"}
                                  :body "dial this endpoint with a websocket (autonomous.ws/connect!)"}
        (not (origin-ok? opts req)) {:status 403 :headers {"Content-Type" "text/plain"} :body "origin not allowed"}
        (:async-channel req)     (kit-response lopts req)
        :else                    (ring-response lopts)))))

(defn wrap-ws
  "Middleware: requests to (:path opts, default \"/autonomous/ws\") go to
   the runtime endpoint (`handler`), everything else to `app`."
  [app opts]
  (let [path (:path opts default-path)
        ws-h (handler opts)]
    (fn [req]
      (if (= path (:uri req)) (ws-h req) (app req)))))
