(ns autonomous.cabi
  "The autonomous runtime as a C library, for apps written in any language
   that calls C (C, C++, Rust, Go, Zig, ...). Built with jolt:

     jolt build --library -m autonomous.cabi -o libautonomous

   Text crosses the ABI, never pointers to managed data. The tool spec is EDN
   (it carries a malli schema); tool args and results are JSON; requests and
   websocket frames are opaque text the host passes through. cabi/include/
   autonomous.h is the contract; doc/cabi.md the guide.

   The host links one function the library calls by name:

     const char *autonomous_host_dispatch(const char *tool_id, const char *args_json)

   It answers the tool's result as JSON, or {\"error\": {\"message\": ..}}.
   The executable must export it (-Wl,--export-dynamic, or -rdynamic).

   Threads: the jolt runtime is entered from ONE thread, the one that called
   jolt_library_init. Every export below, and the dispatcher the library
   calls back, runs on that thread."
  (:require [autonomous.core :as a]
            [autonomous.domain.answer :as answer]
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

(defn- dispatch
  "Call the host's tool `id` with `args`; its JSON result as data."
  [id args]
  (let [out    ((ffi/foreign-fn "autonomous_host_dispatch" [:string :string] :string)
                id (json/write args))
        result (json/read-json out)]
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
       (answer/ok (a/reg-tool (keyword id) (assoc spec :handler (fn [args _grant] (dispatch id args))))))
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
