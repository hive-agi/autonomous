(ns autonomous.config
  "Configuration of `autonomous serve`, in CPPB strata:

   Collect  read-file, read-env, parse-args: raw maps from autonomous.edn,
            the environment and the command line. The only effects.
   Promote  `promote`: merge (args over env over file), derive the target
            from an app directory, apply defaults, validate against
            autonomous.bridge.schema/Config. Pure.

   An app directory is enough: with a shadow-cljs.edn the target is that
   build over .shadow-cljs/nrepl.port, otherwise a Clojure process over
   .nrepl-port. This is how hive-compose programs are reached: compose
   starts the program in its directory, the program writes its port file,
   autonomous reads it."
  (:require [autonomous.bridge.schema :as bs]
            [autonomous.domain.wire :as wire]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

;; =============================================================================
;; Collect
;; =============================================================================

(defn- read-tagged
  "EDN of text `s`, keeping the value under any unknown tag (shadow-cljs.edn
   uses #shadow/env and friends)."
  [s]
  (edn/read-string {:default (fn [_tag v] v)} s))

(defn read-file
  "The map in EDN file `path`, {} when it does not exist."
  [path]
  (let [f (io/file path)]
    (if (.exists f) (read-tagged (slurp f)) {})))

(defn read-app-dir
  "Facts about app directory `dir` a target is derived from."
  [dir]
  (let [shadow (io/file dir "shadow-cljs.edn")]
    {:dir     (.getAbsolutePath (io/file dir))
     :shadow? (.exists shadow)
     :builds  (when (.exists shadow) (vec (keys (:builds (read-tagged (slurp shadow))))))}))

(defn read-env
  "Configuration carried by the environment: AUTONOMOUS_TOKENS (an EDN
   vector of token entries, e.g. from a secret store) and AUTONOMOUS_ACTOR."
  [env]
  (cond-> {}
    (get env "AUTONOMOUS_TOKENS") (assoc :tokens (wire/read-edn (get env "AUTONOMOUS_TOKENS")))
    (get env "AUTONOMOUS_ACTOR")  (assoc :stdio/actor (get env "AUTONOMOUS_ACTOR"))))

(def ^:private flags
  {"--config" :config "--dir" :dir "--build" :build "--port" :port "--host" :host
   "--port-file" :port-file "--http" :http "--ledger" :ledger "--datahike-path" :datahike-path
   "--actor" :actor "--scopes" :scopes "--target-id" :target-id "--timeout-ms" :timeout-ms})

(def ^:private switches {"--clj" :clj?})

(defn parse-args
  "{:opts {..} :errors [..]} from command-line `args` (after the command)."
  [args]
  (loop [[a & more] args acc {:opts {} :errors []}]
    (cond
      (nil? a) acc
      (switches a) (recur more (assoc-in acc [:opts (switches a)] true))
      (flags a) (if (seq more)
                  (recur (rest more) (assoc-in acc [:opts (flags a)] (first more)))
                  (recur more (update acc :errors conj (str a " needs a value"))))
      :else (recur more (update acc :errors conj (str "unknown argument " a))))))

;; =============================================================================
;; Promote
;; =============================================================================

(defn- parse-long* [s] (when s (parse-long (str s))))

(defn split-on
  "Trimmed, non-blank pieces of `s` between occurrences of `sep`."
  [s sep]
  (loop [s (str s) acc []]
    (if-let [i (str/index-of s sep)]
      (recur (subs s (+ i (count sep))) (conj acc (subs s 0 i)))
      (into [] (comp (map str/trim) (remove str/blank?)) (conj acc s)))))

(defn- split-scopes [s]
  (when s (into #{} (map keyword) (split-on s ","))))

(defn- parse-http
  "{:bind :port} of \"host:port\" or \"port\"."
  [s]
  (when s
    (let [i (str/last-index-of s ":")]
      (if i
        {:bind (subs s 0 i) :port (parse-long* (subs s (inc i)))}
        {:bind "127.0.0.1" :port (parse-long* s)}))))

(defn derive-target
  "The Target for command-line `opts` and the facts of their app dir."
  [opts app-dir]
  (let [shadow? (and (:shadow? app-dir) (not (:clj? opts)))
        build   (some-> (or (:build opts) (first (:builds app-dir))) name keyword)]
    {:target/kind     :nrepl
     :target/id       (or (:target-id opts) (some-> (:dir app-dir) io/file .getName) "app")
     :nrepl/host      (or (:host opts) "localhost")
     :nrepl/port      (parse-long* (:port opts))
     :nrepl/port-file (or (:port-file opts)
                          (when (:dir app-dir)
                            (str (io/file (:dir app-dir) (if shadow? ".shadow-cljs/nrepl.port" ".nrepl-port")))))
     :nrepl/mode      (if shadow? :shadow :clj)
     :shadow/build    (when shadow? (or build :app))}))

(defn- ledger-config [opts]
  (case (:ledger opts)
    nil         nil
    "memory"    {:ledger/kind :memory}
    "datahike"  (cond-> {:ledger/kind :datahike}
                  (:datahike-path opts)
                  (assoc :datahike/config
                         {:store {:backend :file :path (:datahike-path opts)
                                  :id (java.util.UUID/nameUUIDFromBytes
                                       (.getBytes ^String (:datahike-path opts) "UTF-8"))}
                          :schema-flexibility :write
                          :keep-history? true}))
    {:ledger/kind (keyword (:ledger opts))}))

(defn promote
  "Config (validated) from the collected `file`, `env` and `opts` maps and
   the app dir facts. Answers {:ok config} or {:error {:message :problems}}."
  [file env opts app-dir]
  (let [http   (parse-http (:http opts))
        ledger (ledger-config opts)
        cfg    (cond-> (merge {:transport :stdio
                               :ledger {:ledger/kind :memory}
                               :stdio/actor "local"
                               :stdio/scopes #{:read :write}}
                              file
                              env)
                 (or (:dir opts) (:port opts) (:port-file opts) (not (:target file)))
                 (assoc :target (derive-target opts app-dir))
                 http               (assoc :transport :http :http (merge (:http file) http))
                 ledger             (assoc :ledger ledger)
                 (:actor opts)      (assoc :stdio/actor (:actor opts))
                 (:scopes opts)     (assoc :stdio/scopes (split-scopes (:scopes opts)))
                 (:timeout-ms opts) (assoc :call-timeout-ms (parse-long* (:timeout-ms opts))))
        cfg    (update cfg :target #(into {} (remove (comp nil? val)) %))]
    (if-let [e (m/explain bs/Config cfg)]
      {:error {:message "invalid configuration" :problems (me/humanize e)}}
      {:ok cfg})))
