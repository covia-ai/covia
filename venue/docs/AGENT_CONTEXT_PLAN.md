# Agent Context — Implementation Plan

**Status:** Closure criteria met (April 2026). Only §5.2 (context entry cache) remains open. Safe to delete this tracker once §5.2 is decided one way or the other.
**Target designs:** [LATTICE_CONTEXT.md](./LATTICE_CONTEXT.md), [GOAL_TREE.md](./GOAL_TREE.md)
**Older draft:** [CONTEXT.md](./CONTEXT.md) — partially superseded

Migration tracker for closing the gap between the canonical designs
and the current code. Delete this doc once the gaps in §3 are closed
or consciously deferred.

**Done:** §4 Decision 1 → Option C (transcript model), implemented in
commit `43d1dd8`. Bugs §2.1 (ephemeral context bloat) and §2.2 (frozen
system prompt) are fixed. See §8 for the migration outcome.

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

1. ✅ **FIXED** (commit `43d1dd8`). **Ephemeral context baked into
   durable history.** Context entries, loaded paths, [Context Map],
   pending results, and inbox messages all `messages.conj(...)` into
   the same vector that becomes `state.history`. After N turns, N
   copies of each.

2. ✅ **FIXED** (commit `43d1dd8`). **System prompt frozen at first
   turn.** `withSystemPrompt` only creates a new system message if
   existing history doesn't already start with one. Updates to
   `LATTICE_REFERENCE` or to the agent's custom `systemPrompt` never
   reach existing agents.

3. **No transcript compaction.** History grows monotonically. The
   safety valve at 70%/90% prunes loaded paths but never history.
   Still open — see §5.4.

4. ✅ **FIXED** (commit `43d1dd8`). **User input persistence is
   ad-hoc.** The task input flow puts the new task into the LLM's
   context but persists it inconsistently. The new transcript model
   synthesises a user message for each task input via
   `wrapTaskAsUserMessage`.

5. **Tools rebuilt every turn, uncached.** ~10ms wasted per turn.
   Still open — see §5.1.

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

### Decision 1 — what to do about LLMAgentAdapter ✅ DECIDED & IMPLEMENTED

Resolved as **Option C — Transcript model**, implemented in commit
`43d1dd8`. The bugs in §2 are fixed and verified live; see §8.

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
| 5.5 | ✅ Verified — `GoalTreeContext` matches GOAL_TREE.md spec exactly: `PARENT_BUDGET = 300`, `ANCESTOR_DECAY = 0.5` (300 → 150 → 75 → … → `MIN_ANCESTOR_BUDGET = 50`), outermost-to-innermost render order. Existing `GoalTreeContextTest` covers all four budget levels and the render order. No code changes needed. |

---

## 6. Remaining work

| # | What | Status |
|---|------|--------|
| Option C | Transcript model in LLMAgentAdapter | ✅ commit `43d1dd8` |
| §5.1 | Tool list cache | ✅ Done — per-Engine cache of resolved default tools, ~10ms saved per turn |
| §5.2 | Context entry cache with versioning | Open — compounding leverage |
| §5.3 | Curated default tool subset | ✅ Done — opt-in tools model: `defaultTools: false` + explicit `tools` list. All 7 harness tools also opt-in via name. See AGENT_LOOP.md §3.6 |
| §5.4 | Compaction strategy for transcript | ✅ Done — `compact` harness tool + auto-compact nudge when conversation exceeds 20 turns and agent has compact in its tool set |
| §5.5 | Verify GoalTreeAdapter ancestor rendering | ✅ Verified |
| §5.6 | Typed agent outputs | ✅ commit `a02a5bb` — see §9 |
| §5.7 | Strict mode enabled | ✅ commit `9016c0f` |
| §5.8 | Caps in system prompt + denial messages | ✅ commit `f1f8bbf` |
| §5.9 | Failed-frame conversation persistence | ✅ commit `f1f8bbf` |
| §5.10 | [Context Map] removed from goaltree | ✅ (inaccurate budget, actively misleading) |
| §5.11 | Session context (date, venue, model) | ✅ added to system prompt |
| §5.12 | `agent:context` operation | ✅ renders full LLM context for live inspection |
| `AGENT_LOOP.md` §3.2 | Document the transcript model | ✅ Done |
| Decision 2 | Whether to persist GoalTreeAdapter frame stack | Deferred until concrete use case |

---

## 7. Closure criteria

Delete this doc once:

- §5.1 tool list cache is implemented (small win) — done in same
  session as Option C
- §5.5 ancestor rendering audit is complete (small audit) — done
  in same session
- §5.4 compaction strategy is at least filed as a separate ticket
- `AGENT_LOOP.md` §3.2 is updated to describe the transcript model

§5.2 (context entry cache) and §5.3 (curated toolset) can be filed
as separate tickets and are not closure-blocking.

LATTICE_CONTEXT.md and GOAL_TREE.md remain canonical. This plan is
just the migration tracker.

---

## 8. Outcome of Option C (commit 43d1dd8)

### Mechanism
- `ContextBuilder.withSystemPrompt` always rebuilds the system message
  fresh from current config + LATTICE_REFERENCE; if a starting vector
  has a stale system message at index 0, it is dropped.
- New `ContextBuilder.withTranscript(state)` reads `state.transcript`
  and appends those messages.
- `LLMAgentAdapter.processChat` builds the per-turn LLM context as:
  ```
  fresh system → context entries → loaded paths → [Context Map]
    → transcript → pending → inbox → empty signal → tools
  ```
- After the tool loop, the transcript delta is `synthesised user
  message per task input` + `wrapped inbox messages` + `new
  assistant/tool messages`. This is the only thing persisted.
- `state.history` field is no longer written. New persistence shape:
  `{transcript, config, loads}`.

### Smoking-gun verification
Before fix (`_proof_test`, two turns, original `state.history`):
```
[system, [Context Map], assistant, [Context Map], assistant]
```
Two `[Context Map]` system messages — one per turn. With Bob and
~2KB rendered policy rules, 20 turns would have stuffed ~40KB of
duplicated reference data into history.

After fix (same kind of agent, freshly created, two turns,
`state.transcript`):
```
[user("{task:Hello}"), assistant(toolCall), tool, assistant("OK"),
 user("{task:World}"), assistant(toolCall), tool, assistant("OK")]
```
8 messages. No `[Context Map]`, no frozen system message, no
duplicated reference data. Multi-turn continuity preserved.

### Migration
Existing agents with `state.history` are not migrated. The new code
ignores `K_HISTORY` and starts with an empty transcript on first
turn after upgrade. Effectively their conversation history resets.
AP demo agents are unaffected because they use `goaltree:chat`,
which already discarded state every transition.

### Test coverage
- `testTranscriptDoesNotAccumulateEphemeralContext` — three turns,
  asserts exactly 6 messages (3 user + 3 assistant), no system, no
  `[Context Map]`
- `testSystemPromptUpdatesAcrossTurnsAreNotFrozen` — verifies
  persisted state contains no frozen system message
- `testExistingSystemPromptIsAlwaysReplaced` (was
  `testExistingSystemPromptPreserved`) — flips the assertion to
  match the new behaviour
- All existing tests updated from `extractHistory` →
  `extractTranscript` with new expected counts

939 venue tests stable green over 3 runs.

---

## 9. Outcome of typed agent outputs (commit a02a5bb)

### Problem
Two parallel mechanisms described the agent's response shape:
- `responseFormat` — applied via OpenAI's `response_format` API to the
  assistant text content, enforced by strict mode server-side.
- `complete(result: any)` harness tool — unconstrained tool argument,
  NOT enforced by strict mode.

The `complete` tool's description actively pushed the LLM toward
the unenforced path for structured outputs. So schema enforcement
was nominal — bypassed whenever the LLM used `complete()`.

### Mechanism
Agents now declare typed `outputs`:
```json
"outputs": {
  "complete": { "schema": { "type": "object", "properties": {...} } },
  "fail":     { "schema": { "type": "object", "properties": {...} } }
}
```

When set:
- `GoalTreeAdapter` synthesises `complete` and `fail` tools with the
  user's schema as their parameters (wrapped in strict-compatible
  parameter objects).
- OpenAI `strictTools=true` enforces the schema at the API level on
  the tool call argument — closing the bypass.
- Text-only assistant responses are rejected at the root frame — the
  LLM must call `complete()` or `fail()` to deliver a result.
- `responseFormat` is dropped from the L3 invocation when outputs is
  set (no double mechanism).
- Migration shim: agents with only `responseFormat` (schema map) get
  auto-converted to `outputs.complete.schema`.
- Default fail schema: `{reason: string, details: string}`.
- Subgoal/child frames keep the legacy untyped harness.

### Verification
4/4 AP demo scenarios produce fully schema-conforming structured
outputs via the typed `complete()` tool. Process logs confirm the
`result` arg arrives as a structured map (not stringified JSON),
proving `strictTools` is enforcing the schema at the API level.

### Related fixes in the same session
- `b4e9839` — `state.config` preserved across transitions (was being
  wiped to `Maps.empty()` every transition, silently losing caps and
  schema enforcement after the first invocation)
- `9016c0f` — OpenAI `strictJsonSchema` and `strictTools` enabled
  (langchain4j defaults both to `false`)
- `f1f8bbf` — caps in system prompt, actionable denial messages,
  failed-frame conversation persisted to `state.lastFailure`
- `d57a96c` — Bob/Carol prompts tightened with step-numbered workflows;
  upgraded to `gpt-4.1-mini` for better tool-loop behaviour
- `[Context Map]` removed from goaltree (inaccurate budget estimate)
- Session context (date, venue name, model) added to system prompt
- `agent:context` operation for live context inspection
