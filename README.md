<p align="center">
  <img src="https://raw.githubusercontent.com/covia-ai/covia-docs/master/static/img/Covia_Logo_Icon_Transp_Big.png" alt="Covia" width="96">
</p>

<h1 align="center">Covia</h1>

<p align="center">
  <b>The universal federated grid for AI.</b><br>
  Let AI models, agents, and data collaborate across organisational boundaries, clouds, and jurisdictions — with built-in governance and without centralising control.
</p>

<p align="center">
  <a href="https://github.com/covia-ai/covia/actions/workflows/test.yml"><img src="https://github.com/covia-ai/covia/actions/workflows/test.yml/badge.svg?branch=develop" alt="Build & test"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-EPL%202.0-blue.svg" alt="License: EPL 2.0"></a>
  <a href="https://docs.covia.ai"><img src="https://img.shields.io/badge/docs-docs.covia.ai-blue.svg" alt="Documentation"></a>
  <a href="https://discord.gg/fywdrKd8QT"><img src="https://img.shields.io/badge/Discord-join-5865F2?logo=discord&logoColor=white" alt="Discord"></a>
</p>

<p align="center">
  <a href="https://docs.covia.ai">Docs</a> ·
  <a href="https://app.covia.ai">Web App</a> ·
  <a href="VENUES.md">Live Venues</a> ·
  <a href="https://github.com/orgs/covia-ai/discussions">Discussions</a> ·
  <a href="https://discord.gg/fywdrKd8QT">Discord</a>
</p>

---

## What is Covia?

Covia is an open-source runtime for **federated AI orchestration**. You run a **venue** — a grid node that hosts *operations* (executable, self-describing capabilities) and keeps an immutable, content-addressed record of every job. Venues talk to each other, so a workflow can call an operation on a partner's venue, in another cloud or jurisdiction, while the data stays where it's governed and only results cross the boundary.

It's built on the [Convex](https://github.com/Convex-Dev/convex) lattice platform for decentralised, cryptographically-verifiable state, and it speaks the protocols your tools already use: **REST**, **MCP**, **A2A**, and **DID**.

> **Status:** Covia is pre-1.0 and moving fast — expect APIs to change. The core engine is solid and well-tested; we're actively improving the developer experience. See [`DX_PLAN.md`](DX_PLAN.md) for the roadmap and good first issues.

---

## Quickstart

The fastest way to try Covia is against a hosted venue — no install required.

### 1. Call a live venue

Every venue exposes the same REST API. List the operations it offers:

```bash
curl https://venue-3.covia.ai/api/v1/operations
```

Invoke one and wait for the result inline. `v/ops/schema/infer` derives a JSON Schema from an example value — one of many built-in operations:

```bash
curl -X POST https://venue-3.covia.ai/api/v1/invoke \
  -H "Content-Type: application/json" \
  -d '{
        "operation": "v/ops/schema/infer",
        "input": { "value": { "name": "Ada", "age": 36, "admin": true } },
        "wait": true
      }'
```

The venue returns a job record whose `output` carries the result:

```json
{
  "status": "COMPLETE",
  "output": {
    "schema": {
      "type": "object",
      "required": ["age", "admin", "name"],
      "properties": {
        "age":   { "type": "integer" },
        "admin": { "type": "boolean" },
        "name":  { "type": "string" }
      }
    }
  }
}
```

Browse the full API interactively at [`/swagger`](https://venue-3.covia.ai/swagger) or [`/redoc`](https://venue-3.covia.ai/redoc).

### 2. Invoke from your code

**TypeScript** ([`@covia/covia-sdk`](https://www.npmjs.com/package/@covia/covia-sdk))

```bash
npm install @covia/covia-sdk
```

```typescript
import { Grid } from "@covia/covia-sdk";

const venue = await Grid.connect("https://venue-3.covia.ai");
const result = await venue.operations.run("v/ops/schema/infer", {
  value: { name: "Ada", age: 36, admin: true },
});
console.log(result); // { schema: { type: "object", ... } }
venue.close();
```

**Python** ([`covia`](https://pypi.org/project/covia/))

```bash
pip install covia
```

```python
from covia import Grid

venue = Grid.connect("https://venue-3.covia.ai")
result = venue.run("v/ops/schema/infer", {"value": {"name": "Ada", "age": 36, "admin": True}})
print(result)  # {'schema': {'type': 'object', ...}}
```

### 3. Run your own venue

A venue is a single self-contained server. With Docker:

```bash
docker run -p 8080:8080 ghcr.io/covia-ai/covia:latest
```

Or download the executable JAR from the [latest release](https://github.com/covia-ai/covia/releases/tag/latest) and run it (requires **Java 21+**):

```bash
java -jar covia.jar
```

Either way the venue comes up on <http://localhost:8080> — open [`/swagger`](http://localhost:8080/swagger) and point the SDK examples above at `http://localhost:8080`. Pass a config file (`java -jar covia.jar config.json`) to set persistence, identity, and secrets — see the [Operator Guide](https://docs.covia.ai/docs/operator-guide/).

---

## What can a venue do?

Operations are provided by pluggable **adapters**. Out of the box a venue can:

| Capability | Adapter | Examples |
|------------|---------|----------|
| Call LLMs | `langchain` | OpenAI, Anthropic, Gemini, Ollama, DeepSeek |
| Run agents | `agent`, `llmagent`, `goaltree` | Stateful, tool-using agents with sessions and planning |
| Orchestrate workflows | `orchestrator` | Multi-step DAGs with dependencies and result composition |
| Federate across venues | `grid` | Invoke operations on remote venues (`grid:run`, `grid:invoke`) |
| Bridge MCP tools | `mcp` | Discover and call any MCP server as a grid operation |
| Make HTTP calls | `http` | SSRF-protected outbound `get` / `post` |
| Store & address content | `asset` | Content-addressed (CAD3 hash) artifacts |
| Read/write lattice state | `covia` | CRUD over a venue's namespaced lattice |
| Files & drives | `file`, `dlfs` | Root-jailed filesystem and a decentralised file system (WebDAV) |
| Manage secrets & capabilities | `secret`, `ucan` | Per-user encrypted secrets; signed UCAN capability tokens |

Operations are **self-describing** (JSON Schema in/out) and **discoverable**, so both humans and AI agents can find and invoke them.

---

## How it works

```
        Clients ── REST · MCP · A2A · DID
            │
       VenueServer            Javalin HTTP, OpenAPI/Swagger, SSE
            │
         Engine               assets · content · identity · JobManager
            │
        Adapters              grid · langchain · mcp · http · agent · covia · asset · dlfs · ucan · …
            │
     Convex lattice           immutable, content-addressed, CRDT-merged state
```

- **Asset** — an immutable, content-addressed resource (identified by its CAD3 value hash).
- **Operation** — an asset you can invoke, dispatched to an adapter via JSON Schema.
- **Job** — the execution record for an invocation. Jobs are long-lived (no framework timeout), poll-able, and stream updates over SSE.
- **Venue** — a grid node with a DID identity that hosts operations and persists state.

Full design lives in the [documentation](https://docs.covia.ai) and `venue/docs/GRID_LATTICE_DESIGN.md`.

---

## Protocols

A venue exposes the same capabilities over multiple protocols:

| Endpoint | Purpose |
|----------|---------|
| `GET  /api/v1/status` | Venue health and stats |
| `GET  /api/v1/operations` | List available operations |
| `POST /api/v1/invoke` | Execute an operation (optionally `?wait=true`) |
| `GET  /api/v1/jobs/{id}` | Job status |
| `GET  /api/v1/jobs/{id}/sse` | Live job event stream |
| `POST /mcp` | Model Context Protocol (JSON-RPC) |
| `POST /a2a` | Agent-to-Agent protocol (JSON-RPC) |
| `GET  /.well-known/did.json` | Venue DID document |
| `GET  /swagger`, `/redoc` | Interactive API docs |

**Use a venue as an MCP server** — point any MCP client at its `/mcp` endpoint:

```json
{
  "mcpServers": {
    "covia": { "url": "https://venue-3.covia.ai/mcp" }
  }
}
```

---

## SDKs

| Language | Package | Install |
|----------|---------|---------|
| TypeScript / JavaScript | [`@covia/covia-sdk`](https://www.npmjs.com/package/@covia/covia-sdk) | `npm install @covia/covia-sdk` |
| Python | [`covia`](https://pypi.org/project/covia/) | `pip install covia` |
| Java | `ai.covia:covia-core` | build from source (Maven artifact coming — see [`DX_PLAN.md`](DX_PLAN.md)) |

Each SDK can connect by URL, DNS name, or DID, and supports Ed25519 keypair and bearer authentication. See the [SDK docs](https://docs.covia.ai/docs/user-guide/sdk/) for the full surface (operations, jobs, assets, agents, workspace, secrets, UCAN).

---

## Build from source

Requires **Java 21+** and **Maven 3.7+**.

```bash
mvn clean install            # build all modules and run tests
java -jar venue/target/covia.jar
```

> All dependencies — including [Convex](https://github.com/Convex-Dev/convex) — resolve from Maven Central, so a clean clone builds with the one command above. Full details — module layout, release flow, troubleshooting — are in [`BUILD.md`](BUILD.md).

| Module | Purpose |
|--------|---------|
| `covia-core` | Grid client library and shared abstractions |
| `venue` | The venue server runtime (produces `covia.jar`) |
| `workbench` | A minimal Swing REPL for demos and testing |

---

## Roadmap & contributing

Covia is built in the open, and contributions are very welcome. We've written down where we are and where we're headed in [`DX_PLAN.md`](DX_PLAN.md) — including items marked 🌱 that make good first contributions. Engineering notes for working in the codebase live in [`AGENTS.md`](AGENTS.md).

- 💬 **Questions or ideas:** [GitHub Discussions](https://github.com/orgs/covia-ai/discussions) and [Discord](https://discord.gg/fywdrKd8QT)
- 🐛 **Found a bug or want a feature:** open an issue
- 📚 **Documentation:** [docs.covia.ai](https://docs.covia.ai)

A dedicated `CONTRIBUTING` guide is on the way; until then, jump into Discussions or Discord and we'll help you get started.

---

## License

Covia is licensed under the [Eclipse Public License 2.0](LICENSE).

<p align="center"><sub>Built on <a href="https://github.com/Convex-Dev/convex">Convex</a> lattice technology.</sub></p>
