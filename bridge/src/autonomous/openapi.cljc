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
  "L2 Pipeline: Ok [[id spec] ...] of the exposed operations, or a
   refusal. See autonomous.openapi.derive/derive-tools for opts."
  [bundle-or-doc opts]
  (derive/derive-tools bundle-or-doc opts))

(defn- empty-surface
  "L1 Promote: the initial Surface, with an explicit app or one named from
   the source prefix."
  [{:keys [app prefix]}]
  {:app (or app {:name (or (some-> prefix name) "openapi")}) :tools {} :resources {}})

(defn- add-tool
  "L1 Promote: Ok `surface` with compiled `tool`, or a :name-clash refusal
   when its name is taken."
  [surface {:keys [name] :as tool}]
  (if (contains? (:tools surface) name)
    (answer/refusal :name-clash (str "tool name " name " is derived twice"))
    (answer/ok (assoc-in surface [:tools name] tool))))

(defn- add-tool-spec
  "L2 Pipeline: one fold step: compile tool spec [id spec] and add it."
  [surface [id spec]]
  (answer/bind (affordance/->tool id spec) #(add-tool surface %)))

(defn surface
  "L2 Pipeline: Ok Surface of the exposed operations, or the first refusal."
  [bundle-or-doc opts]
  (answer/bind (derive-tools bundle-or-doc opts)
               #(answer/reduce-answer add-tool-spec (empty-surface opts) %)))

(defn install!
  "L3 Boundary: register the exposed operations in this runtime. Answers
   Ok of the tool names, or the derivation refusal without registering
   anything."
  [bundle-or-doc opts]
  (answer/bind (derive-tools bundle-or-doc opts)
               #(answer/ok (mapv (fn [[id spec]] (core/reg-tool id spec)) %))))
