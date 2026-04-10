# Persistence and Lattice Component Layering

**Status:** Draft v4 — target state, cross-checked against convex-core/peer.
**Owner:** venue team
**Replaces:** the implicit `app.after("/api/*", ctx -> engine.syncState())` model in `VenueServer.java`.

**Reference docs (upstream):**
- `convex/convex-peer/docs/PERSISTENCE.md` — NodeServer/propagator design, sync flow, the explicit "scheduled sweep + safety net" pattern this draft implements (lines 251-258), and the known open upstream gap around synchronous commit (lines 652-690).
- `convex/convex-core/docs/LATTICE_CURSOR_DESIGN.md` — cursor hierarchy, fork semantics, the deliberate `ForkedLatticeCursor.sync() → parent.updateAndGet()` choice that creates the two-sync requirement (line 223).

---

## 1. Context

The current model triggers persistence with one line in `VenueServer.java:386`:

```java
app.after("/api/*", ctx -> engine.syncState());
```

This has structural problems on at least four axes:

1. **API layer knows about engine internals.** HTTP routes call into `engine.syncState()`. Pure transport layers should not.
2. **Per-route opt-in.** Any new endpoint (`/mcp`, `/a2a`, `/dlfs/*`, `/auth/*`, future) silently bypasses persistence until someone remembers to add an `after` hook.
3. **Non-HTTP mutations are not covered.** The agent run loop's `mergeRunResult`, `JobManager.persistJobRecord`, scheduled wakes, federated grid sub-jobs, and `Auth.putUser` all mutate lattice state outside an HTTP request and rely on the next request to flush.
4. **`venueState` is forked from the root cursor.** Writes go into a `ForkedLatticeCursor`'s `AtomicReference`. The shutdown hook reads from the root, which never sees the fork's writes until `venueState.sync()` runs. Even graceful close loses fork writes if `syncState` wasn't called between the last write and shutdown.

(`LatticePropagator.persistInterval` is **not** a relevant bound here, despite the name. Cross-check below; it's a boolean enable flag, not a time throttle, so it doesn't add any latency.)

**The persistence pipeline already exists.** `NodeServer` + `LatticePropagator` is a fully-formed lattice persistence layer:

- `NodeServer.onSync` (`NodeServer.java:138-143`) installed at construction iterates registered propagators and calls `triggerBroadcast(value)` whenever `cursor.sync()` runs.
- `LatticePropagator` owns a background drain thread, a coalescing `LatestUpdateQueue`, and the `Cells.announce` → `EtchStore.setRootData` write path (`LatticePropagator.java:107-148, 383-472`).
- `NodeServer.close()` registers a JVM shutdown hook (`NodeServer.java:230`) and runs `triggerAndClose` (L807-831) for graceful drain.
- `EtchStore.flush()` and `EtchStore.close()` are available for explicit fsync.

**This document does not invent a new persistence layer. It wires up the one that already exists.**

---

## 2. Goals and non-goals

**Goals**

- Adapters never touch persistence and never touch cursors directly.
- Lattice components own all mutation semantics and are the only path to writes.
- All mutations are durable on a bounded schedule regardless of trigger source (HTTP route, virtual thread, callback, scheduler).
- API layer is pure transport — no engine internals.
- Adding a new endpoint or a new mutation source requires zero new persistence wiring.
- **Explicit barriers (`engine.flush()`, `engine.close()`) are rock-solid.** Background propagation latency is secondary.

**Non-goals**

- Per-mutation `fsync` for arbitrary writes. Critical writes use explicit barriers; not the default.
- Cross-venue replication beyond what `LatticePropagator` already provides.
- Backwards compatibility with the existing `app.after("/api/*", …)` hook — it's deleted.
- Inventing a new `PersistenceCoordinator` class or a new propagator. The existing `LatticePropagator` already does this work; we just need to feed it consistently.
- A new public API on `convex-core`. This design works entirely with the existing `cursor.sync()` primitive plus an Engine-side scheduled sweep.

---

## 3. Four-layer contract

```
┌─────────────────────────────────────────────────────────────┐
│  Transport: HTTP routes (Javalin), MCP JSON-RPC, A2A, DLFS  │  pure I/O
│             WebDAV, OAuth callbacks, future protocols       │  no engine knowledge
└──────────────────────────┬──────────────────────────────────┘
                           │ dispatches to
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Operation dispatch: LocalVenue / JobManager.invokeOperation│  identity, caps,
│                      Engine.invoke                          │  job lifecycle
└──────────────────────────┬──────────────────────────────────┘
                           │ calls
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Adapters (CoviaAdapter, AgentAdapter, DLFSAdapter, …)      │  domain logic;
│  Receive RequestContext + meta + input; call component      │  no cursor access
│  methods; return result. Never touch cursors. Never sync.   │  no sync calls
└──────────────────────────┬──────────────────────────────────┘
                           │ calls named methods on
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Lattice components (AgentState, UserWorkspace, AssetStore, │  encapsulate
│  JobStore, SecretStore, Users, User, VenueState, …)         │  cursor.updateAndGet
│  Each owns a cursor + a vocabulary of named mutations.      │  via update() helper
│  May fork internally for transactional multi-write atomicity│  (component's own concern)
└──────────────────────────┬──────────────────────────────────┘
                           │ cursor.set / updateAndGet
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Cursor layer — UNCHANGED                                   │  no observer,
│  Components write into venueState fork (or other trunks).   │  no fast-path
│  No notifications, no observer infrastructure.              │  cost
└──────────────────────────┬──────────────────────────────────┘
                           │ background sweep + on-demand barriers
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Engine.persistence — NEW (~50 lines, no new class)         │  • daemon thread
│  • Daemon thread runs every 100ms: venueState.sync()        │    sweeps periodically
│  • engine.flush(): synchronous, blocks until disk           │  • flush is synchronous
│  • engine.close(): final flush, then nodeServer.close()     │  • close ordered
└──────────────────────────┬──────────────────────────────────┘
                           │ venueState.sync() → root cursor
                           │ root cursor.sync() → NodeServer.onSync
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  NodeServer + LatticePropagator (existing convex-peer)      │  unchanged.
│  Coalescing queue, Cells.announce, setRootData, broadcast.  │  Just needs
│  Shutdown hook + triggerAndClose.                           │  persistInterval
│                                                             │  config.
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  EtchStore (Convex)                                         │  persistent
└─────────────────────────────────────────────────────────────┘
```

**Principle**: components write to cursors and don't care about persistence. Engine sweeps the relevant trunks on a timer. Adapters that need an immediate barrier call `engine.flush()`. Forgetting to call sync is impossible at the component layer because the sweep covers it.

---

## 4. Lattice components

### 4.1 Inventory

**Existing, kept as-is:**

| Component | File | What it owns |
|---|---|---|
| `VenueState` | `venue/.../VenueState.java` | venue cell, fork root |
| `Users` / `User` | `venue/.../Users.java`, `User.java` | per-DID lattice subtrees, child component factories |
| `AgentState` | `venue/.../AgentState.java` | one agent's record (gold standard for the pattern) |
| `AssetStore` | `venue/.../AssetStore.java` | content-addressed asset index |
| `JobStore` | `venue/.../JobStore.java` | per-user job index |
| `SecretStore` | `venue/.../SecretStore.java` | encrypted secrets |
| `Auth` | `venue/.../Auth.java` | OAuth user records |
| `LatticeStorage` | `venue/.../storage/LatticeStorage.java` | lattice-backed blob CAS |

**New components introduced by this design (Phase 4 — deferred):**

| Component | Replaces | Mutations |
|---|---|---|
| `UserWorkspace` (returned by `User.workspace()`) | `CoviaAdapter` direct cursor writes for `w/` and `o/` namespaces | `write(path, value)`, `delete(path)`, `append(path, element)`, `slice(path, from, to)` |
| `UserDLFS` (returned by `User.dlfs()`) | `DLFSAdapter` direct `driveCursor.set(null)` and ad-hoc cursor navigation | `createDrive`, `deleteDrive`, `listDrives`; file ops delegate to `DLFSLocal` |
| `JobTemp` (held by a `Job`) | `CoviaAdapter` direct `TempNamespaceResolver.updateTemp` for `t/` namespace | `set(key, value)`, `update(key, fn)`, `delete(key)`. Lifetime = the job. |

### 4.2 Component contract

Every lattice component:

1. **Extends `ALatticeComponent<V>`** (existing base class, owns a cursor).
2. **Has a private `update(UnaryOperator<V>)` helper** that wraps `cursor.updateAndGet`. All mutations go through it. Reference: `AgentState.update` at `AgentState.java:85`.
3. **Exposes named, intent-revealing mutation methods.** No `set(path, value)` escape hatch.
4. **Never calls `engine.syncState()` or any persistence API.** Engine handles persistence at a higher level.
5. **May expose CAS / atomic helpers** for concurrent access. Reference: `AgentState.tryStartRunning` at `AgentState.java:268`.
6. **May fork its own cursor for transactional multi-write atomicity** when a single mutation needs several lattice writes that must succeed-or-roll-back together. The component then syncs the fork on success. **Discard on failure is implicit** — there is no explicit `fork.discard()` or `Closeable` API; dropping the fork reference and letting GC reclaim it is the rollback mechanism. This is a component-internal correctness tool, not a persistence mechanism.

### 4.3 Adapter contract

Every adapter:

1. **Receives** `RequestContext`, resolved metadata, input.
2. **Resolves the calling user** via `engine.getVenueState().users().get(ctx.getCallerDID())`.
3. **Calls component methods only.** No `cursor.set`, no `cursor.updateAndGet`, no `cursor.path(…).set(…)`, no `engine.syncState()`.
4. **Returns a result.** That's it.

Compliance audit:

| Adapter | Status | Notes |
|---|---|---|
| `AgentAdapter` | ✅ compliant | Reference implementation. |
| `AssetAdapter` | ✅ compliant | Goes through `user.assets().store(…)`. |
| `SecretAdapter` | ✅ compliant | Goes through `user.secrets().store(…)`. |
| `UCANAdapter` | ✅ compliant | No lattice writes. |
| `VaultAdapter` | ✅ compliant | Pure delegation to DLFS adapter. |
| `CoviaAdapter` (write/delete/append) | ❌ direct cursor writes — refactor in Phase 4 | |
| `DLFSAdapter` (handleDeleteDrive, ensureUserKeyPair) | ❌ direct cursor write + explicit `engine.syncState()` — refactor in Phase 4 | |

---

## 5. Persistence — scheduled sweep + on-demand barriers

**No new class.** Three pieces, all in `Engine`. No convex-core change.

### 5.0 Why two sync calls — the fork model

The sweep below calls `venueState.sync()` followed by `lattice.sync()`. These two
calls do **different things at different layers** and both are necessary. The
naming is mildly misleading because "venueState" is on the lattice already, but
it isn't *connected* to the lattice in the way you'd assume.

`engine.lattice` (`Engine.java:133`) is the root cursor — the cursor that
NodeServer's propagator is wired to. `engine.venueState` is constructed in
`initialiseFromCursor` (`Engine.java:210-218`) as:

```java
VenueState connected = VenueState.fromRoot(lattice, getAccountKey());
connected.initialise(getDIDString());           // signs DID write immediately
this.venueState = connected.fork();              // ← FORK
```

So there are three layers:

```
lattice            (root cursor; propagator watches this)
   ↓ path-derived view via VenueState.fromRoot
connected          (signed cursor boundary; signs every write)
   ↓ .fork()
venueState         (in-memory fork; adapters/components write here)
```

Per the lattice contract, **a fork is independent of its parent** — writes to
the fork sit in the fork's own `AtomicReference` and **do not appear at the
parent until `fork.sync()` is explicitly called**.

The two sync calls correspond to the two layer transitions:

| Call | What it does | What it does NOT do |
|---|---|---|
| `venueState.sync()` | `ForkedLatticeCursor.sync()` at `ForkedLatticeCursor.java:54` deliberately calls **`parent.updateAndGet(...)` (NOT `parent.sync()`)**. This walks the fork's accumulated writes through the chain via `updateAndGet`, crossing the SignedCursor boundary which produces **one Ed25519 signature for the whole batch**, all the way down to the root's `AtomicReference`. After this returns, writes are physically present at the root. | Does NOT call `parent.sync()`. The `RootLatticeCursor.onSync` callback is **not** fired. The propagator hears nothing. |
| `lattice.sync()` | Fires `RootLatticeCursor.onSync` (`NodeServer.java:138-143`), which calls `propagator.triggerBroadcast(value)`. The propagator drains via its background thread, calls `Cells.announce`, calls `EtchStore.setRootData`. | Does NOT write anything new to the cursor — operates on whatever is currently at the root. |

Skip the first → fork writes never reach the root → propagator broadcasts stale state.
Skip the second → root has the writes but the propagator never gets the trigger → no disk write.

The `ForkedLatticeCursor` `parent.updateAndGet` choice is intentional and documented at `convex-core/docs/LATTICE_CURSOR_DESIGN.md:223`. It's there so you can sync the fork mid-life without re-triggering persistence for every upstream component along the chain. The cost is the two-call requirement at the venue layer.

**Why the fork exists.** The SignedCursor boundary signs every write that
crosses it. Without the fork, every component mutation would do an immediate
Ed25519 signature (~tens of µs). With the fork, all writes within the sync
window batch into one signature. Under a 100 ms sweep, "the sync window" is up
to 100 ms of accumulated component writes. **One signature per sweep cycle
instead of N per cursor write.** For a busy venue this is a meaningful saving;
the fork stays for this reason.

**Could we drop the fork?** Yes — `venueState` could be the connected cursor
directly, and we'd only need `lattice.sync()`. The cost is one signature per
individual cursor write instead of one per sweep cycle. Defensible for a
low-throughput venue, a measurable regression at scale. Out of scope for this
design.

### 5.1 Background sweep

A daemon thread runs a periodic sync. Sweeping means: merge the venueState fork
into the root, then trigger the propagator. **There is exactly one trunk
(`venueState`) that needs explicit fork-syncing.** DLFS writes go directly to
the root cursor (path-derived view from `engine.getRootCursor()` per
`DLFSAdapter.java:154-166`), so the existing `lattice.sync()` covers them.

```java
// in covia.venue.Engine

private static final long SWEEP_INTERVAL_MS = 100;
private final ScheduledExecutorService persistenceSweep =
    Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "covia-persistence-sweep");
        t.setDaemon(true);
        return t;
    });

private void startPersistenceSweep() {
    persistenceSweep.scheduleWithFixedDelay(
        this::sweepTrunks,
        SWEEP_INTERVAL_MS,
        SWEEP_INTERVAL_MS,
        TimeUnit.MILLISECONDS);
}

// Merge venueState fork into the root, then trigger the propagator.
// Sync is a no-op if nothing changed, so this is cheap on idle venues.
// See §5.0 for why both calls are needed.
private void sweep() {
    try {
        venueState.sync();   // fork → root (signs the batch)
        lattice.sync();      // fires NodeServer.onSync → propagator
    } catch (Exception e) {
        log.warn("Persistence sweep failed", e);
        // next sweep retries
    }
}
```

If a future feature introduces another independent fork (e.g. a per-job
transactional sub-fork that needs to outlive the operation), `sweep()` is the
single place where it gets added. Today there is one trunk and one place to
audit. DLFS is **not** a trunk — it writes through path views from the root
cursor, so `lattice.sync()` already captures DLFS writes without an extra call.

**Cost when idle**: 10 sync calls/sec, each a no-op when there's nothing to merge. Effectively zero.

**Cost when busy**: 10 sync calls/sec, each merging the accumulated writes since the last sweep. Coalesced naturally by the sweep interval.

**Latency bound**: a write made just after a sweep waits up to 100 ms before the next sweep, then propagates through the existing `LatticePropagator` pipeline. The propagator processes the value within scheduler latency (microseconds to a few ms) — there is **no additional 100ms or 30s bound** despite the `persistInterval` field name (see §5.5). Worst-case crash-loss window ≈ sweep interval (100 ms) + EtchStore write latency.

### 5.2 Synchronous barrier — `engine.flush()`

For mutations that need to be durable before continuing (job completion, audit records, secret rotation, agent TERMINATED, OAuth login), there's an opt-in barrier:

```java
// in covia.venue.Engine

/**
 * Synchronously syncs venueState into the root and persists the current
 * value through the propagator on the caller's thread. Returns when the
 * current write set is on disk.
 *
 * Bypasses the propagator's background queue entirely by calling
 * NodeServer.persistSnapshot, which does Cells.announce + setRootData
 * synchronously (NodeServer.java:788-792). This is the correct primitive
 * for "make this write durable before I return" — there is currently no
 * "wait for the background drain queue to flush" API on a running
 * propagator (the upstream PERSISTENCE.md flags this gap, lines 652-690).
 *
 * Use sparingly — most writes don't need this. Default eventual durability
 * via the background sweep is fine for in-flight job state, conversation
 * history, etc.
 */
public void flush() {
    venueState.sync();                            // fork → root
    nodeServer.persistSnapshot(lattice.get());    // synchronous Cells.announce + setRootData
    // Optional: store.flush() if EtchStore-level fsync is wanted
}
```

Adapters call `engine.flush()` after the rare mutation that needs strong durability. Default mutations don't call it — they get the 100 ms eventual path.

### 5.3 Engine.close ordering

`Engine.close()` must run a final synchronous flush **before** `nodeServer.close()` so the venueState fork's writes reach the root before the existing graceful drain reads from it:

```java
public void close() {
    persistenceSweep.shutdown();
    flush();                // synchronous final sweep + propagator drain
    nodeServer.close();     // existing triggerAndClose
    store.close();
}
```

This fixes the existing bug where `nodeServer.close()` reads from the root cursor before venueState has been merged in.

### 5.4 Delete the after-hook

`VenueServer.java:386`: delete the line.

```diff
- app.after("/api/*", ctx -> engine.syncState());
```

And delete the four `app.after("/mcp", ...)` lines I added earlier (those are the wrong abstraction even within the old model).

`engine.syncState()` becomes package-private — only callable from `Engine` itself, the propagator pipeline, and tests. Public API is `engine.flush()` for the explicit barrier case.

### 5.5 LatticePropagator.persistInterval — known upstream wart, no action

The convex-peer field `LatticePropagator.persistInterval` looks like a throttle but is actually a **boolean enable flag**. At `LatticePropagator.java:441-443`:

```java
if (persistInterval > 0) {
    store.setRootData(value);
}
```

There is no time comparison. Every `processValue` call (i.e. whenever the propagator's background thread dequeues a value from `triggerQueue`) calls `setRootData` if the flag is positive. The 30_000ms default is a misleading magic number — the field should be a boolean named `persistEnabled`. The setter (`setPersistInterval(long)` at L208-210) and config key (`NodeConfig.getPersistInterval()`) exist but `NodeServer.launch()` doesn't actually thread the value through (it only reads it as `>0` to disable when persistence is off).

**Implications for this design:**
- Lowering the value does nothing functionally — the rate at which `setRootData` is called equals the rate at which `cursor.sync()` triggers the propagator (minus `LatestUpdateQueue` coalescing).
- A 100 ms sweep produces ≤ 10 `setRootData` calls/sec already, regardless of `persistInterval`.
- **No convex-peer change is needed for Phase 1.** The stock 30_000 default works correctly because the field is a no-op gate.

This is worth filing as a separate convex-peer cleanup issue (rename to `persistEnabled`, fix `NodeServer.launch()` to thread the config), but it's **not a Phase 1 blocker and not on this design's critical path**.

### 5.6 Why this works

| Problem from §1 | How this design fixes it |
|---|---|
| API layer knows about engine internals | `app.after` hooks deleted; HTTP layer no longer references `engine.syncState()`. |
| Per-route opt-in | New endpoints don't need persistence wiring at all. The sweep covers any cursor write. |
| Non-HTTP mutations | Same: any write to a tracked trunk is picked up by the sweep regardless of who made it. |
| Forked cursor missed by shutdown | `Engine.close()` runs `flush()` before `nodeServer.close()`. |
| `persistInterval = 30 000` | Non-issue — it's a boolean gate, not a throttle (see §5.5). Disk writes happen on every sync regardless. |

### 5.7 Future option — observable cursor

If we ever want **proportional cost** (zero work on idle venues, bounded latency on bursts) instead of the periodic sweep, the migration path is to add a `NotifyingRootLatticeCursor` subclass to convex-core (NOT a method on `ALatticeCursor` itself). It would extend `RootLatticeCursor` and add an `onWrite(Runnable)` callback that fires after successful writes. Engine would construct its trunk cursors as `NotifyingRootLatticeCursor` instances and switch from the timer to the observer.

This keeps the lattice base classes untouched (no fast-path overhead for non-notifying users), confines the new behaviour to a specific cursor type, and makes the migration a swap of one class for another in `Engine` setup.

We don't need this today. Noting it so we know the door is open if scheduled-sweep ever proves inadequate.

---

## 6. Migration plan

Three phases (was four), each independently mergeable, each with tests.

### Phase 1: Persistence sweep + close ordering + barrier API

**Pure covia-side change. No convex-peer change required** (the cross-check showed `persistInterval` is a no-op gate and `NodeServer.persistSnapshot` is the correct synchronous primitive — both already exist).

- **Engine**: add `persistenceSweep` daemon thread, `sweep()`, `flush()`, fixed `close()` ordering.
- `Engine.flush()` calls `nodeServer.persistSnapshot(lattice.get())` for synchronous durability.
- `engine.syncState()` becomes package-private; `engine.flush()` is the new public API.
- Tests: sweep runs, sweep is cheap on idle, sweep merges writes, `flush()` blocks until disk, `close()` drains before shutdown.
- **Coexists with the old after-hook for now** — both fire, the system over-syncs but is correct.

### Phase 2: Delete the after-hook

- Remove `app.after("/api/*", …)` from `VenueServer`.
- Remove the explicit `engine.syncState()` from `DLFSAdapter.ensureUserKeyPair`.
- Tests: persistence test suite (REST, MCP, DLFS, agent run loop, OAuth callback) all pass under abrupt-kill scenarios.

### Phase 3: Adapter refactor (deferred — independent of persistence correctness)

- Add `UserWorkspace`, `UserDLFS`, `JobTemp` components.
- Refactor `CoviaAdapter.handleWrite/Delete/Append` to call `user.workspace().write(…)` etc.
- Refactor `DLFSAdapter` mutation paths to call `user.dlfs().…`.
- Tests: each new component has full unit tests; refactored adapters have integration tests.
- **Does not affect persistence correctness** — the sweep covers any cursor write regardless of which adapter wrote it. Phase 3 ships when convenient.

Phases 1–2 are the **functional fix**. Phase 3 is the **structural cleanup**.

---

## 7. Test strategy

For each persistence path, one regression test that:
1. Starts a venue with a temp etch store and a fixed seed.
2. Writes via the path under test.
3. **Hard-kills** the venue (`Runtime.halt(1)`, NOT `server.close()`).
4. Restarts a new venue against the same etch.
5. Reads back via a different path. Asserts the write survived.

Paths covered:

| Path | Status today |
|---|---|
| REST `/api/v1/invoke` covia:write | ✅ works (because of after-hook) — must continue working after Phase 2 |
| MCP `/mcp` tools/call covia:write | ❌ broken — must pass after Phase 2 |
| DLFS WebDAV `/dlfs/<drive>/<path>` PUT | ❌ likely broken — must pass after Phase 2 |
| Agent run loop `mergeRunResult` (background virtual thread) | ❌ broken — must pass after Phase 2 |
| `JobManager.persistJobRecord` from Job completion callback | ❌ broken — must pass after Phase 2 |
| OAuth `Auth.putUser` from `/auth/callback` | ❌ likely broken — must pass after Phase 2 |
| `engine.flush()` synchronous barrier | new in Phase 1 |

Plus:
- **Race test**: 100 concurrent writes from different sources (REST + MCP + run loop), abrupt kill, restart, verify all 100 survive.
- **Latency test**: measure end-to-end write→disk latency under default config. Target ≤ 250 ms (100 ms sweep + 100 ms propagator persistInterval + Etch overhead).
- **Idle cost test**: venue with no writes for 10 seconds — confirm sweep is performing no-op syncs (CPU stays effectively zero).
- **Adapter compliance test**: a static check that asserts no adapter file contains `cursor.set` / `cursor.updateAndGet` / `engine.syncState`.

---

## 8. Decisions made

| Decision | Choice | Reason |
|---|---|---|
| Where notification originates | **Periodic sweep on Engine, no notification at the cursor level** | Zero fast-path cost, no convex-core API change, equivalent SLA. |
| Future option for proportional cost | **`NotifyingRootLatticeCursor` subclass** if ever needed | Keeps lattice base classes clean. Migration is a class-swap in `Engine`. Not needed today. |
| New persistence class? | **No.** ~50 lines on `Engine`. | NodeServer + LatticePropagator already exist. Don't reinvent. |
| Sweep interval default | **100 ms** | 10 sync calls/sec on idle venues, indistinguishable from zero. Worst-case latency on bursts ≤ 100 ms. |
| `persistInterval` (LatticePropagator) | **No change.** Stock 30 000 default is fine. | Cross-check showed it's a boolean gate, not a throttle — lowering it does nothing. See §5.5. |
| Synchronous durability | **Opt-in `engine.flush()`** | Most writes don't need fsync. Critical writes call `flush()` explicitly. |
| `venueState.fork()` model | **Keep the fork.** Engine.close drains it before nodeServer.close reads root. | Single-sign optimization is real. The bug it caused is fixed by close ordering. |
| Component-internal forks | **Allowed and encouraged** for transactional multi-write atomicity. Component is responsible for its own sync. | Forks are the right tool when a component needs N writes to succeed-or-fail together. Sync of the component's fork happens inside the component, not in Engine. |
| `engine.syncState()` visibility | **Package-private.** Public API is `engine.flush()`. |

---

## 9. Resolved questions

All resolved through review:

1. **Trunk cursor enumeration.** ✅ Resolved: only `venueState` needs explicit fork-syncing. DLFS uses path views from `engine.getRootCursor()` (`DLFSAdapter.java:154-166`) which write directly to the root cursor's storage; `lattice.sync()` already captures them. `sweep()` is exactly two calls, not a registry.

2. **`LatticePropagator.persistInterval` default for venues.** ✅ Resolved: no change. Cross-check found the field is a boolean gate, not a time throttle — lowering it does nothing functional. Filed as a separate convex-peer cleanup task (rename + actually thread NodeConfig through), not a Phase 1 dependency. See §5.5.

3. **Phase 3 (CoviaAdapter / DLFSAdapter refactor) timing.** ✅ Resolved: deferred. Persistence work first, adapter refactor as a separate later PR.

Doc is sign-off ready. Phase 1 implementation begins next.
