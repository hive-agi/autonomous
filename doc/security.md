# Security

The bridge reaches your app through its nREPL. **An nREPL is remote code
execution by design.** The rule that follows from it: the nREPL stays on the
machine, bound to localhost, and only the MCP endpoint ever crosses a network.
That endpoint offers nothing but the scoped tools and resources the app
declared.

## stdio: local, one agent

`autonomous serve` speaks MCP over stdio by default. One process serves one
agent and inherits the trust of whoever started it, so it needs no token. The
agent's identity comes from configuration:

| flag | env | default |
|---|---|---|
| `--actor NAME` | `AUTONOMOUS_ACTOR` | `local` |
| `--scopes read,write` | | `read,write` |

Give a read-only agent `--scopes read`: it will not even see the write tools.

## HTTP: remote agents and swarms

`--http [HOST:]PORT` serves MCP over HTTP instead. A bare port binds
`127.0.0.1`. The server answers on two paths:

| path | method | what |
|---|---|---|
| `/mcp` | `POST` | one JSON-RPC message; `initialize` answers an `Mcp-Session-Id` header that later requests send back |
| `/mcp` | `DELETE` | end the session named by `Mcp-Session-Id` |
| `/health` | any | `{"status":"ok"}`, no authentication |

There is no event stream: an authenticated `GET` on `/mcp` gets 405. A body over 1 MiB gets
413.

### What the transport enforces

In this order:

1. **Exposure.** Binding anything but a loopback address (`127.0.0.1`,
   `localhost`, `::1`) without tokens refuses to start, and `serve` exits
   with code 3. Anyone who reaches an open port would act on the app.
2. **Origin.** A request that carries an `Origin` header not listed in
   `:allowed-origins` gets 403. This stops a web page in the user's browser
   from reaching a loopback server by DNS rebinding. Requests with no
   `Origin` (agents, curl) pass.
3. **Authentication.** With tokens configured, every request needs
   `Authorization: Bearer <token>`. A missing or unknown token gets 401. The
   token names the actor and its scopes. Without tokens (loopback only),
   every caller acts as `local` with `read` and `write`.
4. **Session ownership.** A session id is honoured only for the actor that
   opened it. Another actor's token with that id gets 404, the same answer as
   for an unknown session.

Then, inside the runtime, the tool's scopes are checked against the grant
again, and a request that carries no scopes gets `#{:read}`.

### Tokens

Mint one token per agent. Each agent then has its own identity in the journal
and the ledger, and one agent can be revoked without the others.

```sh
# in a checkout of this repository
clojure -M:token --actor agent-1 --scopes read,write
bb token --actor agent-1 --scopes read,write

# anywhere, from the released jar
clojure -Sdeps '{:deps {io.github.hive-agi/autonomous {:mvn/version "0.1.2"}}}' \
  -M -m autonomous.cli token --actor agent-1 --scopes read,write
```

The command prints the token once (`aut_` and 32 random bytes, base64url) and
a config entry:

```clojure no-run
{:sha256 "<64 hex characters>" :actor "agent-1" :scopes [:read :write]}
```

Give the token to the agent and store it like a password. The configuration
holds only its SHA-256, so a leaked config file grants nothing. Tokens are
compared in constant time, against every entry.

Put the entries in either place:

- `:tokens` in `autonomous.edn` (the default `--config` file, read from the
  working directory),
- the `AUTONOMOUS_TOKENS` environment variable, as an EDN vector of entries.

The environment wins over the file, and command-line flags win over both.

### A configuration file

`--config PATH` (default `./autonomous.edn`) holds anything the flags set,
plus the settings that have no flag, such as `:allowed-origins`:

```clojure no-run
{:transport :http
 :http      {:bind "127.0.0.1" :port 7777
             :allowed-origins ["http://localhost:8280"]}
 :tokens    [{:sha256 "…" :actor "agent-1" :scopes [:read :write]}]
 :ledger    {:ledger/kind :datahike :datahike/path ".autonomous/ledger"}
 :call-timeout-ms 30000}
```

Run it with `serve --dir <app>` (or give the file an explicit `:target`).
The file is validated against `autonomous.bridge.schema/Config`; an invalid
one stops `serve` with exit code 2 and says which key is wrong.

## Reaching the bridge from elsewhere

Keep the bridge on loopback next to the app and carry the port over a tunnel,
not an open port:

```sh
# on the dev box, next to the app
clojure -Sdeps '{:deps {io.github.hive-agi/autonomous {:mvn/version "0.1.2"}}}' \
  -M -m autonomous.cli serve --dir ../todo --http 127.0.0.1:7777

# on the agent's machine
ssh -N -L 7777:127.0.0.1:7777 dev-box
```

Tailscale or WireGuard work the same way. If you do bind a routable address
(`--http 0.0.0.0:7777`), the bridge insists on tokens, and you should still
put TLS in front of it: the transport itself is plain HTTP.

## Secrets with hive-compose

hive-compose (the hive tool that runs a project's containers and host
programs) can run the bridge
as a host program and inject the token entries from
[pass](https://www.passwordstore.org/) when it starts the process, so they
never sit in a file:

```clojure no-run
{:program/id      "todo-bridge"
 :program/dir     "~/PP/todo"
 :program/command ["clojure" "-Sdeps"
                   "{:deps {io.github.hive-agi/autonomous {:mvn/version \"0.1.2\"}}}"
                   "-M" "-m" "autonomous.cli" "serve"
                   "--dir" "." "--http" "127.0.0.1:7777"]
 :program/env     {"AUTONOMOUS_TOKENS" {:pass "autonomous/todo/tokens"}}}
```

The pass entry holds the EDN vector of token entries. Any other secret store
that sets `AUTONOMOUS_TOKENS` works the same way.

## Development tools

`autonomous.re-frame/install-dev-tools!` registers tools that dispatch any
event and read any app-db path. They require `:admin`, so an agent without it
neither sees nor calls them. Still, install them only in development builds
(the example passes `goog.DEBUG`).

## What to check before exposing an app

- The nREPL port is bound to localhost and not forwarded anywhere.
- Every agent has its own token, with the fewest scopes it needs.
- Tools that move money or delete data declare a scope of their own (for
  example `:billing`), not only `:write`.
- `expose-db!` lists `:paths` and `:redact`s anything personal.
- `observe-events!` keeps `:payloads?` off unless the journal may hold what
  users type.
