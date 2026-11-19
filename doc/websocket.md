# Production: the app dials the bridge (websocket)

A deployed app has no nREPL, and should not open a port for agents. Instead
it **dials out** to the bridge over a websocket. One call at app start:

```clojure
(require '[autonomous.ws :as aws])

(aws/connect! {:url   "wss://bridge.example/autonomous/ws"
               :token "aut_..."})          ; a runtime token for this app
```

This works the same in a browser page, a node process, a JVM service and a
babashka service (`autonomous.ws` is `.cljc`: the platform `WebSocket` in
ClojureScript, `java.net.http` on the JVM and bb). The connection redials
with exponential backoff and jitter (500 ms doubling to 30 s), keeping
the same runtime id, so agents attached to the runtime stay attached. A
refusal that a retry cannot fix (bad token, unknown target) stops it.
`(aws/disconnect! handle)` stops it on purpose.

## The bridge side

A runtime token is an ordinary token (`autonomous.bridge.auth`) that grants
the scope `runtime`, with the **target id as its actor**:

```sh
bb token --actor todo --scopes runtime
```

Standalone bridge with no nREPL target:

```sh
AUTONOMOUS_TOKENS='[...agent tokens... ...runtime token...]' \
  bb serve --target-id todo --ws 0.0.0.0:7070 --http 0.0.0.0:7777
# JVM: clojure -M:ws:serve ...   (the :ws alias adds http-kit)
```

Or inside an existing Ring stack, one wrap (http-kit, or any Ring 1.11+
adapter with websocket listeners):

```clojure
(-> app
    (autonomous.ws.ring/wrap-ws {:tokens runtime-token-entries}))  ; path /autonomous/ws
```

and a channel for the target, `(autonomous.bridge.channel/make {:target/kind
:ws :target/id "todo"})`, wherever the bridge builds its client context.

Runtimes appear in `autonomous_runtimes` as they connect and leave when
their socket closes. A request in flight to a runtime that leaves is
refused at once (`:channel/runtime-left`). It does not wait for the timeout.

## Protocol

One EDN map per text frame. Reading EDN evaluates nothing.

```
runtime -> bridge  {:ws/type :hello :token "aut_.." :runtime/id "tab-1" :runtime/kind :browser :runtime/label ".."}
bridge  -> runtime {:ws/type :welcome :target "todo" :runtime/id "tab-1"}
                   {:ws/type :refused :error {:type :auth/unauthorized ..}}   then close (4401/4404/4400/4408)
bridge  -> runtime {:ws/type :request :id 7 :request "<EDN Request>"}
runtime -> bridge  {:ws/type :response :id 7 :answer "<EDN Answer>"}
```

The runtime answers a `:request` by handing the text to
`autonomous.runtime.rpc/rpc-str` and to nothing else.

## Security

- **The nREPL never leaves the box.** The socket carries only Requests
  (`:ping :manifest :call :read :poll :journal :lease :release`) to the
  app's declared tools. There is no eval op to reach.
- **Runtime tokens are not agent tokens.** Registering needs the `runtime`
  scope itself; `:admin` does not stand in. A runtime joins only the
  target its token names, so one app's token cannot pose as another app.
- **Agents are unchanged.** They still authenticate to MCP with their own
  tokens, and their identity is stamped on every request by the bridge,
  never by the runtime.
- A runtime that has not said `:hello` within 10 s is closed. A reconnect
  under the same runtime id replaces the old socket (close code 4409).
