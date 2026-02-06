# Covia Lattice Strategy

This document describes how Covia venues use Convex lattice technology for state management, persistence, replication, and federation.

## Overview

Every Covia venue maintains its state as a **lattice** — a CRDT (Conflict-free Replicated Data Type) structure with merge semantics that are commutative, associative, and idempotent. This means venue state can be merged from any source, in any order, and always converges to the same result.

Lattice technology gives venues:
- **Atomic local updates** — Engine operations read-modify-write via cursors
- **Persistence** — State can be serialised and restored at any time
- **Replication** — State can be shared between venues for resilience
- **Federation** — Independent venues can merge state without coordination
- **Integrity** — Cryptographic signing at sync boundaries ensures authenticity

## Global Lattice Structure

The global lattice assumes all venues exist within a single logical namespace, indexed by DID. Any participant holding a lattice value can merge it with any other, and the result is deterministic.

```
:grid → GridLattice
  :venues → Index<DID, SignedData<VenueState>>
    "did:key:z6Mk..." → Signed(VenueState)
      :assets  → Index<Hash, AssetRecord>       (union merge)
      :jobs    → Index<JobID, JobRecord>         (timestamp merge — newer wins)
      :users   → Index<UserID, UserRecord>       (timestamp merge — newer wins)
      :storage → Index<Hash, ABlob>              (CAS merge — content-addressed)
  :meta → Index<Hash, AString>                   (CAS merge — shared metadata)
```

### Key Design Decisions

**Venues indexed by DID.** Each venue has a `did:key:` identifier derived from its Ed25519 keypair. This is the venue's identity in the global lattice. DIDs are self-certifying — no registry needed.

**Signed at the venue boundary.** Each venue's entire state is wrapped in `SignedData<VenueState>`. This means anyone receiving a venue's lattice value can verify it was produced by the venue's keypair. Foreign values with invalid signatures are rejected on merge.

**Shared metadata pool.** The `:meta` index at grid level is content-addressed (`Hash → JSON string`). Since the same hash always maps to the same content, metadata can be safely deduplicated across venues. Asset records in `:assets` reference metadata by hash rather than embedding it.

> **TODO: Value IDs for metadata.** Currently metadata is hashed as a JSON string. We will likely refactor to use Convex Value IDs (hash of the data structure's root encoding) instead. This gives structural identity rather than string identity — two metadata values with equivalent content but different JSON serialisation would share the same Value ID.

> **TODO: Per-user structure.** Currently assets, jobs, etc. are flat indexes at the venue level. We will likely restructure to per-user lists (e.g. `:users → Index<UserID, {assets, jobs, ...}>`) so each user's state is grouped together. This enables more natural access control, selective sharing per user, and efficient user-scoped queries.

## Venue-Local State

### Cursor Model

Each venue engine holds a **lattice cursor** pointing to its own venue root within the global structure:

```java
// Engine holds cursor to its own VenueState within the global lattice
ACursor<AMap<Keyword, ACell>> venueCursor =
    rootLattice.descend(Covia.GRID, GridLattice.VENUES, myDID);
```

The cursor provides:
- **`get()`** — Read current state
- **`set(value)`** — Write new state
- **`updateAndGet(fn)`** — Atomic read-modify-write
- **`fork()`** — Create isolated working copy
- **`sync()`** — Merge working copy back to parent

For day-to-day operations, the venue works directly with its cursor. It does not interact with the global lattice structure unless syncing with other venues.

### Sub-Cursors

The venue cursor can be navigated to sub-paths for focused access:

```java
ACursor<Index<Hash, AVector<ACell>>> assets = venueCursor.path(VenueLattice.ASSETS);
ACursor<Index<AString, AMap<AString, ACell>>> jobs = venueCursor.path(VenueLattice.JOBS);
ACursor<Index<AString, AMap<AString, ACell>>> users = venueCursor.path(VenueLattice.USERS);
ACursor<Index<Hash, ABlob>> storage = venueCursor.path(VenueLattice.STORAGE);
```

Sub-cursors write through to the venue root atomically — updating a job via the jobs sub-cursor updates the venue root in a single operation.

## Merge Semantics Per Component

| Component | Lattice Type | Merge Rule | Rationale |
|-----------|-------------|------------|-----------|
| `:venues` | Index + per-venue merge | Recursive per DID | Each venue merges independently |
| Venue root | SignedLattice + VenueLattice | Verify signature, merge contents | Authenticity + convergence |
| `:assets` | Union (CAS) | All entries kept | Assets are immutable, content-addressed |
| `:jobs` | Timestamp (newer wins) | Higher `:updated` timestamp wins | Jobs progress forward through status lifecycle |
| `:users` | Timestamp (newer wins) | Higher `:updated` timestamp wins | User records evolve over time |
| `:storage` | CAS union | All entries kept | Same hash = same blob, always safe to merge |
| `:meta` | CAS union | All entries kept | Same hash = same JSON, shared across venues |

All merges satisfy CRDT properties:
- **Commutative:** `merge(a, b) == merge(b, a)`
- **Associative:** `merge(merge(a, b), c) == merge(a, merge(b, c))`
- **Idempotent:** `merge(a, a) == a`

## Update Logic

### Atomic Operations

Engine operations (invoke, asset registration, job status updates) need to perform multiple state changes that are visible atomically. The pattern:

1. **Fork** the venue cursor to create an isolated working copy
2. **Perform updates** against the fork (multiple sub-cursor writes)
3. **Sync** the fork back to the venue root

```java
// Example: submitting a job (creates job record + updates user record)
ALatticeCursor<AMap<Keyword, ACell>> tx = venueCursor.fork();

// Write job record
ACursor<Index<AString, AMap>> jobsCursor = tx.path(VenueLattice.JOBS);
jobsCursor.updateAndGet(jobs -> jobs.assoc(jobID, jobRecord));

// Write user record
ACursor<Index<AString, AMap>> usersCursor = tx.path(VenueLattice.USERS);
usersCursor.updateAndGet(users -> users.assoc(userDID, updatedUserRecord));

// Atomic commit — both writes become visible together
tx.sync();
```

Because sync uses lattice merge, concurrent operations on different parts of the state (different jobs, different assets) merge cleanly without conflict. Operations touching the same job record resolve via timestamp — the later update wins.

### Concurrent Engine Operations

Multiple engine operations may be in flight simultaneously (virtual threads handling different API requests). Each operation forks its own working copy:

```
Request A: fork → update job X → sync
Request B: fork → update job Y → sync   (no conflict — different keys)
Request C: fork → update job X → sync   (timestamp merge — later wins)
```

Fork/sync is always safe because lattice merge is a total function. There are no merge conflicts in the traditional sense — every merge produces a deterministic result.

### Within a Single Operation

For simple operations that touch a single record, forking may be unnecessary — a direct `updateAndGet` on the appropriate sub-cursor is sufficient:

```java
// Simple: update a single job status
jobsCursor.updateAndGet(jobs -> jobs.assoc(jobID, newStatus));
```

Fork/sync is valuable when an operation needs multiple coordinated writes that should appear atomically.

## Signing and Sync

### Principle: Sign Late, Verify Early

Signatures are applied at **sync boundaries** — when state leaves the venue's local process. This avoids the cost of re-signing on every internal operation.

```
[Internal operations]  →  unsigned cursor updates (fast)
           |
     [Sync boundary]   →  sign venue state with keypair
           |
[Persistence / Replication]  →  signed state goes to disk or network
```

The venue's Ed25519 keypair signs the venue state at the point of:
- **Persistence** — Writing state to durable storage (disk, database)
- **Replication** — Sending state to another venue or peer

Internal cursor operations (fork, update, sync-to-parent-cursor) remain unsigned for performance. The signing overhead is paid once per sync, not once per operation.

### Verification on Merge

When receiving foreign lattice state (from disk recovery, peer replication, or federation), the venue verifies signatures before merging:

```java
// SignedLattice.merge() handles this automatically:
// 1. checkForeign(otherValue) — verify Ed25519 signature
// 2. If valid, merge inner VenueState using VenueLattice rules
// 3. If merged result differs from both inputs, re-sign with own keypair
// 4. If signature invalid, reject (keep own value)
```

This means:
- A venue can only update its own entry in the global lattice (signed by its own key)
- Anyone can verify a venue's state came from that venue
- Tampered state is rejected on merge

### Re-signing After Merge

If a merge combines state from two sources and produces a new value, the merging venue re-signs the result. This is necessary because the merged state differs from what either party originally signed. The merging venue is asserting: "I verified both inputs and computed this merge."

## Sharing and Replication

### Selective Sharing

Venues are **not required** to share their lattice state. The default is private — a venue's state lives only in its own process and (optionally) its own persistent storage.

Venues may choose to share state for:
- **Resilience** — Replicate to backup venues so state survives failure
- **Federation** — Allow other venues to see assets, job history, user records
- **Public access** — Open venues that publish their full state for transparency

### Sharing Granularity

Because the lattice is structured hierarchically, venues can share at different levels:

| Level | What's Shared | Use Case |
|-------|--------------|----------|
| Nothing | Only DID document (`.well-known/did.json`) | Private venue, API-only access |
| `:assets` only | Published operations and artifacts | Discoverable venue, jobs stay private |
| `:assets` + `:meta` | Operations with full metadata | Federated operation discovery |
| Full venue state | Everything including jobs and users | Public venue or backup replica |

Selective sharing is implemented by constructing a partial lattice value containing only the components the venue wishes to export, signing it, and publishing or sending to peers.

### Replication Protocol

When two venues replicate, they exchange signed lattice values and merge:

```
Venue A                           Venue B
   |                                 |
   |  ── signed(venueA state) ──→    |
   |                                 |  merge into local lattice
   |    ←── signed(venueB state) ──  |
   |                                 |
   merge into local lattice          |
```

After replication, both venues have merged state for each other. Because merge is commutative and idempotent, it doesn't matter if messages are duplicated, reordered, or if only one direction succeeds. The system converges.

### Public vs Private Venues

**Public venues** expose their state openly. Any participant can fetch and merge the venue's lattice value. This is appropriate for shared infrastructure, open marketplaces, or transparent AI services.

**Private venues** keep state local. They participate in the grid via API calls (REST, MCP, A2A) but do not export lattice state. Other venues interact with them through the protocol layer, not through lattice replication.

**Hybrid venues** share some components (e.g. published assets) while keeping others private (e.g. job history, user records). The venue operator configures what to share.

## Persistence

### Local Persistence

A venue persists its state by writing the signed lattice value to durable storage. On restart, it loads the value, verifies the signature, and resumes from where it left off.

```
[Venue startup]
  1. Load signed state from storage
  2. Verify signature (own keypair)
  3. Initialise cursor from loaded state
  4. Resume operations

[Periodic / on-demand sync]
  1. Sign current venue state
  2. Write to durable storage
  3. Continue operations
```

The frequency of persistence is configurable. Options include:
- **On every sync** — Maximum durability, higher IO cost
- **Periodic** — Write every N seconds or N operations
- **On shutdown** — Minimum IO, risk of data loss on crash

### Recovery After Crash

If a venue crashes between persistence points, it loses operations since the last sync. This is acceptable because:
- The lattice always converges to a valid state (no partial writes or corruption)
- Clients polling for job status will see the job revert to its last persisted state
- Clients can resubmit operations if needed (idempotent where possible)
- Replicated venues can merge in state from peers to recover further

## Implementation Phases

### Phase 1: Lattice Foundation (Complete)
- `GridLattice`, `VenueLattice`, `CASLattice` types with full CRDT properties
- Cursor-based state access in Engine
- 35+ tests verifying merge properties

### Phase 2: Job Persistence
- Move jobs from `HashMap<AString, Job>` to VenueLattice `:jobs` index
- Jobs stored at cursor path `[:jobs <job-id>]` within venue state
- Status updates via timestamp merge (newer wins)
- Persist venue state to disk on sync

### Phase 3: Signing
- Wrap venue state in `SignedData` using venue's Ed25519 keypair
- Sign on persistence and replication boundaries
- Verify foreign signatures on merge

### Phase 4: Replication
- Protocol for exchanging signed lattice values between venues
- Selective sharing (choose which components to export)
- Resilience: backup venue receives and merges primary's state

### Phase 5: Federation
- Cross-venue lattice merge for asset discovery
- Shared `:meta` pool for deduplicated metadata
- Trust policies: which venues to accept state from

## Lattice Type Reference

### Convex Primitives Used

| Type | Purpose |
|------|---------|
| `ALattice<V>` | Abstract base — defines merge, zero, path, checkForeign |
| `KeyedLattice` | Maps different keywords to different child lattices |
| `SignedLattice<V>` | Wraps values in Ed25519 signatures |
| `OwnerLattice<V>` | Per-owner signed values with authorization |
| `IndexLattice<K,V>` | Sorted radix-tree map with recursive value merge |
| `CompareLattice<V>` | Custom comparator (e.g. timestamp — newer wins) |
| `LatticeContext` | Carries timestamp, signing key, owner verifier during merge |
| `ACursor<V>` | Mutable reference with atomic get/set/update/fork/sync |
| `ALatticeCursor<V>` | Cursor with lattice-aware fork/sync (merge on sync) |

### Covia Types

| Type | Extends | Purpose |
|------|---------|---------|
| `GridLattice` | `ALattice<AMap<Keyword, ACell>>` | Top-level grid state (venues + meta) |
| `VenueLattice` | `ALattice<AMap<Keyword, ACell>>` | Per-venue state (assets, jobs, users, storage) |
| `CASLattice<K,V>` | `ALattice<Index<K,V>>` | Content-addressed union merge |
| `Covia` | (constants) | Root lattice definition (`Covia.ROOT`) |
