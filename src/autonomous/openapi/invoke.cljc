(ns autonomous.openapi.invoke
  "The invoke-kind method of derived OpenAPI tools: encode, the identity
   port, the injectable HTTP fn, decode.

     args --encode--> request --principal--> request --http--> response --decode--> Answer

   The Answer is returned as the tool's value: Ok unwraps to the value, a
   refusal is raised as ex-info carrying :autonomous/error, which the
   runtime turns back into the same refusal (answer/from-exception)."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.openapi.codec :as codec]
            [autonomous.openapi.derive :as derive]
            [autonomous.runtime.invoke :as invoke]))

(defn default-http
  "L3 Boundary: the HTTP fn used when a tool names none:
   autonomous.openapi.http on the JVM and babashka, resolved lazily so pure
   users never load it. ClojureScript has none and answers {:req :error}."
  [req]
  #?(:cljs    {:req req :error (ex-info "no default HTTP fn in ClojureScript; pass :http" {})}
     :default ((requiring-resolve 'autonomous.openapi.http/request!) req)))

(defn bearer
  "L1 Promote: an identity port that sends `token-fn`'s token for the grant
   as a bearer: (bearer (fn [grant] token)). No token, request unchanged."
  [token-fn]
  (fn [grant req]
    (if-let [t (token-fn grant)]
      (assoc-in req [:headers "authorization"] (str "Bearer " t))
      req)))

(defn persona-headers
  "L1 Promote: an identity port that adds `headers-fn`'s headers for the
   grant (an Envoy-trusted persona header, for instance)."
  [headers-fn]
  (fn [grant req] (update req :headers merge (headers-fn grant))))

(defn- client-of
  "L1 Promote: Ok of the tool's client options, or a :openapi/no-base-url
   refusal when they say nowhere to send requests."
  [tool]
  (let [client (:openapi/client tool)]
    (if (:base-url client)
      (answer/ok client)
      (answer/refusal :openapi/no-base-url
                      (str (:operation-id (:openapi/operation tool)) ": no :base-url; say where requests go")))))

(defn- request
  "L1 Promote: the encoded request for `op`, signed by `principal` when
   there is one."
  [op {:keys [base-url principal]} args grant]
  (let [req (codec/encode op base-url args)]
    (if principal (principal grant req) req)))

(defn- exchange
  "L3 Boundary: decode the response to the request for `op`; what the
   encoding, principal or HTTP fn throws is an :openapi/unreachable refusal."
  [op {:keys [http] :as client} args grant]
  (try (codec/decode op ((or http default-http) (request op client args grant)))
       (catch #?(:cljs :default :default Throwable) e
         (answer/refusal :openapi/unreachable (str (:operation-id op) ": " (ex-message e))))))

(defn call
  "L2 Pipeline: Answer of calling the derived tool `tool` with decoded
   `args` for `grant`. Pure apart from the HTTP fn; never throws."
  [tool args grant]
  (answer/bind (client-of tool) #(exchange (:openapi/operation tool) % args grant)))

(defn- refusal->ex
  "L1 Promote: the ex-info refusal `a` becomes; answer/from-exception turns
   it back into the same refusal."
  [a]
  (let [{:keys [type message data]} (:error a)]
    (ex-info message (merge (when (map? data) data) {:autonomous/error type}))))

(defn- answer->value
  "L3 Boundary: the Ok value of Answer `a`; a refusal is thrown as
   `refusal->ex`."
  [a]
  (if (answer/refused? a) (throw (refusal->ex a)) (:ok a)))

(defmethod invoke/invoke-kind derive/kind [tool args grant]
  (answer->value (call tool args grant)))
