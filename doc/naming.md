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
- When `<pfx>__<tool>` is already a valid name it is exposed unchanged.
- Otherwise (a remote MCP app may list `admin.tools.list`, or the name is
  over 64 characters) every character outside `[A-Za-z0-9_-]` becomes `_`,
  the name is cut to leave room, and it ends in `-` and a 12 character digest
  of the ORIGINAL `<pfx>__<tool>`: `pfx__admin_tools_list-<digest>`. So
  `pay.out` and `pay_out` get different names, and a name depends on the
  tool's own name alone, never on the order the app lists its tools.
- If two tools of one app still expose the same name, neither is listed;
  both appear under `dropped` in that app's row of `autonomous_apps`.

A call resolves the name against the last tool list that app gave the
calling session (its actor, scopes and attached runtime). A slow or refused
look does not erase that list. With no list yet the bridge asks the app
first. A name no listed tool exposes is refused as `:unknown-tool`; it is
never sent to the app as it is.

In single-app mode names are the app's own. A listed tool whose name is not
valid is left out and reported under `dropped`.
