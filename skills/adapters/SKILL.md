---
name: adapters
description: Discover, invoke and manage adapters on a Covia venue — list adapters and their operations, inspect an operation's schema, run adapter operations, enable/disable/reconfigure adapters at runtime, and load/unload adapter module jars (covia-sql, covia-python-adapter). Use when a user asks what adapters exist, how to call one, or how to add, remove, configure or turn off an adapter.
argument-hint: "<list|inspect|run|enable|disable|configure|load|unload|status> <name>"
---

# Adapters

**Prerequisite:** The venue must be running and connected as an MCP server (`http://localhost:8080/mcp`). If MCP tools are not available, tell the user to run `/venue-setup local` first.

An **adapter** bridges operations to an execution backend (LLMs, HTTP, files, lattice CRUD, agents, SQL, Python …). Each adapter publishes **operations** into the venue catalog under `v/ops/<adapter>/<op>` (the `test` adapter publishes under `v/test/ops/`). Adapters are either compiled into `covia.jar` or arrive in a **module** jar loaded at boot (`modules` config) or at runtime (`v/ops/venue/module/load`).

The canonical adapter table lives in `venue/CLAUDE.md` ("Adapter Reference"). Groups, for orientation:

| Group | Adapters |
|-------|----------|
| Data & state | `covia` (lattice CRUD), `asset`, `dlfs`, `vault`, `memory`, `secret`, `file`, `archive` |
| Execution | `langchain` (LLMs), `mcp`, `http`, `convex`, `jvm`, `schema`, `orchestrator`, `scheduler` |
| Agents | `agent`, `llmagent`, `goaltree`, `skills`, `hitl` |
| Federation | `grid`, `ucan` |
| Admin | `user`, `venue` (runtime adapter/module lifecycle) |
| Modules (not in covia.jar) | `sql` (covia-sql), `python` (covia-python-adapter), `telegram` (covia-telegram), `claudecode` (covia-claude-code) |
| Testing | `test` |

**Kernel adapters** — `covia`, `agent`, `dlfs`, `hitl`, `http`, `file`, `grid`, `venue` — are adapters the standard venue commonly depends on. The marker is informational: venue-authorised configuration and module lifecycle operations may still replace, disable or remove them.

## Commands

### `list` — What is installed

Run in parallel:

```
covia_list  path=v/info/adapters      # active adapters
covia_list  path=v/info/modules       # loaded module jars
```

Only **active** adapters appear under `v/info/adapters`. A disabled adapter is removed from live dispatch and `v/info`, while its durable catalog metadata may remain. To see disabled ones too, use `status` (needs venue authority, below).

### `inspect <name>` — One adapter and its operations

```
covia_read  path=v/info/adapters/<name>
```

Returns `{name, description, operations: [catalog paths], kernel: bool, module?: "<jar name>"}`. Then, for a specific operation's input/output schema and description:

```
covia_read  path=v/ops/<adapter>/<op>
```

(`covia_list path=v/ops/<adapter>` browses the adapter's catalog subtree.) The `operation.input` schema is what `grid_run` will validate against; `operation.adapter` (e.g. `covia:write`) is the adapter's internal dispatch string, **not** an invoke reference.

### `run <adapter> <op>` — Invoke an operation

Always reference an operation by a resolvable name — its catalog path, a pin (`o/…`), a hash, or a DID URL:

```
grid_run     operation=v/ops/<adapter>/<op>  input=<json>     # synchronous, returns the result
grid_invoke  operation=v/ops/<adapter>/<op>  input=<json>     # async, returns a job ID
```

Examples: `grid_run operation=v/test/ops/echo input={"ping":"pong"}`, `grid_run operation=v/ops/langchain/anthropic input={"prompt":"…"}`. Add `venue=<url>` to run on a remote venue (see `/federation`).

If the run fails with *"Cannot resolve operation"*, the adapter is disabled, unloaded, or the path is wrong — `inspect` it. If it fails with a capability denial, the operation is gated (e.g. `secret/decrypt`, `agent/message`) — see `/ucan`.

## Runtime lifecycle (venue administration)

The `venue` adapter changes the adapter set of a *running* venue, no restart:

| Command | Operation | Input |
|---------|-----------|-------|
| `status` | `v/ops/venue/adapters` | `{}` — every registered adapter, **active and disabled**, with `enabled`, `kernel`, `module`, effective `config`, `operations`; plus loaded `modules` |
| `disable <name>` | `v/ops/venue/adapter/disable` | `{"name": "<adapter>"}` |
| `enable <name>` | `v/ops/venue/adapter/enable` | `{"name": "<adapter>"}` |
| `configure <name>` | `v/ops/venue/adapter/configure` | `{"name": "<adapter>", "config": {…}, "merge": false}` — `config` is the `adapters.<name>` shape; `merge: true` overlays the current effective config instead of replacing it |
| `load <jar>` | `v/ops/venue/module/load` | `{"module": "<jar name>", "sha256": "<hex>", "config": {…}}` — `sha256` and `config` optional |
| `unload <name>` | `v/ops/venue/module/unload` | `{"name": "<jar name without .jar>"}` |

All are invoked with `grid_run operation=v/ops/venue/... input=...`. Enable/disable/configure return `{name, enabled, changed}` / `{name, config}` — `changed: false` means it was already in that state (idempotent). Load returns `{name, path, sha256, adapters}`; unload returns `{name, path, adapters, unloaded}`.

### Authority — read this before trying

These are **venue-owned**: they require `adapter/manage` on `<venueDID>/adapters`. A null (unrestricted) capability scope is deliberately *not* enough, so **the default local MCP connection — which acts as the venue's public user — is denied** with `Venue administration denied: requires adapter/manage on <venueDID>/adapters from the venue (call as the venue or present a venue-issued delegation)`. That is by design (loading in-process code is total compromise of the venue), not a misconfiguration. Do not loop retrying; explain and pick a route:

1. **Call as the venue** — authenticate with the venue's own key pair (config `seed`, `keystore`, or the auto-generated `venue.key` beside a persistent store; an ephemeral `local-dev.json` venue has a fresh random key each start, so this route needs a persistent config such as `dev/local.json`). From the Java SDK:
   ```java
   AKeyPair kp = AKeyPair.create(Blob.fromHex(seedHex));
   Venue v = Grid.connect("http://localhost:8080", VenueAuth.keyPair(kp, venueDID));      // did:key venue
   // did:web venue: VenueAuth.identityKeyPair(kp, venueDID, venueDID)
   v.run("v/ops/venue/adapters", Maps.empty()).get();
   ```
   The same strategy's `mintToken()` gives a bearer JWT for `curl … -H "Authorization: Bearer <jwt>" POST /api/v1/run` or an MCP client `headers` block. Never paste the seed into chat or commit it.
2. **In-process operator code** — anything holding the `Engine` uses `engine.jobs().invokeOperation("v/ops/venue/...", input, engine.venueContext())`. This is how tests and embedded venues do it.
3. **Venue-issued delegation** — for a standing admin identity, mint a UCAN *as the venue* (route 1 or 2, `v/ops/ucan/issue` with `att: [{"with": "<venueDID>/adapters", "can": "adapter/manage"}]`, `aud` = the admin's DID); the admin then presents it as a transport proof (`ucans` / bearer). Same model as `user:create`. See `/ucan`.

### Module policy

`module/load` and `module/unload` additionally require the operator opt-in `dynamicModules.enabled: true` (default **off**) in the venue config; otherwise they fail with a policy error regardless of authority. By default `module` must be a **jar name inside the staging directory** `dynamicModules.dir` (default `modules`, relative to the venue's working directory) — no absolute paths, no `..`, and the resolved real path must stay inside the directory. `dynamicModules.anyPath: true` widens this to any filesystem path (a relative one still resolves against `dir`).

Loading covia-sql at runtime, end to end:

```bash
mvn -pl covia-sql -am package -DskipTests               # produces covia-sql-<ver>-module.jar
mkdir -p modules && cp covia-sql/target/covia-sql-*-module.jar modules/
sha256sum modules/covia-sql-*-module.jar                # PowerShell: Get-FileHash -Algorithm SHA256
```
then (with venue authority) `grid_run operation=v/ops/venue/module/load input={"module":"covia-sql-<ver>-module.jar","sha256":"<hex>"}` → `sql` adapter appears, `v/ops/sql/query` and `v/ops/sql/execute` are live, and its module-shipped agent skill materialises at `v/skills/sql`. Unload with `input={"name":"covia-sql-<ver>-module"}` (jar name without `.jar`, as listed by `status` / `v/info/modules`).

A module jar is a shaded jar compiled against `venue` (provided scope) that declares its adapters in `META-INF/services/covia.adapter.AAdapter`; `config` on load is passed to every adapter in the module before registration (`modules[].config` shape).

### Semantics to relay to the user

- An authorised module load is last-write-wins: its adapters and catalog declarations replace existing names and paths. Disable/unload removes live dispatch and introspection but leaves durable catalog metadata until it is overwritten or explicitly deleted.
- **In-flight jobs finish** on the retained adapter instance; anything that re-resolves the adapter by name later (multi-turn messages, restart recovery) fails at that point of use.
- `configure` runs the adapter's `configure` hook — it may **reject** the settings, in which case nothing changes and the error says why.
- Unload closes `AutoCloseable` adapters and the module classloader; JVM class unloading is best-effort (JDBC `DriverManager`, JNI can pin a loader). "Unloaded" means deregistered and released, not guaranteed collected.
- The **live runtime adapter set is not persisted.** After a restart the venue config (`adapters.*`, `modules`) is authoritative again; catalog metadata is lattice state and may remain.

## Boot-time configuration (persistent)

For changes that must survive restarts, edit the venue config instead (see `venue/docs/CONFIG.md`, and `/venue-setup` for where the config lives):

```json
{
  "adapters": {
    "test":         { "enabled": false },          // park an adapter (enable later at runtime)
    "orchestrator": { "maxItems": 50, "maxConcurrency": 8 },
    "vault":        { "drive": "vault" },
    "agent":        { "sessionDelete": false }
  },
  "modules": [
    "modules/covia-sql-<version>-module.jar",
    { "path": "modules/covia-python-adapter-<version>-module.jar", "sha256": "…", "config": { "library": "/usr/lib/libpython3.13.so" } }
  ],
  "dynamicModules": { "enabled": true, "dir": "modules", "anyPath": false }
}
```

- `adapters.<name>` — per-adapter settings; adapters read the *effective* config (this block overlaid by any runtime `configure`).
- `modules` — jars loaded at boot; boot fails fast on any load error; `sha256` pins content.
- `dynamicModules` — the runtime load/unload policy described above.

## Writing a new adapter

Follow the canonical developer contract in `venue/docs/ADAPTERS.md`: it covers `AAdapter` invocation, configuration validation, catalog and introspection publication, private `AdapterWorkspace` state versus user-managed storage, capability enforcement, lifecycle, tests, and optional module packaging. Ship it in `covia.jar` or as a module jar (`META-INF/services/covia.adapter.AAdapter`, shaded, `venue` provided-scope).

## Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| `Venue administration denied: requires adapter/manage …` | Caller is not the venue and holds no venue-issued delegation — see Authority above. Expected for the public MCP user. |
| Operation metadata resolves but invocation fails | Its adapter is disabled/unloaded. Reload a module to overwrite it, or delete the catalog path explicitly. |
| `module/load` policy error | `dynamicModules.enabled` is off, the jar is outside `dynamicModules.dir`, the name has `..`/an absolute path without `anyPath`, or the `sha256` pin mismatched. |
| Change vanished after restart | Runtime changes are not persisted — put it in `adapters.*` / `modules` in the config. |
| Newly enabled adapter's tools missing in the MCP client | Registry rebuilt server-side; reconnect the client (`/mcp`). |
