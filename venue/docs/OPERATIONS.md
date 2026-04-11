# Operations: Naming, Discovery, and Resolution

**Status:** Draft v3 — corrections from cross-reference review (interface extensions, prerequisites, dual-storage clarification).
**Owner:** venue team
**Replaces:** the implicit `engine.operations` global registry and the special-case `covia:functions` / `covia:describe` / `covia:adapters` discovery operations.

**v3 changes from v2:**
- Corrected the references to existing virtual namespaces (`n/` and `t/`, not `/h/` and `/n/`).
- Added §3.3 explaining that `/v/` is a *venue-scoped* virtual resolver, a new pattern distinct from the existing *caller-scoped* `n/` and `t/` resolvers. Documents the small `NamespaceResolver` and `CoviaAdapter` interface extensions required.
- Added §7.0 documenting the venue user record creation as a Phase 1 prerequisite (the venue does not currently have a user record in `:user-data`).
- Added §5.4 clarifying the dual storage model: primitive content stays in `venueState.assets()` (existing venue-level CAS), while inline metadata at `<venue-DID>/w/global/ops/<path>` is an additional copy enabled by lattice structural sharing.
- Expanded §9 with the concrete migration touch points found in the codebase.

**Reference docs:**
- `venue/docs/GRID_LATTICE_DESIGN.md` — namespace model, `/o/` semantics, virtual-namespace resolvers, capability hierarchy.
- `venue/docs/UCAN.md` — capability tokens (referenced for write-side admin delegation; v1 reads are resolver-enforced and need no UCAN).
- `covia-docs/docs/protocol/cogs/COG-005.md` — asset metadata format.
- `covia-docs/docs/protocol/cogs/COG-007.md` — operation specification.

**Related work:**
- [covia-ai/covia#78](https://github.com/covia-ai/covia/issues/78) — unify budget / overflow semantics across `covia:read` / `covia:slice` / `covia:inspect`. Improves catalog-browsing UX but not blocking.

---

## 1. Context

A user invoking an operation today can reach it through several paths:

1. By Asset ID: `grid:run "abc123..."`
2. By bare adapter sub-op string: `grid:run "json:merge"`
3. By workspace path: `grid:run "w/config/ap-pipeline"`
4. By `/o/<name>` — per-user operations namespace
5. By DID URL: `grid:run "did:web:venue.host:u:alice/o/my-op"`

The middle path (2) is the historical default and currently the most common. It works through a global `engine.operations` map keyed by `operation.adapter` strings (`json:merge`, `agent:create`, etc.), populated during adapter registration. Discovery of what's available goes through `covia:functions`, which iterates that map.

This has structural problems:

1. **Global registry collides on bare adapters.** Assets whose `operation.adapter` is just `"orchestrator"` (no sub-op) all map to the same key. The last-registered wins. The result: legitimate orchestration assets shadow each other, and `covia:functions` shows misleading `name: "orchestrator"` entries (observed live in venue testing).

2. **`operation.adapter` is dispatch info, not a user-facing name.** It identifies which adapter handles the operation and how. The format is the adapter's business — not a contract that users should be coupling to. Leaking it as the canonical callable name violates this layering.

3. **`covia:functions` is a parallel data path.** It returns a flat global view that ignores the caller's identity, ignores per-user pins in `/o/`, ignores federation (other venues, other users), and duplicates information that already lives in the lattice. It exists because there is no other way to enumerate what's invokable.

4. **No clean place for venue-provided defaults.** Venue-installed primitive operations live in the global registry. User-pinned operations live in `/o/` per user. There's no namespace that says "these are the operations the venue offers to everyone" without resorting to a magic registry.

5. **Discovery is not federated.** `covia:functions` only knows about the local venue's registry. Discovering operations on a remote venue requires a different code path.

**This document does not invent new metadata fields or new adapter conventions.** It uses the lattice primitives that already exist (`covia:list`, `covia:read`, namespace resolution, DID URLs) and adds a single new virtual namespace prefix for venue-scoped globals.

---

## 2. Goals and non-goals

**Goals**

- Operations have **resolvable names**, not adapter dispatch strings, as their user-facing identity.
- Discovery of operations is **just lattice listing** — no special-case API.
- Per-user pins (in `/o/`) and venue-provided defaults (in `/v/ops/`) are first-class citizens with the same access pattern.
- Federation works for free: discovering operations on a remote venue is just listing a remote DID URL.
- The catalog is **scalable** by construction — pagination is the existing `covia:list` / `covia:slice` API.
- Access policy is **intrinsic to the namespace**, not a separate UCAN dance: the resolver enforces it.
- Consistent with COG-5 / COG-7 (no new metadata fields), and with the GRID_LATTICE_DESIGN namespace and resolver model.

**Non-goals**

- A new metadata schema or new top-level fields on operation assets.
- A new adapter dispatch convention. Adapters remain free to use any `operation.adapter` format internally.
- Removing the existing `/o/` user namespace semantics (mutable, schema-enforced, open-read defaults). `/o/` stays as specified in GRID_LATTICE_DESIGN §4.
- Backwards compatibility with the bare-string `grid:run "json:merge"` form forever. It is supported as a deprecated alias for one transition cycle, then removed.
- A search index over operations. Listing + client-side filter is sufficient for v1.
- Multi-admin authentication for `/v/` writes (venue keypair is sufficient for v1; UCAN delegation is a v2 enhancement).

---

## 3. Namespace model

Two namespaces are involved:

### `/o/` — per-user operations (existing, unchanged)

User-owned, mutable, per-user. Each user has their own `/o/` lattice region under their DID's user record. Users write here to pin operations under names of their choosing — aliases for venue defaults, custom orchestrations they author, or references to operations on other venues.

Semantics, access control, and pin-to-`/a/`-on-invoke behaviour are as already specified in `GRID_LATTICE_DESIGN.md` §4.3 and §4.4. **This document does not change `/o/`.**

### `/v/` — venue globals (new virtual namespace)

`/v/` is a **virtual namespace prefix**, registered alongside the existing `n/` and `t/` resolvers (GRID_LATTICE_DESIGN §4.5). It is not a new top-level lattice region; it is convenience syntax over an existing storage location.

**Resolution.** `/v/<rest>` resolves to the venue user's workspace at `w/global/<rest>`. Concretely: `v/ops/json/merge` is the same lattice cell as the venue user's workspace path `w/global/ops/json/merge`. The venue is treated as a user — it has its own DID (already used for JWT signing, UCAN issuance, and DID document publication) and therefore has a user record with a `/w/` workspace. Its `/w/global/` sub-tree is the place it publishes public data.

**Why this design.** Storage reuse — `/w/` is already a per-user mutable region with persistence, sync, lifecycle, and capability semantics built in. The venue user's `/w/` gets all of this for free. The convention `w/global` is intentionally not load-bearing infrastructure: any user can publish their own `w/global` bulletin board. The `/v/` prefix is syntactic sugar that defaults the resolved DID to the local venue, sparing clients from the discovery step.

### Sub-namespaces in v1

| Path | Purpose | Status |
|------|---------|--------|
| `/v/ops/` | Venue-provided operations catalog | v1 |
| `/v/info/` | Venue identity, version, adapters, protocols | v1 |
| `/v/test/` | Test-only operations and fixtures (separate from `/v/ops/`) | v1 |
| `/v/config/` | Public venue configuration | claimed; deferred |

#### `/v/info/` contents

| Path | Type | Source |
|------|------|--------|
| `/v/info/name` | string | venue config `name` |
| `/v/info/did` | string | venue keypair |
| `/v/info/version` | string | jar manifest / `pom.properties` |
| `/v/info/started` | long (epoch ms) | `System.currentTimeMillis()` at boot |
| `/v/info/protocols` | array of strings | enabled protocol handlers (e.g. `["rest","mcp","a2a","dlfs-webdav"]`) |
| `/v/info/adapters/<name>` | map | per-adapter summary: `{name, description, operations: <count>}` |

A single `covia:slice v/info` round trip gives an agent or tool a complete venue introspection view.

### `/v/` is a venue-scoped resolver — a new pattern

The existing virtual namespaces (`n/` and `t/`) are **caller-scoped**: they navigate to a sub-region inside the *caller's own* user data based on `RequestContext` (`agentId` for `n/`, `jobId` for `t/`). Both implementations call `adapter.ensureUserCursor(ctx)` to get the caller's cursor, then rewrite or navigate within it.

`/v/` is **venue-scoped**: it navigates to a *fixed user* — the venue user — regardless of who is calling. `v/ops/json/merge` always resolves to `<venue-DID>/w/global/ops/json/merge`, whether read by Alice, Bob, or an anonymous client.

This is a new pattern in the resolver framework. To support it, two small interface extensions are required:

1. **`CoviaAdapter.ensureUserCursor(AString did)`** — a parallel to the existing `ensureUserCursor(ctx)`, returning the cursor for a *named* user rather than the calling user. Used by the `/v/` resolver to get the venue user's cursor.

2. **`NamespaceResolver.canWrite(RequestContext ctx)`** — a per-call write check. Existing resolvers have `boolean isWritable()` which is a static yes/no, fine for caller-scoped namespaces (the caller writes to their own data; if they're authorised at all, they're authorised). Venue-scoped `/v/` needs to check the calling identity at write time. Existing resolvers get a default implementation that defers to `isWritable()`, so no behavioural change for `n/` and `t/`.

The existing access pattern for `n/` and `t/` is implicit ("the cursor IS your own data, so writes are inherently authorised"). The new pattern for `/v/` is explicit ("the cursor is someone else's data, so the resolver checks authorisation against the caller's identity"). Both patterns coexist; `/v/` is the first member of the new family.

### Access control

`/v/` access is **enforced by the namespace resolver**, not by stored UCAN proofs:

| Operation | Caller | Result |
|-----------|--------|--------|
| Read `/v/...` (any sub-namespace) | Anyone (anonymous, authenticated, internal) | Allowed |
| Write `/v/...` | `RequestContext.INTERNAL` (engine code) | Allowed |
| Write `/v/...` | JWT signed by venue keypair (operator) | Allowed |
| Write `/v/...` | Anyone else | Rejected with permission error |

The `/v/` resolver short-circuits the standard user-workspace access check for reads, and verifies the caller is the venue identity for writes via the new `canWrite(ctx)` method. There is **no UCAN issued at startup** for public reads; the policy is intrinsic to the namespace. This is a more explicit version of the implicit access policy that the existing `n/` and `t/` resolvers rely on (where the caller's identity is the cursor's owner by construction).

User `/o/` capability semantics are unchanged from GRID_LATTICE_DESIGN §6.

**Implication: `/v/` is exclusively the public namespace.** Anything the venue wants private (admin secrets, audit logs, internal config) lives elsewhere — in the venue user's `/s/`, `/j/`, or `/w/` (without the `global/` sub-key). `/v/` is by definition the venue's public bulletin board.

---

## 4. Resolution

The existing `Engine.resolveAsset` chain (Engine.java around line 650) becomes:

1. **Bare hex hash** → `getAsset(hash, ctx)`
2. **`/a/<hash>`** → explicit asset namespace
3. **`/o/<name>`** → caller's own `/o/` (existing `resolveUserOp`)
4. **`/v/<path>`** → venue globals namespace (NEW — virtual prefix to `<venue-DID>/w/global/<path>`)
5. **DID URL** (`did:...`) → cross-user / cross-venue
6. **Workspace path** (`w/`, `g/`, `o/`, `j/`, etc. without leading slash) → recursive deref through caller's lattice
7. **Bare operation name** (legacy) → global `engine.operations` registry, **deprecated**, removed after one cycle

A reference like `o/json/merge` (with no leading `/v/`) resolves to the **caller's own** `/o/json/merge`. There is **no automatic fallback** from caller's `/o/` to `/v/ops/`. To call a venue default, the caller writes the explicit reference: either `v/ops/json/merge` or via a pin in their own `/o/` (which can itself be a string ref to `v/ops/json/merge`).

**Why no fallback?**

1. **Honest semantics.** `covia:list o` returns exactly what's in the caller's `/o/`, with no synthetic merging. A user who has pinned nothing sees an empty list — and they know to look at `/v/ops/` for venue defaults.
2. **No shadowing surprises.** A user's pin can never accidentally hide or be hidden by a venue default with the same name, because they live in different namespaces.
3. **Federation parity.** Resolving `did:web:other.host:v/ops/json/merge` works the same way as resolving `v/ops/json/merge` locally. The path structure is the same; only the DID prefix differs.

Users who want "venue defaults under short names" can pin them: `covia:write o/merge "v/ops/json/merge"`. The pin stores a string reference; resolution follows the chain. This is the standard alias mechanism.

---

## 5. Entry shape

Each entry in `/v/ops/` is **full asset metadata** — the same JSON the adapter installs via `installAsset`, stored inline at the catalog path. **The catalog entry IS the asset.** It is not a descriptor pointing at one.

```json
{
  "name": "JSON Merge",
  "description": "Deep-merges a vector of maps using RFC 7396 semantics.",
  "creator": "Covia",
  "operation": {
    "adapter": "json:merge",
    "input":  { "type": "object", "properties": { "values": { "type": "array" } } },
    "output": { "type": "object", "properties": { "result": { "type": "object" } } }
  }
}
```

### Why inline metadata, not a descriptor with a ref?

1. **The existing resolver already handles it.** `Engine.resolveUserOp` (Engine.java around line 777) treats a map with an `operation` field as inline metadata via `Asset.fromMeta`. The `/v/ops/` case uses this code path with no new resolver logic.
2. **Single round trip for invocation.** `grid:run v/ops/json/merge` reads the metadata and dispatches. No follow-up `asset:get` to fetch schemas.
3. **Single round trip for discovery.** `covia:read v/ops/json/merge` returns everything an agent or tool needs to render a catalog entry. `covia:slice v/ops` paginates over full entries.
4. **Asset identity is recoverable.** No `ref` field needed — the canonical encoding of the inline map IS the asset's CAD3 ID by definition. Content addressing working correctly.
5. **Storage cost is essentially zero.** The metadata is structurally shared with the immutable copy in the venue-level asset CAS — same Merkle subtree, two parents in the lattice tree.

### Dual storage clarification

In v1, primitive metadata exists in **two places** in the venue's lattice:

| Where | Purpose | Mechanism |
|-------|---------|-----------|
| `venueState.assets()` (existing venue-level CAS) | Content-addressed lookup by CAD3 hash | `engine.storeAsset(metaString, content)` writes here. Backed by the existing top-level asset cursor, separate from any user namespace. Used by `installAsset` today. |
| `<venue-DID>/w/global/ops/<catalogPath>` (new) | Named lookup via `/v/ops/<catalogPath>` | New step in the materialiser, writes the same metadata as inline content under the venue user's workspace. Reachable via the `/v/` resolver. |

These are **two distinct lattice locations** that hold equivalent content. Lattice structural sharing means the underlying Merkle tree is shared — the storage cost of having both is a few extra map entries pointing into the same subtree, not a duplicated copy of the bytes. The `/v/ops/` entry includes the asset hash (recoverable from the inline content's canonical encoding), so a client that wants to fetch the canonical asset can do so via the existing CAS.

A potential future simplification: unify these two storage locations by migrating venue assets to the venue user's `/a/` (currently marked "future" in `Namespace.java`). For v1 we keep both paths and let structural sharing absorb the duplication.

### User pins: three accepted forms

The same `resolveUserOp` already supports three forms for `/o/` entries. They all continue to work:

| Form | Example | When |
|------|---------|------|
| **Inline metadata** | `{"name":"...","operation":{"adapter":"json:merge",...}}` | Custom orchestrations composed inline |
| **String reference** | `"v/ops/json/merge"` or `"<hash>"` | Cheap aliases (`covia:write o/merge "v/ops/json/merge"`) |
| **Hash-only ref** | `"abc123..."` (bare hex) | Pin a content-addressed asset by hash |

`/v/ops/` entries always use inline metadata (form 1). User pins choose freely.

---

## 6. Discovery

There is **no special-case discovery API**. The catalog is the lattice; you read it with the lattice ops.

### From a user's perspective

```
# What have I pinned in my own /o/?
covia:list path=o

# Page through it with full details
covia:slice path=o offset=0 limit=50

# Read details about one entry
covia:read path=o/my-favourite-op

# What does the venue offer as defaults?
covia:list path=v/ops

# Walk the venue introspection
covia:list path=v/info
covia:read path=v/info/version
covia:read path=v/info/adapters/json

# What about a remote venue?
covia:list path=did:web:other.venue:v/ops

# What about another user on this venue?
covia:list path=did:web:this.venue:u:alice/o
```

All examples use the same primitives (`covia:list` / `covia:read` / `covia:slice`), parameterised by path. Pagination, federation, and cross-user discovery are uniform.

### From an agent's perspective

Agents already have `covia_list`, `covia_read`, `covia_slice`, `covia_inspect` in their default toolset. **No new tools needed.** The agent system prompt should include a one-line hint:

> Operations live in `/v/ops/` (venue defaults) and your own `/o/` (your pins). Adapter info lives in `/v/info/adapters/`. Use `covia:list` to discover, `covia:read` to read details.

After Phase 3 (see §9), the existing `covia:functions`, `covia:describe`, and `covia:adapters` ops are removed from the default toolset.

### Three deprecated discovery ops

Three existing `covia:` ops become redundant under this model and are deprecated together:

| Op | Replaced by | Notes |
|----|-------------|-------|
| `covia:functions` | `covia:list v/ops` | Returns the venue catalog. Same useful info, generic mechanism. |
| `covia:describe(name)` | `covia:read v/ops/<path>` | The `/v/ops/` entry IS the asset metadata; reading it returns the full schema. |
| `covia:adapters` | `covia:list v/info/adapters` | List adapter names; `covia:read v/info/adapters/<name>` for per-adapter detail. |

All three follow the same three-phase migration in §9.

---

## 7. Initialisation

`/v/ops/` and `/v/info/` are populated at startup, after each adapter has registered its primitives.

### Prerequisite: venue user record

The venue does not currently have a user record in `:user-data` — its DID is used for JWT signing, UCAN issuance, and the DID document, but no `User` instance exists in the lattice keyed by it. The `/v/` namespace requires this record to exist (because `/v/<rest>` resolves to `<venue-DID>/w/global/<rest>`).

**Phase 1 prerequisite step:** at startup, before adapter registration, ensure the venue user record exists:

```java
// In Engine.addDemoAssets or equivalent startup hook
AString venueDID = engine.getDIDString();
venueState.users().ensure(venueDID);
```

This is idempotent — `Users.ensure(did)` creates the record if missing and returns the existing one if present. The venue user is otherwise an ordinary `User` instance: it has its own `/w/`, `/o/`, `/g/`, `/j/`, `/s/`, `/h/` sub-namespaces, all initially empty. Only `/w/global/` is populated by the materialiser; the others remain unused unless the venue itself runs operations as the venue identity (which it does for some internal book-keeping).

### Lifecycle

```
Engine startup:
  1. Adapters register and install their primitives
     for each adapter:
       adapter.install(engine)
         # Adapter calls installAsset(catalogPath, resourcePath) for each primitive.
         # installAsset: stores meta in /a/<hash> AND inline at /v/ops/<catalogPath>.

  2. Materialise /v/info/
     write v/info/name       ← config.name
     write v/info/did        ← venueDID
     write v/info/version    ← jarVersion()
     write v/info/started    ← System.currentTimeMillis()
     write v/info/protocols  ← enabledProtocols()
     for each registered adapter:
       write v/info/adapters/<name> ← {name, description, operations: opCount}

  3. Sweep stale /v/ entries (see §10)
```

The materialisation step is **re-run on every startup** and is idempotent (modulo `started`, which legitimately reflects the current boot time): same adapters and same config produce the same paths. Persistence of `/v/` across restarts is therefore largely cosmetic — the lattice converges to the same state each boot.

### `installAsset` API change

Adapters explicitly declare their catalog path. The dispatch string `operation.adapter` is opaque; the venue does not parse it for naming.

**Old (existing):**
```java
installAsset("/adapters/json/merge.json");
```

**New (proposed):**
```java
installAsset("json/merge", "/adapters/json/merge.json");
```

The catalog path is the first argument; the asset definition resource path is the second. Adapters choose any path structure they like, subject to validation below.

### Validation rules

The venue validates the catalog path before writing. A path is valid if and only if:

- It is a non-empty `/`-separated string of segments
- Each segment matches `^[a-z][a-z0-9-]*$` (lowercase alphanumeric + hyphens, must start with a letter)
- No segment is `.` or `..`
- No two adapters install at the same `/v/ops/<path>` (duplicates rejected at startup with a clear error)

Invalid paths are skipped with a startup warning. The asset still exists in `/a/<hash>` and remains callable by hash, but does not appear in the catalog.

### Compositions and example assets

Some `installAssets()` calls today install example orchestrations and demo assets — assets whose `operation.adapter` is just a bare adapter name with no sub-op (e.g. `"orchestrator"` for an orchestration). These are **not primitives** and should not pollute the catalog.

A separate method handles these:

```java
installExampleAsset("/asset-examples/google-search.json");
```

This stores the asset in `/a/<hash>` (so it's still discoverable by hash) but does NOT write it to `/v/ops/`. Examples are not primitives; users can pin them under their own `/o/` if they want.

The venue's existing example assets (Google Search demo, etc.) currently installed via `installAsset` are migrated to `installExampleAsset` in Phase 2.

### Test operations

The `test:` adapter installs ops at `/v/test/ops/<path>` instead of `/v/ops/test/<path>`. This puts test ops in their own sub-namespace under `/v/test/`, hidden by default from `/v/ops/` listings while remaining callable via the explicit path. Clients that want test ops know to look there.

A separate `installTestAsset(catalogPath, resourcePath)` method writes to `/v/test/ops/` instead of `/v/ops/`. The `test:` adapter uses this exclusively.

---

## 8. Capability model

`/v/` access is enforced by the namespace resolver, not by stored UCAN proofs.

| Operation | Mechanism |
|-----------|-----------|
| Read `/v/...` | Resolver returns the value unconditionally. No UCAN required. |
| Write `/v/...` (startup) | Engine code uses `RequestContext.INTERNAL`, which the resolver recognises as the venue identity. |
| Write `/v/...` (operator) | JWT signed by the venue keypair. The auth middleware identifies the caller as the venue's own DID; the resolver allows the write. |
| Write `/v/...` (anyone else) | Resolver rejects with a permission error. |

**No new auth machinery.** No "default proofs" registry, no UCAN issuance at startup, no special-case in `AccessControl.canAccess`. The special case lives where it belongs — inside the namespace resolver that owns the prefix.

User `/o/` capability semantics are unchanged from GRID_LATTICE_DESIGN §6.

**Future: admin UCAN delegation.** If an operator needs to grant write access to `/v/` to a specific admin user without sharing the venue keypair, the venue can issue a UCAN granting `{with: did:web:venue/v/, can: crud}` to that admin. The admin then signs requests with their own keypair and presents the UCAN as a proof. This is a v2 enhancement; not required for v1.

---

## 9. Migration

The cutover happens in three phases. Each phase can be its own commit/PR.

### Phase 1 — additive (no breakage)

**Prerequisite:** ensure venue user record exists at startup (per §7 prerequisite).

**Interface extensions:**

- `NamespaceResolver.canWrite(RequestContext ctx)` — new method with default implementation `default boolean canWrite(RequestContext ctx) { return isWritable(); }`. Existing resolvers (`AgentNamespaceResolver`, `TempNamespaceResolver`) get the default for free; behaviour unchanged.
- `CoviaAdapter.ensureUserCursor(AString did)` — new method, parallel to `ensureUserCursor(ctx)`. Returns a cursor positioned at the named user's lattice region. Used by `VenueGlobalsResolver`.

**New code:**

- `VenueGlobalsResolver` — implements `NamespaceResolver`, registered for the `v/` prefix. `resolve()` returns the venue user's `/w/global/<rest-of-path>` cursor regardless of caller. `canWrite(ctx)` returns true only if `ctx.isInternal() || ctx.getCallerDID().equals(venueDID)`.
- Materialiser hook in `Engine.addDemoAssets` (or equivalent) — populates `/v/ops/` and `/v/info/` after all adapters register.
- `AAdapter.installAsset(String catalogPath, String resourcePath)` — new overload. The single-arg form is kept for backwards compatibility during Phase 1.
- `AAdapter.installExampleAsset(String resourcePath)` — for non-primitive assets; stores in venue CAS but does NOT register in `/v/ops/`.
- `AAdapter.installTestAsset(String catalogPath, String resourcePath)` — used by `TestAdapter`; writes to `/v/test/ops/` instead of `/v/ops/`.

**Engine changes:**

- `Engine.resolveAsset` adds step 4 (`/v/<path>`) before the existing DID URL handling.
- All adapter-installed primitives that opt into the new API are written to **both** `engine.operations` (legacy) and `/v/ops/` (new). Adapters that don't opt in stay on the legacy path.

**Behaviour after Phase 1:**

- `covia:functions`, `covia:describe`, `covia:adapters` continue to return the old shapes, unchanged.
- New callers can use `grid:run v/ops/json/merge` and `covia:list v/ops`.
- Existing callers using `grid:run "json:merge"` continue to work via the legacy registry.

### Phase 2 — deprecation

**Adapter migration** (one PR per adapter, or batched):

| Adapter | Files |
|---------|-------|
| `JSONAdapter` | `venue/src/main/java/covia/adapter/JSONAdapter.java:64-67` (4 calls) |
| `JVMAdapter` | `venue/src/main/java/covia/adapter/JVMAdapter.java:38-40` (3 calls) |
| `SchemaAdapter` | `venue/src/main/java/covia/adapter/SchemaAdapter.java:44-48` (5 calls) |
| `AgentAdapter` | `venue/src/main/java/covia/adapter/AgentAdapter.java:86-97` (12 calls) |
| `AssetAdapter` | `venue/src/main/java/covia/adapter/AssetAdapter.java:47-51` (5 calls) |
| `CoviaAdapter` | `venue/src/main/java/covia/adapter/CoviaAdapter.java:132-141` (10 calls) |
| `DLFSAdapter` | `venue/src/main/java/covia/adapter/DLFSAdapter.java:104-111` (8 calls) |
| `VaultAdapter` | `venue/src/main/java/covia/adapter/VaultAdapter.java:50-54` (5 calls) |
| `GridAdapter` | `venue/src/main/java/covia/adapter/GridAdapter.java:46-49` (4 calls) |
| `ConvexAdapter` | `venue/src/main/java/covia/adapter/ConvexAdapter.java:51-52` (2 calls) |
| `MCPAdapter` | `venue/src/main/java/covia/adapter/MCPAdapter.java:56-57` (2 calls) |
| `LangChainAdapter` | `venue/src/main/java/covia/adapter/LangChainAdapter.java:116-121` (4 calls; one moved to `installExampleAsset`) |
| `LLMAgentAdapter` | `venue/src/main/java/covia/adapter/LLMAgentAdapter.java:277` (1 call) |
| `GoalTreeAdapter` | `venue/src/main/java/covia/adapter/agent/GoalTreeAdapter.java:200` (1 call) |
| `SecretAdapter` | `venue/src/main/java/covia/adapter/SecretAdapter.java:41-42` (2 calls) |
| `UCANAdapter` | `venue/src/main/java/covia/adapter/UCANAdapter.java:42` (1 call) |
| `HTTPAdapter` | `venue/src/main/java/covia/adapter/HTTPAdapter.java:137-145` (7 calls; google-search demos move to `installExampleAsset`) |
| `TestAdapter` | `venue/src/main/java/covia/adapter/TestAdapter.java:91-109` (~13 calls; all move to `installTestAsset`) |

**Legacy aliases:**

- `covia:functions` → internally calls `covia:list v/ops`. Asset definition stays at `venue/src/main/resources/adapters/covia/functions.json` but the implementation in `CoviaAdapter.handleFunctions()` (line ~908) is rewired to delegate.
- `covia:describe(name)` → internally calls `covia:read v/ops/<name>`, with a small name→path translator for legacy `<adapter>:<sub>` form. `CoviaAdapter.handleDescribe()` (line ~932) is rewired.
- `covia:adapters` → internally calls `covia:list v/info/adapters` and reads each entry. `CoviaAdapter.handleAdapters()` (line ~949) is rewired.
- Each emits a deprecation log line on use.

**Other Phase 2 touch points:**

- `LLMAgentAdapter.java:246-248` — three deprecated ops in `DEFAULT_TOOL_OPS`. Removed only in Phase 3 to avoid breaking agents during the deprecation cycle.
- `engine.operations` registry is still populated but marked deprecated. Step 7 in `resolveAsset` emits a log line when hit.
- Orchestration assets and skills:
  - `skills/ap-demo/assets/ap-pipeline.json` — step `op` references like `"agent:request"` continue to work via the legacy registry but should be updated to `"v/ops/agent/request"` for consistency.
- Example assets in `venue/src/main/resources/asset-examples/google-search-*.json` are migrated from `installAsset` to `installExampleAsset` in `HTTPAdapter.installAssets()`.
- Documentation updates:
  - `venue/docs/AGENT_LOOP.md` lines 401-402 — discovery tools table.
  - `venue/docs/AGENT_TEMPLATES.md` — template tool lists.
  - `skills/venue-status/SKILL.md` — replace `covia:functions` / `covia:adapters` references with `covia:list v/info` / `covia:list v/ops`.
  - `skills/grid-test/SKILL.md` — same.

### Phase 3 — removal

**Code:**

- `engine.operations` registry deleted from `Engine.java`.
- `resolveAsset` step 7 (bare-name fallback) deleted.
- `CoviaAdapter.handleFunctions()`, `handleDescribe()`, `handleAdapters()` deleted, plus their dispatch cases at `CoviaAdapter.java:161-163`.
- `LLMAgentAdapter.java:246-248` — three ops removed from `DEFAULT_TOOL_OPS`.
- `AAdapter.installAsset(String resourcePath)` — single-arg form deleted (or moved to deprecated section).

**Asset definitions:**

- `venue/src/main/resources/adapters/covia/functions.json` deleted.
- `venue/src/main/resources/adapters/covia/describe.json` deleted.
- `venue/src/main/resources/adapters/covia/adapters.json` deleted.

**Agent templates:** update `tools` arrays in:

- `venue/src/main/resources/agent-templates/manager.json` (line 5)
- `venue/src/main/resources/agent-templates/worker.json` (line 5)
- `venue/src/main/resources/agent-templates/analyst.json` (line 5)
- `venue/src/main/resources/agent-templates/reader.json` (line 5)

**Behaviour after Phase 3:**

- The bare-string form (`grid:run "json:merge"`) no longer resolves; callers must use `v/ops/json/merge` or pin to their own `/o/`.
- Default agent toolset no longer includes the three removed ops; agents discover via `covia:list v/ops` and `covia:list v/info/adapters`.

Phase 1 is non-breaking. Phase 2 emits warnings but is non-breaking. Phase 3 is breaking and should be coordinated with downstream consumers (skills, demos, tests).

---

## 10. Sweep semantics

Because `/v/ops/` and `/v/info/adapters/` are re-materialised on every startup, **stale entries from removed adapters or renamed operations are automatically swept** as part of the materialisation step.

Implementation: before writing the new entries, the materialiser snapshots the current `/v/ops/` and `/v/info/adapters/` keys, computes the diff against the new set, and removes keys that are no longer present. This is cheap (one map-diff per startup) and keeps `/v/` consistent with the venue's actual installed adapters.

The sweep does **not** touch `/o/` — user pins are sacred, and a user can pin a stale reference if they want to. If the referenced asset still exists in `/a/` (which content addressing guarantees as long as it's not garbage-collected), the pin still resolves.

The sweep does **not** touch `/v/info/` leaf paths other than `/v/info/adapters/` — the other fields (`name`, `did`, `version`, `started`, `protocols`) are always overwritten on materialisation, so there's no stale-key concern.

---

## 11. Decisions

The following decisions, originally listed as open questions in v1, are resolved as of v2:

| # | Question | Decision |
|---|----------|----------|
| Q1 | Hardcoded `/v/` slot vs virtual namespace | **Virtual.** `/v/` is a `NamespaceResolver` prefix that maps to `<venue-DID>/w/global/`. No new top-level lattice region; reuses the existing user-workspace storage. |
| Q2 | Where does `/v/` live physically | **Under the venue user's `/w/global/`.** The venue is treated as a user (it already has a DID and a keypair); its `/w/global/` sub-tree is its public bulletin board. The `/v/` prefix is convenience syntax that defaults to the local venue. |
| Q3 | How `operation.adapter` maps to a `/v/ops/` path | **Adapters declare the catalog path explicitly** via the new `installAsset(catalogPath, resourcePath)` API. The venue does not parse the dispatch string for naming. |
| Q4 | Entry shape: descriptor or full metadata | **Full asset metadata, inline.** The catalog entry IS the asset; the existing `resolveUserOp` already handles this case. No descriptor wrapper, no `ref` field. |
| Q5 | How venue admin writes to `/v/` are authenticated | **Resolver-enforced.** Reads are unconditionally allowed; writes require the venue identity (`RequestContext.INTERNAL` or JWT signed by venue keypair). No UCAN dance. Multi-admin via UCAN delegation is a v2 enhancement. |
| Q6 | What happens to `covia:adapters` | **Deprecated alongside `covia:functions` and `covia:describe`.** All three legacy discovery ops become aliases for lattice operations against `/v/ops/` and `/v/info/`, then are removed in Phase 3. `/v/info/adapters/` is brought into v1 scope to support this. |

---

## 12. Out of scope (deferred)

- A new COG defining first-class agent definitions and templates as metadata categories.
- Search / indexing over operation catalogs (client-side filter is sufficient for v1).
- Cross-venue capability negotiation (different doc).
- The `/v/config/` sub-namespace (claimed but unused in v1).
- Admin UCAN delegation for `/v/` writes (v1 uses venue keypair only).
- Other potential `/v/info/` fields (`/v/info/peers`, `/v/info/host`, runtime metrics) — add as concrete consumers appear.
- Unifying budget / overflow semantics across `covia:read`, `covia:slice`, `covia:inspect` — tracked separately as [covia-ai/covia#78](https://github.com/covia-ai/covia/issues/78). Catalog browsing works with current semantics; this is a UX improvement.

---

## 13. Summary

The proposal adds **one new virtual namespace prefix (`/v/`)** and **removes three special-case discovery ops**, with everything else flowing from the existing lattice primitives:

- `/v/` is a virtual prefix mapping `/v/<rest>` → `<venue-DID>/w/global/<rest>`. Storage reuses the venue user's workspace; access is enforced by the resolver (read open, write venue-only).
- `/v/ops/` holds venue-provided operation defaults as inline asset metadata. Catalog entries ARE assets, not descriptors.
- `/v/info/` holds venue introspection: name, DID, version, started time, protocols, and per-adapter summaries.
- `/o/` continues to hold per-user operation pins, unchanged.
- Discovery is just `covia:list` / `covia:read` / `covia:slice` on either namespace. No new ops.
- Three legacy discovery ops (`covia:functions`, `covia:describe`, `covia:adapters`) are deprecated and ultimately removed.
- Federation is free: list `did:web:other.host:v/ops` to discover what a remote venue offers, with the same primitives.
- The bare-string form `grid:run "json:merge"` is deprecated in favour of `grid:run "v/ops/json/merge"`.

The change is **mostly removal**: less code, less special-casing, fewer parallel data paths. The lattice is the source of truth.
