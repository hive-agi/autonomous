/* In-process smoke test of libautonomous: a C app registers a tool whose
 * handler is C, then drives it the way the bridge would.
 *
 *   cc -rdynamic -I../../include -o smoke smoke.c -ldl && ./smoke ../../build/libautonomous.so
 */
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "autonomous.h"

/* The app's tools. Args arrive as validated JSON, e.g. {"a":2,"b":40}. */
const char *autonomous_host_dispatch(const char *tool, const char *args) {
  static char out[128];
  if (strcmp(tool, "calc/add") == 0) {
    long a = 0, b = 0;
    const char *pa = strstr(args, "\"a\":"), *pb = strstr(args, "\"b\":");
    if (pa) a = strtol(pa + 4, NULL, 10);
    if (pb) b = strtol(pb + 4, NULL, 10);
    snprintf(out, sizeof out, "{\"sum\":%ld,\"by\":\"C\"}", a + b);
  } else if (strcmp(tool, "calc/fail") == 0) {
    snprintf(out, sizeof out, "{\"error\":{\"message\":\"the C side said no\"}}");
  } else {
    snprintf(out, sizeof out, "{\"error\":{\"message\":\"unknown tool\"}}");
  }
  return out;
}

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
