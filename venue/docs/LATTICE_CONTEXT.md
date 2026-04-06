# Lattice Agent Context Architecture

**Version:** 1.3 Draft
**Purpose:** Complete context design for an LLM agent on the Convex lattice. Lattice-native replacement for frameworks like Letta.

## Namespaces

Three addressing layers: lattice storage, agent-visible paths, and shorthand prefixes.

### Agent-Visible Paths

| Prefix | Resolves to | Scope | Lifecycle |
|--------|------------|-------|-----------|
| `n/` | `g/{agent}/n/...` | Agent-private workspace | Permanent, dies with agent |
| `t/` | Current task node's scratch | Task-local scratch | Wiped on pop |
| `w/` | `<DID>/w/...` | User shared data | Permanent |
| `o/` | `<DID>/o/...` | Operations registry | Permanent |

`n/` and `t/` are only valid in agent scope. Full paths (e.g. `g/{agent}/timeline/0`) work everywhere.

### Lattice Storage

Per-user state under `:user-data/<DID>`:

| Key | Lattice type | Contents |
|-----|-------------|----------|
| `"g"` | MapLattice + AGENT_LWW | Agent records (atomic, ts-based) |
| `"w"` | MapLattice + JSONValue | User workspace (recursive merge) |
| `"o"` | MapLattice + JSONValue | User operations (recursive merge) |
| `"s"` | MapLattice + LWW | Encrypted secrets |
| `"j"` | IndexLattice + LWW | Job references |
| `"h"` | MapLattice + JSONValue | HITL requests |
| `"a"` | CASLattice | Content-addressed assets |

### Agent Record

An agent's complete state lives at `g/{agentId}` as a single atomic record:

```
g/alice/
  ├── status          ← SLEEPING | RUNNING | SUSPENDED | TERMINATED
  ├── config          ← harness-owned (transition op, tools, LLM params)
  ├── tasks           ← task stack (agent writes via plan())
  ├── n/              ← agent-private workspace (agent read/write)
  │   ├── notes/
  │   ├── drafts/
  │   └── refs/
  ├── timeline        ← append-only audit trail (harness-managed)
  ├── inbox           ← incoming messages (external)
  ├── pending         ← async job references (harness-managed)
  ├── state           ← opaque domain state (transition function)
  ├── ts              ← last-update timestamp (auto)
  ├── error           ← error message if SUSPENDED
  └── wake            ← deferred wake timestamp
```

Framework-managed (read-only to agent): status, config, tasks, timeline, inbox, pending, state, ts, error, wake. The agent writes `n/` directly and `tasks` via `plan()`.

### Destination Shorthand (agent scope only)

| Agent writes | Resolves to |
|-------------|-------------|
| `n/notes/x` | `g/alice/n/notes/x` (agent-private) |
| `t/snapshot` | Current task's scratch area |
| `w/data/x` | `<DID>/w/data/x` (user shared) |
| `o/myop` | `<DID>/o/myop` (user operations) |

## Context Layout

| Section | Size | Attention | Notes |
|---------|------|-----------|-------|
| 1. System prompt | ~2,000 B | Strong (top) | Static. |
| 2. Tool schemas | ~1,600 B | Strong (top) | 6 lattice + 1 harness. MCP deferred. |
| 3. Context map | ~300 B | Strong (top) | Budget + loaded paths. Simple. |
| 4. Loaded data | agent-controlled | Upper | Loaded paths, refreshed each turn. |
| 5. Task conversation | varies | Middle | Current task's history only. Short. |
| 6. Task stack | ~500 B | Strong (bottom) | Focused leaf expanded. |
| 7. Current turn | varies | Strong (bottom) | Tool result + continuation. |

Fixed overhead (sections 1–3, 6): ~4,400 B (~1,100 tokens). Under 0.6%.

**Key insight:** Section 5 is the current task's conversation only — not the entire session. This keeps the middle zone short and focused. Parent and sibling task conversations are not present.

## Lifecycle Model

Three load lifecycles for data in the agent's context. No TTL. Pop is the only automatic cleanup.

| Lifecycle | Source | Expires |
|-----------|--------|---------|
| **Pinned** | Harness | Never (task stack, system prompt) |
| **Global** | `load()` | Explicit `unload()`. Survives push/pop. |
| **Scoped** | `load(scope:)` | Freed when that task pops |

Tool results (`explore`, `compare`) are not "loaded" — they appear in the task conversation and disappear with it on pop.

**Scope incentivises decomposition.** Want automatic cleanup? Break work into tasks. Pop is the only automatic cleanup — no lazy TTL shortcut.

## Task-Scoped Conversation

Each task has its own conversation history. This is the fundamental mechanism that keeps context clean.

### Push (start child task)

When `plan()` sets a child task to `active`, the harness (between turns):

1. Persists the current task's conversation to local storage
2. Activates the child task on the agent record
3. Starts a **fresh conversation** — no parent or sibling history
4. Scoped loads for the child are activated

The agent never pushes directly — it updates the plan, the harness executes the transition.

### Pop (complete or fail child task)

When `plan()` sets a task to `complete` or `failed`, the harness (between turns):

1. Child's conversation is **discarded** (local storage)
2. Child's `t/` scratch data is cleared from the task node
3. Scoped loads are freed
4. Cascades: any active/pending descendants are marked `cancelled`, their conversations discarded, their `t/` cleared
5. Summary inserted into parent conversation: `[{path} completed: "{result}"]` or `[{path} failed: "{error}"]`
6. If more active siblings remain, harness pushes the next one (declaration order)
7. When all active children are done, parent conversation is **resumed**

A task cannot be re-opened after pop. To retry, create a new child task.

### What the agent sees

Only the current task's conversation is in section 5. This means:

```
── Section 5: Task Conversation (/research/vendor-b) ──

load("w/vendors/b/profile", budget: 500, scope: "/research/vendor-b")
→ loaded
explore("w/vendors/b/products", budget: 800)
→ 8 products, 1 flagship, 15% share
explore("w/vendors/b/financials", budget: 600)
→ revenue 2.1B, growth 22%, margin 24%
"Smaller but fastest growing. Higher margins."
```

Clean. No vendor-a history. No parent setup noise. Just this task's work.

### What the parent sees after children pop

```
── Section 5: Task Conversation (/research) ──

"Beginning vendor research."

[vendor-a completed: "23% share, growth slowing"]
[vendor-b completed: "15% share, rapid growth"]
[vendor-c completed: "31% share, declining"]

"All three vendors researched. Moving to comparison."
```

Three one-line results. All the detail was in the child conversations, now discarded.

### Tool results are just conversation

No special result tracking. Tool results live in the task conversation. When the task pops, they're gone. If the agent needs data to survive, it promotes to `n/` (agent-private) or `w/` (shared) before popping.

## Task Stack

### Storage

Tasks are stored flat in the agent's `tasks` Index, keyed by Blob ID. Each task is a map with required `status` field, plus optional `result`, `t` (scratch data), `parent`, `children`, `name`, and arbitrary metadata. The harness renders the flat index as a tree for the agent using task paths (e.g. `/research/vendor-a`).

`plan()` is the only way the agent modifies tasks. The harness translates path-based plan updates to ID-based index mutations.

### Focus and Activation

The agent activates tasks via `plan()`. There is always exactly one active conversation (the focused task).

- Multiple active siblings are processed in declaration order, one at a time
- Parent conversation stays suspended until all active children are done
- After all active children pop, parent resumes and can activate more or proceed
- The harness never auto-activates pending tasks

This allows batch activation ("do A, B, C in parallel") and sequential activation ("do A, then decide") as the agent sees fit.

| State | Rendering |
|-------|-----------|
| Focused (current) | Full — metadata, scoped paths, `t/` contents |
| Other active | Two-line summary |
| Pending | One-line |
| Complete/Failed | One-line + result |

### Task Scratch (`t/`)

Each task node has a `t` field for scratch data. The agent writes via `t/key` shorthand; the harness stores it in the current task's `t` map. On pop, the task node is compacted (status + result only) and `t` is wiped.

No overlay, no fork mechanics — `t/` is simply part of the task node.

**Promote before pop** — agent must write to `n/` (agent-private) or `w/` (shared) to persist results beyond task completion.

## Context Map

```json5
{
  budget: {
    total: 180000, fixed: 4400, loaded: 1200,
    conversation: 2400, available: 172000,
    tokens: {total: 45000, used: 2000, avail: 43000}
  },
  paths: {
    "g/alice/tasks":
      {budget: 500, pinned: true},
    "w/vendors/b/profile":
      {budget: 500, scope: "/research/vendor-b"},
    "n/methodology":
      {budget: 200, scope: "/"}
  }
}
```

Simple. No results tracking. No TTL countdowns.

## Tool Set (7 tools)

### 1. `explore` — One-shot read

```json5
{
  name: "explore",
  description: "Read lattice data at one or more paths, truncated to byte budget. JSON5 with structural annotations. Result appears in task conversation.",
  parameters: {
    paths: "string | string[]", budget: "integer (default: 500)",
    compact: "boolean (default: false)", filter: "string — optional",
    hashes: "boolean (default: false)"
  }
}
```

No `retain` — results live in the task conversation and pop with it.

### 2. `load` — Add refreshing path to context

```json5
{
  name: "load",
  description: "Add lattice path to context. Refreshed each turn. Global (default) or scoped (freed when task pops).",
  parameters: {
    path: "string", budget: "integer (default: 500)",
    scope: "string — optional task path"
  }
}
```

No `ttl` — cleanup is by pop or explicit unload.

### 3. `unload` — Remove from context

```json5
{
  name: "unload",
  description: "Remove path from context. Frees budget. Cannot unload pinned.",
  parameters: { path: "string" }
}
```

### 4. `copy` — Deep-clone to destination

```json5
{
  name: "copy",
  description: "Deep-clone lattice cell. Use n/ for agent workspace, w/ for shared, t/ for task scratch. Immutable snapshot.",
  parameters: { source: "string", dest: "string" }
}
```

### 5. `write` — Author content to destination

```json5
{
  name: "write",
  description: "Write string content. Use n/ for agent workspace (notes, drafts), w/ for shared data, t/ for task scratch.",
  parameters: { dest: "string", value: "string" }
}
```

### 6. `compare` — Efficient lattice diff

```json5
{
  name: "compare",
  description: "Compare two lattice paths, return only differences. Hash comparison skips unchanged subtrees. Inline /* was: */ annotations. Result appears in task conversation.",
  parameters: {
    a: "string", b: "string", budget: "integer (default: 500)"
  }
}
```

### 7. `plan` — Update task stack (harness, schema-enforced)

```json5
{
  name: "plan",
  description: "Create or update task stack. Without path: full replacement. With path: merge at target. Setting status to 'complete' or 'failed' triggers pop: frees scoped loads, discards task conversation (summary preserved in parent), clears t/ scratch, cascades through descendants.",
  parameters: {
    path: "string — optional task path",
    update: "object — merge data. null removes."
  }
}
```

## Agent Loop

```
HARNESS (between turns):
  1. Check task completions/failures → pop:
       discard task conversation (local storage)
       clear t/ scratch from task node
       free scoped loads
       cascade: cancel active/pending descendants
       insert summary into parent conversation
  2. If more active siblings remain:
       push next active sibling (declaration order)
       persist parent conversation to local storage
       start fresh conversation
  3. If all active children done:
       resume parent conversation
  4. If new child task activated by agent:
       persist current conversation to local storage
       push child, start fresh conversation
  5. If root completed → validate, swap next root
  6. Refresh loaded paths from lattice
  7. Render task stack (focused task expanded)
  8. Calculate budget (bytes + tokens)
  9. Safety valve: 70% warn, 90% auto-prune
 10. Assemble prompt (sections 1–7)
 11. Append continuation prompt

AGENT TURN:
  1. Read context map      → budget, loaded paths
  2. Read loaded data      → reference data
  3. Read task conversation → what have I done in this task
  4. Read task stack        → plan, focus
  5. Read current turn      → tool result + continuation
  6. Reason                 → next action
  7. Maybe explore/compare  → results join task conversation
  8. Maybe load/unload      → adjust working set
  9. Maybe write/copy       → persist to n/ or w/, scratch to t/
 10. Maybe plan()           → update tasks (triggers push/pop)
 11. Act                    → Convex MCP tools
 12. Respond
```

## Full Example: Competitive Analysis Report

### Turn 1 — Root task setup

**Task conversation: /**
```
explore("w/vendors", budget: 300)
→ 12 vendors, A/B/C found

plan({update: {
  name: "Competitive analysis: vendors A, B, C",
  status: "active",
  research: {
    status: "active",
    vendor-a: {status: "active"},
    vendor-b: {status: "active"},
    vendor-c: {status: "active"}
  },
  compare: {status: "pending"},
  draft: {status: "pending"},
  review: {status: "pending"}
}})

write("n/methodology",
  "Compare revenue growth, margins, share. Flag risks.")
load("n/methodology", budget: 200, scope: "/")
"Plan created. Starting vendor research."
```

→ Three vendor tasks batch-activated. Harness pushes `/research/vendor-a` (first in declaration order). Root conversation suspended.

### Turn 2 — Research vendor A

**Task conversation: /research/vendor-a** (clean — no root history)
```
load("w/vendors/a/profile", budget: 500, scope: "/research/vendor-a")
→ loaded

explore("w/vendors/a/products", budget: 800)
→ 14 products, 3 flagship, 23% share

explore("w/vendors/a/financials", budget: 600)
→ revenue 4.2B, growth 8%, margin 18%

copy("w/vendors/a/financials", "t/vendor-a-fin")
→ snapshot to task scratch

write("n/vendor-a",
  "Strong portfolio, 23% share. Growth slowing (8% vs 12% prior).
   Margin pressure from new entrants.")

plan({path: "/research/vendor-a",
  update: {status: "complete", result: "23% share, growth slowing"}})
```

→ Pop: conversation discarded. Scoped load freed. `t/` cleared. Parent gets summary.

→ Harness pushes `/research/vendor-b` (next active sibling). Fresh conversation.

### Turn 3 — Research vendor B

**Task conversation: /research/vendor-b** (clean — no vendor A history)
```
load("w/vendors/b/profile", budget: 500, scope: "/research/vendor-b")
→ loaded

explore("w/vendors/b/products", budget: 800)
→ 8 products, 1 flagship, 15% share

explore("w/vendors/b/financials", budget: 600)
→ revenue 2.1B, growth 22%, margin 24%

write("n/vendor-b",
  "Smaller but fastest growing. Higher margins.
   Threat to A in 2-3 years.")

plan({path: "/research/vendor-b",
  update: {status: "complete", result: "15% share, rapid growth"}})
```

→ Pop, push vendor-c (last active sibling). Same pattern.

### Turn 4 — After all research, start comparison

All three vendor tasks were batch-activated, so after vendor-c pops, `/research` resumes automatically.

**Task conversation: /research** (resumed, with child summaries)
```
[vendor-a completed: "23% share, growth slowing"]
[vendor-b completed: "15% share, rapid growth"]
[vendor-c completed: "31% share, declining"]

plan({path: "/research", update: {status: "complete"}})
```

→ Pop `/research`. Root conversation resumed.

**Task conversation: /** (resumed)
```
"Plan created. Starting vendor research."
[research completed: "3 vendors analysed"]

plan({path: "/compare", update: {status: "active"}})
```

→ Push `/compare`. Fresh conversation.

### Turn 5 — Compare

**Task conversation: /compare** (clean)
```
load("n/vendor-a", budget: 300, scope: "/compare")
load("n/vendor-b", budget: 300, scope: "/compare")
load("n/vendor-c", budget: 300, scope: "/compare")
→ all three notes loaded simultaneously

"B strongest growth trajectory. A largest but slowing. C declining."

write("n/comparison",
  "B strongest growth. A largest. C acquisition target.
   Key risk: B's margins may not sustain at scale.")

plan({path: "/compare",
  update: {status: "complete",
    result: "B strongest growth, A largest, C declining"}})
```

→ Pop. Scoped loads freed. Push `/draft`.

### Turn 6 — Draft

**Task conversation: /draft** (clean)
```
load("n/comparison", budget: 400, scope: "/draft")
load("n/methodology", budget: 200, scope: "/draft")

write("n/drafts/competitive-analysis",
  "## Competitive Analysis: Q3 2026\n\n### Summary\n...")

plan({path: "/draft",
  update: {status: "complete",
    result: "draft at n/drafts/competitive-analysis"}})
```

→ Pop. Push `/review`.

### Turn 7 — Review and complete

**Task conversation: /review** (clean)
```
load("n/drafts/competitive-analysis",
  budget: 2000, scope: "/review")

// Agent reviews, spots a gap...
explore("w/vendors/b/financials/breakdown", budget: 500)
→ R&D spend 34% — explains high margins

write("n/drafts/competitive-analysis-final",
  "## Competitive Analysis: Q3 2026 (Final)\n\n...")

plan({path: "/review", update: {status: "complete"}})
plan({update: {status: "complete"}})
```

→ Root complete. All scoped data freed. Harness swaps next root.

**Final root conversation (what the harness archives):**
```
"Plan created. Starting vendor research."
[research completed: "3 vendors analysed"]
[compare completed: "B strongest growth, A largest, C declining"]
[draft completed: "draft at n/drafts/competitive-analysis"]
[review completed: "final at n/drafts/competitive-analysis-final"]
"Report complete."
```

Six lines. The entire multi-turn analysis compressed to an action log.

### What This Demonstrates

- **Clean task conversations** — each vendor research has no sibling noise
- **Batch activation** — all three vendors activated upfront, processed in order
- **Pop discards + summarises** — parent sees one-line results
- **Promote before pop** — `write("n/...")` saves findings to agent workspace
- **Progressive focus** — never holding all vendors' raw data simultaneously
- **`t/` scratch space** — snapshots used during analysis, cleared on pop
- **Conversation stays SHORT** — current task only, maybe 5-10 turns
- **Root archives cleanly** — entire session compresses to ~6 lines

## Full Example: Project Coordination

### Plan

```json5
{
  name: "Product launch coordination",
  status: "active",
  assess: {status: "complete", result: "engineering blocked on auth"},
  unblock: {
    status: "active",
    api-spec: {status: "active", blocker: "missing auth design"},
    marketing: {status: "pending", depends: ["api-spec"]}
  },
  coordinate: {status: "pending"},
  launch: {status: "pending", target: "2026-05-01"}
}
```

### Task conversation: /unblock/api-spec
```
load("w/projects/launch/specs/api", budget: 800, scope: "/unblock/api-spec")
load("w/projects/launch/requirements/auth", budget: 500, scope: "/unblock/api-spec")

explore("w/teams/engineering/drafts/auth-design", budget: 1000)
→ partial design, missing token refresh flow

write("t/auth-options",
  "Option 1: OAuth2+PKCE\nOption 2: mTLS")

write("n/auth-recommendation",
  "Recommend OAuth2+PKCE. Aligns with existing infra.")

plan({path: "/unblock/api-spec",
  update: {status: "complete", result: "auth design recommended"}})
```

→ Pop: scoped loads freed, `t/auth-options` cleared, recommendation persists at `n/`.

Parent conversation gets: `[api-spec completed: "auth design recommended"]`

Marketing task auto-unblocked.

## Best Practices Review

| Practice | Status |
|----------|--------|
| Data at top, query at bottom | ✅ |
| Minimal relevant context | ✅ Budget truncation + task-scoped conversation |
| Observation masking | ✅ Pop discards, parent gets summary only |
| Tiered memory | ✅ Pinned → global → scoped |
| Compaction-resilient | ✅ Sections 1–4, 6 rebuilt by harness |
| Budget awareness | ✅ Bytes + tokens in context map |
| Structured plans | ✅ Uniform tasks, focus rendering |
| Task-scoped context | ✅ Loads, conversation, and /t/ all pop together |
| Task scratch | ✅ t/ in task node, cleared on pop |
| Promote-before-pop | ✅ Explicit save to n/ or w/ for persistent results |
| Slim history | ✅ Current task only + parent summaries |
| Schema efficiency | ✅ 7 tools, ~1,600 B |

## Comparison with Leading Approaches

### Summary

| Dimension | **Letta/MemGPT** | **Claude Code** | **LangChain** | **Covia Lattice** |
|---|---|---|---|---|
| Metaphor | OS (RAM + disk) | IDE session | Middleware pipeline | Task call stack |
| Memory tiers | Core → recall → archival | Context → CLAUDE.md → files | State → store → external | Pinned → global → scoped |
| Cleanup | Agent self-edits | LLM compaction (lossy) | Middleware summarise/mask | **Pop (deterministic cascade)** |
| What gets lost | Agent decides | LLM decides (unpredictable) | Configurable | **Nothing — promote before pop** |
| History scope | Full session + recall search | Single growing conversation | Growing, summarised | **Per-task, discarded on pop** |
| Task structure | None built-in | None (CLAUDE.md conventions) | TodoList (flat) | **Hierarchical stack + cascade** |
| Workspace isolation | None | Subagents (separate process) | None | **Task-scoped t/ scratch, cleared on pop** |
| Data resolution | None | File truncation heuristics | None | **CellExplorer budget control** |
| Change detection | recall_memory_search | Re-read files | None | **compare() with hash-skip** |
| Tool schema overhead | ~2-5K tokens | ~16.8K tokens (8.4% of context) | Varies | **~400 tokens (0.2%)** |

### Where Covia is ahead

**Deterministic cleanup.** Other systems rely on LLM-generated summaries — inherently lossy and unpredictable. Claude Code users report compaction "forgetting" architectural decisions and access control rules. Our pop discards the task conversation, but the agent already promoted findings to `n/` or `w/`. Nothing is lost by surprise. This is RAII vs garbage collection.

**Task-scoped conversation.** No other framework scopes history to tasks. Claude Code operates on a single growing conversation that eventually gets compacted. LangChain summarises the full history. Our agent gets a fresh, focused conversation per task — 5-10 turns, not 200.

**Progressive data resolution.** CellExplorer is unique. A 50MB lattice structure rendered at 500 bytes with structural annotations. No other framework offers budget-controlled views of arbitrary data.

**Task-scoped scratch.** `t/` scratch stored within each task node, cleared on pop. No fork or overlay mechanics — just part of the task lifecycle. Claude Code has manual file checkpoints but they're not task-scoped.

**Structural change detection.** `compare()` skips unchanged subtrees via hash comparison. Orders of magnitude more efficient than re-reading or searching recall memory.

**Schema efficiency.** 7 tools at ~400 tokens vs Claude Code's 16.8K tokens for system tools. 40x more efficient. More context available for actual work.

### Apparent gaps and how Covia covers them

**Self-editing memory / skill learning (Letta).** Letta agents rewrite their own core memory and learn skills from experience. Covia covers this through:
- Agent-private workspace (`n/`) — agent writes learned patterns, preferences, and reusable strategies
- Operations registry (`o/`) — discovered operations cached to `n/refs/` and reloaded for future tasks
- Lattice data is inherently versioned via content-addressing — every state is a snapshot that can be referenced

**Subagent delegation (Claude Code).** Claude Code spawns subagents with isolated context. Covia covers this through:
- **Grid operations** — delegate tasks to remote agents/services on the federated grid
- **Task stack** provides within-agent isolation (each child task gets clean context)
- Multi-agent coordination is a Grid-level concern, not a context management concern

**Git-backed versioning (Letta Context Repositories).** Letta's MemFS projects memory into git-backed files. Covia's lattice is **superior to git** for this purpose:
- Content-addressed Merkle DAG — every value has a unique hash, structural sharing is automatic
- Immutable cells — no merge conflicts, no branch management needed
- `compare()` uses hash trees natively — faster and more precise than git diff
- Task-scoped scratch (`t/`) provides isolated working space per task, cleared on completion

**Agent timeline / history (Letta).** Letta maintains recall memory searchable by date and content. Covia covers this through:
- Agent timeline is a Covia Grid feature — full history of agent actions, decisions, and state transitions
- Archived root conversations are stored on the lattice — retrievable via `explore()`
- Lattice state at any point in time can be referenced via content hashes

**External tool integration (Claude Code MCP).** Claude Code connects to external services via MCP. Covia covers this through:
- **Grid operations** — the federated grid IS the tool integration layer
- **Venue adapters** — bridge to external APIs, databases, services
- Operations discovered via `o/` and invoked through grid — naturally lattice-integrated
- Dynamic tool injection — lattice operations become tool definitions at runtime

**Production maturity.** Claude Code's compaction has been refined through millions of sessions. Letta has extensive benchmarks (Context-Bench, Letta Evals). Our pop-based model is cleaner in theory but unproven at scale. This is the primary gap — it needs implementation and battle-testing.

### The fundamental difference

Every other system treats context management as **damage control** — the conversation grows, eventually you compress or discard, and you hope important information survives.

Covia treats context as **explicitly scoped resources tied to a task lifecycle**. Nothing is lost by surprise. The agent decides what to promote. Conversation history is task-local by design, not compressed after the fact. The lattice provides perfect recall for anything the agent chose to persist.

Other systems: garbage collection (hope the GC picks the right things).
Covia: RAII (resources freed deterministically when their scope ends).

## Design Decisions

1. **Three load lifecycles.** Pinned (harness-managed, always present), global (agent `load()`, explicit `unload()`), scoped (freed when task pops). No TTL. Pop is the only automatic cleanup. Tool results are not "loaded" — they live in conversation.
2. **Task-scoped conversation.** Each task has its own conversation history. Pop discards it. Parent receives the `result` field as a one-line summary. Siblings never see each other's history.
3. **Pop = cascade.** Setting status to `complete` or `failed` triggers pop: discard conversation, clear `t/` scratch, free scoped loads, cascade (cancel active/pending descendants). A task cannot be re-opened — create a new child to retry.
4. **Promote before pop.** Write to `n/` (agent-private) or `w/` (shared) to persist findings. Everything in `t/` and conversation is ephemeral — lost on pop.
5. **Tool results are just conversation.** No special result tracking. `explore()` and `compare()` results live in the task conversation and pop with it.
6. **Push = fresh context.** When `plan()` sets a child to `active`, the harness persists the current conversation to local storage, activates the child, and starts a fresh conversation. The agent never pushes directly.
7. **Parent sees summaries only.** Format: `[{path} completed: "{result}"]` or `[{path} failed: "{error}"]`.
8. **Namespaces.** Three layers: lattice storage (per-user `g`, `w`, `o`, `j`, `s`, `h`, `a`), agent-visible paths (`n/`, `t/`, `w/`, `o/`), and shorthand resolution (harness maps prefixes to lattice locations in agent scope).
9. **Agent record is the unit.** Complete state at `g/{agentId}`: status, config, tasks, `n` (workspace), timeline, inbox, pending, state, ts, error, wake. No nesting layer. The agent record IS the state — fork it, migrate it, archive it.
10. **Task storage.** Flat Index keyed by Blob ID. Each task is a map with required `status`, optional `result`, `t` (scratch), `parent`, `children`, `name`, plus arbitrary metadata. Harness renders flat index as a tree; `plan()` uses paths, harness maps to IDs.
11. **Focus and activation.** Agent activates tasks via `plan()`. Multiple active siblings are processed in declaration order, one at a time. Parent stays suspended until all active children are done. Harness never auto-activates pending tasks.
12. **Task stack at bottom of context.** Adjacent to current turn for recency attention.
13. **Harness continuation prompt.** Always appended. States current task, scoped loads, and suggested next step.
14. **Context map is simple.** Paths + budgets + scopes. No result tracking, no TTL countdowns.
15. **Destination shorthand (agent scope only).** `n/` → `g/{agent}/n/`, `t/` → current task's scratch, `w/` → `<DID>/w/`, `o/` → `<DID>/o/`. Full paths work everywhere.
16. **Single root in LLM context.** Harness manages root queue. External requests create new roots.
17. **Safety valve.** 70%: harness warns in continuation prompt. 90%: auto-prune — unload global loads (most recently loaded first), then truncate oldest conversation turns. Never touches pinned or scoped loads.
18. **Scope incentivises decomposition.** Want automatic cleanup? Break work into tasks.
19. **7 tools, ~1,600 B.** Under 0.5% of context window.
20. **Crash recovery.** Harness reconstructs task stack from agent record (lattice-backed). Suspended conversations are persisted locally — if lost, task resumes with fresh conversation. Agent's `n/` workspace and task `t/` scratch are intact (in agent record on lattice).
