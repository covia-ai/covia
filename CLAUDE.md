# Covia - Federated AI Orchestration Platform

## Vision

Covia is the open-source infrastructure for federated AI orchestration. It enables AI models, agents, and data to collaborate across organisational boundaries, clouds, and jurisdictions — with built-in governance and without centralising control. Built on the Convex lattice platform for decentralised coordination.

## Project Structure

```
covia/                          # ai.covia:covia:0.0.2-SNAPSHOT (parent POM)
├── covia-core/                 # Grid client library and shared abstractions
│   └── src/main/java/covia/
│       ├── api/                #   API field constants (Fields.java)
│       ├── exception/          #   Exception hierarchy (CoviaException, etc.)
│       ├── grid/               #   Core types: Asset, Job, Operation, Grid, Venue, Status
│       ├── grid/auth/          #   Auth strategies: NoAuth, BearerAuth, KeyPairAuth, LocalAuth
│       ├── grid/client/        #   HTTP client implementation (VenueHTTP)
│       └── grid/impl/          #   Content implementations (BlobContent, LatticeContent)
├── venue/                      # Main venue server runtime (produces covia.jar)
│   └── src/main/java/covia/
│       ├── adapter/            #   Adapter framework + 12 implementations
│       ├── lattice/            #   Lattice definitions (Covia.ROOT, Covia.VENUE)
│       ├── venue/              #   Engine, MainVenue, Config, Auth, LocalVenue
│       ├── venue/api/          #   REST API (CoviaAPI), MCP, A2A, UserAPI
│       ├── venue/server/       #   HTTP server (VenueServer, CoviaWebApp, SSE, AuthMiddleware)
│       └── venue/storage/      #   Storage backends (Lattice, File, Memory)
├── workbench/                  # Minimal Swing GUI REPL for demo/testing
│   └── src/main/java/covia/gui/  Bench, ReplPanel, LAF
├── deploy/                     # Deployment configs (Caddyfile, config templates)
├── Dockerfile                  # Container build (Alpine, Java 25)
├── BUILD.md                    # Build and release workflow
└── DEPLOY.md                   # Operator deployment guide
```

## Requirements

- **Java 21+** (JDK)
- **Maven 3.7+** (enforced by maven-enforcer-plugin)
- **Convex 0.8.4-SNAPSHOT** must be installed in local Maven repo (`cd ../convex && mvn clean install`)

## Build & Run

```bash
# Full build (all modules)
mvn clean install

# Build without tests
mvn clean install -DskipTests

# Run venue server (default config, port 8080)
java -jar venue/target/covia.jar

# Run with custom config
java -jar venue/target/covia.jar config.json

# Run tests
mvn test

# Test specific module
mvn test -pl venue
mvn test -pl covia-core
```

**Main class:** `covia.venue.MainVenue`
**Executable JAR:** `venue/target/covia.jar` (fat JAR with all dependencies)

## Key Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Convex | 0.8.4-SNAPSHOT | Lattice platform, immutable data, cryptography |
| Javalin | 6.7.0 | HTTP server with OpenAPI/Swagger/ReDoc |
| LangChain4j | 1.5.0 | LLM orchestration (OpenAI, Ollama, Gemini, DeepSeek) |
| MCP SDK | 0.12.1 | Model Context Protocol |
| JUnit | 6.0.1 | Testing |
| SLF4J/Logback | 2.0.17/1.5.18 | Logging |

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
Adapter Layer
    ├── GridAdapter       — cross-venue federation (grid:run, grid:invoke)
    ├── LangChainAdapter  — LLM inference (openai, ollama, gemini, deepseek)
    ├── MCPAdapter        — MCP tool discovery and invocation
    ├── ConvexAdapter     — blockchain queries and transactions
    ├── HTTPAdapter       — outbound HTTP requests
    ├── Orchestrator      — multi-step workflow coordination
    ├── JVMAdapter        — string utilities
    ├── CoviaAdapter      — internal covia operations
    ├── AgentAdapter      — agent lifecycle (create, message, run)
    ├── LLMAgentAdapter   — LLM-backed agent transitions (chat)
    ├── SecretAdapter      — secret store operations (set, extract)
    └── TestAdapter       — echo, delay, error simulation, chat
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
- **DID** — Decentralized identifiers for venue discovery (`/.well-known/did.json`)

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

## Current State (as of 2026-02)

### What Works Well

- Clean adapter abstraction with 9 pluggable backends
- Lattice foundation with CRDT merge semantics
- Content-addressed assets (CAD3 value hash)
- Async job model with CompletableFuture and SSE
- Per-user job persistence and ownership enforcement
- Multi-protocol support (REST, MCP, A2A, DID)
- Federated cross-venue invocation via GridAdapter
- Strategy-pattern auth (NoAuth, Bearer, KeyPair, Local)

### In Progress

- **Capability enforcement** — `:caps` lattice slot present but not yet enforced; UCAN `with`/`can` checking planned for Phase 3/4

---

## TODOs

### P0 — Critical (blocks production use)

- [x] **Add authorization enforcement** — Job ownership enforced via `AccessControl` + `JobManager`. Per-user job persistence. Capability enforcement (UCAN `with`/`can`) planned for Phase 3/4.

### P1 — High (security and reliability)

- [x] **Secure credential handling** — SecretStore provides per-user encrypted storage. `secretFields` in operation metadata redacts sensitive fields in stored job records (both input and output). `secret:set` operation for storing secrets. Secret references (`s/NAME`) resolved at invocation time via `engine.resolveSecret()`. Capability-gated `secret:extract` planned.

- [ ] **Add rate limiting** — No rate limiting anywhere (operations, uploads, outbound requests). Add per-user and per-operation limits.
  - Files: `venue/.../venue/server/VenueServer.java`, `venue/.../venue/Engine.java`

### P2 — Medium (code quality and operability)

- [x] **Decompose Engine.java** — `JobManager` extracted for job lifecycle. Callers use `engine.jobs()`.

- [ ] **Complete LatticeContent** — Missing constructor and field initialization; cannot be properly instantiated.
  - File: `covia-core/.../grid/impl/LatticeContent.java`

- [ ] **Add VenueHTTP test coverage** — HTTP client layer has zero tests. Cover invoke, polling, content upload/download, error handling.
  - Directory: `covia-core/src/test/java/`

- [ ] **Add auth strategy tests** — No tests for BearerAuth, KeyPairAuth, NoAuth, LocalAuth.
  - Directory: `covia-core/src/test/java/`

- [ ] **Add missing test coverage** — Several implemented features lack dedicated tests:
  - SSRF protection in HTTPAdapter (URL allowlist/blocklist validation)
  - CORS configuration (`Config.CORS`)
  - /config endpoint redaction (public info only)
  - LangChainAdapter IO timeout
  - Thread safety of `Asset.meta()` (concurrent access)

- [ ] **Structured logging** — Switch to JSON log format for production observability. Add request ID propagation.
  - File: `venue/src/main/resources/logback.xml`

- [ ] **Metrics export** — Add Prometheus-compatible metrics for operations, jobs, adapters, storage.

### P3 — Future (design goals from venue/CLAUDE.md)

- [ ] **Asset versioning** — Track version history, deprecation, and lineage in lattice
- [ ] **Cross-venue trust policies** — Policy-based access control between venues; venue reputation/attestation
- [ ] **Capability negotiation** — Discovery endpoint for venue capabilities via DID documents
- [ ] **Signed operations** — Cryptographic attribution for every job submission
- [ ] **Compliance reporting** — Data lineage tracking and audit log queries
- [ ] **Workbench expansion** — Currently 3 files / 180 LOC demo; add configuration, multi-operation support, proper logging
- [ ] **Job restart API** — Consider `PUT /api/v1/jobs/{id}/restart` for re-running failed/cancelled/completed jobs. Semantics need thought: new job with same input? Same job ID? How to handle operations that have changed since original invocation? May be better as a client-side convenience (re-invoke with original params) rather than a server primitive.

## Module-Specific Guides

- **venue/docs/GRID_LATTICE_DESIGN.md** — Grid lattice design: addressing, namespaces, UCAN capabilities, federation, agents, lattice mechanics, implementation phases
- **venue/CLAUDE.md** — Detailed venue module architecture, design objectives, adapter reference, API endpoints, and development guidelines
- **venue/CLAUDE.local.md** — Working notes on lattice persistence implementation progress

## Resources

- **Docs:** https://docs.covia.ai
- **Discord:** https://discord.gg/fywdrKd8QT
- **GitHub:** https://github.com/covia-ai/covia
- **Convex (dependency):** https://github.com/Convex-Dev/convex
