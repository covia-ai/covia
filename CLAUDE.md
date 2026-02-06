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
│       ├── adapter/            #   Adapter framework + 9 implementations
│       ├── lattice/            #   Lattice state types (GridLattice, VenueLattice, CASLattice)
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
- **Convex 0.8.3-SNAPSHOT** must be installed in local Maven repo (`cd ../convex && mvn clean install`)

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
| Convex | 0.8.3-SNAPSHOT | Lattice platform, immutable data, cryptography |
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
Engine (core execution, lattice state)
    ├── Asset Registry    (content-addressed by SHA256, lattice-backed)
    ├── Job Tracker       (status lifecycle, async CompletableFuture)
    ├── Adapter Registry  (pluggable execution backends)
    └── Content Storage   (lattice / file / memory)
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
    └── TestAdapter       — echo, delay, error simulation, chat
```

### Core Abstractions

- **Asset** — Immutable content-addressed resource (SHA256 hash of metadata). Can be an operation, artifact, or reference.
- **Operation** — An Asset with an `"operation"` field; executable via an adapter with JSON Schema input/output.
- **Job** — Execution state for an operation invocation. Lifecycle: `PENDING → STARTED → COMPLETE | FAILED | CANCELLED | REJECTED` (also `PAUSED`, `INPUT_REQUIRED`, `AUTH_REQUIRED`).
- **Venue** — A grid node that hosts operations and manages state. Identified by DID.
- **Adapter** — Bridges operations to execution environments. Extends `AAdapter`, implements `invokeFuture()`.
- **Lattice** — CRDT-based persistent state with merge semantics (commutative, associative, idempotent).

### Lattice State Structure

```
:grid → GridLattice
  :venues → Index<VenueDID, VenueLattice>
    :assets  → Index<Hash, AssetRecord>     (union merge)
    :jobs    → Index<AString, JobRecord>     (timestamp merge)
    :users   → Index<AString, UserRecord>    (timestamp merge)
    :storage → Index<Hash, ABlob>            (CAS merge)
  :meta → Index<Hash, AString>              (shared content-addressed metadata)
```

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
- **Content addressing:** Assets identified by SHA256 hash of metadata JSON string
- **Tests:** JUnit 6, use `Engine.createTemp()` for test instances
- **Prefer editing** existing files over creating new ones

### Adding a New Adapter

1. Create class extending `AAdapter` in `covia.adapter`
2. Implement `getName()`, `getDescription()`, `invokeFuture()`
3. Override `installAssets()` to register default operations
4. Create JSON asset definitions in `venue/src/main/resources/adapters/{name}/`
5. Register in `Engine.addDemoAssets()` or via configuration

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
- Lattice foundation with CRDT merge semantics (Phase 1 complete, 35+ tests)
- Content-addressed assets with SHA256 integrity
- Async job model with CompletableFuture and SSE
- Multi-protocol support (REST, MCP, A2A, DID)
- OpenAPI self-documentation (Swagger/ReDoc)
- Federated cross-venue invocation via GridAdapter
- Strategy-pattern auth (NoAuth, Bearer, KeyPair, Local)

### In Progress

- **Phase 2: Job persistence to lattice** — VenueLattice has `:jobs` index but Engine still uses volatile HashMap

---

## TODOs

### P0 — Critical (blocks production use)

- [ ] **Persist jobs to lattice** — Engine stores jobs in `HashMap<AString, Job>` which is lost on restart. Wire up to `VenueLattice :jobs` index using timestamp-based merge. The lattice infrastructure already exists.
  - Files: `venue/.../venue/Engine.java` (jobs HashMap), `venue/.../lattice/VenueLattice.java` (:jobs index)

- [ ] **Add authorization enforcement** — AuthMiddleware extracts caller DID but `Engine.invokeOperation()` never checks permissions. Implement capability-based access control so venues can restrict which users/agents can invoke which operations.
  - Files: `venue/.../venue/Engine.java`, `venue/.../venue/Auth.java`, `venue/.../server/AuthMiddleware.java`

- [ ] **Add timeout to VenueHTTP polling** — `waitForFinish()` loops indefinitely with exponential backoff but no maximum wait. Network issues or stuck jobs will hang the client forever. Use the existing unused `timeout` field.
  - File: `covia-core/.../grid/client/VenueHTTP.java` (waitForFinish method)

- [ ] **Fix binary content handling** — `VenueHTTP.getContent()` reads binary HTTP responses as `String` then converts to bytes, corrupting non-text content. Should use `BodyHandlers.ofByteArray()`.
  - File: `covia-core/.../grid/client/VenueHTTP.java` (getContent method)

- [ ] **Stream content uploads** — `Engine.putContent()` reads entire input stream into memory before storing. No size limits. Large uploads can crash the server. Add streaming with configurable size limits.
  - File: `venue/.../venue/Engine.java` (putContent method)

### P1 — High (security and reliability)

- [ ] **URL validation in HTTPAdapter** — Accepts arbitrary URLs with no allowlist or validation, creating SSRF risk. Add configurable URL allowlist/blocklist.
  - File: `venue/.../adapter/HTTPAdapter.java`

- [ ] **Secure credential handling in LangChainAdapter** — API keys passed in plaintext JSON input, falls back to env vars. Consider secure credential storage or reference-based lookup.
  - File: `venue/.../adapter/LangChainAdapter.java`

- [ ] **Restrict CORS** — VenueServer accepts `*` origins. Make CORS configurable per venue.
  - File: `venue/.../venue/server/VenueServer.java`

- [ ] **Protect or remove /config endpoint** — Exposes entire engine configuration including potential secrets.
  - File: `venue/.../venue/server/CoviaWebApp.java`

- [ ] **Add adapter-level timeouts** — No timeout enforcement on adapter execution. Long-running or hung operations block indefinitely. Add `CompletableFuture.orTimeout()`.
  - File: `venue/.../adapter/AAdapter.java`, `venue/.../venue/Engine.java`

- [ ] **Standardize exception handling** — VenueHTTP mixes RuntimeException, ResponseException, and null returns. Define consistent error propagation strategy.
  - File: `covia-core/.../grid/client/VenueHTTP.java`

- [ ] **Add rate limiting** — No rate limiting anywhere (operations, uploads, outbound requests). Add per-user and per-operation limits.
  - Files: `venue/.../venue/server/VenueServer.java`, `venue/.../venue/Engine.java`

- [ ] **Thread safety in Asset.meta()** — Metadata field cached without synchronization; concurrent threads may parse redundantly. Add synchronization.
  - File: `covia-core/.../grid/Asset.java`

### P2 — Medium (code quality and operability)

- [ ] **Decompose Engine.java** — At 982 lines, Engine handles too many concerns (assets, jobs, adapters, storage, auth, stats). Extract focused classes.
  - File: `venue/.../venue/Engine.java`

- [ ] **Complete LatticeContent** — Missing constructor and field initialization; cannot be properly instantiated.
  - File: `covia-core/.../grid/impl/LatticeContent.java`

- [ ] **Add VenueHTTP test coverage** — HTTP client layer has zero tests. Cover invoke, polling, content upload/download, error handling.
  - Directory: `covia-core/src/test/java/`

- [ ] **Add auth strategy tests** — No tests for BearerAuth, KeyPairAuth, NoAuth, LocalAuth.
  - Directory: `covia-core/src/test/java/`

- [ ] **Structured logging** — Switch to JSON log format for production observability. Add request ID propagation.
  - File: `venue/src/main/resources/logback.xml`

- [ ] **Metrics export** — Add Prometheus-compatible metrics for operations, jobs, adapters, storage.

- [ ] **Remove unused timeout field** — VenueHTTP has `timeout = 5000` that is set but never read. Either use it or remove it.
  - File: `covia-core/.../grid/client/VenueHTTP.java`

- [ ] **Fix typos** — `Fields.java:34` has `fineName` (should be `fileName`), `Asset.java:66` has "must me" (should be "must be"), `VenueHTTP.java` has "resturned" (should be "returned")

### P3 — Future (design goals from venue/CLAUDE.md)

- [ ] **Asset versioning** — Track version history, deprecation, and lineage in lattice
- [ ] **Cross-venue trust policies** — Policy-based access control between venues; venue reputation/attestation
- [ ] **Capability negotiation** — Discovery endpoint for venue capabilities via DID documents
- [ ] **Signed operations** — Cryptographic attribution for every job submission
- [ ] **Compliance reporting** — Data lineage tracking and audit log queries
- [ ] **Workbench expansion** — Currently 3 files / 180 LOC demo; add configuration, multi-operation support, proper logging

## Module-Specific Guides

- **venue/CLAUDE.md** — Detailed venue module architecture, design objectives, adapter reference, API endpoints, and development guidelines
- **venue/CLAUDE.local.md** — Working notes on lattice persistence implementation (Phase 1 complete, Phase 2 next)

## Resources

- **Docs:** https://docs.covia.ai
- **Discord:** https://discord.gg/fywdrKd8QT
- **GitHub:** https://github.com/covia-ai/covia
- **Convex (dependency):** https://github.com/Convex-Dev/convex
