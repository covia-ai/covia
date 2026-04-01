---
name: asset
description: Store, retrieve, list, and manage content-addressed assets on a Covia venue. Assets are immutable resources — operations, artifacts, agent definitions, orchestrations — identified by the SHA3-256 hash of their metadata. Use when working with any venue asset.
argument-hint: [store|get|list|explain] [id-or-file]
---

# Assets

**Prerequisite:** The venue must be running and connected as an MCP server (`http://localhost:8080/mcp`).

Assets are the fundamental resources of the Covia Grid ([COG-5](https://docs.covia.ai/protocol/cogs/COG-005)). Every asset is **immutable** and **content-addressed** — its ID is the SHA3-256 hash of its metadata. Storing the same metadata twice always returns the same ID.

## Asset Types

Assets are categorised by the presence of specific top-level objects:

| Type | Key Field | Description | Spec |
|------|-----------|-------------|------|
| **Operation** | `operation` | Executable function with adapter, input/output schemas | [COG-7](https://docs.covia.ai/protocol/cogs/COG-007) |
| **Artifact** | `content` | Immutable data with content hash verification | [COG-6](https://docs.covia.ai/protocol/cogs/COG-006) |
| **Agent Definition** | `agent` | Agent config template (operation, LLM settings, system prompt) | [COG-11](https://docs.covia.ai/protocol/cogs/COG-011) |

Additional categorisation via the `type` field (e.g. `"type": "orchestration"`, `"type": "agent-definition"`) enables filtering with `asset_list`.

## Asset Resolution

Asset references are resolved universally across the platform. Anywhere an asset ID is accepted (operations, agent definitions, orchestrations), these forms all work:

| Form | Example | Description |
|------|---------|-------------|
| Hex hash | `7a8b9c0d...` | Direct content-addressed ID (64 hex chars) |
| `/a/<hash>` | `/a/7a8b9c0d...` | Explicit asset namespace path |
| `/o/<name>` | `/o/my-pipeline` | User's operations namespace (per-user) |
| Operation name | `agent:create` | Registered venue operation |
| DID URL | `did:web:venue.example.com/a/7a8b...` | Fully qualified federated reference |

This means `agent:create definition="my-pipeline-agent"` works if that name is registered, as does passing a raw hash or a `/o/` path.

## Common Metadata Fields

Per [COG-5](https://docs.covia.ai/protocol/cogs/COG-005), these fields are recommended for all assets:

```json
{
  "name": "Human-readable name",
  "description": "What this asset is and how to use it",
  "type": "orchestration",
  "creator": "Author or organisation",
  "dateCreated": "2025-01-27T10:00:00Z",
  "keywords": ["pipeline", "invoice", "ap"]
}
```

## Tools

| Tool | Purpose |
|------|---------|
| `asset_store` | Store metadata (+ optional content) -> returns content-addressed ID |
| `asset_get` | Retrieve full metadata by ID |
| `asset_list` | List assets with optional `type` filter and pagination |
| `asset_content` | Retrieve binary content payload |

## Commands

### `store <json-or-file>` — Store an asset

If given a JSON object, store it directly:

```
asset_store  metadata=<json object>
```

If given a file path, read the file and store its contents as metadata:

```
# Read the file, then:
asset_store  metadata=<file contents as JSON>
```

For artifacts with binary content, use `contentText` for text or `content` for hex-encoded bytes:

```
asset_store  metadata={"name": "My Document", "content": {"contentType": "text/plain"}}  contentText="Hello, world"
```

The `content.sha256` field is automatically computed and injected if not provided.

Returns `{id: "<hex hash>", stored: true}`.

### `get <id>` — Retrieve asset metadata

```
asset_get  id=<hash>
```

Returns `{exists: true, value: <full metadata>}` or `{exists: false}`.

### `list [type]` — List stored assets

```
asset_list                          # all assets
asset_list  type=orchestration      # only orchestrations
asset_list  type=agent-definition   # only agent definitions
asset_list  offset=100  limit=50    # pagination
```

Returns summaries: id, name, type, description.

### `explain <id>` — Explain an asset's structure

Fetch the asset and present a human-readable breakdown:

```
asset_get  id=<hash>
```

For **operations**: show adapter, input/output schemas, any step dependencies.
For **orchestrations**: show step graph, input wiring, result assembly, dependency DAG.
For **agent definitions**: show operation, model, system prompt, tools, response format.
For **artifacts**: show content type, size, hash.

## Operation Metadata Format

Operations have an `operation` object ([COG-7](https://docs.covia.ai/protocol/cogs/COG-007)):

```json
{
  "name": "My Operation",
  "description": "What it does",
  "operation": {
    "adapter": "http:get",
    "input": { "type": "object", "properties": { ... }, "required": [...] },
    "output": { "type": "object", "properties": { ... } }
  }
}
```

## Agent Definition Format

Agent definitions have an `agent` object:

```json
{
  "name": "Alice — Invoice Scanner",
  "type": "agent-definition",
  "description": "Extracts structured fields from invoice text",
  "agent": {
    "operation": "llmagent:chat",
    "config": {
      "llmOperation": "langchain:openai",
      "model": "gpt-4o",
      "systemPrompt": "You are Alice...",
      "responseFormat": { "name": "InvoiceExtraction", "schema": { ... } },
      "tools": ["covia:read", "covia:write"]
    }
  }
}
```

Create an agent from a stored definition:

```
asset_store  metadata=<definition json>   -> returns hash
agent_create  agentId=Alice  definition=<hash>
```

The `definition` field accepts any asset reference (hash, name, `/o/` path, DID URL).

## Orchestration Metadata Format

Orchestrations are operations with `adapter: "orchestrator"` and a `steps` array:

```json
{
  "name": "My Pipeline",
  "type": "orchestration",
  "operation": {
    "adapter": "orchestrator",
    "steps": [
      { "op": "agent:request", "input": { "agentId": ["const", "Alice"], ... } },
      { "op": "agent:request", "input": { "agentId": ["const", "Bob"], "data": [0, "output"] } }
    ],
    "result": {
      "step1": [0, "output"],
      "step2": [1, "output"]
    }
  }
}
```

See `/orchestrate` skill for full orchestration documentation.

## Tips

- **Include `type`** in metadata for discoverability — `asset_list type=orchestration` only works if the field is present.
- **Asset IDs are deterministic** — storing identical metadata on different venues produces the same ID. This enables federated asset verification.
- **Metadata is immutable** — to update, store new metadata (which gets a new ID). Use `/o/` namespace aliases to point a stable name at the latest version.
- **Agents can store assets** — `asset:store`, `asset:get`, and `asset:list` are in the default agent tool palette, so agents can autonomously create orchestrations, definitions, and artifacts.
