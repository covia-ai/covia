# Agent Context — Implementation Plan

**Status:** Plan, April 2026
**Target designs:** [LATTICE_CONTEXT.md](./LATTICE_CONTEXT.md), [GOAL_TREE.md](./GOAL_TREE.md)
**Older draft:** [CONTEXT.md](./CONTEXT.md) — partially superseded

Migration tracker for closing the gap between the canonical designs
and the current code. Delete this doc once the gaps in §3 are closed
or consciously deferred.

---

## 1. Current state

Two LLM agent transition adapters, both reusing `ContextBuilder`:

| Adapter | Catalog path | Per-transition behaviour | Cross-transition state |
|---------|-------------|--------------------------|------------------------|
| `GoalTreeAdapter` | `v/ops/goaltree/chat` | Frame stack with subgoal/complete/fail/compact tools | **None** — persists `Maps.empty()` |
| `LLMAgentAdapter` | `v/ops/llmagent/chat` | Single growing conversation | **Bloated** — persists everything |

`GoalTreeAdapter` is the target architecture per LATTICE_CONTEXT and
GOAL_TREE. It's mostly correct because it's stateless across
transitions — fresh root frame every time, in-memory frame stack, only
the final response is returned. The cost: agents can't carry context
between requests.

`LLMAgentAdapter` is the legacy "growing history" model. The smoking
gun, from `_proof_test` after two `agent_request` turns:

```
history: [
  {role: "system",    content: "<custom prompt + LATTICE_REFERENCE>"},
  {role: "system",    content: "[Context Map] budget: 2001/180000 ..."},  ← turn 1
  {role: "assistant", content: "{json response 1}"},
  {role: "system",    content: "[Context Map] budget: 2223/180000 ..."},  ← turn 2
  {role: "assistant", content: "{json response 2}"}
]
```

Two `[Context Map]` system messages — one per turn. With Bob (whose
context loads "AP Policy Rules" via CellExplorer at ~2KB rendered),
20 turns would put ~40KB of duplicated reference data into
`state.history`. The AP demo doesn't hit this because AP agents use
`goaltree:chat`.

**The two adapters call `ContextBuilder` very differently.** GoalTree
passes `Vectors.empty()` to `withSystemPrompt` and persists nothing;
LLMAgent passes the prior history and persists everything that
`ContextBuilder` accumulated. That single difference is the root of
all the bugs in §2.

---

## 2. LLMAgentAdapter bugs

1. **Ephemeral context baked into durable history.** Context entries,
   loaded paths, [Context Map], pending results, and inbox messages
   all `messages.conj(...)` into the same vector that becomes
   `state.history`. After N turns, N copies of each.

2. **System prompt frozen at first turn.** `withSystemPrompt` only
   creates a new system message if existing history doesn't already
   start with one. Updates to `LATTICE_REFERENCE` or to the agent's
   custom `systemPrompt` never reach existing agents.

3. **No transcript compaction.** History grows monotonically. The
   safety valve at 70%/90% prunes loaded paths but never history.

4. **User input persistence is ad-hoc.** The task input flow puts
   the new task into the LLM's context but persists it inconsistently.

5. **Tools rebuilt every turn, uncached.** ~10ms wasted per turn.

---

## 3. Mapping to LATTICE_CONTEXT / GOAL_TREE

| Principle | GoalTree | LLMAgent |
|-----------|----------|----------|
| Fresh system prompt every turn | ✅ | ❌ |
| Context entries NOT persisted as bloat | ✅ stateless | ❌ persisted |
| Task-scoped conversation | ✅ frame stack | ❌ flat |
| Push/pop with cascade | ✅ subgoal/complete/fail | N/A — simpler model |
| Three load lifecycles (pinned/global/scoped) | ⚠️ partial | ⚠️ partial |
| Promote-before-pop | ⚠️ implicit | N/A |
| Frame stack persisted across transitions (GOAL_TREE §State Model) | ❌ persists empty | N/A |
| Compacted segments accumulate (GOAL_TREE §Compaction) | ❌ no persistence | ❌ |
| `compare()` for diff-based reading | ❌ | ❌ |
| Tool schema overhead minimised (~7 tools, ~1.6KB) | ⚠️ uses 18 generic tools | ⚠️ same |

Note: GOAL_TREE §Implementation Notes already walked back from the
7-tool LATTICE_CONTEXT vision and recommends reusing `covia_*` /
`agent_*` tools. So the toolset choice is already a deferred design.

---

## 4. Decisions

### Decision 1 — what to do about LLMAgentAdapter

| Option | Change | Pros | Cons |
|--------|--------|------|------|
| **A. Retire** | Make `goaltree:chat` the only Level 2 adapter; migrate or drop existing `llmagent:chat` agents | One adapter to maintain; bugs simply go away; aligns all docs | Goal-tree mental model forced on simple agents; AGENT_LOOP.md three-level story changes; existing agents need migration |
| **B. Stateless** | Mirror GoalTreeAdapter: `withSystemPrompt(Vectors.empty())` + `newState = Maps.empty()` | Smallest possible diff; bugs fixed; both adapters in parity | Loses cross-turn memory entirely — same caveat that affects GoalTree today |
| **C. Transcript model** | Split state: `state.transcript` = real conversation turns only; ephemeral context (system, entries, loads, [Context Map], pending, inbox) rebuilt fresh per turn and never persisted | Multi-turn memory works correctly; system prompt updates apply immediately; transcript stays compact; foundation for compaction; fixes all bugs in §2 | Larger refactor (~200–400 lines + tests); existing agents have polluted `history` field needing one-shot cleanup |

**Recommendation: Option C.** Worth the refactor. It gives correct
multi-turn behaviour without forcing every agent into the goal-tree
mental model, reuses `ContextBuilder` almost as-is, and the change is
contained to the persistence step plus a `ContextBuilder.build()` split
that separates "what to send to the LLM" from "what to persist".

### Decision 2 — should GoalTreeAdapter persist its frame stack

GOAL_TREE.md §State Model says yes. Current code says no.

**Recommendation: defer.** Keep GoalTreeAdapter stateless for now.
Cross-transition memory in goaltree is a real feature but it's not
blocking, and the AP demo + request/response agents are better with
per-request isolation. Revisit when there's a concrete use case
(e.g. a Dave-style conversational manager that needs to remember
prior invoices).

---

## 5. Other gaps (not blocking)

| # | Gap | Notes |
|---|-----|-------|
| 5.1 | Tool list cache | Cache `(default tools enabled, config.tools)` → tool defs. ~10ms saved per turn. |
| 5.2 | Context entry cache with versioning | LATTICE_CONTEXT's pinned/global/scoped distinction lets the harness cache pinned entries by content hash and only re-resolve on change. |
| 5.3 | LATTICE_CONTEXT 7-tool vs current 18-tool default | GOAL_TREE already deferred this. Possible compromise: curated subset (~8 tools) by default, opt-in to full catalog. |
| 5.4 | Compaction strategy | After Decision 1 → Option C lands, transcript needs a compaction story. GOAL_TREE's `compact` tool with LLM-written summary is the recommended approach. |
| 5.5 | Verify GoalTreeAdapter ancestor rendering | GOAL_TREE §Context Assembly Layout describes parent/grandparent at decreasing budgets. Confirm `GoalTreeContext` matches and budgets are sane. |

---

## 6. Next steps

1. **Get user buy-in on Decision 1 and Decision 2.** This is a
   roadmap call.

2. **Implement the chosen option.** For Option C, the work is:
   - Split `ContextBuilder.build()` into LLM-message and
     transcript-delta outputs
   - Update `LLMAgentAdapter.processChat` to persist only the
     transcript delta
   - Add tests for: system-prompt updates apply immediately, no
     duplication after N turns, multi-turn continuity
   - One-shot cleanup of polluted `history` field on existing agents

3. **Update `AGENT_LOOP.md` §3.2** to clarify which adapter is
   recommended for what.

4. **File §5 items as separate tickets.**

---

## 7. Closure criteria

Delete this doc once:

- Decisions 1 and 2 are made and implemented
- The bugs in §2 are fixed (or no longer apply because the state
  model changed)
- `AGENT_LOOP.md` reflects the canonical adapter recommendation
- §5 items are filed as separate tickets

LATTICE_CONTEXT.md and GOAL_TREE.md remain canonical. This plan is
just the migration tracker.
