# Remote job execution and observation

Status: first delivery implemented, 2026-09-10. Source review: `8c162168de1e60d98b0bfd838c92ca02c9f0cfc0`.
The implemented contract is in [JOBS.md](JOBS.md#remote-delegation). This document
also describes planned extensions; it does not claim they have all shipped.

The first delivery implements unkeyed submit-once, durable handle recovery,
protected saved authority and operation metadata, shared bounded observation,
and conservative acceptance uncertainty. Synchronous adapters and simple
requests remain valid without keys or delegation machinery. Transient/private
jobs retain their existing recording policy. Optional submission keys,
receiver-side deduplication, automatic credential renewal, and SSE observation
remain extensions. Explicit cancellation confirmation is tracked separately.

## Decision

Make remote `grid:run` a durable submission followed by resumable observation of
the accepted remote Job. Apply the same observation lifecycle to A2A tasks. Keep
timeouts on individual network requests and individual callers' waits; remove
deadlines on the delegated operation's lifetime.

The remote venue owns the execution outcome. The local Job owns the delegation
workflow and records what is known about that outcome. A failed observation is
not evidence that execution failed. A future is an in-process waiter or one I/O
attempt; it is never the durable identity of the work.

No implementation can atomically commit state on both venues and send a network
message using a local CAS. The achievable contract is durable intent followed
by repeatable reconciliation, with receiver-side deduplication where supported.
This prevents duplicate submission; it does not promise exactly-once external
side effects inside an operation.

### Durability boundaries

Persistence of a Job record, deduplication of acceptance, recovery of an
observer, and recovery of execution are separate capabilities. None implies
the next. An ordinary synchronous adapter can legitimately lose its running
computation when its host exits. Its recovery hook reconciles the record and
may fail the invocation; it need not implement resumable execution.

The observer resumes attempts to learn the same remote Job's outcome. It
does not promise that the remote work continued during an outage or can be
restarted. After a remote restart, a reported `FAILED`/`CANCELLED` outcome is
mirrored normally. If the peer lost the record as well, the outcome remains
unknown. Neither case authorises automatic re-execution. Any stronger execution
recovery guarantee must come from the particular adapter and backend.

## Evidence at the reviewed baseline

- `GridAdapter.invokeRun` calls `Venue.run`. `VenueHTTP.runRequest` waits on
  `/api/v1/run`, returning output without a remote Job handle. Its default HTTP
  deadline is 120 seconds. The generic adapter bridge turns transport failure
  into a terminal local failure, even when remote processing continues.
- `/api/v1/invoke` already returns a Job record and Location. However, the
  handler calls `JobManager.invokeOperation`, which starts the adapter before
  returning. A synchronous adapter can delay even the supposedly asynchronous
  acknowledgement. There is no submission deduplication contract today.
- `VenueHTTP.invoke` starts SDK background polling with a default 30-second
  caller wait budget. Replacing `run` with `invoke(...).thenCompose(Job::future)`
  alone would therefore retain a lifetime failure path.
- `A2AAdapter.startPoller` fails its local mirror after 30 minutes. It retains
  a remote task ID but has no recovery/suspension override.
- `Job.commitUpdate` provides atomic local state transitions. Its continuation
  runs in a `finally` path even if persistence throws; that is not a physical
  durability barrier authorising an external submission. `Engine.flush()` is
  the existing physical barrier for persistent engines.

Related P2 work, deliberately tracked separately:
[cancellation outcome #511](https://github.com/covia-ai/covia/issues/511),
[interrupted observation #512](https://github.com/covia-ai/covia/issues/512),
[late polling failure #513](https://github.com/covia-ai/covia/issues/513).
The separate restart finding is a dependency of durable delegation: merely
removing the deadlines would leave work orphaned on restart.

## State and invariants

Use one authoritative delegation descriptor inside the local Job's record,
committed with its status/output. Do not maintain another competing execution
status in the scheduler or adapter workspace. Proposed fields:

```text
delegation:
  version
  protocol                       covia | a2a
  target                         verified DID when available; endpoint/tenant
  requestKey, requestDigest      stable identity of this submission
  phase                          prepared | submitting | observing | resolved
  remoteId                       absent until acceptance is known
  remoteStatus, remoteRevision    last authoritative observation, if available
  observedAt                     local observation time
  observation                    healthy | retrying | auth-required |
                                 acceptance-unknown | unavailable
  lastObservationError           bounded, redacted diagnostic
  nextAction, nextAttemptAt       durable description of the next step
  generation                     rejects superseded callbacks
  authRef                        reference to protected credentials, never tokens
```

These are orthogonal facts, not new terminal Job statuses. `STARTED` means the
local delegation workflow has started; it is not proof the remote worker is
currently running. Before acceptance, `remoteStatus` is absent. After acceptance,
retain the last remote status through network outages and mark its observation
as stale. Mirror actual remote pause/input/auth states, but do not claim a remote
pause just because polling is unavailable. Credential failure while polling is
`observation=auth-required`, distinct from the remote task requiring credentials.

The following rules are required:

1. Only a verified remote outcome, or a definite failure of local preparation,
   can settle execution as failed. Local cancellation remains an explicit local
   decision under the existing Job contract; it does not prove remote cessation.
2. Transport timeout, EOF, malformed status, 5xx, rate limiting, and loss of
   credentials cannot manufacture a terminal remote outcome. HTTP 404 after a
   known acceptance may mean retention, routing, or access loss; do not resubmit.
3. A known remote ID is immutable within one delegation. A retry observes that
   ID. An explicit new execution gets a new request key and a new Job.
4. Commit remote identity, validated status, output, and removal of the next
   action together. A terminal record stays sticky; late callbacks cannot reopen
   it. Output must pass the existing `VenueJob` validation contract.
5. No automatic submission replay without a receiver deduplication guarantee.
6. No I/O, future completion, timer registration, or credential mutation inside a
   CAS updater. Only the winning transition can dispatch the next action.

Transport errors belong in the observation diagnostic, not the Job's execution
`error` field. Existing callers can still read `status`; inspectors should show
the freshness and uncertainty of the remote observation alongside it.

## Submission: close the lost-acknowledgement window

For Covia peers, extend `/invoke` with an optional opaque submission key and an
explicitly discoverable durable-deduplication capability. Keep existing clients
compatible. The receiver must:

1. Authenticate and authorise the request, then scope the key to the target
   venue, namespace owner, and acting principal. A key is never an access grant.
2. Atomically create or retrieve one acceptance record containing the key,
   canonical request digest, and allocated Job identity. An existing key returns
   its existing Job; a different semantic request under the key returns 409.
   Revalidate read authority on a replay; refreshed credentials are allowed but
   cannot change the effective principal. Tokens and wait budgets are not part of
   the semantic request digest.
3. Persist the acceptance and prepared Job through a durability barrier before
   dispatching the adapter or acknowledging acceptance. Avoid independent key
   and Job writes with no recoverable relationship. Prefer one acceptance record
   as truth and a rebuildable key index if a cross-record transaction is absent.
4. Return the Job handle promptly and dispatch through the existing prepare/start
   split off the HTTP handler thread. Do not wait for `invokeFuture` to return.
   Dispatch must also proceed if the acknowledgement cannot reach the caller.

On the sender, persist and flush the submission intent before the first send.
After a lost response or a crash, replay the identical key and payload only when
that peer guarantees durable deduplication. Resolve to the existing Job, not a
fresh execution. Preserve the originally accepted operation resolution across
replays, even if a catalog path has changed since acceptance.

The receiver's dispatch is at most one initial start per accepted Job. Recovery
must distinguish a durable accepted-but-not-dispatched record from a claim whose
execution may have begun. Retry the latter only under the operation's own safe
recovery contract; generic deduplication cannot settle side-effect ambiguity.

Retain deduplication tombstones after Job deletion so an old key cannot silently
create new work. A bounded retention policy needs a protocol-enforced replay
horizon: requests outside it must be rejected, including when the old index
entry is absent. The initial implementation should prefer retained tombstones.
An in-process map or a CRDT merge after two replicas have already dispatched is
insufficient. This design assumes one active execution authority per venue
identity; active-active failover requires an exclusive claim/fencing mechanism.

For legacy Covia peers and arbitrary A2A peers, submit once. If acceptance is
ambiguous and no remote ID is known, persist `acceptance-unknown`, stop blind
retries, and expose a reconciliation action. This is an honest limitation, not
a fabricated failure or a promise of automatic recovery. A known ID permits
another observation attempt with valid authority; the peer may no longer retain
the record or be able to recover the underlying execution.

A2A send idempotency is optional; a stable message ID alone is not a guarantee.
Use the peer's supported non-blocking send configuration to obtain a task ID
promptly, respecting the negotiated protocol/SDK version. A2A may also return a
completed message instead of a task; preserve that valid immediate-result path.
See the [A2A operation semantics](https://a2a-protocol.org/latest/specification/#331-idempotency)
and [send configuration](https://a2a-protocol.org/latest/specification/#322-sendmessageconfiguration).

## Observation: bounded attempts, unbounded job lifetime

Use a small shared remote-observation coordinator, with protocol clients doing
one submit or status request per call. Grid uses an explicit submit-without-polling
SDK primitive and `getJobStatus`; A2A uses GetTask. The coordinator does not call
the SDK's bounded `Job.future()` polling wrapper. Existing SDK caller timeouts
remain useful and retain their current meaning.

For each accepted Job, run at most one status request at a time. Bound connection
and request duration; on transient failure schedule a new observation with
capped exponential backoff and jitter, respecting Retry-After. There is no total
elapsed-time or attempt-count threshold that fails execution. A per-peer circuit
breaker may delay requests without changing Job outcome. Bound network
concurrency globally and per peer, and use fair scheduling across Jobs.

The alarm queue is disposable, rebuilt from non-terminal Job descriptors. Waiting
for a retry occupies no thread. Follow the existing Scheduler's alarm/virtual-I/O
pattern, but do not create an operation Job for every status poll. A circuit-open
peer must not accumulate unbounded threads or duplicate timers. Keep the existing
caller Job admission permit semantics; network attempt permits are separate.

Process each response as a state-machine event:

```text
event + expected generation
  -> pure validation/reduction
  -> CAS(status, delegation, output, next action)
  -> persist checkpoint as required
  -> schedule the committed action
  -> one bounded I/O attempt
  -> next event
```

Add an explicit post-commit action seam rather than observing success from a
mutable local variable inside `Job.update`. Store the next action in the same
record as the transition. A crash between commit and timer registration is
repaired by scanning the record. A crash after a poll was sent only repeats a
read. Durable submit dispatch must be gated on successful flush; the generic
`finally` continuation path is unsuitable for that guarantee.

Callbacks carry a lifecycle generation and request sequence. Terminal transition,
shutdown, detachment, and reattachment invalidate old callbacks. Serial polling
avoids overlapping snapshots. If SSE is added later, use a remote revision/event
cursor and snapshot reconciliation; timestamp comparison alone cannot order
updates across clocks. Publish genuine status/health changes, not every unchanged
heartbeat: the `prev` history must not grow on every successful poll forever.
Persist retry scheduling with bounded checkpoint frequency; exact retry timing
need not survive a crash because repeating a status read is safe.

SSE is an optional latency optimisation after polling is correct. Disconnect,
EOF, or subscription expiry triggers snapshot reconciliation, never completion.

## Recovery, authority, and waiting

Grid and A2A override `recoverJob`/`suspendJob` for delegation records. Shutdown
invalidates pending callbacks, stops observations, and preserves the non-terminal
record; it does not cancel remote work. Boot re-arms observation of the same ID,
or reconciles the same submission key. The existing shutdown path can release
in-memory waiters with `JobPollingFailedException` without altering execution.

Keep sensitive replay material in protected adapter-owned state and secrets in
the secret store; only references appear in the Job. A digest is not enough to
replay a request whose input was redacted. Persist any required encrypted replay
bundle before the Job references it and before sending; clean unreferenced
bundles later. Do not persist a raw RequestContext or bearer in Job history.

Pin the selected remote principal, target identity, and authority references.
Renew credentials only through the originally authorised strategy; never fall
back from caller to venue authority, or to another venue after a timeout.
Ephemeral caller proofs can expire: report observation auth-required and allow
an authorised owner to restore credentials. Recovery must not widen capabilities.
Anonymous submissions cannot promise durable cross-restart ownership and safe
deduplication without an explicit server-issued scoped receipt or equivalent
ownership contract. Do not silently authenticate anonymous work as the venue.

Durable delegation requires a recorded local Job. `grid:run` is already mutating,
but internal invocations are transient by default. Make delegated execution opt
into recording (e.g. `operation.internal:false` for the primitive); reject an
explicit private mode when durable recovery is requested. This is an explicit
recording-policy change to document, not an invisible write behind a private Job.
Transient parent workflows do not automatically become restartable merely because
their delegated child survives; parent recovery remains its own contract.

`grid:run` still returns the target operation's output when it eventually settles.
`grid:invoke` still returns submission information; do not silently change it into
a lifetime mirror. Public `/run` and SDK waits may time out independently. Clients
needing a recoverable local handle should invoke `grid:run` through `/invoke`.
No transparent POST replay from the generic HTTP client.

The P2 cancellation issue needs its own follow-up contract. Preserve current local
cancellation semantics meanwhile, explicitly report that remote cessation is
unconfirmed, and retain correlation for reconciliation. Never describe a cancelled
local waiter as proof that the remote operation rolled back or stopped.

## Delivery and validation

1. Introduce the delegation descriptor, protocol-neutral observation loop, and
   recorded-job policy. Replace remote Grid `/run` and the A2A 30-minute lifetime
   failure with bounded status requests. Implement recovery/suspension and an
   explicit acceptance-unknown result for peers without submission guarantees.
2. Add Covia durable keyed acceptance and prompt acknowledgement using
   prepare/start. Enable automatic submission reconciliation only after peer
   capability is established. Keep legacy fallback conservative.
3. Add optional SSE and complete the separately tracked cancellation and
   interrupted-task interaction work. Polling correctness is the baseline.

Required deterministic tests use a fake clock and controllable local peer:

| Scenario | Required invariant |
|---|---|
| Operation lasts beyond 120 seconds / 30 minutes | No local lifetime failure; mirror whatever outcome the peer eventually reports |
| Repeated status timeout, 429, 5xx, disconnect | Non-terminal execution; bounded retry resources |
| Remote accepts; acknowledgement is dropped | Same key returns same Job; one initial dispatch |
| Peer lacks deduplication; acknowledgement is dropped | Acceptance unknown; no automatic second submit |
| Same key with altered input/principal | Conflict or separately scoped request; no authority bypass |
| Crash at each intent/flush/send/ack boundary | Reconciliation or explicit uncertainty; no blind replay |
| Crash after acceptance before dispatch | Receiver obeys its durable dispatch/recovery contract |
| Credential expires or target returns 404 | Observation problem; never new submission or false outcome |
| Old poll arrives after reattach or terminal outcome | Rejected by generation/state checks |
| Output validation fails | Local failure records validation cause and preserves remote completion evidence |
| Caller wait expires / observing engine shuts down | No remote cancellation request; observation may be rebuilt without assuming remote execution survived |
| Executing venue restarts with a non-resumable adapter | Mirror its reported failure/cancellation; do not resubmit |
| Executing venue loses the accepted job record | Outcome unknown; a saved ID does not recreate execution |
| Thousands of jobs on unavailable peers | Bounded I/O, fair alarms, no per-job sleeping threads |
| Unchanged successful polls for days | Bounded observation metadata/history growth |
| Deleted Job replay / receiver restart | Deduplication tombstone still prevents duplicate dispatch |
| A2A task interrupts or returns an immediate message | Correct protocol-specific observation/result behavior |

The acceptance/durability, recording-policy, and authority-renewal choices are
part of the design. Extending timeouts or polling forever on an anonymous future
cannot substitute for them.
