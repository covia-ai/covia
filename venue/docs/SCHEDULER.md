# Agent Wake — How an Agent Cycle Starts

How an agent cycle is initiated, and how scheduling is granular at the session
and task level while execution stays serial per agent.

**Status:** current. Agent wake is now a **consumer of the per-venue grid
scheduler** ([GRID_SCHEDULER.md](./GRID_SCHEDULER.md)) — a scheduled future wake
is just a `agent:trigger` event in the venue `:schedule`. The bespoke per-thread
`AgentScheduler` (STPE + `ThreadRef`) that an earlier design used has been
**retired**; this doc covers the agent-side mapping, the grid doc covers the
engine.

See also:
- [AGENT_LOOP.md](./AGENT_LOOP.md) — run loop, status model, virtual-thread launcher
- [AGENT_SESSIONS.md](./AGENT_SESSIONS.md) — sessions, `session.pending`, history, task binding
- [GRID_SCHEDULER.md §8](./GRID_SCHEDULER.md) — agent wake as a scheduler consumer
- [GRID_LATTICE_DESIGN.md §4.5](./GRID_LATTICE_DESIGN.md) — virtual namespaces and the user-scratch vs framework-field split

---

## 1. Two independent axes

**Execution is per-agent.** One virtual thread runs the loop for a given agent.
Launch is an atomic CAS on `runningLoops: ConcurrentHashMap<agentId,
CompletableFuture>` — only one loop-installing wake wins; concurrent wakes observe
the existing future and attach. The loop picks one session/task per iteration and
`.join()`s each transition before merging. **At most one active computation per
agent.** No `ReentrantLock` on the hot path; the only locking is inside atomic
cursor updates on the agent record.

**Scheduling is per-session and per-task.** Each session is a conversation thread
with its own wake semantics; each task is a unit of committed work with its own.
Sleep on session A must not delay session B. Wake state therefore lives on
sessions and tasks (a `wakeTime` field on each record), **not** on the agent —
there is no agent-level wake field.

These are independent: the single execution thread does not make the threads share
a *scheduling* fate.

---

## 2. Wake taxonomy

| Category | Examples | Mechanism |
|---|---|---|
| **Event** | chat / message / task arrival | intake writes `sessions[sid].pending` or `tasks`, then calls `wakeAgent` directly |
| **Scheduled** | a transition returns a future `wakeTime` on its thread | the framework arms one `agent:trigger` event in the grid scheduler (§4); it fires `wakeAgent(force:false)` when due |
| **Explicit** | `agent:trigger`, `agent:resume` | force-wake regardless of work (`force:true`) |

All three funnel into one method: `wakeAgent(agentId, ctx, force)`.

---

## 3. The wake gate

`wakeAgent` starts a loop only when, in order:

1. no live loop already exists for the agent (else attach to it);
2. phantom-`RUNNING` is corrected to `SLEEPING` (#64 — crash remnant / stale write);
3. status is `SLEEPING` (suspended / terminated do not start);
4. **`force || hasWork(agent)`**, where `hasWork` = "any session has non-empty
   `pending`, or any task exists".

`wakeTime` is **not** consulted by the gate. A scheduled wake works by *firing*
`agent:trigger` at the due time (creating a wake opportunity); the loop then runs
iff `hasWork` — i.e. a parked task is "work" and runs, a session with no pending
is a no-op. There is no `hasDueScheduledWake` arm and no agent-level wake flag —
both were removed when the per-thread model landed.

---

## 4. Scheduled wakes — per-thread `wakeTime`, one event per agent

**Authoritative state.** A session or task record carries `wakeTime` (absolute
millis): "this thread wants to wake at T". It is framework-managed — written only
by the engine, never via the `n/` / `c/` / `t/` user-scratch namespaces. Absent ⇒
the thread is event-driven only.

**Single writer.** Every `wakeTime` change goes through
`AgentState.setThreadWakeTime(scheduler, ownerDID, kind, threadId, wakeTime)`
(`kind` is `AgentState.ThreadKind.SESSION | TASK`). It:

1. writes (or clears, when `wakeTime ≤ 0`) the field on the thread record, then
2. calls `rescheduleWake`, which re-derives the agent's **single** scheduled
   event from the *earliest* `wakeTime` across all its sessions and tasks:
   cancel any previously-armed event (by the `wakeHandle` stored on the agent
   record), and — if any thread still wants a wake — schedule one
   `agent:trigger {agentId, force:false, wait:false}` event in the grid scheduler
   at that earliest time, storing the new handle. If none remain, clear
   `wakeHandle`.

So the grid scheduler holds **at most one operation-agnostic entry per agent**,
while per-thread independence lives entirely in the agent's lattice state. The
wake is scheduled under the **owner's own authority** (no extra proofs/caps) — the
minimum, non-escalating authority, the same as a manual `agent:trigger`.

**Where `wakeTime` is set.** `AgentAdapter.mergeAndPostProcess` reads an optional
`wakeTime` from the transition result and installs it on the picked thread via
`setThreadWakeTime`; if the transition returned none but the picked record still
carries a stale `wakeTime` (from the wake just serviced), it clears it. Intake ops
(`agent:request` / `agent:chat` / `agent:message`) write `pending` / `tasks`, not
`wakeTime` — new work runs ASAP via `hasWork`, not via the scheduler.

**Firing.** The grid scheduler fires `agent:trigger` through the engine's zero-Job
`invokeFuture` path (`AgentAdapter.doKick`), so it **mints no session and creates
no Job** — it just calls `wakeAgent(force:false)`. The run loop processes every
thread whose work is ready and, on merge, re-arms for the next earliest `wakeTime`
(or clears the handle).

**Boot.** `Engine.rebuildSchedulerFromLattice` calls `rescheduleWake` once per
agent, idempotently re-deriving each agent's event from the authoritative
per-thread `wakeTime`s. The `:schedule` slot itself persists across restarts
(GRID_SCHEDULER.md §2), so this only heals a stale handle; overdue wakes fire
immediately on boot.

---

## 5. `agent:trigger` — the explicit kick

`agent:trigger` is a **fallback kick, not a result-getter.** It carries no payload
and guarantees only that the loop gets a cycle. Callers who want output submit work
via `agent:request` (task Job) or `agent:chat` (chat Job) and await that Job.

- **Creates no session.** A supplied `sessionId` is resolved and echoed back; if
  none is supplied the trigger runs unsessioned. (It used to mint an empty session
  — removed.)
- **`force` (default `true`)** — `true` runs a cycle even if the agent is idle
  (the historical kick); `false` runs only if there is work. The flag is additive,
  so existing callers are unchanged; the scheduler is the caller that passes
  `false`.
- **`wait` (default block)** — `true`/absent blocks until the loop drains to
  `SLEEPING`; `false` returns immediately with `{status: RUNNING}`; `<ms>` is a
  bounded block. This is a wait on the run loop's completion future, **not** a
  result-await. Cancelling the trigger Job ends only that caller's wait.

User-facing calls go through the Job-aware `handleTrigger`; the scheduler's
zero-Job fire goes through `invokeFuture` → `doKick`. Both share `wakeAgent`.

---

## 6. Invariants

- **One funnel.** Every wake → `wakeAgent` (atomic launcher CAS, phantom recovery,
  gate, vthread dispatch).
- **Write-then-wake (events).** Intake writes `pending`/`tasks` before calling
  `wakeAgent`. A write landing while a loop runs is picked up by the next
  iteration's `hasWork`; a write landing as the loop exits is caught by the
  `finally`-block re-check after `runningLoops.remove`, which re-wakes.
- **Replace-semantics (scheduled).** The last `wakeTime` writer wins — no
  min-merge. An explicit long sleep must not be clobbered by a short retry.
- **Lattice is truth.** Per-thread `wakeTime` is authoritative and persistent; the
  single per-agent scheduler event is derived from it and rebuilt on boot.

---

## 7. Deferred / not yet built

These were part of an earlier per-thread design and are **not** implemented; they
remain candidate future work and are recorded here so the doc isn't read as
describing them as current:

- **`sleep` harness tool** — let an LLM defer its current thread by writing
  `wakeTime = now + ms`. Today a future `wakeTime` is set only by a transition
  result (`mergeAndPostProcess`), not by a dedicated tool.
- **Backoff / `yieldCount`** — exponential backoff on repeated no-progress yields.
  No `yieldCount` field or backoff policy exists yet.
- **Async-completion wake (Path 4)** — a completion hook on an outbound Job that
  appends to `session.pending` and wakes the agent. The agent-level `pending`
  Index exists; the hook wiring is not part of the wake scheduler.
- **Task/session unification ("threads")** — collapsing tasks and sessions into a
  single `threads` shape with one `pickReadyThread`. Large refactor; its own doc.
- **Observability** — surfacing each thread's `wakeTime` (and the agent's next
  wake) in `agent:info`.
