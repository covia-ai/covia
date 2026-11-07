# Preferences

Preferences are what every agent acting for a user should know about them:
units, tone, timezone, diet. Each user has one map of any structure, kept by
the `preferences` adapter (`covia.adapter.PreferencesAdapter`), shown to the
user at `x/preferences` and edited with one tool. An agent's own notes belong
in `memory` instead: a list at a path the caller names, such as `n/memory`.

## Storage and access

- The map is the adapter's own state at `:adapters/preferences/users/<did>`,
  bound to the user (`adapterState(did)`), so it follows the account's
  lifecycle. Account deletion removes it (`deleteUserState`).
- It is exposed read-only at `x/preferences`
  ([ADAPTERS.md](ADAPTERS.md#user-visible-extension-routes)), and another
  user's at `<did>/x/preferences` under the usual cross-user delegation rules.
  `covia:write` and `covia:delete` there are refused; edits go through the tool.
- Reading needs `crud/read`, and each edit needs `crud/write`, on
  `<did>/x/preferences/<path>`: the path it changes, or `<did>/x/preferences`
  for a merge with no path. A grant on one key therefore allows editing only
  that key. The pin reads the whole map, so it needs read on `x/preferences`.
- The tool edits the addressed user's own preferences (`ctx.getUserDID()`), so
  an agent edits its owner's.
- State version 0: no migration steps.

## Shape and limits

- A map of any structure. Values are any value except null; a null is never
  stored. Map keys are non-empty text without `/`.
- One user's preferences may hold at most the configured number of bytes,
  measured as `Cells.storageSize` of the map, which is cached on each cell. An
  edit that would go over the limit is refused, unless it makes the
  preferences smaller, so a user can always tidy up after the limit is lowered.
- The limit is `adapters.preferences.users.<did>.maxBytes` for that user, else
  `adapters.preferences.maxBytes`, else 4096
  ([CONFIG.md](CONFIG.md#adapter-configuration)).

## The tool

`v/ops/preferences`: adapter `preferences:edit`, tool name `preferences`. Input
`{command, path?, value?}`.

- `command`: `set`, `remove`, `append`, `insert` or `merge`.
- `path`: keys separated by `/`, such as `diet/allergies`. A number picks a
  list item, counting from 0. A leading `x/preferences/`, and a leading or
  trailing `/`, are ignored, so a path copied from a read addresses the same
  place. An empty segment is refused. Every command except `merge` needs a
  path; `set` or `remove` with none would replace or clear everything, so it is
  refused as a mistake.
- `value`: required by every command except `remove`.

| Command | On a map key, or a missing one | On a list |
|---|---|---|
| `set` | Stores the value, creating missing maps on the way; a null value removes the entry | `allergies/2` replaces that item; a position past the end is refused |
| `remove` | Removes the key; nothing there is not an error | `allergies/2` removes the item and moves later items up; with a `value`, removes the items equal to it from the list at the path |
| `append` | A missing key becomes a list holding the value; a map or other value is refused | Adds the value at the end |
| `insert` | Refused | `allergies/0` puts the value before item 0; the list's length appends; beyond is refused |
| `merge` | RFC 7396 (`JSONAdapter.mergePatch`): the value must be a map and the path must hold a map or nothing; nested maps merge, a null removes a key, anything else, a list included, replaces what was there | Refused: lists change with `set`, `append`, `insert` or `remove` |

- A path cannot go inside a value that is not a map or a list.
- A `set`, `append` or `insert` value may not contain null, and no value may
  contain a key a path could not address. In a `merge` value, null is allowed
  as a map value, where it removes that key.
- Every success returns `{ok: true}`. A `remove` that found nothing returns
  `{ok: true, removed: false}`. No value is returned: the agent sees the result
  through its pin, or reads `x/preferences`.
- Each call is one atomic update of the user's map, so concurrent edits by the
  user and any of their agents never lose each other.
- Refusals are `InvalidInputException`, or `AuthException` for authority, with
  reasons from constants on `PreferencesAdapter`.

## How agents see them

Templates pin the map as watched data:

```json
{"ref": "x/preferences", "label": "User preferences", "volatile": true, "trusted": false}
```

It reaches the model as a tool result, not instruction
([AGENT_CONTEXT.md §5.3](AGENT_CONTEXT.md#53-pinned-context)), and is re-read
before each model call, so an edit shows up before the next one. Absent
preferences, or a venue without the adapter, read as absent. The `skilled`,
`worker`, `analyst`, `manager`, `goaltree` and `full` templates, and the
Telegram, Slack and Discord templates, pin it and list the tool; the chat-bot
templates tell the agent to change preferences only when its owner asks.
