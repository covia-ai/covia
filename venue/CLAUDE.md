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
│   │   ├── Engine.java          # Central engine managing state and operations
│   │   ├── MainVenue.java       # Application entry point
│   │   ├── LocalVenue.java      # Local venue implementation
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
- **Content-Addressed Storage:** Assets identified by SHA256 hash of metadata, ensuring integrity
- **Conflict-Free Replication:** Lattice cursors enable distributed state without coordination overhead
- **Cryptographic Verification:** All data can be verified using Convex's hash-based integrity

**Current Lattice Structure:**
```
:covia -> Map
    :assets -> Index<AssetID, [metadata, content, meta-map]>
    :users -> Index<User, Map<:assets, :jobs>>
```

**Design Goals:**
- Store job history in lattice for audit trails
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
- Identified by SHA256 hash of their metadata
- Associated with an adapter that handles execution
- Self-describing via JSON Schema for inputs/outputs

### Job (`covia.grid.Job`)
A running or completed invocation of an operation:
- Has a unique ID (timestamp + counter + random)
- Tracks status, input, output, errors
- Supports async completion via CompletableFuture
- Can be paused, cancelled, or awaited

### Adapter (`covia.adapter.AAdapter`)
Bridges operations to execution environments:
- Installs assets on registration
- Handles invocation dispatch
- Returns results asynchronously

## Adapter Reference

| Adapter | Purpose | Operations |
|---------|---------|------------|
| `grid` | Federated grid operations | `run`, `invoke`, `jobStatus`, `jobResult` |
| `convex` | Convex blockchain | `query`, `transact` |
| `mcp` | Model Context Protocol | `toolList`, `toolCall` |
| `langchain` | AI/LLM models | `openai`, `ollama`, `gemini`, `deepseek` |
| `http` | HTTP requests | `get`, `post` |
| `jvm` | JVM utilities | `stringConcat`, `urlEncode`, `urlDecode` |
| `orchestrator` | Multi-step workflows | Custom orchestration |

## API Endpoints

Base path: `/api/v1/`

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/status` | GET | Venue status and health |
| `/assets/{id}` | GET | Retrieve asset metadata |
| `/assets` | POST | Register new asset |
| `/assets/{id}/content` | GET/PUT | Asset binary content |
| `/invoke` | POST | Execute an operation |
| `/jobs/{id}` | GET | Job status |
| `/jobs/{id}/sse` | GET | Server-sent events for job updates |
| `/.well-known/did.json` | GET | Venue DID document |

## Development Guidelines

### Adding a New Adapter

1. Create a class extending `AAdapter` in `covia.adapter`
2. Implement `getName()`, `getDescription()`, `invokeFuture()`
3. Override `installAssets()` to register default operations
4. Create JSON asset definitions in `src/main/resources/adapters/{name}/`
5. Register in `Engine.addDemoAssets()` or via configuration

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

### Working with Lattice State

```java
// Access assets via cursor
ACursor<Index<AString,AVector<ACell>>> assets = lattice.path(COVIA_KEY, ASSETS_KEY);

// Read current state
AMap<ABlob, AVector<?>> currentAssets = RT.ensureMap(assets.get());

// Update state (atomic)
assets.set(currentAssets.assoc(newId, newRecord));
```

### Testing

- Unit tests in `src/test/java/`
- Use `Engine.createTemp()` for test instances
- Asset examples in `src/main/resources/asset-examples/` for validation

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
- **Deploy Guide:** `../DEPLOY.md` - Deployment options
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
