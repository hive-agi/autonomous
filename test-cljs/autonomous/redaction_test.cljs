(ns autonomous.redaction-test
  "Merge identity and refusal redaction on a ClojureScript runtime: regex
   flags, a rejected promise through the refusal projection, and a
   promise that resolves to a marked refusal."
  (:require [autonomous.decorator :as decorator]
            [autonomous.domain.affordance :as affordance]
            [autonomous.domain.answer :as answer]
            [autonomous.runtime.invoke :as invoke]
            [autonomous.runtime.registry :as registry]
            [autonomous.surface :as surface]
            [cljs.test :refer [async deftest is]]
            [autonomous.core :as a]
            [malli.core :as m]))

(defn- tool [spec]
  (:ok (affordance/->tool :a/x (merge {:description "x" :handler (fn [_ _] 1)} spec))))

(defn- s-of [t] (assoc surface/empty-surface :tools {"a_x" t}))

(defn- merged? [a b] (answer/ok? (surface/merge-surfaces (s-of a) (s-of b))))

(deftest regexes-compare-by-source-and-flags
  (is (merged? (tool {:input [:map [:s [:re (js/RegExp. "^a+$" "i")]]]})
               (tool {:input [:map [:s [:re (js/RegExp. "^a+$" "i")]]]}))
      "two regex objects of one source and flags are one tool")
  (is (not (merged? (tool {:input [:map [:s [:re (js/RegExp. "^a+$" "i")]]]})
                    (tool {:input [:map [:s [:re (js/RegExp. "^a+$")]]]})))
      "the flags tell them apart, as on the JVM"))

(deftest a-property-registry-value-whose-form-hides-its-validator-clashes
  (let [amount  (fn [p] (m/-simple-schema {:type :my/amount :pred p}))
        opts    (fn [p] {:registry (merge (m/default-schemas) {:my/amount (amount p)})})
        strict  #(and (int? %) (< % 100))]
    (doseq [[why mk] [["A" (fn [p] (m/schema [:map {:registry {::a (m/schema [:map [:amount :my/amount]] (opts p))}} [:x ::a]]))]
                      ["B" (fn [p] (m/schema [:map {:registry {:my/amount (amount p)}} [:amount :my/amount]]))]
                      ["H" (fn [p] (m/schema [:schema {:registry {::n (m/schema [:map [:amount :my/amount]] (opts p))}}
                                              [:map [:x [:ref ::n]]]]))]]]
      (is (not (merged? (tool {:input (mk strict)}) (tool {:input (mk int?)})))
          (str why ": equal forms that validate differently never merge"))))
  (is (merged? (tool {:input (m/schema [:map {:registry {::a [:int {:min 0}]}} [:x ::a]])})
               (tool {:input (m/schema [:map {:registry {::a [:int {:min 0}]}} [:x ::a]])}))
      "a property registry of plain forms still merges"))

(def ^:private redacting
  "A tool masking [:token] in its refusal data, and paths through a string."
  ((decorator/redact-refusal [[:token] [:set-cookie 0]]) (tool {})))

(defn- settled-later
  "Settle thenable `p` through `redacting`'s projections in the default
   runtime, reset first (a promise callback runs outside any binding);
   call `k` with the parked answer once it settles."
  [p k]
  (a/reset-registry!)
  (let [id (:pending (invoke/settle p (partial decorator/project-output redacting)
                                    (partial decorator/project-refusal redacting)))]
    (js/setTimeout #(k (registry/take-settled! id)) 20)))

(deftest a-rejected-promise-goes-through-the-refusal-projection
  (async done
    (settled-later (js/Promise.reject (ex-info "denied" {:token "s3cret" :set-cookie "sid=s3cret"}))
                   (fn [a]
                     (is (= {:error {:type :tool-threw :message "denied"
                                     :data {:token "<redacted>" :set-cookie "<redacted>"}}}
                            a)
                         "masked, and a path that stops at a string masks the string, never throws nor hangs")
                     (done)))))

(deftest a-promise-of-a-marked-refusal-settles-as-that-refusal
  (async done
    (settled-later (js/Promise.resolve (invoke/return-answer (answer/refusal :t/denied "no" {:token "s3cret"})))
                   (fn [a]
                     (is (= {:error {:type :t/denied :message "no" :data {:token "<redacted>"}}} a))
                     (done)))))
