(ns autonomous.openapi
  "The :openapi Source: agent tools from an OpenAPI 3 spec, with no change
   to the service. Opt-in: only the operationIds an author names become
   tools.

     (def b (:ok (autonomous.openapi.load/load-bundle \"libs/openapi/sisf-payment.yaml\")))
     (autonomous.openapi/install! b
       {:prefix    \"payment\"
        :base-url  \"https://staging.example/api/payment\"
        :principal (autonomous.openapi.invoke/bearer token-of-persona)
        :expose    {\"getPayment\"   {:scope :payment/read}
                    \"listPayments\" {:scope :payment/read}}})

   Plain functions; the derive-surface method lives in
   autonomous.openapi.spi so it can follow the SPI wherever it lands."
  (:require [autonomous.core :as core]
            [autonomous.domain.affordance :as affordance]
            [autonomous.domain.answer :as answer]
            [autonomous.openapi.derive :as derive]
            [autonomous.openapi.invoke]))

(defn derive-tools
  "Ok [[id spec] ...] of the exposed operations, or a refusal. See
   autonomous.openapi.derive/derive-tools for opts."
  [bundle-or-doc opts]
  (derive/derive-tools bundle-or-doc opts))

(defn surface
  "Ok Surface {:app :tools {mcp-name Tool} :resources {}} of the exposed
   operations, each compiled as reg-tool compiles it, or a refusal. Pure."
  [bundle-or-doc opts]
  (let [specs (derive-tools bundle-or-doc opts)]
    (if (answer/refused? specs)
      specs
      (reduce (fn [acc [id spec]]
                (let [t (affordance/->tool id spec)]
                  (cond
                    (answer/refused? t) (reduced t)
                    (contains? (get-in acc [:ok :tools]) (get-in t [:ok :name]))
                    (reduced (answer/refusal :name-clash (str "tool name " (get-in t [:ok :name]) " is derived twice")))
                    :else (assoc-in acc [:ok :tools (get-in t [:ok :name])] (:ok t)))))
              (answer/ok {:app (or (:app opts) {:name (or (some-> (:prefix opts) name) "openapi")})
                          :tools {} :resources {}})
              (:ok specs)))))

(defn install!
  "Register the exposed operations as tools of this runtime. Answers Ok
   [mcp-name ...] or the refusal (and then registers nothing)."
  [bundle-or-doc opts]
  (let [specs (derive-tools bundle-or-doc opts)]
    (if (answer/refused? specs)
      specs
      (answer/ok (mapv (fn [[id spec]] (core/reg-tool id spec)) (:ok specs))))))
