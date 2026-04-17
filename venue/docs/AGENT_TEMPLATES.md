# Agent Templates — Design

Templates for creating, sharing, and forking agents on the Covia grid.

**Status:** Draft — April 2026. Phases 1, 2, and 3a implemented — `config` accepts string references, `agent:fork` operation exists, standard templates shipped at `v/agents/templates/<name>`.

---

## 1. Motivation

Creating an agent requires specifying config (system prompt, tools, model, caps, context) and optionally initial state. This is verbose and error-prone — especially when an LLM agent creates another agent.

Templates solve this by making agent configurations reusable, discoverable, and composable. An agent template is just data — a map describing how to create an agent. Templates can live anywhere: workspace, asset store, or passed inline.

---

## 2. Core Principles

### Templates are config

There is no separate "template" concept or field — a template **is** agent config. `agent:create`'s `config` field accepts either:

1. An inline map (current behaviour), or
2. A **string reference** that resolves to a map: a workspace path (`w/templates/reader`), asset ref (`/a/<hash>`), DID URL, or venue operation name.

The framework resolves the reference to a map and uses it as config. This unifies "template" and "config" — anywhere you can store a map, you can store a template.

A template is a CVM map with the same structure as `agent:create` config. It may optionally include a `state` field, which is extracted and used as the agent's initial state. No special type, no schema enforcement, no registration step. If it has the right fields, it's a template.

```json
{
  "name": "Convex Query Worker",
  "systemPrompt": "You query the Convex blockchain and report results...",
  "tools": ["convex:query", "covia:read", "covia:write"],
  "model": "gpt-5.4-mini",
  "caps": [
    {"with": "w/results/", "can": "crud/write"},
    {"with": "w/", "can": "crud/read"}
  ]
}
```

### Templates live where data lives

Templates are stored in the same places as all other user data:

| Location | Example | Use case |
|----------|---------|----------|
| Workspace | `w/templates/query-worker` | Personal templates, quick iteration |
| User assets | `/a/<hash>` | Immutable, content-addressed, shareable |
| Venue assets | Registered at startup | Pre-installed standard templates |
| Inline | Passed directly in `agent:create` | One-off agent creation |

No special namespace — templates are maps that happen to describe agent config.

### Fork, don't copy

`agent:fork` creates a new agent from an existing agent's complete state — config, conversation history, timeline, workspace knowledge. The forked agent is a snapshot that can diverge independently. This enables:

- Branching exploration: fork an analyst agent to try two different approaches
- Scaling: fork a trained worker to handle parallel tasks
- Recovery: fork a suspended agent to a clean copy before resuming

---

## 3. Template Format

A template is any CVM map. The following fields are recognised:

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Human-readable template name |
| `description` | string | What this agent does (useful for LLM discovery) |
| `systemPrompt` | string | System prompt defining the agent's role |
| `tools` | array | Tool operation names the agent can call |
| `model` | string | LLM model name (default: gpt-5.4-mini) |
| `llmOperation` | string | LLM backend operation (default: langchain:openai) |
| `caps` | array | Capability restrictions (array of {with, can} objects) |
| `context` | array | Context loading entries (asset hashes, workspace paths) |
| `responseFormat` | object | Structured output schema ({name, schema}) |
| `defaultTools` | boolean | Whether to include platform default tools (default: true) |
| `state` | any | Initial state for the agent (optional) |

All fields are optional. Missing fields get platform defaults.

### Example templates

**Minimal reader:**
```json
{
  "name": "Data Reader",
  "systemPrompt": "You read and summarise data from workspace.",
  "tools": ["covia:read", "covia:list"],
  "defaultTools": false
}
```

**Managed worker with caps:**
```json
{
  "name": "Invoice Processor",
  "systemPrompt": "You process invoices. Read from w/inbox/, write results to w/processed/.",
  "tools": ["covia:read", "covia:write"],
  "caps": [
    {"with": "w/inbox/", "can": "crud/read"},
    {"with": "w/processed/", "can": "crud/write"}
  ],
  "responseFormat": {
    "name": "ProcessedInvoice",
    "schema": {"type": "object", "properties": {"id": {"type": "string"}, "total": {"type": "number"}}, "required": ["id", "total"], "additionalProperties": false}
  }
}
```

**Agent manager:**
```json
{
  "name": "Team Lead",
  "systemPrompt": "You create and coordinate worker agents. Use templates from w/templates/ to create specialised workers.",
  "tools": ["agent:create", "agent:request", "agent:message", "covia:read", "covia:write"]
}
```

---

## 4. Using Templates

`agent:create`'s `config` field is overloaded: it accepts either an inline map or a string reference that resolves to one. Resolution is tried in this order:

1. **Asset ref** — bare hash, `/a/<hash>`, `/o/<name>`, DID URL, or venue operation name (via `engine.resolveAsset`)
2. **Workspace path** — any relative path in the caller's own lattice namespace (e.g. `w/templates/reader`)

If the resolved map contains a `state` field, it is extracted and used as the agent's initial state.

### From workspace

Store a template, then create an agent from it:

```
covia_write  path=w/templates/reader  value={"systemPrompt":"You read data.","tools":["covia:read"],"defaultTools":false}

agent_create  agentId=MyReader  config=w/templates/reader
```

### From asset store

Templates stored as immutable assets are resolved the same way — `config` can be a hash, `/a/<hash>`, or DID URL:

```
asset_store  metadata={"name":"Reader Template","systemPrompt":"You read data.","tools":["covia:read"]}
// returns asset id

agent_create  agentId=MyReader  config=/a/<hash>
```

### Inline

```
agent_create  agentId=MyReader  config={"systemPrompt":"You read data.","tools":["covia:read"],"defaultTools":false}
```

Direct inline config — no indirection. Current behaviour, unchanged.

### Piping through LLMs

Because templates are just maps, an agent creating another agent can read a template, mutate specific fields, then pass the modified map directly:

```
template = covia_read path=w/templates/reader          // get map
template.systemPrompt = "You read ONLY sales data."    // tweak
agent_create agentId=CustomReader config=<template>    // pass modified map
```

No server-side merging logic is needed — LLMs handle the "read, modify, pass" pattern natively via JSON. Override semantics live in the caller, not the framework.

### Legacy: `definition` field

The existing `definition` field still works for agent assets in the nested `meta.agent.config` format. For new templates, prefer the unified `config` reference approach.

---

## 5. agent:fork

Fork creates a new agent from an existing agent's complete state.

### What gets copied

| Field | Copied? | Notes |
|-------|---------|-------|
| config | Yes | Same tools, caps, model, systemPrompt |
| state | Yes | Full conversation history, LLM config |
| timeline | Configurable | `includeTimeline: true` copies run history |
| tasks | No | Pending tasks stay with the original |
| sessions | No | Session state stays with the original |
| status | Reset to SLEEPING | Forked agent starts fresh |

### API

```
agent:fork {
  sourceId: "AnalystV1",
  agentId: "AnalystV2",
  config: { ... },           // optional overrides
  includeTimeline: false      // default: false
}
```

Returns: `{agentId: "AnalystV2", created: true, forkedFrom: "AnalystV1"}`

### Use cases

**Branching exploration:**
```
agent_fork  sourceId=Analyst  agentId=Analyst-PlanA  config={"systemPrompt":"Focus on cost reduction..."}
agent_fork  sourceId=Analyst  agentId=Analyst-PlanB  config={"systemPrompt":"Focus on revenue growth..."}
```

Both forks have the same conversation history and training context, but diverge from the fork point.

**Scaling:**
```
agent_fork  sourceId=TrainedWorker  agentId=Worker-1
agent_fork  sourceId=TrainedWorker  agentId=Worker-2
agent_fork  sourceId=TrainedWorker  agentId=Worker-3
```

Three identical workers, each handling different tasks independently.

**Snapshot before risky operation:**
```
agent_fork  sourceId=ProdAgent  agentId=ProdAgent-backup  includeTimeline=true
agent_request  agentId=ProdAgent  input={"task": "risky migration..."}
```

If the migration goes wrong, the backup fork preserves the pre-migration state.

---

## 6. Pre-installed Templates

The venue ships with standard templates registered as venue-level assets at startup. Agents can discover them via `asset:list type=agent-template`.

### Standard templates (shipped)

Installed at venue startup by `AgentAdapter.installAssets` via `installAgentTemplate(name, path)`. Materialised to the venue lattice at `v/agents/templates/<name>`. Discoverable via `covia_list path=v/agents/templates`. Resolvable via `config="v/agents/templates/<name>"` — standard lattice path resolution, no special-case lookup.

| Path | Tools | Purpose |
|------|-------|---------|
| `v/agents/templates/minimal` | (none, `defaultTools: false`) | Pure reasoning, no side effects |
| `v/agents/templates/reader` | covia:read, covia:list, covia:slice | Read-only data analysis |
| `v/agents/templates/worker` | covia:read/write/delete/append/slice/list | General data processing |
| `v/agents/templates/manager` | agent CRUD ops, covia:read/list, grid:run, **subgoal/compact/more_tools** | Agent coordination with goal decomposition |
| `v/agents/templates/analyst` | covia:read/list/slice, schema:validate/infer/coerce | Data analysis with schema awareness |
| `v/agents/templates/goaltree` | Curated covia + grid + asset ops + all 7 harness tools | Goal-tree agent with full decomposition support |
| `v/agents/templates/full` | All default tools + all 7 harness tools (`defaultTools: true`) | Development and exploration |

**Tools are opt-in.** Each template explicitly lists the tools it needs in `config.tools`. Set `defaultTools: false` to disable the legacy 18-tool default set. Harness tools (`subgoal`, `complete`, `fail`, `compact`, `context_load`, `context_unload`, `more_tools`) are also opt-in — include their names in `config.tools`. Typed outputs (`config.outputs`) auto-inject `complete`/`fail` regardless of the tools list.

Template JSON files live in `venue/src/main/resources/agent-templates/`.

### Default template (Phase 3b — not yet implemented)

Currently when `agent:create` is called with no config, the hardcoded `DEFAULT_TOOL_OPS` list in `LLMAgentAdapter` (19 tools) is merged in. A future change should replace this with the `worker` template as the default, giving a smaller, more focused default tool set and resolving issue #60. This is a breaking change for existing agents that rely on default tools like `agent:create` or `asset:store`, so needs explicit review before rollout.

---

## 7. Agent discovers and uses templates

An agent creating another agent can:

1. **Use a standard template:** `agent_create agentId=Worker config=v/agents/templates/reader`
2. **Browse standard templates:** `covia_list path=v/agents/templates`
3. **Read a workspace template:** `covia_read path=w/templates/my-worker`
4. **Create from workspace reference:** `agent_create agentId=Worker config=w/templates/my-worker`
5. **Customise:** Read a template, modify the returned map, pass the modified map inline as config
6. **Fork existing:** `agent_fork sourceId=TrainedWorker agentId=Worker-2`

This is a fully data-driven workflow — no special APIs, just reading templates and passing them to `agent:create`. The same `config` field handles standard template names, workspace paths, asset references, DID URLs, and inline maps.

---

## 8. Implementation

### Phase 1: `config` accepts string references ✓ DONE

- `agent:create`'s `config` field accepts an inline map *or* a string reference
- Resolution: `AgentAdapter.resolveConfigRef` calls `engine.resolvePath(ref, ctx)` which handles every form (venue paths, workspace paths, asset hashes, DID URLs, pinned ops)
- Embedded `state` field in the resolved map is extracted and used as initial state
- Schema in `create.json` uses `oneOf` to document both forms
- The legacy `definition` field continues to work for nested `meta.agent.config` format

### Phase 2: `agent:fork` ✓ DONE

- `agent:fork` operation in AgentAdapter
- Copies config and state from source; optional `includeTimeline: true` copies run history
- Resets status to SLEEPING; tasks, pending, and sessions are fresh
- Optional `config` override (inline map or string reference) is merged on top of source config per-field
- Source must exist and not be TERMINATED; target must not already exist (unless `overwrite: true` and target is TERMINATED)
- Implementation: `User.forkAgent` + `AgentState.initialiseFromFork`

### Phase 3a: Ship standard templates ✓ DONE

- Template JSONs in `venue/src/main/resources/agent-templates/` (minimal, reader, worker, manager, analyst, full, goaltree)
- `AgentAdapter.installAgentTemplate(name, path)` materialises each to `v/agents/templates/<name>` at venue startup
- No special-case lookup — `resolveConfigRef` uses standard `engine.resolvePath` which handles `v/agents/templates/<name>` like any other venue path
- Templates are discoverable via `covia_list path=v/agents/templates`

### Phase 3b: Swap default

- Change `LLMAgentAdapter` to use a smaller default tool set (e.g. match `v/agents/templates/worker`) instead of the current 19-tool `DEFAULT_TOOL_OPS`
- Resolves #60 (too many default tools)
- Breaking change — needs explicit review before rollout (impacts all agents that rely on current defaults)

---

## 9. Relation to existing features

| Feature | Relation |
|---------|----------|
| `agent:create config` (inline map) | Unchanged — same as before |
| `agent:create config` (string reference) | New in Phase 1 — resolves workspace paths, asset refs, DID URLs |
| `agent:create definition` | Legacy nested format (`meta.agent.config`) — still works, but prefer unified `config` reference |
| `DEFAULT_TOOL_OPS` | Replaced by default template in Phase 3 |
| `agent:delete` + recreate | `agent:fork` is cleaner for "reset with modifications" |
| Context loading | Templates can include `context` array — same mechanism |
