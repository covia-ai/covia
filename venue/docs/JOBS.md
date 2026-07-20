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
- Best-effort: side effects already produced are not undone.

## Pause and resume — adapter opt-in

- `AAdapter.pause`/`resume` **default to throwing**: changing only the
  status while work continues underneath is not a pause. Adapters that can
  genuinely suspend override both (surfaced as HTTP 409 otherwise).
- `Job.pause()` is `STARTED`-only; `resume()` accepts the whole paused
  family.
- Resume **never re-invokes the operation from its stored input** — that
  would duplicate non-idempotent side effects and lose request authority.
  Resumption continues from adapter-owned suspended state.
- `INPUT_REQUIRED`/`AUTH_REQUIRED` jobs are advanced by **message
  delivery**, not the resume endpoint.

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
- **Private jobs** (#192, `private: true` + `enablePrivateJobs`) are never
  persisted: memory-only, no recovery, gone on restart.
- `VenueJob.completeWith` runs output-schema validation first; in strict
  mode a violation fails the job instead of completing it. This covers every
  completion path, including job-aware adapter overrides.

## Recovery on restart (#214)

Recovery **stabilises, never re-executes** — a venue restart leaves every job
in a stable, honest state so callers can resume, cancel, or retry as they wish.
Re-execution would double side effects for non-idempotent ops
(`convex:transact`, `http:post`, …), so nothing is ever re-fired.

| State at crash | At boot | Caller's move |
|----------------|---------|---------------|
| `PENDING` | `FAILED` — "restarted before execution began" | retry |
| `STARTED` (most ops) | `FAILED` — "effects may or may not have applied; verify before retrying" | verify, then retry |
| `STARTED` `agent:chat` | `FAILED` — session intact; the record's `sessionId` names the conversation | re-send into the same session |
| `STARTED` `agent:request`, task still queued | restored, stays `STARTED` — the durable task drives completion | keep polling by ID |
| `STARTED` `agent:request`, task gone | `FAILED` — "task concluded; check the agent timeline" | inspect / retry |
| `PAUSED` / `INPUT_REQUIRED` / `AUTH_REQUIRED` | restored live | continue as before |

Restored non-terminal jobs **re-occupy their caller's concurrency-cap
permit** (`JobSemaphore.reserveRecovered`, which may drive permits negative):
after a restart the cap still holds, and new work admits only as restored
jobs finish.

Agent-side work (pending session envelopes, queued tasks, interrupted
`inCycle` cycles) is all durable and resumes independently via the boot scan
(`AgentAdapter.wakeAgentsWithWork`).

## Admission

- **Shutdown gate**: `Engine.close()` calls `JobManager.beginShutdown()`
  *before* stopping the scheduler — `invokeOperation` then throws and
  `invokeInternal` returns a failed future, so nothing (including a racing
  timer) can submit fresh work after the final persistence barrier.
- **Per-caller concurrency cap**: see `venue/CLAUDE.md` § Rate limiting.
  Sub-jobs (carrying a parent job id) are exempt.

## invokeOperation vs invokeInternal

Two dispatch paths with **identical trust, capability, defaults, and gate
handling** — they differ only in Job creation:

- `invokeOperation` — creates and persists a tracked Job (the caller-facing
  accountability unit).
- `invokeInternal` — zero-Job dispatch for framework composition: agent
  transitions, LLM calls, tool calls, capability gates. Returns the
  adapter's future directly (so cancellation propagates to the executor).
