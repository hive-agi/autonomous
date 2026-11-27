(ns autonomous.domain.caller
  "The coordinate of the agent step that issued a tool call: read from the MCP
   tools/call request _meta and kept on the call entry as :caller, so the agent
   trace and the app trace join (decision 20261127154517-3149b4b8, the two
   traces joined by a graph).

   Wire spelling (MCP 2026-07-28, SEP-414). The W3C trace context travels under
   the spec's own UNPREFIXED keys, `traceparent` and `tracestate`. The hive
   coordinates travel under a reverse-DNS prefix, `io.github.hive-agi/...`,
   because the spec does not define them:

     traceparent                      the W3C traceparent
     tracestate                       the W3C tracestate
     io.github.hive-agi/chat-point    {:run-id :seq :turn}
     io.github.hive-agi/run-task-id   the task (for example the kanban card)
     io.github.hive-agi/run-attempt   the attempt

   The value shapes mirror hive-spi.agent.run-control and
   hive-agent.loop.chat-point/ChatPoint, held as autonomous.domain.schema/Caller.
   Every field is optional. Pure and total: an absent _meta reads as no caller,
   and an invalid field is dropped and named, never an error."
  (:require [autonomous.domain.schema :as s]
            [autonomous.domain.trace :as trace]
            [malli.core :as m]))

(def wire-keys
  "The tools/call request _meta keys a caller coordinate travels under. The
   trace context keys are the MCP spec's own (SEP-414); the hive coordinates
   carry the reverse-DNS prefix this library owns. A later hive-agent card
   sends exactly these."
  {:traceparent "traceparent"
   :tracestate  "tracestate"
   :chat-point  "io.github.hive-agi/chat-point"
   :run-task-id "io.github.hive-agi/run-task-id"
   :run-attempt "io.github.hive-agi/run-attempt"})

(def max-field-chars
  "Longest string field of a caller coordinate kept. Coordinates are short;
   this only keeps a hostile _meta from bloating an entry."
  256)

(defn meta-of
  "The _meta map of tools/call `params`, under its keyword or string spelling
   (`:_meta` or `:meta`), or nil when there is none."
  [params]
  (when (map? params)
    (let [m (or (get params :_meta) (get params :meta) (get params "_meta"))]
      (when (map? m) m))))

(defn- field
  "Value of wire key `k` in meta map `m`, under its keyword or string spelling."
  [m k]
  (let [v (or (get m (keyword k)) (get m k))]
    (when (some? v) v)))

(defn- bounded-string
  "`x` when it is a non-blank string no longer than max-field-chars, else nil."
  [x]
  (when (and (string? x) (<= 1 (count x) max-field-chars)) x))

(defn- chat-point
  "ChatPoint of value `v` (a {:run-id :seq :turn} map), or nil when `v` is not
   one. Mirrors hive-agent.loop.chat-point/ChatPoint (autonomous.domain.schema)."
  [v]
  (when (map? v)
    (let [cp {:run-id (bounded-string (field v "run-id"))
              :seq    (field v "seq")
              :turn   (field v "turn")}]
      (when (m/validate s/ChatPoint cp) cp))))

(defn- traceparent
  "`v` when it is a valid W3C traceparent (autonomous.domain.trace/
   parse-traceparent, the one parser), else nil."
  [v]
  (when (and (string? v) (trace/parse-traceparent v)) v))

(defn- attempt
  "`v` when it is a non-negative integer, else nil."
  [v]
  (when (and (int? v) (<= 0 v)) v))

(defn from-meta
  "The caller coordinate read from MCP tools/call `params`' _meta, and the
   fields that were dropped: {:caller Caller-or-nil :dropped [field ...]}.
   `params` is the parsed tools/call params; a map that already IS the _meta
   works too. With no _meta, or none of the wire keys, the answer is
   {:caller nil :dropped []}. An invalid field is dropped and named, never an
   error: an invalid traceparent, a chat-point that is not a ChatPoint, a
   task-id that is not a string, an attempt that is not a non-negative integer."
  [params]
  (let [m       (or (meta-of params) (when (map? params) params) {})
        raw-tp  (field m (:traceparent wire-keys))
        raw-ts  (field m (:tracestate wire-keys))
        raw-cp  (field m (:chat-point wire-keys))
        raw-tid (field m (:run-task-id wire-keys))
        raw-att (field m (:run-attempt wire-keys))
        tp      (traceparent raw-tp)
        ts      (bounded-string raw-ts)
        cp      (chat-point raw-cp)
        tid     (bounded-string raw-tid)
        att     (attempt raw-att)
        caller  (cond-> {}
                  tp          (assoc :trace/traceparent tp)
                  ts          (assoc :trace/tracestate ts)
                  cp          (assoc :chat-point cp)
                  tid         (assoc :run/task-id tid)
                  (some? att) (assoc :run/attempt att))
        dropped (cond-> []
                  (and (some? raw-tp) (nil? tp))   (conj :trace/traceparent)
                  (and (some? raw-ts) (nil? ts))   (conj :trace/tracestate)
                  (and (some? raw-cp) (nil? cp))   (conj :chat-point)
                  (and (some? raw-tid) (nil? tid)) (conj :run/task-id)
                  (and (some? raw-att) (nil? att)) (conj :run/attempt))]
    {:caller  (when (seq caller) caller)
     :dropped dropped}))
