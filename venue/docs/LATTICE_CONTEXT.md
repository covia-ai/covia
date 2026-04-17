# Lattice Agent Context Architecture

**Version:** 1.3 Draft
**Purpose:** Complete context design for an LLM agent on the Convex lattice. Lattice-native replacement for frameworks like Letta.

## Namespaces

Three addressing layers: lattice storage, agent-visible paths, and shorthand prefixes.

### Agent-Visible Paths

Five shorthands, one per scope. Each resolves to a single state bucket. Data is never wiped — all state persists at its full lattice path for audit. "Scope" only governs where writes are directed based on the currently focused context.

| Prefix | Resolves to | Scope | Writes accepted while… |
|--------|------------|-------|------------------------|
| `t/` | `g/{agent}/tasks/{taskId}/t/...` | Task state (currently focused job) | A task is focused |
| `c/` | `g/{agent}/sessions/{sid}/c/...` | Session / conversation state | A session is active |
| `n/` | `g/{agent}/n/...` | Agent state (cross-session) | Agent is not TERMINATED |
| `w/` | `<DID>/w/...` | User shared data | Always |
| `o/` | `<DID>/o/...` | User operations registry | Always |

`t/`, `c/`, `n/` are only valid in agent scope. Full paths (e.g. `g/{agent}/timeline/0`) work everywhere.

**Note:** `t/` means **task scratch** where "task" is the Covia external unit of work — a job assigned to the agent via `agent_request`. `t/key` resolves to the `t` field inside the taskdata map at `g/{agent}/tasks/{taskId}` (the same Index that's existed since Phase B5). Do not confuse with **goal-tree nodes** (internal recursive context scoping — see "Goal tree" below), which have no top-level shorthand.

**Data persists.** Tasks, sessions, and goals are not wiped at any lifecycle boundary — completion, pop, archive, and termination all leave the data at its full lattice path for audit. "Scope" in the table above governs only where *writes via shorthand* are currently directed, not when data is removed.

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
  ├── tasks           ← Index of tasks (Covia jobs assigned to this agent).
  │                     Each entry is a taskdata map with input, status,
  │                     result, sessionId?, goals?, and a t/ field used by
  │                     the `t/` shorthand.
  ├── sessions        ← Index of sessions (conversational scopes). See
  │                     AGENT_SESSIONS.md for shape.
  ├── n/              ← agent-private workspace (agent read/write)
  │   ├── notes/
  │   ├── drafts/
  │   └── refs/
  ├── timeline        ← append-only audit trail (harness-managed)
  ├── ts              ← last-update timestamp (auto)
  ├── error           ← error message if SUSPENDED
  └── wake            ← deferred wake timestamp
```

Framework-managed (read-only to agent): status, config, tasks, sessions, timeline, ts, error, wake. The agent writes `n/` directly and manipulates the current task's `goals` tree via `plan()`. Messages are delivered via `session.pending` (see AGENT_SESSIONS.md).

### Task vs Goal — terminology

- **Task** (Covia external) — a job assigned to the agent via `agent_request`. One per entry in the `tasks` Index. The `t/` shorthand is per-task. Tasks are not "popped" — they complete with a result and remain in the Index as audit.
- **Goal** (internal, optional) — a node in a recursive decomposition the agent builds *within* a task to structure its own work. Goal trees are manipulated by `plan()`, rendered as paths like `/research/vendor-a`, and use push/pop semantics for conversation focus. Goals have no top-level shorthand — they're below the task.

A task may contain a goal tree (`goals` field inside taskdata); a goal tree always lives inside exactly one task.

### Destination Shorthand (agent scope only)

| Agent writes | Resolves to |
|-------------|-------------|
| `n/notes/x` | `g/alice/n/notes/x` (agent-private) |
| `t/snapshot` | `g/alice/tasks/{taskId}/t/snapshot` — focused task's scratch |
| `c/topic` | `g/alice/sessions/{sid}/c/topic` — active session's scratch |
| `w/data/x` | `<DID>/w/data/x` (user shared) |
| `o/myop` | `<DID>/o/myop` (user operations) |

## Context Layout

| Section | Size | Attention | Notes |
|---------|------|-----------|-------|
| 1. System prompt | ~2,000 B | Strong (top) | Static. |
| 2. Tool schemas | ~1,600 B | Strong (top) | 6 lattice + 1 harness. MCP deferred. |
| 3. Context map | ~300 B | Strong (top) | Budget + loaded paths. Simple. |
| 4. Loaded data | agent-controlled | Upper | Loaded paths, refreshed each turn. |
| 5. Goal conversation | varies | Middle | Current goal's history only. Short. |
| 6. Goal tree | ~500 B | Strong (bottom) | Focused leaf expanded. |
| 7. Current turn | varies | Strong (bottom) | Tool result + continuation. |

Fixed overhead (sections 1–3, 6): ~4,400 B (~1,100 tokens). Under 0.6%.

**Key insight:** Section 5 is the current goal's conversation only — not the entire task or session. This keeps the middle zone short and focused. Parent and sibling goal conversations are not present.

## Lifecycle Model

Two separate lifecycle concepts apply, and they should not be conflated:

1. **Active context scope** — where data appears *in the LLM's live prompt*. This shrinks and grows as goals are pushed and popped. "Freeing on pop" means removing from the live prompt, not deleting data.
2. **Lattice data persistence** — data at lattice paths (including `t/`, `c/`, `n/`, and goal nodes) persists indefinitely. Nothing is wiped at task completion, goal pop, session archive, or agent termination. Past state is preserved as audit and remains queryable via full lattice paths.

Three load lifecycles govern the active context:

| Lifecycle | Source | Leaves active context when… |
|-----------|--------|-----------------------------|
| **Pinned** | Harness | Never (goal tree, system prompt) |
| **Global** | `load()` | Explicit `unload()`. Survives push/pop. |
| **Scoped** | `load(scope:)` | That goal pops |

Tool results (`explore`, `compare`) are not "loaded" — they appear in the goal conversation and leave the active context when the conversation is discarded on pop. The tool invocation itself remains recorded in the persisted conversation on the lattice.

**Scope incentivises decomposition.** Want automatic context shrinkage? Break work into goals — each pop narrows the active window without losing anything from the record.

## Goal-Scoped Conversation

Each goal has its own conversation history. This is the fundamental mechanism that keeps context clean. Goals live inside a task; a single task can host a whole goal tree. Task-level state (the `t/` shorthand and the rest of taskdata) is orthogonal to goal scope.

### Push (start child goal)

When `plan()` sets a child goal to `active`, the harness (between turns):

1. Persists the current goal's conversation (to the goal node on the lattice — it is kept, not thrown away)
2. Activates the child goal
3. Starts a **fresh conversation** for the child — no parent or sibling history visible
4. Scoped loads for the child enter the active context

The agent never pushes directly — it updates the plan, the harness executes the transition.

### Pop (complete or fail child goal)

When `plan()` sets a goal to `complete` or `failed`, the harness (between turns):

1. Child's conversation leaves the active context (remains persisted on the goal node as audit)
2. Scoped loads for that goal leave the active context
3. Cascades: any active/pending descendants are marked `cancelled`; their conversations and scoped loads leave the active context (records preserved)
4. Summary inserted into the parent's conversation: `[{path} completed: "{result}"]` or `[{path} failed: "{error}"]`
5. If more active siblings remain, harness pushes the next one (declaration order)
6. When all active children are done, parent conversation is **resumed**

A popped goal's conversation, tool results, and scoped-load snapshots remain on the lattice for audit. A goal cannot be re-opened as the active target — if the agent wants to resume that line of work, it creates a new child goal, optionally referencing the earlier one.

### What the agent sees

Only the current goal's conversation is in section 5. This means:

```
── Section 5: Goal Conversation (/research/vendor-b) ──

load("w/vendors/b/profile", budget: 500, scope: "/research/vendor-b")
→ loaded
explore("w/vendors/b/products", budget: 800)
→ 8 products, 1 flagship, 15% share
explore("w/vendors/b/financials", budget: 600)
→ revenue 2.1B, growth 22%, margin 24%
"Smaller but fastest growing. Higher margins."
```

Clean. No vendor-a history. No parent setup noise. Just this goal's work.

### What the parent sees after children pop

```
── Section 5: Goal Conversation (/research) ──

"Beginning vendor research."

[vendor-a completed: "23% share, growth slowing"]
[vendor-b completed: "15% share, rapid growth"]
[vendor-c completed: "31% share, declining"]

"All three vendors researched. Moving to comparison."
```

Three one-line results in the live prompt. The detail is still on the lattice at each child goal node — anyone (or the agent on a later turn) can walk back through it.

### Tool results and conversation

Tool results live in the goal conversation. When the goal pops, they leave the *active context* (no longer visible to the LLM) but remain on the lattice with the persisted conversation. Agents that want data to carry *into* subsequent goals or tasks should promote it to `t/` (task scratch, persists for the Covia task), `n/` (agent-private, cross-session), or `w/` (shared) before popping — that's what makes it visible in future active contexts without having to re-explore.

## Goal Tree

### Storage

Goals for the currently focused task are stored flat in a `goals` Index inside that task's taskdata (at `g/{agent}/tasks/{taskId}/goals`), keyed by Blob ID. Each goal is a map with required `status`, plus optional `result`, `conversation` (persisted turns for audit), `parent`, `children`, `name`, and arbitrary metadata. The harness renders the flat index as a tree for the agent using goal paths (e.g. `/research/vendor-a`).

`plan()` is the only way the agent modifies the goal tree. The harness translates path-based plan updates to ID-based index mutations on the focused task's `goals` Index.

### Focus and Activation

The agent activates goals via `plan()`. There is always exactly one active conversation (the focused goal).

- Multiple active siblings are processed in declaration order, one at a time
- Parent conversation stays suspended until all active children are done
- After all active children pop, parent resumes and can activate more or proceed
- The harness never auto-activates pending goals

This allows batch activation ("do A, B, C in parallel") and sequential activation ("do A, then decide") as the agent sees fit.

| State | Rendering |
|-------|-----------|
| Focused (current) | Full — metadata, scoped paths, recent conversation |
| Other active | Two-line summary |
| Pending | One-line |
| Complete/Failed | One-line + result |

### Task scratch (`t/`) is task-level, not goal-level

The `t/` shorthand resolves to the **focused Covia task's** scratch — not the focused goal. All goals within a single task share the same `t/`. This is intentional: `t/` is a place to accumulate findings and in-progress work across the goal tree that should survive individual goal pops but not leak between separate tasks.

- Goal-local working data → keep it in the goal conversation. It persists on the lattice after pop but leaves the active context.
- Cross-goal-but-task-local data → write to `t/`. Survives pops within the task.
- Cross-task-within-agent → write to `n/`.
- Shared across users/agents → write to `w/`.

**Data is never wiped.** Goal conversations, `t/` contents, `c/` contents, and the goal tree itself all remain on the lattice at completion, pop, archive, and termination boundaries. "Promote before pop" is no longer a correctness requirement — it's a *visibility* optimisation: if you want data in the active context of later turns, move it somewhere that's still in scope.

## Context Map

```json5
{
  budget: {
    total: 180000, fixed: 4400, loaded: 1200,
    conversation: 2400, available: 172000,
    tokens: {total: 45000, used: 2000, avail: 43000}
  },
  paths: {
    "g/alice/tasks/{taskId}/goals":
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
  description: "Read lattice data at one or more paths, truncated to byte budget. JSON5 with structural annotations. Result appears in goal conversation.",
  parameters: {
    paths: "string | string[]", budget: "integer (default: 500)",
    compact: "boolean (default: false)", filter: "string — optional",
    hashes: "boolean (default: false)"
  }
}
```

No `retain` — results live in the goal conversation. When the goal pops they leave the active context but remain on the lattice as audit.

### 2. `load` — Add refreshing path to context

```json5
{
  name: "load",
  description: "Add lattice path to context. Refreshed each turn. Global (default) or scoped (leaves active context when that goal pops).",
  parameters: {
    path: "string", budget: "integer (default: 500)",
    scope: "string — optional goal path"
  }
}
```

No `ttl` — entries leave the active context only on pop or explicit unload.

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
  description: "Deep-clone lattice cell. Use n/ for agent workspace, w/ for shared, t/ for current-task scratch, c/ for current-session scratch. Immutable snapshot.",
  parameters: { source: "string", dest: "string" }
}
```

### 5. `write` — Author content to destination

```json5
{
  name: "write",
  description: "Write string content. Use n/ for agent workspace (notes, drafts), w/ for shared data, t/ for current-task scratch, c/ for current-session scratch.",
  parameters: { dest: "string", value: "string" }
}
```

### 6. `compare` — Efficient lattice diff

```json5
{
  name: "compare",
  description: "Compare two lattice paths, return only differences. Hash comparison skips unchanged subtrees. Inline /* was: */ annotations. Result appears in goal conversation.",
  parameters: {
    a: "string", b: "string", budget: "integer (default: 500)"
  }
}
```

### 7. `plan` — Update goal tree (harness, schema-enforced)

```json5
{
  name: "plan",
  description: "Create or update the goal tree for the current task. Without path: full replacement. With path: merge at target. Setting status to 'complete' or 'failed' triggers pop: the goal's conversation and scoped loads leave the active context (record preserved on the lattice), a summary is inserted into the parent conversation, and the pop cascades through active/pending descendants.",
  parameters: {
    path: "string — optional goal path",
    update: "object — merge data. null removes."
  }
}
```

## Agent Loop

```
HARNESS (between turns):
  1. Check goal completions/failures → pop:
       goal conversation leaves active context (persisted on goal node)
       scoped loads leave active context
       cascade: cancel active/pending descendants (records preserved)
       insert summary into parent conversation
  2. If more active siblings remain:
       push next active sibling (declaration order)
       persist parent conversation to its goal node
       start fresh conversation for sibling
  3. If all active children done:
       resume parent conversation
  4. If new child goal activated by agent:
       persist current conversation to its goal node
       push child, start fresh conversation
  5. If root goal completed → validate, task completes (taskdata.result set)
  6. Refresh loaded paths from lattice
  7. Render goal tree (focused goal expanded)
  8. Calculate budget (bytes + tokens)
  9. Safety valve: 70% warn, 90% auto-prune
 10. Assemble prompt (sections 1–7)
 11. Append continuation prompt

AGENT TURN:
  1. Read context map       → budget, loaded paths
  2. Read loaded data       → reference data
  3. Read goal conversation → what have I done in this goal
  4. Read goal tree         → plan, focus
  5. Read current turn      → tool result + continuation
  6. Reason                 → next action
  7. Maybe explore/compare  → results join goal conversation
  8. Maybe load/unload      → adjust working set
  9. Maybe write/copy       → persist to n/, w/, t/, or c/
 10. Maybe plan()           → update goal tree (triggers push/pop)
 11. Act                    → Convex MCP tools
 12. Respond
```

## Full Example: Competitive Analysis Report

### Turn 1 — Root task setup

**Goal conversation: /**
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

→ Three vendor goals batch-activated. Harness pushes `/research/vendor-a` (first in declaration order). Root conversation suspended.

### Turn 2 — Research vendor A

**Goal conversation: /research/vendor-a** (clean — no root history)
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

→ Pop: conversation leaves the active context (still on the goal node). Scoped load leaves the active context. `t/vendor-a-fin` stays — `t/` is task-level and persists through goal pops. Parent gets summary.

→ Harness pushes `/research/vendor-b` (next active sibling). Fresh conversation.

### Turn 3 — Research vendor B

**Goal conversation: /research/vendor-b** (clean — no vendor A history)
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

→ Pop, push vendor-c (last active sibling). Same pattern — child conversations leave active context but remain persisted on their goal nodes.

### Turn 4 — After all research, start comparison

All three vendor goals were batch-activated, so after vendor-c pops, `/research` resumes automatically.

**Goal conversation: /research** (resumed, with child summaries)
```
[vendor-a completed: "23% share, growth slowing"]
[vendor-b completed: "15% share, rapid growth"]
[vendor-c completed: "31% share, declining"]

plan({path: "/research", update: {status: "complete"}})
```

→ Pop `/research`. Root conversation resumed.

**Goal conversation: /** (resumed)
```
"Plan created. Starting vendor research."
[research completed: "3 vendors analysed"]

plan({path: "/compare", update: {status: "active"}})
```

→ Push `/compare`. Fresh conversation.

### Turn 5 — Compare

**Goal conversation: /compare** (clean)
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

→ Pop. Scoped loads leave active context. Push `/draft`.

### Turn 6 — Draft

**Goal conversation: /draft** (clean)
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

**Goal conversation: /review** (clean)
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

→ Root goal complete → task completes, taskdata.result set. All scoped data leaves active context (records preserved). Harness may begin the next task if one is queued.

**Final root conversation (what the harness archives):**
```
"Plan created. Starting vendor research."
[research completed: "3 vendors analysed"]
[compare completed: "B strongest growth, A largest, C declining"]
[draft completed: "draft at n/drafts/competitive-analysis"]
[review completed: "final at n/drafts/competitive-analysis-final"]
"Report complete."
```

Six lines in the active context. The entire multi-turn analysis compressed to an action log; every child conversation and tool result is still retrievable on the lattice via its goal node.

### What This Demonstrates

- **Clean goal conversations** — each vendor research has no sibling noise in the active context
- **Batch activation** — all three vendors activated upfront, processed in order
- **Pop summarises into parent; full detail preserved on the lattice** — parent sees one-line results; audit can walk back any child conversation
- **Promote for visibility** — `write("n/...")` moves findings into a scope visible from later goals and tasks
- **Progressive focus** — never holding all vendors' raw data simultaneously in the active context
- **`t/` scratch** — task-level, survives goal pops within the same task; wiped only by the user, never by the framework
- **Conversation stays SHORT** — current goal only, maybe 5-10 turns
- **Root archives cleanly** — the active view compresses to ~6 lines; the record is lossless

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

→ Pop: scoped loads leave active context. `t/auth-options` remains in taskdata (`t/` is task-level, not goal-level — it persists across goal pops within the same task). The `n/auth-recommendation` note is visible from every subsequent goal in every subsequent task on this agent.

Parent conversation gets: `[api-spec completed: "auth design recommended"]`

Marketing goal auto-unblocked.

## Best Practices Review

| Practice | Status |
|----------|--------|
| Data at top, query at bottom | ✅ |
| Minimal relevant context | ✅ Budget truncation + goal-scoped conversation |
| Observation masking | ✅ Pop removes from active context, parent gets summary only |
| Tiered memory | ✅ Pinned → global → scoped |
| Compaction-resilient | ✅ Sections 1–4, 6 rebuilt by harness |
| Budget awareness | ✅ Bytes + tokens in context map |
| Structured plans | ✅ Uniform goals, focus rendering |
| Goal-scoped active context | ✅ Loads and conversation leave active context on pop (records preserved) |
| Task scratch (`t/`) | ✅ Task-level, survives goal pops within the task, persists for audit |
| Visibility promotion | ✅ Explicit save to `t/`, `n/`, or `w/` keeps data in scope for later goals/tasks |
| Lossless audit | ✅ Goal conversations and tool results preserved on the lattice even after leaving active context |
| Slim active history | ✅ Current goal only + parent summaries |
| Schema efficiency | ✅ 7 tools, ~1,600 B |

## Comparison with Leading Approaches

### Summary

| Dimension | **Letta/MemGPT** | **Claude Code** | **LangChain** | **Covia Lattice** |
|---|---|---|---|---|
| Metaphor | OS (RAM + disk) | IDE session | Middleware pipeline | Goal tree inside task |
| Memory tiers | Core → recall → archival | Context → CLAUDE.md → files | State → store → external | Pinned → global → scoped |
| Active-context cleanup | Agent self-edits | LLM compaction (lossy) | Middleware summarise/mask | **Pop (deterministic cascade)** |
| What gets lost from the record | Agent decides | LLM decides (unpredictable) | Configurable | **Nothing — records preserved on lattice** |
| History scope (active view) | Full session + recall search | Single growing conversation | Growing, summarised | **Per-goal, leaves active context on pop** |
| Goal/plan structure | None built-in | None (CLAUDE.md conventions) | TodoList (flat) | **Hierarchical tree + cascade** |
| Workspace isolation | None | Subagents (separate process) | None | **Task-level `t/` scratch persists; goal-level conversation scoped** |
| Data resolution | None | File truncation heuristics | None | **CellExplorer budget control** |
| Change detection | recall_memory_search | Re-read files | None | **compare() with hash-skip** |
| Tool schema overhead | ~2-5K tokens | ~16.8K tokens (8.4% of context) | Varies | **~400 tokens (0.2%)** |

### Where Covia is ahead

**Deterministic active-context cleanup.** Other systems rely on LLM-generated summaries — inherently lossy and unpredictable. Claude Code users report compaction "forgetting" architectural decisions and access control rules. Our pop removes the goal conversation from the active context, and the full record stays on the lattice. Nothing is lost from the record by surprise — this is RAII for attention, not for storage.

**Goal-scoped conversation.** No other framework scopes history to internal planning steps. Claude Code operates on a single growing conversation that eventually gets compacted. LangChain summarises the full history. Our agent gets a fresh, focused conversation per goal — 5-10 turns, not 200 — with the full ancestral record still on the lattice.

**Progressive data resolution.** CellExplorer is unique. A 50MB lattice structure rendered at 500 bytes with structural annotations. No other framework offers budget-controlled views of arbitrary data.

**Task-level scratch.** `t/` scratch stored inside each task's taskdata, shared across all goals within that task. Persists as audit after the task completes. No fork or overlay mechanics. Claude Code has manual file checkpoints but they're not Covia-task-scoped.

**Structural change detection.** `compare()` skips unchanged subtrees via hash comparison. Orders of magnitude more efficient than re-reading or searching recall memory.

**Schema efficiency.** 7 tools at ~400 tokens vs Claude Code's 16.8K tokens for system tools. 40x more efficient. More context available for actual work.

### Apparent gaps and how Covia covers them

**Self-editing memory / skill learning (Letta).** Letta agents rewrite their own core memory and learn skills from experience. Covia covers this through:
- Agent-private workspace (`n/`) — agent writes learned patterns, preferences, and reusable strategies
- Operations registry (`o/`) — discovered operations cached to `n/refs/` and reloaded for future tasks
- Lattice data is inherently versioned via content-addressing — every state is a snapshot that can be referenced

**Subagent delegation (Claude Code).** Claude Code spawns subagents with isolated context. Covia covers this through:
- **Grid operations** — delegate tasks to remote agents/services on the federated grid
- **Goal tree** provides within-agent active-context isolation (each child goal gets clean active context)
- Multi-agent coordination is a Grid-level concern, not a context management concern

**Git-backed versioning (Letta Context Repositories).** Letta's MemFS projects memory into git-backed files. Covia's lattice is **superior to git** for this purpose:
- Content-addressed Merkle DAG — every value has a unique hash, structural sharing is automatic
- Immutable cells — no merge conflicts, no branch management needed
- `compare()` uses hash trees natively — faster and more precise than git diff
- Task-level scratch (`t/`) provides isolated working space per Covia task, preserved as audit after completion

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

Covia treats the **active context** (what the LLM sees this turn) as a scoped resource tied to the goal tree, and treats the **lattice record** as immutable and complete. Nothing is lost from the record by surprise. The agent decides what to promote *for visibility* — not for preservation. Goal conversation history is scoped-by-design in the active view, while the full transcript remains addressable on the lattice.

Other systems: garbage collection of the record itself (hope the GC picks the right things to keep).
Covia: RAII for attention (records stay; only the active window contracts and expands deterministically).

## Design Decisions

1. **Three load lifecycles.** Pinned (harness-managed, always present), global (agent `load()`, explicit `unload()`), scoped (leaves the active context when that goal pops). No TTL. Pop is the only automatic active-context shrinkage. Tool results are not "loaded" — they live in the goal conversation.
2. **Goal-scoped active conversation.** Each goal has its own conversation. On pop the goal conversation leaves the active context but is preserved on the goal node. Parent receives the `result` field as a one-line summary. Siblings never see each other's conversation in the active context.
3. **Pop = cascade over the active context.** Setting a goal's status to `complete` or `failed` triggers pop: goal conversation and scoped loads leave the active context, cascade cancels active/pending descendants. Records are preserved on the lattice. A goal cannot be re-opened as the active target — create a new child to resume that line of work.
4. **Data persists.** `t/`, `c/`, `n/`, goal conversations, and goal nodes are never wiped by the framework. "Promote" (write to `t/`, `n/`, or `w/`) is a *visibility* choice — it moves data into a scope still visible in the active context of later goals/tasks. All historical state remains queryable via full lattice paths.
5. **Tool results are just conversation.** No special result tracking. `explore()` and `compare()` results live in the goal conversation; on pop they leave the active context with the conversation and remain on the lattice.
6. **Push = fresh active context.** When `plan()` sets a child to `active`, the harness persists the current conversation to its goal node, activates the child, and starts a fresh conversation. The agent never pushes directly.
7. **Parent sees summaries only.** Format: `[{path} completed: "{result}"]` or `[{path} failed: "{error}"]`.
8. **Namespaces.** Three layers: lattice storage (per-user `g`, `w`, `o`, `j`, `s`, `h`, `a`), agent-visible shorthands (`n/`, `t/`, `c/`, `w/`, `o/`), and resolution rules (harness maps prefixes to lattice locations in agent scope, requiring a focused task for `t/` and an active session for `c/`).
9. **Agent record is the unit.** Complete state at `g/{agentId}`: status, config, tasks (Covia jobs Index), sessions, `n` (agent workspace), timeline, ts, error, wake. Messages arrive via `session.pending`. Tasks and sessions are orthogonal.
10. **Task vs goal.** A **task** is a Covia external job (entry in `g/{agent}/tasks`). A **goal** is an internal planning node inside a task's `goals` Index. `t/` is task-level; goals have no shorthand.
11. **Goal tree storage.** Flat Index inside taskdata (`g/{agent}/tasks/{taskId}/goals`), keyed by Blob ID. Each goal is a map with required `status`, optional `result`, `conversation`, `parent`, `children`, `name`, plus arbitrary metadata. Harness renders flat index as a tree; `plan()` uses paths, harness maps to IDs.
12. **Focus and activation.** Agent activates goals via `plan()`. Multiple active siblings are processed in declaration order, one at a time. Parent stays suspended until all active children are done. Harness never auto-activates pending goals.
13. **Goal tree at bottom of context.** Adjacent to current turn for recency attention.
14. **Harness continuation prompt.** Always appended. States current goal, scoped loads, and suggested next step.
15. **Context map is simple.** Paths + budgets + scopes. No result tracking, no TTL countdowns.
16. **Destination shorthand (agent scope only).** `n/` → `g/{agent}/n/`, `t/` → current task's scratch (`g/{agent}/tasks/{taskId}/t/`), `c/` → current session's scratch (`g/{agent}/sessions/{sid}/c/`), `w/` → `<DID>/w/`, `o/` → `<DID>/o/`. Full paths work everywhere.
17. **Single root goal in active context.** Harness manages a queue of pending tasks. Each new Covia task starts with a fresh root goal.
18. **Safety valve.** 70%: harness warns in continuation prompt. 90%: auto-prune — unload global loads (most recently loaded first), then truncate oldest conversation turns from the active view. Never touches pinned or scoped loads, and never alters the lattice record.
19. **Scope incentivises decomposition.** Want automatic active-context shrinkage? Break work into goals.
20. **7 tools, ~1,600 B.** Under 0.5% of context window.
21. **Crash recovery.** Harness reconstructs goal tree from taskdata on the lattice. All conversations, scratch, and scoped-load paths are recoverable. `n/` (agent), `t/` (task), and `c/` (session) are intact.
