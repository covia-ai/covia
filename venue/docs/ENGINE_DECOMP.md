# Engine Runtime Decomposition — Design Note

**Status:** Proposed — 2026-07-20. Consolidates the Engine-core refactor cluster
(#241 composition, #242 persistence, #239 job lifecycle) and fixes their
scope against what is actually on disk. Cross-references the agent **harness**
track (#90, which now subsumes #86) as a *separate* effort.

**Reference docs:** `PERSISTENCE.md` (the persistence design this note defers
to), `JOBS.md` (job lifecycle contract), `AGENT_LOOP.md` §8 (the harness track).

---

## 1. The cluster reframed

The five issues were filed as one "Engine refactor" but sit on **four axes at
three maturity levels**, and two of them are not Engine at all:

| Axis | Issue(s) | What it really is | On disk today |
|---|---|---|---|
| A. Engine composition | #241 | Shed subsystems; become a composition root | god-object, 2284 LOC |
| B. Persistence seam | #242 | *Finish PERSISTENCE.md Phase 2* | Phase 1 shipped; Phase 2 pending |
| C. Job execution handle | #239 | Complete `Job` as the handle | fragmented across 5 classes + 6 adapter overrides |
| D. Harness/step contract | #90 (⊇ #86) | Agent loop redesign — **not Engine** | sign-off-ready plan (§8 D-1…D-4) |

A–C are the Engine-core work and share a dependency (see §6). D is a different
subsystem (`AgentAdapter`/`AgentState`) with its own doc and phasing; it is
listed here only so it stops being co-scheduled with A–C. #86 is closed as
subsumed by #90 D-1.

---

## 2. Principle — separate *subsystems* from *seams*

#241 proposes "six services," but they are not homogeneous. Treating them
uniformly is what makes the decomposition feel heavy and is what put a
`PersistenceCoordinator` class into #241/#242 in tension with PERSISTENCE.md.

The rule this note applies:

> **Extract a subsystem** when it is a large, cohesive body of logic with its
> own state and a narrow API (asset resolution, content routing, adapter
> registry, secrets). **Keep a seam on Engine** when it is a thin,
> single-owner coordination point that must not be scattered (persistence
> sync, the cross-user policy gate, close/lifecycle ordering). A seam does not
> want to become a service — promoting it to a class is the scatter we are
> trying to avoid.

| Concern | Disposition | Where |
|---|---|---|
| Asset resolution + remote-definition cache | **Subsystem** | `engine.assets()` — AssetResolver |
| Content get/put/route | **Subsystem** | `engine.content()` — ContentRouter |
| Adapter install/remove/lookup | **Subsystem** | `engine.adapters()` — AdapterRegistry |
| Secret resolution / provisioning | **Subsystem** | `engine.secrets()` — SecretService |
| Persistence sync / durability barrier | **Seam** (already exists) | `PersistenceHandler` + `sweep()`/`flush()` on Engine |
| Cross-user authorisation gate | **Seam** | `Engine.crossUserAllows` |
| Startup / close ordering | **Seam** | Engine composition root |

---

## 3. Engine composition root (#241)

**API strategy — clean cut (decided).** Follow the existing `engine.jobs()` /
`engine.gridScheduler()` accessor-facade pattern. Extract each subsystem behind
an accessor, **delete the ~40 direct methods**, and migrate all call sites.
No deprecated delegations.

```
engine.assets().resolve(ref, ctx)      // was engine.resolveAsset(ref, ctx)
engine.content().get(assetId)          // was engine.getContent(assetId)
engine.adapters().register(adapter)    // was engine.registerAdapter(adapter)
engine.secrets().resolve(name, ctx)    // was engine.resolveSecret(name, ctx)
```

**Delivery — one subsystem per PR** (extract + delete + migrate + focused
tests), in this order of decreasing payoff:

1. **AssetResolver** — the biggest win: ~20 methods, `Engine.java:903–1542`
   (`resolveAsset`, `resolvePath`, `resolveVirtualNamespace`, the remote
   `fetch*` cluster, the definition cache). Extracting this alone removes ~a
   third of Engine.
2. **ContentRouter** — `getContent*`, `putContent*`, `resolveContent`,
   `providerContent`, `createStorage`; deterministic provider ordering.
3. **AdapterRegistry** — `register/get/has/remove/getAdapterNames`; make
   install/remove transactional and own `AutoCloseable` adapters/modules.
4. **SecretService** — `resolveSecret`, `provisionConfiguredSecrets`.
5. **VenueProvisioner** — venue bootstrap/provisioning (`materialiseVOps`,
   `seedMcpServers`, `materialiseVenueInfo`, and `addDemoAssets` renamed away
   from "demo"). Extracting this breaks the `Engine → JobManager → Engine`
   construction cycle and lets the composition root order startup
   deterministically. Do it last — after the pure extractions.

**Residual Engine = composition root.** It constructs and owns the subsystems
with **transactional startup** (construction either completes or closes every
resource — no partial catalog state), invokes the `VenueProvisioner` once the
subsystems exist, owns the seams (§2), and runs an **ordered close** (§6).

**Acceptance-criteria mapping (from #241):** narrow API + focused tests → per
subsystem; atomic adapter install → AdapterRegistry; "close closes all owned
services in documented order" and "no partial state" → composition root — but
the **drain** half of those criteria depends on #239 (§6).

---

## 4. Persistence seam (#242)

Deferred entirely to `PERSISTENCE.md`. Re-scoped from "build a
`PersistenceCoordinator`" to **"finish PERSISTENCE.md Phase 2"**:

- Phase 1 is **already shipped**: 100 ms `sweep()` (`Engine.java:456`),
  synchronous `flush()` barrier (`:493`), correct `close()` ordering (`:515`),
  and the `PersistenceHandler` interface decoupling Engine from convex-peer —
  the single-owner seam already exists.
- Phase 2 (remaining): delete the five transport after-hooks
  (`VenueServer.java:749-753`), remove the one direct `DLFSAdapter.java:160`
  `syncState()`, make `Engine.syncState()` package-private, prove under the
  hard-kill restart matrix (PERSISTENCE.md §7). A ~10-line deletion diff plus
  restart tests.

A `PersistenceCoordinator` class is an explicit non-goal in PERSISTENCE.md and
is dropped from this cluster.

---

## 5. `Job` is the execution handle (#239)

**Decision: no new `ExecutionHandle` type.** `Job` (covia-core) already carries
the full control surface — `cancel()`, `pause()` (STARTED-only), `resume()`,
`completeWith()`/`fail()`, `future()`/`awaitResult()`, `setCancelHook()`,
listeners, and the CAS-committed record. A parallel handle object keyed by the
same jobID would split lifecycle across two types — the complection this
refactor exists to remove.

**`Job` is the base type all callers program against; underlying semantics vary
by subclass — and JOBS.md already commits to this shape.** Per JOBS.md
(*Core contract*), `Job` is concrete and already doubles as the **remote /
read-only handle** built from a record snapshot; `VenueJob` is the
local-within-venue machinery (persistence, redaction, output validation), and
today there are **no other subclasses**. Callers program to `Job` and never
branch on which.

What the remote-proxy vision adds beyond this is a **live-forwarding** remote
control impl — one whose `cancel()`/`pause()`/`resume()` forward to the owning
venue — which is more than today's read-only snapshot handle (and more than
`GridAdapter`'s hand-rolled `jobStatus`/`jobResult` polling). When it lands,
JOBS.md's *Core contract* note ("there are no other subclasses") updates with
it.

**Durable record vs live execution** — the real distinction the issue reaches
for — already exists on `Job` as *which fields persist*:

- Persisted (CAS'd to the lattice via `VenueJob.onUpdate`): status, input,
  output, prev-chain, ownership.
- Transient (in-memory; gone on restart): `resultFuture`, `onCancel`,
  `cancelled`, listeners.

After a restart, `recoverJobs()` rebuilds records with empty control fields — a
record with no live execution, modelled correctly without a second type.

**Work items (no new abstraction):**

1. **Single completion/cancel wiring path.** Collapse the
   `thenAccept(job::completeWith).exceptionally(...)` currently duplicated
   across the six job-aware adapters (A2A, Agent, Grid, HITL, Orchestrator,
   Test) into one final path. Adapters supply the work, not the wiring.
2. **`Engine.close()` drains `JobManager.activeJobs`.** The registry already
   exists; close just doesn't iterate it. This is also the fix for the
   post-flush lost-write in §6.
3. **Adapter suspend/resume hook symmetric to `onCancel`.** For adapters that
   need genuine pause/checkpoint/resume, keep live execution state
   adapter-owned but hang the hook off `Job` (`setPauseHook`/`setResumeHook`),
   not a separate object. Resume never re-invokes from stored input (unchanged,
   JOBS.md).

**Forward note.** When the remote `Job` proxy lands, the local CAS record
likely moves from the `Job` base down into the local subclass so the base stays
semantics-agnostic. Deferred until federation needs it (open question §9).

---

## 6. Dependency and sequence

The issues present A/B/C as siblings; they are **layered**.

**#239 gates the clean-shutdown half of #241.** `Engine.close()` today closes
admission and flushes but performs **no in-flight drain** (`Engine.java:515`).
Concrete failure:

> A job still running on a virtual thread calls `completeWith` *after* the final
> `persist()` in `close()`. `VenueJob.onUpdate` writes the terminal record into
> `venueState`, but the sweep is stopped and no further sync runs, so that write
> never reaches disk. On restart the job's last *persisted* status is `STARTED`,
> so recovery (JOBS.md *Recovery*) stabilises a job that actually **succeeded**
> down to `FAILED` ("effects may or may not have applied"). A completed
> operation is misreported as failed — and #241's "no post-flush writes"
> criterion is violated.

`close()` cannot drain what it cannot address; #239 (Job-as-handle + drain over
`activeJobs`) is the prerequisite. JOBS.md *Admission* today guarantees only
that no *new* work is admitted after the barrier; the in-flight drain (§5 item
2) is what closes this gap, and that section updates when the drain lands.

**Recommended order:**

1. **#242 Phase 2** — single sync owner; ~free; establishes the invariant.
2. **#239** — Job-as-handle: drain + collapse the six duplicated cancel/complete
   wirings; cleans the `AAdapter` contract, which makes step 3 cleaner.
3. **#241** — extract the four subsystems; composition-root `close()` that
   actually drains.
4. **#90** (⊇ #86) — harness redesign, a **separate track** on its own D-1…D-4
   phasing (§7); no ordering dependency on 1–3.

---

## 7. Harness track (#90, ⊇ #86) — separate

Listed for boundary clarity only. #90 is the agent harness (L1 run loop + L2
step adapter) redesign captured in `AGENT_LOOP.md` §8: drop the vestigial
`state` accumulator, narrow the step output, and generalise the cycle so the
framework stops pre-picking task/session. #86 (transition-snapshot plumbing) is
closed as subsumed by D-1 — that plumbing is deleted, not re-shaped, once the
step adapter reads from the lattice via `agent.*` accessors. This track touches
`AgentAdapter`/`AgentState`, not Engine, and should be scheduled on its own.

---

## 8. Decisions

| Decision | Choice | Reason |
|---|---|---|
| Engine public API | **Clean cut** — accessor facades, delete direct methods, migrate call sites | Matches `engine.jobs()`; smallest residual surface |
| Persistence as class or seam | **Seam** — finish PERSISTENCE.md Phase 2 | Single owner already exists behind `PersistenceHandler`; a class is the scatter to avoid |
| `ExecutionHandle` new type | **No** — `Job` is the handle | A parallel type complects lifecycle across two objects |
| `Job` interface or base class | **Base class** — polymorphic subclasses (local `VenueJob`, future grid proxy) | Callers program to `Job`, never branch on impl |
| Drain mechanism | **Iterate existing `JobManager.activeJobs`** | The registry already exists |
| Sequence | #242 → #239 → #241; #90 separate | #239 gates #241's clean-shutdown criteria |

---

## 9. Open questions

1. **Remote `Job` proxy timing.** When federation needs a first-class remote
   job, does the local CAS record move down into the local subclass (leaner
   base) or stay in the base with the remote proxy overriding the commit/await
   path? Deferred; revisit when the proxy is actually built.
2. **Where suspend-state lives.** The adapter-owned execution state behind
   `setPauseHook`/`setResumeHook` — keyed by jobID in the adapter, or carried on
   the `Job`'s transient fields? Lean: adapter-keyed, hook on `Job`.
3. **Subsystem package placement.** `covia.venue` alongside Engine, or a new
   `covia.venue.runtime` subpackage for the four extracted subsystems?
