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

- **Scheduled event** — a single deferred invocation:
  `{ time, id, operation, input, owner, proofs, repeat? }`.
  - `time` — absolute wall-clock millis at which the event becomes due.
  - `operation` — a grid operation reference (asset id or operation path).
  - `input` — the cell passed to that operation when it fires.
  - `owner` — the DID that scheduled it; the identity the operation runs as.
  - `proofs` — the UCAN proof(s) the owner presented when scheduling, captured so
    firing runs with **exactly** that authority and cannot escalate (§5).
  - `repeat` — optional interval (millis) for recurrence; absent ⇒ one-shot.

- **Handle** — a stable reference to an event, composed of its `time` and `id`.
  The handle *locates* the event in the index in `O(log n)` (see §3), so it is
  the only thing a caller needs to cancel, trigger, or inspect its event.

- **The schedule** — the per-venue, time-ordered collection of pending events.
  Because it is ordered by `time`, it is a **time-based priority queue**: the
  head is always the next event due.

---

## 2. Lattice model — authoritative and per-venue

The schedule is **lattice state, owned by the venue**, so it survives restarts.
It is a venue-level slot alongside `:assets`, `:jobs`, and `:users`:

```
:grid → :venues → <venueDID> → VENUE
                                ├── :assets    (content-addressed)
                                ├── :jobs       IndexLattice  (append-only job records)
                                ├── :schedule   LWW { updated, events }  (replaced as a unit)   ← new
                                ├── :users
                                └── …
```

The slot value is a single `{ updated, events }` map:

```
events = Index keyed by  time (8-byte big-endian unsigned millis) ‖ id (unique bytes)
         value = { op, input, owner, time, proofs?, caps? }
updated = strictly-increasing stamp; the whole value with the higher stamp wins the merge
```

- The `events` **Index** keeps entries sorted by key, so the key *is* the
  priority ordering: a big-endian time prefix makes unsigned byte-order equal
  numeric order, so the index head (smallest key) is the soonest-due event —
  `O(log n)` insert, remove, head-peek. The `id` suffix disambiguates events
  sharing a `time` and gives each a stable identity (minted at schedule time,
  same generator as Job IDs).

**Why the whole value is replaced as a unit (not a per-entry `IndexLattice`).**
The venue commits its state through a *forked cursor*: `syncState` (and the
background persistence sweep) merge the fork into the parent with a **lattice
join**. A per-entry `IndexLattice` join is a key-wise *union* — a key removed on
cancel or fire has no tombstone, so the union **re-introduces it** after the next
sync. (Adds are monotonic and survive; only removals break — which is why `:jobs`,
being append-only, gets away with `IndexLattice`.) Making `:schedule` a whole
`{updated, events}` value under an `LWW` slot lattice means the merge keeps the
*latest whole value* — removals included. The Convex `Index` lives **inside** that
value purely for ordering. See GRID_LATTICE_DESIGN.md and the regression
`ScheduleSlotMergeTest`.

**Single-writer.** The scheduler's single timer thread (§4) is the sole mutator
of `:schedule`, so whole-value LWW has no concurrency downside; the
strictly-increasing `updated` stamp (seeded from the persisted value on boot)
guarantees the latest write wins the merge. Cross-venue federation of schedules
is out of scope.

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
2. **Claim each by removing it from the index** (a whole-value replace, §2) before
   dispatching. Because removal and every other mutation run on this one thread,
   a `trigger` of the same event either already ran (removed it — drain skips) or
   runs after (finds it gone). This gives **at-most-once** firing with no locking.
3. Dispatch the claimed event's operation as the owner on a fresh virtual thread,
   **replaying the event's stapled `proofs`/`caps`** and nothing more (§5), via the
   engine's internal, zero-Job dispatch — fire-and-forget; any error is logged.
   The vthread keeps the operation's I/O off the timer thread.
4. Re-arm for the new head.

**Claim-then-invoke** is chosen over invoke-then-remove so a crash mid-fire can
at worst *drop* an event, never double-run a user's operation. Schedules that
need at-least-once delivery are a future option (§9) paired with idempotent
operations.

**Boot.** On startup the service reads the index head and arms — nothing to
replay. Events whose `time` already passed while the venue was down are overdue
and fire immediately as a catch-up; a large overdue backlog is drained in
time-order (throttling the catch-up is a future concern).

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
- **cancel** — remove an event by handle.
- **trigger** — fire an event now by handle, ahead of its time, and remove it
  (for one-shot) — the early-execution / test hook.
- **list** — the caller's pending events, in time order.

All operations are scoped to the caller's identity. Scheduling is itself an
operation, so an agent can schedule future work (including waking itself) as a
normal tool call.

---

## 7. Relationship to Jobs — tracking is opt-in

Firing does **not** create a Job. The scheduler performs a lightweight,
fire-and-forget invocation (the engine's zero-Job internal dispatch), so a wake
or a periodic housekeeping op costs nothing more than the call itself. The
scheduler stays orthogonal to the job lifecycle.

If you *want* a tracked, owned, observable invocation, **schedule an operation
that creates one** — `grid:invoke` (async job, returns an id to poll) or
`grid:run` (run-and-wait). The Job is then created by `grid:invoke` / `grid:run`,
not by the scheduler, and is visible through the usual job APIs (status, SSE,
cancel). Job semantics live in *what you schedule*, not in the firing path:

```
schedule(agent:trigger, {agentId}, T)          → lightweight wake, no Job
schedule(grid:invoke,   {operation: X, …}, T)  → a tracked Job for X at T
```

Result and error visibility are opt-in the same way: a fire-and-forget op
surfaces errors only in the log; schedule it through `grid:invoke` when the
outcome needs to be recorded and awaited.

---

## 8. Agent wake as a consumer

The agent wake path maps onto this service without the scheduler knowing what an
agent is (the agent-side details are in [SCHEDULER.md](./SCHEDULER.md)):

- A session or task carries its own `wakeTime` on the agent record — the
  authoritative per-thread "this thread wants to wake at T" marker.
- `AgentState.setThreadWakeTime` writes that field, then `rescheduleWake`
  re-derives the agent's **single** scheduled event at the *earliest* unfired
  `wakeTime` across all its threads. The event fires `agent:trigger {agentId,
  force:false, wait:false}`. Its handle is stored on the agent record
  (`wakeHandle`) so the next change can cancel-and-replace it — exactly one event
  per agent.
- When it fires, `agent:trigger` (via the zero-Job `invokeFuture` path, so no
  session is minted and no Job is created) calls `wakeAgent(force:false)`. The run
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
A scheduled `agent:trigger` is exactly as lightweight as a direct wake (zero-Job,
§7).

---

## 9. Boundaries and future work

**In scope (first cut):** per-venue `:schedule` index; `schedule` / `cancel` /
`trigger` / `list`; one-shot events; **captured authority — stapled UCAN proofs
replayed at fire time, no escalation (§5)**; lightweight (zero-Job) firing, with
Job tracking opt-in via scheduling `grid:invoke` / `grid:run`; owner-scoped
access; boot catch-up; claim-by-removal (at-most-once).

**Deferred:**
- **Recurrence** — `repeat` interval re-inserts the event at `time + repeat` on
  fire; cron-style calendar expressions later.
- **Quotas and rate limits** — bound events per owner, far-future horizons, and
  recursive scheduling (an operation that schedules more) to prevent abuse.
- **At-least-once option** — for events that prefer redelivery over loss, paired
  with idempotent operations.
- **Catch-up throttling** — pace a large overdue backlog on boot.

---

## 10. Resolved decisions and open questions

**Resolved (as built):**
1. **Records per-venue** — single slot, single-writer (the timer thread).
2. **Firing is zero-Job by default** — lightweight fire-and-forget; Job tracking
   is opt-in by scheduling `grid:invoke` / `grid:run` rather than the target
   operation directly.
3. **Handle encoding** — the index key surfaced as an opaque hex string
   (`0x…`); `cancel`/`trigger` accept it as a hex string or blob.
4. **Slot stores the whole `{updated, events}` value under `LWW`** (not a
   per-entry `IndexLattice`) so removals survive the fork-merge (§2).

**Open:**
- **Delivery** — at-most-once (claim-then-invoke) is the default; an opt-in
  at-least-once mode (with idempotent ops) is future work.
- **Recurrence, quotas, catch-up throttling** — see §9.
