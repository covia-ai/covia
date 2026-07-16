# Covia Venue - Development Guide

## Overview

The **venue** module is the core runtime server for Covia - a federated AI orchestration platform. A Venue is a node in the Covia Grid that hosts and executes operations, manages assets, and participates in the federated network.

**Main Entry Point:** `covia.venue.MainVenue`
**Core Engine:** `covia.venue.Engine`
**Technology Stack:** Java 21, Maven, Convex Lattice, Javalin

## Project Structure

```
venue/
├── src/main/java/covia/
│   ├── venue/           # Core venue runtime
│   │   ├── Engine.java          # Core state: adapters, assets, content, identity (~770 lines)
│   │   ├── JobManager.java      # Job lifecycle: submit, query, persist, recover (~720 lines)
│   │   ├── VenueState.java      # Lattice state wrapper (assets, jobs, users, auth cursors)
│   │   ├── Users.java           # Per-user lattice wrapper (:user-data cursor)
│   │   ├── User.java            # Single user's lattice state (jobs, workspace)
│   │   ├── AccessControl.java   # Job ownership enforcement, capability checking
│   │   ├── MainVenue.java       # Application entry point
│   │   ├── LocalVenue.java      # Local venue implementation (delegates to Engine + JobManager)
│   │   ├── RequestContext.java   # Caller identity context (INTERNAL, ANONYMOUS, authenticated)
│   │   ├── api/                 # REST API (CoviaAPI.java)
│   │   ├── server/              # HTTP server configuration
│   │   ├── storage/             # Content storage abstractions
│   │   ├── lattice/             # Lattice cursor management
│   │   └── auth/                # Authentication/authorization
│   └── adapter/         # Adapter implementations
│       ├── AAdapter.java        # Abstract base adapter
│       ├── GridAdapter.java     # Federated grid operations
│       ├── ConvexAdapter.java   # Convex blockchain operations
│       ├── MCPAdapter.java      # Model Context Protocol
│       ├── LangChainAdapter.java # AI/LLM integration
│       └── ...
├── src/main/resources/
│   ├── adapters/        # Adapter asset definitions (JSON)
│   └── asset-examples/  # Example asset metadata
└── pom.xml
```

## Design Objectives

The following objectives should guide all development work on operations and assets:

### 1. Universal Capabilities via the Grid API

Operations and assets must be **universally exposable** across the federated grid network:

- **Protocol Agnostic:** Assets should be invocable via REST API, MCP, direct Java calls, or any future protocol
- **Self-Describing:** Every asset must carry complete metadata (JSON schema for inputs/outputs, descriptions, versioning)
- **Interoperable:** Operations should work seamlessly whether executed locally or on a remote venue
- **Discoverable:** Assets should be queryable and browsable by agents and humans alike

**Current Pattern:**
```json
{
  "name": "Operation Name",
  "description": "LLM-friendly description of what this does",
  "operation": {
    "adapter": "adapter:operation",
    "input": { "type": "object", "properties": {...} },
    "output": { "type": "object", "properties": {...} }
  }
}
```

**Design Goals:**
- Standardize asset metadata schema across all adapters
- Enable capability negotiation between venues
- Support versioned operations with backwards compatibility
- Provide rich semantic descriptions for AI agent consumption

### 2. Full Utilization of Convex Lattice Technology

Leverage Convex Lattice for **performance, power, and integrity**:

- **Immutable Data Structures:** All state changes use Convex's persistent data structures (AMap, AVector, Index)
- **Content-Addressed Storage:** Assets identified by CAD3 value hash (SHA3-256 of canonical encoding)
- **Conflict-Free Replication:** Lattice cursors enable distributed state without coordination overhead
- **Cryptographic Verification:** All data can be verified using Convex's hash-based integrity

**Lattice Structure:** Defined in `src/main/java/covia/lattice/Covia.java`. Full design in `docs/GRID_LATTICE_DESIGN.md`.

**Design Goals:**
- Enable cross-venue state synchronization via lattice merging
- Implement asset versioning with lattice-based history
- Use lattice for distributed consensus on shared operations

### 3. System of Record for Agents/Organizations

Venues must serve as a **trusted system of record**:

- **Audit Trail:** Every operation invocation produces an immutable job record
- **Provenance Tracking:** Track the origin, transformations, and ownership of all assets
- **Access Control:** Fine-grained permissions on who can invoke what operations
- **Accountability:** Signed operations with cryptographic attribution

**Job Lifecycle:**
```
PENDING -> STARTED -> COMPLETE | FAILED | CANCELLED | REJECTED
         └-> PAUSED -> INPUT_REQUIRED | AUTH_REQUIRED
```

**Design Goals:**
- Implement signed job submissions with DID-based identity
- Create queryable audit logs with lattice-backed storage
- Support organizational hierarchies and delegated permissions
- Enable compliance reporting and data lineage tracking

### 4. Federated Model with Decentralized Identity

The grid operates on a **federated trust model**:

- **Decentralized Identifiers (DIDs):** Each venue has a DID for identity (`did:key:...`)
- **Venue Trust:** Different venues can have different trust levels and capabilities
- **Cross-Venue Invocation:** Operations can delegate to remote venues via `grid:run` / `grid:invoke`
- **Data Sovereignty:** Data stays where it's controlled; only results cross boundaries

**DID Document Structure:**
```json
{
  "id": "did:key:z...",
  "@context": "https://www.w3.org/ns/did/v1",
  "verificationMethod": [...],
  "service": [{ "type": "CoviaGridEndpoint", "serviceEndpoint": "..." }]
}
```

**Design Goals:**
- Implement venue capability discovery via DID documents
- Support trust policies (which venues can invoke what)
- Enable credential delegation for cross-organization workflows
- Build reputation/attestation system for venue reliability

## Key Abstractions

### Asset (`covia.grid.Asset`)
An immutable, content-addressed resource with metadata. Assets can be:
- **Operations:** Executable capabilities with input/output schemas
- **Artifacts:** Arbitrary content (files, models, datasets)
- **References:** Pointers to external resources

### Operation (`covia.grid.Operation`)
A specialized Asset that can be invoked. Operations are:
- Identified by CAD3 value hash of their metadata
- Associated with an adapter that handles execution
- Self-describing via JSON Schema for inputs/outputs

### Job (`covia.grid.Job`)
A running or completed invocation of an operation:
- Has a unique ID (timestamp + counter + random)
- Tracks status, input, output, errors
- Supports async completion via CompletableFuture
- Can be paused, cancelled, or awaited
- Every job has an owner (caller DID) — required, never null

### JobManager (`covia.venue.JobManager`)
Manages the full job lifecycle. Accessed via `engine.jobs()`. Handles submission, queries, per-user lattice persistence, access control, and recovery on restart.

### Adapter (`covia.adapter.AAdapter`)
Bridges operations to execution environments:
- Installs assets on registration
- Receives resolved metadata and `RequestContext` for every invocation (meta is never null)
- Uses `getSubOperation(meta)` to extract the adapter-specific operation name
- Returns results asynchronously via `CompletableFuture`
- For multi-turn or orchestration adapters, override the job-aware `invoke` method for direct job control

## Adapter Reference

| Adapter | Purpose | Operations |
|---------|---------|------------|
| `grid` | Federated grid operations | `run`, `invoke`, `jobStatus`, `jobResult` |
| `convex` | Convex blockchain | `query`, `transact` |
| `mcp` | Model Context Protocol | `toolList`, `toolCall` |
| `langchain` | AI/LLM models | `openai`, `ollama`, `anthropic`, `gemini`, `deepseek` |
| `http` | HTTP requests (SSRF-protected) | `get`, `post` |
| `jvm` | JVM utilities | `stringConcat`, `urlEncode`, `urlDecode` |
| `file` | Filesystem (root-jailed; host/temp/DLFS-backed roots) | `roots`, `list`, `tree`, `read`, `write`, `append`, `delete`, `mkdir`, `stat` |
| `schema` | JSON Schema operations | `validate`, `validateAll`, `infer`, `coerce`, `check` |
| `orchestrator` | Multi-step workflows | Custom orchestration |
| `covia` | Lattice CRUD | `read`, `write`, `delete`, `append`, `slice`, `list`, `inspect`, `aggregate`, `functions`, `describe`, `adapters` |
| `asset` | Content-addressed assets | `store`, `get`, `getContent`, `list`, `pin` |
| `agent` | Agent lifecycle | `create`, `fork`, `request`, `message`, `trigger`, `query`, `list`, `delete`, `suspend`, `resume`, `update`, `cancelTask`, `deleteSession` |
| `llmagent` | LLM agent transitions | `chat` |
| `goaltree` | Goal-tree agent planning | `chat` |
| `dlfs` | Decentralised file system | `listDrives`, `createDrive`, `deleteDrive`, `list`, `read`, `write`, `mkdir`, `delete` |
| `vault` | Health vault (DLFS wrapper) | `read`, `write`, `list`, `mkdir`, `delete` |
| `secret` | Secret store | `set`, `extract` (removal via `covia:delete s/<name>`) |
| `memory` | Per-user agent memory — ONE `AVector` at a workspace path (default `w/memory`), every mutation a whole-vector LWW rewrite so removals never re-materialise; edited by 1-based position | `recall`, `remember`, `update`, `forget` |
| `ucan` | Capability tokens | `issue` |
| `scheduler` | Deferred grid-op invocation (per-venue `:schedule`) | `schedule`, `cancel`, `trigger`, `list` |
| `test` | Testing | `echo`, `delay`, `fail`, `never`, `random`, `chat`, `pause`, `taskComplete` |

## API Endpoints

Base path: `/api/v1/`

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/status` | GET | Venue status and health |
| `/assets/{id}` | GET | Retrieve asset metadata |
| `/assets` | POST | Register new asset |
| `/assets/{id}/content` | GET/PUT | Asset binary content |
| `/invoke` | POST | Execute an operation — async by default (201 + job record to poll); `?wait=true` blocks up to the 120s cap, `?wait=<ms>` up to that many ms (clamped), returning the finished record (200) |
| `/values/{read,list,slice,inspect,aggregate,count}` | GET | Job-free lattice reads (#177) — `?path=…`, synchronous, capability-checked, **no job persisted**. Shares `covia:*` read accessors. `aggregate`/`count` tally entries at a `depth`, optional `groupBy`. See `docs/READ_API.md` |
| `/agents`, `/agents/{id}` | GET | Job-free agent listings (#180) — the caller's own agents, sharing `agent:list`/`agent:info` accessors, **no job persisted**. `?status=true` for the status-annotated form, `?includeTerminated=true` to include terminated |
| `/jobs/{id}` | GET | Job status |
| `/jobs/{id}/sse` | GET | Server-sent events for job updates |
| `/.well-known/did.json` | GET | Venue DID document — `did:web:<hostname>` alias (canonical did:key in `alsoKnownAs`) when a public `hostname` is set, else the did:key document (#167) |

## Development Guidelines

### Adding a New Adapter

1. Create a class extending `AAdapter` in `covia.adapter`
2. Implement `getName()`, `getDescription()`, and the invocation method (receives `RequestContext`, resolved metadata, and input)
3. Use `getSubOperation(meta)` to extract the adapter-specific operation name from metadata
4. Override `installAssets()` to register default operations
5. Create JSON asset definitions in `src/main/resources/adapters/{name}/`
6. Register in `Engine.addDemoAssets()` or via configuration

The engine always resolves operation references to metadata before dispatching — adapters never receive null metadata. For adapters that need direct job control (multi-turn, orchestration), override the job-aware invocation method instead of the simple future-returning one.

### Creating Asset Metadata

Assets are defined as JSON with this structure:

```json
{
  "name": "Human-readable name",
  "description": "Detailed description for agents/humans",
  "creator": "Author or organization",
  "dateCreated": "ISO8601 timestamp",
  "operation": {
    "adapter": "adaptername:operation",
    "toolName": "mcpToolName",
    "input": {
      "type": "object",
      "properties": {
        "param1": { "type": "string", "description": "..." }
      },
      "required": ["param1"]
    },
    "output": {
      "type": "object",
      "properties": {
        "result": { "type": "string", "description": "..." }
      }
    }
  }
}
```

### Working with Jobs

```java
// All job operations go through engine.jobs() — never directly on Engine
Job job = engine.jobs().invokeOperation(opRef, input, callerDID);  // callerDID required
Job job = engine.jobs().invokeOperation(opRef, input, requestCtx); // or via RequestContext
ACell result = job.awaitResult();                                   // blocks until complete

// Query jobs (scoped by caller identity)
Index<Blob, ACell> myJobs = engine.jobs().getJobs(requestCtx);
AMap<AString, ACell> data = engine.jobs().getJobData(jobID, requestCtx);

// Lifecycle control
engine.jobs().cancelJob(jobID, requestCtx);
engine.jobs().pauseJob(jobID, requestCtx);
engine.jobs().resumeJob(jobID, requestCtx);
```

### Working with Lattice State

```java
// Via VenueState application wrappers (preferred)
Hash id = engine.storeAsset(metadataString, contentBlob);

// Per-user access (jobs, agents, secrets, workspace)
VenueState vs = engine.getVenueState();
User user = vs.users().ensure(callerDID);
Index<Blob, ACell> userJobs = user.getJobs();
```

### Testing

- Unit tests in `src/test/java/`
- Use `Engine.createTemp()` for test instances
- Asset examples in `src/main/resources/asset-examples/` for validation

## Configuration

### Persistence

Venue state (lattice, agents, secrets, DLFS) is persisted via Etch store:

```json
{
  "store": "/data/venue.etch",
  "seed": "hex-ed25519-seed"
}
```

- `store`: `"temp"` (default, deleted on exit), `"memory"`, or file path
- `seed`: Ed25519 hex seed for stable venue identity. If omitted with a persistent store, auto-generated and saved to `venue.key` alongside the store file.

### Venue identity

Identity resolution order: `seed` → `keystore` → `venue.key` next to a
persistent store → freshly generated. A venue on an ephemeral store
(`temp`/`memory`) without `seed`/`keystore` gets a **new DID every start —
by design** (#208): a stable identity is something the operator pins
explicitly, not something the venue persists behind their back.

For managed keys, point the venue at a PKCS12 keystore in the Convex format
(so keys are created/listed with the Convex CLI — `convex key generate`):

```json
{
  "keystore": {
    "path": "~/.convex/keystore.pfx",
    "alias": "<hex-public-key>",
    "storepass": "...",
    "keypass": "..."
  }
}
```

- `path`: defaults to `~/.convex/keystore.pfx` (env `CONVEX_KEYSTORE` fills absence)
- `alias`: required — the key entry to use (Convex convention: hex public key)
- `storepass` / `keypass`: env `CONVEX_KEYSTORE_PASSWORD` / `CONVEX_KEY_PASSWORD`
  fill absence; missing both config and env is a fatal startup error

Any keystore failure (missing file, bad password, unknown alias) is **fatal** —
the venue never silently falls back to a generated key. Likewise, booting an
existing store with a key that owns none of its venue state fails at startup
naming the store's real owner: venues are keyed by AccountKey, so a wrong key
would otherwise silently create a fresh empty venue and orphan the existing
data. Never commit keystore passwords; use env vars or gitignored dev configs.

### Network binding

```json
{
  "port": 8080,
  "bindAddress": "127.0.0.1"
}
```

- `port`: HTTP listen port (default `8080`).
- `bindAddress`: network interface the HTTP connector binds to. When omitted, the venue binds **all interfaces** (`0.0.0.0`) — reachable from the LAN. Set to `"127.0.0.1"` to restrict the venue to loopback (recommended when embedding the venue as a local subprocess). This is the socket bind address and is distinct from `hostname`, which is the venue's *advertised* public host used to derive `baseUrl`/DID.

### System tray

When `MainVenue` runs on a desktop (not headless), each venue gets a system
tray icon: hover shows the venue name, port and DID; the menu offers **Open
Venue** (status page in the browser — double-click does the same), **Close
Venue** (that venue; the process exits when the last one closes) and **Exit**
(all venues). Close/Exit run the full shutdown flush, same as SIGTERM.

Strictly best-effort — headless JVMs (Docker, CI, servers) and unsupported
desktops run without an icon, and a tray failure never takes a venue down.
Set `COVIA_NO_TRAY=1` to suppress it explicitly. See `covia.venue.Tray`.

### Rate limiting

```json
{
  "rateLimit": {
    "enabled": true,
    "rps": 100,
    "burst": 300,
    "maxConcurrentJobsPerUser": 100,
    "blockMs": 3000
  }
}
```

Two independent backpressure controls, keyed per caller identity (all anonymous
callers share the venue `:public` DID → one bucket).

- **Request rate** (`rps`, `burst`) — a per-caller token bucket on `/api/*`,
  `/mcp`, `/a2a*`. A denied request short-circuits with **429 + `Retry-After`**
  before any handler runs. Deliberately coarse/high — it's a flood backstop, not
  a normal-traffic gate; the job cap is the precise control.
- **Concurrent jobs** (`maxConcurrentJobsPerUser`) — admission control on
  top-level invokes: a caller at the cap **blocks** up to `blockMs` for a slot
  to free (a job completing releases it), then sheds with **429 + `Retry-After`**.
  Sub-jobs (orchestrator / agent fan-out, which carry a parent job id) are
  **exempt**, so internal fan-out is never throttled. Set `blockMs` under typical
  client read timeouts so a saturated caller gets a clean 429, not a socket
  timeout. Set the cap to `0` to disable it.

`enabled` defaults **on** for a LAN/public bind and **off** for a loopback bind
(the embedded-venue case, where the only caller is a trusted local process); an
explicit `enabled` always wins.

### Adapter configuration

```json
{
  "adapters": {
    "agent": { "sessionDelete": false }
  }
}
```

Per-adapter settings, keyed by adapter name (`Config.getAdapterConfig(name)`).
Currently defined:

- `agent.sessionDelete` — whether `agent:deleteSession` is available
  (default `true`). Set `false` to disable user-initiated session deletion
  venue-wide; the op then fails with "disabled on this venue".

### Private jobs

```json
{
  "enablePrivateJobs": true
}
```

Off by default. When enabled, an invoke with `private: true` (body field)
creates a **memory-only job** (#192): never persisted — no record in the
caller's job index, no lattice write, no recovery, gone on venue restart.
Use `wait` to collect the result; a completed private job is immediately
forgotten. A private request against a venue without this flag is an error —
never a silent downgrade to a persisted job. A private conversation is agent
intake (`agent:chat` / `agent:request`) invoked private; the session record
remains the (deletable, `agent:deleteSession`) conversation store.

**Operator telemetry is unaffected**: private controls the durable lattice
record, not operational visibility. The venue still logs job events (ID,
operation, status transitions, timings) per its logging config, live
job-update listeners (SSE, MCP notifications) still fire to authorized
subscribers, and stats counters still count. Note that log lines are
ID-and-status shaped as a rule, but failure messages can quote content
fragments — operators wanting content-clean logs address that via logging
policy (levels, appender redaction), not the job system.

### DLFS WebDAV

```json
{
  "webdav": { "enabled": true }
}
```

Mounts WebDAV at `/dlfs/` for file access to DLFS drives. Off by default.

### MCP tool bridging

```json
{
  "mcp": {
    "servers": {
      "github": { "url": "https://mcp.github.example", "auth": "s/GITHUB_MCP_TOKEN" }
    }
  }
}
```

Bridges external MCP tools into the catalog (#80): they materialise as
ordinary operations — capability grants, gates, job records and schema
validation all apply because they are ordinary ops. **The tool is the
entity**; the server is just where it lives. Two management styles:

- **Curated** (`v/ops/mcp/add-tool {server, tool, path, auth?, name?,
  description?, default?}`): one tool at a caller-chosen catalog path.
  Groups are just paths — `o/research/search_papers` and
  `o/research/github_search` can point at different servers with different
  auth. Registry-free (the asset is self-contained); remove with
  `covia:delete` on the path — nothing resurrects it. `name`/`description`
  overrides are yours and survive refresh. `default` purpose-shapes the
  tool with argument defaults (any value types; defaulted keys leave
  `required`; generic `operation.default` mechanism — `docs/OPERATIONS.md`
  §5): a generic five-field `create_issue` becomes a two-field
  `report_bug`.
- **Mirrored** (`v/ops/mcp/add-server {name, url, auth?, scope?}` /
  `remove-server`): ALL of a server's tools under `o/mcp/<server>/` (or
  `v/ops/mcp/<server>/` at venue scope), registry entry for bookkeeping.
  Config-declared servers (above) mirror at venue scope on boot —
  best-effort per server; one that is down logs a warning and the last-known
  catalog persists.

`v/ops/mcp/refresh` follows the same split: `{name}` reconciles a mirror
fully (vanished tools deleted); `{path}` refreshes curated tools in place —
schemas/annotations update, name/description untouched, vanished tools
**reported in `missing`, never deleted**. Exactly one of `name`/`path`.

Destination paths under the caller's own `o/` need nothing extra; venue-side
targets (`v/ops/...` or `scope: "venue"`) require the `mcp/manage` ability.
Server URLs pass the same SSRF validation (and operator allow/block lists) as
the http adapter. `auth` should be a secret reference (`s/<name>`, stored via
`v/ops/secret/set`; bare refs are stored DID-qualified to the registrar) —
resolved at call time, never persisted raw; raw tokens warn.

Bridged assets are self-contained — a hand-authored asset with
`operation: {adapter: "mcp:tools:call", remoteToolName, server, auth?}` works
without any registry entry. The bridged op's declared input IS the tool's own
schema, so the invocation input is passed directly as the tool arguments.
Failures are LLM-diagnosable at the point of use: a remote tool-level error
(`isError` per the MCP spec) fails the job with the remote error text;
transport failures name the tool, server and root cause with a remedy;
text-only tool results are preserved (structured content wins when present).

### A2A protocol

```json
{
  "a2a": {
    "defaultChatOp": "v/test/ops/echo",
    "agentInfo": {
      "name": "My Venue Agent",
      "description": "What this agent does",
      "organization": "Acme",
      "providerUrl": "https://acme.example"
    }
  }
}
```

Enables the A2A (Agent-to-Agent) protocol. Off by default — the endpoints are
registered **only** when an `a2a` block is present. Without it, `POST /a2a` and
`GET /.well-known/agent-card.json` return `501` with a hint pointing back here
(rather than an indistinguishable 404).

- `defaultChatOp` — the operation invoked on a fresh `message/send` (no
  `taskId`). Its Job becomes the A2A Task; its output becomes the Task's
  artifact. `v/test/ops/echo` needs no LLM secret and is handy for smoke tests;
  point it at an `llmagent`/`agent` chat op for a real agent.
- `agentInfo` — surfaced in the agent card (`name`/`description`, plus
  `organization`/`providerUrl` for the card's `provider`). All optional.

**Auth note:** `message/send` invokes `defaultChatOp` as the *calling*
identity. Under the default read-only public ceiling an unauthenticated caller
cannot invoke, so the Task comes back `TASK_STATE_FAILED`. To exercise
`message/send` from an unauthenticated client, either authenticate the caller
or widen `auth.public.caps` to permit the op — do the latter only on a
loopback-bound (`bindAddress: 127.0.0.1`) throwaway venue, never a
LAN-reachable one. The agent-card GET is public and works regardless.

### Secrets bootstrap

Per-venue config can pre-populate the encrypted per-user secret stores at startup:

```json
{
  "secrets": {
    "venue":  { "OPENAI_API_KEY": "sk-..." },
    "public": { "OPENAI_API_KEY": "sk-...", "ANTHROPIC_API_KEY": "sk-ant-..." },
    "did:key:z6MkAlice...": { "FOO": "bar" }
  }
}
```

Top-level keys resolve as follows:
- `"venue"` → the venue's own DID (used by venue-internal operations and self-issued requests)
- `"public"` → `<venueDID>:public`, the default identity for unauthenticated callers
- Anything else → used verbatim; expected to be a literal DID string

Each named secret overwrites any existing value under that name for that user — config is the source of truth at launch. Names not listed are left untouched. Per-secret failures log a warning but do not fail startup. Values are never logged.

**Never commit production secrets here.** Intended for personal dev configs in gitignored locations (e.g. `dev/local.json`).

## Build & Run

```bash
# Build
mvn clean install

# Run
java -jar target/covia.jar [config.json]

# Development
mvn compile && java -cp "target/classes:target/dependency/*" covia.venue.MainVenue
```

## Related Documentation

- **Main README:** `../README.md` - Project overview
- **Build Guide:** `../BUILD.md` - Detailed build instructions
- **Deploy Guide:** `../deploy/README.md` - Deployment options
- **Core Module:** `../covia-core/` - Grid client and shared abstractions
- **Online Docs:** https://docs.covia.ai

## Key Improvement Areas

When working on operations and assets, focus on:

1. **Schema Standardization:** Ensure all adapters use consistent input/output schemas
2. **Lattice Integration:** Move more state into lattice for better integrity/replication
3. **DID Integration:** Strengthen identity and capability discovery
4. **Audit Completeness:** Capture full provenance for every operation
5. **Cross-Venue Trust:** Implement policy-based access control between venues
6. **Agent Ergonomics:** Make operations easily discoverable and invocable by AI agents
