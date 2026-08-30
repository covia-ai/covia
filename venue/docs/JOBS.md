# Jobs — Implementation Semantics

The venue-side contract for Jobs as implemented. The protocol-level
specification is **COG-8: Jobs** (covia-docs); this document is the
implementation companion: what the code guarantees, where, and why.
The REST surface is tabulated in `venue/CLAUDE.md` (API Endpoints) and is
not duplicated here.

## Core contract

A `Job` (covia-core, `covia.grid.Job`) is a handle to a request for an
operation:

```java
Blob getID();                              // unique identity
AString getStatus();                       // current status (snapshot)
AMap<AString, ACell> getData();            // current record snapshot
CompletableFuture<ACell> future();         // lazy result future
ACell awaitResult();                       // block until terminal
ACell awaitResult(long timeoutMs);         // bounded caller-side wait
void cancel();                             // request cancellation
boolean isFinished();                      // terminal?
boolean isPaused();                        // paused family?
```

`Job` is concrete and serves as the remote/read-only handle (constructed
from a record snapshot). Venue-side machinery lives in `VenueJob`
(persistence, redaction, output validation); there are no other subclasses.

## Lifecycle and status semantics

`PENDING → STARTED → COMPLETE | FAILED | CANCELLED | REJECTED` (terminal,
**sticky**), plus the paused family `PAUSED`, `INPUT_REQUIRED`,
`AUTH_REQUIRED` (each resumes to `STARTED`). `Status.TIMEOUT` exists as a
constant for protocol interop but is emitted by no code path — there is no
timeout status, by design (see Waiting below).

What each terminal status means, as the venue actually emits them:

| Status | Meaning |
|--------|---------|
| `COMPLETE` | The op succeeded; `output` carries the result. |
| `FAILED` | The op failed — the `error` message carries the reason. This is the **single failure status** the invoke / agent dispatch path emits: execution errors, schema / invalid-input errors, **and authorisation/capability denials** (`"Capability denied: requires <ability> on <resource>. …"`). Distinguish the kind of failure by the error string, not the status. |
| `CANCELLED` | The caller cancelled the job (`cancel()` / `jobs/{id}/cancel`). |
| `REJECTED` | Reserved for a policy/protocol rejection distinct from an execution failure. Core invoke/agent dispatch **does not emit it today** — it is defined in the lifecycle and used by the A2A protocol mapping (`TASK_STATE_REJECTED` ↔ `REJECTED`). A capability denial is a `FAILED` job with a detailed error string, **not** a `REJECTED` job. |

This is **ruled, not open** (covia#209, closed): denials stay `FAILED` because
nested jobs make a status-level distinction impossible — a child sub-job's
denial surfaces as a `FAILED` parent, so error introspection is unavoidable and
a partial denial status would be wrong at exactly that boundary. Treat `FAILED`
+ the error string (which names the withheld resource + ability) as the
authoritative signal; `REJECTED` is reserved for the A2A protocol mapping.

Agent task/chat job records additionally carry `tokens: {input, output,
total}` when the cycle's LLM calls reported usage (#217) — provider-measured
counts, stamped before completion so they ride the persisted record. Absence
means "not measured", never zero.

## The update path — CAS-committed, terminal-sticky

All Job mutations funnel through `Job.commitUpdate` (a CAS loop):

- The **updater and `processUpdate` are side-effect free** — they may be
  retried when another thread wins the race. `VenueJob.processUpdate` only
  stamps `updated`.
- **Post-commit hooks observe exactly the committed value**: `onUpdate`
  (persistence + listeners/SSE) and, on a terminal transition, result-future
  completion and `onFinish` (active-cache eviction, permit release). A losing
  update has **no external side effects**.
- **Terminal states are sticky**: once finished, every further mutation
  no-ops. Racing resolutions (completion vs cancellation vs expiry) commit
  exactly one winner.
- Each committed record embeds its predecessor under `prev`, forming the
  state-history chain (COG-8's chain model).

## Waiting and polling — no framework timeout

Jobs have **no framework-level timeout**; they may legitimately run or wait
for days, weeks, or months (workflows, HITL, agents). Consequences:

- `awaitResult(timeoutMs)` is a **caller-side** bound. On expiry it throws
  `JobPollingFailedException` (carrying the last-known status) and **does not
  mutate the job**. The caller re-attaches later by job ID.
- `JobFailedException` is thrown only when the job itself actually failed.
  `JobPollingFailedException` (a sibling, not a subclass) means "your view
  stopped; the job is unaffected" — also used by the client SDK
  (`VenueHTTP.pollingFailed`) on transport loss.
- Adapters SHOULD bound their own IO (socket timeouts, `llmTimeoutMs` for
  agent LLM calls) — bounding real work is the operation's job, never the
  framework's.

## Cancellation

- `cancel()` marks the job `CANCELLED` and runs the registered **cancel
  hook**. The default adapter path wires the hook to `future.cancel(true)`;
  adapters that submit interruptible work (e.g. LangChainAdapter) bridge
  cancellation to a worker-thread interrupt, closing in-flight HTTP calls.
- `cancel(reason)` — `PUT /jobs/{id}/cancel` with a `{"reason"}` body,
  `agent:cancelTask reason`, `VenueHTTP.cancelJob(id, reason)` — makes the
  reason the job's `error`, so a cancelled job reads like any other
  non-completion: status says cancelled, `error` says why. Without a reason
  the error names the job. There is no separate reason field, deliberately:
  one key explains every way a job can end short of completion.
- Best-effort: side effects already produced are not undone.

## Pause and resume — Job verbs, hook opt-in

- Pause/resume are **`Job` verbs**: `job.pause()` / `job.resume()`. There is no
  parallel adapter pause path — an adapter opts in by registering a **pause /
  resume hook** (`Job.setPauseHook`/`setResumeHook`, symmetric to the cancel
  hook) when it starts a suspendable execution. The hook is how the adapter
  actually suspends or restarts its work.
- A job with **no hook rejects** pause/resume (`IllegalStateException` → HTTP
  409): changing only the status while work continues underneath is not a pause.
- `Job.pause()` is `STARTED`-only; `resume()` accepts the whole paused family.
- Resume **never re-invokes the operation from its stored input** — that would
  duplicate non-idempotent side effects and lose request authority. Resumption
  continues from adapter-owned suspended state (the resume hook's closure).
- `INPUT_REQUIRED`/`AUTH_REQUIRED` jobs are advanced by **message delivery**,
  not the resume endpoint (they register no resume hook).

## Message delivery — no per-job queue

`POST /jobs/{id}` → `JobManager.deliverMessage` → `adapter.handleMessage`,
dispatched only when the adapter declares `supportsMultiTurn()`. Delivery to
a terminal job is rejected (409). The base `Job` holds **no message queue**;
buffering, when needed, is the receiving operation's concern (agents queue
inbound messages durably in `session.pending` — see AGENT_SESSIONS.md).

## Persistence, redaction, validation

- `VenueJob.onUpdate` persists the record to the caller's lattice
  (`:user-data/<DID>/:j/<id>`) **post-commit**, applying
  `redactJobSecrets` — both `input` and `output` redacted per the
  operation's `secretFields` — on **every durable write**, since adapters
  may update records after submission.
- Transient Job wrappers used by result-oriented read-only runs and ordinary
  internal composition are never persisted: memory-only, no recovery, gone
  when terminal or on restart. Public invoke always persists.
- `VenueJob.completeWith` runs output-schema validation first; in strict
  mode a violation fails the job instead of completing it. This covers every
  completion path, including job-aware adapter overrides.

## Recovery on restart (#214)

Recovery **stabilises, never re-executes** — a venue restart leaves every job
in a stable, honest state so callers can resume, cancel, or retry as they wish.
The framework never re-fires an operation: re-execution would double side
effects for non-idempotent ops (`convex:transact`, `http:post`, …).

The decision is the owning adapter's. At boot, before the venue serves
requests, `JobManager.recoverJobs()` rebuilds each non-terminal durable job
from its record, registers it, and calls `AAdapter.recoverJob(job)`. The job
carries its record and every verb; whatever state it is in when the hook
returns is the durable truth, and anything still non-terminal is restored
live. The default — also applied when the adapter is absent (module not
loaded, boot-disabled):

| State at crash | Default at boot | Caller's move |
|----------------|-----------------|---------------|
| `PENDING` | `FAILED` — "restarted before execution began" | retry |
| `STARTED` | `FAILED` — "effects may or may not have applied; verify before retrying" | verify, then retry |
| `PAUSED` / `INPUT_REQUIRED` / `AUTH_REQUIRED` | restored live | continue as before |

An adapter overrides `recoverJob` to re-attach to work that continued outside
the process (poll a remote job again, re-arm a timer, re-subscribe to an
agent loop) or to retry an operation *it* knows is idempotent, calling
`super` for the cases it does not handle. Today the agent adapter still takes
the default for `STARTED` agent requests/chats and removes their queued
intake and stale session fence; HITL re-arms its durable expiries after
recovery.

Restored non-terminal jobs **re-occupy their caller's concurrency-cap
permit** (`JobSemaphore.reserveRecovered`, which may drive permits negative):
after a restart the cap still holds, and new work admits only as restored
jobs finish.

After generic Job recovery, AgentAdapter reconciles its own queues: intake for
terminal Jobs is removed, stale execution markers/fences are cleared, and only
remaining durable queued work can start a fresh attempt. `inCycle` never causes
a wake by itself.

## Shutdown

`Engine.close()` runs the job shutdown sequence first, outside the engine
monitor (`JobManager.shutdown`):

1. **Top-level admission closes** (`beginShutdown()`): `invokeOperation`
   throws. Internal composition — the sub-operations in-flight work runs to
   finish — is still admitted.
2. **Grace**: in-flight work (`PENDING`/`STARTED`) gets up to
   `shutdown.graceMs` (default 2000) to finish on its own. An upper bound:
   shutdown proceeds the moment nothing is in flight.
3. **Suspend**: each job still in flight is handed to its adapter,
   `AAdapter.suspendJob(job)`. In-process execution is bounded and ends with
   the process; a wait on something outside it is state, not a thread. The
   default pauses a job whose adapter registered a pause hook and cancels any
   other in-flight job with the reason "Venue shut down"; the paused family
   is left as is. An adapter overrides to record a durable wait and let its
   thread go — whatever it leaves non-terminal comes back through
   `recoverJob` at the next boot.
4. **Admission closes completely** (`closeAdmission()`): `invokeInternal`
   now returns a failed future too, so nothing — including a racing timer —
   submits work after the final persistence barrier.
5. **Release**: a waiter still parked on a surviving job's in-memory future
   gets a `JobPollingFailedException` ("Venue shut down") with no change to
   the record, so no thread outlives the venue on a job's account.

Then the persistence sweep stops and the final flush runs, carrying the
suspended states.

## Admission

- **Shutdown gate**: admission closes first in the [shutdown
  sequence](#shutdown), before anything else is stopped.
- **Per-caller concurrency cap**: see `venue/CLAUDE.md` § Rate limiting.
  Sub-jobs (carrying a parent job id) are exempt.

## invokeOperation, runOperation, and invokeInternal

Three dispatch paths with **identical trust, capability, defaults, and gate
handling**. All execute through a Job; they differ in audience, admission, and
whether the Job is durable:

- `invokeOperation` — creates and persists a tracked Job (the caller-facing
  accountability unit), even for read-only operations.
- `runOperation` — public result-oriented dispatch. Returns the operation
  result rather than a Job handle and applies normal top-level admission.
- `invokeInternal` — in-process framework composition for agent transitions,
  LLM calls, tool calls, and capability gates. Returns the operation result
  future and is exempt from top-level admission.

`runOperation` uses a transient, non-persisted Job only when the operation
declares `operation.readOnly: true`. `invokeInternal` is transient by default.
`operation.internal: false` forces a durable Job on either result-oriented
path; `recordReadOnlyOperations: true` forces read-only Jobs to be durable too.
