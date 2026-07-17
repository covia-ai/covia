# Agent Context — Design

Design for loading reference material into an agent's working context at run time.

**Status:** Current — implemented (string / workspace / asset / job / op entries; configured layer; labelled system messages). §8's scope chain (agent → session → frame tiers) is **implemented** (`ContextChain`, #142); the old `state.context` dynamic layer described in §4.2–4.3 is **retired** — dynamic context is the loads scope chain. Skills ([SKILLS.md](./SKILLS.md)) build on loads entries: a skill-flagged entry renders its instructions and contributes tools via the generic "a loads entry may declare `tools`" rule.

See [AGENT_LOOP.md](./AGENT_LOOP.md) §3.2 for level 2 architecture and config conventions.

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

3. **Two roles, one pipeline.** Context exists in two *roles* that share all machinery (see §8):
   - **Configured** (`config.context`) — declared by whoever configured the agent, loaded every run, the agent's baseline knowledge. **Pinned**: the agent cannot remove it.
   - **Agent-managed** — the working set the agent curates at run time via `context_load` / `context_unload`. Mutable and evictable.

   Both roles use the same entry grammar (§3), the same resolver and rendering contract (§3.6), and one shared budget. They differ only in *who owns the entry* and *its lifetime* — not in what an entry can be. So the agent can build context from ops, jobs, refs, or text exactly as configuration can.

4. **Injected, not fetched.** Context entries are resolved by the framework (level 2) and injected as system messages before the conversation. The LLM never sees tool calls for context — it just has the material. No history pollution, no wasted tool calls.

5. **Fail-visible, not fail-silent.** Each entry has three possible outcomes:
   - **Absent / empty** (deleted asset, empty workspace path, an assemble op that returns nothing) → **skipped** quietly. Context is supplementary; a source with nothing to add should add no noise.
   - **Errors while resolving** (an assemble op throws or times out, a read genuinely fails) → a **visible** `[Context: <label> — unavailable: <reason>]` system element is injected, so the LLM knows that source is broken and can adapt (retry via a tool, tell the user) rather than silently operating without it.
   - **`required: true`** → a resolution failure **throws** and fails the turn. Use only for context the agent genuinely cannot run without.

   Separately, the `context` value *itself* must be an **array** (or absent): a present-but-malformed `config.context` / `state.context` (a string, map, number, …) is a configuration error and **throws** so it gets fixed, rather than silently leaving the agent with no context. Net rule: *bad shape → throw; `required` failure → throw; error → visible element; absent/empty → skip.*

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
  "op": "v/ops/covia/read",
  "input": {"path": "w/docs/ap-rules"},
  "label": "AP Policy Rules"
}
```

The presence of an `op` field distinguishes an operation entry from a reference entry. The operation is invoked **fresh every turn** at context load time (before the LLM sees any messages), under the caller's identity. The output becomes the context content. Operation references use the `v/ops/<adapter>/<op>` catalog form. Because they run on every turn, **assemble ops must be read-only / side-effect-free** (a write-on-assemble would fire every turn).

This generalises all other resolution mechanisms — workspace reads, asset fetches, cross-venue lookups, and purpose-built "assemble" ops are all just grid operations:

```json
// Read from workspace (equivalent to ref: "w/docs/rules")
{"op": "v/ops/covia/read", "input": {"path": "w/docs/rules"}}

// Assemble the user's memory as an always-in-context numbered list (see §5.6)
{"op": "v/ops/memory", "input": {"command": "recall", "path": "w/memory"}, "label": "User memory"}

// Call a remote venue
{"op": "v/ops/grid/run", "input": {"venue": "did:web:compliance.example.com", "operation": "policy:latest"}}

// Run an orchestration that assembles context from multiple sources
{"op": "8cd17cbd...", "input": {"scope": "ap"}}
```

**Timeout:** Operation context entries have a timeout (default 10s). If the operation doesn't complete in time, the entry is skipped (or fails if `required: true`).

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

### 3.6 Return & rendering contract

Whatever an entry resolves to (an op's output, a workspace value, a job result, literal text) flows through one rendering contract before it reaches the model. This is the data-shape agreement between the three layers:

| Layer | Shape |
|-------|-------|
| **Specify** (`config.context` / `state.context`) | a JSON **array** of entries (or absent). Wrong shape → throws (§2, principle 5). Each entry is a string or one of the maps above. |
| **Return** (what the source yields) | **any value**, or nothing. A returned `null` (e.g. an assemble op with nothing to add) means "inject nothing" — the entry is skipped, no empty block. |
| **Render** (what the model sees) | exactly one `system` message per non-null entry: `[Context: <label>]` followed by the rendered value. **A string is rendered verbatim** (newlines/markdown preserved); **a structured value is rendered as budget-bounded JSON5**. |
| **On error** (resolution throws) | a visible `[Context: <label> — unavailable: <reason>]` element is injected instead — unless the entry is `required`, which throws and fails the turn (§2, principle 5). |

Consequences for authoring an **assemble op** (an op written to be referenced from `context`):

- Return a **string** when you want exact formatting in the prompt (e.g. a numbered list); return a **map/vector** when a compact JSON5 view is fine — it will be truncated to the per-entry budget.
- Return **null** to contribute nothing this turn (no empty heading appears).
- **Throw** (don't return null) when a failure should be visible to the LLM — it becomes an "unavailable" element. Return null only when there is genuinely nothing to add.
- Supply the **`label`** on the *entry* (not inside the op output) — without it the heading falls back to the raw op path. The op should return only the body.
- Keep it **read-only** — it runs every turn.

---

## 4. State Structure

### 4.1 Config Context (Baseline)

Declared at agent creation in `config.context`. Loaded on every run. The agent's standing knowledge base.

```json
{
  "config": {
    "llmOperation": "v/ops/langchain/openai",
    "model": "gpt-5.4-mini",
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

1. **System prompt** (from `config.systemPrompt`) — always first
2. **Config context** (from `config.context`) — stable baseline
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

### 5.6 Always-in-context user memory (assemble op)

A purpose-built assemble op computes context dynamically. The single `v/ops/memory` tool (one tool, dispatched by a `command`) renders the user's memory — a durable numbered list — as an always-present block via `command: "recall"`:

```json
// agent config
"context": [
  {"op": "v/ops/memory", "input": {"command": "recall", "path": "w/memory"}, "label": "User memory"}
]
```

Every turn this injects:

```
[Context: User memory]
1. Prefers plain-English explanations
2. Anxious about heart health
```

The numbers are stable edit handles: the agent (or the user, via the agent) maintains the list with the same `memory` tool — `command: "remember"` (append), `"update"` (replace item *n*), `"forget"` (remove item *n*). `recall` can also point at a **map** collection (e.g. a slug-keyed clinical problem list) via a `displayField`, rendering its active/surfaceable values as the numbered list. It returns the bare numbered list (the heading comes from the entry `label`), returns nothing when empty (so the entry is skipped), and is read-only — exactly the assemble-op contract from §3.6.

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

## 8. Unified Context Model — the Context Scope Chain (#142)

**Loads form a scope chain with lexical-scoping semantics.** Tiers, outer → inner:

```
agent (config.loads)  →  session (sessions.<sid>.loads)  →  frame (frame.loads, goaltree)
```

Every tier has the same two-part shape: **automatic loads declared when the tier's container is created** by that tier's author (the operator at create/update, the caller at session mint via a `loads` param on `agent:chat`/`agent:request`, the parent frame at `subgoal` via its `loads` argument), plus **dynamic entries written by the runtime while the tier is innermost** (`context_load`/`context_unload`).

Rules (implemented in `ContextChain`, pure functions):

- **Assembly = union down the chain, inner shadows outer** on a path collision.
- **Masking**: `context_unload` of a path supplied by an outer tier writes a **nil tombstone** at the innermost tier — excluded from that tier inward; the outer entry and every other session/frame are untouched. A later `context_load` overwrites the tombstone (local un-mask). Goaltree's copy-on-push frame inheritance copies tombstones too, so masks propagate to child frames.
- **Inner tiers read outer tiers but never mutate them** — one writer per tier, recursively (the ownership model of #144 applied to scope).
- **Budget & safety valve respect the hierarchy**: the agent tier (operator-pinned) is never pruned; dynamic tiers prune innermost-first, LIFO by timestamp.
- **No session in scope** → no writable tier: `context_load`/`unload` fail with a diagnosable tool result.
- Agent-level `state.loads` and `state.context` are retired; `agent:create`/`update` reject a `loads` (or `config`) key inside `state`.

The two roles below remain the ends of the chain; the session and frame tiers sit between them.

### 8.1 The two roles

- **Configured context** — entries in `config.context` (rendered entries) and `config.loads` (pinned loads). Owned by whoever configured the agent; the agent's standing knowledge. **Pinned**: the agent cannot remove it (only mask it per-conversation); it changes only through configuration (`agent_create` / `agent_update`).
- **Agent-managed context** — the working set the agent curates while pursuing a goal via `context_load` / `context_unload`, scoped to the session (llmagent) or frame (goaltree) tier. Mutable and evictable per conversation.

Both are *the same kind of thing*: a set of context entries, each resolved fresh every turn and injected as a labelled system message ahead of the conversation. The distinction is ownership, not mechanism.

### 8.2 Shared entry grammar and capabilities

Every entry — in either role — uses the entry model of §3: a string (path / asset / literal) or a map (`ref`, `text`, `op`+`input`, `job`+`path`) with optional `label`, `required`, and `budget`.

The capability this unification adds: **agent-managed entries are no longer path-only.** The agent can pin a computed result the same way configuration can — an op (`{op, input, label}`), a job result (`{job, path}`), a literal note (`{text}`), or a reference (`{ref}`). *Using ops to build context* becomes available to both roles. An agent can, for example, `context_load {op: "v/ops/memory", input: {command: "recall", …}, label: "User memory"}` to keep a computed view always present — exactly what an operator can declare in config today.

### 8.3 Shared resolution, rendering, and budget

- **Resolver & rendering** — identical for both roles: the contract in §3.6 (skip-absent, fail-visible, required-throws; string verbatim, structured value as budget-bounded JSON5).
- **One budget** — a single per-agent context budget. Each entry, in either role, carries a per-entry byte budget (declared or derived) that bounds its rendering and is accounted against the total.
- **Context map** — one live inventory lists every loaded entry with its role, label, and budget, plus total usage and a near-ceiling warning. Configured entries become visible and accounted consistently — today they consume budget but appear in neither the context map nor the safety valve, so a heavy pinned entry can silently starve the working set with no signal.

### 8.4 What differs — role semantics only

| Property | Configured | Agent-managed |
|----------|-----------|---------------|
| Declared by | operator / configuration | the agent, at run time |
| Mutated via | `agent_create` / `agent_update` | `context_load` / `context_unload` (or `agent_update`) |
| Agent may remove it | No — pinned | Yes |
| Eviction under budget pressure | Never auto-evicted | LIFO safety-valve eviction (newest first) until back under the warn threshold |
| Goaltree lifetime | Inherited down the whole frame stack | Scoped to the active frame |

If configured context alone exceeds the budget, that is a configuration error to surface — not something the safety valve silently prunes.

### 8.5 Tool surface

- **`context_load(entry)`** — `entry` is the full §3 entry model (a path string, or a map with `ref` / `text` / `op`+`input` / `job`+`path`, plus `label`, `budget`, `required`). Adds or replaces an entry in the agent-managed set. Takes effect next turn.
- **`context_unload(ref)`** — removes an agent-managed entry by its reference/label. Removing a configured (pinned) entry is **rejected** — pinned context belongs to the operator, not the agent.

(A future `context_pin` could promote an agent-managed entry to configured; out of scope here.)

### 8.6 Load order

System prompt → configured context → agent-managed context → conversation history → current work (outstanding tasks / pending results). Configured precedes agent-managed so baseline knowledge frames the working set.

### 8.7 Goaltree frame scoping

Agent-managed context is per-frame: a subgoal inherits the configured context but starts with its own empty agent-managed set, curating loads for its sub-task without polluting the parent or siblings. Configured context flows down the stack unchanged. This frame scoping is the one reason the agent-managed store is not a single flat agent-level list — it is the legitimate structural difference the unified model preserves.

### 8.8 What this consolidates

At the design level the unified model collapses the parallel structures that grew up around the two roles:

- The dynamic `state.context` layer and the agent-managed `loads` store become **one agent-managed context set** with a single shape.
- The separate resolution paths (one for configured entries, one for loaded paths, plus a near-duplicate used by the goaltree assembler) become **one resolver** invoked identically for every entry.
- Budget accounting, the context map, and the safety valve apply **uniformly** to all entries, scoped by role (evict agent-managed only).

The user-facing distinction — *a configured baseline the agent can't drop* vs *a working set the agent curates* — is preserved deliberately; only the duplicated machinery behind it is merged.

---

## 9. Phasing

| Phase | Scope |
|-------|-------|
| **F1** | `config.context` — string entries (workspace paths, asset refs, inline text). Injected as system messages. Fail-open. |
| **F2** | `state.context` — dynamic context layer. Map entries with labels and required flag. |
| **F3** | Size guards, truncation, token budget awareness. |
| **F4** | Agent self-loading context (tool or convention for adding to own `state.context`). |
| **F5** | Cross-user context with UCAN proof verification. |
| **F6** | Unify configured + agent-managed context (§8): one entry grammar (agent-managed gains op/job/text entries), one resolver, one budget + context map + eviction; preserve pinned-vs-managed semantics and goaltree frame scoping. |
