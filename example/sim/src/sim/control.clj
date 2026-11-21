(ns sim.control
  "Agent tools over a payment simulator's control plane: the payer paid.

     sim_pix_pay     POST /__sim/pix/{txid}/pay          {valor payerName payerCpf}?
     sim_boleto_pay  POST /__sim/boleto/{nossoNumero}/pay {valorPago}?
     sim_state       GET  /__sim/state

   The set is closed: the simulator's reset, balance and waf endpoints are
   never tools (reset wipes every open charge under a running swarm).
   Requests go to a configured loopback base URL, the local end of a
   `kubectl port-forward`; a base URL off this host is refused when the
   tools are built, so the simulator is never reached through a public
   route. Identifiers are checked as path-safe and percent-encoded.
   Transport: autonomous.openapi.http (java.net.http) unless :http is given."
  (:require [autonomous.bridge.url :as url]
            [autonomous.core :as a]
            [autonomous.openapi.codec :as codec]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(def path-safe-id
  "A txid: 1..64 of [A-Za-z0-9_-]. No '/', '.', '%' or anything to encode."
  [:re {:description "1..64 of [A-Za-z0-9_-]"} #"^[A-Za-z0-9_-]{1,64}$"])

(def nosso-numero
  "A nossoNumero: 1..20 digits."
  [:re {:description "1..20 digits"} #"^[0-9]{1,20}$"])

(def ^:private amount
  [:re {:description "amount in reais, e.g. \"1.27\""} #"^[0-9]{1,9}(\.[0-9]{1,2})?$"])

(def operations
  "The control-plane operations that are tools, by tool id. Nothing else
   the simulator serves is reachable from here."
  {:sim.pix/pay
   {:method :post :path ["__sim" "pix" :txid "pay"] :body-keys [:valor :payerName :payerCpf]
    :description (str "The payer paid a Pix charge: the simulator settles the charge with this txid "
                      "and delivers the bank's webhook to the payment service. Omit valor to pay in "
                      "full; a different valor exercises the discrepancy path, a second call "
                      "redelivery. A hybrid boleto's txid liquidates that boleto.")
    :input [:map
            [:txid path-safe-id]
            [:valor {:optional true} amount]
            [:payerName {:optional true} [:string {:min 1 :max 140}]]
            [:payerCpf {:optional true} [:re {:description "11 digits"} #"^[0-9]{11}$"]]]}

   :sim.boleto/pay
   {:method :post :path ["__sim" "boleto" :nossoNumero "pay"] :body-keys [:valorPago]
    :description (str "The payer paid a boleto: the simulator liquidates the boleto with this "
                      "nossoNumero and delivers the boleto webhook. Omit valorPago to pay in full.")
    :input [:map
            [:nossoNumero nosso-numero]
            [:valorPago {:optional true} [:and number? [:>= 0]]]]}

   :sim/state
   {:method :get :path ["__sim" "state"] :body-keys [] :read-only? true
    :description "What the simulated bank holds: its charges, its boletos and the webhook URL it delivers to."
    :input [:map]}})

(def forbidden-segments
  "Control-plane endpoints no tool may reach."
  #{"reset" "balance" "waf"})

(defn- segment
  "L1: one path segment of `args`: a literal, or an argument value that
   must be path safe, percent-encoded. Throws on anything else (the input
   schema already refused it; this holds when a handler is called directly)."
  [args s]
  (if (keyword? s)
    (let [v (str (get args s))]
      (when-not (re-matches #"[A-Za-z0-9_-]{1,64}" v)
        (throw (ex-info (str (name s) " is not a path-safe identifier")
                        {:autonomous/error :sim/invalid-id :arg s})))
      (codec/url-encode v))
    s))

(defn request-path
  "L1: the path of operation `op` for `args`, every argument segment
   checked and percent-encoded."
  [op args]
  (str "/" (str/join "/" (map (partial segment args) (:path op)))))

(defn- body-of
  "L1: the JSON body of `args` for `op`, nil when it sends none."
  [op args]
  (let [b (select-keys args (:body-keys op))]
    (when (seq b) (json/write-str b))))

(defn- request
  "L1: the ring-style request of `op` with `args` against `cfg`."
  [{:keys [base-url timeout-ms]} op args]
  (let [body (body-of op args)]
    (cond-> {:method (:method op)
             :url (str (str/replace base-url #"/+$" "") (request-path op args))
             :headers {"accept" "application/json"}
             :timeout-ms timeout-ms}
      body (-> (assoc :body body) (assoc-in [:headers "content-type"] "application/json")))))

(defn- parsed
  "L1: the JSON body of `resp`, or its text when it is not JSON."
  [resp]
  (let [b (:body resp)]
    (if (str/blank? b) nil (try (json/read-str b :key-fn keyword) (catch Exception _ b)))))

(defn- value-of
  "L3 Boundary: the tool value of response `resp` of tool `id`: the parsed
   body of a 2xx, else a thrown refusal (:sim/unreachable, :sim/refused)."
  [id resp]
  (let [status (:status resp)]
    (cond
      (or (:error resp) (not (int? status)))
      (throw (ex-info (str (name id) ": the simulator did not answer ("
                           (some-> (:error resp) ex-message) "); is the port-forward up?")
                      {:autonomous/error :sim/unreachable}))
      (<= 200 status 299) (parsed resp)
      :else (let [body (parsed resp)]
              (throw (ex-info (str (name id) ": the simulator answered " status
                                   (when-let [e (and (map? body) (:error body))] (str ": " e)))
                              {:autonomous/error :sim/refused :status status :body body}))))))

(defn- handler
  "L3 Boundary: the handler of tool `id`."
  [cfg id op]
  (fn [args _grant]
    (value-of id ((:http cfg) (request cfg op args)))))

(defn- default-http [req]
  ((requiring-resolve 'autonomous.openapi.http/request!) req))

(defn- uri-view
  "L3 Boundary: {:scheme :host :port :path} of `s` as java.net.URI reads
   it, or nil when it does not parse, has no host, carries userinfo,
   query or fragment, or has a non-numeric port."
  [s]
  (try
    (let [u (java.net.URI. s)]
      (when (and (.getHost u) (nil? (.getRawUserInfo u)) (nil? (.getRawQuery u)) (nil? (.getRawFragment u))
                 (= (.getRawAuthority u) (str (.getHost u) (when (pos? (.getPort u)) (str ":" (.getPort u))))))
        {:scheme (some-> (.getScheme u) str/lower-case) :host (str/lower-case (.getHost u))
         :port (when (pos? (.getPort u)) (str (.getPort u))) :path (str (.getRawPath u))}))
    (catch Exception _ nil)))

(defn loopback-base-url?
  "L1: is `s` an http(s) URL of this host with no path, userinfo, query or
   fragment, read the same by autonomous.bridge.url and java.net.URI? Both
   must say loopback and agree on scheme, host and port; any disagreement
   refuses."
  [s]
  (boolean
   (when (string? s)
     (let [{:keys [scheme host port path]} (url/parts s)
           theirs (uri-view s)]
       (and theirs host
            (url/clean? s) (url/loopback? s) (url/loopback? (str "http://" (:host theirs)))
            (#{"http" "https"} scheme)
            (= [scheme host port] [(:scheme theirs) (:host theirs) (:port theirs)])
            (str/blank? (str/replace (str path) "/" ""))
            (str/blank? (str/replace (:path theirs) "/" "")))))))

(defn- checked-config
  "L1: `opts` with defaults, or throws :invalid-sim-config: the base URL
   must pass loopback-base-url? (a port-forward on this host)."
  [{:keys [base-url http timeout-ms pay-scopes] :as opts}]
  (when-not (loopback-base-url? base-url)
    (throw (ex-info (str "sim.control: :base-url must be a loopback http URL with no path, "
                         "the local end of a kubectl port-forward; got " (pr-str base-url))
                    {:autonomous/error :invalid-sim-config :base-url base-url})))
  {:base-url base-url
   :http (or http default-http)
   :timeout-ms (or timeout-ms 10000)
   :pay-scopes (set (or pay-scopes [:write :sim/pay]))
   :opts opts})

(defn tool-specs
  "L1: [[id spec] ...] of the control-plane tools for opts:
     :base-url    loopback URL of the port-forward, e.g. http://127.0.0.1:18099 [required]
     :http        (fn [request] response), default autonomous.openapi.http/request!
     :timeout-ms  per request, default 10000
     :pay-scopes  scopes the pay tools require, default [:write :sim/pay]
   sim_state needs :read. Throws ex-info :invalid-sim-config on a bad base URL."
  [opts]
  (let [cfg (checked-config opts)]
    (vec (for [[id op] (sort-by key operations)]
           [id (cond-> {:description (:description op)
                        :input (:input op)
                        :read-only? (boolean (:read-only? op))
                        :handler (handler cfg id op)}
                 (not (:read-only? op)) (assoc :scopes (:pay-scopes cfg)))]))))

(defn install!
  "L3 Boundary: register the control-plane tools in this runtime (see
   tool-specs for opts). Answers their MCP names."
  [opts]
  (mapv (fn [[id spec]] (a/reg-tool id spec)) (tool-specs opts)))
