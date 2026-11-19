/* autonomous.h: the autonomous runtime as a C library (libautonomous).
 *
 * An app in any language that calls C declares tools an agent may call; a
 * bridge serves them over MCP. The app dials the bridge over a websocket and
 * passes frames through the library; the library validates arguments (malli),
 * checks scopes and leases, journals every call and calls the app's tools
 * through one dispatcher the app defines. See doc/cabi.md.
 *
 * Encodings: tool specs are EDN (they carry a malli schema), tool args and
 * results are JSON, requests and frames are opaque text.
 *
 * Threads: call jolt_library_init once, then make EVERY call from that same
 * thread. The library calls autonomous_host_dispatch on that thread too.
 *
 * Strings: arguments are copied by the library. Returned strings stay valid
 * until the next call into the library; copy what you keep.
 */
#ifndef AUTONOMOUS_H
#define AUTONOMOUS_H

#ifdef __cplusplus
extern "C" {
#endif

/* ---- jolt runtime entry (resolve with dlsym, or link -lautonomous) ------- */

/* Start the runtime. 0 on success. argv may be NULL. */
int   jolt_library_init(int argc, char **argv);
/* The export registered under `name`, or NULL. */
void *jolt_lookup(const char *name);

/* ---- exports, resolved with jolt_lookup("<name>") ------------------------ */

/* Describe the app: {"name": "...", "description": "...", "version": "..."}.
 * The description is the first thing an agent reads. Answers 0. */
typedef int         (*autonomous_set_app_fn)(const char *info_json);

/* Register tool `id` ("todo/add"; MCP name "todo_add") from an EDN spec:
 *   {:description "Add a todo item"
 *    :input [:map [:text [:string {:min 1}]]]
 *    :read-only? false :scopes #{:write}}
 * Answers JSON {"ok": "<mcp name>"} or {"error": {...}}. */
typedef const char *(*autonomous_reg_tool_fn)(const char *id, const char *spec_edn);

/* Unregister tool `id`. Answers 0. */
typedef int         (*autonomous_unreg_tool_fn)(const char *id);

/* Append an app event agents can read: {"kind": "todo/added", ...}.
 * Answers its sequence number. */
typedef long        (*autonomous_journal_fn)(const char *entry_json);

/* Answer one EDN Request ({:op :call :tool "todo_add" :args {...}
 * :scopes #{:write} :actor "me"}) with EDN Answer text. In-process use and
 * tests; a deployed app answers through the websocket functions. */
typedef const char *(*autonomous_rpc_fn)(const char *request_edn);

/* ---- websocket: the app dials the bridge (wss://host/autonomous/ws) ------ */

/* The first frame to send once the socket opens. `token` is a runtime token
 * (scope runtime) for this app's target; `runtime_id` names this process and
 * should survive redials; `label` may be "". */
typedef const char *(*autonomous_ws_hello_fn)(const char *token, const char *runtime_id,
                                              const char *label);

/* What a frame from the bridge asks: "request" (answer it with
 * autonomous_ws_reply), "welcome", "refused" (redial later), "fatal" (stop:
 * a redial only repeats the refusal) or "other" (ignore). */
typedef const char *(*autonomous_ws_kind_fn)(const char *frame);

/* The frame answering a bridge frame, "" when none is due. Send it back as
 * one text frame. Runs the tool (and so the dispatcher) before it returns. */
typedef const char *(*autonomous_ws_reply_fn)(const char *frame);

/* ---- the app defines this one, exported from its executable ------------- */

/* Run tool `tool_id` with `args_json` (already validated against the tool's
 * input schema). Answer its result as JSON, or {"error": {"message": "..."}}.
 * The library copies the result before the next dispatch. */
const char *autonomous_host_dispatch(const char *tool_id, const char *args_json);

#ifdef __cplusplus
}
#endif
#endif /* AUTONOMOUS_H */
