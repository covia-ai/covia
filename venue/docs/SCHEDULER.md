# Agent Scheduler — Wake Paths and Per-Thread Scheduling

Design for the four ways an agent cycle starts, and how scheduling is granular
at the session and task level while execution remains serial per agent.

**Status:** April 2026. Paths 1 and 2 are built. Path 3 (this doc's main
subject) and Path 4 are planned.

See:
- [AGENT_LOOP.md](./AGENT_LOOP.md) — run loop, status model, virtual-thread launcher
- [AGENT_SESSIONS.md](./AGENT_SESSIONS.md) — sessions, session.pending, history, task binding
- [GRID_LATTICE_DESIGN.md §4.5](./GRID_LATTICE_DESIGN.md) — virtual namespaces (`n/`, `c/`, `t/`) and the split between user scratch and framework-managed state.

This doc references two distinct `pending` concepts: agent-level `pending` (Index of outbound Job IDs, Path 4) and session-level `sessions[sid].pending` (Vector of incoming envelopes). Same field name, different paths.

---

## 1. Threading Model — The Core Distinction

**Execution threading is per-agent.** One virtual thread runs the loop for
a given agent. Launch is serialised by an atomic CAS on a
`ConcurrentHashMap<agentId, CompletableFuture>` — only one loop-installing
wake wins, subsequent wakes observe the existing future and return. The
loop picks one session/task per iteration and blocks on each transition's
future with `.join()` before merging. No `ReentrantLock` on the hot path;
the only locking happens inside atomic cursor updates on the agent record.

**Scheduling threading is per-session and per-task.** Each session is a
conversation thread with its own wake semantics; each task is a unit of
committed work with its own wake semantics. Sleep in session A must not
delay session B. Backoff on task X must not delay task Y. The execution
lock does not make them share a scheduling fate.

**Consequence:** wake state lives on sessions and tasks, not on the agent.
The agent record carries no agent-level wake field. The scheduler indexes
per-thread wake times in a priority queue and calls `wakeAgent` when a
thread comes due.

This is the single most important design decision in this document and
drives the rest.

---

## 2. Wake Taxonomy

Three categories of things that cause an agent cycle to run:

| Category | Examples | State change | Scheduler involved? |
|---|---|---|---|
| **Event** | chat / message / task arrival; async job completion (Path 4) | writes to `sessions[sid].pending` or `tasks` Index | No — direct `wakeAgent` |
| **Scheduled** | agent `sleep`; framework backoff after yield | writes `sessions[sid].wakeTime` or `tasks[tid].wakeTime` | Yes — scheduler fires when target reached |
| **Explicit** | `agent:trigger`, `agent:resume` | no state required | No — force-wakes regardless |

Events go through the work path. Scheduled wakes go through the timer path.
Explicit wakes bypass both. Different mechanisms, one funnel (§9).

---

## 3. Principles

1. **One funnel.** Every wake goes through `wakeAgent(agentId, ctx, force)`.
   Atomic launcher CAS on `runningLoops`, phantom-RUNNING recovery, status
   write, virtual-thread dispatch.

2. **Write-then-wake (events).** Intake writes to pending / tasks / session
   before calling `wakeAgent`. If a loop is already running, the write
   naturally becomes visible to its next iteration's top-of-loop `hasWork`
   check — no handoff needed. If no loop is running, the run loop's
   `finally` block re-checks `hasWork` after releasing the launcher slot,
   triggering a fresh wake for any write that landed during exit.

3. **Replace-semantics (scheduled).** Sleep and framework backoff write
   session/task wake times directly. The last writer's value stands. No
   min-merge — an explicit 5-minute sleep must not be clobbered by a
   1-second framework retry.

4. **Events bypass wake times.** The gate is `hasWork OR anyDueScheduledWake`.
   New pending writes trigger `hasWork`; `wakeTime` is not consulted on
   that branch. A sleeping session A does not block processing of a new
   message on session B — they are independent scheduling threads.

5. **Launcher slot is truth for liveness.** The lattice `status` field is
   a durable hint; the in-memory `runningLoops` map tracks whether a loop
   is actually live (the agent's `CompletableFuture` is present and
   unfinished). Phantom RUNNING (crash remnant, stale write past a clean
   exit) is corrected before installing a fresh slot (#64).

6. **Lattice is source of truth; scheduler index is derived.** `wakeTime`
   on sessions and tasks is authoritative and persistent. The scheduler
   holds an in-memory priority queue keyed by `wakeTime` for O(log n)
   next-due lookup, rebuilt on boot from the lattice. A single helper
   (`AgentState.setThreadWakeTime`) writes the field AND updates the
   index atomically — no other call site mutates `wakeTime`, so
   invalidation cannot drift.

---

## 4. The Four Paths

| # | Name | Initiator | `force` | Writes what? | Runs without work? |
|---|---|---|---|---|---|
| 1 | Explicit trigger | `agent:trigger`, `agent:resume` | `true` | nothing | **Yes** |
| 2 | Work arrival | `agent:request`, `agent:chat`, `agent:message` | `true`* | session.pending / tasks | No |
| 3 | Scheduled | venue scheduler priority queue | `false` | (reads session.wakeTime / task.wakeTime) | No |
| 4 | Async completion | Job completion hook | `false` | session.pending (completion envelope) | No |

\* Path 2 uses `force=true` purely to cover the write-visibility window
between the lattice write and the gate's re-read. Semantically the work
exists — the force is a race guard, not a bypass.

---

## 5. Path 1 — Explicit Trigger

`agent:trigger` / `agent:resume` invoke `wakeAgent(agent, ctx, true)`. Force
skips the gate — the loop runs one cycle even with no session pending, no
due timers, no awaits.

**Trigger is a fallback kick, not a result-getter.** It carries no payload
and guarantees only that the loop gets a cycle. Callers who want agent
output should submit work via `agent:request` (task Job) or `agent:chat`
(chat Job) and await that Job — those are the result-bearing paths. The
`wait` modes below control how long the trigger call blocks on the run
loop's `CompletableFuture`, not how long it waits for agent output:

- `wait` absent / `true` — block until the loop completes (drained all
  work and exited to SLEEPING)
- `wait: false` — return immediately with `{status: RUNNING}`; poll via
  `agent:info` or `covia:read path=g/<agent>/status`
- `wait: <ms>` — bounded block; on timeout return a RUNNING snapshot

A transition that awaits an async op (HTTP/LLM/HITL) simply keeps its
virtual thread blocked on `.join()` — there is no explicit yield/resume
handoff. The loop continues to its next iteration only after the
transition's future completes. A trigger returning `SLEEPING` means the
loop drained all outstanding work. A `RUNNING` snapshot means the loop
is still processing (either mid-transition or iterating through more
work); the caller can poll status to observe quiescence.

**Use cases:** manual kicks, diagnostic runs, resume from SUSPENDED, nudging
a stuck agent, tests. Not the common path — events (Path 2) and timers
(Path 3) cover normal operation. Path 4 (async completion) becomes
relevant when an external Job write lands on the agent's lattice after
the loop has exited; the finally-block re-check or the next intake call
is what wakes the loop again.

---

## 6. Path 2 — Work Arrival

| Intake op | Writes to | Then |
|---|---|---|
| `agent:request` | `tasks[jobId] = taskData` | `wakeAgent(agent, ctx, true)` |
| `agent:chat` | `sessions[sid].pending ← env` + reserves chat slot | `wakeAgent(agent, ctx, true)` |
| `agent:message` | `sessions[sid].pending ← env` | `wakeAgent(agent, ctx, false)` |

The "has work" predicate is true when any session has non-empty `pending`, or any task entry exists, or any task is past due (see §7).

---

## 7. Path 3 — Scheduled Wake (B8.8)

### 7.1 Problem

Two scheduling intents exist:

- **Agent-initiated (sleep)** — "nothing to do on this thread right now; wake me in X ms."
- **Framework-initiated (backoff)** — a task that yields without progress has its next cycle deferred, with backoff growing on repeated yields. See AGENT_SESSIONS.md §6.3.

The current shape (single agent-level wake field, min-merge semantics, no poller) does not match either intent. Scheduling should be per-thread (not agent-level), replace-semantics (not min-merge), and observed by a scheduler that fires the appropriate wake when a thread becomes due.

### 7.2 State Shape — Per-Thread Wake

Agent-level wake state is eliminated. Sessions and tasks are the scheduling threads, and each carries the same two fields under the same names — a deliberate step toward eventual unification (§11.9). A "thread" here means either a session record or a task record; they share the scheduling contract.

| Field | Type | Description |
|---|---|---|
| `wakeTime` | CVMLong? | Absolute millis. When `now ≥ wakeTime`, this thread is due. Cleared by any cycle that processes the thread. Written by the `sleep` harness tool (agent intent) or by framework backoff on yield. Absent means "no scheduled wake for this thread". |
| `yieldCount` | CVMLong? | Incremented on each consecutive yield without progress on this thread. Used to compute exponential backoff. Reset by progress (currently: task completion; see §7.9). Sessions accumulate yield count too — a session that repeatedly yields with no new messages backs off the same way as a task. |

**System-owned, not user-writable.** `wakeTime` and `yieldCount` are framework-managed fields written only by the engine and dedicated ops. They are **not** exposed via user-scratch shortcuts like `t/` or `c/` — agent code requests a sleep via the `sleep` tool (§7.8); it does not write `wakeTime` directly. This keeps the user surface focused on data the operation owns, and keeps the framework free to restructure scheduling storage without breaking user code. See [GRID_LATTICE_DESIGN.md §4.5](./GRID_LATTICE_DESIGN.md) for the broader user-scratch vs system-field split.

Absent `wakeTime` means "no outstanding schedule" — the thread is event-driven only (e.g. a chat session waiting for the next message).

### 7.3 Why Not Scan

At venue scale (thousands of users × many agents × many sessions) a
per-second full scan over SLEEPING agents × threads is wasted work:
steady state is "almost everything is sleeping with no wake due", so
~100% of the scan cost produces no action. An indexed scheduler, fired
only when a thread is actually due, is O(log n) per schedule/cancel
and zero cost at idle.

### 7.4 Scheduler Component — `ScheduledThreadPoolExecutor`

`AgentScheduler` is a thin wrapper around the JDK's
`ScheduledThreadPoolExecutor` (STPE). STPE is already a heap-backed
priority-queue scheduler with park-until-next-due, automatic promotion
on earlier writes, O(log n) cancel, and battle-tested shutdown. No
reason to re-implement any of it.

**Internals:**

```java
class AgentScheduler {
    private final ScheduledThreadPoolExecutor timer;
    private final ConcurrentHashMap<ThreadRef, ScheduledFuture<?>> handles;
    private final Engine engine;

    AgentScheduler(Engine engine) {
        this.engine = engine;
        this.timer = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "agent-scheduler");
            t.setDaemon(true);
            return t;
        });
        // Cancelled futures removed from queue immediately, not at fire time.
        this.timer.setRemoveOnCancelPolicy(true);
        this.handles = new ConcurrentHashMap<>();
    }
}
```

One platform daemon thread for timing. Fire action dispatches onto a
fresh virtual thread (below) so `wakeAgent`'s lattice writes / Etch
I/O never stall the timer.

**Public surface:**

| Method | Semantics |
|---|---|
| `schedule(ref, wakeTime)` | Cancel any prior future for `ref`, install a new fire at `wakeTime`. |
| `cancel(ref)` | Cancel the future for `ref`, if any. |
| `nextDue()` | Earliest absolute wake (diagnostics / tests); `-1` if empty. |
| `tickOnce()` | Test-only: synchronously fire all due refs against an injected `TimeSource`. |

**Implementation:**

```java
void schedule(ThreadRef ref, long wakeTime) {
    ScheduledFuture<?> prev = handles.remove(ref);
    if (prev != null) prev.cancel(false);
    long delay = Math.max(0, wakeTime - now());
    handles.put(ref, timer.schedule(() -> fire(ref), delay, MILLISECONDS));
}

void cancel(ThreadRef ref) {
    ScheduledFuture<?> f = handles.remove(ref);
    if (f != null) f.cancel(false);
}

private void fire(ThreadRef ref) {
    handles.remove(ref);   // slot cleared; next schedule installs fresh
    Thread.ofVirtual().start(() -> {
        try {
            engine.wakeAgent(
                ref.agentId(),
                RequestContext.scheduler(ref.userDid()),
                false);
        } catch (Throwable t) {
            log.warn("scheduler fire failed for {}", ref, t);
        }
    });
}
```

**`ThreadRef`** is a record `(userDid, agentId, threadKind, threadId)`
with value-based equals/hashCode — the map key for cancel-by-ref.

**`RequestContext.scheduler(did)`** is a new factory mirroring
`INTERNAL` but carrying the agent-owner's DID for access control.
Bypasses rate limits (the scheduler is the venue itself).

### 7.5 Invariant: Single-Writer per `wakeTime` Field

Every `wakeTime` write goes through one helper:

```
AgentState.setThreadWakeTime(kind, threadId, wakeTime | null)
    ↓ cursor.updateAndGet — writes sessions[sid].wakeTime / tasks[tid].wakeTime
    ↓ wakeTime != null ? scheduler.schedule(ref, wakeTime) : scheduler.cancel(ref)
```

**Order matters: lattice first, scheduler second.** If the process
crashes between the two, the boot rebuild (§7.6) restores the scheduler
from the lattice. The reverse order would risk losing writes.

**All call sites identified:**

| Caller | Context | Runs on |
|---|---|---|
| `sleep` tool | Inside a transition's LLM tool loop | Agent's run-loop vthread |
| `mergeRunResult` post-cycle clear | End of a cycle that processed the thread | Same vthread |
| `mergeRunResult` backoff-on-yield | Same cycle if no progress | Same vthread |
| Boot rebuild | Startup | Before any run loop starts |

Intake ops (`agent:request`, `agent:chat`, `agent:message`) write
`pending`/`tasks` entries, *not* `wakeTime`. New tasks with no
`wakeTime` are "run ASAP" — handled by `hasWork`, not the scheduler.

**Per-ref concurrency is zero.** Virtual-thread-per-agent means at
most one cycle runs per agent. All in-cycle writers (`sleep`,
`mergeRunResult`) are sequential on that one vthread. The only
cross-thread writer is boot rebuild, which runs before any cycle
starts. No lock needed on `setThreadWakeTime`.

**Clock skew.** STPE uses `System.nanoTime` internally. Wall-clock
NTP jumps do not move the fire target (STPE computes delay in nanos
at schedule time). Acceptable — wake is advisory; the run loop's
`hasWork` + `hasDueScheduledWake` gate reads lattice truth. Wakes
persist across restart; the scheduler uses relative delays from
absolute `wakeTime - now()` at (re)schedule time.

### 7.6 Crash Recovery — One-Pass Rebuild on Boot

The scheduler is in-memory only. The lattice carries authoritative
`wakeTime` on each session/task. On venue startup, after lattice
load and before accepting traffic:

```
for each user in venueState.users():
    for each agent in user.agents():
        for each session with session.wakeTime != null:
            scheduler.schedule(sessionRef, session.wakeTime)
        for each task with task.wakeTime != null:
            scheduler.schedule(taskRef, task.wakeTime)
```

One-time cost, proportional to the count of *scheduled* threads (not
total threads). Past-due wakes land with `delay ≤ 0` and fire
immediately.

Startup ordering in `Engine.start()`:

1. Load lattice.
2. Construct `AgentScheduler` (STPE ready, no futures scheduled yet).
3. Boot rebuild — populate futures from lattice.
4. Start `VenueServer` (accept external traffic).

Between (3) and (4), futures may fire and start run loops. That's
fine — run loops don't require external traffic.

Phantom RUNNING agents (was running when venue stopped) are corrected
by `wakeAgent`'s phantom-recovery path (#64) when the scheduler fires
them. No scheduler-specific recovery logic needed.

**Belt-and-braces reconcile (optional).** A low-frequency pass (e.g.
every 10 minutes) could re-scan the lattice and log any drift between
scheduler handles and lattice `wakeTime`. Not required under §7.5's
single-helper invariant — default off via
`config.scheduler.reconcileIntervalMs`. Added only if a future code
path introduces a bypass write.

### 7.7 Failure Modes — Checked

| Scenario | Outcome |
|---|---|
| Fire races with `cancel(ref)` — future already running | `fire` → `wakeAgent` → gate reads lattice, sees no work / nothing due → returns. Harmless. |
| `schedule` replaces a ref mid-fire | Old fire runs with stale wakeTime; `wakeAgent` gate catches it. New future installed, fires at new time. |
| Crash after lattice write, before `scheduler.schedule` | Boot rebuild restores from lattice. |
| 10K refs due at same `now` | STPE pops all, spawns 10K vthreads rapidly. `runningLoops.compute` dedups per agent. O(1) per fire. |
| Executor task throws | `fire` catches `Throwable`, logs. STPE continues (no silent cancel of other tasks). |
| Venue shutdown | `timer.shutdown()` in `Engine.stop()`. In-flight fires complete on their vthreads. |

### 7.8 Agent-Driven Sleep — `sleep` Harness Tool

Sleep is a **harness tool** in `LLMAgentAdapter`, not a venue op.
Rationale: sleep writes only to the current agent's own session/task
scheduling state. No external caller has a reason to touch another
agent's wake — they'd use `agent:trigger` to wake, or `agent:suspend`
to pause. The venue-op pattern (used by `complete_task` / `fail_task`)
exists to let supervisor agents act on workers; sleep has no such use.

**Tool shape:**

```json
{
  "name": "sleep",
  "description": "Sleep for the given number of milliseconds on the current thread. Other events (chat, message, task, other-thread wakes) still wake the agent normally; this only defers the current thread.",
  "parameters": {
    "type": "object",
    "properties": {
      "ms": { "type": "integer", "minimum": 1, "maximum": 2592000000 }
    },
    "required": ["ms"]
  }
}
```

**Scope resolution.** Sleep writes to whichever scheduling thread the
current cycle is on. Determined from `toolCtx`:

- `toolCtx.taskId != null` → write `tasks[taskId].wakeTime = now + ms`
- else if `toolCtx.sessionId != null` → write `sessions[sid].wakeTime = now + ms`
- else → return error "sleep requires an active session or task"

(When a cycle is picked with *both* taskId and sessionId — a task
attached to a session — task wins. The task is the more specific work
unit; sleeping the task does not block the session from receiving new
messages on a parallel thread.)

**Replace-semantics.** Writes the target directly, overwriting any
pre-existing value (framework backoff, prior sleep). No min-merge.
Explicit intent wins.

**Events still wake.** The scheduler gate and `wakeAgent`'s gate both
read `hasWork` as an independent OR-arm. A chat arriving on the
sleeping session writes pending → `hasWork` fires → cycle runs
regardless of the sleep target. Sleep only prevents *pointless* wakes,
not *meaningful* ones.

**Framework backoff defers to sleep.** On the cycle where `sleep` was
called, the framework does not apply backoff on top. The run loop
tracks a per-cycle `sleepCalled` flag (set by the tool handler); if
true, `mergeRunResult` preserves the sleep value and skips the
backoff write.

**Cycle exit preserves the wake time.** Wake times live on session and task records, not on the agent. The status transition at the end of a cycle only touches the agent's status field, so scheduled wakes on other threads persist naturally. A wake is only cleared by the cycle that actually processes that thread.

**Bounds.** 1 ms ≤ ms ≤ 30 days (2_592_000_000). Lower bound is a
sanity floor (sub-millisecond sleep is not a scheduling target — use
events); the scheduler itself has no tick quantum since it's
event-driven. Upper bound guards LLM arithmetic bugs; agents wanting
indefinite dormancy suspend explicitly.

**Return value.** `{thread: "session"|"task", id: <sid|tid>,
wakeTime: <absolute target>}` — the LLM sees which thread it slept
and when it will wake.

### 7.9 Framework Backoff on Yield

When a cycle yields on a task without progress (no `complete_task`,
no `fail_task`, no state change the framework recognises as
progress), `mergeRunResult` increments `tasks[tid].yieldCount` and
writes `wakeTime = now + backoff(yieldCount)` where backoff is
exponential (e.g. `min(maxBackoff, baseMs * 2^yieldCount)`).

Per-task only. A task's yieldCount does not affect any other task or
session. The backoff applies only if the cycle did **not** call
`sleep` on this task (see §7.8 replace-semantics).

Progress signals that reset yieldCount:

- `complete_task` / `fail_task` on the task (removes the task entirely)
- Transition produced any effect the agent's design considers progress
  — this is adapter-specific and initially conservative (only the
  explicit completion venue ops reset the count)

Policy knobs (venue config, defaults TBD): `baseMs` (e.g. 1000),
`maxBackoffMs` (e.g. 1 hour), `factor` (e.g. 2.0).

### 7.10 Migration from the agent-level wake field

Shape of the change (design level):

- Agent-level wake state is removed. Scheduling moves to per-session and per-task `wakeTime` / `yieldCount` fields.
- The `wakeAgent` gate changes from "force or agent-level shouldWake" to `force || hasWork() || hasDueScheduledWake()`.
- `mergeRunResult` clears wake state on the thread it just processed, applies backoff on yield unless the cycle called `sleep`, and preserves an explicit sleep target otherwise.
- Intake paths are unchanged (they write to pending, not wake).

No back-compat is carried for the old agent-level wake field: it is ignored on read and absent on new writes. The field is optional and LWW replaces the whole record, so agents tolerate the schema change naturally.

Implementation-level details (method names, call sites, test updates) are tracked in `CLAUDE.local.md` under B8.8.

### 7.11 Clean-Exit Handshake

The run loop's clean-exit handshake re-reads state under the lock before exiting to decide whether to start another iteration. In the new model the decision is a single predicate — "is there work to do or a due wake?" — identical to `wakeAgent`'s gate (§9). Using the same predicate at both entry and exit points ensures that a late write (event arriving just as the loop is exiting) never strands work.

### 7.12 Test Matrix

- **Scope.** `sleep` in task cycle writes task.wakeTime; in session
  cycle (no task) writes session.wakeTime; with neither → error.
- **Replace-semantics.** Pre-existing wake value is overwritten by
  sleep (not min-merged).
- **Independence.** Sleep on session A does not affect session B's
  wake state. Backoff on task X does not affect task Y.
- **Event override.** Chat on session A while session A is sleeping
  → immediate wake (hasWork path). Sleep state untouched (it was
  attached to the session, which the cycle now processes and clears).
- **Scheduler fires at target.** `sleep(5000)` → scheduler unparks at
  the target and fires `wakeAgent`; session wakes within a few ms of
  target. Test via injected `TimeSource` + `tickOnce()` to avoid
  real-time flake.
- **Earlier write bumps the wait.** Scheduler parked for `sleep(60_000)`;
  a new `sleep(1000)` call must fire at 1s, not 60s. `schedule()`
  signals the scheduler thread; verified via `tickOnce()` after a
  simulated new-earliest write.
- **Cancel clears the wake.** Processing a thread clears its wakeTime
  via `setThreadWakeTime(..., null)` → scheduler removes it from the
  queue. Ref is not fired again unless re-scheduled.
- **Backoff on yield.** Task yields without progress → yieldCount
  bumps, wakeTime deferred by backoff(yieldCount). Repeat yields
  escalate.
- **Sleep defers backoff.** Task yields but sleep was called → sleep
  value stands; no backoff applied over it.
- **Restart preservation.** Session.wakeTime / task.wakeTime survive
  venue restart; the boot-time rebuild populates the priority queue
  from the lattice; overdue wakes fire on the scheduler's first
  iteration after boot.
- **Phantom recovery.** Crashed-while-RUNNING agent + scheduled wake
  reaches the queue head → wakeAgent recovers to SLEEPING and starts
  fresh loop.
- **Index ↔ lattice reconcile.** Startup rebuild matches lattice
  `wakeTime`s exactly; optional periodic reconcile (§7.6) finds zero
  drift under the single-helper invariant.

### 7.13 Open Questions

- **Sleep with no active thread.** Force-triggered agent with no
  session or task, LLM calls `sleep`: error today per §7.8. Does this
  leave cron-shaped agents without scheduling? Proposal: cron agents
  create a synthetic task (`agent:request` with self as target, or
  framework-created task on template init) that their loop keeps
  alive via backoff. Alternative: a reserved "system" session per
  agent. Defer — see §11.1.

- **Task ownership of sleep.** If a task is nested in a session
  (created via `agent:request` within a session context), does sleep
  on the task also imply sleep on the session? Proposal: no. Task
  and session are independent scheduling threads; sleeping the task
  does not block the session from receiving new messages. The LLM
  chooses whichever thread it wants to defer.

- **Backoff reset criteria.** §7.9 proposes conservative reset
  (only on explicit completion). This may be too strict — a task
  that makes partial progress but doesn't complete would accumulate
  backoff forever. Alternative: any observable state change on the
  task's side (agent wrote to `w/` keyed by taskId, etc.). Initial
  implementation: conservative; revisit with real agent behaviour.

- **Starvation under sustained load.** 10K sessions all due
  simultaneously → scheduler fires all, run loops serialise per-agent,
  total throughput bounded by virtual-thread pool. Acceptable for
  v1; revisit if observed.

---

## 8. Path 4 — Async Completion (Planned)

**Shape unchanged from prior draft**, but updated to use per-session
state explicitly:

Agent transition returns an optional `awaits` field in its result map:

```
awaits: [ {jobId, sessionId, tag?} ]
```

Framework writes each entry to the agent-level `pending` Index
(separate from session-level pending — see note at top of doc) and
attaches a completion hook on the named Job:

```java
job.getResultFuture().whenComplete((result, err) -> {
    AgentState agent = ...;
    ACell env = Maps.of(
        Fields.JOB_ID, jobId,
        Fields.STATUS, job.getStatus(),
        Fields.RESULT, result,
        Fields.TAG, tag);
    agent.appendSessionPending(sessionId, env);   // reuses Path 2 shape
    agent.removePending(jobId);
    wakeAgent(agentId, ctx, false);
});
```

Agent sees the completion on the next cycle as a normal
`session.pending` envelope — reuses S3c consumption, no new read path.

**`sessionId` is required** on every await. Agents without a logical
conversation context should use a synthetic "default" session (see
§11.1). Removes ambiguity about where the completion lands.

**Restart.** Hooks are in-memory. On boot, `JobManager` scans each
user's agent-level `pending` Index; for entries whose Jobs are already
finalised on lattice, replay the hook synthetically; for STARTED
jobs, re-attach the hook.

**Cross-venue awaits** (federated `grid:invoke` returning a remote
Job ID): out of scope for Path 4 v1. Local Job completion hooks only.
Remote venues publishing completion notifications is Phase E
federation work.

---

## 9. `wakeAgent` Invariants

1. **Atomic launcher CAS.** `runningLoops.compute(agentId, ...)` either
   installs a fresh `CompletableFuture` (and the caller starts the
   virtual thread) or returns the existing live future (and the caller
   attaches). No shared lock on the hot path.

2. **Phantom RUNNING recovery.** No entry in `runningLoops` + lattice
   shows RUNNING → correct to SLEEPING before installing a fresh slot.

3. **Gate on non-force.**
   `force || hasWork() || hasDueScheduledWake()` must be true, or no
   loop starts. `hasWork` covers events; `hasDueScheduledWake` covers
   session/task timers.

4. **Dedup via launcher slot.** Concurrent `wakeAgent` calls (scheduler
   + intake + trigger) all observe the same `CompletableFuture<ACell>`
   — the first to install wins, the rest attach.

5. **No wake flag on lattice.** No agent-level wake field to set.
   Events carry themselves via pending writes (picked up by the next
   iteration's top-of-loop `hasWork` check, or by the run loop's
   post-exit `finally` re-check); scheduled wakes are held in the
   scheduler's priority queue, which fires `wakeAgent` at the target
   time.

---

## 10. Summary

- Execution is per-agent single-threaded; scheduling is per-session
  and per-task. These are independent design axes.
- No agent-level wake field. Sessions and tasks both carry
  `wakeTime` and `yieldCount` under identical field names —
  "threads" from the scheduler's point of view. Replace-semantics
  for both.
- Events bypass wake times via `hasWork`.
- Scheduler is an in-memory priority queue keyed by `wakeTime`,
  event-driven (no polling). Updated atomically by the single
  `setThreadWakeTime` helper that also writes the lattice field.
  Rebuilt on boot from the lattice.
- `sleep` is a harness tool (not a venue op), scoped to the active
  thread from `toolCtx`.
- Framework backoff defers to explicit sleep on the same cycle.
- Path 4 async completion unchanged in shape — writes to
  session.pending and uses the event path.

Path 3 is ready to implement with this model. §7.13 open questions
are non-blocking for v1. Path 4 v1 targets local hooks only.

---

## 11. Further Improvements (Post-B8.8)

### 11.1 Default session / task for scheduling-only agents

Cron-shaped agents (no human in the loop, no task from an external
caller) have no natural session or task to schedule. Two options:

- **Synthetic task on template.** `template:cron` or similar creates
  one persistent task at agent init; the task's wakeTime drives
  scheduling. Clean but requires template support.
- **Reserved system session.** Every agent has an implicit
  `"system"` session created on first scheduled wake need.
  More uniform but hidden behaviour.

Decision deferred to actual cron use case.

### 11.2 Uniform return-value convention for scheduling intent

Adapters other than LLMAgentAdapter (e.g. GoalTreeAdapter,
hand-written custom adapters) cannot emit tool calls. Give them an
equivalent: the transition result map accepts

```
{ response: ..., state: ..., sleepMs: <n>, awaits: [...] }
```

`sleepMs` is interpreted the same way the sleep tool is — scoped to
the active thread via the transition's ctx. LLMAgentAdapter
populates this field from tool calls or leaves it empty;
non-LLM adapters return it directly. Unifies the scheduling
surface across adapter types.

### 11.3 Observability

`agent:info` response should include each session's `wakeTime`,
each task's `wakeTime` + `yieldCount`, and the next overall wake
(min of all). Operators need this to debug stuck agents and
LLMs themselves can see their own scheduling state if the
framework surfaces it in the transition input.

### 11.4 Configurable backoff policy

Venue config keys for backoff tuning:

- `scheduler.backoff.baseMs` (default 1000)
- `scheduler.backoff.maxMs` (default 3600000 — 1 hour)
- `scheduler.backoff.factor` (default 2.0)

Operators tune per workload. Default conservative; aggressive
workloads may want shorter retry cycles.

### 11.5 Cancel / reschedule

`sleep(0)` to clear the current thread's wake (resume at next
opportunity). Low priority — agents can just yield normally. Add if
real use case emerges.

### 11.6 Fairness across sessions

If many sessions are simultaneously due, the loop picks in
iteration order (currently the Index's natural order). Could
prefer oldest-due-first. Not observed as a problem; revisit if
agents stall on specific sessions while others fire.

### 11.7 Scheduler testability

`AgentScheduler` exposes a `tickOnce()` method for tests — synchronously
drains all entries at or before `now` without involving the scheduler's
own thread. Tests control time via a `TimeSource` abstraction (wall-clock
default, controllable for tests); `tickOnce()` uses the injected time
source so tests can fast-forward without real sleep. Removes real-time
flakiness.

### 11.8 Yield-count reset criteria refinement

§7.9 notes that conservative reset (only on task completion) may
over-penalise partial progress. Future work: define "progress"
more precisely (state-hash change? specific field writes?
agent-declared checkpoint tool?). Requires real agent behaviour
data to evaluate.

### 11.9 Task / Session Unification — "Threads"

**Observation.** Once B8.8 aligns `wakeTime` and `yieldCount` across
both, the remaining differences between tasks and sessions are:

| Axis | Session | Task |
|---|---|---|
| Lifecycle | open-ended | terminates on `complete_task` / `fail_task` |
| Caller's Job awaits... | next assistant turn (chat slot) | terminal completion |
| Participants | multi-party (`parties` vector) | single caller |
| Incoming | stream (`pending`) | single `input` |
| History | explicit (`history`) | implicit (input + output) |
| Per-entity state | `c/` | conventional `w/` keyed by taskId |

None of these differences are fundamental — tasks are effectively
"sessions with one party and a terminal completion contract."

**Proposed unification.** Consolidate both into a single `threads`
Index on the agent. Each thread carries:

```
{
  id:      Blob,                   // session sid or task Job ID
  parties: AVector<AString>,       // DIDs/identities
  pending: AVector<envelope>,      // incoming (chat/message/completion envelopes)
  history: AVector<turn>,          // S3 turn shape
  c:       AMap,                   // per-thread state
  wakeTime: CVMLong?,              // scheduling
  yieldCount: CVMLong?,            // backoff
  meta:    AMap,                   // created, turns, kind
  awaiters: AMap {                 // Jobs awaiting this thread
    turn:       [Job IDs],         //   completed on next assistant turn (chat)
    completion: [Job IDs]          //   completed on explicit terminal op (task)
  }
}
```

A task-like thread is one with a `completion` awaiter. A chat-like
thread is one with a `turn` awaiter. The distinction becomes
per-Job, not per-thread — a single thread could host both kinds of
awaiters over its lifetime (callers chatting and requesting on the
same conversation).

**Benefits.**

- Single `pickReadyThread()` in the run loop.
- Single scheduler scan pass.
- `sleep` has one unambiguous scope (current thread).
- Async completion envelopes (Path 4) land in the originating
  thread's `pending` — uniform with events.
- `complete_thread` / `fail_thread` unify `complete_task` /
  `fail_task`; chat slot mechanism becomes a special case of
  turn-awaiter release.
- Adapters read one shape regardless of whether the work came from
  chat, message, or request.

**Costs / risks.**

- Large refactor: AgentState shape, intake ops, run loop picker,
  completion ops, chat-slot mechanism, all tests.
- S2.8 chat slot is atomic reservation per session — must translate
  to per-thread turn-awaiter list with analogous atomicity.
- S2.7c deferred-completion ordering invariant must survive the
  refactor.
- Intake ops' external contract (chat vs message vs request) is
  user-facing; internal unification must not leak as API churn.

**Recommendation.** Not in B8.8 scope. Deserves its own design doc
and phased migration. B8.8's cosmetic alignment (shared
`wakeTime` / `yieldCount` names, common accessor) is a down payment
that makes the full unification cheaper when it happens.
