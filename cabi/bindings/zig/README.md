# Zig bindings to libautonomous

`autonomous.zig` is the binding (one file, no dependencies beyond libc);
`example/main.zig` registers one tool, dispatches calls through the library
and prints the answers.

```sh
bb cabi       # cabi/build/libautonomous.so (needs jolt and JOLT_CHEZ_CSV)
bb cabi-zig   # zig build run: build the example and run it; fails on a wrong answer
```

or, from this directory, `zig build run` (`-Dlib-dir=<dir>` when the
library lives elsewhere). Tested with Zig 0.16.

```zig
const autonomous = @import("autonomous");

fn dispatch(tool: []const u8, args_json: []const u8) anyerror![*:0]const u8 { ... }
comptime { autonomous.exportDispatch(dispatch); }   // autonomous_host_dispatch

const rt = try autonomous.Runtime.start();
try rt.describe("{\"name\":\"calc\",\"description\":\"...\"}");
_ = try rt.tool("calc/add", "{:description \"Add\" :input [:map [:a :int] [:b :int]]}");
const answer = try rt.rpc("{:op :call :tool \"calc_add\" :args {:a 1 :b 2} :scopes #{:write}}");
```

**Who called (libautonomous 0.3).** `exportDispatchAs(f)` exports
`autonomous_host_dispatch_v2` (and a 0.2 fallback): `f(tool, args_json, who)`
is handed an `autonomous.Grant` with `actor` and `scopes` (`who.has("write")`).
A call that arrives without a grant (a 0.2 library) or with an unreadable one
fails closed with `GrantNotPassed` / `GrantUnreadable`, and `f` does not run.
`exportDispatch(f)` stays grant-blind. Export one of the two, never both. The
actor is printable ASCII whose escapes start with a backslash, so escape it
before writing it into JSON (the example does). `zig build run -Dblind=true`
builds the example grant-blind; `bb cabi-zig` runs both.

The executable is linked with `rdynamic` so the library can find
`autonomous_host_dispatch_v2` or `autonomous_host_dispatch`. The dispatcher's result must stay valid until the
next dispatch (the example keeps it in a static buffer). An error it returns
is answered as the tool error `{"error":{"message":"<ErrorName>"}}`, so no
Zig error reaches the library; use checked arithmetic (`std.math.add`)
rather than an operator that panics. Strings the library returns stay valid
until the next call into it; `lastRefusal()` is the binding's own copy.
`rt.unregister(id)` removes a tool. `zig build test` runs the binding's unit
tests.

**One thread.** jolt runs on the thread that called `jolt_library_init`.
`Runtime.start` remembers that thread, and every method answers
`error.WrongThread` from any other one. The example shows that refusal.

A tool spec written as JSON (see doc/cabi.md) becomes the EDN text
`Runtime.tool` takes with `bb tool-spec tools.json`.
