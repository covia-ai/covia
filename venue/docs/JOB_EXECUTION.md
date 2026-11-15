# Job execution — adapters that stop, and what bounds execution

**Status:** design, decisions settled (§10), not yet built. Nothing here is implemented beyond what
[JOBS.md](JOBS.md) and [ADAPTERS.md](ADAPTERS.md) describe; when a part is
built, its facts move into those documents and leave this one.

This document gives one model for three things that today are decided in
separate places: what happens to a job whose adapter is not running, what a
waiting job costs, and what limits how much work the venue runs at once.

## 1. Goals

1. **One rule for an adapter that is not running**, whatever the reason
   (disabled, failed to start, stopped for a restart) and whether it stopped
   at runtime or was stopped at boot. The adapter, not the venue, decides
   what its jobs do.
2. **Waiting costs no capacity.** A job waiting for a person, another job, a
   remote venue or its own adapter is limited by nothing and limits nothing.
3. **Limit what is scarce, not jobs.** Nothing counts a user's jobs, waiting
   or running. A scarce resource has a limit of its own, held only while it
   is in use.

## 2. Today

- **Recovery** runs once, at boot, before adapters start
  ([JOBS.md, Recovery](JOBS.md#recovery-on-restart-214)). A running adapter's
  `recoverJob` decides each of its unfinished jobs. A job whose adapter is
  not running, or not registered at all, gets the framework default:
  `PENDING` and `STARTED` fail, the paused family is restored, a job
  following a remote venue stops following and stays unfinished.
- **Suspension** runs once, at venue shutdown
  ([JOBS.md, Shutdown](JOBS.md#shutdown)): each job still in flight is handed
  to its adapter's `suspendJob`, whose default pauses a pausable job and
  cancels the rest.
- **Disable, restart and unload** call `stop()` but leave the adapter's jobs
  alone: in-flight work runs on to the end on the stopped instance, a remote
  job it delegated is still followed (`RemoteJobs` belongs to the venue, not
  the adapter, and mirrors the remote status through the adapter's code), and
  messages to its jobs are refused. A job restored at boot while its adapter is not
  running has no adapter at all, so cancelling it leaves what the adapter
  linked to it (an agent's queued task, an open HITL request) in place.
  Enabling the agent adapter at runtime does not wake agents with queued
  work; only launch does.
- **Concurrency** is bounded per user by unfinished top-level jobs
  (`rateLimit.maxConcurrentJobsPerUser`, default 100): a permit is taken at
  admission and given back only when the job finishes. A job waiting days for
  a person holds its permit for those days, so a user with 100 waiting jobs
  can start nothing. Restored jobs take their permits back at boot, driving
  the count negative. Sub-jobs are exempt.
- **Threads**: job work runs on virtual threads, through
  `AAdapter.VIRTUAL_EXECUTOR` or `Thread.ofVirtual` directly, with no count.
  A child job is started on its parent's thread. Where job code waits is in
  §6.

## 3. Installed and started

An adapter has two phases, and they are separate:

- **Installed**: the venue has the adapter, and the adapter owns its jobs.
  `install(engine)` binds it to the engine and its private state, declares
  its catalog, and readies whatever its job hooks (`recoverJob`, `cancelIn`,
  `suspendJob`) need. It starts no workers, sends nothing outside the venue
  and does no autonomous work. **Every registered adapter is installed**,
  whatever its state: enabled or disabled, and whether it accepted, declined
  or refused its settings. Settings are offered before installation, as
  today, so an adapter that declined or refused them is installed with none
  accepted, and its `install` must not need them. Unloading the adapter, or
  closing the venue, ends this phase.
- **Started**: the adapter is live. The venue sends it new work (its
  operations, and messages, pause and resume for its jobs), its information
  is published (`v/info/adapters/<name>`), and `start()` has started its
  workers. Only an enabled adapter that accepted its settings is started.
  `stop()` ends this phase: disable, restart, a failure, unload, venue close.

| State | Installed | Started | Example |
|-------|-----------|---------|---------|
| running | yes | yes | normal operation |
| failed | yes | no | `start()` threw, or its settings were refused |
| disabled | yes | no | disabled in config or at runtime, or declined its configuration (its prerequisites are missing, such as Python for the `python` adapter) |
| missing | no | no | its module is not loaded, this build has no such adapter, or its `install` threw |

*Not running* means installed but not started: the adapter can still decide
what its jobs need. *Missing* means no code here can; the framework decides
for it. An adapter whose `install` throws is reported as failed, but it
cannot own its jobs, so they are treated as a missing adapter's.

Today the engine ties the phases together: an adapter disabled in config,
declined, or refused at launch is not installed until it is first enabled,
so not started and not installed coincide, and its jobs fall to the
framework. Installation already must not start autonomous work
([ADAPTERS.md](ADAPTERS.md), lifecycle step 4); the one adapter found doing
so is Claude Code, which schedules its idle-session reaper in `install`, and
that moves to `start()` and `stop()`. Otherwise installation does little
beyond binding and the catalog: the `file` adapter loads its roots (creating
the ephemeral `tmp` root when none are configured), and `http` logs an error
if the JVM's name resolver is not the guarded one.

## 4. The rule for an adapter's jobs

**A job belongs to the adapter that runs it, from admission to its end,
whether that adapter is running or not, and what happens to it is that
adapter's policy.** Whether the adapter runs decides one thing only: whether
the venue sends it new work.

| Event | What the venue does with the adapter's jobs |
|-------|---------------------------------------------|
| the adapter stops at runtime (disable, restart, unload, a failure) | nothing: work in flight runs on, on the stopped instance |
| the adapter starts at runtime (enable, restart) | nothing: its jobs are already its own |
| the venue boots | hands each unfinished job to its adapter's `recoverJob`, whether or not that adapter will start, before any adapter starts |
| the venue shuts down | hands each job still in flight to its adapter's `suspendJob`, whether or not that adapter is running (§8) |

So booting with a stopped adapter is the same case as stopping an adapter
with jobs in flight: the adapter holds its jobs and is not running, and its
own code decides what they do. The default recovery fails a job that was
`PENDING` or `STARTED` when the venue went down, since the restart
interrupted it either way, and keeps the paused family; only five adapters
override it, so for every other adapter the outcome at boot is the same
whether it runs or not. The five do on a stopped adapter what they do on a
running one: grid, job and remote agent re-follow a remote job (a disabled
`grid` still polls the remote venue, as it does today after a runtime
disable), the agent adapter keeps an agent's queued task and re-registers a
queued chat, HITL re-arms an open request's expiry. Not yet checked: what
the expiry does when it fires while `hitl` is stopped and its operation is
refused, and what the agent adapter's recovery of an external agent's turn
starts. An adapter whose policy depends on whether it runs
tracks its own `start()` and `stop()`, as the messaging adapters do, and
takes up in `start()` whatever it left until then: the agent adapter wakes
agents with queued work whenever it starts, not only at launch. What its
work in flight does when it finishes, or when it tries to act while the
adapter is stopped, is decided the same way, as a messaging bot already
checks that it is running before it writes or sends.

**The venue sends a stopped adapter no new work.** Its operations, and
messages, pause and resume for its jobs, are refused with
`UnavailableException` (`ADAPTER_NOT_RUNNING`), leaving the job unchanged:

| Verb on a job whose adapter is not running | Result |
|--------------------------------------------|--------|
| read, list, SSE | as for any job |
| cancel | cancels it; the adapter's `cancelIn` ends what it linked to the job, and `stop(job)` its work |
| message, pause, resume | refused; the job is unchanged |

Because every registered adapter is installed (§3), its hooks can run
whether or not it is started, and a boot-disabled adapter is exactly one
disabled at runtime.

**A missing adapter's jobs are decided by the framework at boot**, as today:
`PENDING` and `STARTED` fail, since no code here can run them or say what
their operation permits; a remote job stays unfinished, its record saying it
is not followed until the adapter is back (`RemoteJobs.ADAPTER_MISSING`);
the paused family is kept. A module loaded later does not recover them; the
next boot does, as today.

`recoverJob` can therefore run for an adapter that will not start, and must
not assume otherwise. It is still called once per job per venue run, at
boot, before the venue serves requests.

## 5. Job states

No new status. Each COG-8 status already says what a job is waiting for,
and a job of a stopped adapter is in whichever status its adapter leaves it:

| Status | Waiting for |
|--------|-------------|
| `PENDING` | to be started |
| `STARTED` | its own work: code running, or a sub-job, remote venue or model it is waiting on |
| `PAUSED` | a resume |
| `INPUT_REQUIRED`, `AUTH_REQUIRED` | its caller |

## 6. What bounds execution

### What a running job costs

- **CPU.** Virtual threads already run on a bounded pool: the JVM's carrier
  threads, one per core by default. A virtual thread that blocks on network
  I/O, a future, a lock or a sleep leaves its carrier, so a waiting job uses
  no CPU and blocks no other job. This holds on JDK 24 and later (the venue
  recommends 25), with one exception: a native call (Python through FFM)
  keeps its carrier. On JDK 21 to 23 a thread blocked inside `synchronized`
  keeps its carrier too, and `JobManager.Prepared.start` is synchronized and
  runs the adapter's `invoke`, where `agent:trigger` can wait without bound.
- **Memory.** A job in flight holds its state whether it is computing or
  waiting on a thread. Only a job whose wait is recorded as state (§5's
  paused family, a followed remote job, an agent's queued task) holds none.
- **Scarce outside resources**: model calls (money, provider rate limits),
  database connections, Claude Code processes, outbound requests to other
  venues.

### Where job code waits today

Some waits are already state and hold no thread: a HITL request, an idle
agent, an agent's queued task, a remote job followed by `RemoteJobs` (one
shared timer; a request permit is held for one request only), an HTTP call
(asynchronous client), a Claude Code session between turns. Others block a
virtual thread:

| Wait | Where | How long |
|------|-------|----------|
| orchestration on its steps; each step on its sub-job; each `foreach` item | `Orchestrator` | unbounded: days for a step that asks a person |
| agent run loop on the cycle's transition | `AgentAdapter.executeRunLoop` | unbounded |
| transition on its model call; the model call's network I/O | `AbstractLLMAdapter`, `LangChainAdapter` | 120 s each by default |
| agent tool call on its sub-job; a wave on its calls | `AbstractLLMAdapter`, `ToolCycleEngine` | 300 s, then tracked as state |
| `agent:trigger` with a wait | `AgentAdapter` | unbounded by default |
| capability gate on its gate operation | `JobManager.evaluateGate` | 30 s |
| messaging facade turn on a remote agent | `ConversationRouter` | unbounded |
| MCP tool call | `MCPAdapter` | its request timeout |

None of these uses CPU while it waits; each holds memory, and none survives a
restart.

### Decided: no limit on jobs or threads

- **The venue counts neither jobs nor threads.** The carrier pool bounds
  CPU, and a waiting job leaves it. A limit on jobs in flight deadlocks
  once a job waits for a sub-job it started: the parent holds its place,
  and the child needs one. Today's cap avoids that only by exempting
  sub-jobs, so a job can fan out without bound under it anyway. A count of
  running tasks has the same flaw unless every wait in the table above, and
  every blocking call in module and embedder adapters, gives its place up.
- **Invoking is consuming.** An operator who lets a user invoke operations
  accepts that the user's work uses the venue's capacity. The request rate
  (`rps`, `burst`) is the venue-wide control on how fast a user asks.
- **Scarce resources have limits of their own**, set by the operator where
  the resource is used, such as model calls, and held only while it is in
  use, as `RemoteJobs` holds its request permit for one request. The limits
  that exist stay (remote requests, Claude Code processes, scheduler fires,
  the local speech engine). A new one, model calls among them, is its own
  change, not part of this design.

A wait that holds a thread for days or must survive a restart, such as an
orchestration step that asks a person, becomes state in its own adapter's
change; that too is outside this design.

## 7. Per-user controls

| Control | Today | Decided |
|---------|-------|---------|
| Request rate (`rps`, `burst`) | per caller, 429 before any handler | unchanged |
| Unfinished top-level jobs (`maxConcurrentJobsPerUser`) | 100; blocks then 429; waiting jobs count, sub-jobs do not | removed |

With the cap go its permits, the wait at admission and the 429 it sheds
with, and the permits restored jobs take back at boot. `rateLimit.blockMs`
stays: the authentication in-flight limit uses it. A config that still sets
`maxConcurrentJobsPerUser` is refused under strict configuration, which
rejects unknown `rateLimit` keys; how a venue's existing configs are carried
over is settled when the cap is removed.

## 8. Shutdown

As today:

1. Top-level admission closes.
2. Running work gets `shutdown.graceMs` to finish.
3. Each job still in flight goes to its adapter's `suspendJob`, whether that
   adapter is running or stopped (§4).
4. Admission closes completely; waiters on surviving jobs are released; the
   final flush carries the suspended states.

Step 3 changes what happens today, where only a running adapter's jobs reach
its `suspendJob`: a stopped adapter's jobs get the framework default, which
cancels a `PENDING` or `STARTED` one. An agent request still queued on an
agent adapter disabled at runtime is cancelled that way.

A job that never started cannot be started after a restart unless its full
input and its caller's authority were kept. The record hides secret inputs
and holds no authority; only a job whose adapter asked to keep its caller's
context has the second ([JOBS.md, Kept caller
context](JOBS.md#kept-caller-context)). So a `PENDING` job is still cancelled
at shutdown, or failed at boot after a crash.

## 9. New and changed interfaces

| Interface | Change |
|-----------|--------|
| `AAdapter.recoverJob(job)` | at boot, called for every registered adapter's jobs, whether or not it will start; must not assume the adapter is running |
| `AAdapter.suspendJob(job)` | at shutdown, called for a stopped adapter's jobs too |
| `AAdapter.stop()`, `start()` | contract: stopping does nothing to the adapter's jobs, whose fate is the adapter's policy; `start()` takes up what the adapter left until it runs |
| `AAdapter.install(engine)` | called for every registered adapter as it registers, whatever its state (§3); must not need accepted settings; still starts nothing |
| Engine registration | installs every registered adapter; starts, publishes and dispatches to only an enabled one that accepted its settings |
| An adapter whose `install` throws | reported as failed; its jobs treated as a missing adapter's |
| Job recovery, shutdown and a job's adapter | look the adapter up by registration, started or not (`Engine.getRegisteredAdapter`, which exists for module bookkeeping), instead of `getAdapter`, which returns only running ones |
| Claude Code adapter | its idle-session reaper starts in `start()` and stops in `stop()`, not in `install` |
| Agent adapter `start()` | wakes agents with queued work, whenever it starts |
| `rateLimit.maxConcurrentJobsPerUser` | removed, with its 429 at admission |
| `Status`, `adapter.invoke`, `VIRTUAL_EXECUTOR`, `cancelIn` | unchanged |

## 10. Decisions

1. **What bounds execution**: nothing counts jobs or threads; a scarce
   resource has its own limit, set by the operator (§6).
2. **The per-user job cap**: removed (§7).
3. **An adapter's jobs, running or not**: the adapter's policy throughout.
   Stopping or starting an adapter at runtime does nothing to its jobs; at
   boot every registered adapter recovers its own, whether or not it will
   start (§4). The venue decides only for a missing adapter.
4. **Installed and started are separate phases**: every registered adapter
   is installed, whatever its state; only an enabled one that accepted its
   settings is started (§3).

What decision 3 changes in the venue as it stands: the jobs of an adapter
stopped at boot are recovered by that adapter instead of the framework
default. For most adapters the outcome is the same, since their recovery is
the default: a job interrupted by the restart fails. The five that override
it differ: an agent's queued requests survive a boot with the agent adapter
disabled, and a disabled `grid` follows its remote jobs again.
