# Grid Scheduler — Deferred Operation Invocation

A per-venue service that invokes **any grid operation** at a future wall-clock
time. The unit of work is a *scheduled event* — "invoke operation `op(input)`
as `owner` at time `T`". Waking an agent is one consumer of this, not the
model: it is simply a scheduled invocation of the agent's wake operation
(`agent:trigger`, §8).

Agent wake is a consumer of this service — see §8 and
[SCHEDULER.md](./SCHEDULER.md) for the agent-side mapping. The bespoke
per-thread `AgentScheduler` that predated this service has been retired.

---

## 1. Entities

- **Scheduled event** — a deferred invocation, one-shot or recurring:
  `{ time, id, operation, input, owner, proofs, repeat?, track?, lastFired?, lastJob? }`.
  - `time` — absolute wall-clock millis at which the event is next due.
  - `operation` — a grid operation reference (asset id or operation path).
  - `input` — the cell passed to that operation when it fires.
  - `owner` — the DID that scheduled it; the identity the operation runs as.
  - `proofs` — the UCAN proof(s) the owner presented when scheduling, captured so
    firing runs with **exactly** that authority and cannot escalate (§5).
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
                                ├── :schedule   { updated, events }
                                ├── :users
                                └── :user-data → <DID> → { j, g, s, w, o, h, a }
```

The `:schedule` value is a single `{ updated, events }` map:

```
events = Index keyed by  time (8-byte big-endian unsigned millis) ‖ id (unique bytes)
         value = { op, input, owner, time, proofs?, caps?, repeat?, track?, lastFired?, lastJob? }
updated = wall-clock stamp of the last mutation
```

- The `events` **Index** keeps entries sorted by key, so the key *is* the
  priority ordering: a big-endian time prefix makes unsigned byte-order equal
  numeric order, so the index head (smallest key) is the soonest-due event —
  `O(log n)` insert, remove, head-peek. The `id` suffix disambiguates events
  sharing a `time` and gives each a stable identity (minted at schedule time,
  same generator as Job IDs).

**Why removals survive the merge.** The venue commits its state through a
*forked cursor*: `syncState` (and the background persistence sweep) merge the
fork into the parent with a **lattice join**. The venue `:value` is a single
**whole-value-LWW node** (see GRID_LATTICE_DESIGN.md §A.2): on merge the newest
whole value wins wholesale, so a removed event (cancel or fire) stays removed —
there is no per-entry `Index` *union* to re-introduce it. `:schedule` is a plain
field inside that node; its `Index` exists purely for ordering, not merge. The
`{updated, events}` shape and the strictly-increasing `updated` stamp are
retained by the scheduler as the events container and a last-mutation marker;
deletion durability itself comes from the venue-level whole-value LWW.

**Single-writer.** The scheduler's single timer thread (§4) is the sole mutator
of `:schedule`, so there is no concurrency downside. Cross-venue federation of
schedules is out of scope.

**The lattice is the source of truth.** The in-memory firing mechanism (§4) holds
no durable state beyond the stamp counter — it is rebuilt from the index on boot.

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

The mechanism is a single in-memory **alarm** (one `ScheduledThreadPoolExecutor`
daemon, one thread) that does nothing but wake at the head event's time. That one
timer thread also **owns every index mutation** — `schedule`, `cancel`,
`trigger`, and drain all run on it (callers hop onto it and await). So mutations
are serialised by construction: there is no cross-thread race on the index, no
claim flag, and no lock. The alarm is deliberately dumb — all ordering lives in
the lattice index.

**Arming.** After any mutation the alarm is (re)set for the current head's `time`.
Inserting an earlier event brings it forward; a later insert leaves the existing
alarm (it fires and re-arms for the next head). At idle the alarm is unset and
costs nothing — there is no scan of sleeping events.

**On fire** (now ≥ head time), on the timer thread:
1. Walk the head while `time ≤ now`.
2. **Claim each** before dispatching — **one** whole-value replace (§2) that
   covers everything the fire changes about the schedule: a one-shot event is
   **removed**; a recurring event is **re-inserted** under the same `id` at
   its next due slot with `lastFired = now` and, for a tracked fire, `lastJob`.
   To make `lastJob` part of that same write, a tracked fire's Job is
   *prepared* first, on the timer thread: minted, PENDING, persisted in the
   owner's history — the adapter is **not** started. So there is no state in
   which an event has been consumed but its Job is unknown, and nothing an
   observer can see between "scheduled" and "fired with Job X". Because the
   claim and every other mutation run on this one thread, a `trigger` of the
   same event either already ran (drain skips it) or runs after. This gives
   **at-most-once** firing with no locking.
3. Dispatch on a fresh virtual thread, **replaying the event's stapled
   `proofs`/`caps`** and nothing more (§5): start the prepared Job (tracked) or
   run the engine's transient-Job dispatch (untracked, §7). Fire-and-forget;
   any error is logged. The vthread keeps the operation's I/O off the timer
   thread — the timer only ever resolves the op and writes the lattice.
4. Re-arm for the new head.

If a tracked Job cannot be prepared (unresolvable op, capability denied), the
event is still claimed exactly as an untracked failure would be — the schedule
advances, there is no Job and no `lastJob`, and the error is logged (or
returned, for `trigger`).

**Claim-then-invoke** is chosen over invoke-then-remove so a crash mid-fire can
at worst *drop* an event, never double-run a user's operation. Schedules that
need at-least-once delivery are a future option (§9) paired with idempotent
operations.

**Recurrence.** The next due time of a `{every}` event is the first slot on the
`time + n·every` grid strictly after `now`. Anchoring to the grid keeps the
phase ("every hour" stays on the hour it started on); taking the first slot
*after now* means a backlog of missed slots collapses into the single catch-up
fire that just happened — one fire, never a burst. The next slot is always in
the future, so the drain loop always advances.

**Boot.** On startup the service reads the index head and arms — nothing to
replay. Events whose `time` already passed while the venue was down are overdue
and fire immediately as a catch-up — once each, however many slots a recurring
event missed; a large overdue backlog is drained in time-order (throttling the
catch-up is a future concern).

---

## 5. Captured authority — no capability escalation

Every event records the `owner` DID taken from the `RequestContext` at schedule
time. `cancel`, `trigger`, `inspect`, and `list` are restricted to the owner
(reusing the venue's access control).

A scheduled event is a *deferred* invocation, and at fire time the owner is not
present to supply credentials. The venue must **not** fire the operation under
its own ambient authority, nor under the owner's ambient authority as it stands
at fire time — either would let a caller schedule work that runs with more
authority than they actually held, or with authority that should since have
lapsed. That is a **capability-escalation vector**, and it is disallowed.

**Invariant: capabilities are captured at schedule time and replayed at fire
time, unchanged.** The owner's UCAN proof(s) for the operation — exactly as
presented on the scheduling request — are stapled into the event's `proofs`
field. Firing presents **those proofs and nothing more**. Consequences, all
intended:

- A scheduled invocation carries precisely the authority the owner held when
  scheduling — no escalation.
- An event cannot be scheduled for an operation the owner could not invoke
  directly at schedule time (the proofs simply wouldn't authorise it).
- A stapled proof's own bounds still apply at fire time: an expired, revoked, or
  attenuated proof makes the deferred run fail exactly as it would an immediate
  one. The scheduler never invents or refreshes authority.

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
  chooses durable or transient fires (§7).
- **cancel** — remove an event by handle.
- **trigger** — fire an event now by handle, ahead of its time — the
  early-execution / test hook. A one-shot event is consumed; a recurring event
  stays scheduled at its unchanged next due time.
- **list** — the caller's pending events, in time order: each
  `{handle, op, time, track}` plus `repeat`, `lastFired` and `lastJob` where
  present. `track` is the effective tracking the next fire will use, after
  venue policy.

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

The agent wake path maps onto this service without the scheduler knowing what an
agent is (the agent-side details are in [SCHEDULER.md](./SCHEDULER.md)):

- A session or task carries its own `wakeTime` on the agent record — the
  authoritative per-thread "this thread wants to wake at T" marker.
- `AgentState.setThreadWakeTime` writes that field, then `rescheduleWake`
  re-derives the agent's **single** scheduled event at the *earliest* unfired
  `wakeTime` across all its threads. The event fires `agent:trigger {agentId,
  force:false, wait:false}` with `track:false` stated explicitly — a wake is
  machinery, not user work, so the venue's `trackJobs` default does not turn
  it into a durable Job (only `forceTrackJobs` does, §7). Its handle is stored
  on the agent record (`wakeHandle`) so the next change can cancel-and-replace
  it — exactly one event per agent.
- When it fires, `agent:trigger` (via the transient-Job `invokeFuture` path, so no
  session is minted and no durable Job is recorded) calls `wakeAgent(force:false)`. The run
  loop runs iff there is work (`hasWork` = session pending or tasks); it processes
  what's due, and the post-cycle `setThreadWakeTime` re-arms for the next earliest
  or clears `wakeHandle` if none remain.
- On boot, `Engine.rebuildSchedulerFromLattice` calls `rescheduleWake` per agent,
  idempotently re-deriving each agent's event from the authoritative per-thread
  `wakeTime`s (healing any stale handle).

So per-thread independence lives in the agent's own lattice state, while the
scheduler holds a single, operation-agnostic entry per agent. The retired
`AgentScheduler`/`ThreadRef` and per-thread due-ness leave the scheduler entirely.
Redundant wakes collapse safely because waking is idempotent — one run loop per
agent via the `runningLoops` CAS (**at most one active computation per agent**).
A scheduled `agent:trigger` is exactly as lightweight as a direct wake (transient Job,
§7).

---

## 9. Boundaries and future work

**In scope:** per-venue `:schedule` index; `schedule` / `cancel` / `trigger` /
`list`; one-shot and fixed-interval (`repeat.every`) events with handles stable
across re-keying; **captured authority — stapled UCAN proofs replayed at fire
time, no escalation (§5)**; transient or tracked fires per event and venue
policy, with `lastJob` as the sole link to execution records (§7);
owner-scoped access; boot catch-up (one fire per event, missed slots skipped);
claim-then-invoke (at-most-once); a 1 s floor on `repeat.every`.

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
1. **Records per-venue** — single slot, single-writer (the timer thread).
2. **Firing is transient-Job by default** — lightweight fire-and-forget; a
   durable Job per fire is opted into per event (`track`) or by venue policy
   (`scheduler.trackJobs` / `forceTrackJobs`), and is still forced by
   `operation.internal:false` (§7).
3. **Handle encoding** — the index key surfaced as an opaque hex string
   (`0x…`); `cancel`/`trigger` accept it as a hex string or blob, and follow
   the `id` when a recurring event has been re-keyed (§3).
4. **`:schedule` is a plain `{updated, events}` field inside the venue `:value`**,
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
