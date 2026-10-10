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

Cancellation hooks run after cancellation commits, including when registered
after cancellation, and each registration is claimed once. The default bridge
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

## Lifecycle and configuration

The significant lifecycle is:

1. A built-in adapter is constructed, or a module discovers one through
   `ServiceLoader`.
2. A module adapter receives `configureModule(modules[].config, strict)` once.
   It may return `false` when optional runtime prerequisites are unavailable.
3. Registration applies the effective `adapters.<name>` configuration through
   `configure(config, strict)`.
4. Unless boot-disabled or declined, `install(engine)` binds the engine and
   private `AdapterWorkspace`, then `installAssets()` declares its catalog.
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

Override `info()` for stable facts a client needs until the next reconfigure,
such as a mount point or enabled protocol feature. Live status, sessions,
connection counts, and mutable health belong behind operations, not in
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
2. **Adapter-global state.** Durable state owned by an adapter lives at the
   fixed well-known location `<venue-did>/w/adapters/<adapter>/` and is
   accessed through `adapterWorkspace()`.
3. **Secrets.** Secret material remains in `s/`. Adapter records contain only
   `s/NAME` references. An adapter that sends a secret resolves it with the
   request's destination (`engine.resolveSecret(ref, ctx, url)`), so a
   `secret/use` grant bound to a url is honoured.

`AdapterWorkspace` is bound to the adapter name and venue principal during
`install`; an invocation context cannot redirect it into the caller's
workspace. Its root is not a configurable `statePath`. It validates adapter
names, relative paths, traversal, bare user DIDs, null writes, and empty
mutation paths.

```java
AdapterWorkspace state = adapterWorkspace();
AString owner = ctx.getUserDID();

String relative = state.userPath(owner, "preferences/theme");
state.write(relative, Strings.create("dark"));
ACell theme = state.read(relative);
state.delete(relative);
```

An adapter may map user-level instances or preferences below
`users/<did>/...`. The DID is an association key, not an access grant: the
adapter owns and validates this schema, and users interact with it through
capability-checked adapter operations. When an adapter needs durable state
for operator-declared instances, `config/<instance>/...` is the conventional
separation from `users/<did>/...`. Other adapter-global keys are adapter-owned
but should remain stable and documented.

The corresponding public and private roots therefore have different roles:

| Root | Owner and purpose | Visibility |
|------|-------------------|------------|
| `v/adapters/<adapter>/` | Published operations, skills, templates, facts, safe config | Public discovery surface while active |
| `<venue-did>/w/adapters/<adapter>/` | Durable runtime records, sessions, preferences, recovery state | Venue-private; accessed by adapter code |
| `<user-did>/w/...` | User-selected content and working data | Governed by that user's workspace capabilities |
| `<user-did>/s/...` | Credentials and secret material | Secret-store rules; adapter state stores references only |

State schemas need explicit tests. Cover the exact canonical paths, venue
ownership, restart recovery, deletion, config/runtime separation, malformed
records, and any migration from an older layout. A new schema should define
which version wins during migration and make repeated migration idempotent.

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
`completedAt`. Input must contain the event needed for processing, **not**
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

`AWebhookMessagingAdapter` and `WebhookBot` implement the shared HTTP binding
worker used by the optional [WhatsApp](../../covia-whatsapp/README.md) and
[Slack](../../covia-slack/README.md) modules. They require credential references,
keep configuration private, and publish `send`, `create`, `delete` and `bots`.
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

The worker scans PENDING receipts on start and periodically for late secret
provisioning. Whole turns share the conversation queue, claim and flush before
execution, and complete only after any reply. STARTED receipts remain uncertain
after failure and are not replayed. Pending records also carry `bindingVersion`;
after a policy/handler change, an older version stays pending for operator
inspection rather than executing under the new settings. Completed receipts
continue deduplicating across those changes. Changing owner/installation
isolates both inbox and sessions. No automatic receipt pruning is performed.

Stopping a worker prevents queued work, late replies and late session writes.
Deleting a runtime binding removes its registry, sessions and inbox; this
also removes its deduplication history. An already dispatched external request
may finish. Recreating a deleted binding is a new intake lifecycle.

### Provider boundaries

The first modules implement text messaging over HTTP callbacks. The remaining
provider-specific extension points are:

- **WhatsApp:** bind an app and phone-number ID to the owner/handler. Keep app
  secret, verification token and outbound token separate. Verify the Meta
  webhook, split batches into events, deduplicate message IDs within the phone
  binding, and process delivery status separately from conversation turns.
  Template messages and the customer service window remain provider policy.
- **Slack:** make one binding identify an installation, including the workspace
  and enterprise context where applicable. Support an HTTP Events API receiver
  or, in a future extension, a Socket Mode runner feeding the same inbox/router. HTTP ingress requires
  Slack signature and timestamp verification against the raw body; Socket Mode
  authenticates its connection separately. Keep bot and app/signing credentials
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
6. Use `adapterWorkspace()` only for adapter-owned state. Keep user-managed
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
- declares every adapter in `META-INF/services/covia.adapter.AAdapter`;
- uses the services resource transformer when shading;
- includes its operation JSON, skills, and templates in its own resources,
  under classpath paths the venue jar does not use (for example
  `/adapters/<name>/skill.json` rather than `/skills/<name>.json`, which the
  venue's connection skills occupy), so both jars can share one classpath;
- has an integration test that loads the actual shaded jar and verifies its
  catalog appears and retracts correctly.

Boot modules are configured with `modules`; runtime loading is additionally
gated by `dynamicModules` and venue authority. See
[CONFIG.md, “Venue modules”](CONFIG.md#venue-modules) for operator settings and
[BUILD.md](../../BUILD.md) for reactor and release packaging. Existing
`covia-telegram`, `covia-discord`, `covia-sonnylabs`, and `covia-sql` modules
are concrete examples.

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
- `AdapterWorkspace` paths and ownership are exact, recover after restart, and
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
