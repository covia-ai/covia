# Operations: Implementation Plan

This is a **temporary** implementation tracking document for rolling out the design in `OPERATIONS.md`. It captures concrete touch points, the staged migration, and prerequisites that don't belong in the target-state design.

This doc should be deleted (or moved to git history) once the migration is complete.

**Target design:** see [OPERATIONS.md](OPERATIONS.md).

---

## Prerequisites

### Venue user record

The venue does not currently have a user record in `:user-data` — its DID is used for JWT signing, UCAN issuance, and the DID document, but no `User` instance exists in the lattice keyed by it. The `/v/` namespace requires this record to exist (because `/v/<rest>` resolves to `<venue-DID>/w/global/<rest>`).

At startup, before adapter registration, ensure the venue user record exists:

```java
// In Engine.addDemoAssets or equivalent startup hook
AString venueDID = engine.getDIDString();
venueState.users().ensure(venueDID);
```

This is idempotent — `Users.ensure(did)` creates the record if missing and returns the existing one if present. The venue user is otherwise an ordinary `User` instance.

### Interface extensions

Two small additions to existing interfaces:

- `NamespaceResolver.canWrite(RequestContext ctx)` — new method with default implementation `default boolean canWrite(RequestContext ctx) { return isWritable(); }`. Existing resolvers (`AgentNamespaceResolver`, `TempNamespaceResolver`) get the default for free; behaviour unchanged.
- `CoviaAdapter.ensureUserCursor(AString did)` — new method, parallel to `ensureUserCursor(ctx)`. Returns a cursor positioned at the named user's lattice region. Used by `VenueGlobalsResolver`.

### New code

- `VenueGlobalsResolver` — implements `NamespaceResolver`, registered for the `v/` prefix. `resolve()` returns the venue user's `/w/global/<rest-of-path>` cursor regardless of caller. `canWrite(ctx)` returns true only if `ctx.isInternal() || ctx.getCallerDID().equals(venueDID)`.
- Materialiser hook in `Engine.addDemoAssets` (or equivalent) — populates `/v/ops/` and `/v/info/` after all adapters register.
- `AAdapter.installAsset(String catalogPath, String resourcePath)` — new overload.
- `AAdapter.installExampleAsset(String resourcePath)` — for non-primitive assets; stores in venue CAS but does NOT register in `/v/ops/`.
- `AAdapter.installTestAsset(String catalogPath, String resourcePath)` — used by `TestAdapter`; writes to `/v/test/ops/` instead of `/v/ops/`.

### Universal resolution refactor

Currently the codebase has at least two parallel path-walking implementations:

| Function | Where | Handles | Returns |
|----------|-------|---------|---------|
| `Engine.resolveAsset(ref, ctx)` | `Engine.java:650` | Hex hash, `/a/`, `/o/`, DID URL, workspace path, legacy registry | `Asset` (interprets value as asset metadata) |
| `CoviaAdapter.readPath(...)` | `CoviaAdapter.java` | User lattice paths only (`w/`, `g/`, `o/`, etc.) | `ACell` (raw value) |

This leads to inconsistencies — `grid:run "v/ops/json/merge"` works, but `covia:read path="v/ops/json/merge"` may not handle the same input forms. The plan unifies these into one canonical resolver with layered concerns.

**New: `Engine.resolvePath(ref, ctx) → ACell`** — pure path navigation. Handles every input form (hex hash, `/a/<hash>`, user namespace paths, virtual prefixes including the new `/v/`, DID URLs, plain workspace paths). Returns the **literal value** at the resolved location. Does NOT interpret the value as an asset. Does NOT chase any kind of reference. Single-step navigation only.

**Refactor: `Engine.resolveAsset`** — becomes a thin composition:

```java
public Asset resolveAsset(AString ref, RequestContext ctx) {
    ACell value = resolvePath(ref, ctx);
    return Asset.fromMeta(value);  // null if not a map with operation field
}
```

No recursion. No `MAX_RESOLVE_DEPTH`. If a `/o/<name>` entry isn't a map with an `operation` field, op-invocation fails explicitly with "operation not found at <path>" — which is the correct behaviour because there is no convention for follow-this-pointer.

**Refactor: `CoviaAdapter.readPath`** — delegates to `Engine.resolvePath`. Removes the duplicated path-walking logic. After this change, `covia:read` accepts every input form that `grid:run` does.

**Refactor: `Engine.resolveUserOp`** — drop the implicit string-as-reference branch entirely. The function becomes a pure read of the user's `/o/<name>` value via the standard cursor navigation. Whatever's there is returned literally; `Asset.fromMeta` (called by `resolveAsset`) interprets it. There is no `{ref: ...}` convention, no string-as-ref handling, no recursion. The only callable shape is a map with an `operation` field; everything else is opaque data.

### New `covia:copy` primitive

- **`covia:copy`** — args `from` (any resolvable address), `to` (mutable path). Server-side value duplication: reads from source via `Engine.resolvePath` (caps enforced), writes to dest via standard write path (caps enforced). Returns nothing useful. Implementation `~30 lines`. Asset definition at `venue/src/main/resources/adapters/covia/copy.json`.

### `asset:pin` semantic generalisation

The existing `asset:pin` op (`venue/src/main/java/covia/adapter/AssetAdapter.java`) currently takes a `source` arg that copies an existing asset by hash into the user's `/a/`. Generalise it to accept any resolvable address:

- **`asset:pin path=<any resolvable address>`** — resolves the source value via `Engine.resolvePath`, computes its CAD3 hash, writes to caller's `/a/<hash>`, returns `{ hash: "<hex>" }`. Idempotent — same value re-pinned produces the same hash. Activates per-user `/a/` (currently marked "future" in `Namespace.java:31`).

The generalisation widens the input from "an existing asset reference" to "any resolvable lattice address". The internal storage (writes to caller's `/a/`) and return shape (a hash) are unchanged.

The `source` arg is renamed to `path` for consistency with other read-side ops; the old name stays as a deprecated alias for one cycle.

### `asset:get` and `asset:content` generalisation

- **`asset:get id=<any resolvable address>`** — currently takes a hex hash. Generalise to accept any resolvable address by delegating to `Engine.resolvePath`. The semantic ("get the metadata for an asset") is preserved; the input is widened.
- **`asset:content id=<any resolvable address>`** — same generalisation. The op then checks that the resolved value has a content payload and returns it.

These are backwards-compatible (hex hashes continue to work) and additive (paths and DID URLs newly work).

### Engine resolver chain change

`Engine.resolvePath` includes a `/v/<path>` step (resolving via the new `VenueGlobalsResolver`) before the existing DID URL handling.

---

## Migration phases

The cutover happens in three phases. Each phase can be its own commit/PR.

### Phase 1 — additive (non-breaking)

- Land prerequisites (venue user record, interface extensions, new resolver, new code).
- All adapter-installed primitives that opt into the new `installAsset(catalogPath, resourcePath)` API are written to **both** `engine.operations` (legacy) and `/v/ops/` (new). Adapters that don't opt in stay on the legacy path.
- `covia:functions`, `covia:describe`, `covia:adapters` continue to return their old shapes, unchanged.

After Phase 1: existing callers continue to work; new callers can use `grid:run v/ops/json/merge` and `covia:list v/ops`.

### Phase 2 — deprecation (non-breaking, emits warnings)

#### Adapter migration

One PR per adapter, or batched. Each adapter's `installAssets()` is rewritten to use the new two-arg form.

| Adapter | File | Calls |
|---------|------|-------|
| `JSONAdapter` | `venue/src/main/java/covia/adapter/JSONAdapter.java:64-67` | 4 |
| `JVMAdapter` | `venue/src/main/java/covia/adapter/JVMAdapter.java:38-40` | 3 |
| `SchemaAdapter` | `venue/src/main/java/covia/adapter/SchemaAdapter.java:44-48` | 5 |
| `AgentAdapter` | `venue/src/main/java/covia/adapter/AgentAdapter.java:86-97` | 12 |
| `AssetAdapter` | `venue/src/main/java/covia/adapter/AssetAdapter.java:47-51` | 5 |
| `CoviaAdapter` | `venue/src/main/java/covia/adapter/CoviaAdapter.java:132-141` | 10 |
| `DLFSAdapter` | `venue/src/main/java/covia/adapter/DLFSAdapter.java:104-111` | 8 |
| `VaultAdapter` | `venue/src/main/java/covia/adapter/VaultAdapter.java:50-54` | 5 |
| `GridAdapter` | `venue/src/main/java/covia/adapter/GridAdapter.java:46-49` | 4 |
| `ConvexAdapter` | `venue/src/main/java/covia/adapter/ConvexAdapter.java:51-52` | 2 |
| `MCPAdapter` | `venue/src/main/java/covia/adapter/MCPAdapter.java:56-57` | 2 |
| `LangChainAdapter` | `venue/src/main/java/covia/adapter/LangChainAdapter.java:116-121` | 4 (one moved to `installExampleAsset`) |
| `LLMAgentAdapter` | `venue/src/main/java/covia/adapter/LLMAgentAdapter.java:277` | 1 |
| `GoalTreeAdapter` | `venue/src/main/java/covia/adapter/agent/GoalTreeAdapter.java:200` | 1 |
| `SecretAdapter` | `venue/src/main/java/covia/adapter/SecretAdapter.java:41-42` | 2 |
| `UCANAdapter` | `venue/src/main/java/covia/adapter/UCANAdapter.java:42` | 1 |
| `HTTPAdapter` | `venue/src/main/java/covia/adapter/HTTPAdapter.java:137-145` | 7 (google-search demos move to `installExampleAsset`) |
| `TestAdapter` | `venue/src/main/java/covia/adapter/TestAdapter.java:91-109` | ~13 (all move to `installTestAsset`) |

#### Legacy aliases

Three legacy discovery ops become thin aliases. The asset definitions stay; their handler implementations in `CoviaAdapter` are rewired:

- `covia:functions` → internally calls `covia:list v/ops`. `CoviaAdapter.handleFunctions()` (line ~908).
- `covia:describe(name)` → internally calls `covia:read v/ops/<name>`, with a small name→path translator for legacy `<adapter>:<sub>` form. `CoviaAdapter.handleDescribe()` (line ~932).
- `covia:adapters` → internally calls `covia:list v/info/adapters` and reads each entry. `CoviaAdapter.handleAdapters()` (line ~949).
- Each emits a deprecation log line on use.

#### Other Phase 2 touch points

- `LLMAgentAdapter.java:246-248` — three deprecated ops in `DEFAULT_TOOL_OPS`. Removed only in Phase 3 to avoid breaking agents during the deprecation cycle.
- `engine.operations` registry continues to be populated but is marked deprecated. Bare-name resolution path emits a log line when hit.
- Orchestration assets and skills:
  - `skills/ap-demo/assets/ap-pipeline.json` — step `op` references like `"agent:request"` continue to work via the legacy registry but should be updated to `"v/ops/agent/request"` for consistency.
- Example assets in `venue/src/main/resources/asset-examples/google-search-*.json` are migrated from `installAsset` to `installExampleAsset` in `HTTPAdapter.installAssets()`.
- Documentation updates:
  - `venue/docs/AGENT_LOOP.md` lines 401-402 — discovery tools table.
  - `venue/docs/AGENT_TEMPLATES.md` — template tool lists.
  - `skills/venue-status/SKILL.md` — replace `covia:functions` / `covia:adapters` references with `covia:list v/info` / `covia:list v/ops`.
  - `skills/grid-test/SKILL.md` — same.

### Phase 3 — removal (breaking)

#### Code

- `engine.operations` registry deleted from `Engine.java`.
- Bare-name fallback step deleted from `Engine.resolveAsset`.
- `CoviaAdapter.handleFunctions()`, `handleDescribe()`, `handleAdapters()` deleted, plus their dispatch cases at `CoviaAdapter.java:161-163`.
- `LLMAgentAdapter.java:246-248` — three ops removed from `DEFAULT_TOOL_OPS`.
- `AAdapter.installAsset(String resourcePath)` — single-arg form deleted.

#### Asset definitions

- `venue/src/main/resources/adapters/covia/functions.json` deleted.
- `venue/src/main/resources/adapters/covia/describe.json` deleted.
- `venue/src/main/resources/adapters/covia/adapters.json` deleted.

#### Agent templates

Update `tools` arrays in:

- `venue/src/main/resources/agent-templates/manager.json` (line 5)
- `venue/src/main/resources/agent-templates/worker.json` (line 5)
- `venue/src/main/resources/agent-templates/analyst.json` (line 5)
- `venue/src/main/resources/agent-templates/reader.json` (line 5)

#### Resulting behaviour

- The bare-string form (`grid:run "json:merge"`) no longer resolves; callers must use `v/ops/json/merge` or pin to their own `/o/`.
- Default agent toolset no longer includes the three removed ops; agents discover via `covia:list v/ops` and `covia:list v/info/adapters`.

---

## Phase summary

| Phase | Breaking? | Goal |
|-------|-----------|------|
| 1 | No | Land all new infrastructure alongside the legacy registry |
| 2 | No (warnings) | Migrate adapters and rewire legacy discovery ops as aliases |
| 3 | Yes | Remove the legacy registry and the three deprecated ops |

Phase 3 should be coordinated with downstream consumers (skills, demos, tests).
