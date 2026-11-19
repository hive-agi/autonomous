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
  "The HTTP fn used when a tool names none: autonomous.openapi.http on
   the JVM and babashka. Resolved lazily so pure users never load it."
  [req]
  #?(:cljs    {:req req :error (ex-info "no default HTTP fn in ClojureScript; pass :http" {})}
     :default ((requiring-resolve 'autonomous.openapi.http/request!) req)))

(defn bearer
  "An identity port that sends `token-fn`'s token for the grant as a
   bearer: (bearer (fn [grant] token))."
  [token-fn]
  (fn [grant req]
    (if-let [t (token-fn grant)]
      (assoc-in req [:headers "authorization"] (str "Bearer " t))
      req)))

(defn persona-headers
  "An identity port that adds `headers-fn`'s headers for the grant (an
   Envoy-trusted persona header, for instance)."
  [headers-fn]
  (fn [grant req] (update req :headers merge (headers-fn grant))))

(defn call
  "Answer of calling the derived tool `tool` with decoded `args` for
   `grant`. Pure apart from the HTTP fn; never throws."
  [tool args grant]
  (let [op                               (:openapi/operation tool)
        {:keys [base-url http principal]} (:openapi/client tool)]
    (if-not base-url
      (answer/refusal :openapi/no-base-url
                      (str (:operation-id op) ": no :base-url; say where requests go"))
      (try
        (let [req  (codec/encode op base-url args)
              req  (if principal (principal grant req) req)
              resp ((or http default-http) req)]
          (codec/decode op resp))
        (catch #?(:cljs :default :default Throwable) e
          (answer/refusal :openapi/unreachable
                          (str (:operation-id op) ": " (ex-message e))))))))

(defn- answer->value [a]
  (if (answer/refused? a)
    (let [{:keys [type message data]} (:error a)]
      (throw (ex-info message (merge (when (map? data) data) {:autonomous/error type}))))
    (:ok a)))

(defmethod invoke/invoke-kind derive/kind [tool args grant]
  (answer->value (call tool args grant)))
