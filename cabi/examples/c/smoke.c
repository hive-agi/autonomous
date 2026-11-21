/* In-process smoke test of libautonomous: a C app registers a tool whose
 * handler is C, then drives it the way the bridge would.
 *
 *   cc -rdynamic -I../../include -o smoke smoke.c -ldl && ./smoke ../../build/libautonomous.so
 *
 * Built with -DSMOKE_V1 it exports only the grant-blind dispatcher of 0.2,
 * so the same checks also cover an old-style host.
 */
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "autonomous.h"

#ifdef SMOKE_V1
#define WHO(grant) "C"
#else
/* The caller's actor, copied out of grant_json {"actor":"...","scopes":[..]}. */
static char who[AUTONOMOUS_MAX_SHOWN_ACTOR * 6 + 1];
static const char *actor_of(const char *grant) {
  const char *p = grant ? strstr(grant, "\"actor\":\"") : NULL;
  size_t n = 0;
  if (p)
    for (p += 9; *p && *p != '"' && n < sizeof who - 1; p++) who[n++] = *p;
  who[n] = '\0';
  return who;
}
#define WHO(grant) actor_of(grant)
#endif

/* The app's tools. Args arrive as validated JSON, e.g. {"a":2,"b":40}. */
static const char *run_tool(const char *tool, const char *args, const char *grant) {
  static char out[512];
  (void)grant;
  if (strcmp(tool, "calc/add") == 0) {
    long a = 0, b = 0;
    const char *pa = strstr(args, "\"a\":"), *pb = strstr(args, "\"b\":");
    if (pa) a = strtol(pa + 4, NULL, 10);
    if (pb) b = strtol(pb + 4, NULL, 10);
    snprintf(out, sizeof out, "{\"sum\":%ld,\"by\":\"%s\"}", a + b, WHO(grant));
  } else if (strcmp(tool, "calc/whoami") == 0) {
    snprintf(out, sizeof out, "{\"grant\":%s}", grant ? grant : "null");
  } else if (strcmp(tool, "calc/fail") == 0) {
    snprintf(out, sizeof out, "{\"error\":{\"message\":\"the C side said no\"}}");
  } else {
    snprintf(out, sizeof out, "{\"error\":{\"message\":\"unknown tool\"}}");
  }
  return out;
}

static int dispatched = 0;

#ifdef SMOKE_V1
const char *autonomous_host_dispatch(const char *tool, const char *args) {
  dispatched++;
  return run_tool(tool, args, NULL);
}
#else
const char *autonomous_host_dispatch_v2(const char *tool, const char *args, const char *grant) {
  dispatched++;
  return run_tool(tool, args, grant);
}
#endif

static int failures = 0;

static void expect(const char *what, const char *got, const char *needle) {
  int ok = got && strstr(got, needle) != NULL;
  printf("%s %-10s %s\n", ok ? "ok  " : "FAIL", what, got ? got : "(null)");
  if (!ok) failures++;
}

int main(int argc, char **argv) {
  const char *lib = argc > 1 ? argv[1] : "./libautonomous.so";
  void *h = dlopen(lib, RTLD_NOW);
  if (!h) { fprintf(stderr, "%s\n", dlerror()); return 1; }
  int (*init)(int, char **) = (int (*)(int, char **))dlsym(h, "jolt_library_init");
  void *(*look)(const char *) = (void *(*)(const char *))dlsym(h, "jolt_lookup");
  if (!init || !look || init(0, NULL) != 0) { fprintf(stderr, "jolt init failed\n"); return 1; }

  autonomous_set_app_fn  set_app  = (autonomous_set_app_fn)look("autonomous_set_app");
  autonomous_reg_tool_fn reg_tool = (autonomous_reg_tool_fn)look("autonomous_reg_tool");
  autonomous_journal_fn  journal  = (autonomous_journal_fn)look("autonomous_journal");
  autonomous_rpc_fn      rpc      = (autonomous_rpc_fn)look("autonomous_rpc");
  autonomous_ws_hello_fn ws_hello = (autonomous_ws_hello_fn)look("autonomous_ws_hello");
  autonomous_ws_kind_fn  ws_kind  = (autonomous_ws_kind_fn)look("autonomous_ws_kind");
  autonomous_ws_reply_fn ws_reply = (autonomous_ws_reply_fn)look("autonomous_ws_reply");

  set_app("{\"name\":\"calc\",\"description\":\"A C calculator. calc_add adds two integers.\"}");
  expect("reg", reg_tool("calc/add",
         "{:description \"Add two integers (in C)\" :input [:map [:a :int] [:b :int]]}"),
         "\"ok\":\"calc_add\"");
  expect("reg", reg_tool("calc/fail", "{:description \"Always fails\" :input [:map]}"),
         "\"ok\":\"calc_fail\"");
  expect("reg-bad", reg_tool("calc/bad", "{:input [:map]}"), "\"error\"");

  expect("manifest", rpc("{:op :manifest :scopes #{:write}}"), "calc_add");
  expect("call", rpc("{:op :call :tool \"calc_add\" :args {:a 2 :b \"40\"} :scopes #{:write} :actor \"agent-1\"}"),
         ":sum 42");
  expect("invalid", rpc("{:op :call :tool \"calc_add\" :args {:a \"x\"} :scopes #{:write}}"), ":invalid-args");
  expect("scope", rpc("{:op :call :tool \"calc_add\" :args {:a 1 :b 2}}"), ":forbidden");
  expect("host-err", rpc("{:op :call :tool \"calc_fail\" :args {} :scopes #{:write}}"), "the C side said no");

  char seq[32];
  snprintf(seq, sizeof seq, "%ld", journal("{\"kind\":\"calc/cleared\",\"by\":\"human\"}"));
  expect("journal", seq, "5");
  expect("journal", rpc("{:op :journal :scopes #{:read}}"), ":calc/cleared");

  /* The caller's grant on the dispatch path. */
  expect("reg", reg_tool("calc/whoami", "{:description \"Who called\" :input [:map] :read-only? true}"),
         "\"ok\":\"calc_whoami\"");
  const char *call_as = "{:op :call :tool \"calc_whoami\" :scopes #{:write :read} :actor \"%s\"}";
  char req[512];
#ifdef SMOKE_V1
  snprintf(req, sizeof req, call_as, "agent-1");
  expect("v1-grant", rpc(req), ":grant nil");
  int before = dispatched;
  expect("v1-empty", rpc("{:op :call :tool \"calc_whoami\" :actor \"\"}"), ":grant nil");
  expect("v1-ran", dispatched == before + 1 ? "ran" : "did not run", "ran");
#else
  snprintf(req, sizeof req, call_as, "agent-1");
  expect("grant", rpc(req), ":actor \"agent-1\"");
  expect("scopes", rpc(req), "\"read\" \"write\"");
  expect("by", rpc("{:op :call :tool \"calc_add\" :args {:a 1 :b 1} :scopes #{:write} :actor \"agent-2\"}"),
         ":by \"agent-2\"");
  /* The actor arrives as printable ASCII: " \\ and everything else escaped
   * \uxxxx (EDN shows the backslash doubled), whole: an actor whose shown
   * form is longer than 64 is refused, never cut. */
  snprintf(req, sizeof req, call_as, "ag\\u001b[31ment-012345678901234567890123456789012345678901234567");
  expect("shown", rpc(req), ":actor \"ag\\\\u001b[31ment-012345678901234567890123456789012345678901234567\"");
  snprintf(req, sizeof req, call_as, "ag\\u001b[31ment-0123456789012345678901234567890123456789012345678");
  expect("too-long", rpc(req), ":host/actor-too-long");
  snprintf(req, sizeof req, call_as, "\\u202E\\u202E\\u202E\\u202E\\u202E\\u202E\\u202E\\u202E\\u202E\\u202Eabcd-1");
  expect("too-long", rpc(req), ":host/actor-too-long");
  /* JSON may escape / as \/ inside grant_json: a host must parse it, never
   * slice it. 64 slashes arrive whole. */
  snprintf(req, sizeof req, call_as,
           "////////////////////////////////////////////////////////////////");
  expect("slashes", rpc(req), ":actor \"////////////////////////////////////////////////////////////////\"");
  snprintf(req, sizeof req, "{:op :call :tool \"calc_add\" :args {:a 1 :b 1} :scopes #{:write} :actor \"%s\"}",
           "////////////////////////////////////////////////////////////////");
  expect("slashes", rpc(req), ":by \"////////////////////////////////////////////////////////////////\"");
  snprintf(req, sizeof req, call_as, "agent:\\u202Enamuh");
  expect("bidi", rpc(req), ":actor \"agent:\\\\u202enamuh\"");
  snprintf(req, sizeof req, call_as, "agent\\u200B-1");
  expect("zero-width", rpc(req), ":actor \"agent\\\\u200b-1\"");
  snprintf(req, sizeof req, call_as, "agent-\xF0\x9F\x98\x80"); /* U+1F600 as UTF-8 */
  expect("astral", rpc(req), ":actor \"agent-\\\\ud83d\\\\ude00\"");
  snprintf(req, sizeof req, call_as, "a\\u0085b\\u009B");
  expect("c1", rpc(req), ":actor \"a\\\\u0085b\\\\u009b\"");
  /* A quote in the actor cannot add scopes: the grant is JSON-encoded. */
  expect("inject", rpc("{:op :call :tool \"calc_whoami\" :actor \"a\\\",\\\"scopes\\\":[\\\"admin\\\"]\"}"),
         ":scopes [\"read\"]}}}");
  expect("inject", rpc("{:op :call :tool \"calc_whoami\" :actor \"a\\\",\\\"scopes\\\":[\\\"admin\\\"]\"}"),
         ":actor \"a\\\\u0022,\\\\u0022scopes\\\\u0022:[\\\\u0022admin\\\\u0022]\", :scopes [\"read\"]");
  int before = dispatched;
  expect("empty", rpc("{:op :call :tool \"calc_whoami\" :actor \"\"}"), ":host/grant-missing");
  snprintf(req, sizeof req, call_as, "\\u0007\\u0000");
  expect("controls", rpc(req), ":host/grant-missing");
  snprintf(req, sizeof req, call_as, " \\t\\n ");
  expect("blank", rpc(req), ":host/grant-missing");
  snprintf(req, sizeof req, call_as, "\\u200B\\u202E\\u2028");
  expect("invisible", rpc(req), ":host/grant-missing");
  expect("closed", dispatched == before ? "not run" : "ran", "not run");
  expect("anonymous", rpc("{:op :call :tool \"calc_whoami\"}"), ":actor \"anonymous\"");
#endif

  expect("hello", ws_hello("aut_x", "c-1", "smoke"), ":ws/type :hello");
  expect("kind", ws_kind("{:ws/type :welcome :target \"calc\" :runtime/id \"c-1\"}"), "welcome");
  expect("kind", ws_kind("{:ws/type :refused :error {:type :auth/unauthorized}}"), "fatal");
  expect("kind", ws_kind("{:ws/type :request :id 7 :request \"{:op :ping}\"}"), "request");
  expect("reply", ws_reply("{:ws/type :request :id 7 :request \"{:op :call :tool \\\"calc_add\\\" :args {:a 1 :b 2} :scopes #{:write}}\"}"),
         ":sum 3");
  const char *none = ws_reply("{:ws/type :welcome}");
  expect("no-reply", none && none[0] == '\0' ? "(empty)" : none, "(empty)");

  printf(failures ? "\n%d FAILED\n" : "\nall ok\n", failures);
  return failures ? 1 : 0;
}
