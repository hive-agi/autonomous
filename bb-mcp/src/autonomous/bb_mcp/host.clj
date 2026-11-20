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
  "Promote: the tools/list entry of bb-mcp tool spec `spec`."
  [{:keys [name description schema]}]
  {:name name :description description :inputSchema schema})

(defn with-tools
  "Promote: tools/list response `resp` with `extra` (tools/list entries) appended,
   except names it already lists: a host's own tool is never shadowed."
  [resp extra]
  (let [have (into #{} (map :name) (get-in resp [:result :tools]))]
    (update-in resp [:result :tools]
               (fnil into []) (remove #(contains? have (:name %))) extra)))

(defn with-list-changed
  "Promote: initialize response `resp` advertising tools.listChanged."
  [resp]
  (cond-> resp
    (:result resp) (assoc-in [:result :capabilities :tools :listChanged] true)))

(defn- request? [msg method] (and (= method (:method msg)) (contains? msg :id)))

(defn- watch!
  "Boundary: send notifications/tools/list_changed through `write!` whenever the
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

(defn- run-call
  "Boundary: bb-mcp {:result :error?} of tools/call `params` on `owner` as
   `actor`; a thrown call is an error result."
  [owner params actor]
  (try (mount/call owner (:name params) (or (:arguments params) {}) actor)
       (catch Exception e {:result (str "Error: " (ex-message e)) :error? true})))

(defn- answer-call!
  "Boundary: answer tools/call `msg` for `owner` on a future; the response
   is written unless the call was cancelled meanwhile."
  [{:keys [calls write! actor]} owner {:keys [id params]}]
  ;; Registered BEFORE the future starts: a fast call must find itself.
  (swap! calls assoc id ::starting)
  (let [fut (future
              (let [{:keys [result error?]} (run-call owner params (actor))
                    [old _] (swap-vals! calls dissoc id)]
                (when (contains? old id)
                  (write! (proto/tool-call-response id result error?)))))]
    (swap! calls #(if (contains? % id) (assoc % id fut) %))))

(defn- cancel!
  "Boundary: cancel the call answered here under request `id`; true when
   there was one, else nil."
  [{:keys [calls]} id]
  (let [[old _] (swap-vals! calls dissoc id)]
    (when (contains? old id)
      (let [fut (get old id)]
        (when (future? fut) (future-cancel fut)))
      true)))

(defn- pending-kind
  "Promote: the response kind request `msg` awaits from bb-mcp: :list for
   tools/list, :init for initialize, else nil."
  [msg]
  (cond (request? msg "tools/list") :list
        (request? msg "initialize") :init))

(defn- intercept!
  "Boundary: answer an owned tools/call, or cancel one answered here; true
   when `msg` was consumed and so never reaches bb-mcp."
  [{:keys [m] :as t} msg]
  (if-let [owner (and (request? msg "tools/call") (mount/owner m (get-in msg [:params :name])))]
    (do (answer-call! t owner msg) true)
    (boolean (and (= "notifications/cancelled" (:method msg))
                  (cancel! t (get-in msg [:params :requestId]))))))

(defn- pass-through!
  "Boundary: remember the response kind `msg` awaits in `pending`; `msg`."
  [pending msg]
  (when-let [kind (pending-kind msg)] (swap! pending assoc (:id msg) kind))
  msg)

(defn- decorated
  "Boundary: bb-mcp response `resp` as the client sees it, given the `kind`
   of request it answers: the mount's tools appended to a :list, listChanged
   advertised on an :init, any other unchanged."
  [{:keys [m opts]} kind resp]
  (case kind
    :list (with-tools resp (map (comp ->listed tool/tool-spec) (mount/tools m (:actor opts))))
    :init (with-list-changed resp)
    resp))

(defn- start-watch!
  "Boundary: start the list_changed watcher once, on the first :init answer,
   unless :watch-ms is 0; the watcher's future when it started one, else nil."
  [{:keys [m opts running?] :as t} kind]
  (when (and (= :init kind) (pos? (:watch-ms opts)) (compare-and-set! running? :new true))
    (watch! m (:write! t) running? (:watch-ms opts))))

(defrecord MountedTransport [inner m opts lock pending running? calls]
  proto/Transport
  (read-msg [this]
    (loop []
      (let [msg (proto/read-msg inner)]
        (cond
          (nil? msg)            (do (reset! running? false) nil)
          (intercept! this msg) (recur)
          :else                 (pass-through! pending msg)))))
  (write-msg [this resp]
    (let [kind (when (contains? resp :id) (get @pending (:id resp)))
          out  (decorated this kind resp)]
      (when kind (swap! pending dissoc (:id resp)))
      ((:write! this) out)
      (start-watch! this kind))))

(defn mounted
  "Boundary: `inner` (a bb-mcp Transport) serving `m` (a seq of initialized IAddons)
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
