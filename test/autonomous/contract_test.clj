(ns autonomous.contract-test
  "The contract kit against injected healthy and hostile adapter ports."
  (:require [autonomous.contract :as contract]
            [autonomous.domain.answer :as answer]
            [autonomous.spi :as spi]
            [clojure.test :refer [deftest is testing]]
            [autonomous.surface :as surface]))

(def echo-codec
  {:encode (fn [_ args]
             (if (and (map? args) (string? (:text args)))
               (answer/ok (:text args))
               (answer/refusal :test/invalid-call "expected text")))
   :decode (fn [_ output]
             (if (string? output)
               (answer/ok {:text output})
               (answer/refusal :test/invalid-output "expected text")))})

(def echo-adapter
  {:source-kind :contract-test/source
   :derive (fn [specs _]
             (answer/bind (surface/compile-tools
                           (map (fn [[id spec]] [id (assoc spec :kind :contract-test/echo)])
                                (filter (comp :expose val) specs)))
                          #(surface/->surface {:tools %})))
   :kind :contract-test/echo :codec echo-codec})

(spi/register-adapter! echo-adapter)

(def exposed
  {:contract-test/echo {:description "Echo" :input [:map [:text :string]]
                        :output [:map [:text :string]] :expose true}})

(defn fixture
  "Healthy host with explicit opted-in, unexposed and empty sources."
  []
  {:source exposed
   :unexposed-source {:contract-test/hidden (assoc (:contract-test/echo exposed) :expose false)}
   :empty-source {}
   :exposed-tools #{"contract-test_echo"}
   :samples {"contract-test_echo" {:text "hello"}}
   :human-call (constantly :human)
   :mount (fn [] :mounted)
   :host (fn [_ input _] (answer/ok input))
   :generated-samples 3})

(deftest check-adapter-answers-data-for-a-healthy-adapter
  (let [report (contract/check-adapter echo-adapter (fixture))]
    (contract/assert-contract report)
    (is (:ok? report))
    (is (= [:opt-in-only :human-code-path :unmount-empty :schema-round-trip
            :decorators-preserve-schemas :codecs-total]
           (mapv :check (:checks report))))))

(deftest source-kind-is-a-valid-adapter-reference
  (let [report (contract/check-adapter :contract-test/source (fixture))]
    (is (:ok? (first (:checks report))))
    (is (:ok? (nth (:checks report) 2)))
    (is (not (:ok? report)) "a Source key alone declares no Codec")
    (is (false? (:ok? (last (:checks report)))))))

(deftest an-adapter-without-a-codec-skips-the-round-trip
  (let [report (contract/check-adapter (dissoc echo-adapter :kind :codec) (fixture))
        by-id (into {} (map (juxt :check identity)) (:checks report))]
    (is (:ok? (:schema-round-trip by-id))
        (str "a Source without a Codec has no round trip to fail: "
             (pr-str (:detail (:schema-round-trip by-id)))))
    (is (false? (:ok? (:codecs-total by-id))) "codecs-total still requires a :codec")))

(def mixed-adapter
  "Echo tools plus one tool of another kind with no :input; its host port
   is the AdapterSpec's :invoke, not a fixture :host."
  (assoc echo-adapter
         :source-kind :contract-test/mixed-source
         :derive (fn [specs opts]
                   (answer/let-answer [echo ((:derive echo-adapter) specs opts)
                                       other (surface/compile-tools
                                              (when (some (comp :expose val) specs)
                                                [[:contract-test/other
                                                  {:description "Another kind, no :input"
                                                   :kind :contract-test/other}]]))]
                     (surface/->surface (update echo :tools merge other))))
         :invoke (fn [_ input _] (answer/ok input))))

(spi/register-adapter! (select-keys mixed-adapter [:source-kind :derive]))

(deftest the-codec-serves-its-kind-through-the-adapter-invoke-port
  (let [report (contract/check-adapter mixed-adapter (-> (fixture)
                                                 (dissoc :host)
                                                 (update :exposed-tools conj "contract-test_other")
                                                 (assoc-in [:samples "contract-test_other"] {})))]
    (contract/assert-contract report)
    (is (:ok? report) "the :invoke port is the host; the other kind is not the Codec's")))

(deftest hostile-codecs-become-failed-check-data
  (let [hostile (assoc echo-adapter :codec
                       {:encode (fn [_ _] (throw (ex-info "hostile" {})))
                        :decode (fn [_ _] {:not-an-answer true})})
        report (contract/check-adapter hostile (fixture))
        decode-hostile (assoc echo-adapter :codec {:decode (fn [_ _] {:not-an-answer true})})
        decode-report (contract/check-adapter decode-hostile (fixture))]
    (is (not (:ok? report)))
    (is (false? (:ok? (nth (:checks report) 3))) "encode failure breaks the sample round trip")
    (is (:ok? (last (:checks report))) "encode exceptions become SPI refusals")
    (is (:ok? (last (:checks decode-report))) "a missing encoder becomes an SPI refusal")
    (is (not (:ok? decode-report)) "the sample round trip requires a successful encode")))

(deftest fixture-failures-are-contained-as-check-data
  (let [report (contract/check-adapter echo-adapter
                                       (assoc (fixture) :human-call
                                              (fn [] (throw (ex-info "human failed" {})))))]
    (is (not (:ok? report)))
    (is (= :human-code-path (:check (second (:checks report)))))
    (is (false? (:ok? (second (:checks report)))))))

(deftest generated-arguments-really-cross-the-codec
  (let [seen (atom [])
        codec (update echo-codec :encode
                      (fn [encode]
                        (fn [tool args]
                          (swap! seen conj args)
                          (encode tool args))))
        report (contract/check-adapter (assoc echo-adapter :codec codec) (fixture))]
    (is (:ok? report))
    (is (some nil? @seen))
    (is (some string? @seen))
    (is (< 3 (count @seen)))))
