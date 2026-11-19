# App fleets through bb-mcp

Every hive client already runs [bb-mcp](https://github.com/hive-agi/bb-mcp).
`autonomous.bb-mcp` mounts an autonomous fleet into that same process: the
client sees bb-mcp's tools (`bash`, `clojure_eval`, the hive tools) and,
beside them, the tools of every configured app, named `<app>__<tool>`, plus
the `autonomous_*` built-ins. One babashka process, no JVM in the path: apps
are reached over their own nREPLs (autonomous.bridge.fleet).

```sh
bb -m autonomous.bb-mcp.main --port 7001 --target-id orders --dir ../todo
# or: bb bb-mcp [fleet options]
```

The fleet is configured exactly like `autonomous serve` ([fleet.md](fleet.md)):
fleet options on the command line, or `:targets` in the EDN file named by
`--config`, else `AUTONOMOUS_FLEET`, else `autonomous.edn` in the client's
project directory (`BB_MCP_CALLER_CWD`). With no app configured it is plain
bb-mcp. Fleet naming is always on, so an app tool never shadows a bb-mcp tool
(and a name bb-mcp already lists is never replaced).

Each head acts as its agent: the ling id bb-mcp stamps (`BB_MCP_AGENT_ID`,
`BB_MCP_CLIENT_ID`, `CLAUDE_SWARM_SLAVE_ID`, else `coordinator`;
`AUTONOMOUS_ACTOR` overrides). Lings therefore get the app tools for free,
each with its own session on every app, and the apps' journals and ledger
say which ling did what.

## Design: a Mount for a new host

| namespace | role |
|---|---|
| `autonomous.bb-mcp.addon` | IAddon `autonomous.fleet` over a fleet server: live tool list, one session per agent, MCP CallToolResults; hooks `:mount/owns?`, `:mount/call`, `:mount/fingerprint` |
| `autonomous.bb-mcp.mount` | IAddons as `bb-mcp.tool/Tool` records; routes a name to its owning addon |
| `autonomous.bb-mcp.host` | decorates bb-mcp's public `Transport` and runs its public `run-server`: tools/list merged, owned tools/call answered on their own future, `listChanged` advertised and sent when an app comes or goes |
| `autonomous.bb-mcp.main` | entry: collect config, initialize the addon, serve |

No bb-mcp code is modified (convention: plug a brick, never edit another
addon's core). bb-mcp has no public tool-source seam yet: its
`tool-sources` vector is private and fixed, so the brick decorates the
transport instead. When bb-mcp gains a registry of tool sources, the
Tools from `autonomous.bb-mcp.mount/tools` plug into it unchanged and the
host decorator goes away.

Tests: `bb test` (suite `autonomous.bb-mcp.mount-test`, stub channels, runs
bb-mcp's own `run-server`).
