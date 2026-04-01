---
name: orchestrate
description: Create, store, and run multi-step orchestrations on a Covia venue. Chain operations with dependency management, parallel execution, and result aggregation. Use when building pipelines, workflows, or coordinating multiple agents/operations.
argument-hint: [create|run|explain|ap-pipeline] [name]
---

# Orchestrations

**Prerequisite:** The venue must be running and connected as an MCP server (`http://localhost:8080/mcp`).

Orchestrations are multi-step workflows that chain operations together. The orchestrator adapter handles dependency resolution, parallel execution, and result aggregation — all as a single immutable job with a full audit trail.

## How It Works

An orchestration is a JSON asset with:
- **`steps`** — an ordered array of operations to execute
- **`result`** — a spec that assembles the final output from step outputs

Steps run in parallel by default. A step only waits if it references output from a previous step — the orchestrator scans input specs at construction to build the dependency graph automatically.

## Orchestration Format

```json
{
  "name": "My Pipeline",
  "description": "What this orchestration does",
  "operation": {
    "adapter": "orchestrator",
    "steps": [
      {
        "op": "operation:name",
        "input": { ... }
      }
    ],
    "result": { ... }
  }
}
```

### Step Fields

| Field | Required | Description |
|-------|----------|-------------|
| `op` | Yes | Operation to invoke (e.g. `"agent:request"`, `"test:echo"`, `"covia:read"`) |
| `input` | Yes | Input specification — can wire in constants, orchestration input, or previous step outputs |
| `venue` | No | Remote venue DID or URL for federated execution |

### Input Specification

Input specs tell the orchestrator where each value comes from. Three source types:

#### 1. Orchestration input — `["input", ...]`

Pull values from whatever was passed to the orchestration:

```json
["input"]                        // entire orchestration input
["input", "invoice_text"]        // input.invoice_text
["input", "config", "model"]     // input.config.model (nested path)
```

#### 2. Constants — `["const", value]`

Literal values baked into the orchestration definition:

```json
["const", "Alice"]               // string constant
["const", true]                  // boolean
["const", {"key": "value"}]      // object constant
```

#### 3. Previous step output — `[stepIndex, ...]`

Reference output from a completed step by its zero-based index. **This creates a dependency** — the current step will wait for the referenced step to finish.

```json
[0]                              // entire output of step 0
[0, "output"]                    // step 0's output.output
[1, "result", "vendor_name"]     // step 1's output.result.vendor_name
```

#### Composing inputs

Input specs can be nested in maps to build structured inputs:

```json
{
  "agentId": ["const", "Alice"],
  "input": {
    "invoice": ["input", "invoice_text"],
    "metadata": ["const", {"source": "email"}]
  },
  "wait": ["const", true]
}
```

### Result Specification

The `result` field assembles the orchestration's final output using the same reference syntax:

```json
{
  "result": {
    "extraction": [0, "output"],
    "enrichment": [1, "output"],
    "decision":   [2, "output"],
    "original":   ["input", "invoice_text"]
  }
}
```

## Dependency Rules

- Steps can **only** reference previous steps (lower indices). Forward references are rejected.
- Steps with no step references run **immediately in parallel**.
- The orchestrator scans all input specs recursively to find step index references and builds the dependency graph at construction time.
- A step starts as soon as all its dependencies have completed.

### Example: Parallel then sequential

```json
"steps": [
  { "op": "covia:read", "input": {"path": ["const", "w/vendors"]} },
  { "op": "covia:read", "input": {"path": ["const", "w/orders"]} },
  { "op": "agent:request", "input": {
      "agentId": ["const", "Analyser"],
      "input": {"vendors": [0, "value"], "orders": [1, "value"]},
      "wait": ["const", true]
  }}
]
```

Steps 0 and 1 run in parallel (no step references). Step 2 waits for both (references `[0, ...]` and `[1, ...]`).

## Asset Tools Reference

Orchestrations are stored as immutable, content-addressed assets. These tools manage the lifecycle:

| Tool | Operation | Purpose |
|------|-----------|---------|
| `asset_store` | `asset:store` | Store orchestration metadata -> returns content-addressed hash (ID) |
| `asset_get` | `asset:get` | Retrieve orchestration metadata by hash |
| `asset_list` | `asset:list` | List stored assets (supports `type` filter, pagination) |
| `asset_content` | `asset:content` | Retrieve binary content payload (if any) |

### Storing an orchestration

```
asset_store  metadata=<orchestration JSON>
```

Returns `{id: "<hex hash>", stored: true}`. The hash is deterministic — storing identical metadata always returns the same ID. Include `"type": "orchestration"` in the metadata for easy filtering with `asset_list`.

### Finding stored orchestrations

```
asset_list  type=orchestration
```

Returns summaries (id, name, type, description) with pagination (offset/limit).

### Retrieving full definition

```
asset_get  id=<hash>
```

Returns `{exists: true, value: <full metadata>}` — inspect steps, result spec, and input schema.

## Ready-Made Assets

The `assets/` directory contains orchestration definitions as JSON files. To use one, read the file and pass its contents to `asset_store`:

| File | Description |
|------|-------------|
| `assets/ap-invoice-pipeline.json` | AP pipeline: Alice -> Bob -> Carol |

## Commands

### `create <name>` — Build an orchestration interactively

Walk the user through building an orchestration:

1. Ask what operations to chain (agents, reads, writes, LLM calls, HTTP, etc.)
2. Build the steps array with proper input wiring
3. Define the result spec
4. Include `"type": "orchestration"` in the metadata
5. Store as an asset via `asset_store`
6. Return the asset hash for invocation

### `run <hash-or-name>` — Execute a stored orchestration

Invoke via `grid_run`:

```
grid_run  operation=<asset-hash>  input=<json>
```

The orchestration runs as a single job. The result contains the assembled output. The job record includes a `steps` array with per-step status for debugging.

### `explain <hash>` — Show an orchestration's structure

Fetch the asset and display:
- Step-by-step breakdown with operations
- Dependency graph (which steps wait for which)
- Input wiring diagram
- Result assembly

```
asset_get  id=<hash>
```

Then parse and present the steps, dependencies, and result mapping in a readable format.

### `ap-pipeline` — Store and run the AP demo orchestration

Read the ready-made asset from [assets/ap-invoice-pipeline.json](assets/ap-invoice-pipeline.json) and store it:

```
asset_store  metadata=<contents of ap-invoice-pipeline.json>
```

Then run it:

```
grid_run  operation=<hash>  input={"invoice_text": "INVOICE #INV-2024-1042\nFrom: Acme Corp\n..."}
```

The result contains all three stages in one response with full provenance.

## Error Handling

- If any step fails, the entire orchestration fails — the job status shows which step caused it.
- Step status is tracked in the job's `steps` array (inspect via `covia_read path=j/<job-id>/steps`).
- Common failures: agent not found, operation timeout, LLM error.

## Federation

Steps can execute on remote venues by adding a `venue` field:

```json
{
  "op": "agent:request",
  "input": { ... },
  "venue": "did:key:z6Mk..."
}
```

The orchestrator connects to the remote venue via Grid and routes the step there. Results flow back into the local dependency graph.

## Tips

- **Use `wait: true`** on `agent:request` steps — without it the step completes immediately with just the task ID, and the next step won't have the agent's output.
- **Keep step count reasonable** — each step is a separate job invocation. For simple data transforms, consider doing them in a single agent prompt rather than adding orchestration steps.
- **Name your orchestrations** clearly — the name and description appear in asset listings and help agents discover reusable pipelines.
- **Test with `test:echo`** first — wire up your input/result specs using the echo adapter to verify data flow before switching to real operations.
