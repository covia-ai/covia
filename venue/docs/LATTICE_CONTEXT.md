# Lattice Agent Context Architecture

**Version:** 1.2 Draft
**Purpose:** Complete context design for an LLM agent on the Convex lattice. Lattice-native replacement for frameworks like Letta.

## Namespaces

| Namespace | Purpose | Permissions | Lifecycle |
|-----------|---------|-------------|-----------|
| `/g/{agent}/` | Agent namespace | Harness-owned | Permanent |
| `/g/{agent}/state/plan` | Task stack | Agent via `plan()` | Pinned |
| `/g/{agent}/state/w/` | Agent persistent workspace | Agent read/write | Permanent |
| `/t/` | Task temp workspace | Agent full access | Fork-on-push, rollback-on-pop |
| `/w/` | User/shared data | Per-resource | External |
| `/o/` | Operations registry | Read-only | External |

### Agent State

```
/g/alice/
  ├── state/
  │   ├── plan              ← task stack (pinned)
  │   └── w/                ← persistent workspace
  │       ├── notes/
  │       ├── drafts/
  │       └── refs/
  └── (harness config — harness-owned)
```

### Destination Shorthand

| Agent writes | Resolves to |
|-------------|-------------|
| `w/notes/x` | `/g/alice/state/w/notes/x` |
| `t/snapshot` | `/t/snapshot` |

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

Four lifecycles. No TTL. Cleanup is by pop or explicit unload.

| Lifecycle | Source | Expires |
|-----------|--------|---------|
| **Pinned** | Harness | Never |
| **Global** | `load()` | Explicit `unload()` |
| **Scoped** | `load(scope:)` | Task pops |
| **Ephemeral** | One-shot within turn | Next turn |

**Scope incentivises decomposition.** If you want automatic cleanup, break work into tasks. Pop is the only automatic cleanup — no lazy TTL shortcut.

## Task-Scoped Conversation

Each task has its own conversation history. This is the fundamental mechanism that keeps context clean.

### Push (start child task)

1. Parent's conversation is **suspended** (held by harness, not in context)
2. Child gets a **fresh conversation** — no parent or sibling history
3. Scoped loads for the child are activated
4. `/t/` is forked (child gets clean overlay)

### Pop (complete child task)

1. Child's conversation is **discarded**
2. Child's result is inserted into parent's conversation as a one-line summary
3. Scoped loads are freed
4. `/t/` overlay is rolled back
5. Parent's conversation is **resumed**

### What the agent sees

Only the current task's conversation is in section 5. This means:

```
── Section 5: Task Conversation (/research/vendor-b) ──

load("/w/vendors/b/profile", budget: 500, scope: "/research/vendor-b")
→ loaded
explore("/w/vendors/b/products", budget: 800)
→ 8 products, 1 flagship, 15% share
explore("/w/vendors/b/financials", budget: 600)
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

No special result tracking. Tool results live in the task conversation. When the task pops, they're gone. If the agent needs data to survive, it promotes to `w/` or `t/` before popping.

## Task Stack

### Uniform Tasks

Every node is a task — map with required `status`. Nested maps with `status` are children.

### Paths

Direct: `/research/vendor-a`.

### Focus

Multiple active siblings allowed. Focus = most recently updated active leaf.

| State | Rendering |
|-------|-----------|
| Focused leaf | Full — metadata, scoped paths, `/t/` contents |
| Other active | Two-line summary |
| Pending | One-line |
| Complete | One-line + result |

### Cascade

Pop = free scoped loads + discard conversation + rollback `/t/` + cascade through descendants.

### Transactional `/t/`

Fork-on-push, rollback-on-pop. Copy-on-write via Merkle sharing (free fork). Reads: own overlay → parent overlay → base. Writes: own overlay only.

**Promote before pop** — agent must copy/write to `w/` to persist results beyond task completion.

## Context Map

```json5
{
  budget: {
    total: 180000, fixed: 4400, loaded: 1200,
    conversation: 2400, available: 172000,
    tokens: {total: 45000, used: 2000, avail: 43000}
  },
  paths: {
    "/g/alice/state/plan":
      {budget: 500, pinned: true},
    "/w/vendors/b/profile":
      {budget: 500, scope: "/research/vendor-b"},
    "/g/alice/state/w/notes/methodology":
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
  description: "Deep-clone lattice cell. Use w/ for persistent workspace, t/ for task temp. Immutable snapshot.",
  parameters: { source: "string", dest: "string" }
}
```

### 5. `write` — Author content to destination

```json5
{
  name: "write",
  description: "Write string content. Use w/ for persistent (notes, drafts), t/ for task temp.",
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
  description: "Create or update task stack. Without path: full replacement. With path: merge at target. Completing a task triggers pop: frees scoped loads, discards task conversation (summary preserved in parent), rolls back /t/ overlay, cascades through descendants.",
  parameters: {
    path: "string — optional task path",
    update: "object — merge data. null removes."
  }
}
```

## Agent Loop

```
HARNESS (between turns):
  1. Check task completions → pop:
       discard task conversation
       insert summary into parent conversation
       free scoped loads
       rollback /t/ overlay
       cascade through descendants
  2. If pushing new child task:
       suspend parent conversation
       start fresh conversation
       fork /t/
  3. Update focus
  4. If root completed → validate, swap next root
  5. Refresh loaded paths from lattice
  6. Render task stack (focused leaf expanded)
  7. Calculate budget (bytes + tokens)
  8. Safety valve: 70% warn, 90% prune
  9. Assemble prompt (sections 1–7)
 10. Append continuation prompt

AGENT TURN:
  1. Read context map      → budget, loaded paths
  2. Read loaded data      → reference data
  3. Read task conversation → what have I done in this task
  4. Read task stack        → plan, focus
  5. Read current turn      → tool result + continuation
  6. Reason                 → next action
  7. Maybe explore/compare  → results join task conversation
  8. Maybe load/unload      → adjust working set
  9. Maybe write/copy       → persist to w/ or scratch to t/
 10. Maybe plan()           → update tasks (triggers push/pop)
 11. Act                    → Convex MCP tools
 12. Respond
```

## Full Example: Competitive Analysis Report

### Turn 1 — Root task setup

**Task conversation: /**
```
explore("/w/vendors", budget: 300)
→ 12 vendors, A/B/C found

plan({update: {
  name: "Competitive analysis: vendors A, B, C",
  status: "active",
  research: {
    status: "active",
    vendor-a: {status: "active"},
    vendor-b: {status: "pending"},
    vendor-c: {status: "pending"}
  },
  compare: {status: "pending"},
  draft: {status: "pending"},
  review: {status: "pending"}
}})

write("w/notes/methodology",
  "Compare revenue growth, margins, share. Flag risks.")
load("/g/alice/state/w/notes/methodology", budget: 200, scope: "/")
"Plan created. Starting vendor A research."
```

→ Harness pushes `/research/vendor-a`. Root conversation suspended. Fresh conversation starts.

### Turn 2 — Research vendor A

**Task conversation: /research/vendor-a** (clean — no root history)
```
load("/w/vendors/a/profile", budget: 500, scope: "/research/vendor-a")
→ loaded

explore("/w/vendors/a/products", budget: 800)
→ 14 products, 3 flagship, 23% share

explore("/w/vendors/a/financials", budget: 600)
→ revenue 4.2B, growth 8%, margin 18%

copy("/w/vendors/a/financials", "t/vendor-a-fin")
→ snapshot to temp workspace

write("w/notes/vendor-a",
  "Strong portfolio, 23% share. Growth slowing (8% vs 12% prior).
   Margin pressure from new entrants.")

plan({path: "/research/vendor-a",
  update: {status: "complete", result: "23% share, growth slowing"}})
```

→ Pop: conversation discarded. `/w/vendors/a/profile` unloaded. `/t/` rolled back. Parent gets summary.

→ Harness pushes `/research/vendor-b`. Fresh conversation.

### Turn 3 — Research vendor B

**Task conversation: /research/vendor-b** (clean — no vendor A history)
```
load("/w/vendors/b/profile", budget: 500, scope: "/research/vendor-b")
→ loaded

explore("/w/vendors/b/products", budget: 800)
→ 8 products, 1 flagship, 15% share

explore("/w/vendors/b/financials", budget: 600)
→ revenue 2.1B, growth 22%, margin 24%

write("w/notes/vendor-b",
  "Smaller but fastest growing. Higher margins.
   Threat to A in 2-3 years.")

plan({path: "/research/vendor-b",
  update: {status: "complete", result: "15% share, rapid growth"}})
```

→ Pop, push vendor-c. Same pattern.

### Turn 4 — After all research, start comparison

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
"Plan created. Starting vendor A research."
[research completed: "3 vendors analysed"]

plan({path: "/compare", update: {status: "active"}})
```

→ Push `/compare`. Fresh conversation.

### Turn 5 — Compare

**Task conversation: /compare** (clean)
```
load("/g/alice/state/w/notes/vendor-a", budget: 300, scope: "/compare")
load("/g/alice/state/w/notes/vendor-b", budget: 300, scope: "/compare")
load("/g/alice/state/w/notes/vendor-c", budget: 300, scope: "/compare")
→ all three notes loaded simultaneously

"B strongest growth trajectory. A largest but slowing. C declining."

write("w/notes/comparison",
  "B strongest growth. A largest. C acquisition target.
   Key risk: B's margins may not sustain at scale.")

plan({path: "/compare",
  update: {status: "complete",
    result: "B strongest growth, A largest, C declining"}})
```

→ Pop. Notes unloaded. Push `/draft`.

### Turn 6 — Draft

**Task conversation: /draft** (clean)
```
load("/g/alice/state/w/notes/comparison", budget: 400, scope: "/draft")
load("/g/alice/state/w/notes/methodology", budget: 200, scope: "/draft")

write("w/drafts/competitive-analysis",
  "## Competitive Analysis: Q3 2026\n\n### Summary\n...")

plan({path: "/draft",
  update: {status: "complete",
    result: "draft at w/drafts/competitive-analysis"}})
```

→ Pop. Push `/review`.

### Turn 7 — Review and complete

**Task conversation: /review** (clean)
```
load("/g/alice/state/w/drafts/competitive-analysis",
  budget: 2000, scope: "/review")

// Agent reviews, spots a gap...
explore("/w/vendors/b/financials/breakdown", budget: 500)
→ R&D spend 34% — explains high margins

write("w/drafts/competitive-analysis-final",
  "## Competitive Analysis: Q3 2026 (Final)\n\n...")

plan({path: "/review", update: {status: "complete"}})
plan({update: {status: "complete"}})
```

→ Root complete. All scoped data freed. All `/t/` rolled back. Harness swaps next root.

**Final root conversation (what the harness archives):**
```
"Plan created. Starting vendor A research."
[research completed: "3 vendors analysed"]
[compare completed: "B strongest growth, A largest, C declining"]
[draft completed: "draft at w/drafts/competitive-analysis"]
[review completed: "final at w/drafts/competitive-analysis-final"]
"Report complete."
```

Six lines. The entire multi-turn analysis compressed to an action log.

### What This Demonstrates

- **Clean task conversations** — each vendor research has no sibling noise
- **Pop discards + summarises** — parent sees one-line results
- **Promote before pop** — `write("w/...")` saves findings before completion
- **Progressive focus** — never holding all vendors' raw data simultaneously
- **`/t/` scratch space** — snapshots used during analysis, rolled back
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
load("/w/projects/launch/specs/api", budget: 800, scope: "/unblock/api-spec")
load("/w/projects/launch/requirements/auth", budget: 500, scope: "/unblock/api-spec")

explore("/w/teams/engineering/drafts/auth-design", budget: 1000)
→ partial design, missing token refresh flow

write("t/auth-options",
  "Option 1: OAuth2+PKCE\nOption 2: mTLS")

write("w/notes/auth-recommendation",
  "Recommend OAuth2+PKCE. Aligns with existing infra.")

plan({path: "/unblock/api-spec",
  update: {status: "complete", result: "auth design recommended"}})
```

→ Pop: specs unloaded, `/t/auth-options` rolled back, recommendation persists at `w/`.

Parent conversation gets: `[api-spec completed: "auth design recommended"]`

Marketing task auto-unblocked.

## Best Practices Review

| Practice | Status |
|----------|--------|
| Data at top, query at bottom | ✅ |
| Minimal relevant context | ✅ Budget truncation + task-scoped conversation |
| Observation masking | ✅ Pop discards, parent gets summary only |
| Tiered memory | ✅ Pinned → global → scoped → ephemeral |
| Compaction-resilient | ✅ Sections 1–4, 6 rebuilt by harness |
| Budget awareness | ✅ Bytes + tokens in context map |
| Structured plans | ✅ Uniform tasks, focus rendering |
| Task-scoped context | ✅ Loads, conversation, and /t/ all pop together |
| Transactional workspace | ✅ /t/ fork-on-push, rollback-on-pop |
| Promote-before-pop | ✅ Explicit save to w/ for persistent results |
| Slim history | ✅ Current task only + parent summaries |
| Schema efficiency | ✅ 7 tools, ~1,600 B |

## Comparison with Leading Approaches

### Summary

| Dimension | **Letta/MemGPT** | **Claude Code** | **LangChain** | **Covia Lattice** |
|---|---|---|---|---|
| Metaphor | OS (RAM + disk) | IDE session | Middleware pipeline | Task call stack |
| Memory tiers | Core → recall → archival | Context → CLAUDE.md → files | State → store → external | Pinned → global → scoped → ephemeral |
| Cleanup | Agent self-edits | LLM compaction (lossy) | Middleware summarise/mask | **Pop (deterministic cascade)** |
| What gets lost | Agent decides | LLM decides (unpredictable) | Configurable | **Nothing — promote before pop** |
| History scope | Full session + recall search | Single growing conversation | Growing, summarised | **Per-task, discarded on pop** |
| Task structure | None built-in | None (CLAUDE.md conventions) | TodoList (flat) | **Hierarchical stack + cascade** |
| Workspace isolation | None | Subagents (separate process) | None | **Forked /t/ per task, rollback** |
| Data resolution | None | File truncation heuristics | None | **CellExplorer budget control** |
| Change detection | recall_memory_search | Re-read files | None | **compare() with hash-skip** |
| Tool schema overhead | ~2-5K tokens | ~16.8K tokens (8.4% of context) | Varies | **~400 tokens (0.2%)** |

### Where Covia is ahead

**Deterministic cleanup.** Other systems rely on LLM-generated summaries — inherently lossy and unpredictable. Claude Code users report compaction "forgetting" architectural decisions and access control rules. Our pop discards the task conversation, but the agent already promoted findings to `w/`. Nothing is lost by surprise. This is RAII vs garbage collection.

**Task-scoped conversation.** No other framework scopes history to tasks. Claude Code operates on a single growing conversation that eventually gets compacted. LangChain summarises the full history. Our agent gets a fresh, focused conversation per task — 5-10 turns, not 200.

**Progressive data resolution.** CellExplorer is unique. A 50MB lattice structure rendered at 500 bytes with structural annotations. No other framework offers budget-controlled views of arbitrary data.

**Transactional workspace.** `/t/` with fork-on-push and rollback-on-pop. Copy-on-write via Merkle sharing makes forking free. Claude Code has manual file checkpoints but they're not task-scoped.

**Structural change detection.** `compare()` skips unchanged subtrees via hash comparison. Orders of magnitude more efficient than re-reading or searching recall memory.

**Schema efficiency.** 7 tools at ~400 tokens vs Claude Code's 16.8K tokens for system tools. 40x more efficient. More context available for actual work.

### Apparent gaps and how Covia covers them

**Self-editing memory / skill learning (Letta).** Letta agents rewrite their own core memory and learn skills from experience. Covia covers this through:
- Agent persistent workspace (`/g/{agent}/state/w/`) — agent writes learned patterns, preferences, and reusable strategies
- Operations registry (`/o/`) — discovered operations cached to `w/refs/` and reloaded for future tasks
- Lattice data is inherently versioned via content-addressing — every state is a snapshot that can be referenced

**Subagent delegation (Claude Code).** Claude Code spawns subagents with isolated context. Covia covers this through:
- **Grid operations** — delegate tasks to remote agents/services on the federated grid
- **Task stack** provides within-agent isolation (each child task gets clean context)
- Multi-agent coordination is a Grid-level concern, not a context management concern

**Git-backed versioning (Letta Context Repositories).** Letta's MemFS projects memory into git-backed files. Covia's lattice is **superior to git** for this purpose:
- Content-addressed Merkle DAG — every value has a unique hash, structural sharing is automatic
- Immutable cells — no merge conflicts, no branch management needed
- `compare()` uses hash trees natively — faster and more precise than git diff
- Copy-on-write forking (our `/t/` workspace) is what git tries to do, but at the data structure level rather than the file level

**Agent timeline / history (Letta).** Letta maintains recall memory searchable by date and content. Covia covers this through:
- Agent timeline is a Covia Grid feature — full history of agent actions, decisions, and state transitions
- Archived root conversations are stored on the lattice — retrievable via `explore()`
- Lattice state at any point in time can be referenced via content hashes

**External tool integration (Claude Code MCP).** Claude Code connects to external services via MCP. Covia covers this through:
- **Grid operations** — the federated grid IS the tool integration layer
- **Venue adapters** — bridge to external APIs, databases, services
- Operations discovered via `/o/` and invoked through grid — naturally lattice-integrated
- Dynamic tool injection — lattice operations become tool definitions at runtime

**Production maturity.** Claude Code's compaction has been refined through millions of sessions. Letta has extensive benchmarks (Context-Bench, Letta Evals). Our pop-based model is cleaner in theory but unproven at scale. This is the primary gap — it needs implementation and battle-testing.

### The fundamental difference

Every other system treats context management as **damage control** — the conversation grows, eventually you compress or discard, and you hope important information survives.

Covia treats context as **explicitly scoped resources tied to a task lifecycle**. Nothing is lost by surprise. The agent decides what to promote. Conversation history is task-local by design, not compressed after the fact. The lattice provides perfect recall for anything the agent chose to persist.

Other systems: garbage collection (hope the GC picks the right things).
Covia: RAII (resources freed deterministically when their scope ends).

## Design Decisions

1. **Four lifecycles.** Pinned → global → scoped → ephemeral. No TTL. Pop is the cleanup mechanism.
2. **Task-scoped conversation.** Each task has its own conversation. Pop discards it. Parent gets one-line summary. Siblings never see each other's history.
3. **Pop = cascade.** Frees scoped loads + discards conversation + rolls back `/t/` + cascades descendants.
4. **Promote before pop.** Write to `w/` to persist. Everything in task scope and `/t/` is ephemeral by default.
5. **Tool results are just conversation.** No special result tracking. They live in the task conversation and pop with it.
6. **Push = fresh context.** Parent conversation suspended. Child gets clean conversation + forked `/t/`.
7. **Parent sees summaries only.** Child task result inserted as one-line entry in parent conversation.
8. **Covia namespaces.** `/g/` agents, `/w/` user data, `/o/` operations, `/t/` temp workspace.
9. **Agent state at `/g/{agent}/state/`.** Plan at `state/plan`, persistent workspace at `state/w/`.
10. **Uniform task nodes.** `status` field distinguishes children from metadata.
11. **Focus = most recently modified active leaf.**
12. **Task stack at bottom of context.** Adjacent to current turn for recency attention.
13. **Harness continuation prompt.** Always appended. States next step.
14. **Context map is simple.** Just paths + budgets + scopes. No results tracking, no TTL countdowns.
15. **Destination shorthand.** `w/...` → `/g/{agent}/state/w/...`. `t/...` → `/t/...`.
16. **Single root in LLM context.** Harness manages root queue.
17. **Safety valve.** 70% warn, 90% prune.
18. **Scope incentivises decomposition.** Break work into tasks → get automatic cleanup.
19. **7 tools, ~1,600 B.** Under 0.5% of context window.
