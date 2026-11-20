# Any language: libautonomous (the C ABI)

An app does not have to be written in Clojure to be agent-operable.
`libautonomous` is the autonomous runtime compiled into a shared library
with [jolt](https://jolt-lang.net) (Clojure on Chez Scheme). Any language
that calls C can load it: C, C++, Rust, Go, Zig, Odin, Nim and so on.

The app keeps its own code and its own sockets. The library keeps
everything autonomous decides: malli validation of arguments (with the same
lenient decoding, so `"3"` becomes `3`), scopes, leases, the journal, the
manifest agents read. The app's tools are called back through one function
it defines.

```
agent --MCP--> bridge (bb / JVM / cljw)  <--websocket-- your app (Rust, C, Go, ...)
                                                          |  frames in, frames out
                                                          v
                                                     libautonomous.so
                                                       validate, scope, journal
                                                          |
                                                          v
                                               autonomous_host_dispatch (your code)
```

This is the production channel of [websocket.md](websocket.md): the app
dials the bridge with a runtime token, so it opens no port and runs no REPL.

## Build it

You need jolt and the Chez Scheme kernel files it links against (a
threaded, `-fPIC` build of the Chez version jolt was made with, found at
`JOLT_CHEZ_CSV`). Then:

```sh
bb cabi        # cabi/build/libautonomous.so, then the C smoke test (16 checks)
bb cabi-rust   # the Rust example app
bb cabi-e2e    # live: the Rust app dials a bb bridge, agents call it over MCP
```

The library is about 30 MB: it carries the jolt runtime, its GC, malli and
the autonomous runtime. It builds in about a minute.

## The ABI

`cabi/include/autonomous.h` is the contract. Specs are EDN, because a tool's
input is a malli schema; tool args and results are JSON; requests and
frames are opaque text you pass through.

| export | does |
|---|---|
| `jolt_library_init(argc, argv)` | start the runtime, once |
| `jolt_lookup(name)` | find an export below |
| `autonomous_set_app(info_json)` | name and describe the app for agents |
| `autonomous_reg_tool(id, spec_edn)` | declare a tool; answers `{"ok": "<mcp name>"}` or `{"error": ..}` |
| `autonomous_unreg_tool(id)` | remove one |
| `autonomous_journal(entry_json)` | record an app event agents can read |
| `autonomous_ws_hello(token, runtime_id, label)` | the first frame on a new socket |
| `autonomous_ws_kind(frame)` | `request`, `welcome`, `refused`, `fatal` or `other` |
| `autonomous_ws_reply(frame)` | the frame answering a request, `""` if none is due |
| `autonomous_rpc(request_edn)` | answer one request in process (tests) |

The app defines, and exports from its executable (`-rdynamic`, or
`-Wl,--export-dynamic`):

```c
const char *autonomous_host_dispatch(const char *tool_id, const char *args_json);
```

It receives arguments that already passed the tool's schema and answers the
result as JSON. `{"error": {"message": "..."}}` reports a failure; the agent
sees it as a tool error and the journal records `:tool-threw`.

A tool spec:

```clojure
{:description "Receive qty units of sku into stock. Answers the new on-hand count."
 :input       [:map [:sku [:string {:min 1}]] [:qty [:int {:min 1}]]]
 :read-only?  false          ; read-only tools need only the :read scope
 :scopes      #{:write}}     ; optional; any keyword the app invents
```

## The loop

Every language does the same few steps (the Rust example,
`cabi/examples/rust/src/main.rs`, is about 200 lines with its tools):

1. `jolt_library_init`, then register the app and its tools.
2. Open a websocket to `wss://bridge/autonomous/ws`; send
   `autonomous_ws_hello(token, runtime_id, label)`.
3. For each text frame: ask `autonomous_ws_kind`. On `request`, send back
   `autonomous_ws_reply(frame)`. On `fatal`, stop. Ignore the rest.
4. When the socket drops, redial with backoff under the same runtime id, so
   attached agents stay attached.

On the bridge: `bb token --actor <target> --scopes runtime` mints the app's
token, and `bb serve --target-id <target> --ws HOST:PORT --http HOST:PORT`
serves it (see [websocket.md](websocket.md)).

## Rules the runtime imposes

- **One thread.** Call `jolt_library_init` and every export from the same
  thread. The library calls `autonomous_host_dispatch` on that thread, so
  a tool may block it; hand long work to your own threads and answer from
  what they leave behind. (jolt allows other threads through `:collect-safe`
  exports; this library does not declare any yet.)
- **Strings.** Arguments are copied in. A returned string is valid until
  the next call into the library: copy what you keep. The result of your
  dispatcher must stay valid until it returns to the library, which copies
  it.
- **No eval.** As over the websocket from a Clojure app, the bridge reaches
  only the tools the app declared.

## What is still open

- Bindings: the header plus a C smoke test and a Rust app exist; Go (cgo),
  Zig and a JSON-only spec form are next.
- The library is one process-wide runtime; the per-instance runtime of the
  surface work could give each embedder several.
- Size: 30 MB per app. A thin, library-free client (the frame protocol
  spoken directly, decisions on the bridge) would suit tiny apps; it gives
  up in-process validation and the journal.
