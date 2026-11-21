# Tool names

Every tool an agent sees has an MCP name. autonomous derives it from the
tool's id, checks it, and never sends a name outside one pattern:

```
[A-Za-z0-9_-]{1,64}
```

That is ASCII letters, digits, `_` and `-`, from 1 to 64 characters. The
MCP specification (2025-11-25) also allows `.` and up to 128 characters, but
the model APIs behind MCP clients are narrower: Claude's tool names match
`^[a-zA-Z0-9_-]{1,128}$` (no dot), and OpenAI-style function names stop at
64 characters. autonomous emits only names every one of them accepts.

## Hyphens stay

`-` is a legal character and is kept as it is. `:todos/clear-done` is
`todos_clear-done`, and in a fleet `backend__todos_clear-done`. Existing
names are not rewritten.

One model API is stricter: Kimi (Moonshot) wants a function name to match
`^[a-zA-Z_][a-zA-Z0-9-_]{0,127}$`, so a name may not START with a digit or
a hyphen. Ids that begin with a letter (`:todo/add`, `:orders/place`) never
hit this. Fleet names begin with the app prefix, so give apps ids that start
with a letter.

## Deriving a name from an id

`autonomous.domain.naming/tool-name`:

1. A keyword loses its leading `:`; anything else is taken as its string.
2. The result is cut to its first 64 characters.
3. Every character outside `[A-Za-z0-9_-]` becomes `_`: the `/` of a
   namespaced keyword, `.`, spaces, and any non-ASCII character. A character
   is one UTF-16 code unit, so an emoji (two code units) becomes `__` on the
   JVM, babashka and JavaScript alike.
4. An empty result is `_`.

| id | name |
|---|---|
| `:todo/add` | `todo_add` |
| `:todos/clear-done` | `todos_clear-done` |
| `:app.todos/add-item` | `app_todos_add-item` |
| `"admin.tools.list"` | `admin_tools_list` |
| `"ação"` | `a__o` |
| `""` | `_` |
| an 80 character id | its first 64 characters |

The rule is total and deterministic: every id gets a valid name, the same
id always gets the same name, and a valid name maps to itself.

## Explicit names are checked, not repaired

A spec may set `:name`. That name must already match the pattern:
`reg-tool` with `:name "a.b"`, or a name of 65 characters, throws an
`:invalid-tool` error at registration and changes nothing.

## Collisions

Two ids can derive the same name (`:a/b` and `:a.b`). Registering the second
throws a `:name-clash` and leaves the first in place. Give one of them an
explicit `:name`.

## Fleet names

In a fleet, app tool `tool` of the app with prefix `pfx` is exposed as
`<pfx>__<tool>` (see [fleet](fleet.md)):

- The prefix is the app id made safe, with `_` and `.` turned into `-`, at
  most 24 characters, so the first `__` ends the prefix.
- The tool part is made safe too: every character outside
  `[A-Za-z0-9_-]` becomes `_`. A remote MCP app may list `admin.tools.list`;
  the fleet exposes `pfx__admin_tools_list`.
- A name over 64 characters keeps its first 59 characters, then `-` and a
  4 character checksum of the whole name.

When a name was cut or had characters replaced, the bridge finds the tool it
stands for among the tools that app listed the calling agent. If two tools of
one app expose the same name, the first one listed keeps it, and the app's
row in `autonomous_apps` lists the other under `dropped`.
