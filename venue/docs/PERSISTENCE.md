# Persistence and Lattice Component Layering

**Status:** Draft v5 — hosted component model implemented; remaining migration
phases are tracked below.
**Owner:** venue team
**Replaces:** the implicit `app.after("/api/*", ctx -> engine.syncState())` model in `VenueServer.java`.

**Reference docs (upstream):**
- `convex/convex-peer/docs/PERSISTENCE.md` — NodeServer/propagator design, sync flow, the explicit "scheduled sweep + safety net" pattern this draft implements (lines 251-258), and the known open upstream gap around synchronous commit (lines 652-690).
- `convex/convex-core/docs/LATTICE_CURSOR_DESIGN.md` — cursor hierarchy and the fork semantics used by bounded component transactions.
- `convex/convex-core/docs/LATTICE_APPLICATIONS.md` — `RootComponent`,
  `ALatticeApplication`, component-parent policy, publication and durability.

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
4. **A long-lived `venueState` fork created a second working copy.** Root readers could observe stale state until `venueState.sync()` ran, and persistence needed an otherwise-unnecessary two-stage sync. Engine now keeps its `VenueState` connected; forks are reserved for bounded transactions.

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
│  SecretStore, Users, User, VenueState, …)                   │  cursor.updateAndGet
│  Each owns a cursor + a vocabulary of named mutations.      │  via update() helper
│  May fork internally for transactional multi-write atomicity│  (component's own concern)
└──────────────────────────┬──────────────────────────────────┘
                           │ cursor.set / updateAndGet
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Cursor layer — connected Engine state                      │  atomic root
│  Successful component writes update the authoritative root. │  updates
│  Explicit forks exist only for bounded transactions.        │
└──────────────────────────┬──────────────────────────────────┘
                           │ background sweep + on-demand barriers
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Engine.persistence (~50 lines, no new class)               │  • daemon thread
│  • Daemon publishes the connected root every 100ms          │    sweeps periodically
│  • engine.flush(): synchronous, blocks until disk           │  • flush is synchronous
│  • engine.close(): final flush, then nodeServer.close()     │  • close ordered
└──────────────────────────┬──────────────────────────────────┘
                           │ application.sync() → NodeServer.onSync
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

**Principle**: components update connected state and don't care about persistence. Engine publishes the root on a timer. Adapters that need an immediate durability barrier call `engine.flush()`. A component uses a fork only when a bounded multi-write transaction requires one.

---

## 4. Lattice components

### 4.1 Inventory

**Hosted component tree:**

| Component | File | What it owns |
|---|---|---|
| `CoviaApplication` | `venue/.../CoviaApplication.java` | complete hosted root; publication and store policy |
| `VenueState` | `venue/.../VenueState.java` | connected venue subtree; bounded transaction fork factory |
| `Users` / `User` | `venue/.../Users.java`, `User.java` | per-DID lattice subtrees, child component factories |
| `AgentState` | `venue/.../AgentState.java` | one agent's record (gold standard for the pattern) |
| `AssetStore` | `venue/.../AssetStore.java` | content-addressed asset index |
| `SecretStore` | `venue/.../SecretStore.java` | encrypted secrets |
| `Auth` | `venue/.../Auth.java` | OAuth user records |
| `LatticeStorage` | `venue/.../storage/LatticeStorage.java` | lattice-backed blob CAS |

**New components introduced by this design (Phase 4 — deferred):**

| Component | Replaces | Mutations |
|---|---|---|
| `UserWorkspace` (returned by `User.workspace()`) | `CoviaAdapter` direct cursor writes for `w/` and `o/` namespaces | `write(path, value)`, `delete(path)`, `append(path, element)`, `slice(path, from, to)` |
| `UserDLFS` (returned by `User.dlfs()`) | `DLFSAdapter` direct `driveCursor.set(null)` and ad-hoc cursor navigation | `createDrive`, `deleteDrive`, `listDrives`; file ops delegate to `DLFSLocal` |

`JobTemp` is no longer needed: the `t/` namespace resolves to the Job record's
`temp` slot as an ordinary cursor path, so it takes the same deep read/write as
every other namespace.

### 4.2 Component contract

Every lattice component:

1. **Extends `ALatticeComponent<V>`** (existing base class, owns a cursor).
2. **Is constructed with its containing component** using
   `super(parent, cursor)`. Child factories pass `this`; a fork keeps the same
   component parent while its cursor continues to sync to the cursor it forked.
3. **Has a private `update(UnaryOperator<V>)` helper** that wraps `cursor.updateAndGet`. All mutations go through it. Reference: `AgentState.update`.
4. **Exposes named, intent-revealing mutation methods.** No `set(path, value)` escape hatch.
5. **Never calls `engine.syncState()` or any persistence API.** Engine handles persistence at a higher level.
6. **May expose CAS / atomic helpers** for concurrent access.
7. **May fork its own cursor for transactional multi-write atomicity** when a single mutation needs several lattice writes that must succeed-or-roll-back together. The component then syncs the fork on success. **Discard on failure is implicit** — there is no explicit `fork.discard()` or `Closeable` API; dropping the fork reference and letting GC reclaim it is the rollback mechanism. This is a component-internal correctness tool, not a persistence mechanism.

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

### 5.0 Connected state; forks are transactions

`engine.lattice` is the application root cursor and `engine.venueState` is a
connected, path-derived component at the venue owner boundary:

```java
this.venueState = application.venue(getAccountKey());
venueState.initialise(getDIDString());
```

A successful component mutation crosses the signed owner boundary and updates
the authoritative in-memory root immediately. Publication and durability are
separate operations:

| Call | Meaning |
|---|---|
| component mutation | atomically updates connected venue state and signs the resulting owner value |
| `application.sync()` | publishes the current hosted root through the host policy |
| `application.flush()` | invokes the store's physical durability barrier |

Local mutations do not compete through the LWW merge. Their atomic root update
order decides the current value, including writes sharing a millisecond stamp.
The wall-clock timestamp is only evidence for external snapshot reconciliation;
it is not a logical local sequence number.

This removes the second long-lived working copy and the two-stage
`venueState.sync()` → `application.sync()` protocol. `Engine.syncState()` is
therefore only a publication hint; it is not a state commit.

`VenueState.fork()` remains available for an explicit bounded transaction, but
the narrowest component fork is preferred. A component performs all transaction
writes against the fork, commits on success, and drops the fork on failure.
How it commits depends on where the fork sits: `sync()` merges only at a level
whose lattice defines a merge, and below the venue's whole-value LWW node the
JSON regions define none. A narrow fork there is a staging and validation copy;
the component commits by replaying its staged writes in one atomic
`updateAndGet` on the connected cursor, which preserves concurrent unrelated
writes. The bootstrap materializer demonstrates this for the venue-owned
`w/global` workspace, so catalog writes become visible together without
copying unrelated venue state. Request lifetime, persistence cadence, and
signature batching alone are not transaction boundaries.

### 5.1 Background sweep

A daemon publishes the connected root every 100 ms. Every 10 seconds it also
invokes the store durability barrier, bounding the default unclean-shutdown
loss window independently of the source of a mutation. Ephemeral applications
and raw-cursor Engines with the no-op persistence handler do not start the
daemon.

```java
// in covia.venue.Engine

private static final long SWEEP_INTERVAL_MS = 100;
private void startPersistenceSweep() {
    persistenceSweep.scheduleWithFixedDelay(
        this::sweep,
        SWEEP_INTERVAL_MS,
        SWEEP_INTERVAL_MS,
        TimeUnit.MILLISECONDS);
}

private void sweep() {
    try {
        publishApplicationRoot();
        if (System.currentTimeMillis() - lastFlushMillis >= FLUSH_INTERVAL_MS) {
            flushStore();
            lastFlushMillis = System.currentTimeMillis();
        }
    } catch (Exception e) {
        log.warn("Persistence sweep failed", e);
    }
}
```

**Cost when idle**: 10 publication hints/sec plus one durability barrier every
10 seconds. No venue state is copied or merged.

**Latency bounds**: root visibility is immediate, host publication waits at
most 100 ms by default, and physical durability waits at most 10 seconds unless
the caller requests an explicit `flush()`.

### 5.2 Synchronous barrier — `engine.flush()`

For mutations that need to be durable before continuing (job completion, audit records, secret rotation, agent TERMINATED, OAuth login), there's an opt-in barrier:

```java
// in covia.venue.Engine

/**
 * Publishes the complete hosted root, then asks the host store for its
 * physical durability barrier.
 *
 * Use sparingly — most writes don't need this. Default eventual durability
 * via the background sweep is fine for in-flight job state, conversation
 * history, etc.
 */
public void flush() {
    application.sync();     // root publication policy
    application.flush();    // store durability barrier
}
```

Adapters call `engine.flush()` after the rare mutation that needs strong durability. Default mutations don't call it — they get the 100 ms eventual path.

### 5.3 Engine.close ordering

`Engine.close()` must run a final synchronous flush **before**
`nodeServer.close()` so publication and durability complete while the host is
still available:

```java
public void close() {
    persistenceSweep.shutdown();
    flush();                // final publication + durability barrier
    nodeServer.close();     // existing triggerAndClose
    store.close();
}
```

There is no separate venue-state drain: successful component mutations are
already in the root.

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
| API layer knows about engine internals | The remaining role-selected hook is only a low-latency publication hint; state correctness and eventual publication do not depend on it. |
| Per-route opt-in | New endpoints don't need persistence wiring at all. The sweep covers any cursor write. |
| Non-HTTP mutations | Same: any write to a tracked trunk is picked up by the sweep regardless of who made it. |
| Long-lived fork made root state stale | Engine components use connected venue state; bounded transaction forks commit themselves. |
| `persistInterval = 30 000` | Non-issue — it's a boolean gate, not a throttle (see §5.5). Disk writes happen on every sync regardless. |

### 5.7 Future option — observable cursor

If we ever want **proportional cost** (zero work on idle venues, bounded latency on bursts) instead of the periodic sweep, the migration path is to add a `NotifyingRootLatticeCursor` subclass to convex-core (NOT a method on `ALatticeCursor` itself). It would extend `RootLatticeCursor` and add an `onWrite(Runnable)` callback that fires after successful writes. Engine would construct its trunk cursors as `NotifyingRootLatticeCursor` instances and switch from the timer to the observer.

This keeps the lattice base classes untouched (no fast-path overhead for non-notifying users), confines the new behaviour to a specific cursor type, and makes the migration a swap of one class for another in `Engine` setup.

We don't need this today. Noting it so we know the door is open if scheduled-sweep ever proves inadequate.

---

### 5.8 Store garbage collection

Etch never reclaims space on its own. `etch.gc.onStart` (CONFIG.md
"Persistence", covia#451) collects the store at boot, in
`VenueServer.createStore` before `NodeServer` launches — the only point where
no `RefSoft` is bound to the old file, which is what makes the cutover
trivially safe. Online collection of a running venue (`v/ops/venue/gc`,
covia#452; `StoreControl` installed by `VenueServer`) instead relies on the
old `EtchStore` handle remaining a functional view after `completeGC()` —
the venue never rebinds a live ref, retains the successor only to close it
cleanly at shutdown, and allows one cycle per process; see
`convex-core/docs/ETCH_GC.md`.

## 6. Migration plan

Three phases (was four), each independently mergeable, each with tests.

### Phase 1: Persistence sweep + close ordering + barrier API — complete

**Pure Covia-side change.** The current Convex host model supplies publication
through `RootComponent.sync()` and physical durability through
`RootComponent.flush()`.

- `CoviaApplication` connects Covia to the `NodeServer`'s hosted root.
- **Engine** owns the sweep, flush and close ordering.
- `Engine.flush()` performs application sync → application flush.
- Focused tests cover the sweep, explicit barrier and close-time drain.
- The route-selected sync safety net still coexists with the sweep.

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
| Engine `VenueState` model | **Connected by default.** | Engine is the live representative of venue state; it should not hide a second long-lived working copy beneath itself. |
| Component-internal forks | **Allowed for bounded transactional multi-write atomicity.** Component is responsible for its own sync. | Forks are appropriate when N writes must become visible together, not as a general request or persistence boundary. |
| `engine.syncState()` visibility | **Package-private.** Public API is `engine.flush()`. |

---

## 9. Resolved questions

All resolved through review:

1. **Trunk cursor enumeration.** ✅ Resolved: Engine has no long-lived forked trunk. Connected component writes update the application root directly; `application.sync()` publishes that root. Bounded transaction forks are committed by their owning component and are not sweep-managed.

2. **`LatticePropagator.persistInterval` default for venues.** ✅ Resolved: no change. Cross-check found the field is a boolean gate, not a time throttle — lowering it does nothing functional. Filed as a separate convex-peer cleanup task (rename + actually thread NodeConfig through), not a Phase 1 dependency. See §5.5.

3. **Phase 3 (CoviaAdapter / DLFSAdapter refactor) timing.** ✅ Resolved: deferred. Persistence work first, adapter refactor as a separate later PR.

The hosted component and Phase 1 persistence foundations are implemented;
the remaining phases above are independent cleanup work.
