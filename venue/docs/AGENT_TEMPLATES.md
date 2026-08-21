# Agent Templates — Design

Templates for creating, sharing, and forking agents on the Covia grid.

**Status:** Current — ordered config composition, functional template assets,
`agent:fork`, and standard templates are implemented.

---

## 1. Motivation

Creating an agent requires specifying config (system prompt, tools, model, caps, context) and optionally initial state. This is verbose and error-prone — especially when an LLM agent creates another agent.

Templates solve this by making agent configurations reusable, discoverable, and composable. An agent template is just data — a map describing how to create an agent. Templates can live anywhere: workspace, asset store, or passed inline.

---

## 2. Core Principles

### Configuration is an ordered stack

`agent:create.config` accepts one layer or an ordered vector of layers. Each
layer is either an inline map or a reference to a map/asset: a workspace path
(`w/templates/reader`), asset ref (`a/<hash>`), DID URL, or venue path.

Layers merge left-to-right. Nested maps merge recursively; later scalar,
vector, and null values replace earlier values. This lets independent assets
select a behavioural template, provider, system prompt, tool palette, output
contract, or final local override without any one asset owning the whole agent:

```json
[
  "v/agents/templates/worker",
  "w/agent-config/providers/anthropic",
  "w/agent-config/prompts/invoice-review",
  {"model": "claude-sonnet-5", "temperature": 0}
]
```

Arrays are deliberately atomic. A later `tools`, `skills`, `context`, or
`caps` vector is an explicit replacement, avoiding surprising implicit union
semantics (especially for security-sensitive caps). A selector asset can
publish the complete desired vector.

### A template is a functional Covia asset

The canonical immutable form is ordinary asset metadata with an `agent` facet,
mirroring `operation` and `skill` facets:

```json
{
  "name": "Convex Query Worker",
  "description": "Queries Convex and writes evidence-backed results.",
  "agent": {
    "config": {
      "systemPrompt": "You query the Convex blockchain and report results...",
      "tools": ["v/ops/convex/query", "v/ops/covia/read", "v/ops/covia/write"],
      "caps": [
        {"with": "w/results/", "can": "crud/write"},
        {"with": "w/", "can": "crud/read"}
      ]
    }
  }
}
```

The asset is content-addressed, shareable, and directly usable as a config
layer. `agent.config` may itself be a map, a reference, or an ordered layer
vector. For compatibility, flat config maps remain valid in workspace and as
inline layers. An optional `agent.state` supplies initial state.

### Templates live where data lives

Templates are stored in the same places as all other user data:

| Location | Example | Use case |
|----------|---------|----------|
| Workspace | `w/templates/query-worker` | Personal templates, quick iteration |
| User assets | `/a/<hash>` | Immutable, content-addressed, shareable |
| Venue assets | Registered at startup | Pre-installed standard templates |
| Inline | Passed directly in `agent:create` | One-off agent creation |

The venue catalog path is a discoverable pin, not the identity: the underlying
asset remains addressable by its content hash and may be used from any grid
location that resolves it.

### Fork, don't copy

`agent:fork` creates a new agent from an existing agent's complete state — config, conversation history, timeline, workspace knowledge. The forked agent is a snapshot that can diverge independently. This enables:

- Branching exploration: fork an analyst agent to try two different approaches
- Scaling: fork a trained worker to handle parallel tasks
- Recovery: fork a suspended agent to a clean copy before resuming

---

## 3. Template Format

The following fields are recognised inside canonical `agent.config` (or at the
top level of a legacy flat config map):

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Human-readable template name |
| `description` | string | What this agent does (useful for LLM discovery) |
| `systemPrompt` | string | System prompt defining the agent's role |
| `tools` | array | Tool operation lattice paths the agent can call (e.g. `v/ops/covia/read`) |
| `model` | string | Optional provider model name; provider default when absent |
| `llmOperation` | string | Optional LLM backend operation path; venue default when absent |
| `caps` | array | Capability restrictions (array of {with, can} objects) |
| `context` | array | Context loading entries (asset hashes, workspace paths) |
| `responseFormat` | object | Structured output schema ({name, schema}) |
| `defaultTools` | boolean | Whether to include the platform default tool pack on top of `tools` (default: false — strict allowlist) |
| `state` | any | Initial state for the agent (optional) |

All fields are optional. Missing fields get platform defaults.

> **Tool references are operation lattice paths, not adapter shorthand.** Use `v/ops/covia/read`, not `covia:read` — the latter names an *adapter* and will not resolve. The same applies to `operation` and `llmOperation` (e.g. `v/ops/llmagent/chat`, `v/ops/langchain/openai`). The exception is harness tools (`subgoal`, `complete`, `fail`, `compact`, `context_load`, `context_unload`, `more_tools`) — those are bare names, not operations.

> **Keep provider-facing callable names out of durable prompts.** Put canonical
> operation references in `config.tools` and describe the intended capability,
> decision rule, arguments, result, and failure handling in `systemPrompt`. The
> runtime resolves those references on every inference and advertises the exact
> name, description, and input schema accepted by that provider. A template must
> tell the model to choose from that live palette, never to reconstruct a name or
> path from examples in prompt prose. Bare harness names belong only in the
> configuration that enables them and in implementation documentation such as
> this page.

> **Private tool definitions need metadata read access.** Adding a user-scoped
> operation such as `w/ops/risk/issue-limit` to `tools` requires both `invoke`
> authority for the operation and `crud/read` authority for its definition path.
> The runtime must read the operation metadata to build the provider tool name,
> description, and input schema. A definition that is missing or unreadable is
> omitted from the provider palette and reported in `agent:create` warnings,
> `agent:info.unavailableTools`, and the model's assembled context. Shared
> `v/ops/...` catalog metadata remains publicly discoverable.

### Example templates

For brevity these examples show config layer maps. When publishing one as an
immutable asset, place the map under `agent.config` with `name` and
`description` as ordinary top-level metadata.

**Minimal reader:**
```json
{
  "name": "Data Reader",
  "systemPrompt": "You read and summarise data from workspace.",
  "tools": ["v/ops/covia/read", "v/ops/covia/list"],
  "defaultTools": false
}
```

**Managed worker with caps:**
```json
{
  "name": "Invoice Processor",
  "systemPrompt": "You process invoices. Read from w/inbox/, write results to w/processed/.",
  "tools": ["v/ops/covia/read", "v/ops/covia/write"],
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
  "tools": ["v/ops/agent/create", "v/ops/agent/request", "v/ops/agent/message", "v/ops/covia/read", "v/ops/covia/write"]
}
```

---

## 4. Using Templates

`agent:create.config` accepts an inline map, a string reference, or an ordered
vector containing either. References use ordinary lattice resolution:

1. **Asset ref** — bare hash, `/a/<hash>`, `/o/<name>`, DID URL, or venue operation name (via `engine.resolveAsset`)
2. **Workspace path** — any relative path in the caller's own lattice namespace (e.g. `w/templates/reader`)

If the resolved map contains a `state` field, it is extracted and used as the agent's initial state.

### From workspace

Store a template, then create an agent from it:

```
covia_write  path=w/templates/reader  value={"systemPrompt":"You read data.","tools":["v/ops/covia/read"],"defaultTools":false}

agent_create  agentId=MyReader  config=w/templates/reader
```

### From asset store

Templates stored as immutable assets are resolved the same way — `config` can be a hash, `/a/<hash>`, or DID URL:

```
asset_store  metadata={"name":"Reader Template","systemPrompt":"You read data.","tools":["v/ops/covia/read"]}
// returns asset id

agent_create  agentId=MyReader  config=/a/<hash>
```

### Inline

```
agent_create  agentId=MyReader  config={"systemPrompt":"You read data.","tools":["v/ops/covia/read"],"defaultTools":false}
```

Direct inline config — no indirection. Current behaviour, unchanged.

### Composition without copying

Callers pass references and a small final override; the framework performs the
deterministic merge. The LLM never has to copy or rewrite a large template:

```json
{
  "agentId": "CustomReader",
  "config": [
    "v/agents/templates/reader",
    "w/agent-config/providers/anthropic",
    {"systemPrompt": "You read only sales data."}
  ]
}
```

### Legacy: `definition` field

The existing `definition` field still works. It now resolves the same canonical
`agent` facet and ordered layers as `config`; new callers should normally put
the asset reference directly in the `config` stack.

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

The venue ships with standard templates registered as venue-level assets at
startup. Each has ordinary metadata plus the canonical `agent.config` facet.

### Standard templates (shipped)

Installed at venue startup by `AgentAdapter.installAssets` via `installAgentTemplate(name, path)`. Materialised to the venue lattice at `v/agents/templates/<name>`. Discoverable via `covia_list path=v/agents/templates`. Resolvable via `config="v/agents/templates/<name>"` — standard lattice path resolution, no special-case lookup.

The shipped behavioural templates are provider-neutral: they do not select an
LLM operation or model. Explicit use therefore inherits venue defaults unless
a later config layer selects a provider/model. Their system prompts are also
tool-name-neutral: operation references and harness controls live in the
configuration, while the prompt describes workflows against the live tool
palette assembled for the current provider call.

| Path | Tools | Purpose |
|------|-------|---------|
| `v/agents/templates/minimal` | No operation tools; complete skill index | Lean on-demand general agent |
| `v/agents/templates/skilled` | `v/ops/covia/{inspect,read,list}`; complete skill index | Recommended lean default |
| `v/agents/templates/reader` | `v/ops/covia/{inspect,read,list,slice}`; curated read-oriented skills; enforced read caps | Read-only data analysis |
| `v/agents/templates/worker` | `v/ops/covia/{inspect,read,write,delete,append,slice,list}`; storage/provenance skills | General data processing |
| `v/agents/templates/manager` | Agent coordination, local reads, grid run/status/result, **subgoal/compact/more_tools**; management skills | Multi-agent coordination |
| `v/agents/templates/analyst` | `v/ops/covia/{inspect,read,list,slice,aggregate}`, schema ops; analysis skills | Evidence and schema analysis |
| `v/agents/templates/goaltree` | Curated covia + grid + asset ops + all 7 harness tools | Goal-tree agent with full decomposition support |
| `v/agents/templates/full` | Broad agent/asset/covia/schema/grid palette plus context tools, `more_tools` and complete skill index | Context-heavy development and exploration |

**Tools are opt-in, on every runtime.** Each template explicitly lists the tools it needs in `config.tools`; `defaultTools: true` adds only the deliberately small read/list pack. Harness tools are opt-in by name under the same rule: the shared ones (`context_load`, `context_unload`, `more_tools`, `skill_load`) on both runtimes, the goal-tree frame tools (`subgoal`, `compact`, `complete`, `fail`) on goaltree. What the situation implies is offered without listing: declared skills imply `skill_load` and `context_unload` (the index is rendered, so loading from it and removing a load are implied); an outstanding task offers `complete_task`/`fail_task`; typed goaltree `outputs` inject `complete`/`fail`. An agent that declares none of this has no tools at all — and no capability notice.

**Namespace literacy is a pinned skill, not prompt text.** Every template with lattice tools pins `v/skills/data/lattice` through `config.loads` (as a skill entry), so the namespace and addressing reference renders as a loaded element the agent can mask rather than as a line in every head; `minimal` has no tools and carries none (AGENT_CONTEXT.md §5.1).

Template JSON files live in `venue/src/main/resources/agent-templates/`.

### Default template

`agent:create` with no `config` starts from `v/agents/templates/skilled`: a lean inspect/read/list base plus skills loaded on demand. The venue supplies its configured transition and LLM provider, and the provider chooses its own default model. Passing an explicit map—including `{}`—continues to mean exactly that configuration and does not acquire template capabilities implicitly.

---

## 7. Agent discovers and uses templates

An agent creating another agent can:

1. **Use a standard template:** `agent_create agentId=Worker config=v/agents/templates/reader`
2. **Browse standard templates:** `covia_list path=v/agents/templates`
3. **Read a workspace template:** `covia_read path=w/templates/my-worker`
4. **Create from workspace reference:** `agent_create agentId=Worker config=w/templates/my-worker`
5. **Compose:** Pass an ordered config vector of template/provider/prompt/tool-selector references plus a final inline override
6. **Fork existing:** `agent_fork sourceId=TrainedWorker agentId=Worker-2`

This is a fully data-driven workflow — no special APIs, just reading templates and passing them to `agent:create`. The same `config` field handles standard template names, workspace paths, asset references, DID URLs, and inline maps.

---

## 8. Multi-agent pipelines (passing outputs)

When agents chain work — one agent's output feeds the next — there are two mechanisms, and they differ in whether an LLM sits in the *data* path.

### Deterministic: the `orchestrator` operation

For a fixed, known pipeline, define an `orchestrator` operation whose steps reference each other's outputs by index (`[<stepIndex>, <path…>]`, plus `[:const …]` / `[:input …]` / `[:concat …]`). The orchestrator wires each step's output into the next step's input **verbatim**, and runs independent steps in parallel and dependent ones in order — no agent, no LLM, in the data path. This is the right tool whenever the pipeline shape is known ahead of time. See `covia.adapter.Orchestrator`.

### Dynamic: an LLM manager agent

A `manager` agent decides the pipeline at runtime by calling `agent_request` on sub-agents. Here the LLM *is* in the control path, so the data must not be: a model asked to copy a prior agent's full output into the next request will paraphrase or truncate it (issue #71). Use the structural `outputPath` handoff so the manager receives a receipt, not the payload:

- Pass `outputPath="w/pipeline/<run>/step-1"` to `agent_request`. On successful completion the framework writes the worker's full result there and returns `{status, outputPath, bytes}` without `output`.
- Tell the next worker to read that path. The handoff cell is the exact worker result; the manager never copies it through model context.
- Alternatively the manager passes the job reference `j/<jobId>` of a completed request for the next worker to read.
- Requests without `outputPath` retain the existing direct-output response.
- The manager sequences dependent steps (a sufficient `timeout`, or polls an
  async task to completion) rather than firing them in parallel.

`outputPath` uses normal Covia path resolution. Relative and execution-scoped paths
(`w/`, `t/`, `c/`, `n/`) resolve in the requesting manager's captured context;
authorised owner-scoped DID URLs use the same cross-user checks as `covia:write`;
and foreign `did:web` paths dispatch the write to the destination venue. Both
venues enforce the requester's authority at write time. Failed or cancelled tasks
write nothing.

### Capabilities for handoff

A worker can read a handoff path **only if its capability scope covers it** — sharing the owner's namespace is not sufficient. An agent's `config.caps` narrows it to exactly the listed `{with, can}` grants (`AbstractLLMAdapter.capsContext` applies `ctx.withCaps(caps)` to the transition context); an agent with **no** `caps` runs with the owner's full authority and can read any of the owner's paths.

So for a pipeline of *capped* workers, provision the handoff area explicitly:

```json
{
  "name": "Pipeline Stage",
  "tools": ["v/ops/covia/read", "v/ops/covia/write"],
  "caps": [
    {"with": "w/pipeline/", "can": "crud/read"},
    {"with": "w/pipeline/out/", "can": "crud/write"}
  ]
}
```

Every stage can read the shared `w/pipeline/` area. With structural handoff the
**manager** needs `crud/write` on each `outputPath`, because the framework writes
under the requester's captured capability scope; each consumer needs `crud/read` on its
input path. The producing worker does not need write authority merely to return
its result. Uncapped agents need none of these explicit grants — but capping is
the least-privilege posture for untrusted or externally-facing work, and there
the handoff caps are mandatory, not optional.

---

## 9. Implementation

### Phase 1: `config` accepts references and ordered layers ✓ DONE

- `agent:create`'s `config` field accepts an inline map, a string reference, or an ordered vector of either
- Resolution: `AgentAdapter.resolveConfigRef` calls `engine.resolvePath(ref, ctx)` which handles every form (venue paths, workspace paths, asset hashes, DID URLs, pinned ops)
- Layers merge left-to-right; nested maps merge recursively and later non-map values replace earlier values
- Canonical assets expose configuration and optional initial state under the `agent` facet; flat maps remain compatible
- Embedded `state` is extracted and used as initial state
- Schema in `create.json` documents all three forms
- The compatibility `definition` field resolves the same canonical `agent.config` facet and ordered layers

### Phase 2: `agent:fork` ✓ DONE

- `agent:fork` operation in AgentAdapter
- Copies config and state from source; optional `includeTimeline: true` copies run history
- Resets status to SLEEPING; tasks, pending, and sessions are fresh
- Optional `config` override (inline map or string reference) is merged on top of source config per-field
- Source must exist and not be TERMINATED; target must not already exist
- Implementation: `User.forkAgent` + `AgentState.initialiseFromFork`

### Phase 3a: Ship standard templates ✓ DONE

- Provider-neutral template assets in `venue/src/main/resources/agent-templates/` (minimal, skilled, reader, worker, manager, analyst, full, goaltree)
- `AgentAdapter.installAgentTemplate(name, path)` materialises each to `v/agents/templates/<name>` at venue startup
- No special-case lookup — `resolveConfigRef` uses standard `engine.resolvePath` which handles `v/agents/templates/<name>` like any other venue path
- Templates are content-addressed assets and discoverable pins via `covia_list path=v/agents/templates`

### Phase 3b: Swap default

✓ Done: no-config creation uses the `skilled` template, whose deliberately
minimal read-only base (`covia/read`, `covia/list`) is extended on demand via
`skill_load`. Explicit configs remain strict allowlists (#92).

---

## 10. Relation to existing features

| Feature | Relation |
|---------|----------|
| `agent:create config` (inline map) | Unchanged — same as before |
| `agent:create config` (string reference) | New in Phase 1 — resolves workspace paths, asset refs, DID URLs |
| `agent:create definition` | Compatibility entry point for an asset with `agent.config`; prefer placing that asset reference in the ordered `config` stack |
| `DEFAULT_TOOL_OPS` | Replaced by default template in Phase 3 |
| `agent:delete` + recreate | `agent:fork` is cleaner for "reset with modifications" |
| Context loading | Templates can include `context` array — same mechanism |
