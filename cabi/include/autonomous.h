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
 * thread. The library calls the app's dispatcher on that thread too.
 *
 * Strings: arguments are copied by the library. Returned strings stay valid
 * until the next call into the library; copy what you keep. The strings the
 * library passes to the app's dispatcher (tool_id, args_json, grant_json)
 * are valid only for the duration of that call.
 */
#ifndef AUTONOMOUS_H
#define AUTONOMOUS_H

/* The library version this header describes. A minor bump only adds. */
#define AUTONOMOUS_VERSION_MAJOR 0
#define AUTONOMOUS_VERSION_MINOR 3

/* The most characters of an actor the library hands a host. The shown actor
 * is printable ASCII only, so this is also its most bytes (excluding the
 * terminating NUL). */
#define AUTONOMOUS_MAX_SHOWN_ACTOR 64

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

/* ---- the app defines ONE of these, exported from its executable --------- */

/* Run tool `tool_id` with `args_json` (already validated against the tool's
 * input schema) for the caller `grant_json`:
 *   {"actor": "agent-1", "scopes": ["read", "write"]}
 * The actor is shown the way the library shows actors everywhere: printable
 * ASCII (0x20..0x7E) as itself, except that a double quote, a backslash and
 * every other UTF-16 code unit are escaped as the six characters \uxxxx in
 * lowercase hex (so a bidi, zero-width or control character is visible, and
 * an astral character arrives as its surrogate pair, e.g. \ud83d\ude00).
 * Distinct actors stay distinct. The
 * result is cut at AUTONOMOUS_MAX_SHOWN_ACTOR characters, never inside an
 * escape. Scopes are sorted names. Answer the result as JSON, or
 * {"error": {"message": "..."}}. The library copies the result before the
 * next dispatch.
 *
 * tool_id, args_json and grant_json are valid only for the duration of the
 * call: copy what you keep. The decoded actor is at most
 * AUTONOMOUS_MAX_SHOWN_ACTOR characters of printable ASCII, so at most 64
 * bytes (plus the NUL). The 64 counts characters of the shown form, so a
 * \uxxxx escape counts 6 and is never split. The shown actor holds no quote
 * and no backslash except the one that starts an escape, so inside
 * grant_json its JSON string takes at most 64 + 10 bytes (an escape's
 * backslash is escaped once more); parse grant_json, never slice it.
 *
 * Preferred since 0.3. The library looks the symbol up ONCE per process,
 * process-wide (dlsym over every loaded object, as RTLD_DEFAULT does), so a
 * process must hold exactly one definition of each dispatcher: if any
 * loaded shared object exports autonomous_host_dispatch_v2, the library
 * calls it and never autonomous_host_dispatch. The Rust binding crate
 * (cabi/bindings/rust) exports BOTH, so do not link it into a process that
 * defines its own dispatcher. When the library calls _v2 it never calls it
 * without an actor:
 * a call whose actor is empty, whitespace-only or invisible-only is refused
 * (:host/grant-missing) before the app runs. */
const char *autonomous_host_dispatch_v2(const char *tool_id, const char *args_json,
                                        const char *grant_json);

/* The grant-blind dispatcher of 0.2, still called when the executable does
 * not export autonomous_host_dispatch_v2. */
const char *autonomous_host_dispatch(const char *tool_id, const char *args_json);

#ifdef __cplusplus
}
#endif
#endif /* AUTONOMOUS_H */
