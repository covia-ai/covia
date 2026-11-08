# Grid Scheduler — Deferred Operation Invocation

A per-venue service that invokes **any grid operation** at a future time on the
venue's clock ([EMBEDDING.md](./EMBEDDING.md#time)). The unit of work is a *scheduled event* — "invoke operation `op(input)`
as `owner` at time `T`". Waking an agent is one consumer of this, not the
model: it is simply a scheduled invocation of the agent's wake operation
(`agent:trigger`, §8).

Agent wake is a consumer of this service — see §8, and
[AGENT_LOOP.md §4.6](./AGENT_LOOP.md) for the agent side.

---

## 1. Entities

- **Scheduled event** — a deferred invocation, one-shot or recurring:
  `{ time, id, operation, input, caller, owner?, created, proofs, description?, repeat?, track?, lastFired?, lastJob? }`.
  - `time` — absolute millis on the venue's clock at which the event is next due.
  - `operation` — a grid operation reference (asset id or operation path).
  - `input` — the cell passed to that operation when it fires.
  - `caller` — the principal that scheduled it: the owner, or one of its
    agents or a delegate acting for it. The event fires as its caller. An
    agent's events go with it: deleting the agent removes them, and one that
    comes due after its agent is gone is dropped without firing.
  - `owner` — the user it belongs to, when that differs from `caller`: the
    namespace it runs in and the key for `list`, `cancel` and `trigger`. Absent
    means the caller's own. Both fields follow COG-8 and are written and read
    with `covia.grid.Attribution`.
  - `created` — millis on the venue's clock at which it was scheduled.
  - `description` — optional caller-written display text saying what the event
    is for, at most 500 characters. Never interpreted.
  - `proofs` — the UCAN proof(s) the scheduler presented when scheduling,
    presented again when it fires (§5).
  - `repeat` — optional recurrence spec; absent ⇒ one-shot. It is an object so
    richer forms can join later without renaming anything: today the only form
    is `{every: <millis>}`, a fixed interval (minimum 1 s). Calendar forms
    ("weekdays at 09:00") are future work (§9).
  - `track` — optional; whether each fire is a durable Job (§7). Absent ⇒ the
    venue default applies at fire time.
  - `lastFired` / `lastJob` — set only on a recurring event, which is the only
    kind that still exists after it fires: when it last fired, and the ID of
    the durable Job that fire produced (tracked fires only). These are the
    **whole** of the scheduler's execution memory — see §7.

- **Handle** — a stable reference to an event, composed of its `time` and `id`.
  The handle *locates* the event in the index in `O(log n)` (see §3), so it is
  the only thing a caller needs to cancel, trigger, or inspect its event.

- **The schedule** — the per-venue, time-ordered collection of pending events.
  Because it is ordered by `time`, it is a **time-based priority queue**: the
  head is always the next event due.

---

## 2. Lattice model — authoritative and per-venue

The schedule is **lattice state, owned by the venue**, so it survives restarts.
It is a plain field of the venue `:value`, alongside `:assets`, `:storage`,
`:users`, and `:user-data`:

```
:grid → :venues → <venueDID> → :value   (a single whole-value-LWW node)
                                ├── :assets    (content-addressed refs)
                                ├── :storage   (content-addressed blobs)
                                ├── :schedule   { updated, events, ... }
                                ├── :users
                                └── :user-data → <DID> → { j, g, s, w, o, h, a }
```

The `:schedule` value is a single extensible `{ updated, events, byActor, ... }` map:

```
events  = Index keyed by  time (8-byte big-endian unsigned millis) ‖ id (unique bytes)
          value = { op, input, caller, owner?, time, created, description?, proofs?,
                    repeat?, track?, lastFired?, lastJob? }
byActor = map from each caller that scheduled for another owner (an agent or a
          delegate) to the set of keys of those events
updated = the venue clock's stamp of the last mutation
```

- The `events` **Index** keeps entries sorted by key, so the key *is* the
  priority ordering: a big-endian time prefix makes unsigned byte-order equal
  numeric order, so the index head (smallest key) is the soonest-due event —
  `O(log n)` insert, remove, head-peek. The `id` suffix disambiguates events
  sharing a `time` and gives each a stable identity (minted at schedule time,
  same generator as Job IDs).
- `byActor` is the reverse index from the `caller` of each event that names an
  `owner`, written in the same transaction as every change to `events`
  (schedule, cancel, fire, re-key), so the two never disagree. Deleting an agent
  removes its events through it (`Scheduler.cancelCaller`) without scanning
  `events`. An event that comes due
  after its agent is gone, such as one scheduled before the index recorded it,
  is removed and fails with `Scheduler.AGENT_GONE`.

**Why removals survive external merge.** Local scheduler changes update the
Engine's connected root directly; no lattice merge occurs between successive
local writes. The venue `:value` is a single **whole-value-LWW node** (see
GRID_LATTICE_DESIGN.md §A.2): when an external snapshot is reconciled, the newer
whole value wins wholesale, so a removed event (cancel or fire) stays removed —
there is no per-entry `Index` *union* to re-introduce it. `:schedule` is a plain
field inside that node; its `Index` exists purely for ordering, not merge. The
`{updated, events, ...}` shape is retained by the scheduler as an extensible
events container and a last-mutation marker. Navigating to `events` crosses a
stamp-on-write boundary, which refreshes `updated` from the lattice write clock
while preserving sibling metadata. Deletion durability itself comes from the
venue-level whole-value LWW.

**Single-writer.** Every mutation of `:schedule` holds the scheduler's lock
(§4), so there is no concurrency downside. Cross-venue federation of
schedules is out of scope.

**The lattice is the source of truth.** The firing mechanism (§4) keeps in
memory only the events it is starting — nothing durable, nothing to rebuild.

---

## 3. Handles

`schedule` returns a handle that encodes the event's `time` and `id` — in
practice the index key itself, surfaced as an opaque string. Holding the handle
lets a caller act on exactly one event without scanning:

- **Cancel** — remove the event before it fires.
- **Trigger** — run the event *now*, ahead of its time (see §6). This is both a
  real feature ("run my scheduled thing immediately") and the deterministic hook
  tests use to fire an event without waiting on the wall clock.
- **Inspect** — read the event's record.

Because the handle carries `time`, it points straight at the index entry; an id
alone would require a scan. A handle for an already-fired or cancelled event
resolves to "not found".

**Recurring events keep their handle.** When a recurring event fires it is
re-keyed at its next due time (§4), so the exact key a caller was given no
longer exists — but the `id` half never changes. `cancel` and `trigger` look up
the exact key first and, on a miss, fall back to matching the `id` across the
index. Schedules are small, so the fallback is cheap and only ever runs for a
handle that has moved on; `list` always reports the current key.

---

## 4. Firing model

Nothing in the scheduler keeps time. The application's **update**
(`CoviaApplication.update()`) reads the application's clock once and updates
each mounted venue in mount order, and a venue's update starts its events due
by then. Whoever opens the application decides when it runs:
`CoviaApplication.autoUpdate()` starts a timer that calls it when the earliest
next event across the venues is due by the clock, and a venue server,
`Engine.createTemp` and the shared test engine start one. An embedder that
drives the clock steps the application itself instead
([EMBEDDING.md](./EMBEDDING.md#time)).

**One lock per venue** covers every index mutation — `schedule`, `cancel`,
`trigger`, claims by an update, agent wakes, user and caller cancellation —
each run on its caller's thread. There is no cross-thread race on the index and
no claim flag: a claim reads the head and removes it under the lock. All
ordering lives in the lattice index; nothing scans sleeping events.

**Next due.** Once a change commits, the venue tells the application when its
next event is due, after releasing its lock so that no venue's lock is ever
held while another's is taken. That is the head event's `time`, which may
already have passed (an event scheduled in the past is due at once). A venue
reports nothing due when its schedule is empty, while every place is taken
(the start that frees one reports again), and until it has started or once it
is closing. The timer wakes by the reported time when that is sooner than its
current wake-up, and after each update asks every venue again. A timer with
nothing due holds no thread.

**An update at time `t`:**
1. Raises the venue's drain time to `t`. Overlapping updates make one drain,
   whose time is the latest asked for. A step that leaves the time unchanged
   still starts whatever is due, such as an event added since with a past time.
2. While a place is free (`scheduler.maxConcurrency`, below), **claims** the
   head event if its `time ≤ t` — **one** whole-value replace (§2) that covers
   everything the fire changes about the schedule: a one-shot event is
   **removed**; a recurring event is **re-inserted** under the same `id` at
   its next due slot with `lastFired = t` and, for a tracked fire, `lastJob`.
   To make `lastJob` part of that schedule write, a tracked fire's Job is
   *prepared* first, under the lock: minted, PENDING, persisted in the
   owner's history — the adapter is **not** started. Job preparation and the
   schedule claim are two ordered lattice updates because they touch different
   records. A crash between them can leave an unstarted PENDING Job while the
   event remains; recovery fails that Job and a later fire prepares a new one.
   There is still no state in which an event has been consumed but its Job is
   unknown. Because the claim and every other mutation hold the lock, a
   `trigger` of the same event either already ran (the drain finds it gone) or
   runs after. This gives **at-most-once** firing.
3. Starts each claimed event on a fresh virtual thread, **as the event's caller
   would invoke it now, presenting its `proofs` again** (§5): start the
   prepared Job (tracked) or run the engine's transient-Job dispatch
   (untracked, §7). Any error is logged. Its place frees **when the
   operation's invocation returns** — the adapter has been called and has
   handed back its result — not when its Job finishes; the drain then claims
   the next due event. Adapters run blocking I/O off the invoking thread
   ([ADAPTERS.md](./ADAPTERS.md)), so a place is held only briefly.
4. Completes the update's future once every event due by `t` has started. How
   each resolves is its own: a test waits on the Job.

**Concurrency.** `scheduler.maxConcurrency` (default 2,
[CONFIG.md](./CONFIG.md)) bounds the events a venue is starting at once, so a
backlog never starts a thousand operations together. Due events start in index
order — time, then ID — and the rest wait in the schedule, still cancellable,
until a place frees. An agent wake takes a place like any other event.
`trigger` is a caller's own request and takes none.

Claims use the update's time, never the clock's current value, so a run stepped
on a driven clock repeats exactly. Only a started venue takes part: until
launch has finished recovery, and from the moment close begins, it starts
nothing, so events falling due then stay in the schedule for its next start.
Its close fails any update still waiting on it with `ShuttingDownException`.

If a tracked Job cannot be prepared (unresolvable op, capability denied), the
event is still claimed exactly as an untracked failure would be — the schedule
advances, there is no Job and no `lastJob`, and the error is logged (or
returned, for `trigger`).

**Claim-then-invoke** is chosen over invoke-then-remove so a crash mid-fire can
at worst *drop* an event, never double-run a user's operation. Schedules that
need at-least-once delivery are a future option (§9) paired with idempotent
operations.

**Recurrence.** The next due time of a `{every}` event is the first slot on the
`time + n·every` grid strictly after the update's time. Anchoring to the grid
keeps the phase ("every hour" stays on the hour it started on); taking the
first slot *after* the update means a backlog of missed slots collapses into
the single catch-up fire that just happened — one fire, never a burst. The next
slot is always later than the drain's time, so the drain always advances.

**Boot.** On startup the venue keeps nothing in memory: when it has started it
reports its index head to the application — nothing to replay. Events whose
`time` already passed while the venue was down are overdue and start at the
next update as a catch-up — once each, however many slots a recurring event
missed; a large overdue backlog starts in time order, `scheduler.maxConcurrency`
at a time.

---

## 5. Authority at fire time — no capability escalation

Every event records its `caller` and its `owner` (the caller's own namespace
unless it says otherwise), taken from the `RequestContext` at schedule time.
`cancel`, `trigger`, `inspect`, and `list` are restricted to the owner
(reusing the venue's access control). Sharing the owner's namespace is
proximity, not permission: a capability-scoped agent or delegate reaches only
the events it scheduled itself (its `caller`). Scheduled events are not a
capability resource, so no caps extend that reach.

A scheduled event is a *deferred* invocation by its caller: the owner, or the
agent or delegate that scheduled it. The venue must **not** fire it under its
own ambient authority, nor under anyone's but the caller's: either would let
a caller schedule work that runs with more authority than they hold.

**Invariant: a fire runs exactly as its caller would invoke the operation at
fire time** (`Engine.context(owner, caller, proofs)`), in the owner's
namespace, presenting again the UCAN proof(s) the caller presented when
scheduling, which the event keeps in `proofs`. Consequences, all intended:

- An owner's fire has the owner's authority; an agent's has the agent's
  `config.caps` as they are then, so narrowing an agent narrows its queued
  work, and a deleted agent's work does not fire.
- A delegate's fire passes the same `user/sudo` check a live request would, so
  a revoked or expired delegation stops it.
- The public principal's fire has `auth.public.caps` as they are then, and
  none while public access is off.
- A proof's own bounds still apply: an expired, revoked or attenuated proof
  makes the deferred run fail exactly as it would an immediate one. The
  scheduler never invents or keeps authority.

Every context that can schedule is a principal's own as it stands, so this
loses nothing: an agent's step always runs as that agent, under its record's
caps, whatever its input names (`Engine.stepContext`).

A fire presents no sign-in credential: only a live request carries one, and
the venue keeps none. A grid hop with `authenticateAs: "caller"` therefore
fails when it fires; work that must act as its caller at another venue is
scheduled at that venue (UCAN.md §5.6).

See [UCAN.md](./UCAN.md).

---

## 6. The `scheduler` adapter

The user-facing surface is a Covia adapter, so scheduling is invocable over REST,
MCP, A2A, or directly — and is itself discoverable as a set of operations. Its
operations, described by intent:

- **schedule** — register an event for a future time (absolute) or after a delay
  (relative), with an operation reference and its input; returns a handle.
  Optional `repeat: {every: <millis>}` makes it recurring (with neither `time`
  nor `after`, the first fire is one interval from now); optional `track`
  chooses durable or transient fires (§7); optional `description` (a string
  of at most 500 characters, refused otherwise) says what the event is for.
- **cancel** — remove an event by handle.
- **trigger** — fire an event now by handle, ahead of its time — the
  early-execution / test hook. A one-shot event is consumed; a recurring event
  stays scheduled at its unchanged next due time.
- **list** — the caller's pending events, in time order: each
  `{handle, op, caller, time, track}` plus `owner`, `input`, `description`,
  `created`, `repeat`, `lastFired` and `lastJob` where present. `track` is the effective
  tracking the next fire will use, after venue policy. `input` is shown as a
  job record would hold it: the fields the operation declares in
  `secretFields` are hidden, resolved as a job record's `op` is, and `input`
  is withheld when the operation no longer resolves, since its secret fields
  are then unknown. The stored input is untouched, because firing replays it.
  Captured `proofs` are authority, not description, and are never listed.
  Events stored before `created` and `description` were recorded list without
  them.

All operations are scoped to the caller's identity. Scheduling is itself an
operation, so an agent can schedule future work (including waking itself) as a
normal tool call.

---

## 7. Relationship to durable Jobs — the scheduler tracks schedules, Jobs track executions

The scheduler never records an outcome. It has no status, no error text, no
per-fire history — that would be a second job system. What a fire *did* is a
Job's business; the scheduler's only memory of executions is `lastFired` and,
for tracked fires, the Job ID (`lastJob`) on a recurring record. Read that Job
(`GET /api/v1/jobs/{id}`) for its status, result or failure — the same surface
every other job uses.

**Tracked vs transient fires.** Each fire is either

- **transient** — a lightweight Job wrapper via the engine's internal dispatch,
  so the adapter gets the normal execution context and lifecycle without a
  durable record. Errors surface only in the log. This is the right shape for
  chatty machinery such as an agent's own wake (`agent:trigger` every few
  minutes should not fill the owner's job history). An operation declaring
  `operation.internal:false` still forces recording.
- **tracked** — a durable Job in the owner's `j/` history, exempt from
  top-level admission like any internal dispatch, recorded regardless of
  operation metadata. The Job is prepared before the claim so its ID is
  written into the surviving recurring record as `lastJob` in the same
  atomic replace that consumes the fire (§4); a tracked one-shot simply
  appears in the owner's job list like any other job. On restart, a prepared
  Job whose adapter never ran is failed as interrupted by job recovery — the
  same rule as any other PENDING job — so the split can never double-run.

**Which applies is resolved at fire time**, so an operator's policy covers
events already queued:

1. `scheduler.forceTrackJobs: true` in the venue config → every fire is
   tracked, whatever the event asked for.
2. Otherwise the event's own `track`, if the caller set one.
3. Otherwise `scheduler.trackJobs` (venue default; `false` if unset).

```
schedule(agent:trigger, {agentId}, T)                    → transient (venue default)
schedule(X, in, T, track:true)                           → durable Job for X at T
schedule(X, in, T, repeat:{every:3600000}, track:true)   → a durable Job per fire;
                                                           list shows lastJob → read it
```

Scheduling `grid:invoke` as the target still works and still yields a durable
Job, but `track:true` does the same with one Job per fire instead of two.

---

## 8. Agent wake as a consumer

The agent wake path uses ordinary scheduled operations; its runtime entry point
coordinates the venue transaction and the scheduler (where `wakeTime` is set,
and how the run loop treats a fire, are in [AGENT_LOOP.md §4.6](./AGENT_LOOP.md)):

- A session or task carries its own `wakeTime` on the agent record — the
  authoritative per-thread "this thread wants to wake at T" marker.
- `Scheduler.setThreadWakeTime` commits the intent, event and handle in one
  `VenueState` transaction, then reports the venue's next due time from
  committed state (§4). `Agent.setThreadWakeTime` itself is a data-only
  mutation, safe on drafts, and `VenueState.setAgentThreadWakeTime` writes the
  intent and the event together without touching the live scheduler, so it
  composes into a larger venue transaction. Drafts never call a live scheduler.
  The venue re-derives the agent's **single** scheduled event at the *earliest* unfired
  `wakeTime` across all its threads. The event fires `agent:trigger {agentId,
  force:false, wait:false}` with `track:false` stated explicitly — a wake is
  machinery, not user work, so the venue's `trackJobs` default does not turn
  it into a durable Job (only `forceTrackJobs` does, §7). Its handle is stored
  on the agent record (`wakeHandle`) so the next change can cancel-and-replace
  it — exactly one event per agent.
- The event records the agent's owner as its `caller`, with no other `owner`
  and no `proofs`, so it fires with the owner's own authority, exactly as the
  owner calling `agent:trigger` would (§5).
- When it fires, `agent:trigger` (via the transient-Job `invokeFuture` path, so no
  session is minted and no durable Job is recorded) calls `wakeAgent(force:false)`. The run
  loop runs iff there is work (a session message, or a ready task, which includes a
  parked task whose `wakeTime` has come: AGENT_LOOP.md §4.6); it processes what's
  due, and the post-cycle `setThreadWakeTime` schedules the next earliest or
  clears `wakeHandle` if none remain.
- On boot, `Engine.rebuildSchedulerFromLattice` calls `Scheduler.rescheduleAgentWake` per agent,
  idempotently re-deriving each agent's event from the authoritative per-thread
  `wakeTime`s (healing any stale handle).

So per-thread independence lives in the agent's own lattice state, while the
scheduler holds a single, operation-agnostic entry per agent and knows nothing of
per-thread due-ness. Redundant wakes collapse safely because waking is idempotent — one run loop per
agent via the `runningLoops` CAS (**at most one active computation per agent**).
A scheduled `agent:trigger` is exactly as lightweight as a direct wake (transient Job,
§7).

**HITL timeouts** are another consumer, built on the job machinery. The
request's `expires` is the durable fact; a one-shot `hitl:expire {user, id}`,
made and fired by the venue as itself and untracked, is only its alarm. The
transaction that opens a request also makes the event and keeps its handle in
the job's adapter data ([ADAPTERS.md](./ADAPTERS.md)), and the transaction that
ends the job, however it ends, removes the event. Only the venue may invoke
`hitl:expire`, and it leaves a request already resolved as it is. Job recovery
restores the event of an open request that has none, so a request whose alarm
fired without expiring it, because the venue stopped, still expires (COG-16).

---

## 9. Boundaries and future work

**In scope:** per-venue `:schedule` record and its event index; `schedule` / `cancel` / `trigger` /
`list`; one-shot and fixed-interval (`repeat.every`) events with handles stable
across re-keying; **fires run as their caller at fire time, presenting their
recorded proofs, no escalation (§5)**; transient or tracked fires per event and venue
policy, with `lastJob` as the sole link to execution records (§7);
owner-scoped access; firing by the application's update, at most
`scheduler.maxConcurrency` starting at once (§4); boot catch-up (one fire per
event, missed slots skipped); claim-then-invoke (at-most-once); a 1 s floor on
`repeat.every`.

**Deferred:**
- **Calendar recurrence** — `repeat` forms beyond `every`: cron-style or
  "daily at 09:00" expressions, timezone-aware. The `repeat` object is shaped
  so these can be added without renaming anything.
- **Quotas and rate limits** — bound events per owner, far-future horizons, and
  recursive scheduling (an operation that schedules more) to prevent abuse.
  Only the `every` floor exists today.
- **At-least-once option** — for events that prefer redelivery over loss, paired
  with idempotent operations.
- **Catch-up throttling** — pace a large overdue backlog on boot.

---

## 10. Resolved decisions and open questions

**Resolved (as built):**
1. **Records per-venue** — single slot, single-writer (the scheduler's lock).
2. **Firing is transient-Job by default** — lightweight fire-and-forget; a
   durable Job per fire is opted into per event (`track`) or by venue policy
   (`scheduler.trackJobs` / `forceTrackJobs`), and is still forced by
   `operation.internal:false` (§7).
3. **Handle encoding** — the index key surfaced as an opaque hex string
   (`0x…`); `cancel`/`trigger` accept it as a hex string or blob, and follow
   the `id` when a recurring event has been re-keyed (§3).
4. **`:schedule` is an extensible `{updated, events, ...}` field inside the venue `:value`**,
   which is a single whole-value-LWW node — so removals survive the fork-merge
   wholesale, with no per-entry `Index` union to re-introduce them (§2).
5. **No execution history in the scheduler** — outcomes live on Jobs; the
   scheduler keeps `lastFired` and a `lastJob` pointer, nothing more (§7).
   Asking it for per-fire status would be rebuilding the job store.
6. **`repeat` is an object, not a number** — `{every}` today, calendar forms
   later, no renames (§1).

**Open:**
- **Delivery** — at-most-once (claim-then-invoke) is the default; an opt-in
  at-least-once mode (with idempotent ops) is future work.
- **Calendar recurrence, quotas, catch-up throttling** — see §9.
