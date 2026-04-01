# Agent Context — Design

Design for loading reference material into an agent's working context at run time.

**Status:** Draft — April 2026

See [AGENT_LOOP.md](./AGENT_LOOP.md) §3.2 for level 2 architecture and `state.config` conventions.

---

## 1. Problem

Agent system prompts grow unwieldy when they inline all the reference material the agent needs — policy rules, data schemas, standard procedures, tool usage guides. The same material is duplicated across agents that share a domain. Updates require touching every agent's config.

Agents can read workspace data with tool calls, but that costs a tool call per reference per run, pollutes conversation history, and relies on the LLM knowing what to fetch and when.

We need a mechanism to **declaratively load reference material into context** before the LLM sees any messages — like a system prompt extension that can point at live data.

---

## 2. Principles

1. **Grid-native.** Context entries are references into the grid — workspace paths, asset IDs, artifact content. Not local files, not URLs. Everything resolvable via the venue's existing resolution infrastructure.

2. **Flexible sources.** Three kinds of context entry, all mixed freely:
   - **Literal text** — hardcoded strings for small, stable instructions
   - **Workspace references** — paths like `w/docs/ap-rules` that resolve to live data
   - **Asset references** — hashes, `/a/` paths, `/o/` names, operation names, DID URLs — resolved via `engine.resolveAsset()`, loading artifact text content or metadata description

3. **Two layers.** Context lives in two places:
   - **`state.config.context`** — declared at agent creation, loaded on every run. The agent's baseline knowledge.
   - **`state.context`** — mutable, added to dynamically. An agent (or its operator) can push context entries into state between runs. Loaded after config context.

4. **Injected, not fetched.** Context entries are resolved by the framework (level 2) and injected as system messages before the conversation. The LLM never sees tool calls for context — it just has the material. No history pollution, no wasted tool calls.

5. **Fail-open on load.** If a context reference can't be resolved (deleted asset, empty workspace path), it is silently skipped. The agent runs with whatever context loaded successfully. Context is supplementary — a missing reference should not crash the agent.

6. **Labels.** Each injected system message is prefixed with a label so the LLM knows what it's reading: `[Context: w/docs/ap-rules]` or `[Context: AP Policy Rules]`.

---

## 3. Context Entry Format

A context entry is either a **string** (inline text or reference) or a **map** (with explicit fields).

### 3.1 String Entries

A plain string is interpreted by prefix:

| Pattern | Resolution |
|---------|------------|
| `w/...`, `g/...`, `o/...`, `j/...`, `s/...` | Workspace/namespace path — read via `user.readPath()` |
| Hex hash (64 chars) | Asset ID — resolve via `engine.resolveAsset()` |
| `/a/...` | Asset path — resolve via `engine.resolveAsset()` |
| `/o/...` | User operation namespace — resolve via `engine.resolveAsset()` |
| `did:...` | DID URL — resolve via `engine.resolveAsset()` |
| Registered operation name (e.g. `test:echo`) | Resolve via `engine.resolveAsset()` |
| Anything else | **Literal text** — injected as-is |

### 3.2 Job Result Entries

A context entry can reference a job result by ID. The job must be complete — if it's still running, the entry is skipped (or awaited if `wait` is specified).

```json
{
  "job": "0x019d4863e68d0000000000000003",
  "label": "Previous Pipeline Result"
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `job` | string | — | Job ID (hex). The presence of a `job` field distinguishes this from other entry types. |
| `label` | string | `Job <id>` | Label prefix for the injected message |
| `wait` | integer | 0 | Milliseconds to wait for a running job. 0 = skip if not complete. |
| `path` | string | — | Optional path into the job output (e.g. `"output.decision"`) |

This is particularly useful for:
- **Pipeline provenance** — Carol's context includes Alice's and Bob's job results
- **Continuation** — an agent picks up where a previous run left off
- **Audit review** — load a past decision as reference when processing a similar case

The `path` field navigates into the job output, so `{"job": "0x...", "path": "output"}` loads just the output value rather than the full job record.

### 3.3 Grid Operation Entries

A context entry can invoke a grid operation at load time. The operation's output becomes the context content. This is powerful — an agent's context can be computed dynamically by any operation on the grid.

```json
{
  "op": "covia:read",
  "input": {"path": "w/docs/ap-rules"},
  "label": "AP Policy Rules"
}
```

The presence of an `op` field distinguishes an operation entry from a reference entry. The operation is invoked synchronously at context load time (before the LLM sees any messages). The output is serialised as the context content.

This generalises all other resolution mechanisms — workspace reads, asset fetches, and even cross-venue lookups are all just grid operations:

```json
// Read from workspace (equivalent to ref: "w/docs/rules")
{"op": "covia:read", "input": {"path": "w/docs/rules"}}

// Fetch asset content
{"op": "asset:content", "input": {"id": "abc123..."}}

// Call a remote venue
{"op": "grid:run", "input": {"venue": "did:web:compliance.example.com", "operation": "policy:latest"}}

// Run an orchestration that assembles context from multiple sources
{"op": "8cd17cbd...", "input": {"scope": "ap"}}
```

**Timeout:** Operation context entries should have a timeout (default 10s). If the operation doesn't complete in time, the entry is skipped (or fails if `required: true`).

**Caching:** Operation results are not cached across runs — they are resolved fresh each time. For expensive operations, store the result in workspace and reference it with a path instead.

This means simple strings like `"Always respond in British English"` work as inline instructions, while `"w/docs/ap-rules"` loads from workspace.

### 3.4 Map Entries

A map entry provides explicit control:

```json
{
  "ref": "w/docs/ap-rules",
  "label": "AP Policy Rules",
  "required": true
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `ref` | string | — | Reference to resolve (same rules as string entries) |
| `text` | string | — | Literal text (mutually exclusive with `ref`) |
| `label` | string | derived from ref | Label prefix for the injected message |
| `required` | boolean | `false` | If true, fail the run when this entry can't be resolved |

Either `ref` or `text` must be present. If `ref` is provided, it is resolved using the rules from §3.1.

### 3.5 Asset Resolution

When a context entry resolves to an asset:

1. **Check for text content.** If the asset has a content payload, attempt to decode as UTF-8 text. This is the primary path for artifacts (documents, policy files, templates).
2. **Fall back to description.** If no content payload, use the `description` field from the asset metadata. This works for operations and agent definitions — their description summarises what they do.
3. **Structured metadata.** If neither content nor description exists, skip (or fail if `required`).

This means storing a policy document as an artifact (`asset_store` with `contentText`) produces a reusable context entry that any agent can reference.

---

## 4. State Structure

### 4.1 Config Context (Baseline)

Declared at agent creation in `state.config.context`. Loaded on every run. The agent's standing knowledge base.

```json
{
  "config": {
    "llmOperation": "langchain:openai",
    "model": "gpt-4o",
    "systemPrompt": "You are Carol, the AP Payment Approver...",
    "context": [
      "w/docs/ap-policy-rules",
      "w/docs/vendor-guidelines",
      "Always use British English in all responses."
    ]
  }
}
```

### 4.2 State Context (Dynamic)

Stored in `state.context`. Mutable — can be pushed by the agent itself, by `agent:update`, or by another agent via messaging conventions. Loaded after config context.

```json
{
  "config": { ... },
  "context": [
    {"ref": "w/docs/q4-budget-memo", "label": "Q4 Budget Memo"},
    "w/docs/emergency-procedures"
  ],
  "history": [ ... ]
}
```

An agent that needs temporary reference material (e.g. a briefing document for a specific task) can add it to `state.context` without modifying its permanent config.

### 4.3 Load Order

Context is injected as system messages in this order:

1. **System prompt** (from `state.config.systemPrompt`) — always first
2. **Config context** (from `state.config.context`) — stable baseline
3. **State context** (from `state.context`) — dynamic additions
4. **Conversation history** — existing messages
5. **Task context** — outstanding tasks (built dynamically per iteration)

This means the LLM sees: identity → reference material → conversation → current work.

---

## 5. Examples

### 5.1 Shared Policy Document

Store the AP policy rules once in workspace:

```
covia_write  path=w/docs/ap-policy-rules  value="AP Policy Rules\n\n- AP-001 Amount Threshold: under $5,000 auto-approve; $5,000–$50,000 manager; over $50,000 VP\n- AP-002 Sanctions: REJECT if FLAGGED\n..."
```

Reference from multiple agents:

```json
// Carol's config
"context": ["w/docs/ap-policy-rules"]

// Dave's config
"context": ["w/docs/ap-policy-rules", "w/docs/escalation-procedures"]
```

Update the rules once — all agents see the new version on their next run.

### 5.2 Artifact as Context

Store a procedures document as an immutable artifact:

```
asset_store  metadata={"name": "AP Procedures v2", "type": "document"}  contentText="1. All invoices over $10,000 require two approvals..."
```

Returns hash `abc123...`. Reference it:

```json
"context": ["abc123def456..."]
```

Immutable — the agent always gets the exact version you stored. Store a new version and update the reference to upgrade.

### 5.3 Inline Instructions

Small, stable instructions can be inline:

```json
"context": [
  "w/docs/ap-policy-rules",
  "Always write enrichment results to w/enrichments/{invoice_number}.",
  "Log all sanctions flags to w/alerts/{vendor_name}."
]
```

### 5.4 Dynamic Context via State

An operator pushes a temporary briefing before a batch run:

```
agent_update  agentId=Dave  state={"context": [{"ref": "w/docs/q4-audit-brief", "label": "Q4 Audit Brief"}]}
```

Dave sees the briefing on his next run. Remove it when the audit is done:

```
agent_update  agentId=Dave  state={"context": []}
```

### 5.5 Agent Self-Loading Context

An agent can add context to its own state during a run (via `covia_write` to its own state path, or the framework could expose a tool). This enables patterns like:

- Agent receives a task with a reference document → adds it to `state.context` → processes on next iteration with full context
- Agent discovers it needs a procedure → reads it, stores in `state.context` for future runs

---

## 6. Implementation Notes

### 6.1 Where It Happens

Context resolution belongs in **level 2** (`LLMAgentAdapter.processChat()`), after the system prompt is built and before the tool call loop. It is a level 2 concern because:

- Level 1 (framework) doesn't know about messages or LLM context
- Level 3 (LLM call) is stateless — it just sees the messages array
- Level 2 owns the message history and knows what the LLM needs to see

### 6.2 Resolution

All references are resolved using existing infrastructure:

- Workspace paths: `user.readPath(ref)` (same as `covia:read`)
- Asset references: `engine.resolveAsset(ref, ctx)` (universal resolution — hash, `/a/`, `/o/`, DID URL, registered name)
- Asset content: `AssetStore.getRecord(hash)` → position 1 (content blob) → decode UTF-8
- Asset description fallback: `asset.meta().get("description")`

### 6.3 Size Considerations

Context entries consume tokens. The framework should:

- Impose a per-entry size limit (e.g. 100KB default, configurable)
- Truncate with a `[truncated]` suffix rather than failing
- Log a warning when total context exceeds a threshold

### 6.4 Caching

Workspace references are resolved fresh on each run (they may change between runs). Asset references are immutable by definition — the venue's asset store already caches parsed metadata. No additional caching layer is needed.

---

## 7. Relationship to Other Features

| Feature | How context relates |
|---------|-------------------|
| **System prompt** | Context extends the system prompt with external references. The prompt defines identity; context provides knowledge. |
| **Tools** | Context replaces "read the docs" tool calls. The agent still has tools for on-demand lookups, but baseline knowledge is pre-loaded. |
| **Workspace** | Context can reference workspace data. The workspace is both a data store agents write to and a knowledge base agents read from. |
| **Assets** | Context can reference artifact content. Assets are the grid-native way to store immutable documents. |
| **Orchestrations** | Orchestrations could set context on agents before invoking them (future enhancement). |
| **UCAN** | Cross-user context loading would require appropriate capabilities (Phase C2+). |

---

## 8. Phasing

| Phase | Scope |
|-------|-------|
| **F1** | `state.config.context` — string entries (workspace paths, asset refs, inline text). Injected as system messages. Fail-open. |
| **F2** | `state.context` — dynamic context layer. Map entries with labels and required flag. |
| **F3** | Size guards, truncation, token budget awareness. |
| **F4** | Agent self-loading context (tool or convention for adding to own `state.context`). |
| **F5** | Cross-user context with UCAN proof verification. |
