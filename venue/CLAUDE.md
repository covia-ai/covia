# Covia Venue - Development Guide

## Overview

The **venue** module is the core runtime server for Covia. A Venue is a node
in the Covia Grid that hosts and executes operations, manages assets and
agents, and participates in the federated network.

**Main Entry Point:** `covia.venue.MainVenue`
**Core Engine:** `covia.venue.Engine`
**Stack:** Java 21, Maven, Convex Lattice, Javalin

## Project Structure

```
venue/
├── src/main/java/covia/
│   ├── venue/           # Core runtime
│   │   ├── Engine.java          # Core state: adapters, assets, content, identity
│   │   ├── JobManager.java      # Job lifecycle: submit, query, persist, recover
│   │   ├── VenueState.java      # Lattice state wrapper (assets, jobs, users, managed users)
│   │   ├── Users.java / User.java  # Per-user lattice state (jobs, agents, workspace, h/ inbox)
│   │   ├── AccessControl.java   # Job ownership enforcement
│   │   ├── Auth.java            # Auth config, public capability scope, login providers
│   │   ├── RequestContext.java  # Caller identity, proofs, capability scope
│   │   ├── api/                 # REST (CoviaAPI), MCP, A2A, UserAPI
│   │   ├── server/              # HTTP server, AuthMiddleware, SSE
│   │   └── storage/             # Content storage backends
│   ├── adapter/         # Adapter implementations (AAdapter base + 35 adapters)
│   └── lattice/         # Lattice definitions (Covia.java), CapabilityChecker
├── src/main/resources/
│   ├── adapters/        # Operation asset definitions (JSON, per adapter)
│   ├── skills/          # Venue skill library (agent-loadable instruction+tool bundles)
│   └── agent-templates/ # Standard agent templates
└── docs/                # Module design docs (see Related Documentation)
```

## Key Abstractions

- **Asset** — immutable, content-addressed resource (CAD3 value hash).
  Operations, artifacts, or references.
- **Operation** — an Asset with an `operation` field; executed by an adapter,
  with JSON Schema input/output. See `docs/OPERATIONS.md`.
- **Job** — execution state for an invocation. No framework-level timeout;
  terminal states sticky; caller-side wait timeouts never mutate the job.
  Full implementation semantics: `docs/JOBS.md`; protocol: COG-8.
- **Adapter** — bridges operations to execution. Extends `AAdapter`; receives
  resolved metadata (never null) and a `RequestContext`; returns
  `CompletableFuture` (or overrides the job-aware `invoke` for direct job
  control — multi-turn, orchestration, HITL).
- **RequestContext** — caller DID, verified UCAN proofs, capability scope,
  execution scopes. `RequestContext.of` builds one; `Engine.context` decides
  what a principal may do now. `Engine.requireAuthority(ctx, resource, ability)` is the
  point-of-action enforcement primitive; `Engine.crossUserAllows` is the
  single cross-user gate (public-user parity + target-side admission
  `config.accepts` for talking to an agent, #447 + delegation proofs).
  **Two identities, never conflated**: `getCallerDID()` is *who acted*
  (attribution, delegation audience, granting authority); `getUserDID()` is
  *whose namespace* (bare paths, workspace, secrets, jobs, inbox, quota). They
  differ only for an agent sub-principal, `<ownerDID>:g:<agentId>`, and a
  delegate acting for another user (`user/sudo`) — see `docs/UCAN.md` §5.4.
- **Lattice** — CRDT-based persistent state. Structure defined in
  `covia.lattice.Covia`; design in `docs/GRID_LATTICE_DESIGN.md`.

**Job Lifecycle:**
```
PENDING -> STARTED -> COMPLETE | FAILED | CANCELLED | REJECTED   (terminal, sticky)
STARTED <-> PAUSED | INPUT_REQUIRED | AUTH_REQUIRED              (paused family)
```
Pause is `STARTED`-only and adapter opt-in; the paused family resumes to
`STARTED` via resume (PAUSED) or message delivery (INPUT_REQUIRED /
AUTH_REQUIRED).

## Adapter Reference

| Adapter | Purpose | Operations |
|---------|---------|------------|
| `grid` | Invoke operations locally or across venues | `run`, `invoke`; compatibility queries `jobStatus`, `jobResult` (prefer `job`) |
| `job` | Universal Job inspection and control, local or remote; Grid Job reads remain compatibility entry points | `status`, `result`, `cancel`, `pause`, `resume`, `message` |
| `remoteagent` | Covia agent proxy on a local or remote venue; selected by `g/<id>/agent.adapter`, used through `agent:chat` / `agent:request` | internal transport |
| `p2p` | The venue's Convex P2P node (`convex-p2p`), off unless `adapters.p2p.join`; peer list plus in-process change events, venue-owned (`docs/CONFIG.md#p2p-network-adaptersp2p`) | `peers`, `connect` |
| `convex` | Convex blockchain, CAD3 conversion, and secret-backed Ed25519 keys | `query`, `transact`, `generate-key`, `sign`, `decode-cad3`, `encode-cad3` |
| `crypto` | Local cryptography in Convex formats; secret-backed and held keys, JWTs and BIP39 phrases (see `docs/CONFIG.md#cryptography-crypto`) | `hash`, `generate-key`, `public-key`, `sign`, `verify`, `encrypt`, `decrypt`, `generate-mnemonic`, `validate-mnemonic`, `derive-key`, `jwt-sign`, `jwt-verify`, `jwt-decode`, `add-key`, `remove-key`, `list-keys` |
| `mcp` | Model Context Protocol client, bridging, and scoped tokens for the venue's `/mcp` (`docs/MCP_TOKENS.md`) | `tools-list`, `tools-call`, `add-server`, `add-tool`, `remove-server`, `refresh`, `create-token`, `update-token`, `delete-token`, `list-tokens`, `get-token` |
| `a2a` | Agent-to-Agent protocol client — remote agent cards, tasks and message relay | `send`, `get-task`, `cancel`, `agent-card`, `import-agent` |
| `langchain` | AI/LLM models | `openai`, `ollama`, `anthropic`, `gemini`, `xai`, `deepseek`, `mistral`, `openrouter`, `models` |
| `http` | HTTP requests (SSRF-protected, bounded validated redirects, default User-Agent; `adapters.http`) | `get`, `post` |
| `connections` | Service connection catalog; owns provider skills and reports transport/credential presence without reading secrets | `list`, `status` |
| `jvm` | JVM utilities | `stringConcat`, `urlEncode`, `urlDecode` |
| `file` | Filesystem (root-jailed); reads see into archives via `x.zip!/entry` | `roots`, `list`, `tree`, `read`, `write`, `append`, `delete`, `mkdir`, `stat` |
| `archive` | Zip/jar archives over file roots (zip-slip + zip-bomb guarded) | `list`, `extract`, `zip` |
| `schema` | JSON Schema | `validate`, `validateAll`, `infer`, `coerce`, `check` |
| `json` | JSON value utilities | `merge`, `cond`, `assoc`, `select` |
| `orchestrator` | Multi-step workflows | Custom orchestration |
| `covia` | Lattice CRUD | `read`, `write`, `copy`, `delete`, `append`, `slice`, `list`, `inspect`, `aggregate` |
| `asset` | Content-addressed assets | `store`, `get`, `getContent`, `list`, `pin` |
| `agent` | Agent lifecycle | `create`, `fromSkills`, `fork`, `request`, `message`, `trigger`, `query`, `list`, `delete`, `suspend`, `resume`, `update`, `cancelTask`, `parkTask`, `deleteSession` |
| `llmagent` | LLM agent transitions | `chat` |
| `goaltree` | Goal-tree agent planning | `chat` |
| `hitl` | Human-in-the-Loop (COG-16) | `request`, `respond`, `list` over the per-user `h/` inbox; `expire` (venue-only, fired by a request's scheduled timeout) |
| `project` | Projects as a work breakdown tree at `w/projects/<pid>` (`docs/PROJECT.md`); publishes the `projects` skill family | none yet — skills only |
| `dlfs` | Decentralised file system | `listDrives`, `createDrive`, `deleteDrive`, `list`, `read`, `write`, `mkdir`, `delete` |
| `vault` | Personal vault (configurable-drive DLFS wrapper) | `read`, `write`, `list`, `mkdir`, `delete` |
| `secret` | Secret store | `set`, `list`, `delete`, `extract` (direct owner `/run` only, never the public principal) |
| `memory` | Per-user agent memory (one vector at a path, default `w/memory`; each edit one atomic update) | `memory` (commands `recall`, `remember`, `update`, `forget`), `memory-recall` |
| `preferences` | Per-user preferences: one map per user in adapter state, read-only at `x/preferences` (`docs/PREFERENCES.md`) | `preferences` (commands `set`, `remove`, `append`, `insert`, `merge`) |
| `skills` | Agent skills discovery and SKILL.md import (see `docs/SKILLS.md`) | `list`, `read`, `parse`, `import` |
| `ucan` | Capability tokens — granting surface (COG-17) | `issue`, `verify` |
| `scheduler` | Deferred grid-op invocation, one-shot or `repeat.every`; tracked fires are durable Jobs (`docs/GRID_SCHEDULER.md`) | `schedule`, `cancel`, `trigger`, `list` |
| `auth` | Authentication and identity | `whoami`, `identity-token` (caller-only bearer saved as a secret) |
| `oauth` | Connected accounts — OAuth 2.0 grants held for users; `http:*` attaches tokens via `bearerSecret: "oauth/<provider>"` | `connect`, `complete`, `status`, `disconnect` |
| `user` | Explicit user registration, operator account deletion and discovery (arbitrary DIDs; venue-managed did:web usernames) | `create`, `delete`, `info`, `list` |
| `venue` | Venue administration — runtime adapter/module lifecycle and process restart (venue-owned; `docs/CONFIG.md`) | `adapters`, `adapter/enable`, `adapter/disable`, `adapter/configure`, `module/load`, `module/unload`, `restart`, `gc` |
| `test` | Testing | `echo`, `delay`, `fail`, `never`, `hold` (finishes when the test releases its gate), `holdstart` (as `hold`, inside its own invocation, so its start waits too), `random`, `chat`, `pause`, `taskComplete`, `failllm` (a model call that fails as the agent's `model` names: `setup`, `unavailable`, `value`, `content`, `bug`, `long-setup`), `step` (an agent transition scripted by its input: hold at a gate, complete, fail or park the task, fail the cycle, yield) |

Module adapters (shaded module jars, not in covia.jar — `docs/CONFIG.md` "Venue modules"):

| Adapter | Module | Purpose | Operations |
|---------|--------|---------|------------|
| `sql` | covia-sql | Venue-local convex-db and operator-registered JDBC databases | `query`, `execute` |
| `python` | covia-python-adapter | Operator-configured Python operations and stateful instances | configured ops, `instances/*` |
| `telegram` | covia-telegram | Telegram bots (operator-declared or user-created) routing chats and media to agents or handing Updates to operations; Bot API access in Telegram's own shapes | `send`, `call`, `download`, `create`, `delete`, `bots`, `format` |
| `discord` | covia-discord | Discord bots (operator-declared or user-created) routing DMs and mentioned guild messages to agents or operations; Discord REST API access | `send`, `call`, `create`, `delete`, `bots` |
| `whatsapp` | covia-whatsapp | WhatsApp Cloud API phone bindings with signed callbacks, durable intake and text replies; voice notes, photos and files reach agents as attachments (shared `MessagingMedia`, optional `mediaOperation`) and operations as raw messages for `download`; `relay` bindings serve many principals at `/relay/whatsapp/<binding>` | `send`, `download`, `create`, `delete`, `bots` |
| `slack` | covia-slack | Slack workspace bindings with DMs/channel mentions over signed Events API callbacks or Socket Mode, durable intake and thread replies; `relay` bindings as for WhatsApp | `send`, `create`, `delete`, `bots` |
| `sonnylabs` | covia-sonnylabs | SonnyLabs AI-firewall scanning for prompt injection and related LLM safety findings | `scan` |
| `documents` | covia-documents | Readable text from PDF and Office documents (PDFBox, POI) behind `mode: "extract"` on file/vault/dlfs reads; page ranges and a character cap | `extract` |
| `speech` | covia-speech | Speech to text and text to speech over an OpenAI-compatible audio API (a cloud provider, or a local server so audio stays on the machine), or transcription in-process with sherpa-onnx (`engine: "local"`); `transcribe` doubles as a messaging `mediaOperation`, `speak` stores Ogg/Opus voice notes | `transcribe`, `speak`, `engines`, `prepare`, `listen`, `hear` |
| `claudecode` | covia-claude-code | Drives the Claude Code CLI in venue-authorised project directories: one-shot runs and long-lived resumable sessions over a bounded warm-process pool | `run`, `session`, `sessions`, `stop`, `projects`, `create`, `delete` |

## API Endpoints

Base path: `/api/v1/`

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/status` | GET | Venue status and health; `access.{public,userAutoCreate}` states the admission policy truthfully (#255) |
| `/assets/{ref}` | GET | Asset metadata for any ref form; `/content/{ref}` GET returns content (#368); `/assets` POST registers; `/assets/{id}/content` GET/PUT (hash-only, deprecated GET) |
| `/invoke` | POST | Execute an operation — async by default (201 + job record); `?wait=true` blocks up to the 120s cap, `?wait=<ms>` up to that many ms |
| `/run` | POST | Execute an operation and return only its result; the one route for `secret:extract` |
| `/adapters/{name}/operations` | GET | The adapter's local operation names mapped to their full asset metadata |
| `/values/{read,list,slice,inspect,aggregate,count}` | GET | Job-free lattice reads (#177) — synchronous, capability-checked, no job persisted. See `docs/READ_API.md` |
| `/agents`, `/agents/{id}` | GET | Job-free agent listings (#180, #233) |
| `/agents/{id}/sse` | GET | Server-sent live agent run-loop events (#394; owner-level, `?sessionId=` narrows to one session, closes on TERMINATED) — `docs/AGENT_LOOP.md` §2.6 |
| `/schedules` | GET | Job-free listing of the caller's pending scheduled events (#369) |
| `/users`, `/users/{did}`, `/users/{did}/authentications` | GET | Job-free user admin reads (#255) — operator-only except a caller's own DID; 403 (not a broken page) for a signed-in non-operator |
| `/dlfs/drives`, `/dlfs/list` | GET | Job-free DLFS browsing reads (#253) — drive list and one directory's entries; file content itself was already job-free via `/content/dlfs/<drive>/<path>` |
| `/assets?scope=own` | GET | Job-free listing of the caller's own `a/` assets (#382); default `/assets` is the venue catalog |
| `/assets?kind=` | GET | Restricts the catalog listing to `operation` (assets carrying an `operation`) or `data` (those that do not). The listing is filtered before it is paged, so `offset` counts filtered entries and `total` is the filtered size |
| `/jobs` | GET | Caller's jobs as a paged `{items, total, offset, limit}` envelope (#229) |
| `/jobs/{id}` | GET | Job status. Proofs ride the `X-Covia-Ucans` header on body-less reads (federated observation) |
| `/jobs/{id}` | POST | Message delivery to a running job (202/403/404/409) |
| `/jobs/{id}/{cancel,pause,resume,delete}` | PUT | Lifecycle control (pause/resume are adapter opt-in → 409 otherwise) |
| `/jobs/{id}/sse` | GET | Server-sent job updates (closes on terminal) |
| `/.well-known/did.json` | GET | Venue DID document (#167) |
| `/.well-known/oauth-authorization-server` | GET | OAuth 2.1 AS metadata (RFC 8414) when `auth.oauth.provider` is enabled |
| `/oauth/{authorize,token,revoke}` | — | OAuth 2.1 authorization server: code+PKCE flow issuing venue bearers (`auth.oauth.provider`) |
| `/auth/connect/{provider}/callback` | GET | OAuth connection callback — the provider redirects the user here after approval; it hands the browser a one-time completion code for `oauth:complete` (`adapters.oauth`; not `/api/v1`) |

MCP endpoint at `/mcp`, reached with a venue bearer or a scoped MCP token (`docs/MCP_TOKENS.md`); A2A is opt-in with an `a2a` config block.

### A2A surfaces

Covia exposes both the venue front door and individual hosted agents:

| Surface | Card | JSON-RPC endpoint |
|----------|------|-------------------|
| Venue front door | `GET /.well-known/agent-card.json` | `POST /a2a` |
| Hosted agent | `GET /a2a/<ownerDID>/g/<agentId>/.well-known/agent-card.json` | `POST /a2a/<ownerDID>/g/<agentId>` |

The front door invokes `a2a.defaultChatOp` for a fresh `SendMessage`. A
per-agent `SendMessage` invokes `agent:request`; the resulting Covia task Job
is the A2A Task, and its session is the A2A `contextId`. `GetTask`,
`CancelTask`, `SubscribeToTask` and a `taskId` continuation on the same
per-agent endpoint reach only the asker's own tasks for that agent; anything
else is refused alike (`A2A.AGENT_TASK_NOT_FOUND`).

Per-agent exposure is private by default. Whoever may ask the agent may use
its endpoint: the owner, a caller the agent's `config.accepts` admits
(`"anyone"` includes anonymous callers), or one presenting an `agent/request`
UCAN — the same check as every other way of asking an agent. Each request is
the asker's own, and the agent serves it as itself, its tool calls under its
own `config.caps` and an external agent's backend with its controller's
authority, so an owner who wants a narrower public face publishes a
dedicated agent. Agent config `a2a: {public: true}` publishes the card and
admits nobody. Anonymous callers ask as the venue's public principal, so
the target's admission policy can authorise the action. The bound A2A
endpoint does not require generic `invoke` authority.
Anonymous denials are existence-hiding 404s; authenticated callers without
standing receive 403. A2A introduces no new authority.

Detailed addressing, publication, identifier mapping and access semantics are
in [`docs/A2A_AGENTS.md`](docs/A2A_AGENTS.md), the implementation companion to
COG-14. Operator configuration and a minimal request example are in
[`docs/CONFIG.md`](docs/CONFIG.md#a2a-protocol); the non-owner authority model
is in [`docs/A2A_INTERACTION_AUTHORITY.md`](docs/A2A_INTERACTION_AUTHORITY.md).

## Development Guidelines

### Adding a New Adapter

See **[`docs/ADAPTERS.md`](docs/ADAPTERS.md)**, the canonical adapter-system
and authoring contract. It covers invocation, configuration, catalog
publication, private state, capabilities, lifecycle, module packaging, and
the required tests.

Operation metadata examples and rules, including `secretFields` and defaults,
are in [`docs/ADAPTERS.md`](docs/ADAPTERS.md#writing-an-adapter) and
[`docs/OPERATIONS.md`](docs/OPERATIONS.md).

### Working with Jobs

```java
// All job operations go through engine.jobs() — never directly on Engine
Job job = engine.jobs().invokeOperation(opRef, input, requestCtx); // RequestContext carries caller
ACell result = job.awaitResult();          // blocks until terminal (no framework timeout)
ACell result = job.awaitResult(60_000);    // bounded caller-side wait — throws
                                           // JobPollingFailedException on timeout WITHOUT
                                           // mutating the job; re-attach by job ID later

// Query jobs (scoped by caller identity)
Index<Blob, ACell> myJobs = engine.jobs().getJobs(requestCtx);
AMap<AString, ACell> data = engine.jobs().getJobData(jobID, requestCtx);

// Lifecycle control
engine.jobs().cancelJob(jobID, requestCtx);
// Pause/resume are opt-in by the job's adapter (AAdapter.canPause / canResume).
// A job whose adapter cannot rejects them (HTTP 409). Pause is STARTED-only;
// resume never re-invokes the operation from stored input.
engine.jobs().pauseJob(jobID, requestCtx);
engine.jobs().resumeJob(jobID, requestCtx);
```

### Working with Lattice State

```java
Hash id = engine.storeAsset(metadataString, contentBlob);

// Per-user access (jobs, agents, secrets, workspace, h/ inbox)
User user = engine.getVenueState().users().ensure(callerDID);
Index<Blob, ACell> userJobs = user.jobs().getAll();

```

Adapter-owned state uses `AdapterState`; see
[`docs/ADAPTERS.md`](docs/ADAPTERS.md#private-state-and-user-managed-storage).

### Testing

- A test of components working together, or a long-running or
  timing-dependent one, is an `@IntegrationTest`, off unless
  `-Dcovia.tests.integration=true` (`BUILD.md`, Tests); a small
  synchronous test of a helper API stays in the default suite
- Tests downloading large models require a separate explicit opt-in, never
  the general integration flag. Speech model tests use
  `-Dcovia.tests.speech.models=true`; default and QA suites use local fixtures.
- Use the shared `TestEngine.ENGINE` with per-test DIDs (`TestEngine.uniqueDID`,
  `TestEngine.uniqueManagedDID` for a user the venue hosts)
  — never a fresh Engine per test unless persistence/restart is under test. It
  has a fictitious public hostname (`TestEngine.HOSTNAME`) and the default `tmp`
  file root, which a class fills with `TestEngine.writeTempFile`
- Every test class can run again in the same JVM, which is how an intermittent
  failure is reproduced: a scratch JUnit `LauncherFactory` harness loops the
  class under CPU load with the suite's parallel settings, calling
  `TestEngine.nextRun()` between runs so `uniqueDID` gives each test an
  identity no earlier run used. A class with an engine of its own starts it in
  `@BeforeAll` and closes it in `@AfterAll`, and venue-global state a class
  writes (the public identity's drives and secrets) must not stop a second
  run: it removes that state in `@AfterAll` or overwrites it
- Cross-venue tests use the shared `TwoVenueTestServer` (static venues,
  per-test identities) — never spin venues per test
- That the venue's state survives a restart is tested once, for the whole
  state (`VenuePersistenceTest`, `docs/PERSISTENCE.md` §7): a feature's tests
  never re-check it for their own state, and relaunch only to test what start-up does
- Launching a venue is the expensive part: tell a feature's story as one
  scenario on one store, and
  run authority matrices and edge cases on `TestEngine.ENGINE` or a store from
  `TestTemp.etch`. A test class in the default suite takes a second or two at
  most. `TestEngine.ENGINE` is Etch-backed so store machinery such as online
  GC runs on it under the suite's own load (`SharedEngineOnlineGcTest`)
- A test waits on a signal from the code under test, never a sleep or timed
  poll: an agent's run-loop events (`AgentWatch`), a Job's updates
  (`TestEngine.awaitJob`), a `TestAdapter.TestGate` that holds
  `v/test/ops/hold` work in flight, or a `TestSignal` a stub calls
- Unit tests run on a held clock, which wakes nothing: `TestEngine.ENGINE` and
  `Engine.createTemp(config)` hold theirs at the current time, so nothing fires
  in the middle of another test. A test that moves time takes a venue of its
  own, `Engine.createTemp(clock, config)` with a `DrivenClock`, and calls
  `engine.application().update()` after each step (`SteppedSchedulerTest`).
  A test never opens a `CoviaApplication` itself unless that is what it tests:
  `TestEngine.restartable(config)` is a venue it can restart in memory, and
  `TestEngine.application(...)` an application over its own store or host, both
  on a held clock unless given a `VenueClock`. `Engine.createRealTime` is the
  system clock, for integration and smoke tests; `BothClocksTest` runs the engine
  on both. A venue behind HTTP (`VenueServer`, `TestServer`, `TwoVenueTestServer`)
  stays on the system clock
- Size and durability assertions call `engine.flush()` first: otherwise state
  reaches the store only when the host's sync worker next runs, if whoever
  opened the host runs one
- A test that needs an Etch store of its own (restart, recovery, GC, the root,
  identity) gets it from `TestTemp.etch` or `TestTemp.etchFile`, never
  `@TempDir` or `EtchStore.createTemp` (`TestTemp` says why); a test server
  names its store, `"memory"` unless persistence is under test
- Rate-limit wiring tests, which need a venue of their own with limits on,
  are failsafe `*IT`s (`RateLimitIT`); the limiter itself is unit-tested on an
  injected clock (`RateLimiterTest`)
- A test that opens a release-written store fixture (`src/test/resources/migrations`)
  is a failsafe `*IT`, run by `mvn verify`; migration unit tests build their
  older states in code (`docs/MIGRATE.md`, Testing), and a new venue version or
  older shape is seeded in `VenueMigrationEndToEndTest`
- `mvn test -pl venue` runs the module; asset examples in
  `src/main/resources/asset-examples/`

## Configuration

Full operator reference: **`docs/CONFIG.md`** — persistence & identity
(seed/keystore/venue.key), startup store GC (`etch.gc.onStart`), network binding, rate limiting, public access
(`auth.public.caps`), user admission (`users.autoCreate`), per-adapter config,
private jobs, DLFS WebDAV, MCP tool
bridging, LLM providers, venue modules, A2A, secrets bootstrap.

Quick dev shapes: `java -jar covia.jar` (defaults, port 8080) or
`java -jar covia.jar local-dev.json` (two ephemeral venues, 8080/8081).

## Build & Run

```bash
mvn clean install            # produces venue/target/covia.jar (install phase, not package!)
java -jar target/covia.jar [config.json]
```

## Related Documentation

- `docs/ADMIN.md` — venue-level privileged operations, admin abilities and resources, exceptions and migration; document responsibility map
- `docs/AUTH.md` — authentication design (#297): method contract, central sessions, assurance policy
- `docs/ADAPTERS.md` — canonical adapter system, state, lifecycle, authoring, and module contract
- `docs/CONFIG.md` — operator configuration reference
- `docs/EMBEDDING.md` — running a venue inside another application: lifecycle and close order, adapters, user data, upgrading from 0.9.x
- `docs/JOBS.md` — job implementation semantics (COG-8 companion)
- `docs/ERRORS.md` — how the venue raises, types and reports failures, and what each surface tells a caller
- `docs/UCAN.md` — capabilities, granting surface, proof channels
- `docs/VENUE_AUTHORITY.md` — raising authority above a principal's base: allowances, activation, and the venue's power over the users it hosts (design)
- `docs/HELD_KEYS.md` — keyrings of private keys the crypto adapter holds for the identities a venue hosts: store format, use and custody rules
- `docs/HITL_TOKEN_TRANSPORT.md` — COG-19 design (proposal): HITL transport of
  a user-signed, self-sovereign token for cross-venue access (covia#292)
- `docs/SKILLS.md` — agent skill system
- `docs/OPERATIONS.md` — operation model, defaults, discovery
- `docs/PREFERENCES.md` — per-user preferences: storage, the `x/preferences` view, the edit tool's commands and size limit
- `docs/MCP_TOKENS.md` — scoped MCP tokens: a token request's identity and authority, the token operations and store, and `/mcp` with a token
- `docs/MODELS.md` — model definition assets (design): a model is an operation asset at `v/models/<provider>/<id>`
- `docs/PROJECT.md` — projects (design): a work-breakdown tree at `w/projects/<pid>` where every node has a principal, an assignee, targets, tolerances and PRINCE2-style product descriptions, and the assignee of a node is the principal of its children; the `project` adapter builds, executes and rolls up the tree
- `docs/GRID_LATTICE_DESIGN.md` — lattice design
- `docs/MIGRATE.md` — venue migration: requirements, ownership and design,
  each migration in this build with its code, and how migration is tested
- `docs/AGENT_LOOP.md`, `docs/AGENT_SESSIONS.md`, `docs/AGENT_TASKS.md`,
  `docs/AGENT_TEMPLATES.md`, `docs/GOAL_TREE.md` — agent architecture
- `docs/READ_API.md` — job-free read surface
- `../AGENTS.md` — project-level guide; `../BUILD.md` — release flow
