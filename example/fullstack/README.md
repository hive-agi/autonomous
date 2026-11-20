# Fullstack todo: an Elm UI and a Rust backend, driven by agents

A todo list a person uses in the browser, and that an agent (or a Clojure
script) can drive end to end, through the same paths the person's clicks
take. Neither half is written in Clojure.

```
 person ──clicks──▶ Elm UI (browser) ──REST / server-sent events──▶ Rust backend
                      │ autonomous.js                                │ libautonomous
                      └──────────── websocket ──▶ bridge ◀── websocket┘
                                                   ▲
                                agent (MCP) or Clojure (bb fullstack-drive)
```

| app | language | how it joins | its agent tools |
|---|---|---|---|
| `frontend` | Elm, through ports | `autonomous.js` (the runtime as an ES module) | `ui_state`, `ui_type`, `ui_submit`, `ui_set_filter`, `ui_click_toggle`, `ui_click_remove`, `ui_click_clear_done` |
| `backend` | Rust | `libautonomous` (the runtime as a C library) | `todos_list`, `todos_add`, `todos_toggle`, `todos_remove`, `todos_clear_done` |

One bridge serves both as a fleet, so an agent sees `frontend__ui_submit`
beside `backend__todos_add`. Each app keeps validation (malli), scopes and
its journal in process; the bridge never evaluates code in either.

## Run it

You need jolt with `JOLT_CHEZ_CSV` (for `libautonomous`, see
[doc/cabi.md](../../doc/cabi.md)), cargo, Elm 0.19, the Clojure CLI and
babashka.

```sh
bb fullstack            # builds what is missing, starts everything, prints the next steps
```

Open the printed URL (http://127.0.0.1:8090/). The footer says
`agents: open` once the tab has dialed the bridge.

### Drive it from an agent

`bb fullstack` prints a ready `claude mcp add` line with a fresh agent token:

```sh
claude mcp add --transport http todo http://127.0.0.1:7891/mcp --header "Authorization: Bearer aut_..."
```

Then ask Claude Code something like *"add three todos for a picnic through
the UI, tick the first one, and show me only what is left"*. Watch the page:
the draft fills in, the items appear, the filter changes, and the activity
feed says who did what.

### Drive it from Clojure

```sh
AUTONOMOUS_MCP=http://127.0.0.1:7891/mcp AUTONOMOUS_TOKEN=aut_... bb fullstack-drive
```

[`drive.clj`](drive.clj) is a plain MCP client: it adds a todo through the
backend, types and submits one through the UI, toggles it with a UI click
and switches the filter, pausing between steps so you can watch.

### Check it

```sh
bb fullstack-e2e        # free ports, a headless Chromium tab, assertions over MCP
```

## How each half does it

**The Elm UI** ([`frontend/src/Main.elm`](frontend/src/Main.elm)) has two
ports for agents. An agent command arrives on `agentCommand`, is decoded to
the `Msg` a click would send (`Submit`, `Toggle id`, `SetFilter Active`),
goes through `update`, and the resulting view state leaves on `agentReply`.
[`frontend/static/app.js`](frontend/static/app.js) registers one tool per
command with `autonomous.js` and answers each with a Promise that the reply
resolves:

```js
autonomous.registerTool('ui/submit',
  { description: 'Press Enter in the new-todo input.', input: '[:map]' },
  (args) => ui('submit', args));
```

**The Rust backend** ([`backend/src/main.rs`](backend/src/main.rs)) serves
REST and an event stream to the UI from a pool of HTTP threads, and owns the
autonomous runtime on its main thread, through the
[`autonomous` crate](../../cabi/bindings/rust):

```rust
rt.tool("todos/add",
        "{:description \"Add a todo.\" :input [:map [:text [:string {:min 1 :max 200}]]]}",
        move |args| Ok(store.change("agent", ..., |s| s.add(args["text"].as_str().unwrap()))))?;
rt.serve(&Dial::new(&ws_url, &token), |rt| { /* journal what humans did */ });
```

Both kinds of change go through one `change` function that updates the
store and streams the new list to every open tab, so a person sees an
agent's edit at once, and an agent reads a person's edit in the journal.

**The bridge** ([`fullstack.clj`](fullstack.clj)) is the stock one, given a
fleet of two websocket targets in `target/fullstack/autonomous.edn`:

```clojure
{:targets [{:target/kind :ws :target/id "backend"}
           {:target/kind :ws :target/id "frontend"}]
 :ws      {:bind "127.0.0.1" :port 7890}
 :tokens  [<runtime token of backend> <runtime token of frontend> <agent token>]}
```

A runtime token names its target, so the tab cannot pose as the backend.
The backend hands the tab its token through `/config.json` here because
this is a local demo; a real app gives it out behind its own login.

## Notes

- The Elm app's `elm.json` says 0.19.3, the compiler this was built with;
  set it to your compiler's version (0.19.1 works) if `elm make` complains.
- The demo backend (tiny_http) speaks HTTP/1.1 only. A client that asks to
  upgrade to HTTP/2 (babashka.http-client by default) hangs: pass
  `{:version :http1.1}`.
- The backend journals every REST change as `todo/human`, including UI
  clicks an agent made; who clicked is in the frontend's journal, where each
  UI tool call is recorded under the agent's actor.
- The frontend tools exist only while a tab is open. With several tabs, the
  bridge drives the oldest; `autonomous_runtimes` and `autonomous_attach`
  pick another.
