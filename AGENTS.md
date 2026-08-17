# Covia - Federated AI Orchestration Platform

## Vision

Covia is the open-source infrastructure for federated AI orchestration. It enables AI models, agents, and data to collaborate across organisational boundaries, clouds, and jurisdictions — with built-in governance and without centralising control. Built on the Convex lattice platform for decentralised coordination.

## Project Structure

```
covia/                          # ai.covia:covia (parent POM)
├── covia-core/                 # Grid client library and shared abstractions
│   └── src/main/java/covia/
│       ├── api/                #   API field constants (Fields.java)
│       ├── exception/          #   Exception hierarchy (CoviaException, etc.)
│       ├── grid/               #   Core types: Asset, Job, Operation, Grid, Venue, Status
│       ├── grid/auth/          #   Auth strategies: NoAuth, BearerAuth, KeyPairAuth, LocalAuth
│       ├── grid/client/        #   HTTP client implementation (VenueHTTP)
│       └── grid/impl/          #   Content implementations (BlobContent, LatticeContent)
├── covia-python/               # Dependency-light Java FFM bridge to embedded CPython
├── covia-python-adapter/       # Optional Python operations venue module
│                               #   (shaded "module" jar, not in covia.jar)
├── venue/                      # Main venue server runtime (produces covia.jar)
│   └── src/main/java/covia/
│       ├── adapter/            #   Adapter framework and implementations
│       ├── lattice/            #   Lattice definitions (Covia.ROOT, Covia.VENUE)
│       ├── venue/              #   Engine, MainVenue, Config, Auth, LocalVenue
│       ├── venue/api/          #   REST API (CoviaAPI), MCP, A2A, UserAPI
│       ├── venue/server/       #   HTTP server (VenueServer, CoviaWebApp, SSE, AuthMiddleware)
│       └── venue/storage/      #   Storage backends (Lattice, File, Memory)
├── covia-sql/                  # SQL adapter venue module (convex-db + Calcite;
│                               #   shaded "module" jar loaded via config, not in covia.jar)
├── covia-telegram/             # Telegram bot venue module (operator-declared bots →
│                               #   agents/operations, telegram:send; shaded "module" jar)
├── covia-claude-code/          # Claude Code CLI venue module (runs/resumable sessions in
│                               #   authorised project dirs; shaded "module" jar, not in covia.jar)
├── workbench/                  # Minimal Swing GUI REPL for demo/testing
│   └── src/main/java/covia/gui/  Bench, ReplPanel, LAF
├── .claude/                    # Claude Code config (settings.json tracked; rest gitignored)
├── skills/                     # Claude Code skills (junction .claude/skills → skills/)
│   ├── adapters/               #   Adapter discovery, invocation, runtime enable/disable/configure, module load/unload
│   ├── ap-demo/                #   AP invoice audit trail demo (Alice/Bob/Carol)
│   ├── agent/                  #   Agent creation and management
│   ├── venue-setup/            #   Build and run a venue (local/VM/Docker)
│   ├── venue-status/           #   Venue health check
│   ├── grid-test/              #   Smoke test venue operations
│   ├── workspace/              #   Browse/read/write lattice data
│   ├── secret/                 #   Manage API keys and credentials
│   ├── telegram/               #   Connect a venue to Telegram (bots → agents, send, allow-list)
│   ├── federation/             #   Cross-venue grid operations
│   ├── hitl/                   #   Human-in-the-Loop requests (send, respond, teach agents, test)
│   └── ucan/                   #   Capability token management
├── deploy/                     # Deployment: operator guide (README.md), Caddyfile,
│                               #   azure/ec2 (dev venues), gcp (stable venues), docker/
├── Dockerfile                  # Primary container build (Alpine, Java 25)
└── BUILD.md                    # Build and release workflow
```

## Requirements

- **Java 21+** (JDK; the published Docker image runs on Java 25)
- **Maven 3.7+** (enforced by maven-enforcer-plugin)
- **Convex 0.8.12** — released artifacts resolve directly from Maven Central, including the explicit JWT `kid` API required by #352. No sibling Convex checkout or source build is required.

## Build & Run

```bash
# Full build (all modules)
mvn clean install

# Build without tests
mvn clean install -DskipTests

# Run venue server (default config, port 8080)
java -jar venue/target/covia.jar

# Run with the checked-in ephemeral dev config (temp store, wiped on exit)
java -jar venue/target/covia.jar local-dev.json

# Run with a personal persistent config (gitignored under /dev/;
# stable DID, etch+secrets survive restarts — see venue-setup skill)
java -jar venue/target/covia.jar dev/local.json

# Run tests
mvn test

# Test specific module
mvn test -pl venue
mvn test -pl covia-core
```

**Main class:** `covia.venue.MainVenue`
**Executable JAR:** `venue/target/covia.jar` (fat JAR with all dependencies)

## Branch Strategy

`develop` for active development, `master` for releases. See `BUILD.md` for the full release flow.

## Key Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Convex | 0.8.12 | Lattice platform, immutable data, cryptography |
| Javalin | 7.2.2 | HTTP server with OpenAPI/Swagger/ReDoc |
| LangChain4j | 1.18.1 | LLM orchestration (OpenAI, Ollama, Gemini, DeepSeek) |
| MCP SDK | 2.0.0 | Model Context Protocol |
| A2A | 1.2.0.Final | Agent-to-Agent protocol |
| JUnit | 6.1.3 | Testing |
| SLF4J/Logback | 2.0.18/1.6.1 | Logging |

## Architecture Overview

```
Client (REST / MCP / A2A)
    |
VenueServer (Javalin HTTP, OpenAPI)
    |
CoviaAPI / UserAPI / MCP / A2A endpoints
    |
Engine (core state, adapters, assets, content, identity)
    ├── Asset Registry    (content-addressed by CAD3 hash, lattice-backed)
    ├── Adapter Registry  (pluggable execution backends)
    ├── Content Storage   (lattice / file / memory)
    └── JobManager        (job lifecycle, per-user persistence, recovery)
    |
Adapter Layer (~25 pluggable adapters — canonical table in venue/CLAUDE.md)
    ├── Data & state:  covia (lattice CRUD), asset, dlfs, vault, memory, secret, file, archive
    ├── Execution:     langchain (LLMs), mcp, http, convex, jvm, schema, orchestrator, scheduler
    ├── Agents:        agent, llmagent, goaltree, skills, hitl (COG-16 h/ inbox)
    ├── Federation:    grid (run/invoke/jobStatus), ucan (granting surface, COG-17)
    ├── Admin:         user (registration), venue (runtime adapter/module lifecycle)
    └── Testing:       test (echo, delay, never, chat, pause, ...)
```

### Core Abstractions

- **Asset** — Immutable content-addressed resource (CAD3 value hash of metadata). Can be an operation, artifact, or reference.
- **Operation** — An Asset with an `"operation"` field; executable via an adapter with JSON Schema input/output.
- **Job** — Execution state for an operation invocation. Jobs are long-lived and have NO framework-level timeout (they can run for days, weeks, or months). Lifecycle: `PENDING → STARTED → COMPLETE | FAILED | CANCELLED | REJECTED` (also `PAUSED`, `INPUT_REQUIRED`, `AUTH_REQUIRED`). Clients may time out polling and reconnect; after reconnect they re-acquire the latest job status by ID.
- **Venue** — A grid node that hosts operations and manages state. Identified by DID.
- **Adapter** — Bridges operations to execution environments. Extends `AAdapter`, receives resolved metadata and a `RequestContext` for every invocation.
- **Lattice** — CRDT-based persistent state with merge semantics (commutative, associative, idempotent).

### Lattice State Structure

Defined in code at `venue/src/main/java/covia/lattice/Covia.java`. Full design in `venue/docs/GRID_LATTICE_DESIGN.md`.

### Protocols

- **REST** — `/api/v1/` with OpenAPI docs at `/swagger` and `/redoc`
- **SSE** — Server-sent events for real-time job updates (`/api/v1/jobs/{id}/sse`)
- **MCP** — Model Context Protocol JSON-RPC endpoint
- **A2A** — Agent-to-Agent federated protocol
- **DID** — Decentralized identifiers for venue discovery (`/.well-known/did.json`). A venue may declare `did:web:<hostname>` as its stable identity; otherwise its key-derived `did:key` remains the identity. Consumers preserve the presented DID as-is (`alsoKnownAs` is informational, never a rebinding instruction). Remote routing and signature verification dispatch by DID method: `did:key` and `did:web` are built in, while future methods such as `did:convex` plug in without changing federation or UCAN code (#167, #343).

## Development Conventions

- **Package naming:** `covia.<module>.<feature>` (e.g., `covia.venue.api`, `covia.adapter`, `covia.grid.auth`)
- **Constants:** Use `Strings.intern()` for field names and status strings (see `Fields.java`, `Status.java`)
- **Async:** Return `CompletableFuture` from adapters; use virtual threads for IO-bound work
- **Immutability:** Use Convex ACell hierarchy for persistent data (AMap, AVector, Index)
- **Content addressing:** Assets identified by CAD3 value hash (SHA3-256 of canonical encoding)
- **Jobs:** Use `engine.jobs()` accessor for all job operations (submit, query, cancel, etc.)
- **Tests:** JUnit 6, use `Engine.createTemp()` for test instances
- **Prefer editing** existing files over creating new ones

### Adding a New Adapter

1. Create class extending `AAdapter` in `covia.adapter`
2. Implement `getName()`, `getDescription()`, and the invocation method (receives `RequestContext`, resolved metadata, and input)
3. Use `getSubOperation(meta)` to extract the adapter-specific operation name from metadata
4. Override `installAssets()` to register default operations
   - Override `info()` to publish adapter-owned facts (mount points, enabled features — anything a client needs to know that is not an operation) into `v/info/adapters/<name>`; it is re-read after every `configure`
   - Ship the adapter's agent skill with it: `installSkill("<name>", "/skills/<name>.json")` in `installAssets()` (resource under `src/main/resources/skills/`), so `v/skills/<name>` is present exactly when the adapter is active
   - Everything an adapter installs or publishes also appears under its own subtree `v/adapters/<name>/` (`info`, `config` — only what `publicConfig()` explicitly allow-lists (`publicConfig("maxItems", …)`), nothing by default, `ops/`, `skills/`, `templates/`), published and retracted with the adapter
5. Create JSON asset definitions in `venue/src/main/resources/adapters/{name}/`
6. Register in `Engine.addDemoAssets()` or via configuration

The engine always resolves operation references to metadata before dispatching — adapters never receive null metadata. For adapters that need direct job control (multi-turn, orchestration), override the job-aware invocation method instead.

### Asset Metadata Format

```json
{
  "name": "Operation Name",
  "description": "LLM-friendly description",
  "creator": "Author",
  "dateCreated": "ISO8601",
  "operation": {
    "adapter": "adaptername:operation",
    "input": { "type": "object", "properties": {...}, "required": [...] },
    "output": { "type": "object", "properties": {...} }
  }
}
```

## Current State

### What Works Well

- Clean adapter abstraction with many pluggable backends
- Lattice foundation with CRDT merge semantics
- Content-addressed assets (CAD3 value hash)
- Async job model with CompletableFuture and SSE
- Per-user job persistence and ownership enforcement
- Multi-protocol support (REST, MCP, A2A, DID)
- Federated cross-venue invocation via GridAdapter
- Strategy-pattern auth (NoAuth, Bearer, KeyPair, Local)

### In Progress

- **Capability enforcement** — largely landed: point-of-action `requireCapability` (adapter-pinned) + the single cross-user gate `Engine.crossUserAllows` (public-user parity #254 + UCAN proofs); granting is production-gated at surfaces (`ucan:issue`, HITL) per COG-17. See `venue/docs/UCAN.md`. Remaining: custodial attestation trust policy (C3b), resource-precise pins for agent-state mutations.

---

## TODOs

The list below tracks engineering tasks. For the developer-experience and open-source-readiness roadmap (onboarding, packaging, CI gate, versioning, docs, community scaffolding, operability), see **`DX_PLAN.md`** — the public-facing companion to this section.

### P0 — Critical (blocks production use)

- [x] Authorization enforcement, agent workspace CRUD (`/w/`, `/o/`, `/h/`), UCAN capability enforcement — shipped; see `venue/docs/UCAN.md` and COG-13/16/17.

### P1 — High (security and reliability)

- [x] Secure credential handling — per-user encrypted SecretStore, `secretFields` redaction, `s/NAME` resolution (public-store fallback, #254); capability-gated `secret:extract` still pending.

- [ ] **Per-operation rate limiting** — request-rate and concurrent-job caps exist (see `venue/docs/CONFIG.md`); per-operation limits do not.

### P2 — Medium (code quality and operability)

- [x] Decompose Engine.java — `JobManager` extracted; callers use `engine.jobs()`.

- [ ] **Wire LatticeContent into pinned content-addressable storage** — the `AContent` view over content pinned in the lattice `:data` region (content rides state replication, addressed by hash; pairs with `asset:pin`). Implemented and unit-tested; awaiting its consumer in the storage backend / client SDK.
  - File: `covia-core/.../grid/impl/LatticeContent.java`

- [x] **Add VenueHTTP test coverage** — real-venue contract tests cover direct run, status, polling and caller-side timeouts, content round-trips, concurrent use, authentication, and error paths. Deterministic client tests cover 429 retry/backoff behavior.
  - Files: `venue/src/test/java/covia/grid/client/VenueHTTPTest.java`, `covia-core/src/test/java/covia/grid/client/VenueHTTPRetryTest.java`

- [x] **Complete auth strategy tests** — `KeyPairAuth` has deterministic claim/signing tests, bearer authentication and rejection paths run against real venues, and unsupported token minting is covered. Focused tests also cover constructor/header behavior for `NoAuth` and `BearerAuth`, plus `LocalAuth` DID propagation and no-header behavior through the in-process path.
  - Directory: `covia-core/src/test/java/`

- [x] **Add SSRF and CORS regression coverage** — HTTPAdapter allow/block policy, private/loopback targets, invalid schemes, configured CORS origins, loopback/PNA behavior, and disabled CORS are covered.
  - Files: `venue/src/test/java/covia/adapter/http/HTTPTest.java`, `venue/src/test/java/covia/venue/VenueServerTest.java`

- [x] **Add remaining focused test coverage**:
  - `/config` page redaction (public info only)
  - LangChainAdapter IO timeout
  - Thread safety of `Asset.meta()` (concurrent access)

- [ ] **Structured logging** — Switch to JSON log format for production observability. Add request ID propagation.
  - File: `venue/src/main/resources/logback.xml`

- [ ] **Metrics export** — Add Prometheus-compatible metrics for operations, jobs, adapters, storage.

### P3 — Future (design goals from venue/CLAUDE.md)

- [ ] **Asset versioning** — Track version history, deprecation, and lineage in lattice
- [ ] **Cross-venue trust policies** — Policy-based access control between venues; venue reputation/attestation. Self-sovereign cross-venue grants + authority forwarding (identity tokens, `venue/relay` delegations) shipped (covia#100 C3a, `venue/docs/UCAN.md` §5.6); remaining = custodial attestation + the venue trust policy (C3b)
- [ ] **Capability negotiation** — Discovery endpoint for venue capabilities via DID documents
- [ ] **Signed operations** — Cryptographic attribution for every job submission
- [ ] **Compliance reporting** — Data lineage tracking and audit log queries
- [ ] **Workbench expansion** — Currently a 3-file / ~155-line demo; add configuration, multi-operation support, proper logging
- [ ] **Job restart API** — Consider `PUT /api/v1/jobs/{id}/restart` for re-running failed/cancelled/completed jobs. Semantics need thought: new job with same input? Same job ID? How to handle operations that have changed since original invocation? May be better as a client-side convenience (re-invoke with original params) rather than a server primitive.

## Module-Specific Guides

- **DX_PLAN.md** — Public developer-experience roadmap: onboarding, packaging, build reproducibility, CI quality gate, versioning, docs, community scaffolding, open-core boundary, operability
- **venue/docs/GRID_LATTICE_DESIGN.md** — Grid lattice design: addressing, namespaces, UCAN capabilities, federation, agents, lattice mechanics, implementation phases
- **venue/CLAUDE.md** — Detailed venue module architecture, design objectives, adapter reference, API endpoints, and development guidelines
- **venue/CLAUDE.local.md** — Working notes on lattice persistence implementation progress

## Skills

Reusable Claude Code skills live in `skills/` (tracked in git). Claude Code reads them from `.claude/skills/`, which is a local junction to `skills/`.

### Setup

The junction must be created once per checkout (it's gitignored):

```bash
# Windows (from covia root)
cmd /c "mklink /J .claude\skills skills"

# macOS / Linux
ln -s ../skills .claude/skills
```

Skills then work as `/skill-name` in **CLI**, **Desktop Chat**, and **IDE Cowork** modes. Example: `/ap-demo setup`, `/venue-setup local`, `/agent create MyAgent`.

### Shared Configuration

`.mcp.json` (committed) defines the shared MCP server config; `.claude/settings.json` (committed) holds tool permissions. Environment-specific overrides go in `.claude/settings.local.json` (gitignored).

### Discovering Skills

Skills are self-describing: each `skills/<name>/SKILL.md` carries a frontmatter
`description` that the Claude Code harness surfaces automatically in-session —
no list is maintained here (it would only drift). Browse `skills/` for the
full set; invoke as `/<name>`.

## Resources

- **Docs:** https://docs.covia.ai
- **Discord:** https://discord.gg/fywdrKd8QT
- **GitHub:** https://github.com/covia-ai/covia
- **Convex (dependency):** https://github.com/Convex-Dev/convex
