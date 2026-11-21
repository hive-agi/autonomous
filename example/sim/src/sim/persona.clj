(ns sim.persona
  "Configuration example: which payment providers a persona may name.

   A charge on the payment service names its provider (a bank code or a
   provider name). Personas name only simulator providers; the real bank
   stays behind a human. Each money-moving tool gets, in this order:
   its scope, then the provider allowlist or the approval gate.

     (autonomous.core/decorate!
       (autonomous.core/each-tool (sim.persona/money-guard asker)))"
  (:require [autonomous.decorator :as decorator]))

(def providers
  "The providers the payment service registers, as it resolves them: by
   code first, then by name ignoring case."
  [{:code "756"        :name "Sicoob"}
   {:code "sicoob-sim" :name "SicoobSim"}])

(def persona-allowed
  "Provider codes a persona may name without a human. A card simulator
   (card-sim) is added here, and to `providers`, once the service has it."
  ["sicoob-sim"])

(def money-tools
  "MCP names of the payment tools that move money and carry a provider."
  #{"payment_createPixCharge" "payment_createBoleto"})

(defn money-guard
  "Tool -> Tool: on a money-moving tool, require `scope` (default
   :payment/money), let a simulator provider through, and put any other
   provider to a human through `asker` (autonomous.approval/Asker), who has
   `timeout-ms` (default 5 minutes) to answer. Other tools are unchanged."
  ([asker] (money-guard asker {}))
  ([asker {:keys [scope timeout-ms] :or {scope :payment/money timeout-ms (* 5 60 1000)}}]
   (let [guard (comp (decorator/require-scope scope)
                     (decorator/provider-allowlist {:path [:provider] :providers providers
                                                    :allowed persona-allowed})
                     (decorator/require-approval {:asker asker :timeout-ms timeout-ms}))]
     (fn [tool] (if (contains? money-tools (:name tool)) (guard tool) tool)))))
