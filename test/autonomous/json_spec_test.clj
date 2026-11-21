(ns autonomous.json-spec-test
  "The JSON tool-spec form: JSON Schema and malli-as-EDN inputs, $refs
   shared across a document, refusals that name the problem, and a
   registered tool that validates like an EDN one."
  (:require [autonomous.core :as a]
            [autonomous.domain.affordance :as affordance]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.wire :as wire]
            [autonomous.json-spec :as js]
            [autonomous.json-spec.load :as load]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each (fn [t] (a/reset-registry!) (t) (a/reset-registry!)))

(def fixture "test/fixtures/json_spec/inventory.json")

(def specs (into {} (:ok (load/read-file fixture))))

(deftest a-document-yields-its-tools-in-order
  (is (= [:inventory/receive :inventory/stock] (mapv first (:ok (load/read-file fixture))))))

(deftest json-schema-inputs-become-malli-with-refs-resolved
  (let [receive (specs :inventory/receive)]
    (is (= [:map
            [:note {:optional true} [:string]]
            [:qty [:int {:min 1}]]
            [:sku [:string {:min 1 :description "Stock keeping unit"}]]]
           (:input receive)))
    (is (= #{:write} (:scopes receive)))
    (is (= [:map [:on_hand [:int]] [:sku [:string {:min 1 :description "Stock keeping unit"}]]]
           (:output receive))
        "output is converted too, sharing the document's $defs")))

(deftest malli-edn-inputs-and-the-other-fields-carry-over
  (let [stock (specs :inventory/stock)]
    (is (= [:map] (:input stock)))
    (is (true? (:read-only? stock)))
    (is (= {:idempotentHint true} (:annotations stock)))
    (is (not (contains? stock :scopes)) "absent scopes keep the runtime's default")))

(deftest a-registered-json-tool-validates-like-an-edn-one
  (is (= {:ok ["inventory_receive" "inventory_stock"]}
         (load/register! (vec specs) {:inventory/receive (fn [{:keys [sku qty]} _] {:sku sku :on_hand qty})
                                      :inventory/stock   (fn [_ _] {})})))
  (testing "lenient decoding, then validation"
    (is (= {:ok {:sku "a" :on_hand 3}}
           (a/rpc {:op :call :tool "inventory_receive" :args {:sku "a" :qty "3"} :scopes #{:write}})))
    (is (= :invalid-args
           (get-in (a/rpc {:op :call :tool "inventory_receive" :args {:sku "" :qty 0} :scopes #{:write}})
                   [:error :type]))))
  (testing "a read-only tool needs only :read"
    (is (answer/ok? (a/rpc {:op :call :tool "inventory_stock" :args {} :scopes #{:read}})))))

(deftest the-edn-text-is-what-the-c-abi-reads
  (doseq [[id spec] specs]
    (let [back (wire/read-edn (load/edn-text spec))]
      (is (= spec back))
      (is (answer/ok? (affordance/->tool id (assoc back :handler (fn [_ _] nil))))))))

(deftest refusals-name-the-problem
  (is (= "tool (no id) JSON spec needs an \"id\""
         (get-in (js/->tool-spec {"description" "x"}) [:error :message])))
  (is (= {"description" ["should be at least 1 character"]}
         (get-in (js/->tool-spec {"id" "a" "description" ""}) [:error :data])))
  (is (= "should be a JSON Schema object or a string of malli EDN"
         (first (get-in (js/->tool-spec {"id" "a" "description" "d" "input" 3}) [:error :data "input"])))
      "a schema is a JSON Schema object or malli EDN text")
  (is (= "tool a input is not readable EDN"
         (get-in (js/->tool-spec {"id" "a" "description" "d" "input" "[:map"}) [:error :message])))
  (is (= "tool a is declared twice"
         (get-in (js/document->specs [{"id" "a" "description" "d"} {"id" "a" "description" "e"}])
                 [:error :message])))
  (is (= :invalid-tool (get-in (js/document->specs {"x" 1}) [:error :type])))
  (is (= :invalid-tool (get-in (js/->tool-spec ["not" "an" "object"]) [:error :type]))))

(deftest an-explicit-id-wins-over-the-spec's-own
  (is (= :b/c (first (:ok (js/->tool-spec "b/c" {"id" "a" "description" "d"}))))))

(deftest a-non-object-json-schema-input-is-refused-at-registration
  (let [[id spec] (:ok (js/->tool-spec {"id" "a" "description" "d" "input" {"type" "string"}}))]
    (is (= :invalid-tool (get-in (affordance/->tool id (assoc spec :handler (fn [_ _] nil))) [:error :type])))))

(deftest the-loader-refuses-instead-of-throwing
  (is (= :invalid-tool (get-in (load/parse "{nope") [:error :type])))
  (is (= "no JSON spec file /no/such.json" (get-in (load/read-file "/no/such.json") [:error :message])))
  (is (= {:missing [:inventory/stock]}
         (get-in (load/register! (vec specs) {:inventory/receive (fn [_ _] nil)}) [:error :data])))
  (is (empty? (:tools (a/rpc {:op :manifest :scopes #{:admin}}))) "nothing registered on a refusal"))

(defn- input-refusal
  "The refusal of a one-tool document whose input has property p = `p`."
  [p]
  (js/document->specs {"$defs" {"Sku"  {"type" "string"}
                                "Node" {"type" "object" "properties" {"next" {"$ref" "#/$defs/Node"}}}}
                       "tools" [{"id" "a" "description" "d"
                                 "input" {"type" "object" "properties" {"p" p}}}]}))

(deftest a-schema-that-would-accept-anything-is-refused
  (doseq [[why p msg] [["a typo in a local $ref" {"$ref" "#/$defs/Skuu"}
                        "tool a input $ref #/$defs/Skuu does not resolve within the document at [\"properties\" \"p\"]"]
                       ["a path out of the document" {"$ref" "../../etc/passwd#/x"}
                        "tool a input $ref ../../etc/passwd#/x does not resolve within the document at [\"properties\" \"p\"]"]
                       ["a remote document" {"$ref" "http://evil/x.json#/y"}
                        "tool a input $ref http://evil/x.json#/y does not resolve within the document at [\"properties\" \"p\"]"]
                       ["a cycle" {"$ref" "#/$defs/Node"}
                        "tool a input $ref #/$defs/Node refers to itself at [\"properties\" \"p\" \"properties\" \"next\"]"]
                       ["an unknown type" {"type" "integr"}
                        "tool a input type \"integr\" is not a JSON Schema type at [\"properties\" \"p\"]"]
                       ["an unknown type deep inside" {"type" "array" "items" {"anyOf" [{"type" "string"} {"type" "strin"}]}}
                        "tool a input type \"strin\" is not a JSON Schema type at [\"properties\" \"p\" \"items\" \"anyOf\" 1]"]]]
    (testing why
      (let [a (input-refusal p)]
        (is (= :invalid-tool (get-in a [:error :type])))
        (is (= msg (get-in a [:error :message])))
        (is (seq (get-in a [:error :data :problems])) "every problem is listed for the author")))))

(deftest output-schemas-are-checked-the-same-way
  (is (= "tool a output $ref #/nope does not resolve within the document"
         (get-in (js/->tool-spec {"id" "a" "description" "d" "output" {"$ref" "#/nope"}}) [:error :message]))))

(deftest a-ref-that-resolves-still-registers
  (is (= [:map [:p {:optional true} [:string]]] (-> (input-refusal {"$ref" "#/$defs/Sku"}) :ok first second :input))))

(deftest every-spec-is-checked-as-a-tool
  (is (= "tool :a :input is not a malli schema"
         (get-in (js/->tool-spec {"id" "a" "description" "d" "input" "[:mapp [:a :intt]]"}) [:error :message])))
  (is (= "tool a input is blank: write \"[:map]\" or leave input out"
         (get-in (js/->tool-spec {"id" "a" "description" "d" "input" "  "}) [:error :message])))
  (is (= :invalid-tool
         (get-in (js/->tool-spec {"id" "a" "description" "d" "input" "[:string]"}) [:error :type]))
      "an input that is not a map is refused before anyone registers it"))

(deftest an-unknown-key-is-refused-and-a-look-alike-named
  (doseq [[k v hint] [["scope" ["admin"] "unknown key; did you mean \"scopes\"?"]
                      ["readonly" true "unknown key; did you mean \"readOnly\"?"]
                      ["inputSchema" {"type" "object"} "unknown key; did you mean \"input\"?"]
                      ["handler" "rm -rf" "unknown key"]]]
    (testing k
      (let [a (js/->tool-spec {"id" "a" "description" "d" k v})]
        (is (= (str "tool a JSON spec has unknown key " (pr-str k)) (get-in a [:error :message])))
        (is (= {k hint} (get-in a [:error :data])))))))

(deftest the-real-keys-set-scopes-and-read-only
  (let [[_ spec] (:ok (js/->tool-spec {"id" "a" "description" "d" "scopes" ["admin"] "readOnly" true}))]
    (is (= #{:admin} (:scopes spec)))
    (is (true? (:read-only? spec)))))

(deftest a-single-tool-document-may-carry-its-own-defs
  (is (= [:map [:p {:optional true} [:int]]]
         (-> (load/parse "{\"$schema\":\"x\",\"$defs\":{\"N\":{\"type\":\"integer\"}},\"id\":\"a\",\"description\":\"d\",
                          \"input\":{\"type\":\"object\",\"properties\":{\"p\":{\"$ref\":\"#/$defs/N\"}}}}")
             :ok first second :input))))

(deftest malli-edn-that-carries-code-needs-the-host's-opt-in
  (let [spec {"id" "a" "description" "d"}]
    (doseq [[why input] [["a :fn schema" "[:map [:x [:fn (fn [_] true)]]]"]
                         ["a :fn deep in a map" "[:map [:x [:vector [:fn pos?]]]]"]
                         ["a bare call" "[:map [:x (System/exit 0)]]"]
                         ["a :fn inside properties" "[:map {:x [:fn odd?]} [:y :int]]"]]]
      (testing why
        (is (re-find #"^tool a input carries code"
                     (str (get-in (js/->tool-spec (assoc spec "input" input)) [:error :message]))))))
    (testing "the host may allow it; the file cannot"
      (is (answer/ok? (js/->tool-spec nil (assoc spec "input" "[:map [:x [:fn pos?]]]")
                                      (autonomous.openapi.doc/bundle {}) {:allow-fn? true})))
      (is (= :invalid-tool (get-in (js/->tool-spec (assoc spec "allowFn" true "input" "[:map [:x [:fn pos?]]]"))
                                   [:error :type]))))
    (testing "keywords that merely look like code stay schemas"
      (is (answer/ok? (js/->tool-spec (assoc spec "input" "[:map [:f :keyword] [:g [:enum :fn :x]]]")))))))

(defn- temp-json
  "A temp file holding JSON text `s`."
  [s]
  (doto (java.io.File/createTempFile "json-spec" ".json") (.deleteOnExit) (spit s)))

(defn- run-captured
  "[exit-code stdout stderr] of load/run on `args`."
  [& args]
  (let [out (java.io.StringWriter.) err (java.io.StringWriter.)
        code (binding [*out* out *err* err] (apply load/run args))]
    [code (str out) (str err)]))

(deftest tool-spec-answers-zero-only-for-a-valid-file
  (let [[code out err] (run-captured fixture)]
    (is (= 0 code))
    (is (= 2 (count (clojure.string/split-lines out))))
    (is (= "" err)))
  (doseq [[why text] [["a malli typo" "{\"id\":\"a\",\"description\":\"d\",\"input\":\"[:mapp [:a :intt]]\"}"]
                      ["blank EDN" "{\"id\":\"a\",\"description\":\"d\",\"input\":\"\"}"]
                      ["a dangling ref" "{\"id\":\"a\",\"description\":\"d\",\"input\":{\"$ref\":\"#/x\"}}"]
                      ["unreadable JSON" "{nope"]]]
    (testing why
      (let [[code out err] (run-captured (str (temp-json text)))]
        (is (= 1 code))
        (is (= "" out) "nothing is printed for a host to register")
        (is (clojure.string/includes? err ":invalid-tool")))))
  (is (= 1 (first (run-captured "/no/such.json"))))
  (is (= 1 (first (run-captured))) "no file is a usage error"))

(deftest the-bb-task-exits-with-that-code
  (let [bb (or (System/getenv "BABASHKA_BIN") "bb")
        sh (fn [& args] (-> (ProcessBuilder. ^java.util.List (vec args)) (.redirectErrorStream true) .start (doto .waitFor) .exitValue))]
    (when (try (zero? (sh bb "--version")) (catch Exception _ false))
      (is (= 0 (sh bb "tool-spec" fixture)))
      (is (= 1 (sh bb "tool-spec" (str (temp-json "{\"id\":\"a\",\"description\":\"d\",\"input\":\"[:mapp]\"}"))))))))
