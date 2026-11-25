(ns autonomous.spi-test
  (:require [autonomous.core :as core]
            [autonomous.domain.answer :as answer]
            [autonomous.runtime.invoke :as invoke]
            [autonomous.runtime.registry :as registry]
            [autonomous.spi :as spi]
            [autonomous.surface :as surface]
            [clojure.test :refer [deftest is testing]]))

(def echo-codec
  {:encode (fn [_ args]
             (if (int? (:n args))
               (answer/ok (:n args))
               (answer/refusal :echo/bad-call "expected an integer")))
   :decode (fn [_ n] (answer/ok {:ok n}))})

(deftest source-and-codec-can-be-installed-together
  (let [spec {:source-kind :spi-test/source
              :derive (fn [_ _] (answer/ok surface/empty-surface))
              :kind :spi-test/echo :codec echo-codec
              :invoke (fn [_ n _] (answer/ok (inc n)))}]
    (is (= {:ok spec} (spi/register-adapter! spec)))
    (is (= {:ok surface/empty-surface} (spi/derive-surface :spi-test/source nil {})))
    (registry/with-instance (registry/make-runtime)
      (fn []
        (core/reg-tool :echo {:description "Echo" :kind :spi-test/echo})
        (is (= {:ok {:ok 4}} (core/rpc {:op :call :tool "echo" :args {:n 3} :scopes [:write]})))
        (is (= :echo/bad-call (get-in (core/rpc {:op :call :tool "echo" :scopes [:write]})
                                    [:error :type])))))))

(deftest registration-validates-all-ports-first
  (doseq [spec [{} {:source-kind :unqualified :derive identity}
                {:kind :fn :codec echo-codec}
                {:source-kind :spi-test/not-installed :derive identity :kind :spi-test/no-codec}
                {:kind :spi-test/bad-codec :codec {:decode identity}}]]
    (is (= :invalid-adapter (get-in (spi/register-adapter! spec) [:error :type]))))
  (is (= :autonomous/unknown-source
         (get-in (spi/derive-surface :spi-test/not-installed nil {}) [:error :type])))
  (is (= :invalid-surface
         (do (spi/register-adapter! {:source-kind :spi-test/bad-surface :derive (fn [& _] (answer/ok 17))})
             (get-in (spi/derive-surface :spi-test/bad-surface nil {}) [:error :type])))))

(deftest codec-failures-travel-as-values
  (let [host (fn [& _] (answer/ok 7))
        throws (fn [& _] (throw (ex-info "broken port" {})))
        tool {:kind :spi-test/echo}]
    (testing "fail to encode without calling the host"
      (let [calls (atom 0)
            method (spi/codec->invoke (assoc echo-codec :encode throws)
                                      (fn [& _] (swap! calls inc) (answer/ok 0)))]
        (is (= :codec/encode (get-in (method tool {} nil) [:error :type])))
        (is (zero? @calls))))
    (testing "refusals keep non-map data"
      (let [no (answer/refusal :echo/not-found "no" [1 2])
            method (spi/codec->invoke (assoc echo-codec :decode (constantly no)) host)]
        (is (= no (invoke/settle (method tool {:n 1} nil))))))
    (testing "bad decoder output, decoder exceptions, and malformed host replies"
      (doseq [[codec port type] [[(assoc echo-codec :decode (constantly 7)) host :codec/decode]
                                 [(assoc echo-codec :decode throws) host :codec/decode]
                                 [echo-codec (constantly 7) :adapter/host]]]
        (is (= type (get-in ((spi/codec->invoke codec port) tool {:n 1} nil) [:error :type])))))))

(deftest guard-refusals-carry-the-marker
  (let [tool {:kind :spi-test/echo}]
    (doseq [[why a] [["a throwing encoder" (spi/encode-call {:encode (fn [& _] (throw (ex-info "x" {})))} tool {})]
                     ["a non-Answer decoder" (spi/decode-result {:decode (constantly 7)} tool nil)]]]
      (is (spi/guard-refusal? a) why)
      (is (true? (get-in a [:error :data spi/guard-marker])) why))
    (testing "a codec's own refusal of the same type is not the guard's"
      (let [own (spi/encode-call {:encode (fn [& _] (answer/refusal :codec/encode "mine"))} tool {})]
        (is (= :codec/encode (get-in own [:error :type])))
        (is (not (spi/guard-refusal? own)))))))
