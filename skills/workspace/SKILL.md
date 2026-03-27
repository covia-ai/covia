---
name: workspace
description: Explore and manage workspace data on a Covia venue — browse paths, read/write values, understand namespace structure. Use when inspecting or manipulating venue state.
argument-hint: [browse|read|write] [path]
---

# Workspace Explorer

**Prerequisite:** The venue must be running and connected as an MCP server (`http://localhost:8080/mcp/sse`). If MCP tools are not available, tell the user to run `/venue-setup local` first.

Navigate and manage data in the venue's lattice namespace.

## Namespace Structure

| Prefix | Name | Access | Purpose |
|--------|------|--------|---------|
| `g/` | Agents | Read-only (framework-managed) | Agent records, state, timeline |
| `s/` | Secrets | Read-only (framework-managed) | Encrypted per-user secrets |
| `j/` | Jobs | Read-only (framework-managed) | Job records and history |
| `w/` | Workspace | Read/write | User data — free-form storage |
| `o/` | Operations | Read/write | User-defined operations |

## Commands

### `browse [path]` — Explore structure

```
covia_list  path=<path>
```

Omit path for top-level. Returns type, keys (for maps), count (for vectors/countables), or values (for sets).

For deeper exploration, follow keys:
```
covia_list  path=w              → workspace keys
covia_list  path=w/my-data      → structure of my-data
covia_list  path=g              → agent names
covia_list  path=g/Alice        → Alice's record structure
```

### `read <path>` — Read a value

```
covia_read  path=<path>
```

Returns `{exists: true, value: <data>}` or `{exists: false}`.

For large values, use `covia_slice` with offset/limit for pagination.

### `write <path> <value>` — Write a value

```
covia_write  path=w/<key>  value=<json>
```

Only `w/` and `o/` namespaces are writable. Writing `null` deletes the entry.

Deep paths are supported — intermediate maps are created as needed:
```
covia_write  path=w/config/api/timeout  value=30000
```

### Vector operations

Append to a vector (creates it if missing):
```
covia_append  path=w/events  element={"type": "login", "ts": 1234567890}
```

Read a slice:
```
covia_slice  path=w/events  offset=0  limit=10
```

## Agent Timeline Inspection

The most useful read-only path. Each agent's timeline is a vector of run records:

```
covia_read  path=g/Alice/timeline      → full timeline
covia_slice  path=g/Alice/timeline  offset=0  limit=1  → latest run only
```

Each timeline entry contains: start/end timestamps, tasks, taskResults, messages, state, op, result.
