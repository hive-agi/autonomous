(ns sim.persona
  "Configuration example: which payment providers a persona may name.

   A charge on the payment service names its provider (a bank code or a
   provider name). Personas name only simulator providers; the real bank
   stays behind a human. Each money-moving tool gets, in this order:
   its scope, then the provider allowlist or the approval gate.

     ;; after the payment tools are registered
     (autonomous.core/decorate! (sim.persona/money-guard asker))

   `providers` must list exactly what the payment service registers (its
   knownProviders, by code and name). A provider missing here can only
   fail closed: the allowlist resolves nothing for it, so a call naming
   it goes to the human. Until the service registers sicoob-sim, a call
   naming it is allowed here and refused by the service."
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

(defn- guard-tool
  "Tool -> Tool: scope, then allowlist or approval."
  [asker scope timeout-ms]
  (comp (decorator/require-scope scope)
        (decorator/provider-allowlist {:path [:provider] :providers providers
                                       :allowed persona-allowed})
        (decorator/require-approval {:asker asker :timeout-ms timeout-ms})))

(defn money-guard
  "Surface -> Surface: on each money-moving tool (`:tools`, default
   `money-tools`), require `:scope` (default :payment/money), let a
   simulator provider through, and put any other provider to a human
   through `asker` (autonomous.approval/Asker), who has `:timeout-ms`
   (default 5 minutes) to answer. Other tools are unchanged.

   Throws ex-info :sim/money-tool-missing when a configured name is not
   on the surface: a renamed or not yet registered tool would otherwise
   move money unguarded while the guard looked installed. decorate! then
   changes nothing, so install it after the payment tools are registered."
  ([asker] (money-guard asker {}))
  ([asker {:keys [tools scope timeout-ms]
           :or {tools money-tools scope :payment/money timeout-ms (* 5 60 1000)}}]
   (let [guard (guard-tool asker scope timeout-ms)
         names (set tools)]
     (fn [surface]
       (let [present (set (map :name (vals (:tools surface))))
             missing (remove present names)]
         (when (seq missing)
           (throw (ex-info (str "money-guard: no tool named " (pr-str (vec (sort missing)))
                                " on the surface; nothing is guarded")
                           {:autonomous/error :sim/money-tool-missing :missing (vec (sort missing))})))
         (update surface :tools update-vals #(if (names (:name %)) (guard %) %)))))))
