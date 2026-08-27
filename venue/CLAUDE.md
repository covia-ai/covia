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
│   │   ├── VenueState.java      # Lattice state wrapper (assets, jobs, users, auth)
│   │   ├── Users.java / User.java  # Per-user lattice state (jobs, agents, workspace, h/ inbox)
│   │   ├── AccessControl.java   # Job ownership enforcement
│   │   ├── Auth.java            # Auth config, public capability scope, login providers
│   │   ├── RequestContext.java  # Caller identity, proofs, capability scope
│   │   ├── api/                 # REST (CoviaAPI), MCP, A2A, UserAPI
│   │   ├── server/              # HTTP server, AuthMiddleware, SSE
│   │   └── storage/             # Content storage backends
│   ├── adapter/         # Adapter implementations (AAdapter base + ~25 adapters)
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
  execution scopes. `Engine.requireAuthority(ctx, resource, ability)` is the
  point-of-action enforcement primitive; `Engine.crossUserAllows` is the
  single cross-user gate (public-user parity + delegation proofs).
  **Two identities, never conflated**: `getCallerDID()` is *who acted*
  (attribution, delegation audience, granting authority); `getUserDID()` is
  *whose namespace* (bare paths, workspace, secrets, jobs, inbox, quota). They
  differ only for an agent sub-principal, `<ownerDID>:g:<agentId>` — see
  `docs/UCAN.md` §5.4.
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
| `grid` | Federated grid operations | `run`, `invoke`, `jobStatus`, `jobResult` |
| `convex` | Convex blockchain | `query`, `transact` |
| `mcp` | Model Context Protocol | `toolList`, `toolCall`, bridging ops |
| `langchain` | AI/LLM models | `openai`, `ollama`, `anthropic`, `gemini`, `xai`, `deepseek`, `mistral`, `openrouter`, `models` |
| `http` | HTTP requests (SSRF-protected) | `get`, `post` |
| `jvm` | JVM utilities | `stringConcat`, `urlEncode`, `urlDecode` |
| `file` | Filesystem (root-jailed); reads see into archives via `x.zip!/entry` | `roots`, `list`, `tree`, `read`, `write`, `append`, `delete`, `mkdir`, `stat` |
| `archive` | Zip/jar archives over file roots (zip-slip + zip-bomb guarded) | `list`, `extract`, `zip` |
| `schema` | JSON Schema | `validate`, `validateAll`, `infer`, `coerce`, `check` |
| `orchestrator` | Multi-step workflows | Custom orchestration |
| `covia` | Lattice CRUD | `read`, `write`, `delete`, `append`, `slice`, `list`, `inspect`, `aggregate`, `functions`, `describe`, `adapters` |
| `asset` | Content-addressed assets | `store`, `get`, `getContent`, `list`, `pin` |
| `agent` | Agent lifecycle | `create`, `fork`, `request`, `message`, `trigger`, `query`, `list`, `delete`, `suspend`, `resume`, `update`, `cancelTask`, `deleteSession` |
| `llmagent` | LLM agent transitions | `chat` |
| `goaltree` | Goal-tree agent planning | `chat` |
| `hitl` | Human-in-the-Loop (COG-16) | `request`, `respond`, `list` over the per-user `h/` inbox |
| `dlfs` | Decentralised file system | `listDrives`, `createDrive`, `deleteDrive`, `list`, `read`, `write`, `mkdir`, `delete` |
| `vault` | Personal vault (configurable-drive DLFS wrapper) | `read`, `write`, `list`, `mkdir`, `delete` |
| `secret` | Secret store | `set`, `extract` (removal via `covia:delete s/<name>`) |
| `memory` | Per-user agent memory (one LWW vector, default `w/memory`) | `recall`, `remember`, `update`, `forget` |
| `skills` | Agent skills discovery and SKILL.md import (see `docs/SKILLS.md`) | `list`, `read`, `parse`, `import` |
| `ucan` | Capability tokens — granting surface (COG-17) | `issue`, `verify` |
| `scheduler` | Deferred grid-op invocation, one-shot or `repeat.every`; tracked fires are durable Jobs (`docs/GRID_SCHEDULER.md`) | `schedule`, `cancel`, `trigger`, `list` |
| `auth` | Authentication ops | login/token flows |
| `user` | Explicit user registration and discovery (arbitrary DIDs; venue-managed did:web usernames) | `create`, `info`, `list` |
| `venue` | Venue administration — runtime adapter/module lifecycle and process restart (venue-owned; `docs/CONFIG.md`) | `adapters`, `adapter/enable`, `adapter/disable`, `adapter/configure`, `module/load`, `module/unload`, `restart` |
| `test` | Testing | `echo`, `delay`, `fail`, `never`, `random`, `chat`, `pause`, `taskComplete` |

Module adapters (shaded module jars, not in covia.jar — `docs/CONFIG.md` "Venue modules"):

| Adapter | Module | Purpose | Operations |
|---------|--------|---------|------------|
| `sql` | covia-sql | Venue-local convex-db and operator-registered JDBC databases | `query`, `execute` |
| `python` | covia-python-adapter | Operator-configured Python operations and stateful instances | configured ops, `instances/*` |
| `telegram` | covia-telegram | Telegram bots (operator-declared or user-created) routing chats to agents or handing Updates to operations; Bot API access in Telegram's own shapes | `send`, `call`, `create`, `delete`, `bots` |
| `discord` | covia-discord | Discord bots (operator-declared or user-created) routing DMs and mentioned guild messages to agents or operations; Discord REST API access | `send`, `call`, `create`, `delete`, `bots` |
| `sonnylabs` | covia-sonnylabs | SonnyLabs AI-firewall scanning for prompt injection and related LLM safety findings | `scan` |
| `claudecode` | covia-claude-code | Drives the Claude Code CLI in venue-authorised project directories: one-shot runs and long-lived resumable sessions over a bounded warm-process pool | `run`, `session`, `sessions`, `stop`, `projects`, `create`, `delete` |

## API Endpoints

Base path: `/api/v1/`

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/status` | GET | Venue status and health |
| `/assets/{ref}` | GET | Asset metadata for any ref form; `/content/{ref}` GET returns content (#368); `/assets` POST registers; `/assets/{id}/content` GET/PUT (hash-only, deprecated GET) |
| `/invoke` | POST | Execute an operation — async by default (201 + job record); `?wait=true` blocks up to the 120s cap, `?wait=<ms>` up to that many ms |
| `/values/{read,list,slice,inspect,aggregate,count}` | GET | Job-free lattice reads (#177) — synchronous, capability-checked, no job persisted. See `docs/READ_API.md` |
| `/agents`, `/agents/{id}` | GET | Job-free agent listings (#180, #233) |
| `/agents/{id}/sse` | GET | Server-sent live agent run-loop events (#394; owner-level, closes on TERMINATED) — `docs/AGENT_LOOP.md` §2.6 |
| `/schedules` | GET | Job-free listing of the caller's pending scheduled events (#369) |
| `/assets?scope=own` | GET | Job-free listing of the caller's own `a/` assets (#382); default `/assets` is the venue catalog |
| `/jobs` | GET | Caller's jobs as a paged `{items, total, offset, limit}` envelope (#229) |
| `/jobs/{id}` | GET | Job status. Proofs ride the `X-Covia-Ucans` header on body-less reads (federated observation) |
| `/jobs/{id}` | POST | Message delivery to a running job (202/403/404/409) |
| `/jobs/{id}/{cancel,pause,resume,delete}` | PUT | Lifecycle control (pause/resume are adapter opt-in → 409 otherwise) |
| `/jobs/{id}/sse` | GET | Server-sent job updates (closes on terminal) |
| `/.well-known/did.json` | GET | Venue DID document (#167) |

MCP endpoint at `/mcp`; A2A is opt-in with an `a2a` config block.

### A2A surfaces

Covia exposes both the venue front door and individual hosted agents:

| Surface | Card | JSON-RPC endpoint |
|----------|------|-------------------|
| Venue front door | `GET /.well-known/agent-card.json` | `POST /a2a` |
| Hosted agent | `GET /a2a/<ownerDID>/g/<agentId>/.well-known/agent-card.json` | `POST /a2a/<ownerDID>/g/<agentId>` |

The front door invokes `a2a.defaultChatOp` for a fresh `SendMessage`. A
per-agent `SendMessage` invokes `agent:request`; the resulting Covia task Job
is the A2A Task, and its session is the A2A `contextId`. `GetTask` and
`CancelTask` use the same per-agent endpoint.

Per-agent exposure is private by default. The owner may always address their
agent. Agent config `a2a: {public: true}` publishes the card; an explicit
`a2a.caps` additionally permits anonymous interaction, running under the
owner's identity narrowed by that scope. Card-only publication does not grant
interaction. Anonymous denials are existence-hiding 404s; authenticated
callers without standing receive 403. Native authentication and UCAN checks
still apply—A2A introduces no new authority.

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
// Pause/resume are opt-in via Job hooks (the adapter calls job.setPauseHook /
// setResumeHook when it starts a suspendable execution). A job with no hook
// rejects them (HTTP 409). Pause is STARTED-only; resume never re-invokes the
// operation from stored input.
engine.jobs().pauseJob(jobID, requestCtx);
engine.jobs().resumeJob(jobID, requestCtx);
```

### Working with Lattice State

```java
Hash id = engine.storeAsset(metadataString, contentBlob);

// Per-user access (jobs, agents, secrets, workspace, h/ inbox)
User user = engine.getVenueState().users().ensure(callerDID);
Index<Blob, ACell> userJobs = user.getJobs();

```

Adapter-owned state uses `AdapterWorkspace`; see
[`docs/ADAPTERS.md`](docs/ADAPTERS.md#private-state-and-user-managed-storage).

### Testing

- Use the shared `TestEngine.ENGINE` with per-test DIDs (`TestEngine.uniqueDID`)
  — never a fresh Engine per test unless persistence/restart is under test
- Cross-venue tests use the shared `TwoVenueTestServer` (static venues,
  per-test identities) — never spin venues per test
- `mvn test -pl venue` runs the module; asset examples in
  `src/main/resources/asset-examples/`

## Configuration

Full operator reference: **`docs/CONFIG.md`** — persistence & identity
(seed/keystore/venue.key), network binding, rate limiting, public access
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

- `docs/AUTH.md` — authentication design (#297): method contract, central sessions, assurance policy
- `docs/ADAPTERS.md` — canonical adapter system, state, lifecycle, authoring, and module contract
- `docs/CONFIG.md` — operator configuration reference
- `docs/JOBS.md` — job implementation semantics (COG-8 companion)
- `docs/UCAN.md` — capabilities, granting surface, proof channels
- `docs/HITL_TOKEN_TRANSPORT.md` — COG-19 design (proposal): HITL transport of
  a user-signed, self-sovereign token for cross-venue access (covia#292)
- `docs/SKILLS.md` — agent skill system
- `docs/OPERATIONS.md` — operation model, defaults, discovery
- `docs/MODELS.md` — model definition assets (design): a model is an operation asset at `v/models/<provider>/<id>`
- `docs/GRID_LATTICE_DESIGN.md` — lattice design
- `docs/AGENT_LOOP.md`, `docs/AGENT_SESSIONS.md`, `docs/AGENT_TEMPLATES.md`,
  `docs/GOAL_TREE.md` — agent architecture
- `docs/READ_API.md` — job-free read surface
- `../AGENTS.md` — project-level guide; `../BUILD.md` — release flow
