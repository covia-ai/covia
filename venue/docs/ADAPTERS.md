# Adapter System

This is the canonical developer guide and contract for Covia adapters. It
covers how adapters are constructed, configured, published, invoked, given
private state, tested, and packaged. Adapter-specific settings and operations
belong in [CONFIG.md](CONFIG.md); the operation data model is defined in
[OPERATIONS.md](OPERATIONS.md).

## Model

An adapter bridges Covia operations to an execution environment: lattice
state, an LLM, an external protocol, a database, another venue, or any other
backend. An operation asset and its adapter are deliberately separate:

- the catalog path, such as `v/ops/http/get`, is the user-facing reference;
- `operation.adapter`, such as `http:get`, is internal dispatch metadata;
- the engine resolves every operation reference to metadata before dispatch,
  so an adapter never receives null metadata;
- every invocation receives a `RequestContext` and runs through a `Job`.

Adapters extend `AAdapter`. Most implement
`invokeFuture(RequestContext, AMap, ACell)` and return a
`CompletableFuture<ACell>`. Adapters that directly control a multi-turn or
suspendable job override the job-aware `invoke` method instead. Blocking I/O
should run on `AAdapter.VIRTUAL_EXECUTOR`; Covia Jobs have no framework-level
timeout.

Persisting a Job preserves its record, not its running execution. Adapters are
not required to continue work after restart. `recoverJob` reconciles the saved
record with what that adapter can actually recover; it may report failure
instead of restoring execution. A resumable execution needs an adapter-specific
checkpoint or an external operation that still exists. A submission key only
prevents duplicate acceptance; it supplies neither of those capabilities.

Synchronous adapters may return `CompletableFuture.completedFuture(result)`;
they need no delegation record or repeatable request key. HTTP `/invoke`
dispatches execution separately from acknowledgement, so even a synchronous
adapter cannot hold the job-handle response until execution completes.

Remote Grid runs and A2A sends use the engine's `RemoteJobs` observer. An
accepted remote handle survives individual request failures; a lost submission
acknowledgement records uncertainty and is never blindly replayed. See
[JOBS.md](JOBS.md#remote-delegation) for the implemented lifecycle and
[REMOTE_JOBS_DESIGN.md](REMOTE_JOBS_DESIGN.md) for the further protocol design.

The default invocation path claims `PENDING → STARTED` with `Job.start` before
calling `invokeFuture`. Job transitions commit atomically; only the winning
transition triggers its continuation. Start, pause, and resume hooks see the
committed state and may immediately advance it again. Hooks should schedule
their next step promptly. A synchronous exception or JVM error fails the Job
before propagating; late completion cannot replace a terminal state. Typed
failure causes are committed together with the failure record.

Error codes are **optional and adapter-specific**. Ordinary `job.fail(message)`
and `job.fail(exception)` produce no `errorCode`. An adapter may opt in with
`job.fail("adapter:condition", message)` for a condition it explicitly defines
and supports as a stable machine-readable contract. The human-readable `error`
remains available. There is no mandatory taxonomy or inference from prose;
consumers must handle missing and unfamiliar codes as generic failures. See
[JOBS.md](JOBS.md#optional-adapter-specific-error-codes-462) for the job surface
and the agent adapter's `agent:unknown-session` example.

Cancellation hooks run after cancellation commits, including when registered
late, and each registration is claimed once. Hooks must signal promptly:
propagate to owned local children directly,
and dispatch network cancellation asynchronously with
`RemoteJobs.attemptCancellation`. The adapter interprets its backend response;
the helper retains sanitised evidence without changing the local terminal result.
See [JOBS.md](JOBS.md#remote-delegation). The default bridge
cancels the returned `CompletableFuture`; this alone does not interrupt blocking
work. Adapters needing interruption must retain the worker's `Future`, register
their own cancellation hook, and use `completeFromJobFuture` for completion.
Terminal cleanup releases job resources before existing result-future
continuations run.

Capability checks belong at the point of action, before any side effect.
Invoke-class adapters normally begin with `requireInvoke(ctx)` and add
resource-specific checks through the relevant `Engine.require*` method. Do
not infer authority from an operation name or confuse `ctx.getCallerDID()`
(who acted) with `ctx.getUserDID()` (whose namespace the action uses). See
[UCAN.md](UCAN.md) for the authority model.

`engine.venueContext()` is the operator's authority. Act under it only on what
the operator declared: venue configuration, or a record created under
`requireVenueAuthority`. Never use it on a reference taken from operation
input or from a record a user created, such as a secret reference or a
catalog path. Resolve those under the caller's or record owner's context, so
another principal's resource, the venue's included, needs a grant. A null
capability scope covers the caller's own namespace only: `requireAuthority`
on a resource that is not DID-scoped does not establish venue authority.

Read a reference a caller supplied with `engine.resolvePath` or
`engine.resolveAsset`. Both check that the caller may read it: another user's
path needs a grant, and a capability scope must cover the caller's own.
`resolvePathUnchecked` and `resolveAssetUnchecked` are engine-internal: their
context selects whose namespace a bare path means and confers no authority.
Use them only after applying the check the read needs (for example
`asset/read` before an asset lookup) or for state the adapter owns.

## Atomic local session delivery

Messaging adapters can use `ConversationRouter.deliver` with a trusted
`RequestContext.SessionDelivery` participant. Agent intake runs on a native
`VenueState.transact` draft, so the session input, adapter receipt and conversation
mapping and Job admission state publish together. The callback must use only draft-bound components,
perform no I/O, and tolerate retries. Returning false discards the draft as a
duplicate. The engine flushes before waking the agent and returns the chat Job
without waiting for its result. A flush failure propagates to the caller; no
background retry is started and already committed input is not rolled back.
`ConversationRouter.onResponse` observes replies
on the shared virtual executor, handles failed Jobs and skips duplicate deliveries.
Adapter writes use `AdapterState.inVenue(draft)`, preserving the original account
generation even across deletion and recreation. The router holds the binding
lifecycle lock through admission, so stopped/deleted
bindings cannot recreate private session mappings after cleanup.

Telegram, Slack and WhatsApp use this contract for local agent receipts. Discord
uses the same admission/reply separation and atomic session mapping, but its Gateway
intake has no durable inbox or duplicate-message guarantee; a failed delivery is
reported once and never retried.

Workers claim each receipt before media downloads, preprocessing or dispatch.
They make one attempt. A known admission failure records FAILED with a reason
and last error; interrupted STARTED work remains for inspection. Neither is
replayed automatically. Local session admission completes the receipt atomically
with the input, mapping and Job admission state. Later flush or reply failures
must be reported without claiming that committed input was rolled back.

HITL uses a native `Users.transact` transaction to resolve the inbox request,
write the requesting Job's final result, and append the session notification.
Competing resolutions cannot overwrite the winner or append another notification.
Signing happens before the transaction; notifications and wake happen after commit.
A flush error reaches the responder directly; live Job waiters still observe the
committed result. There is no prepared-resolution checkpoint or replay step.
Signed tokens stay in the requester's Job/session, never in the responder's `h/`.
An older resolved request without a saved result fails explicitly rather than
remaining INPUT_REQUIRED or reconstructing a missing grant.
A session notification is independent of the completed HITL Job and survives
restart before wake. If the requesting session is gone, the request still resolves
and the adapter reports the unavailable destination.

This is a local handoff contract, not an exactly-once external-effects claim.
Remote agent facades cannot join the transaction. Keep their existing provider
protocol, and keep preprocessing and outbound delivery outside the transaction.

## Reference-addressed content

Storage adapters implement `ContentProvider` so consumers can resolve and write
content through `Engine` using the provider's reference namespace and authority
checks. `getContent` returns null for unrecognised references; optional
`putContent` and `createContent` return false when unsupported. Recognised
references that fail authorization must throw, never degrade to another provider.

`createContent` is the create-only contract: providers must atomically reserve a
new filename and throw `FileAlreadyExistsException` on conflict. Do not implement
it as an existence check followed by an overwriting write, or fall back to
`putContent`. File and DLFS providers use native `CREATE_NEW`; callers such as
Telegram can retry a different filename without risking existing content.
DID-scoped DLFS references use URL-encoded paths, decoded once before path
normalisation and authorization; relative DLFS paths retain their literal spelling.

## Lifecycle and configuration

The significant lifecycle is:

1. A built-in adapter is constructed, or a module discovers one through
   `ServiceLoader`.
2. A module adapter receives `configureModule(modules[].config, strict)` once.
   It may return `false` when optional runtime prerequisites are unavailable.
3. Registration applies the effective `adapters.<name>` configuration through
   `configure(config, strict)`.
4. Unless boot-disabled or declined, `install(engine)` binds the engine and
   private `AdapterState`, then `installAssets()` declares its catalog.
   Installation must not start inbound workers or other autonomous work.
5. Before the venue serves requests, `recoverJob(job)` is called once per
   non-terminal durable Job the adapter's operations own. The default
   stabilises and never re-executes; override to re-attach or retry
   (see [JOBS.md § Recovery](JOBS.md#recovery-on-restart-214)).
6. `start()` activates adapter-owned workers after assembly, catalogue
   publication, secret provisioning and recovery. It must be idempotent:
   runtime registration and enable also call it after publishing the adapter
   on an already-live engine. A prepared engine defers it until activation.
7. Runtime `adapter/configure` calls `configure` again and republishes public
   configuration and information if accepted.
8. Disable removes live dispatch and public introspection but retains the
   instance and durable catalog metadata. Enable restores it.
9. Module unload deregisters its live adapters and closes `AutoCloseable`
   resources and the module classloader. In-flight jobs retain their adapter
   instance and may finish.
10. At venue shutdown, in-flight Jobs get `shutdown.graceMs` to finish;
   `suspendJob(job)` is then called for each still in flight. The default
   pauses a pausable Job and cancels the rest; override to record a
   durable wait and let the thread go
   (see [JOBS.md § Shutdown](JOBS.md#shutdown)).

The venue config is authoritative again after restart; runtime enable,
disable, configure, load, and unload changes are not persisted. Full operator
semantics and authority requirements are in
[CONFIG.md, “Runtime adapter lifecycle”](CONFIG.md#runtime-adapter-lifecycle).

There are two distinct configuration hooks:

- `configureModule` receives module bootstrap configuration exactly once and
  answers whether that module adapter can run in this JVM.
- `configure` receives the effective adapter configuration: static
  `adapters.<name>` overlaid by runtime reconfiguration. Adapters that read
  `engine.adapterConfig(getName())` lazily need not override it.

An override of `configure` should validate the whole proposed configuration
before changing live fields, throw `IllegalArgumentException` with an
actionable message for malformed known settings, and reject unknown settings
when `strict` is true. Security boundaries and reserved settings must remain
enforced even in lenient mode. Return `false` only to decline a valid but
unavailable configuration. The reserved `enabled` key is owned by the
framework.

Never publish an effective config object wholesale. `publicConfig()` is an
explicit allow-list and defaults to publishing nothing; the helper
`publicConfig("key", ...)` selects named top-level fields. Credentials,
private endpoints, allow-lists, and other operator-private values must not
appear there.

Adapters can implement `covia.venue.auth.AuthenticationMethods` to contribute
named trusted verifiers (`Map<AString, Verifier>`). Each verifier receives the
selected venue method id and transient `ACell` input, returns an `AuthResult`
for that method, or throws `AuthException`. Declarations must be immutable and
stable for the installed adapter instance; use replacement to change them.
Configuration hooks validate before changing verifier behaviour, as for other
adapter settings. See [AUTH.md §3.1](AUTH.md#31-grid-native-data-and-operations).
Their protocol settings belong under the owning adapter's existing configuration,
and their availability follows this lifecycle. `auth.methods` binds venue method
ids to trusted adapter implementations, with a local implementation selector
when needed. A binding cannot load or enable an adapter. Core auth also keeps
shared policy and sessions; implementation lifecycle remains adapter-owned.
Public operation assets alone cannot register a trusted authentication verifier.
Verification runs outside the engine lifecycle monitor. A successful result
is rejected if the adapter registry/configuration revision changed while it
was being verified, including disable/re-enable of the same instance.

## Public catalog and introspection

An active adapter can contribute these public lattice surfaces:

```text
v/ops/<catalog-path>                 canonical operations
v/skills/<name>                     canonical agent skills
v/agents/templates/<name>           canonical agent templates
v/info/adapters/<adapter>            framework and adapter information
v/adapters/<adapter>/
├── info                             adapter information
├── config                           explicitly public configuration only
├── ops/...                          mirror of its operations
├── skills/...                       mirror of its skills
└── templates/...                    mirror of its templates
```

Declare these in `installAssets()`:

```java
@Override
protected void installAssets() {
    installAsset("example/run", "/adapters/example/run.json");
    installSkill("example", "/skills/example.json");
    installAgentTemplate("example", "/agent-templates/example.json");
}
```

Catalog paths are explicit and independent of the dispatch string. Their
segments must match `[a-z][a-z0-9-]*`. Asset resources are loaded through the
adapter's own classloader first, which lets the same helpers work from a
module jar.

### Operation discovery

`AAdapter.getOperations()` returns an immutable
`AMap<AString, AMap<AString, ACell>>`: **adapter-local name → full asset
metadata**. The new `GET /api/v1/adapters/{name}/operations` endpoint returns
this map directly. Schemas, descriptions, defaults and extension fields stay
inside the original metadata; clients need no hash lookup or catalogue tree
traversal. For example, `/api/v1/adapters/skills/operations` returns:

```json
{
  "list": {
    "name": "List skills",
    "operation": {
      "adapter": "skills:list",
      "input": {"type": "object"}
    }
  }
}
```

Declare an operation without a mutable binding with
`installOperation("local-name", metadata)` inside `installAssets()`. Existing
`installAsset(catalogPath, ...)` and `installTestAsset(...)` helpers also
publish operations: the local name is the declared catalogue name with the
adapter's own leading segment removed, if present. For example,
`skills/list` becomes `list` and `venue/adapter/disable` becomes the single
map key `adapter/disable`. These keys are opaque names, not paths to traverse
or invoke. The new `installOperation` helper rejects a conflicting definition
of an existing local name; repeating identical metadata is harmless. Legacy
catalogue helpers retain their existing permissive behavior: if declarations
map to the same local name, the last operation declaration wins in discovery,
matching the adapter-owned mirror's precedence. All original catalogue
bindings remain available. Non-operation assets at legacy catalogue paths
keep their bindings but do not enter the operation metadata map.

Only explicitly published operations enter this map. Skills, agent templates,
model-catalogue entries and example assets keep their separate discovery
surfaces. Mutable operator bindings neither add to nor replace these
declarations. Reading discovery does not rewrite or delete those bindings.
Identical metadata can appear in several lattice locations without creating
independent definitions.

An active adapter with no published operations returns `{}`; an unknown or
disabled adapter returns 404. Publication describes what is offered, not what
a caller is authorised to execute. The Java engine accepts the returned
metadata for invocation; reference-based clients continue to use explicitly
chosen bindings.

This API is additive. `GET /api/v1/operations` still returns its existing
array of named summaries, and `GET /api/v1/operations/{name}` is unchanged.
The `operations` fields in adapter info and the admin listing remain arrays
of catalogue paths from `getOperationPaths()`. `stats.ops` keeps counting
those bindings, so an operation published without a path is discoverable via
the new endpoint but does not increase that legacy count. Existing SDKs do
not need an update; new helpers can opt into the metadata endpoint.

Override `info()` for stable facts a client needs until the next reconfigure,
such as a mount point or enabled protocol feature. Live status, sessions,
connection counts, and mutable health belong behind operations or explicit lattice views, not in
`info()`. Framework-owned fields such as `name`, `description`, `kernel`,
`module`, and `operations` cannot be replaced by adapter information.

Disable and unload retract live dispatch and introspection. Canonical catalog
metadata is durable lattice state and may remain, but it is not invocable
without a live adapter.

## Private state and user-managed storage

There are three separate concerns:

1. **User-managed data.** A user may select any location they can write. An
   operation should accept an ordinary caller-authorised path where this is
   useful, with a sane default such as `w/memory`.
2. **Adapter-owned state.** Each adapter has its own value at
   `:adapters/<name>` within the venue. `adapterState()` returns its lattice
   component; this is not a user workspace or a caller-addressable path.
3. **Secrets.** Secret material remains in `s/`. Adapter records contain only
   `s/NAME` references. Resolve secrets against their destination
   (`engine.resolveSecret(ref, ctx, url)`) so URL-bound `secret/use` grants apply.

`AdapterState` uses upstream `ALatticeComponent` and `TransactionalComponent`.
The value may be any CAD3 cell: there is no mandatory map, user prefix,
workspace envelope or per-adapter merge rule. An adapter owns its schema and
may wrap the component's cursor in its own typed domain components. For simple
map/vector records, `read`, `write` and `delete` offer type-aware relative paths;
`get`, `set`, `update` and `transact` operate on the complete value. `rootPath`
and `path` are diagnostic locations, not workspace API addresses.

```java
AdapterState state = adapterState();
state.transact(draft -> {
    draft.write("settings/theme", Strings.create("dark"));
    draft.write("settings/version", CVMLong.ONE);
});

engine.getVenueState().transact(draft -> {
    draft.adapterState("telegram").write("settings/enabled", CVMBool.TRUE);
    draft.adapterState("slack").write("settings/enabled", CVMBool.FALSE);
    // User, schedule and other domain operations can join this same commit.
});
```

Long-lived user-owned workers must capture `adapterState(ownerDID)` when
created and use that component for their durable state, including configuration
records. Reads, cursor updates and transaction commits are fenced to that
account's generation. Call `requireCurrentUser()` before admitting queued work.
The unbound component is for adapter-wide administration and deletion hooks;
it does not infer ownership from field names. Messaging uses `users/<did>` and
`config/<name>` as its own schema, but neither is a framework requirement.

A venue is one authoritative running application instance. All these components
share its atomic state and transaction boundary. Whole-venue LWW selects complete
snapshots for backup, replication and failover; it does not combine changes from
independently executing forks. Arbitrary workflow invariants and external effects
cannot generally be merged. Failover must fence the previous writer before the
replacement starts producing outputs. No per-adapter publication framework or
independent store is introduced.

Existing `w/adapters/<name>` data in the venue principal is moved on first
access to that adapter component. The exact value is copied and the old key
removed in one venue transaction, including when access happens inside a larger
transaction. No unrelated adapter or user data is scanned on startup. Identical
duplicate values are safe to collapse; conflicting old and new values fail
without changing either copy. There is no generic field merge or dual-write
fallback. Older binaries that write the workspace layout must not be run against
a migrated store. The small legacy `w/user-deletions` queue migrates similarly
to `:user-deletions` in the venue `0 → 1` migration, with an on-access fallback
for transitional records. Venue `:version` describes this compatibility contract;
it does not require an eager rewrite of adapter state. See
[PERSISTENCE.md](PERSISTENCE.md#venue-versions-and-migrations).

### Versioned state and migration

An adapter that changes the shape of its state versions it. A venue holding
older state then has it migrated before the adapter reads it, and state newer
than the adapter understands is refused instead of misread. Built-in adapters,
modules, and adapters an operator or embedder adds all use the same hook.

```java
@Override public long stateVersion() { return 2; }

@Override public ACell migrateState(long version, ACell state) {
    return switch ((int) version) {
        case 0 -> Maps.of("bots", state);                                  // 0 → 1
        case 1 -> RT.ensureMap(state).assoc(Strings.create("limits"), Maps.empty()); // 1 → 2
        default -> super.migrateState(version, state);
    };
}
```

- The venue records the version each adapter's state has reached in its
  `:adapter-versions` region. An adapter that does not override
  `stateVersion()` is at version 0 and records nothing.
- Steps run in order from the recorded version up to `stateVersion()`, and the
  new version is recorded with them. A legacy `w/adapters/<name>` value moves
  first. A venue with no state for the adapter records the version without
  calling `migrateState`.
- At launch, the built-in adapters and every configured module's adapters
  migrate together, after the venue's own steps, in the one transaction that
  opens the venue and before any bootstrap write. A refusal or a failing step in
  any of them discards the whole transaction, so the start fails with the store
  exactly as it was. A module adapter with the same name as a built-in replaces
  it, and that built-in never registers.
- An adapter registered later (in the `beforeStart` hook, on an engine prepared
  by hand, or by loading a module at runtime) migrates in its own transaction as
  it registers.
- Either way, steps run whether the adapter is enabled or disabled, and before
  `configure`, install or any deletion hook sees the state. A step cannot depend
  on the adapter's configuration.
- Each step is a pure function of its arguments: no I/O, no engine calls, no
  clock, no runtime changes. The transaction may run it again, or discard its
  result. It returns the same cell when nothing changes, and throws for a shape
  it does not recognise. The state and version then stay as they were, and the
  start or registration fails.
- State recorded at a newer version than the adapter supports is refused with
  an `UnsupportedVersionException` naming the adapter, including a disabled
  adapter, so the start or registration fails. To run an older build without
  restoring the state from before the upgrade, remove the adapter from the
  configuration; disabling it is not enough.
- A replaced instance keeps running until its replacement has registered. When
  loading a module at runtime that raises its state version, disable the old
  instance first.

### User-visible extension routes

`x/<adapterName>/...` is a virtual route into an active adapter's explicit
view for the addressed user. It does not expose `:adapters/<name>` automatically
or create an `x` field in user storage. Adapters own the shape of the view:
maps, vectors, scalars and pure projections are all supported.

Override `latticeView(ctx)` to return a Convex `AForkableCursor<ACell>`, or null
for no view (the default). For example, an adapter whose private schema stores
public account data under `accounts/<did>` can expose just that subtree:

```java
@Override public AForkableCursor<ACell> latticeView(RequestContext ctx) {
    return adapterState(ctx.getUserDID()).cursor().withDefault(Maps.empty())
        .path(Strings.intern("accounts")).withDefault(Maps.empty())
        .path(ctx.getUserDID());
}
```

Use `ctx.getUserDID()` for the addressed owner; `ctx.getCallerDID()` still
identifies the actor. The owner-bound component retains account deletion and
recreation guards. Keep credentials and internal records outside the exposed
subtree: reading a parent returns its entire value, including descendants.

Execution references live together in `ctx.getExecutionScope()`, qualified by
its `ownerDID`. Delegating the addressed namespace never changes that owner.
The local `getAgentId()/getSessionId()/getJobId()/getTaskId()` helpers return null
when the execution belongs to a different user, so `n/`, `c/` and `t/` cannot
silently target identically named records under the addressed owner. Use the
qualified scope for attribution. Explicitly binding a new local execution ID
under a different user starts a fresh scope without inheriting the old IDs.

Views are read-only by default. Override `latticeWriteDenial(ctx, path)` for
write, append, copy-to and delete at selected paths relative to the view root.
Return `null` to allow, or a refusal reason; the router returns that reason
unchanged. Adapters own value validation in their cursor write transformations.
An empty path addresses the whole view. Allowing a parent write allows replacement
of its entire subtree; deny it if descendants must remain protected. Writable
views must connect to authoritative venue state, retaining its atomic update
and transaction semantics. A cursor over a projected snapshot must stay read-only.

The standard `covia:read/list/slice/inspect/write/append/delete/copy` accessors,
`Engine.resolvePath` and agent context references use the same route. Normal
`crud/read`, `crud/write` and `crud/delete` authority applies in addition to the
adapter policy. `<owner-did>/x/<adapter>/...` addresses a local owner's view
under the usual cross-user delegation rules. Bare paths accept strings or vectors;
DID-qualified addresses use strings.

Hooks and cursor transformations must be local and pure, with no network I/O
or external effects. Update callbacks and policy checks may run again on a
transaction retry. External actions remain adapter operations/jobs. Views are
scoped to one operation/transaction: do not cache them across operations or
reconfiguration. Disabling or unloading
an adapter removes its route; absent views never fall through to user storage.
The bare `x` route has no automatic directory listing; use adapter discovery for
available implementations and their documented paths.

This is a routing contract, not a persisted schema change: existing records and
the venue version are unchanged.

#### Try it with the test adapter

When the `test` adapter is enabled, `x/test` provides:

| Path | Purpose | Access |
|------|---------|--------|
| `x/test/context` | Current `caller`, addressed `user`, `venue`, and `execution` (`owner`, `agentId`, `sessionId`, `jobId`, `taskId`); unset IDs are null | Read-only |
| `x/test/samples` | Unicode/multiline text, a number vector, a nested record, null and empty containers | Read-only |
| `x/test/scratch` | Persistent, per-user scratch data of any value shape | Read/write/delete |

The adapter advertises these paths at `v/info/adapters/test/lattice`. Try the
normal accessors (paths are also available as agent context references):

```text
covia:read    {"path":"x/test/context"}
covia:slice   {"path":"x/test/samples/numbers","offset":2,"limit":3}
covia:copy    {"from":"x/test/samples/record","to":"x/test/scratch/example"}
covia:append  {"path":"x/test/scratch/events","value":{"message":"hello"}}
covia:read    {"path":"x/test/scratch"}
covia:delete  {"path":"x/test/scratch"}
```

Reading diagnostics or fixtures writes nothing. Scratch survives adapter reload
and venue persistence; deleting its owner removes it through the normal adapter
deletion hook. The venue principal gets its own view and scratch, with the same
cross-user delegation rules as other callers. Context shows the actor and owner
separately for delegated reads, and never exposes credentials or proofs. Root
replacement is denied so it cannot overwrite the read-only branches. Scratch is
disposable test data, not included in user exports.

### User deletion hooks

`AAdapter` has two narrowly scoped lifecycle hooks:

- `deleteUserState(did, state)` removes the adapter's user-associated durable state through
  the supplied **transaction-bound** `AdapterState`. It runs atomically with
  the user tombstone, namespace and login-alias removal. Use only that component;
  no live engine writes, runtime changes or I/O. It may run again on a transaction
  conflict. The adapter owns all schema-specific cleanup; the default hook is
  for adapters without user-associated state.
- `onUserDeleted(did, previousUser)` runs after the commit is flushed. Stop
  workers and release external resources here. It must be idempotent and throw
  on incomplete cleanup so the venue can retry. The previous user record is
  available only on initial deletion, never retained for recovery. Tolerate null
  on retry and an instance that was boot-disabled and never installed.

An adapter needing durable external cleanup identifiers must maintain its own
cleanup records outside the data it erases and consume them idempotently in the runtime
hook. Recovery cannot depend on the erased namespace or an in-memory snapshot.

Both hooks apply to disabled adapters. Incomplete work is indexed privately at
`:user-deletions/<adapter>/<did>` within the venue; `_venue` covers core
job/scheduler cleanup. Entries contain only the deletion tombstone, not deleted
payloads. Unloaded adapters retain their state and a pending entry until their
next activation, when both hooks run before workers start. The framework cannot
interpret an unavailable adapter's private schema. Failures leave the account revoked and bar
recreation. Repeat `user:delete` to retry; loading an unavailable adapter also
reconciles its queue. Normal startup checks only these small pending queues,
with no sweep of user or job records. Repeating deletion repairs adapter data
left by tombstones from older venues as well.

The messaging base implements both hooks for runtime and configured bots. It
records configured-bot ownership, removes sessions/inboxes/caches, stops runners,
and skips deleted owners during rearming. External effects already in flight
are not part of the lattice transaction; provider-specific cleanup remains the
adapter's responsibility.

The corresponding public and private roots therefore have different roles:

| Root | Owner and purpose | Visibility |
|------|-------------------|------------|
| `v/adapters/<adapter>/` | Published operations, skills, templates, facts, safe config | Public discovery surface while active |
| `:adapters/<adapter>/` | Durable runtime records, sessions, preferences, recovery state | Venue-private; accessed by adapter code |
| `<user-did>/x/<adapter>/` | Adapter's explicit view for this user; no separate storage | Normal CRUD authority plus adapter policy; read-only by default |
| `<user-did>/w/...` | User-selected content and working data | Governed by that user's workspace capabilities |
| `<user-did>/s/...` | Credentials and secret material | Secret-store rules; adapter state stores references only |

State schemas need explicit tests. Cover the exact canonical paths, venue
ownership, restart recovery, deletion, config/runtime separation, malformed
records, and any migration from an older layout. A new schema should define
which version wins during migration and make repeated migration idempotent.
Version it with `stateVersion` and `migrateState`
([Versioned state and migration](#versioned-state-and-migration)), and test each
step: older state converts, a current state comes back as the same cell, and an
unknown shape is refused.

## External agent interactions

`covia.adapter.remote.ARemoteAgentAdapter<I extends RemoteInteraction>` is the
shared Job-facing base for outbound A2A, Claude Code and native Covia agent
proxies. External means execution controlled outside Covia's own agent loop;
the transport may be HTTP, a local CLI, or an SDK.

Subclasses select interaction operations with `isInteractionOperation` and
implement `startInteraction`. The base claims `Job.start` once, checks invoke
authority, routes messages, binds cancellation and removes live bindings on
terminal completion. Administrative operations retain `invokeFuture` dispatch.
Providers validate their target, credentials, options and resource authority
before effects. Construct a passive `RemoteInteraction` and bind it with
`bindInteraction` before starting work, or on remote acceptance when its handle
first becomes known. Late acceptance after local cancellation still invokes
the binding's cancel hook.

An interaction implements `deliver(messageRecord)` and `cancel()`. Delivery
receives the original structured Job message; it must throw on unsupported or
unacknowledged delivery. Returning means accepted, not completed. Local queue
acceptance is not durable external acceptance. A task, turn and conversation
have different completion rules: A2A mirrors the remote task, Claude `run`
completes after one turn, and Claude `session` waits in INPUT_REQUIRED after
each reply. Only conversation operations opt into `returnsFirstReply`.
Progress uses the shared `publishProgress(job, message, data)` helper: one
line a person can read, with the provider's own detail beside it, kept in
the Job's record as its current state (JOBS.md, Progress). Provider-native
results, message parts and session/task identifiers are preserved.

Recovery stays explicit. A2A and `remoteagent` compose `RemoteJobs` for durable
submission intent, protected transport authority and reattachment to an
accepted task. Observation failure cannot manufacture execution failure or
authorize replay. Claude may reconstruct an idle conversation through
`restoreInteraction`; resuming a transcript does not recover an interrupted
turn. There is no default resubmission, polling loop or lifetime timeout in
the base. Cancellation is a request, not proof of remote cessation.

`VenueRemoteAgent` is a Java proxy over any authenticated `Venue`, including
`LocalVenue`. Its `request` returns a task handle without SDK result polling;
`task`, `deliver` and `cancel` operate on that handle. The built-in
`remoteagent:request` is an internal transport operation; applications use
`agent:request` or `agent:chat` on an [agent definition](./AGENT_LOOP.md#221-agent-definitions-and-external-implementations). It accepts the native
`agent:request` fields plus optional `venue` and `authenticateAs`, and preserves
the destination task's output envelope. A new request with `sessionId` starts
a new task in that conversation; a message to an existing proxy Job continues
that task. The Job records `sessionId` separately from `delegation.remoteId`.

Native remote connections share `VenueConnection` with Grid: anonymous by
default, explicit target-bound caller identity, or an explicitly delegated
venue relay. Omitting `venue` carries the complete current RequestContext to
the local agent. A local proxy cannot reconstruct that attenuated context from
a caller DID after restart, so its recovery fails explicitly and leaves the
original task ID for inspection; it never substitutes venue authority. Remote
proxies recover their saved transport authority and observe the same task.

### Agent dispatch hooks

Adapters implement `validateAgent`, `agentInvocation`, `agentSession` and,
when needed, `agentOutput` on the shared base. `agentInvocation` is a pure
translation into a backend operation and input; it must not submit work. The
base's existing interaction lifecycle handles that backend Job. `agentSession`
must work for failed and cancelled Jobs too. Parent recovery attaches to the
saved child; child recovery belongs to its provider. Neither path resubmits an
uncertain invocation. Startup restores all job handles before adapter recovery
so parent subscriptions attach to the actual recovered child.

Agent metadata, creation, admission and supported operations are defined in
[AGENT_LOOP.md §2.2.1](./AGENT_LOOP.md#221-agent-definitions-and-external-implementations).
Conversation identity, continuation and recovery behaviour are defined in
[AGENT_SESSIONS.md §2.2](./AGENT_SESSIONS.md#22-external-agent-sessions).

## Messaging adapters and webhook ingress

`covia.adapter.messaging` is the provider-independent support used by the Telegram,
Discord, WhatsApp and Slack modules. These classes live in the venue API; optional modules depend on
them with `provided` scope and do not bundle their own copies.

- `AMessagingAdapter<S, R>` owns config/runtime bot registration, reconciliation,
  restoration, selection, per-owner capability gates, secret lookup, retry
  scheduling and shutdown. Subclasses parse their settings and supply live
  `MessagingBot` instances; workers start through `start()`, never `install()`.
  A spec implements `MessagingBotSpec` for identity and agent/operation routing.
- `ConversationRouter` serializes whole turns and resets per opaque conversation
  key, invokes Jobs as the binding's configured user, restores agent sessions,
  and applies the result/silent/fixed reply policy. It imposes no Job timeout.
  Its queue is in memory and is **not durable acceptance**.
- `ConversationSessions` stores mappings under the existing private
  `config/<bot>/sessions` or `users/<did>/sessions/<bot>` roots. Numeric Telegram
  and Discord keys retain their paths. Other keys are encoded as single safe
  path segments; `ConversationRouter.key(installation, channel, thread)` builds
  an unambiguous compound key. Optional legacy callbacks support migration.

Transport, credentials, sender/recipient admission, group visibility, provider
payloads, formatting, limits, and outbound API schemas stay with the provider.
An allow-listed sender does not automatically authorize posting into a channel
visible to others. Operation handlers retain their provider-native payload
contracts; sharing conversation machinery does not introduce a common wire format.
Providers with multiple credentials override `validateRuntimeCredentials` to
require secret references for each one. Public information must not expose
installation credentials or private recipient policies.

### HTTP callbacks

An adapter may implement `covia.adapter.webhook.WebhookHandler` to receive
`GET` and `POST /webhooks/{adapter}/{binding}`. The server registers these routes
once and looks up the active adapter on every request, supporting runtime module
load, disable, enable and unload. Unavailable/non-webhook adapters return 404;
already-dispatched requests follow the normal in-flight module semantics.
There is no retained module handler after a request completes.

The SPI uses JDK types, not Javalin/Servlet classes. `WebhookRequest` preserves
raw body bytes, case-insensitive headers and query parameters. The route limits
bodies to 1 MiB and opts into venue HTTP rate limiting. It does **not** require
a venue bearer, map the caller to a venue user, or assume that a binding name
authenticates a request. The provider must verify its signature and replay
constraints, validate the app/installation/destination and sender, and choose
the `WebhookResponse`. Signature verification must precede parsing that changes
the signed representation or any side effects. Challenges and unsupported
event types also need provider-specific handling.

### Receipt durability and retries

`WebhookInbox` is an optional durable receipt primitive, usable by both HTTP
and socket transports. Give each installation/binding a private root such as
`config/<binding>/inbox` or `users/<did>/inbox/<binding>` and a provider event ID.
IDs are hashed into path segments; the original ID remains in the record.
Records contain `id`, `input`, `status`, `receivedAt` and, on completion,
`completedAt`; FAILED adds `reason`, `lastError` and `failedAt`. Input must contain the event needed for processing, **not**
access tokens, signing secrets or credential-bearing HTTP headers.

1. Authenticate/admit the event, then `accept(id, input)`. This writes PENDING
   and flushes the configured store before returning. Duplicate deliveries
   retain the original receipt and also flush, covering an earlier failed
   durability barrier. A failed acceptance must not receive a success ack.
2. Acknowledge promptly, independently of agent execution. Schedule PENDING
   receipts even on duplicate delivery; scan them again on startup. No worker
   is started automatically by the inbox.
3. Within the conversation queue, `claim(id)` changes PENDING to STARTED and
   flushes before work begins. Only the winning claim may execute. A failed
   flush must not proceed to effects.
4. After processing and any required reply, `complete(id)` flushes COMPLETE.
   Duplicate callbacks must not resubmit the Job or send another reply.

Local agent intake also claims before preprocessing, then uses `deliver` to
complete the receipt in the same venue transaction as session input, mapping
and Job admission state. Failed admission becomes FAILED. FAILED receipts are
terminal and excluded from completed-receipt pruning.

STARTED records found after an interruption are uncertain: the provider must
reconcile them with its Job/send checkpoints and external state. The shared
inbox deliberately offers no automatic replay of started work. Persisting a
Job does not recover its execution, and an external send cannot be atomically
committed with the inbox. This is not an exactly-once guarantee. Claims coordinate
one Engine, not multiple independently running replicas. Memory/temp venues
retain their normal ephemeral durability. `pruneCompleted(cutoff)` removes only
completed receipts; the provider chooses retention beyond its retry window and
must clean binding-owned inbox/checkpoint data in `deleteRuntimeState`.

### HTTP messaging workers

`AWebhookMessagingAdapter` and `WebhookBot` implement the shared binding
worker, fed by signed HTTP callbacks or a provider connection, used by the optional [WhatsApp](../../covia-whatsapp/README.md) and
[Slack](../../covia-slack/README.md) modules. They require credential references,
keep configuration private, and publish `send`, `create`, `delete` and `bots`.
A module that implements `fetchMedia` may also ship `/adapters/<name>/download.json`,
published as `<name>:download` (`{bot?, media_id, destination?, fileName?}`, gated on
`<name>/download`), which the shared class runs: without the file there is no such
operation. A module may also ship `/adapters/<name>/bot-management-skill.json`, published as
`v/skills/adapters/<name>-bot-management` for its main skill to reveal, and
`/agent-templates/<name>.json`, published as `v/agents/templates/<name>`.
Providers implement validation, signed callback parsing, admission and sends.
`MessagingHttp` uses a bounded response body, request/connection timeouts and
no redirects or automatic send retries; none of these timeouts limits a Job.

Config callback names are `c-<name>`. Runtime names are `u-<hash>`, derived from
owner and bot name, and returned as `webhookPath` by create/bots. Names locate
bindings; provider signatures authenticate them. Runtime records remain at
`users/<did>/bots/<name>`. Inbox roots are
`config/<name>/inbox/<identity>` or `users/<did>/inbox/<name>/<identity>`.
Session roots use `sessions` in place of `inbox`, with a further configuration
version segment before the encoded conversation key. Identity is the CAD3
hash of a compound owner/provider-installation key; version is the settings
map's hash. Resolved credentials are never stored in either record.

A spec declares its ingress. `webhookIngress()` (default true) enables the
callback route; otherwise the route answers 404. `connectionIngress()` makes
the worker hold a provider event connection. The provider opens it through
`AWebhookMessagingAdapter.openConnection`, returning a `MessagingConnection`.
The worker opens it once credentials resolve, on a virtual thread, never on
the shared retry thread. It reconnects at once after a normal end (a provider
refresh) and after the retry interval following a failure. It closes the
connection on stop, and `bots` reports `connection` as `CONNECTED`,
`CONNECTING` or `RETRYING`. The provider authenticates the connection, calls
`WebhookBot.accept` and acknowledges only after it returns. Both ingresses may
be enabled; they share one inbox and router. `MessagingHttp.socket` checks a
provider-issued socket URL (WSS, or WS on loopback).

Some providers balance a credential's events across all of its open
connections. In that case `openConnection` returns a lease from
`SharedConnections`. Bindings whose credential resolves to the same value
share one connection, keyed by the credential's hash in memory only. The
opener receives the current members and routes each event to the bindings it
concerns. A lease ends with the shared connection and leaves the group before
its binding reconnects. The last lease out closes the connection. Possession
of the credential is the sharing test, so bindings with different credentials
never share, whatever installation they claim.

The worker scans PENDING receipts on start and periodically for late secret
provisioning. For local agents, the conversation queue serializes admission:
PENDING is claimed as STARTED before preprocessing, then becomes COMPLETE in the
transaction that appends input and saves the mapping and Job admission state.
Admission failure becomes FAILED without retry. Further inputs can be admitted while
the agent is thinking. Replies
run independently; a turn/send failure cannot reopen or replay a delivered receipt.
Reply callbacks are not a durable outbound queue, so a process interruption can lose
a reply. Operation handlers and remote agents retain claim-before-execution and
complete-after-reply behavior; their STARTED receipts remain uncertain after failure
and are not replayed. Pending records also carry `bindingVersion`;
after a policy/handler change, an older version fails once with the
`WebhookBot.RECONFIGURED` reason rather than executing under the new settings. Completed receipts
continue deduplicating across those changes. Changing owner/installation
isolates both inbox and sessions. No automatic receipt pruning is performed.
`bots` reports `pending`, `started` and `failed` receipt counts.

Stopping a worker prevents queued work, late replies and late session writes.
Deleting a runtime binding removes its registry, sessions and inbox; this
also removes its deduplication history. An already dispatched external request
may finish. Recreating a deleted binding is a new intake lifecycle.

### Provider boundaries

The first modules implement text messaging over HTTP callbacks and, for Slack,
Socket Mode. The remaining provider-specific extension points are:

- **WhatsApp:** bind an app and phone-number ID to the owner/handler. Keep app
  secret, verification token and outbound token separate. Verify the Meta
  webhook, split batches into events, deduplicate message IDs within the phone
  binding, and process delivery status separately from conversation turns.
  Template messages and the customer service window remain provider policy.
- **Slack:** make one binding identify an installation, including the workspace
  and enterprise context where applicable. A signing secret enables the HTTP
  Events API receiver; an app token enables a Socket Mode connection feeding
  the same inbox/router. HTTP ingress requires
  Slack signature and timestamp verification against the raw body; Socket Mode
  authenticates its connection with the app token. Slack balances an app's
  envelopes across all its connections, so bindings resolving to one app token
  share a connection and each event goes to every binding of its installation;
  all bindings of an app should use the same token. A channel conversation
  starts with a mention; an un-mentioned reply continues only a thread the
  agent already holds (`WebhookBot.holdsConversation`), and the `message` copy
  of a mention is skipped in favour of its `app_mention`. Keep bot and app/signing credentials
  distinct. Deduplicate Events API `event_id`; the Socket Mode `envelope_id`
  belongs to acknowledgement. Use installation + channel + thread for channel
  conversations and an explicit per-DM policy. Preserve timestamp IDs as strings,
  filter self/bot echoes, enforce channel visibility/recipient policy, and pass
  `thread_ts` through outgoing replies. OAuth installation management and rich
  Block Kit/interactivity remain in the Slack module.

Slack's [Events API](https://docs.slack.dev/apis/events-api/) requires HTTP
acknowledgement within three seconds. Its [request verification guide](https://docs.slack.dev/authentication/verifying-requests-from-slack/)
defines signed body and replay checks; [Socket Mode](https://docs.slack.dev/apis/events-api/using-socket-mode/)
defines envelope acknowledgements. [chat.postMessage](https://docs.slack.dev/reference/methods/chat.postMessage/)
documents threaded replies. None of these transport details belong in the common
conversation or registry classes.

### Relays: one provider binding, many principals

An adapter may also implement `covia.adapter.relay.RelayHandler` to serve the
principals a hub relays to: `GET` and `POST /relay/{adapter}/{binding}/{action}`.
Every venue registers these routes once and answers 404 while no relay adapter is
live; like `/webhooks`, they look the adapter up on every request and cap bodies at
1 MiB. Unlike `/webhooks`, they require a credential the venue accepts (a self-issued
DID JWT is enough) and pass the caller's DID to the adapter; no venue user is created
or admitted. Authentication says who calls; what that principal may do is the
adapter's decision. The handler answers with a future, so an action such as a long
poll can wait without holding a server thread.

Every webhook messaging provider (`AWebhookMessagingAdapter`) is a relay handler.
A binding whose handler is `relay` (`true`, or `{holdSeconds?, maxParts?, admit?,
charge?, notices?}`), in place of `agent` or `operation`, serves many principals from
one provider binding. The binding authenticates, parses and sends exactly as any other,
then `covia.adapter.messaging.RelayBinding` takes over: nothing reaches the durable
inbox, and no Job records message text. Principals use the binding's own id, as in
`/relay/whatsapp/c-main` or the `relayPath` that `bots` reports. A provider needs only
the hooks it wants to change: `maxText` (replies are split to fit), `sendFollowUp`
(later parts of a split reply), `relayReplyWindowMs`, and `relayContact` and
`relayLink` (an address such as a phone number, and a link that opens a chat with the
linking message typed). A provider that puts `media` on its events and implements
`fetchMedia` relays attachments too, and gives them to agent bindings through
`covia.adapter.messaging.MessagingMedia`, the attachment layer Telegram also uses:
private storage in the owner's drive named for the provider, attachment
descriptors, image blocks and the binding's `mediaOperation`. WhatsApp and Slack
both relay; WhatsApp also relays and delivers media, and offers `download`.

| Action | Request | Answer |
|---|---|---|
| `status` | — | `{enabled, linked, …contact}` |
| `link` | `{}` | `{code, expires_in, link?, …contact}`; the person sends `LINK <code>` to the binding; a code lasts ten minutes and is spent once |
| `unlink` | `POST {}` | `{unlinked}` |
| `poll` | `{wait_ms?}` | `{linked, messages[{id, text, timestamp, via, media?}]}`, waiting up to 20 s; `via` holds the provider's `channel`, `from`, `chat` and `thread`; `media` lists attachments as `{kind, mediaType, fileName?, voice?}`, the caption being the text |
| `reply` | `POST {message_id, text}` | `{sent, parts}`: once per message, through the provider's own reply, so its threading and reply window apply; refused with `too_long` beyond `maxParts` (default 10) |
| `media` | `?message_id=…&index=…`, or the same as a JSON body | The attachment's bytes with its media type, fetched from the provider on each request and never kept; only for the message's own principal, inside its reply window; `no_media`, `media_too_large` or `media_unavailable` otherwise |

Errors are `{error}` with the codes `RelayBinding` defines. A principal counts as open
while it has a poll waiting or polled in the last minute. With `holdSeconds` at 60 or
less (the default), a message for a principal that is not open is refused and its
sender told so; a longer hold keeps it in memory that long. `STOP` from the sender
unlinks; an unlinked sender is told how to link; a linked sender of something the
provider cannot relay (a location, say) is told so. The hub's own replies are `notices` (`linked`,
`unlinked`, `notLinked`, `codeUnknown`, `notOpen`, `held`, `textOnly`,
`notAvailable`), each overridable. A relay binding admits every sender unless
`open: false` narrows it to `allow`, and its owner must be a DID, since both hooks run
as that user without a Job record: `admit` receives `{principal}` before linking,
polling and each delivery and answers `{allowed: true}` or `{allowed: false, code?,
notice?}`; `charge` receives `{principal, parts, message_id}` after a reply is sent.

`covia.adapter.relay.MessageRelay` is the store beneath: links between a provider
sender and a principal (durable, at `<root>/links` and `<root>/principals`, the root
being `config/<bot>/relay/<identity>` or the owner's `relay/<bot>/<identity>`),
linking codes, per-principal mailboxes with the hold policy, each message's opaque
reply address, de-duplication, and one reply per message. Text, codes and addresses
are bounded and in memory, so a restart loses only messages not yet collected.

## Writing an adapter

1. Create a class extending `AAdapter` in a `covia.<module>.<feature>` package.
2. Implement `getName()` and an LLM-friendly `getDescription()`.
3. Implement `invokeFuture`, or the job-aware `invoke` when the adapter owns
   the job lifecycle. Call `requireInvoke(ctx)` or the correct resource gate
   before effects, and use `getSubOperation(meta)` for dispatch.
4. Override `configure` when settings need validation or cached runtime state.
   Override `info` and `publicConfig` only for intentionally public facts.
5. Override `installAssets()` and create JSON operation definitions under
   `src/main/resources/adapters/<name>/`. Ship relevant skills and templates
   with the adapter rather than registering them centrally.
6. Use `adapterState()` only for adapter-owned state. Keep user-managed
   data in caller-authorised paths and credentials in `s/`.
7. Implement `AutoCloseable` when the adapter owns threads, clients, native
   handles, or other resources.
8. Register a built-in adapter in the engine bootstrap, or package it as an
   optional module.
9. Add unit/functional tests and, for a module, a load/unload integration test
   against the packaged module jar.

Operation metadata should describe inputs and outputs precisely enough for
schema validation and agent tool use:

```json
{
  "name": "Run example",
  "description": "What the operation does and when to use it.",
  "operation": {
    "adapter": "example:run",
    "readOnly": false,
    "activityLabel": "Running example",
    "input": {
      "type": "object",
      "properties": {},
      "additionalProperties": false
    },
    "output": { "type": "object" }
  }
}
```

`operation.input` may describe any JSON value — an operation is not obliged
to take an object. Tools are: MCP `inputSchema` and provider tool schemas
require `type: object`. An operation whose declared input type excludes
`object` is therefore published and callable as an operation, and advertised
as a tool best-effort, but the adapter logs a warning at install because a
tool client may reject the schema or send an object the operation cannot use.
Declare an object schema for anything meant to be called as a tool. The
converse holds too: a string arriving at an operation is a valid input and is
never parsed into an object; that repair happens only at tool-call boundaries,
whose schema admits nothing else.

`operation.readOnly` is optional. An explicit `true` permits result-oriented
execution without a durable job record. `false` or absence retains the normal
durable-job default, preserving compatibility with existing and external
operation assets. Use `true` only for safe reads; operators can still record
classified reads with `recordReadOnlyOperations: true`.

`operation.activityLabel` is optional UI text for a running tool call. It is
never sent in the model provider's tool definition. The runtime falls back to
the asset's top-level `name`, then to the raw tool name when the metadata omits
it.

`operation.secretFields` marks inputs containing secret values for job-record
redaction. Reference-only inputs such as HTTP `bearerSecret` and `secretHeaders`
retain their references; resolving credentials must not replace those inputs
with plaintext. The recorded operation and inputs identify the credential used
without a separate provenance field.

See [OPERATIONS.md](OPERATIONS.md) for defaults, discovery, reference
resolution, and full metadata rules.

## Optional module packaging

Optional adapters should be separate Maven modules when operators may choose
whether to install their dependency tree. A module:

- depends on `venue` with `provided` scope;
- shades only its own runtime dependencies into a `*-module.jar` written beside the slim jar (not attached, so Maven Central receives only the slim jar);
- excludes Covia, Convex, SLF4J, and Logback platform classes;
- excludes every library `covia.jar` already ships (Kotlin, Jackson, Gson,
  Protobuf and the like) and uses the venue's copy, so no package is in both
  jars and the module jar could load as a Java module layer over the venue;
- sets `covia.module.name` to `covia.<name>`, which the parent POM writes as
  the jar's `Automatic-Module-Name`;
- declares every adapter in `META-INF/services/covia.adapter.AAdapter`;
- uses the services resource transformer when shading;
- includes its operation JSON, skills, and templates in its own resources,
  under classpath paths the venue jar does not use (for example
  `/adapters/<name>/skill.json` rather than `/skills/<name>.json`, which the
  venue's connection skills occupy), so both jars can share one classpath;
- has an integration test that loads the actual shaded jar and verifies its
  catalog appears and retracts correctly, that the jar names its module, and
  that it carries no package `covia.jar` ships.

Boot modules are configured with `modules`; runtime loading is additionally
gated by `dynamicModules` and venue authority. See
[CONFIG.md, “Venue modules”](CONFIG.md#venue-modules) for operator settings and
[BUILD.md](../../BUILD.md) for reactor and release packaging. Existing
`covia-telegram`, `covia-discord`, `covia-sonnylabs`, `covia-speech`, and
`covia-sql` modules are concrete examples.

## Test checklist

At minimum, test the following where applicable:

- configuration accepts valid values and rejects malformed known values;
- strict mode rejects unknown keys, while invariant/security keys cannot be
  used to bypass fixed boundaries in lenient mode;
- operation dispatch receives resolved metadata and applies capability checks
  before effects;
- public info and config contain no tokens, secrets, or private settings;
- catalog operations, skills, templates, and `v/adapters` mirrors materialise;
- disable, enable, reconfigure, unload, and close semantics preserve or release
  state as designed;
- `AdapterState` values and ownership are exact, recover after restart, and
  clean up without exposing adapter-global state as user workspace data;
- external I/O is exercised through a deterministic local fake where possible;
- module service discovery and the shaded artifact work in integration tests.

Run the owning module tests during development and `mvn clean install` before
release so the full reactor, javadocs, shaded jars, and module integration
tests are verified.

The messaging suites run locally without provider credentials. WhatsApp and
Slack module integration tests boot a private venue with the packaged module,
post signed HTTP callbacks, and capture outgoing requests at a loopback fake API.
They cover verification challenges, rejected signatures, duplicate delivery,
reply context, explicit sends, and disabling/unloading the live HTTP receiver.
Telegram's module test exercises polling and sending against its local fake API.
The shared `MessagingHttpTest` checks malformed and oversized responses,
redirects, provider errors, and deadlines before headers and during body reads.

Run the focused messaging tests and packaged-module checks from the root:

```bash
mvn -pl covia-whatsapp,covia-slack,covia-telegram -am verify -Dtest=MessagingHttpTest,WhatsAppAdapterTest,SlackAdapterTest,TelegramAdapterTest,ConversationRouterTest,WebhookInboxTest,WebhookRoutesTest -Dsurefire.failIfNoSpecifiedTests=false
```

These are local contract tests; they do not validate live provider credentials,
subscriptions, permissions, or delivery over the public internet.

## Related references

- [CONFIG.md](CONFIG.md) — operator configuration and runtime administration
- [OPERATIONS.md](OPERATIONS.md) — operation metadata, references, and defaults
- [UCAN.md](UCAN.md) — capabilities and proofs
- [GRID_LATTICE_DESIGN.md](GRID_LATTICE_DESIGN.md) — lattice namespaces and federation
- [../CLAUDE.md](../CLAUDE.md) — venue architecture and adapter inventory
- [../../BUILD.md](../../BUILD.md) — build, module artifacts, and release flow
