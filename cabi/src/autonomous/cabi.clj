(ns autonomous.cabi
  "The autonomous runtime as a C library, for apps written in any language
   that calls C (C, C++, Rust, Go, Zig, ...). Built with jolt:

     jolt build --library -m autonomous.cabi -o libautonomous

   Text crosses the ABI, never pointers to managed data. The tool spec is EDN
   (it carries a malli schema); tool args and results are JSON; requests and
   websocket frames are opaque text the host passes through. cabi/include/
   autonomous.h is the contract; doc/cabi.md the guide.

   The host links one function the library calls by name, either

     const char *autonomous_host_dispatch_v2(const char *tool_id, const char *args_json,
                                             const char *grant_json)

   which is handed the caller's grant {\"actor\": .., \"scopes\": [..]}, or the
   older grant-blind

     const char *autonomous_host_dispatch(const char *tool_id, const char *args_json)

   The library prefers _v2 when the executable exports it. Either answers
   the tool's result as JSON, or {\"error\": {\"message\": ..}}. The
   executable must export it (-Wl,--export-dynamic, or -rdynamic).

   Threads: the jolt runtime is entered from ONE thread, the one that called
   jolt_library_init. Every export below, and the dispatcher the library
   calls back, runs on that thread."
  (:require [autonomous.core :as a]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.decision :as decision]
            [autonomous.domain.wire :as wire]
            [autonomous.mcp.json :as json]
            [autonomous.ws :as ws]
            [jolt.ffi :as ffi]))

;; =============================================================================
;; Pure
;; =============================================================================

(defn tool-error
  "The exception a host result asks for, or nil. A result whose only key is
   :error is a failure the host reports; anything else is the tool's value."
  [result]
  (when (and (map? result) (= #{:error} (set (keys result))))
    (let [e (:error result)]
      (ex-info (str (or (when (map? e) (:message e)) e "tool failed"))
               (if (map? e) e {:error e})))))

(defn grant-missing
  "The exception refusing a call of tool `id` that a grant-aware host would
   be handed with no actor to attribute it to: the call fails closed, the
   host never runs."
  [id]
  (ex-info (str "tool " id " needs a caller with an actor; the call names none")
           {:autonomous/error :host/grant-missing :tool id}))

(defn hello-frame
  "The websocket :hello frame for a native runtime."
  [token runtime-id label]
  (ws/hello {:token token :runtime-id runtime-id :kind :native
             :label (if (seq label) label "native runtime")}))

(defn frame-kind
  "What bridge frame `text` asks of the host: \"request\" (answer it with
   autonomous_ws_reply), \"welcome\", \"refused\" (redial later), \"fatal\"
   (a refusal a redial only repeats: stop) or \"other\"."
  [text]
  (let [f (ws/read-frame text)]
    (cond
      (nil? f)                       "other"
      (ws/fatal? f)                  "fatal"
      (#{:request :welcome :refused} (:ws/type f)) (name (:ws/type f))
      :else                          "other")))

;; =============================================================================
;; Boundary: the host's dispatcher
;; =============================================================================

(def ^:private grant-aware-host?
  (delay (some? (ffi/find-symbol "autonomous_host_dispatch_v2"))))

(defn- host-out
  "The host's JSON answer for tool `id` with `args`, called for `grant`."
  [id args grant]
  (if @grant-aware-host?
    (if-let [g (decision/host-grant grant)]
      ((ffi/foreign-fn "autonomous_host_dispatch_v2" [:string :string :string] :string)
       id (json/write args) (json/write g))
      (throw (grant-missing id)))
    ((ffi/foreign-fn "autonomous_host_dispatch" [:string :string] :string)
     id (json/write args))))

(defn- dispatch
  "Call the host's tool `id` with `args` for `grant`; its JSON result as data."
  [id args grant]
  (let [result (json/read-json (host-out id args grant))]
    (if-let [e (tool-error result)] (throw e) result)))

;; =============================================================================
;; Exports
;; =============================================================================

(defn- answer-json [x] (json/write x))

(defn- set-app
  "Describe the app: info_json {\"name\": .., \"description\": .., \"version\": ..}."
  [info-json]
  (a/set-app-info! (json/read-json info-json))
  0)

(defn- reg-tool
  "Register tool `id` (\"todo/add\") from EDN spec text; its handler is the
   host dispatcher. Answers JSON {\"ok\": \"<mcp name>\"} or {\"error\": ..}."
  [id spec-edn]
  (answer-json
   (try
     (let [spec (wire/read-edn spec-edn)]
       (answer/ok (a/reg-tool (keyword id) (assoc spec :handler (fn [args grant] (dispatch id args grant))))))
     (catch Exception e
       (answer/refusal :invalid-tool (ex-message e) (ex-data e))))))

(defn- unreg-tool [id]
  (a/unreg-tool (keyword id))
  0)

(defn- journal
  "Append an app event (JSON object with \"kind\") to the journal agents read."
  [entry-json]
  (let [e (json/read-json entry-json)]
    (a/journal! (update e :kind keyword))))

(defn- rpc
  "Answer one EDN Request with EDN Answer text (in-process use and tests)."
  [request-edn]
  (a/rpc-str request-edn))

(defn- ws-hello [token runtime-id label]
  (wire/print-edn (hello-frame token runtime-id label)))

(defn- ws-reply
  "The frame answering bridge frame `text`, or \"\" when it needs none."
  [text]
  (if-let [r (some-> (ws/read-frame text) ws/reply)]
    (wire/print-edn r)
    ""))

(ffi/export! "autonomous_set_app"    set-app    [:string] :int)
(ffi/export! "autonomous_reg_tool"   reg-tool   [:string :string] :string)
(ffi/export! "autonomous_unreg_tool" unreg-tool [:string] :int)
(ffi/export! "autonomous_journal"    journal    [:string] :long)
(ffi/export! "autonomous_rpc"        rpc        [:string] :string)
(ffi/export! "autonomous_ws_hello"   ws-hello   [:string :string :string] :string)
(ffi/export! "autonomous_ws_kind"    frame-kind [:string] :string)
(ffi/export! "autonomous_ws_reply"   ws-reply   [:string] :string)
