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
            [malli.error :as me]
            [autonomous.domain.fleet :as fleet]))

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

(defn read-fleet-dirs
  "{dir facts} of every app directory a fleet names: the :dir of each
   command-line target group in `opts` and of each option map in the
   file's :targets."
  [file opts]
  (into {} (comp (keep :dir) (distinct) (map (juxt identity read-app-dir)))
        (concat (:targets opts) (remove :target/kind (:targets file))))) ; :http entries carry a :target/kind too

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
   "--actor" :actor "--scopes" :scopes "--target-id" :target-id "--timeout-ms" :timeout-ms "--probe-ms" :probe-ms
   "--ws" :ws})

(def ^:private switches {"--clj" :clj? "--fleet" :fleet?})

(def target-keys
  "Options that describe one target. Repeating an anchor (--dir, --port,
   --port-file) starts the next target: `--dir a --dir b --clj` is two."
  #{:dir :build :port :host :port-file :clj? :target-id})

(def ^:private anchor-keys #{:dir :port :port-file})

(defn- add-target-opt
  "`groups` (a vector of target option maps) with option `k` = `v`; an
   anchor the last group already holds opens a new group."
  [groups k v]
  (let [last-g (peek groups)]
    (if (or (nil? last-g) (and (anchor-keys k) (some anchor-keys (keys last-g))))
      (conj groups {k v})
      (conj (pop groups) (assoc last-g k v)))))

(defn parse-args
  "{:opts {..} :errors [..]} from command-line `args` (after the command).
   :opts holds the last value of every flag (one target, as always) and,
   when several targets are named, :targets [target-opts ..] in order."
  [args]
  (loop [[a & more] args acc {:opts {} :errors [] :groups []}]
    (let [put (fn [acc k v]
                (cond-> (assoc-in acc [:opts k] v)
                  (target-keys k) (update :groups add-target-opt k v)))]
      (cond
        (nil? a) (let [gs (:groups acc)]
                   (cond-> (dissoc acc :groups)
                     (< 1 (count gs)) (assoc-in [:opts :targets] gs)))
        (switches a) (recur more (put acc (switches a) true))
        (flags a) (if (seq more)
                    (recur (rest more) (put acc (flags a) (first more)))
                    (recur more (update acc :errors conj (str a " needs a value"))))
        :else (recur more (update acc :errors conj (str "unknown argument " a)))))))

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

(defn ws-target
  "The websocket Target `opts` name: runtimes dial the bridge's --ws
   listener with a runtime token whose actor is this target id."
  [opts]
  {:target/kind :ws :target/id (or (:target-id opts) "app")})

(defn http-target
  "Promote one remote app entry to an :http Target. Credentials select
   environment references by actor, with :default as the fallback."
  [entry]
  {:target/kind :http
   :target/id   (str (:app entry))
   :url         (str (:url entry))
   :token-env   (some-> (:token-env entry) str)
   :credentials (:credentials entry)
   :timeout-ms  (:timeout-ms entry)})

(defn- ledger-config [opts]
  (case (:ledger opts)
    nil        nil
    "memory"   {:ledger/kind :memory}
    "datahike" (cond-> {:ledger/kind :datahike}
                 (:datahike-path opts) (assoc :datahike/path (:datahike-path opts)))
    {:ledger/kind (keyword (:ledger opts))}))

(defn- clean-target [t] (into {} (remove (comp nil? val)) t))

(defn- file-target
  "The Target of one autonomous.edn :targets entry: a Target as written, an
   :http app entry, or target options such as {:dir \"../api\" :clj? true}
   derived from their directory."
  [entry derive]
  (cond
    (:target/kind entry) entry
    (= :http (:channel entry)) (clean-target (http-target entry))
    :else (derive entry)))

(defn- fleet-targets
  "The Targets of a fleet, or nil for single mode: the command line's
   target groups when it names several, else the file's :targets (each a
   Target, an :http app entry, or target options such as {:dir \"../api\"
   :clj? true})."
  [file opts dir-facts]
  (let [named  (fn [o] (if (or (:target-id o) (:dir o) (nil? (:port o)))
                         o
                         (assoc o :target-id (str "app-" (:port o)))))
        derive (fn [o] (clean-target (derive-target (named o) (get dir-facts (:dir o)))))]
    (cond
      (seq (:targets opts)) (mapv derive (:targets opts))
      (seq (:targets file)) (mapv #(file-target % derive) (:targets file)))))

(defn promote
  "Config (validated) from the collected `file`, `env` and `opts` maps and
   the facts of the app dir `app-dir`; `dir-facts` ({dir facts}) carries the
   facts of every fleet target's dir. Answers {:ok config} or {:error
   {:message :problems}}. Several targets (or --fleet) make a fleet: :targets
   replaces :target."
  ([file env opts app-dir] (promote file env opts app-dir {}))
  ([file env opts app-dir dir-facts]
   (let [http    (parse-http (:http opts))
         ws      (parse-http (:ws opts))
         ws-only (and ws (not (or (:dir opts) (:port opts) (:port-file opts))))
         ledger  (ledger-config opts)
         targets (fleet-targets file opts dir-facts)
         single? (and (nil? targets)
                      (or (:dir opts) (:port opts) (:port-file opts) (not (:target file))))
         cfg     (cond-> (merge {:transport :stdio
                                 :ledger {:ledger/kind :memory}
                                 :stdio/actor "local"
                                 :stdio/scopes #{:read :write}}
                                (dissoc file :targets)
                                env)
                   single?            (assoc :target (if ws-only
                                                       (ws-target opts)
                                                       (clean-target (derive-target opts app-dir))))
                   ws                 (assoc :ws (merge (:ws file) ws))
                   targets            (-> (dissoc :target) (assoc :targets targets))
                   (:fleet? opts)     (assoc :fleet? true)
                   http               (assoc :transport :http :http (merge (:http file) http))
                   ledger             (assoc :ledger ledger)
                   (:actor opts)      (assoc :stdio/actor (:actor opts))
                   (:scopes opts)     (assoc :stdio/scopes (split-scopes (:scopes opts)))
                   (:timeout-ms opts) (assoc :call-timeout-ms (parse-long* (:timeout-ms opts)))
                   (:probe-ms opts)   (assoc :probe-ms (parse-long* (:probe-ms opts))))
         cfg     (cond-> cfg
                   (:target cfg) (update :target clean-target)
                   (and (:fleet? cfg) (:target cfg)) (-> (assoc :targets [(:target cfg)]) (dissoc :target)))
         targets (or targets (when (:fleet? cfg) (:targets cfg)))
         ids     (map :target/id (:targets cfg))]
     (if-let [e (m/explain bs/Config cfg)]
       {:error {:message "invalid configuration" :problems (me/humanize e)}}
       (if-let [problems (and targets (fleet/check-targets ids))]
         {:error {:message "invalid fleet" :problems problems}}
         {:ok cfg})))))
