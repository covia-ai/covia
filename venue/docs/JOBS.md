# Jobs — Target Design

The `Job` abstraction: a clean contract over a (potentially remote)
request–response to an operation. Implementations may differ
(venue-hosted, remote-client, in-process) but the core contract is small,
the core class holds no runtime machinery it can't justify, and
implementation concerns stay in subclasses.

## Core contract

A `Job` is a handle to a request for an operation. It exposes:

```java
public class Job {
    Blob getID();                              // unique identity
    AString getStatus();                       // current status (snapshot)
    AMap<AString, ACell> getData();            // current record snapshot
    CompletableFuture<ACell> getResult();      // eventual result
    ACell awaitResult();                       // blocking convenience
    ACell awaitResult(long timeoutMs);         // bounded blocking convenience
    void cancel();                             // request cancellation
    boolean isFinished();                      // terminal?
}
```

That's it. Everything else is an implementation concern.

**Explicitly NOT on the base class:**

- Lattice cursors
- Message queues
- In-flight `Future<?>` handles for interruptible cancel
- Listener registrations
- Operation metadata
- Persistence / recovery
- Message-delivery machinery

Each of those is runtime state for a particular *way* of running a Job. The
client SDK has no use for a message queue or a cursor. A venue doesn't need
a different `Job` class just because it also persists; it needs a subclass
that adds persistence.

## Shape of the class hierarchy

```
Job  (covia-core)
  ├─ VenueJob   — venue-hosted: lattice cursor at :user-data/DID/:j/<id>,
  │              durable, replicable, recoverable on restart
  ├─ RemoteJob  — client-side handle to a Job on a remote venue:
  │              polls status over HTTP/SSE, no local state beyond the future
  └─ LocalJob   — in-process Job with arbitrary completion mechanism
                  (direct callback, test harness, non-lattice execution)
```

`Job` is concrete enough to be useful as a remote-readable handle (constructed
from a record snapshot), but contains no machinery a remote handle can't use.
Venue-side machinery lives in `VenueJob`.

## `VenueJob` — target shape

```java
class VenueJob extends Job {
    // narrow cursor at this Job's own path
    final ALatticeCursor<AMap<AString, ACell>> cursor;

    @Override void setStatus(AString s)            { writeThen(r -> r.assoc(K_STATUS, s)); }
    @Override void completeWith(ACell out)         { writeThen(r -> terminalSet(r, COMPLETE, K_OUTPUT, out));
                                                      future.complete(out); }
    @Override void fail(String msg)                { writeThen(r -> terminalSet(r, FAILED, K_ERROR, Strings.create(msg)));
                                                      future.completeExceptionally(new JobFailedException(this)); }

    private void writeThen(UnaryOperator<AMap<AString,ACell>> fn) {
        cursor.updateAndGet(r -> isTerminal(r) ? r : fn.apply(r));
    }
}
```

Every state transition is one atomic `updateAndGet` at the Job's own path.
The lambda gates invalid transitions (no-op once terminal). The cursor is
the source of truth; the base class's future is completed *after* the
durable write.

### Lattice shape

```
:user-data  →  <callerDID>  →  :j  →  <jobID>  →  { status, input, output, error, ts, op, ... }
```

Each Job has a unique ID, so no two Jobs share a path. Writes are
contention-free at the record level — two Jobs with distinct IDs write to
disjoint paths and compose via lattice merge at the enclosing Index.

### Sync boundaries

Signing and persistence are decoupled from Job writes:

| Event | Trigger | Action |
|---|---|---|
| Job transition | adapter / run loop / client | narrow `updateAndGet` at VenueJob's cursor. No sign, no persist. |
| Sign + persist | HTTP `after` hook, periodic sweep, explicit flush | `venueState.sync()` then `lattice.sync()`. Batched. |

Thousands of Job updates can land between sync points. Writes are cheap
and concurrent; durability is batched.

## Why this removes contention

The current engine-wide fork routes every write through one shared
`AtomicReference`. The CAS lambda grows with accumulated write volume, and
concurrent writers serialise even when their target paths are disjoint.

`VenueJob` cursors resolve to the root directly. Each `updateAndGet` is a
small `assocIn` at a disjoint path. CAS retries are cheap (microseconds);
lattice merge at the enclosing `Index` is commutative and associative so
concurrent siblings compose without conflict.

This is what the lattice architecture is for — we'd been fighting it by
funnelling writes through one fork.

## Multi-turn delivery (replaces in-memory message queue)

The current base `Job` has a `ConcurrentLinkedQueue<AMap>` for multi-turn
adapters. It's in-process, non-durable, and duplicates the lattice inbox
pattern already used by `AgentState`.

Target: **remove `messageQueue` from `Job` entirely**. If a multi-turn
adapter needs buffered message delivery, `VenueJob` exposes a lattice-backed
inbox at its own path — same `cursor.updateAndGet` primitive:

```
:user-data  →  <callerDID>  →  :j  →  <jobID>  →  :inbox  →  [message, ...]
```

Same shape, same durability guarantees, same contention model as other
lattice state. Adapter reads the inbox through the cursor; delivery writes
through the cursor. Survives restarts; replicates across peers.

## Why both `JobStore` and `JobManager`?

| Class | Today | Target |
|---|---|---|
| **`JobStore`** | Cursor wrapper at `:jobs` level; CRUD on a shared Index | **Removed.** The parent Index at `:user-data/DID/:j` is just the natural view when you read the parent path; no abstraction needed. |
| **`JobManager`** | Runtime coordinator + in-memory cache + futures + recovery + access control + message-delivery | Slimmed: dispatch + future-cache + recovery scan + access control. No state ownership, no cursor ownership, no message-queue ownership. |

The two-layer split was historical (Phase 2.5 in `CLAUDE.local.md`) and
made sense when Jobs were "entries in a shared Index owned by a store".
Under the target design, Jobs own their own cursors — the "store" role
disappears.

## From here to there

1. Define `VenueJob extends Job` with a narrow path cursor rooted at
   `path(USER_DATA, ctx.did, J, jobID)`. `JobManager.createJob(...)` returns
   a `VenueJob`.
2. Move cursor-writing transition methods to `VenueJob`. Base `Job` keeps
   only the future-completion side (or delegates terminal completion back
   to the base after the cursor write).
3. Remove `messageQueue`, `workFuture`, `updateListener`, `operation` from
   base `Job`. Each moves to the subclass that actually uses it — likely
   `VenueJob` for the first three; `operation` is mostly a cached
   convenience and can become a getter against cursor data.
4. Remove `JobStore` (86 lines, 6 refs). Migrate call sites to read through
   Job's cursor or the parent Index via `cursor.get()`.
5. Stop routing Job writes through `Engine.venueState`. Path cursors go
   directly to the root.
6. Add a concurrent-submission regression test that currently flakes under
   the shared fork; target is near-constant per-Job latency under
   parallelism.

Non-goals:
- Changing the signed-lattice model or persistence story.
- Removing forks where they genuinely fit (batched single-writer workloads
  like multi-step agent transitions).
- Fixing the same pattern in `AgentState` / `AssetStore` / `SecretStore` —
  each is a mechanically similar follow-on refactor, one subsystem at a
  time.

## Testing

- Concurrent Job submission tests should scale with core count under the
  target design. Currently they flake because all writers funnel through
  one fork.
- A regression test: N threads each submit M Jobs and await completion.
  Total wall time should be dominated by per-Job work, not contention.
  This test is the catalyst for the design change.
