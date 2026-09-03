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
5. Before the venue serves requests, `recoverJob(job)` is called once per
   non-terminal durable Job the adapter's operations own. The default
   stabilises and never re-executes; override to re-attach or retry
   (see [JOBS.md § Recovery](JOBS.md#recovery-on-restart-214)).
6. Runtime `adapter/configure` calls `configure` again and republishes public
   configuration and information if accepted.
7. Disable removes live dispatch and public introspection but retains the
   instance and durable catalog metadata. Enable restores it.
8. Module unload deregisters its live adapters and closes `AutoCloseable`
   resources and the module classloader. In-flight jobs retain their adapter
   instance and may finish.
9. At venue shutdown, in-flight Jobs get `shutdown.graceMs` to finish;
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
   `s/NAME` references.

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

`operation.readOnly` is optional. An explicit `true` permits result-oriented
execution without a durable job record. `false` or absence retains the normal
durable-job default, preserving compatibility with existing and external
operation assets. Use `true` only for safe reads; operators can still record
classified reads with `recordReadOnlyOperations: true`.

`operation.activityLabel` is optional UI text for a running tool call. It is
never sent in the model provider's tool definition. The runtime falls back to
the asset's top-level `name`, then to the raw tool name when the metadata omits
it.

See [OPERATIONS.md](OPERATIONS.md) for defaults, discovery, reference
resolution, and full metadata rules.

## Optional module packaging

Optional adapters should be separate Maven modules when operators may choose
whether to install their dependency tree. A module:

- depends on `venue` with `provided` scope;
- shades only its own runtime dependencies into an attached `*-module.jar`;
- excludes Covia, Convex, SLF4J, and Logback platform classes;
- declares every adapter in `META-INF/services/covia.adapter.AAdapter`;
- uses the services resource transformer when shading;
- includes its operation JSON, skills, and templates in its own resources;
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

## Related references

- [CONFIG.md](CONFIG.md) — operator configuration and runtime administration
- [OPERATIONS.md](OPERATIONS.md) — operation metadata, references, and defaults
- [UCAN.md](UCAN.md) — capabilities and proofs
- [GRID_LATTICE_DESIGN.md](GRID_LATTICE_DESIGN.md) — lattice namespaces and federation
- [../CLAUDE.md](../CLAUDE.md) — venue architecture and adapter inventory
- [../../BUILD.md](../../BUILD.md) — build, module artifacts, and release flow
