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
String getErrorCode();                     // optional producer-specific code, or null
CompletableFuture<ACell> future();         // lazy result future
ACell awaitResult();                       // block until terminal
ACell awaitResult(long timeoutMs);         // bounded caller-side wait
void cancel();                             // request cancellation
boolean isFinished();                      // terminal?
boolean isPaused();                        // paused family?
```

`Job` is concrete and serves as the remote/read-only handle (constructed
from a record snapshot). `VenueJob` is the execution handle for a recorded venue
job; its data comes directly from the job's `JobRecord` lattice component, which
admission (`Jobs.create`) or recovery hands it.
`JobManager` owns execution, recovery and admission. Transient handles and remote
client snapshots retain local backing records. Acceptance writes a recorded job's
row before it registers the live handle; a lookup by id that arrives in between
waits for that job's acceptance alone, so a job a caller has just listed is always
reachable by cancel, pause, resume and message delivery. The row's transaction
refuses a deleted account, and the account is checked again once the handle is
registered, so a job accepted across a deletion is cancelled and refused.

## Canonical operation and tool surface

Jobs are a universal Covia abstraction, independent of the adapter performing
the work. The `job` adapter exposes this contract; it does not own execution,
invent a second lifecycle, or acquire authority over the jobs it addresses.
The hosting venue checks access and routes each control to the implementation
responsible for that job. Grid handles operation invocation and federation.

| Operation | MCP tool | Result |
|---|---|---|
| `job:status` (`v/ops/job/status`) | `job_status` | Current Job record, including progress, result/error and any delegation observations |
| `job:result` (`v/ops/job/result`) | `job_result` | Exact output value when the target completes; failure if it fails, is rejected or is cancelled |
| `job:cancel` (`v/ops/job/cancel`) | `job_cancel` | Acceptance acknowledgement; existing terminal outcomes are unchanged |
| `job:pause` (`v/ops/job/pause`) | `job_pause` | Acceptance acknowledgement after an implementation-supported pause |
| `job:resume` (`v/ops/job/resume`) | `job_resume` | Acceptance acknowledgement after an implementation-supported resumption |
| `job:message` (`v/ops/job/message`) | `job_message` | `{id, delivered: true}` after the receiving adapter accepts the message |

All operations require `id`, the exact Job ID returned by its invocation.
IDs are venue-scoped: omit `venue` for a local Job, or supply its hosting
venue's URL or routable DID. An explicit non-string `venue`, including null,
is rejected rather than falling back locally. Custom operation metadata may
set `operation.venue`; an input `venue` takes precedence. Remote
`authenticateAs` uses the same explicit `anonymous` (default), `caller` or
venue-only `venue` connection policy as Grid. Local calls retain the full
caller context. Reads and controls require the Job abilities below;
permission to read a Job does not grant permission to cancel it.

`result` optionally accepts a non-negative integer `timeout` in milliseconds;
omission or zero waits without a deadline. Expiry fails the **wait**, leaving
the target untouched. Cancelling a result-wait Job also leaves the target and
its result future untouched. Remote waits observe an existing job and never
submit it again. The wait remains STARTED while the target needs input; its
`delegation.remoteStatus` reports that state. Send input to the target Job,
not to the result waiter. Unbounded remote waits reattach after restart when
their saved connection permits it; bounded waits end on restart rather than
starting a fresh timeout budget. Local waits are process-bound.

`message` requires a map `message`, forwarded unchanged in the receiving
operation's format, for example `{content: "continue"}`. An acknowledgement
means accepted delivery, not task completion. Pause/resume and message delivery
are optional implementation features: unsupported controls fail explicitly.
No control replays the target's original operation.

For a local proxy Job, use its local ID with no `venue`. To address its remote
destination directly, use the destination ID and hosting venue. Cancellation
is best-effort and does not undo side effects or prove remote cessation.
The returned record retains any available delegation/cancellation observations;
absence of confirmation must not be treated as confirmation.

The default MCP palette includes all six Job tools. The `jobs` skill supplies
them to agents; agent/task skills and templates use the canonical Job paths.
Existing `grid:jobStatus` and `grid:jobResult` paths, tool names and behavior
remain compatible. Existing configured agents and tokens are not rewritten.

The legacy tools remain discoverable for compatibility and describe themselves
as such; new workflows use the `job` operations. They are not interchangeable
aliases: legacy Grid result waits accept negative timeouts as unbounded and
ignore non-integer timeouts, while `job:result` rejects either. An unbounded
remote Grid waiter mirrors the destination's paused/input/auth-required state;
the canonical result waiter stays STARTED and reports it in
`delegation.remoteStatus`. Bounded legacy remote waits use SDK polling, whereas
canonical remote waits use the venue's shared observation and retry policy.
These differences are retained rather than silently changing existing callers.

Tool descriptions carry each tool's name, purpose, arguments and result
guidance. Skills supply workflow advice rather than restating included tools'
descriptions or relying on fixed callable names. Runtime handoff messages
identify the local Job handle and direct callers to their advertised Job tools;
they do not assume a particular tool name is present in a scoped palette.

## Job capabilities

The standard capability catalog is [UCAN.md section 3.2](UCAN.md#32-standard-capability-catalog-can).
All Job APIs use these domain abilities, independently of raw lattice CRUD:

| Action | Ability on `<owner-DID>/j/<id>` |
|---|---|
| Status, result and SSE updates | `job/read` |
| Cancel, pause, resume and delete | `job/manage` |
| Deliver input or append a caller's message | `job/message` |

The siblings imply none of one another; `job` covers all three. Listing and
counting require `job/read` on the owner's `j/` collection, not merely one Job.
`job/read` discloses the whole externally visible record, including input,
output/error and history; it is not a status-only grant. Private execution
checkpoints and credentials are never part of that record.

An unscoped authenticated owner has these rights by default. An agent, scoped
session or MCP token must have the required effective authority: creator
attribution supplies no bypass. Creating or invoking work grants no continuing
Job permissions; its direct invocation response is still returned to the caller.
A separate principal can present the owner's valid UCAN grant without sudo or
impersonation. Expiry and token restrictions apply at use, and result delivery
and SSE updates recheck read permission.

Job tools accept optional `owner` (a DID); REST Job routes accept `?owner=<DID>`.
This locates retained records after live-cache eviction and never changes the
request's identity or scope. Local omission uses the caller's namespace for
retained records; active Jobs can resolve by ID. Remote tools bind the owner
before the request: explicit `owner`, otherwise the hop's authenticated principal
or destination public principal for anonymous access. A mismatched owner cannot
address a different Job that happens to be live. Supply the owner's DID when
sharing a Job reference.

Lifecycle controls return `{id, accepted: true}`. They do not return input,
output, history or a status snapshot. Acceptance is not proof of remote cessation;
use a separate read if authorized. A2A methods that return a full Task require
`job/read` in addition to their action right. Task-specific agent cancellation
and continuation also check `job/manage` and `job/message` respectively; agent
administration and an adapter's internal execution remain separate operations.

### Remote authority and host binding

A direct remote Job action is authorized by its hosting venue using the explicitly
selected authentication mode, credentials and grants. The source authorizes use
of the local operation and enforces its outbound connection and credential-use
policy; it does not additionally require local `job/read`, `job/manage` or
`job/message` grants on the remote Job. Local capability scopes are not exported
as remote execution scopes. A deliberately supplied broad remote credential can
therefore authorize remote actions that the caller could not perform locally.

The same rule applies to Grid run/invoke and remote orchestration steps. The
source still checks `invoke` permission on a dynamically selected operation
reference, including its input-dependent constraints. Being scoped does not
prohibit remote submission. The destination independently checks invocation and
resource permissions under the authenticated remote principal and its grants.
Anonymous mode uses the destination's public policy; it does not borrow an
identity credential. Caller mode needs an explicitly presented, in-date identity
credential for that caller and destination. Venue mode is reserved to the venue
itself. No local scope, grant or tool selection implicitly lends the venue's or
another principal's identity. Credentials stay outside inputs and public Job records.

An owning local proxy binds the destination Job and connection established at
submission. `job/manage` on that local proxy authorizes its supported lifecycle
controls, including the framework's cancellation attempt on that exact remote
execution using the bound connection. The caller needs neither a separate remote
Job grant nor access to the bound credentials. A result waiter only observes an
existing Job: cancelling it ends observation, never the target execution.
Successful submission does not guarantee that a later cancellation is supported
or authorized at the destination. Local cancellation completes independently;
its cancellation evidence reports acceptance, refusal or an unconfirmed outcome.
An asynchronous submission acknowledgement is not a continuing owning proxy:
follow and control the returned destination handle at its hosting venue.

Job IDs are venue-scoped. To grant access to one hosted Job, include the existing
`nb.url` constraint with the hosting venue's canonical base URL:

```json
{"with":"did:example:alice/j/0123456789abcdef", "can":"job/read",
 "nb":{"url":"https://venue.example/"}}
```

The hosting venue enforces this constraint against its configured `baseUrl`
(or its hostname/port-derived base URL). Operators behind a proxy must configure
the canonical base URL; use that address when presenting a host-bound grant.
A grant without this constraint deliberately covers the named resource wherever
hosted. Token audience is the grantee and is not a hosting restriction. A relay
forwards eligible grants to the selected principal; it does not mint a new
credential or grant, or substitute its own base URL for the hosting venue's.

Recovery preserves the bound connection's authentication mode and owner lookup.
Caller identity credentials remain unpersisted, so caller-authenticated proxies
and observations require reattachment after restart. Anonymous observations may
resume under the destination's public policy; the original caller's local scope
is not reconstructed as remote authority. Every remote status request is
independently authorized at the host. Local Job reads and result delivery still
require the caller's current local Job permissions.

### Migration

There is no CRUD fallback. Replace Job API grants of `crud/read` with `job/read`,
and assign `job/manage` and/or `job/message` deliberately in place of old write
rights. Raw lattice reads continue to require CRUD, and framework-managed Job
state remains unwritable by raw CRUD. Public read-only defaults grant no Job API
access; explicitly grant the public principal `job/read` to expose shared Jobs.
Existing agents and tokens are not silently widened. The reader template includes
explicit Job read permission; full/manager templates start with empty caps and
need task-specific grants. The explicitly unrestricted admin template is unchanged.

## Lifecycle and status semantics

`PENDING → STARTED → COMPLETE | FAILED | CANCELLED | REJECTED` (terminal,
**sticky**), plus the paused family `PAUSED`, `INPUT_REQUIRED`,
`AUTH_REQUIRED` (each resumes to `STARTED`). `Status.TIMEOUT` exists as a
constant for protocol interop but is emitted by no code path — there is no
timeout status, by design (see Waiting below).

`INPUT_REQUIRED` and `AUTH_REQUIRED` mean the caller owes the job more input;
`STARTED` means the caller is waiting on the job. So when the far end of a
mirrored interaction asks a question, the job moves to `INPUT_REQUIRED` and
the caller's reply is delivered to it; the question is never the job's result.

What each terminal status means, as the venue actually emits them:

| Status | Meaning |
|--------|---------|
| `COMPLETE` | The op succeeded; `output` carries the result. |
| `FAILED` | The op failed — the `error` message carries the reason. This is the **single failure status** the invoke / agent dispatch path emits: execution errors, schema / invalid-input errors, **and authorisation/capability denials** (`"Capability denied: requires <ability> on <resource>. …"`). An adapter may additionally supply an optional `errorCode` for programmatic handling; the status alone does not classify failures. |
| `CANCELLED` | The caller cancelled the job (`cancel()` / `jobs/{id}/cancel`). |
| `REJECTED` | Reserved for a policy/protocol rejection distinct from an execution failure. Core invoke/agent dispatch **does not emit it today** — it is defined in the lifecycle and used by the A2A protocol mapping (`TASK_STATE_REJECTED` ↔ `REJECTED`). A capability denial is a `FAILED` job with a detailed error string, **not** a `REJECTED` job. |

This is **ruled, not open** (covia#209, closed): denials stay `FAILED` because
nested jobs make a status-level distinction impossible — a child sub-job's
denial surfaces as a `FAILED` parent, so a partial denial status would be wrong
at exactly that boundary. The error message names the withheld resource and
ability for the caller; denials have no error code by default. `REJECTED` is
reserved for the A2A protocol mapping.

Agent task/chat job records additionally carry `tokens: {input, output,
total}` when the cycle's LLM calls reported usage (#217) — provider-measured
counts, stamped before completion so they ride the persisted record. Absence
means "not measured", never zero.

### Optional adapter-specific error codes (#462)

`error` remains the human-readable explanation. `errorCode` is an optional
string whose meaning is defined by the producing adapter, not a universal
Covia error taxonomy. By default there is no code: `job.fail(message)` and
`job.fail(exception)` leave it absent, and the framework never infers one from
message wording or exception type. Adapters opt in with
`job.fail(code, message)`; use an adapter prefix to avoid collisions.

For example, the agent adapter supplies `agent:unknown-session` when chat,
query or context rejects a supplied session that does not exist. A client that
understands that specific contract can choose to start a fresh conversation.
It also supplies `agent:suspended` for a request or chat to a suspended agent
([AGENT_LOOP.md](./AGENT_LOOP.md)). Other agent failures carry no code;
`agent:message` can create a missing session and does not reject it on that
basis.

The code travels with the persisted job record and REST job snapshots.
`Job.getErrorCode()` and `JobFailedException.getErrorCode()` return it, or
`null` when absent. Consumers must treat absent or unfamiliar codes as generic
failures and must not guess from the message. Codes do not change status,
authorise retries, or automatically propagate through wrapper jobs; recovery
remains the application's choice.

## Record fields: `caller`, `owner`, `op`, `adapter` and `parent`

- **`caller`** is the principal that made the call: a user, one of a user's
  agents (`<owner>:g:<agent>`) or a `admin/sudo` delegate (COG-8).
- **`owner`** is the namespace the job belongs to, written only when it
  differs from `caller`: an agent's owner, or the user a delegate acts for.
  The job is stored in that user's `j/`, and ownership, access control and the
  admission quota key on it (`Job.getOwner`, `owner` else `caller`). The owner
  is never derived from a DID; `covia.grid.Attribution` writes and reads both
  fields. A scoped context needs explicit Job authority even for work it created.
- **`op`** is the reference that was invoked, verbatim: `v/ops/json/merge`,
  `o/my-tool`, a DID URL, or a bare hash when the caller pinned a definition
  explicitly (#499). Only an inline definition has no reference, in which
  case the metadata's own hash stands in. Records written before 0.9.9
  always carry the resolved hash; readers must accept both forms. A path is
  a mutable binding. What a job in flight needs from its definition is kept
  when the job is admitted ([The venue's job entries](#the-venues-job-entries)),
  so a later change to the binding does not reach it. Invoke by hash when the
  record must pin the exact definition that ran.
- **`adapter`** is the name of the adapter the venue dispatched to (`covia`,
  `langchain`, `grid`, …), stamped at submission (#520). It is the job-list
  fact a client cannot derive from a hash-valued `op` without resolving each
  record's definition, and it survives that definition becoming unavailable.
  The venue routes a job's messages, shutdown and recovery to this adapter.
  Absent on records written before 0.9.9, whose operation names it instead.
- **`parent`** is the id of the nearest *recorded* job inside whose execution
  this job was dispatched (#500); absent on top-level jobs. Transient
  wrappers never appear in a record, so a recorded grandchild dispatched
  through a transient layer links straight to its recorded ancestor. Only
  the up-link is stored — there is no `root`, no children list and no
  children query; a client reconstructs a tree by tracing `parent` from each
  job it holds. Links are venue- and user-local: a job run on another venue
  through the grid adapter carries none.

## The venue's job entries

A job's record is what its caller sees. What the venue needs to carry an
unfinished job to its end is its own, kept in the venue's `:jobs` region by
job id (`JobIndex`), and no route reads it. Each entry holds:

- **`owner`**: the user whose `j/` holds the record.
- **`rules`**: what the job's operation declared about its data when the job
  was admitted (`OutputRules`): its `secretFields`, hidden in the job's input
  and output on every write, and its output schema, checked when the job
  completes if the operator turns `outputValidation` on. A catalog change
  while the job runs changes neither. Absent when the operation declares
  neither.
- **`context`**: the caller's context, kept when the job's adapter asks
  ([Kept caller context](#kept-caller-context)).
- **`adapterData`**: what the job's adapter keeps to carry it to its end
  (`JobManager.adapterData`), read only by that adapter: how a remote job is
  followed and reached again ([Remote delegation](#remote-delegation)), or
  which backend job serves an external agent's turn. A job held only in
  memory keeps it with its handle instead.

An entry is written with its record when the job is admitted, in one
transaction, and removed in the same transaction as the update that finishes
the job. Deleting the job removes it with the record, and deleting a user
removes the entries of that user's jobs with the account. So an entry exists
exactly while its record is unfinished, and nothing is left to sweep. A job
held only in memory has neither.

## Kept caller context

A job whose work continues for its caller after the request keeps that
caller's context, so that it acts again with the authority it started with
and no more. The adapter asks for it (`JobManager.keepCallerContext`): a
Claude Code session does, so does an agent request naming an `outputPath`,
whose job completes by writing there as its caller (AGENT_TEMPLATES.md), and
so does a `remoteagent` call to an agent on this venue, which follows the
agent's task as its caller (ADAPTERS.md, External agent interactions), and so
does every external agent's backend job, which the agent's harness starts
(UCAN.md §5.4). The job's entry keeps the grant proofs the caller presented, in
the form checked on arrival, the separate authority the request acts with, if
any (`Engine.keptAuthority`: an agent's harness acts with its controller's),
and the request's execution scopes (agent, session, job and task), even when
there are none, so that `JobManager.keepsCallerContext` tells such a job from
one whose adapter never asked. `JobManager.callerContext` rebuilds the caller
from them with `Engine.context(owner, caller, proofs, authority)`, checking
what a live request would: the principal and the kept authority still exist,
a delegate still holds its `admin/sudo` grant, an agent acts under its current
`config.caps`, an agent controller under its own. The kept authority is the
controller the work started with, as it acts now, and the MCP token narrowing
the request, if any, which then narrows it as it stands and to nothing once
it is gone (MCP_TOKENS.md). The record never holds them,
and they go with the entry when the job ends (UCAN.md §4.3).

## The update path — CAS-committed, terminal-sticky

Lifecycle mutations funnel through `Job.commitUpdate` and its atomic backing-record
update. For recorded venue jobs this edits `JobRecord`, not an in-memory copy:

- The **updater and `processUpdate` are side-effect free** and may be retried.
  `JobRecord` binds the standard `updated` timestamp policy; account guards and
  parent timestamp/signing policies apply through its cursor.
- **Hooks run after the primary record commits**: `onUpdate` sends notifications;
  terminal completion also evicts the runtime handle, releases its permit and
  settles the result future. Hooks perform no persistence copy. A losing
  transition has no external effects.
- **Terminal lifecycle states are sticky**. Completion, failure and cancellation
  race for one winner. History and cancellation diagnostics remain independently
  editable after completion, without replaying terminal hooks.
- Each update replaces the current state. A job never embeds or links earlier
  versions under `prev`. Message `history` and scratch data remain ordinary
  fields; version retention and audit are separate concerns.
- Original outputs and typed failures are runtime outcomes. The primary record
  is redacted; `awaitResult()` and `getOutput()` on the executing handle retain
  the original result. Reading `getData()` always reads the primary record while
  it exists, including changes made through other component views.

`JobRecord` supports upstream transactions for data edits. Obtain it from the
transaction's user draft; never invoke runtime `Job` verbs inside a retryable
transaction. Runtime dispatch, notifications and future completion belong after
the enclosing commit. A component commit is local; host flush remains the
separate durability barrier.

## Progress — the work in flight, in the record

- An operation reports what its running work is doing with
  `job.progress(message, data)`: `message` is one line a person can read,
  `data` optional detail for a consumer that knows the operation (Claude
  Code's carries `project`, `toolCalls`, `session`, `model`, `lastTool`,
  `text`). The Job's record holds it as `progress: {message, data?}`.
  `Job.progressMessage` and `Job.progressData` read it however a producer
  wrote it: a bare string is the message, a map without `message` is all
  detail.
- **Current state, not history.** Each record replaces the last in place,
  through the same atomic backing-record update as any other change, and
  reaches the Job's listeners and the venue's job observers (`onProgress`).
  The last progress stays in the finished record.
- **Mirrors copy it.** A Job mirroring another — `ExternalAgents` for an
  external agent's child, `RemoteAgentAdapter` for a remote venue's Job,
  `A2AAdapter` for a remote Task — records status changes as updates and
  copies progress with `setProgress`, so a child's progress replaces the
  parent's in place rather than adding to its history.
- **On the wire.** A2A: a working Task's `status.message` is the Job's
  progress — an agent message with the line as a TextPart and the detail as
  a DataPart — so each change is a `TaskStatusUpdateEvent`; inbound, a
  remote working Task's status message becomes the mirror's progress. MCP:
  a long call's `notifications/progress` carry the message. The agent tap
  relays a tool call's as `tool:progress` (AGENT_LOOP.md §2.6).

## Waiting and polling — no framework timeout

Jobs have **no framework-level timeout**; they may legitimately run or wait
for days, weeks, or months (workflows, HITL, agents). Consequences:

- `awaitResult(timeoutMs)` is a **caller-side** bound. On expiry it throws
  `JobPollingFailedException` (carrying the last-known status) and **does not
  mutate the job**. The caller re-attaches later by job ID.
- `JobFailedException` is thrown only when the job itself actually failed.
  `JobPollingFailedException` (a sibling, not a subclass) means "your view
  stopped; the job is unaffected" — also used by the client SDK
  (`Job.pollingFailed`, raised from `VenueHTTP`) on transport loss.
- Adapters SHOULD bound their own IO (socket timeouts, `llmTimeoutMs` for
  agent LLM calls) — bounding real work is the operation's job, never the
  framework's.

## Cancellation

- `cancel()` marks the job `CANCELLED`. The adapter that runs the job, set
  when the job is created, then ends what the job was linked to, in the same
  transaction (`cancelIn`: a request it opened, a task it queued), and stops
  its running work once that commits (`stop`). Work recorded with `runs` is
  cancelled with interruption, so adapters that submit interruptible work
  (e.g. LangChainAdapter) close in-flight HTTP calls. See
  [ADAPTERS.md](ADAPTERS.md#ending-a-job).
- `cancel(reason)` — `PUT /jobs/{id}/cancel` with a `{"reason"}` body,
  `agent:cancelTask reason`, `VenueHTTP.cancelJob(id, reason)` — makes the
  reason the job's `error`, so a cancelled job reads like any other
  non-completion: status says cancelled, `error` says why. Without a reason
  the error names the job. There is no separate reason field, deliberately:
  one key explains every way a job can end short of completion.
- Best-effort: side effects already produced are not undone.

## Pause and resume — Job verbs, adapter opt-in

- Pause/resume are **`Job` verbs**: `job.pause()` / `job.resume()`. The job's
  adapter opts in by answering `canPause(job)` / `canResume(job)`, and after
  the change commits the job calls its `pause(job)` / `resume(job)`, which
  actually suspend or restart the work.
- A job whose adapter **cannot rejects** pause/resume (`NotSupportedException`
  → HTTP 409): changing only the status while work continues underneath is not
  a pause.
- `Job.pause()` is `STARTED`-only; `resume()` accepts the whole paused family.
- Resume **never re-invokes the operation from its stored input** — that would
  duplicate non-idempotent side effects and lose request authority. Resumption
  continues from the suspended state the adapter holds.
- `INPUT_REQUIRED`/`AUTH_REQUIRED` jobs are advanced by **message delivery**,
  not the resume endpoint, unless their adapter can restart them.

## Message delivery — no per-job queue

`POST /jobs/{id}` → `JobManager.deliverMessage` → `adapter.handleMessage`,
dispatched only when the adapter declares `supportsMultiTurn()`. Delivery is
synchronous: 202 means the adapter took the message, and an adapter that
cannot take it throws rather than dropping it. Delivery to a terminal job is
rejected (409). The base `Job` holds **no message queue**;
buffering, when needed, is the receiving operation's concern (agents queue
inbound messages durably in `session.pending` — see AGENT_SESSIONS.md).

## Persistence, redaction, validation

- Admission creates the job record once, in its owner's jobs (`:user-data/<owner>/j/<id>`),
  with its entry ([The venue's job entries](#the-venues-job-entries)).
  Runtime mutations edit the existing record and hide the job's
  `secretFields` inside the same atomic update, before storage. Both `input`
  and `output` are redacted.
- A read changes nothing: not the record, its timestamps or the user's
  activity.
- History appends use one component operation for both active and completed
  jobs. Late cancellation evidence edits the latest record and cannot overwrite
  independent history or scratch updates.
- Removal cannot be undone by a retained handle. If its row or owning account is
  erased, that handle detaches permanently and may settle outstanding work in
  local memory, preserving the existing deletion behaviour. It never recreates
  storage; public job queries do not expose this detached record. Account
  generation guards also prevent writes into a recreated account.
- Transient Job wrappers used by result-oriented read-only runs, explicit
  private runs, and ordinary internal composition are never persisted:
  memory-only, no recovery, gone when terminal or on restart. A caller that
  stops waiting on one is pointed at a recorded mirror instead
  ([Mirrors](#mirrors)). Public invoke always persists. Explicit private runs
  require the operator's `enablePrivateJobs` opt-in and omit only the Job
  record, not operation side effects.
- `VenueJob.completeWith` first checks the output against the schema the
  job's operation declared when it was admitted; in strict mode a violation
  fails the job instead of completing it. This covers every completion path,
  including job-aware adapter overrides.

## Recovery on restart (#214)

A durable Job is a durable record of an invocation, not a guarantee that its
adapter's execution survives restart. Rebuilding the record and observing its
outcome are separate from resuming computation or external effects. Simple
in-process adapters need no execution checkpointing contract.

Recovery **stabilises, never re-executes** — a venue restart leaves every job
in a stable, honest state so callers can resume, cancel, or retry as they wish.
The framework never re-fires an operation: re-execution would double side
effects for non-idempotent ops (`convex:transact`, `http:post`, …).

The decision is the owning adapter's. At boot, before the venue serves
requests, `JobManager.recoverJobs()` reads the venue's job entries
([The venue's job entries](#the-venues-job-entries)), not every user's
records. It rebuilds each job from its record and entry, registers it, and
calls `AAdapter.recoverJob(job)` on the adapter its record names. The job
carries its record and every verb; whatever state it is in when the hook
returns is the durable truth, and anything still non-terminal is restored
live. For ordinary jobs, the default also applies when the adapter is absent
(module not loaded, boot-disabled). A job that delegates its work to a remote
venue or agent behaves as described under [Remote delegation](#remote-delegation).

| State at crash | Default at boot | Caller's move |
|----------------|-----------------|---------------|
| `PENDING` | `FAILED` — "restarted before execution began" | retry |
| `STARTED` | `FAILED` — "effects may or may not have applied; verify before retrying" | verify, then retry |
| `PAUSED` / `INPUT_REQUIRED` / `AUTH_REQUIRED` | restored live | continue as before |

An adapter overrides `recoverJob` to re-attach to work that continued outside
the process (poll a remote job again, re-arm a timer, re-subscribe to an
agent loop) or to retry an operation *it* knows is idempotent, calling
`super` for the cases it does not handle. The agent adapter keeps queued
request and chat Jobs whose intake is still on the agent — a request
*is* its task, a chat is answered from its session's pending envelope — and
the boot wake completes them; only a `trigger` wait takes the default. HITL
restores the expiry event of an open request that has lost it
(GRID_SCHEDULER.md §8).

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
   finish — is still admitted, but sends nothing new to a remote venue
   ([Remote delegation](#remote-delegation)).
2. **Grace**: in-flight work (`PENDING`/`STARTED`) gets up to
   `shutdown.graceMs` (default 2000) to finish on its own. An upper bound:
   shutdown proceeds the moment nothing is in flight. A job that has just
   finished is in flight until its finish effects end, so a cancellation it
   sends to a remote venue starts before remote requests close.
3. **Suspend**: each job still in flight is handed to its adapter,
   `AAdapter.suspendJob(job)`. In-process execution is bounded and ends with
   the process; a wait on something outside it is state, not a thread. The
   default pauses a job whose adapter can suspend it and cancels any
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

Then requests already sent to remote venues may run until the grace window
ends ([Remote delegation](#remote-delegation)), and the final flush runs,
carrying the suspended states.

## Admission

- **Shutdown gate**: admission closes first in the [shutdown
  sequence](#shutdown), before anything else is stopped.
- **Per-caller concurrency cap**: see `venue/CLAUDE.md` § Rate limiting.
  Sub-jobs (carrying a parent job id) are exempt.

## Remote delegation

Remote `grid:run`, remote `grid:jobResult` without a caller timeout, A2A
sends and `remoteagent` calls delegate their work. The job's record shows its
caller what they can follow of it, under `delegation`:

- **`remoteId`**: the remote job, once the remote side accepted it (for an
  A2A send, the remote Task's id).
- **`remoteStatus`**: the remote job's status as last observed.
- **`notFollowed`**: present only while this venue has stopped following the
  remote job and will not take it up by itself, saying why and what the
  caller can do: it does not know whether the remote side accepted the job
  (`RemoteJobs.ACCEPTANCE_UNKNOWN`), it could not reach the remote job again
  after a restart (`RemoteJobs.UNREACHABLE`), or it restarted without the
  adapter that ran the job (`RemoteJobs.ADAPTER_MISSING`). It goes when the
  venue follows the remote job again.

How the venue follows it is its own, kept with the connection as the
adapter's data in the job's entry
([The venue's job entries](#the-venues-job-entries)): the protocol, the
target, whether the job owns the remote job (`ownsRemote`), and observation
health (`observation`, with the last failure's `observationErrorType` and,
for an expected failure, `observationError`). Local processing stays
`STARTED` while a remote job is queued. Remote interruptions and terminal
outcomes are mirrored; a terminal result and its supporting remote status
are committed atomically before result continuations run.

A `remoteagent:request` job also carries `sessionId`, taken from the
destination's task as it is observed: the destination agent's session, which is
one of this venue's own when the destination is this venue. An agent that
serves its calls through a remote-agent backend is different: its own job names
its Covia session, and the backend job beneath it names the destination's
([AGENT_SESSIONS.md](AGENT_SESSIONS.md#22-external-agent-sessions)).

Transport timeouts, malformed observations, missing remote records, and 5xx
responses do not prove execution failed. They set observation health to
`retrying`; 401/403 observations set `auth-required`, and the venue keeps
trying either way. The failure's type is kept as `observationErrorType` and,
for an expected failure, its reason as `observationError`; both clear when
observation is healthy again. None of this reaches the record. Requests are bounded and
back off with jitter (including HTTP Retry-After), with at most 32 requests
globally and four per target. There is no job lifetime deadline. Interrupted
A2A tasks remain observed, including when resumed directly at the remote peer.

An interrupted A2A mirror is waiting on its caller. A message delivered to the
local job is relayed as a continuation `SendMessage` on the same remote task,
and the job takes the state in the remote response before delivery returns. A
remote refusal (4xx, JSON-RPC error) is a 409 to the deliverer; a transport
failure, 5xx or unreadable reply is a 502, since delivery is then unknown.
Either way the local job is unchanged and observation continues.

Submissions do not require a repeatable key. Grid submits once to `/invoke`;
A2A requests `returnImmediately`. If acceptance cannot be established, the
local job stays non-terminal and the venue stops following it
(`acceptance-unknown`, and `delegation.notFollowed` on the record). It is not
automatically resubmitted, including after restart. A definite protocol rejection can fail
the local submission. Optional receiver-side deduplication is a later protocol
extension, described in [REMOTE_JOBS_DESIGN.md](REMOTE_JOBS_DESIGN.md).

Recorded delegations checkpoint intent before submission and the handle before
observation. Recovery attempts to observe that same handle; it does not resume
the remote adapter's execution. If the remote venue reports `FAILED` because
its adapter could not recover after restart, the local mirror fails too. If
the remote job has disappeared, its outcome remains unknown; a saved ID alone
cannot recover it. What recovery needs to reach the remote job again is
kept as the adapter's data in the job's entry
([The venue's job entries](#the-venues-job-entries)), never on the record:
the connection's mode, with the target's DID and the forwarded grants when
the venue authenticated as itself, or the SecretStore reference an A2A bearer
was read from; never a sign-in credential or a secret's value. The grants
are addressed to this venue, so only its key can present them. A hop that signed in with a credential only its request
presented (`authenticateAs: "caller"`, or an A2A `bearerToken`) is therefore
not resumed: its recovered job fails, naming the remote job and its venue
(`RemoteJobs.CREDENTIAL_NOT_KEPT`). A recovered A2A job reads its bearer again from
its caller's secrets, under the context `JobManager.callerContext` rebuilds
for the job, so a rotated secret is sent as it now stands. A direct A2A
send keeps no context, so not the grants its request presented, while an
external agent's backend job keeps its harness's authority
([Kept caller context](#kept-caller-context)). When its caller can no longer use the
secret (it was deleted, the caller cannot act now, or it is another user's,
used under a grant the request presented), the job fails, naming the remote
task and the agent's address (`A2AAdapter.SECRET_UNREADABLE`). Recovery does not resolve a changed catalog binding or
silently elevate the original authority. Missing authority, an unreachable
remote job or an unavailable adapter leave the remote outcome unresolved: the
job stays non-terminal and the venue stops following it
(`delegation.notFollowed`), until a later restart reaches it again. What was
kept goes with the entry when the job ends.

Cancellation commits locally and propagates through owned local child hooks
without waiting for network IO. Grid runs, A2A sends and remote-agent calls
then attempt backend cancellation once, asynchronously, with their captured
transport authority. A handle arriving after local cancellation still triggers
the attempt. A `grid:jobResult` observer does not own the remote execution:
cancelling that local wait never cancels the remote job. The job's entry
keeps this distinction (`ownsRemote`) for recovery; a job whose entry does
not say it owns the remote job gains no cancellation authority.

The optional `cancellation` record contains `remoteId`, `outcome`, and available
`remoteStatus`, `httpStatus` or protocol `errorCode` evidence:

- `requested`: the attempt started; no response has established its outcome.
- `acknowledged`: the peer returned a cancelled task/job.
- `finished`: the peer returned another terminal state (for example, completion
  won the race).
- `rejected`: the peer refused the cancellation request.
- `unknown`: a transport failure, unreadable response or nonterminal response
  did not establish cancellation. A failure records its type as `errorType`
  and, for an expected failure, its reason as `error`.

This evidence can be persisted after local termination through the narrow
`Job.recordCancellation` method. It never changes the terminal status, result
or error, and never repeats terminal cleanup or cancellation hooks. Only an
expected failure's reason is recorded, in the venue's own words; other error
text, response bodies and credentials are not. A missing cancellation
record is not confirmation that remote work stopped. If acceptance is lost and
no remote ID arrives, no cancellation can be addressed. There is no confirmation
polling or automatic retry; a process crash can leave an attempt unresolved.
Even an acknowledgement cannot undo effects already performed remotely.

Shutdown suspends observation and fences late callbacks, without claiming
remote cancellation. Once `Engine.close()` begins, the venue sends nothing new
to another venue or remote agent: a new remote job (a Grid run or invoke, an
A2A send, a remote agent request), a message to one, or a write there fails
with `ShuttingDownException` before any contact. Status checks and
cancellations still go out during the shutdown grace window, the jobs' window
counted from the start of close, so a remote job that finishes in it completes
here. A request still running when the window ends is given up on: its answer
is ignored, and a cancellation keeps `requested`. Close does not recall a
request already sent. After the window no request starts: a cancellation then
sends nothing and records `unknown` with the reason. Transient/private jobs
retain their existing recording policy: they can observe remote work while
the process lives, but have no restart recovery guarantee. Use `/invoke` when
a durable local record is needed. Neither `/invoke` nor a saved remote handle
guarantees that the executing adapter can survive its own venue restarting.

## invokeOperation, runOperation, and invokeInternal

All dispatch paths preserve the caller's authority, apply defaults and arm
capability gates. Every operation enforces its resource authority, whether its
Job is durable or transient. Generic reference invocation additionally checks
`invoke` on the requested operation location; a bound tool or framework call
does not need that lookup permission. Job creation alone confers no authority.

The dispatch paths differ in audience, lookup permission, admission and
whether the Job is durable:

- `invokeOperation` — creates and persists a tracked Job (the caller-facing
  accountability unit), even for read-only operations.
  Its `submitOperation` variant, used by HTTP `/invoke`, checkpoints the handle
  and dispatches execution on a virtual thread before returning the handle;
  local `invokeOperation` retains its existing immediate-start behavior.
- `runOperation` — public result-oriented reference invocation, with the same
  location-scoped `invoke` check as `invokeOperation`. Returns the operation
  result rather than a Job handle and applies normal top-level admission.
- `callOperation` — a trusted endpoint or tool binding with a durable Job.
  It retains resource checks without an arbitrary-lookup permission check.
  Metadata-direct dispatch likewise already has a selected definition.
- `runToolOperation` — a call to a tool binding selected by the serving
  registry, not an arbitrary reference chosen in the call's input. No blanket
  `invoke` check; all action-specific checks still apply.
- `invokeReference` — the agent loop's raw-reference fallback, with an
  `invoke` check and internal composition's observation and recording rules.
- `invokeInternal` — trusted in-process framework composition for agent transitions,
  LLM calls, tool calls, and capability gates. Returns the operation result
  future and is exempt from top-level admission.

`runOperation` uses a transient, non-persisted Job when the operation declares
`operation.readOnly: true`, or when the caller explicitly requests a private
run and the venue enables private jobs. `invokeInternal` is transient by
default; its caller can ask for the Job to be recorded, as an agent with
`recordToolCalls` does for its tool calls. `operation.internal: false` forces
an ordinary result-oriented Job to be durable, as does
`recordReadOnlyOperations: true` for classified reads; an explicit
operator-enabled private run overrides those recording hints.

## Mirrors

A caller that stops waiting on a transient call (an agent's tool call past
`toolCallTimeoutMs`, an MCP tool call past its ceiling) is given the id of a
recorded **mirror** of it (`JobManager.mirror`): a job that `grid_job_status`
and `grid_job_result` find, and that outlasts a restart. A call that is
already recorded is its own job and needs none.

- The mirror looks like the call. Its record copies `op`, `input` (secrets
  hidden), `name`, `adapter`, `caller`, `owner`, `parent` and `created`, and
  `mirrorOf` names the transient call.
- It is `STARTED` until the call ends, then takes the call's `status`,
  `output` and `error`. Cancelling the mirror cancels the call with the same
  reason, and cancelling the call settles the mirror.
- No adapter runs it. It holds no admission permit and takes no messages,
  pause or resume. Shutdown cancels it, which cancels the call; a restart
  fails it as interrupted (the default in the table above), since the call
  ran only in memory.
- Direct and private runs are never mirrored: their caller asked for no record.
