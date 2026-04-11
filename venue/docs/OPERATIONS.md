# Operations: Naming, Discovery, and Resolution

This document defines how operations are named, discovered, and resolved on a Covia venue. It introduces the `/v/` virtual namespace for venue-provided defaults and replaces the global `engine.operations` registry with lattice-native discovery.

**Reference docs:**
- `venue/docs/GRID_LATTICE_DESIGN.md` — namespace model, `/o/` semantics, virtual-namespace resolvers, capability hierarchy.
- `venue/docs/UCAN.md` — capability tokens (referenced for write-side admin delegation; reads are resolver-enforced and need no UCAN).
- `covia-docs/docs/protocol/cogs/COG-005.md` — asset metadata format.
- `covia-docs/docs/protocol/cogs/COG-007.md` — operation specification.

**Related work:**
- [covia-ai/covia#78](https://github.com/covia-ai/covia/issues/78) — unify budget / overflow semantics across `covia:read` / `covia:slice` / `covia:inspect`. Improves catalog-browsing UX but not blocking.
- [covia-ai/covia#79](https://github.com/covia-ai/covia/issues/79) — toolsets (exploratory follow-up).
- [covia-ai/covia#80](https://github.com/covia-ai/covia/issues/80) — bridging external MCP servers into the catalog (exploratory follow-up).

---

## 1. Design rationale

Operations on a Covia venue must be:

- **Resolvable by name in the caller's own namespace**, not by adapter dispatch strings. The format of `operation.adapter` is the adapter's internal dispatch concern; it should not leak as the canonical user-facing name.
- **Discoverable through generic lattice primitives** (`covia:list`, `covia:read`, `covia:slice`), not through a special-case discovery API. Discovery becomes federated for free — listing a remote venue's catalog uses the same primitive as listing the local one.
- **Scoped per caller**, so a user's own operation pins coexist with venue-provided defaults without collision and without forcing every user to see the same flat list.
- **Stored in one place** — the lattice — without parallel data structures. The lattice is the source of truth.

The design therefore has two namespaces for operations:

- `/o/` — per-user operation pins (existing, unchanged from `GRID_LATTICE_DESIGN.md`)
- `/v/ops/` — venue-provided operation defaults (new, virtual prefix)

Discovery is `covia:list` on either. There is no global `engine.operations` registry, no `covia:functions` discovery op, no `operation.adapter` parsing for naming. Every operation reachable by name is reachable through lattice resolution; the catalog IS the lattice.

This document does not invent new metadata fields or adapter conventions. It uses the lattice primitives that already exist (`covia:list`, `covia:read`, namespace resolution, DID URLs) and adds one new virtual namespace prefix for venue-scoped globals.

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
- Changes to the existing `/o/` user namespace semantics (mutable, schema-enforced, open-read defaults). `/o/` stays as specified in GRID_LATTICE_DESIGN §4.
- A search index over operations. Listing + client-side filter is sufficient.
- Multi-admin authentication for `/v/` writes — the venue keypair is the sole admin authority. UCAN delegation to admin users is a future extension.

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

### Sub-namespaces

| Path | Purpose |
|------|---------|
| `/v/ops/` | Venue-provided operations catalog |
| `/v/info/` | Venue identity, version, adapters, protocols |
| `/v/test/` | Test-only operations and fixtures (separate from `/v/ops/`) |
| `/v/config/` | Public venue configuration (reserved; not yet populated) |

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

### Universal resolution principle

Any operation argument that accepts a lattice address — `path`, `from`, `id`, `source`, `operation` — accepts every resolvable form. Resolution is performed by a single canonical resolver (`Engine.resolvePath`) used by all read-side ops. The accepted input forms are:

| Form | Example | Meaning |
|------|---------|---------|
| Bare hex hash | `abc123def456...` | Content-addressed lookup; equivalent to `/a/<hash>` |
| Explicit `/a/<hash>` | `/a/abc123...` | CAS lookup |
| User namespace path | `o/merge`, `w/notes`, `g/alice/state` | Caller's own lattice region |
| Virtual prefix | `n/notes`, `t/draft`, `v/ops/json/merge` | Resolved by registered `NamespaceResolver` |
| DID URL | `did:web:venue.host:v/ops/json/merge`, `did:web:venue.host:u:alice/o/merge` | Cross-user / cross-venue, local or remote |

A read-side argument never needs to know "is this a hash or a path or a DID?" — the resolver figures it out. Capability checks happen during resolution: the caller needs read access to whatever resolves.

### Argument classification

Op arguments fall into two classes:

| Class | Acceptable forms | Used by |
|-------|------------------|---------|
| **Resolvable address** (read-side) | Any of the forms above | `covia:read path`, `covia:list path`, `covia:slice path`, `covia:inspect path`, `covia:copy from`, `asset:get id`, `asset:content id`, `asset:pin path`, `grid:run operation` |
| **Mutable target** (write-side) | Writable lattice paths only: `o/...`, `w/...`, `g/<own-agent>/...`, `s/...`, virtual writable namespaces (`n/`, `t/`) | `covia:write path`, `covia:append path`, `covia:delete path`, `covia:copy to` |

A write-side argument is constrained because you can't write to a content-addressed location (`/a/` is hash-determined), to a read-only namespace (`/v/` from non-venue callers), or to another user's namespace without UCAN delegation.

### The resolution chain

`Engine.resolvePath(ref, ctx)` walks the following steps in order:

1. **Bare hex hash** → fetch from CAS
2. **`/a/<hash>`** → fetch from CAS
3. **`/o/<name>`** → caller's own `/o/`
4. **`/v/<path>`** → venue globals via `VenueGlobalsResolver` (virtual prefix to `<venue-DID>/w/global/<path>`)
5. **DID URL** (`did:...`) → cross-user / cross-venue
6. **Workspace path** (`w/`, `g/`, `o/`, `j/`, etc. without leading slash) → caller's lattice cursor

The resolver returns the **literal value** at the resolved location. It does NOT chase references, follow indirections, or interpret the value in any way. It is a single-step navigation primitive.

`Engine.resolveAsset(ref, ctx)` is a thin composition: `Asset.fromMeta(resolvePath(ref, ctx))`. It returns an `Asset` if the resolved value is a map with an `operation` field, and `null` otherwise. Op-invocation paths (`grid:run`, agent loop) call `resolveAsset` and expect a non-null result; if the resolved value isn't asset-shaped, the op fails explicitly with "operation not found".

### No reference indirection

There is no `{ref: ...}` convention or any other "follow this pointer" data shape. The resolver does not interpret values; values are values. If a user wants `/o/<name>` to behave like a redirect to a venue op, they either:

- **Snapshot it** with `covia:copy from=v/ops/json/merge to=o/merge` — the copy is independent inline metadata.
- **Build a one-step orchestration** that delegates to the venue op:
  ```json
  {
    "name": "My merge proxy",
    "operation": {
      "adapter": "orchestrator",
      "steps": [{ "op": "v/ops/json/merge", "input": ["input"] }],
      "result": [0]
    }
  }
  ```
  This is a real operation the user composed. The orchestrator dispatches to the venue op at run time, so the user gets live tracking of upstream changes. No special resolver behaviour involved.

The "live alias" use case is therefore expressible as a regular operation, not as a resolver special case. This keeps the core resolution path simple and explicit.

### No fallback between namespaces

A reference like `o/merge` (with no leading `/v/`) resolves to the **caller's own** `/o/merge`. There is **no automatic fallback** from caller's `/o/` to `/v/ops/`. To call a venue default, the caller writes the explicit reference: `v/ops/json/merge`, or pins it in their own `/o/` as `{"ref":"v/ops/json/merge"}` (live alias) or via `asset:pin` (frozen by hash).

**Why no fallback?**

1. **Honest semantics.** `covia:list o` returns exactly what's in the caller's `/o/`, with no synthetic merging. A user who has pinned nothing sees an empty list — and they know to look at `/v/ops/` for venue defaults.
2. **No shadowing surprises.** A user's pin can never accidentally hide or be hidden by a venue default with the same name, because they live in different namespaces.
3. **Federation parity.** Resolving `did:web:other.host:v/ops/json/merge` works the same way as resolving `v/ops/json/merge` locally. The path structure is the same; only the DID prefix differs.

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

### Why inline metadata at the catalog path

1. **Single round trip for invocation.** `grid:run v/ops/json/merge` reads the metadata and dispatches. No follow-up fetch to retrieve schemas.
2. **Single round trip for discovery.** `covia:read v/ops/json/merge` returns everything an agent or tool needs to render a catalog entry. `covia:slice v/ops` paginates over full entries.
3. **Asset identity is recoverable.** No separate `ref` field needed — the canonical encoding of the inline map IS the asset's CAD3 ID by definition. Content addressing working correctly.
4. **Storage cost is essentially zero.** The metadata is structurally shared with the immutable copy in the venue-level asset CAS — same Merkle subtree, two parents in the lattice tree.

### Dual storage

Primitive metadata exists in **two lattice locations**:

| Where | Purpose | Mechanism |
|-------|---------|-----------|
| `venueState.assets()` — venue-level CAS | Content-addressed lookup by CAD3 hash | `engine.storeAsset(metaString, content)` writes here. A top-level asset cursor, separate from any user namespace. |
| `<venue-DID>/w/global/ops/<catalogPath>` | Named lookup via `/v/ops/<catalogPath>` | The materialiser writes the same metadata as inline content under the venue user's workspace, reachable via the `/v/` resolver. |

These are two distinct lattice locations that hold equivalent content. Lattice structural sharing means the underlying Merkle tree is shared — the storage cost of having both is a few extra map entries pointing into the same subtree, not a duplicated copy of the bytes. The `/v/ops/` entry's canonical encoding hashes to the asset's CAD3 ID, so a client that wants to fetch the canonical asset can do so via the existing CAS.

### User pin entry shape

A `/o/<name>` entry is a value the resolver returns literally. There is **one shape** for an entry to be callable as an operation: a map with an `operation` field. The resolver does not chase references, follow string aliases, or interpret values in any way other than navigating to them. `grid:run o/<name>` calls `Asset.fromMeta` on the literal value; if the value isn't a map with an `operation` field, the call fails explicitly.

```json
{
  "name": "JSON Merge",
  "description": "...",
  "operation": {
    "adapter": "json:merge",
    "input":  { "type": "object", "properties": { "values": { "type": "array" } } },
    "output": { "type": "object", "properties": { "result": { "type": "object" } } }
  }
}
```

Other map shapes, strings, vectors, scalars — all valid lattice data, but not callable as operations. They're readable via `covia:read` but `grid:run` will reject them.

### Creating user pins

Two ways for a user to populate `/o/<name>` with a callable operation:

| Intent | Op call | Stored at `o/<name>` |
|--------|---------|----------------------|
| Snapshot a venue op | `covia:copy from=v/ops/json/merge to=o/merge` | Full operation metadata, identical to the source at copy time |
| Custom inline op | `covia:write path=o/my-pipeline value={"name":"...","operation":{"adapter":"orchestrator","steps":[...]}}` | The map you wrote |

Both produce the same shape: a map with an `operation` field. Each call's intent is obvious from the call alone.

### "Live alias" via orchestration

A user who wants `/o/merge` to track a venue op live (rather than as a frozen snapshot) writes a one-step orchestration that delegates to the venue op:

```
covia:write path=o/merge value={
  "name": "My merge alias",
  "operation": {
    "adapter": "orchestrator",
    "steps": [
      { "op": "v/ops/json/merge", "input": ["input"] }
    ],
    "result": [0]
  }
}
```

This is a regular orchestration. When invoked, the orchestrator dispatches to whatever `v/ops/json/merge` currently is, so the user gets live tracking. There is no special "alias" concept in the resolver — aliasing is just a small orchestration pattern.

### Frozen versions via content addressing

A user who wants a specific version of a venue op (one that doesn't change if the venue updates the catalog) uses `covia:copy` and verifies the hash with `asset:pin` if needed:

```
covia:copy from=v/ops/json/merge to=o/merge
hash = asset:pin path=o/merge        # returns the CAD3 hash of the copied metadata
```

The hash is the version identifier. If the user wants to confirm they have a specific version, they compare the returned hash against an expected value.

---

## 6. Discovery

There is **no special-case discovery API**. The catalog is the lattice; you read it with the lattice ops.

### Lattice primitives

A small fixed set of primitives covers all interaction with operations and the catalog. Read-side ops (left column) accept any resolvable address per §4. Write-side ops (right column) require a writable mutable path.

#### `covia:` — lattice CRUD

| Op | Args | Reads from | Writes to | Returns | Purpose |
|----|------|-----------|-----------|---------|---------|
| `covia:read` | `path` | Any resolvable path | — | The value | Read a value at a path |
| `covia:list` | `path`, `limit?`, `offset?` | Any resolvable path | — | Structure summary | Describe the structure at a path |
| `covia:slice` | `path`, `limit?`, `offset?` | Any resolvable path | — | Paginated entries | Read a paginated chunk of a collection |
| `covia:inspect` | `path`, `budget?` | Any resolvable path | — | Rendered JSON5 | Budget-controlled rendering for unknown structures |
| `covia:write` | `path`, `value` | — | Mutable path | — | Store a literal value at a mutable path |
| `covia:copy` | `from`, `to` | Any resolvable path | Mutable path | — | Duplicate a value from one path to another (server-side) |
| `covia:append` | `path`, `value` | — | Mutable path (vector) | — | Append to a vector |
| `covia:delete` | `path` | — | Mutable path | — | Remove a value at a path |

#### `asset:` — content-addressed assets

| Op | Args | Reads from | Writes to | Returns | Purpose |
|----|------|-----------|-----------|---------|---------|
| `asset:pin` | `path` | Any resolvable path | `/a/` (caller's CAS) | `{ path, hash }` | Snapshot a value into the content-addressed namespace; returns the caller's DID URL and the CAD3 hash |
| `asset:store` | `metadata`, `content?` | — | `/a/` (caller's CAS) | `{ path, hash }` | Create a new asset from raw metadata + optional content blob |
| `asset:get` | `id` | Any resolvable path | — | The metadata | Read asset metadata (alias for `covia:read` with asset semantics) |
| `asset:content` | `id`, `maxSize?` | Any resolvable path | — | The content blob | Read the binary content payload of an asset |
| `asset:list` | `limit?`, `offset?` | Caller's `/a/` | — | Asset summaries | List assets in the caller's CAS |

#### `grid:` — operation invocation

| Op | Args | Returns | Purpose |
|----|------|---------|---------|
| `grid:run` | `operation`, `input`, `venue?` | Job result | Run an operation; wait for completion |
| `grid:invoke` | `operation`, `input` | Job status | Submit and return immediately |
| `grid:jobStatus` | `id` | Status | Poll a job by ID |
| `grid:jobResult` | `id` | Output | Wait for a job by ID and return its output |

Each op has a single purpose. None has a mode flag. Two-path operations (`covia:copy`) use `from` and `to` to make direction explicit; single-path operations use `path`. Every read-side argument accepts the universal resolution chain from §4.

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

# Snapshot a venue op into your own /o/
covia:copy from=v/ops/json/merge to=o/merge

# Get the content hash of what you just copied (version identifier)
{path, hash} = asset:pin path=o/merge

# What about a remote venue?
covia:list path=did:web:other.venue:v/ops

# Snapshot a remote venue op
covia:copy from=did:web:other.venue:v/ops/some-op to=o/their-op

# Snapshot a remote asset by hash into your own CAS
hash = asset:pin path=did:web:other.venue:v/ops/some-op

# What about another user on this venue?
covia:list path=did:web:this.venue:u:alice/o
```

All examples use the same primitives, parameterised by path. Pagination, federation, and cross-user discovery are uniform.

### From an agent's perspective

Agents have `covia_list`, `covia_read`, `covia_slice`, `covia_inspect` in their default toolset. **No discovery-specific tools needed.** The agent system prompt includes a one-line hint:

> Operations live in `/v/ops/` (venue defaults) and your own `/o/` (your pins). Adapter info lives in `/v/info/adapters/`. Use `covia:list` to discover, `covia:read` to read details.

---

## 7. Initialisation

`/v/ops/` and `/v/info/` are populated at startup, after each adapter has registered its primitives. The venue user record (the user record keyed by the venue's own DID) is the host for `/v/` data and is ensured to exist before adapter registration runs.

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

### `installAsset` API

Adapters explicitly declare their catalog path. The dispatch string `operation.adapter` is opaque; the venue does not parse it for naming.

```java
installAsset("json/merge", "/adapters/json/merge.json");
```

The catalog path is the first argument; the asset definition resource path is the second. Adapters choose any path structure they like, subject to the validation rules below.

### Validation rules

The venue validates the catalog path before writing. A path is valid if and only if:

- It is a non-empty `/`-separated string of segments
- Each segment matches `^[a-z][a-z0-9-]*$` (lowercase alphanumeric + hyphens, must start with a letter)
- No segment is `.` or `..`
- No two adapters install at the same `/v/ops/<path>` (duplicates rejected at startup with a clear error)

Invalid paths are skipped with a startup warning. The asset still exists in `/a/<hash>` and remains callable by hash, but does not appear in the catalog.

### Compositions and example assets

Example orchestrations and demo assets — assets whose `operation.adapter` is just a bare adapter name with no sub-op (e.g. `"orchestrator"` for an orchestration) — are **not primitives** and do not appear in `/v/ops/`. A separate method installs them:

```java
installExampleAsset("/asset-examples/google-search.json");
```

This stores the asset in the venue CAS (so it's still discoverable by hash) but does not write it to `/v/ops/`. Users can pin examples under their own `/o/` if they want named access.

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

**Admin UCAN delegation.** If an operator needs to grant write access to `/v/` to a specific admin user without sharing the venue keypair, the venue can issue a UCAN granting `{with: did:web:venue/v/, can: crud}` to that admin. The admin then signs requests with their own keypair and presents the UCAN as a proof. This is an extension beyond the base design, not part of the core model.

---

## 9. Sweep semantics

Because `/v/ops/` and `/v/info/adapters/` are re-materialised on every startup, **stale entries from removed adapters or renamed operations are automatically swept** as part of the materialisation step.

Implementation: before writing the new entries, the materialiser snapshots the current `/v/ops/` and `/v/info/adapters/` keys, computes the diff against the new set, and removes keys that are no longer present. This is cheap (one map-diff per startup) and keeps `/v/` consistent with the venue's actual installed adapters.

The sweep does **not** touch `/o/` — user pins are sacred, and a user can pin a stale reference if they want to. If the referenced asset still exists in `/a/` (which content addressing guarantees as long as it's not garbage-collected), the pin still resolves.

The sweep does **not** touch `/v/info/` leaf paths other than `/v/info/adapters/` — the other fields (`name`, `did`, `version`, `started`, `protocols`) are always overwritten on materialisation, so there's no stale-key concern.

---

## 10. Out of scope

- A new COG defining first-class agent definitions and templates as metadata categories.
- Search / indexing over operation catalogs (client-side filter is sufficient).
- Cross-venue capability negotiation (different doc).
- The `/v/config/` sub-namespace (reserved but unused).
- Admin UCAN delegation for `/v/` writes (venue keypair is the sole admin authority).
- Other potential `/v/info/` fields (`/v/info/peers`, `/v/info/host`, runtime metrics) — added as concrete consumers appear.
- Unifying budget / overflow semantics across `covia:read`, `covia:slice`, `covia:inspect` — tracked separately as [covia-ai/covia#78](https://github.com/covia-ai/covia/issues/78).
- Toolsets (named bundles of operations) — tracked separately as [covia-ai/covia#79](https://github.com/covia-ai/covia/issues/79).
- Bridging external MCP servers into the catalog — tracked separately as [covia-ai/covia#80](https://github.com/covia-ai/covia/issues/80).
- The rollout plan that takes the system from its current state to this design — see [OPERATIONS_PLAN.md](OPERATIONS_PLAN.md).

---

## 11. Summary

The design adds **one virtual namespace prefix (`/v/`)** and **removes one global registry (`engine.operations`)**, with everything else flowing from the existing lattice primitives:

- `/v/` is a virtual prefix mapping `/v/<rest>` → `<venue-DID>/w/global/<rest>`. Storage reuses the venue user's workspace; access is enforced by the resolver (read open, write venue-only).
- `/v/ops/` holds venue-provided operation defaults as inline asset metadata. Catalog entries ARE assets, not descriptors.
- `/v/info/` holds venue introspection: name, DID, version, started time, protocols, and per-adapter summaries.
- `/o/` continues to hold per-user operation pins, unchanged from `GRID_LATTICE_DESIGN.md`.
- Discovery is `covia:list` / `covia:read` / `covia:slice` on either namespace. No special-case discovery API.
- Federation: list `did:web:other.host:v/ops` to discover what a remote venue offers, with the same primitives.

The lattice is the source of truth. Less code, less special-casing, fewer parallel data paths.
