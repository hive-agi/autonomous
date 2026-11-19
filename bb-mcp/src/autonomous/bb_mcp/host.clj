(ns autonomous.bb-mcp.host
  "Plugs a Mount (IAddons) into an unmodified bb-mcp server, through the two
   seams bb-mcp makes public: the `bb-mcp.protocol/Transport` protocol and
   `bb-mcp.core/run-server`, which takes any transport. `mounted` decorates
   the transport (OCP: bb-mcp's code is not touched, nor its private vars):

     tools/list    bb-mcp answers; the mount's tools are appended to it
     tools/call    a name the mount owns is answered here, on its own
                   future, and never reaches bb-mcp; any other passes through
     initialize    bb-mcp answers; tools.listChanged is turned on, and a
                   watcher sends notifications/tools/list_changed when an
                   app comes up, goes down or changes its tools
     cancelled     a call answered here is cancelled here

   Every write, bb-mcp's and the mount's, goes through one lock, so no two
   messages ever interleave on the stream."
  (:require [autonomous.bb-mcp.mount :as mount]
            [bb-mcp.protocol :as proto]
            [bb-mcp.tool :as tool]))

(def default-watch-ms 2000)

(defn- log [& xs] (binding [*out* *err*] (apply println "[autonomous.bb-mcp]" xs)))

(defn ->listed
  "The tools/list entry of bb-mcp tool spec `spec`."
  [{:keys [name description schema]}]
  {:name name :description description :inputSchema schema})

(defn with-tools
  "tools/list response `resp` with `extra` (tools/list entries) appended,
   except names it already lists: a host's own tool is never shadowed."
  [resp extra]
  (let [have (into #{} (map :name) (get-in resp [:result :tools]))]
    (update-in resp [:result :tools]
               (fnil into []) (remove #(contains? have (:name %))) extra)))

(defn with-list-changed
  "initialize response `resp` advertising tools.listChanged."
  [resp]
  (cond-> resp
    (:result resp) (assoc-in [:result :capabilities :tools :listChanged] true)))

(defn- request? [msg method] (and (= method (:method msg)) (contains? msg :id)))

(defn- watch!
  "Send notifications/tools/list_changed through `write!` whenever the
   mount's fingerprint moves, until `running?` is false."
  [m write! running? watch-ms]
  (future
    (loop [seen ::none]
      (when @running?
        (let [fp (try (mount/fingerprint m)
                      (catch Exception e (log "watcher:" (ex-message e)) seen))]
          (when (and (not= ::none seen) (not= fp seen))
            (write! {:jsonrpc "2.0" :method "notifications/tools/list_changed"}))
          (Thread/sleep (long watch-ms))
          (recur fp))))))

(defn- answer-call!
  "Answer tools/call `msg` for `owner` on a future; the response is written
   unless the call was cancelled meanwhile."
  [{:keys [calls write! actor]} owner {:keys [id params]}]
  ;; Registered BEFORE the future starts: a fast call must find itself.
  (swap! calls assoc id ::starting)
  (let [fut (future
              (let [{:keys [result error?]}
                    (try (mount/call owner (:name params) (or (:arguments params) {}) (actor))
                         (catch Exception e {:result (str "Error: " (ex-message e)) :error? true}))
                    [old _] (swap-vals! calls dissoc id)]
                (when (contains? old id)
                  (write! (proto/tool-call-response id result error?)))))]
    (swap! calls #(if (contains? % id) (assoc % id fut) %))))

(defn- cancel! [{:keys [calls]} id]
  (let [[old _] (swap-vals! calls dissoc id)]
    (when (contains? old id)
      (let [fut (get old id)]
        (when (future? fut) (future-cancel fut)))
      true)))

(defrecord MountedTransport [inner m opts lock pending running? calls]
  proto/Transport
  (read-msg [this]
    (loop []
      (let [msg (proto/read-msg inner)]
        (cond
          (nil? msg)
          (do (reset! running? false) nil)

          (and (request? msg "tools/call") (mount/owner m (get-in msg [:params :name])))
          (do (answer-call! this (mount/owner m (get-in msg [:params :name])) msg) (recur))

          (and (= "notifications/cancelled" (:method msg))
               (cancel! this (get-in msg [:params :requestId])))
          (recur)

          :else
          (do (cond
                (request? msg "tools/list")  (swap! pending assoc (:id msg) :list)
                (request? msg "initialize")  (swap! pending assoc (:id msg) :init))
              msg)))))
  (write-msg [this resp]
    (let [kind (when (contains? resp :id) (get @pending (:id resp)))
          resp (case kind
                 :list (with-tools resp (map (comp ->listed tool/tool-spec)
                                             (mount/tools m (:actor opts))))
                 :init (with-list-changed resp)
                 resp)]
      (when kind (swap! pending dissoc (:id resp)))
      ((:write! this) resp)
      (when (and (= :init kind) (pos? (:watch-ms opts)) (compare-and-set! running? :new true))
        (watch! m (:write! this) running? (:watch-ms opts))))))

(defn mounted
  "`inner` (a bb-mcp Transport) serving `m` (a seq of initialized IAddons)
   beside bb-mcp's own tools. opts: :actor (zero-arg fn: who acts),
   :watch-ms (0: no list_changed watcher)."
  [inner m {:keys [actor watch-ms] :or {watch-ms default-watch-ms}}]
  (let [lock (Object.)
        t    (map->MountedTransport
              {:inner inner :m (vec m)
               :opts {:actor (or actor (constantly "coordinator")) :watch-ms watch-ms}
               :lock lock :pending (atom {}) :running? (atom :new) :calls (atom {})})]
    (assoc t
           :actor (get-in t [:opts :actor])
           :write! (fn [msg] (locking lock (proto/write-msg inner msg))))))
