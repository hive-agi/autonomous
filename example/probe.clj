(ns probe
  "A stdio MCP probe: starts a bridge as a subprocess, speaks MCP to it one
   line at a time, prints every exchange and checks each step answered as
   expected. It is how the examples are verified end to end through the
   bridge, and a way to see what an agent sees.

     bb example/probe.clj STEPS.edn BRIDGE-ARGV...

   e.g. bb example/probe.clj example/orders/probe.edn \\
          bb serve --clj --dir example/orders --scopes read,write,billing

   STEPS.edn is a vector of steps:
     {:tools true}                     tools/list
     {:resources true}                 resources/list
     {:read \"uri\"}                     resources/read
     {:call \"tool\" :args {..}}         tools/call
     {:poll true}                      autonomous_poll the last Pending id,
                                       until it settles (up to ~10 s)
   A step may say :expect :error (the call must be refused), :contains
   \"text\" (or a vector of them) that its result must hold and, as any
   argument value, [:ref n path..] takes a value from step n's
   structuredContent. Exit code 0 when every step answered as expected.
   The bridge's stderr goes to target/probe-bridge.err.log."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(defn- resolve-refs
  "`args` with every [:ref n & path] replaced by that value of step n and
   every [:str x ..] by its parts joined."
  [args results]
  (walk/postwalk (fn [x]
                   (cond
                     (and (vector? x) (= :ref (first x)))
                     (get-in (nth results (second x)) (into [:structuredContent] (drop 2 x)))
                     ;; refs inside are already resolved: postwalk goes bottom up
                     (and (vector? x) (= :str (first x))) (apply str (rest x))
                     :else x))
                 args))

(defn- request
  "The JSON-RPC method and params of `step`."
  [step results last-pending]
  (cond
    (:tools step)     ["tools/list" {}]
    (:resources step) ["resources/list" {}]
    (:read step)      ["resources/read" {:uri (:read step)}]
    (:poll step)      ["tools/call" {:name "autonomous_poll" :arguments {:id last-pending}}]
    :else             ["tools/call" {:name (:call step) :arguments (resolve-refs (:args step {}) results)}]))

(defn- converse
  "A function sending one JSON-RPC message to `proc` and answering the
   response with the same id (notifications in between are skipped)."
  [proc]
  (let [in (io/writer (:in proc)) out (io/reader (:out proc)) n (atom 0)]
    (fn [method params]
      (let [id (swap! n inc)]
        (.write in (str (json/generate-string {:jsonrpc "2.0" :id id :method method :params params}) "\n"))
        (.flush in)
        (loop []
          (let [line (.readLine out)]
            (cond
              (nil? line) {:error {:message "the bridge closed stdout"}}
              :else (let [m (try (json/parse-string line true) (catch Exception _not-json nil))]
                      (if (= id (:id m)) m (recur))))))))))

(defn- summary
  "One line of what response `r` carried."
  [r]
  (let [res (:result r)
        s   (cond
              (:error r)          (str "JSON-RPC error " (json/generate-string (:error r)))
              (:tools res)        (str (count (:tools res)) " tools: " (str/join " " (map :name (:tools res))))
              (:resources res)    (str "resources: " (str/join " " (map :uri (:resources res))))
              (:contents res)     (get-in res [:contents 0 :text])
              :else               (str (when (:isError res) "REFUSED ") (get-in res [:content 0 :text])))]
    (if (< 240 (count s)) (str (subs s 0 240) "...") s)))

(defn- refused? [r] (or (some? (:error r)) (true? (get-in r [:result :isError]))))

(defn- contains-all?
  "True when the JSON of `result` holds every string of `wanted` (one
   string, or a vector of strings and [:ref ..] forms)."
  [result wanted results]
  (let [text (json/generate-string result)]
    (every? #(str/includes? text (str %))
            (resolve-refs (if (string? wanted) [wanted] (vec wanted)) results))))

(defn- pending-id [r] (get-in r [:result :structuredContent :pending]))

(defn- run-step
  "Response of `step`; a :poll step polls until the result settles."
  [send! step results last-pending]
  (let [[method params] (request step results last-pending)]
    (loop [tries 50]
      (let [r (send! method params)]
        (if (and (:poll step) (pending-id r) (pos? tries))
          (do (Thread/sleep 200) (recur (dec tries)))
          [method params r])))))

(defn -main [steps-file & argv]
  (let [steps (edn/read-string (slurp steps-file))
        _     (io/make-parents "target/x")
        proc  (p/process {:cmd (vec argv) :in :stream :out :stream
                           :err (io/file "target/probe-bridge.err.log")})
        send! (converse proc)
        init  (send! "initialize" {:protocolVersion "2025-06-18" :capabilities {}
                                   :clientInfo {:name "probe" :version "0"}})]
    (println "initialize ->" (get-in init [:result :protocolVersion])
             "|" (first (str/split-lines (str (get-in init [:result :instructions])))))
    (let [bad (loop [[step & more] steps i 0 results [] pending nil bad 0]
                (if-not step
                  bad
                  (let [[method params r] (run-step send! step results pending)
                        want-error?        (= :error (:expect step))
                        ok?                (and (= want-error? (refused? r))
                                                (contains-all? (:result r) (:contains step) results))]
                    (println (format "%2d %s %s %s" i (if ok? "ok  " "FAIL") method
                                     (or (:name params) (:uri params) "")))
                    (println "     <-" (summary r))
                    (recur more (inc i) (conj results (:result r))
                           (or (pending-id r) pending) (if ok? bad (inc bad))))))]
      (p/destroy-tree proc)
      (println (if (zero? bad) "probe: every step answered as expected" (str "probe: " bad " step(s) failed")))
      (System/exit (if (zero? bad) 0 1)))))

(apply -main *command-line-args*)
