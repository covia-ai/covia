# Covia Goal Tree

**Version:** 2.1 Draft (session-unified)
**Purpose:** Hierarchical goal decomposition, execution, and history for lattice-aware agents. Agents pursue goals; sub-goals open scoped frames with their own conversation; results return up the tree. The goal tree IS the session's conversation record — no separate history structure.

## Core Idea

A session's root frame is its conversation. `subgoal` is a tool call that brackets a section of work inside a child frame. `compact` brackets a run of live turns as a segment — this is how per-question thinking is scoped in chat sessions. `complete` returns a structured result to the parent (one-shot invocations) or is never called (chat sessions run indefinitely). Everything is lattice data on the session record, rendered by CellExplorer at any budget.

## Relationship to Existing Architecture

The goal tree builds on the existing three-level agent architecture:

```
Level 1: agent:run / agent:trigger   (framework — AgentAdapter)
Level 2: GoalTreeAdapter             (domain — goal frames, tool loop, context assembly)
Level 3: langchain:openai etc.       (LLM call — via grid operation)
```

**GoalTreeAdapter** (`covia.adapter.agent.GoalTreeAdapter`) is a more powerful variant of `LLMAgentAdapter`. It replaces the flat conversation model with a frame stack, adds 4 harness tools (subgoal, complete, fail, compact), and manages scoped context. The existing `LLMAgentAdapter` remains available for simpler agents that don't need goal decomposition.

Both adapters:
- Use the same Level 3 message format (`{messages, tools?, responseFormat?}`)
- Dispatch tool calls through the same capability-checking pipeline
- Persist state via `AgentState` on the lattice
- Are invoked by Level 1 (`agent:run` / `agent:trigger`)

The goal tree adapter registers as operation `goaltree:chat`, used in agent config:
```json
{"operation": "goaltree:chat"}
```

## Sessions and Frames

**A session IS a root frame.** The session record at `sessions/<sid>` carries a `frames` vector whose first element is the root frame. `frames[0].conversation` is the agent's complete execution and conversation record for that session.

Everything goes here — nothing lives alongside. Live turns from chat/message intake, assistant responses, tool calls, tool results, compacted segments, and subgoal branches are all entries in the same conversation vector. Child frames pushed by `subgoal` live at `frames[1..N]`. The frame stack is lattice data; transitions read it, append to it, and write it back atomically.

### Lifecycle

| Event | Effect |
|-------|--------|
| Session created | Root frame created, description = session origin (first message, task request, operation invocation) |
| Chat / message intake | Envelope appended to `sessions/<sid>/pending` |
| Transition picked | Envelopes drained from pending, appended to root frame conversation as `user` turns |
| Transition runs | Assistant turns, tool calls, tool results, subgoal branches appended atomically in `mergeRunResult` |
| Agent calls `compact` | Run of live turns collapses into a segment with the agent's summary |
| Agent calls `subgoal` | Child frame pushed onto `sessions/<sid>/frames`; child conversation builds up; pop on `complete` |
| Session closed | Frames persist on the lattice — no destructive cleanup |

Chat sessions typically never `complete` the root frame — they run indefinitely, appending turns. One-shot invocations (a single grid operation call on a fresh session) do complete the root frame, and the session's role is the same as a single-turn task.

### Per-question bracketing

"Thinking for a specific question" is bracketed by `compact`. The agent calls `compact(summary)` at the end of its work on a question, rolling that run of live turns into a segment whose summary surfaces in future-turn context. No extra frame needed — the compact segment IS the bracket.

Subgoals remain available for intra-question decomposition (e.g. "analyse each vendor") and nest inside the session's root frame. A complex question might do: user turn → tool calls → subgoal(vendor-a) → subgoal(vendor-b) → subgoal(vendor-c) → assistant synthesis → compact.

### LLMAgentAdapter is the degenerate case

`llmagent:chat` agents use the same structure: root frame only, no harness tools enabled (no `subgoal`, no `compact`, no `complete`). Their conversation is a flat list of live turns. A single reader ingests both `llmagent` and `goaltree` sessions — no divergent schema.

## How LLMs Work With Tools

An LLM generates text plus optional tool-call blocks. The harness executes the tools and feeds results back. Inside a goal frame, text-only responses (no tool calls) are treated as an implicit `complete` — the text becomes the result returned to the parent. This is a natural extension of the standard API contract:

```
[SYSTEM]
You are a research analyst agent...

[TOOLS]
covia_inspect, context_load, context_unload, covia_write, subgoal, complete, fail, compact, ...

[USER]
Analyse the competitive landscape for vendors A, B, C.

[ASSISTANT]
I'll explore the available data first.
<tool_call name="covia_inspect">{"path": "w/vendors", "budget": 300}</tool_call>

[TOOL_RESULT]
{a: {name: "Acme Corp"}, b: {name: "Beta Inc"}, c: {name: "Gamma Ltd"},
 /* +9 more */} /* Map, 12 entries, 45KB */

[ASSISTANT]
Found all three. I'll research vendor A first.
<tool_call name="subgoal">{"description": "Analyse Acme Corp..."}</tool_call>

[TOOL_RESULT]
{"status": "complete", "result": {"share": 0.23, "growth": 0.08}}
```

From the parent's perspective, `subgoal` is just a tool call that branches to the child and returns structured data. The child's 15 turns of work are invisible.

**Tool name convention:** Operations use colons internally (`covia:inspect`) but LLMs see underscores (`covia_inspect`) because MCP and most tool-calling APIs don't permit colons in tool names. The harness handles this mapping automatically via `ContextBuilder.deriveToolName()`.

## Conversation Structure

A frame's conversation is a list. Each entry is either a **live turn** or a **compacted segment**:

```
[
  {summary: "...", turns: 200, items: [...]},   // segment 0
  {summary: "...", turns: 200, items: [...]},   // segment 1
  {role: "user", content: "...",                 // live turn from intake
   ts: 1713600000000, source: "chat"},
  {role: "assistant", content: "..."},           // live turn
  {role: "assistant", content: "...",            // live turn with tool calls
   toolCalls: [{id: "tc_1", name: "covia_inspect", arguments: {...}}]},
  {role: "tool", id: "tc_1", name: "covia_inspect", content: "..."},
]
```

**Turn envelope fields:** `role` and `content` are the LLM-conversation primitives. Optional `ts` (wall-clock millis) and `source` (`"chat" | "message" | "request" | "transition"`) carry provenance — they let audit and replay distinguish a chat-intake user turn from a task-request user turn without inspecting role alone. Vendor LLM APIs ignore `ts`/`source`; the adapter strips them when constructing the level 3 payload. Assistant turns with `toolCalls` and subsequent `tool` turns preserve intra-transition interleaving on the lattice.

Compacted segments contain the full conversation data in `items`. The `summary` is an LLM-provided description of that phase of work. CellExplorer renders the whole list at whatever budget the context allows — segments are truncated to summaries at low budget, expanded to full turns at high budget.

**There is no separate archive.** The segments hold the actual data. CellExplorer controls how much is visible in the active context window. At full budget, every turn is visible. At low budget, only summaries show.

```
Budget 50:
  [{/* Seg, 200 turns */}, {/* Seg, 200 turns */}], turn 401, turn 402

Budget 200:
  [{summary: "Setup and vendor research...", turns: 200, items: [/* Vec, 200 */]},
   {summary: "Cross-reference and draft...", turns: 200, items: [/* Vec, 200 */]}],
  turn 401, turn 402

Budget 2000+:
  [{summary: "...", turns: 200, items: [
     {role: "assistant", content: "Let me check products."},
     {role: "assistant", content: "...", toolCalls: [{id: "tc_1", name: "covia_inspect", ...}]},
     {role: "tool", id: "tc_1", name: "covia_inspect", content: "{share: 0.23, ...}"},
     /* +197 more */]},
   ...],
  turn 401, turn 402
```

## The Frame Stack

Frames live on the session record (`sessions/<sid>/frames`). The LLM doesn't see the stack — it just calls tools. The stack is how the harness assembles context for each inference and is persisted across transitions as part of session state.

### Frame Contents

| Field | Description |
|-------|-------------|
| description | Root: session origin. Child: the `subgoal` description that created this frame. |
| conversation | List of segments + live turns (each turn: `{role, content, ts?, source?, toolCalls?}`) |
| loads | Lattice paths loaded at this scope |

### Context Assembly

For each inference, the harness assembles context from the stack. The key rule: **current frame's conversation is full detail, ancestor frames are progressively summarised.**

```
Stack:
  Frame 0 (root):     conversation rendered at budget ~100
  Frame 1 (research): conversation rendered at budget ~300
  Frame 2 (vendor-b): conversation at full detail        <- active
```

The LLM sees:

```
[SYSTEM]
(system prompt, tool schemas, context map)

[ANCESTOR CONTEXT]
[{description: "Competitive analysis for A, B, C",
  conversation: ["Explored vendors. Methodology loaded.",
    subgoal("Research vendors") -> pending...]},
 {description: "Research vendors sequentially",
  conversation: [
    subgoal("vendor-a") -> {share: 0.23, growth: 0.08},
    subgoal("vendor-b") -> pending...]}]

[LOADED DATA]
w/notes/methodology (200B, inherited from root):
"Compare revenue growth, margins, share. Flag risks."

w/vendors/b/profile (500B, own scope):
{name: "Beta Inc", founded: 2018, employees: 1200, ...}

[CONVERSATION -- full detail]
(live turns from current frame)

[GOAL]
Analyse Beta Inc: products, financials, market position.
```

Ancestor budget is configurable. Rule of thumb: parent ~300B, grandparent ~150B, great-grandparent ~80B. CellExplorer renders each ancestor's conversation at its budget — segments show as summaries, live turns may be truncated.

## Harness Tools (7, opt-in)

GoalTreeAdapter provides 7 built-in tools. **All are opt-in** — agents declare which ones they need in `state.config.tools` alongside operation paths. Zero harness tools by default — a bare chatbot just responds with text.

```json5
"tools": ["subgoal", "compact", "more_tools", "v/ops/covia/read"]
```

The registry:

| Name | Purpose |
|------|---------|
| `subgoal` | Delegate work to an isolated child frame |
| `complete` | Return structured result (auto-injected with typed outputs) |
| `fail` | Report failure with structured error (auto-injected with typed outputs) |
| `compact` | Archive conversation to a summary, freeing context space |
| `context_load` | Pin workspace data in context across turns |
| `context_unload` | Remove pinned data |
| `more_tools` | Add operations to the tool set at runtime |

### Typed outputs auto-inject complete/fail

When `state.config.outputs.complete.schema` is declared, the harness auto-injects typed `complete` and `fail` tools with schema-enforced parameters — no need to list them in `config.tools`. The LLM's tool call arguments must match the declared schema (enforced by OpenAI `strictTools`). Text-only responses are rejected.

```json5
"outputs": {
  "complete": { "schema": { "type": "object", "properties": {...}, "required": [...], "additionalProperties": false } }
}
```

### `subgoal` — Open a subgoal

```json5
{
  name: "subgoal",
  description: "Delegate a self-contained piece of work to a child frame. The child runs independently with its own conversation and tool access. Returns the child's result as a tool result.",
  parameters: { description: { type: "string" } },
  returns: "{status: 'complete', result: any} or {status: 'failed', error: any}"
}
```

When the parent calls `subgoal`:
1. Harness pushes a new frame onto the stack
2. Description becomes the child's goal message
3. Assembles child context (summarised ancestry + inherited loads)
4. Runs child's tool call loop (same loop structure as parent)
5. Child calls `complete` or `fail`, or produces text-only response (implicit complete)
6. Harness pops the frame, restores parent context
7. Result delivered to parent as a tool result

No concurrency. The stack exists for the same reason a CPU has a call stack — to know where to return to and what context to restore.

### `complete` and `fail` — Return result to parent

**Flattened parameters:** the entire tool input IS the result/error. No `result`/`error` wrapper.

```json5
// Untyped: parameters are open object — pass any fields
complete({any: "data", you: "want"})
fail({reason: "API key invalid", details: "401 from openai.com"})

// Typed: parameters ARE the user's schema (strictTools enforced)
complete({invoice_number: "INV-001", decision: "APPROVED", ...})
```

Protocol limitation: LLM tool call arguments must be JSON objects (OpenAI, Anthropic, all major providers). Agents cannot return arrays or primitives directly — wrap them: `complete({items: [...]})`.

### `compact` — Checkpoint conversation

```json5
{
  name: "compact",
  description: "Archive your conversation so far into a summary you write. Frees context space.",
  parameters: { summary: { type: "string" } }
}
```

When the agent calls `compact`:
1. Live turns archived into a new segment with the LLM's summary
2. Segment appended to the conversation's segment list
3. Live conversation starts fresh after the segments
4. Agent continues with more context space

The `summary` parameter is required — only the LLM knows what matters in the work done so far. The harness prompts when compaction is needed but the agent writes the summary:

**Auto-compact nudge (current):** When live turn count exceeds `AUTO_COMPACT_THRESHOLD` (20) and the agent has `compact` in its tool set, the harness injects a system message: "Your conversation has N turns. Call compact(summary) now to free context space before continuing." The LLM decides whether to compact.

**Future (planned):** byte-budget-based thresholds (70% / 90%) with hard truncation of oldest turns at 90% if the LLM ignores the nudge.

## Compacted Segment Structure

```json5
{
  summary: "Explored vendor A products (23% share, 14 products). Explored financials (4.2B revenue, 8% growth). Identified margin pressure. Wrote notes to w/notes/vendor-a.",
  turns: 45,
  items: [
    {role: "assistant", content: "Let me check products."},
    {role: "assistant", content: "...", toolCalls: [{id: "tc_1", name: "covia_inspect", arguments: {path: "w/vendors/a/products", budget: 800}}]},
    {role: "tool", id: "tc_1", name: "covia_inspect", content: "{flagship: [\"Atlas\", \"Beacon\", \"Core\"], total: 14, share: 0.23}"},
    {role: "assistant", content: "Strong portfolio, 23% share. Now financials."},
    // ... all 45 turns
  ]
}
```

`summary` is LLM-provided (via `compact` call). `turns` and `items` are harness-provided. CellExplorer uses `summary` for low-budget rendering and `items` for high-budget rendering. Everything is always there — resolution is the only variable.

## Scoped Loads

Loads are lexically scoped to frames. Children inherit parent loads.

The existing `context_load` / `context_unload` tools from LLMAgentAdapter are reused. The difference is that GoalTreeAdapter tracks loads per frame and manages inheritance on push/pop.

### Inheritance

```
// Root loads methodology
context_load({path: "w/notes/methodology", budget: 200})

// Child frame inherits it -- no action needed
// Visible in child's loaded data section
```

### Shadowing

```
// Parent has overview
context_load({path: "w/market-data", budget: 200})

// Child needs detail
context_load({path: "w/market-data", budget: 2000})
// -> child sees 2000B version, parent's 200B is hidden

// Child completes -> parent's 200B restored
```

### On Pop

Child's own scoped loads freed. Shadowed parent loads restored. Inherited loads continue.

### Implementation

Each frame has its own `loads` map. Effective loads at any depth = merge of ancestor loads with child loads taking precedence (later frames shadow earlier ones). On pop, the child's loads map is discarded.

## Storage Tiers

Four virtual namespaces, resolved by `NamespaceResolver` implementations registered on `CoviaAdapter`. Each has a distinct lifetime and scope, forming a hierarchy from permanent to ephemeral.

| Storage | Lifetime | Scope | Backed by | Purpose |
|---------|----------|-------|-----------|---------|
| `w/` | Permanent | User | User lattice `w/` | Curated outputs: notes, drafts, refs. Survives everything. |
| `n/` | Permanent | Agent | `g/<agentId>/n/` | Agent workspace — persistent per-agent scratch and notes. |
| `c/` | Session | Session | `g/<agentId>/sessions/<sid>/c/` | Session-scoped scratch — survives across turns within the same session. |
| `t/` | Job | Transition | `j/<jobId>/temp/` | Per-transition temp — shared across subgoals and tool calls within a single Job. Gone at transition end. |

All existing `covia:*` operations work with each prefix transparently — agents write `c/draft` or `t/scratch` without knowing the full lattice path. The resolver rewrites the keys based on the `RequestContext` (`agentId`, `sessionId`, `jobId`).

### `c/` — Session-Scoped Scratch

`c/` is the natural home for cross-turn memory within a single session. It persists across transitions (unlike `t/`) but is scoped to one session (unlike `n/` or `w/`). Requires both `agentId` and `sessionId` on the `RequestContext`; errors otherwise. Typical uses: running summaries the agent builds up, draft artefacts refined turn-by-turn, state tracking specific to this conversation.

### `t/` — Job-Scoped Temp

`t/` resolves to the `temp` field within the current job record. The `jobId` comes from the `RequestContext`, so each Job gets isolated temp. Sub-operations that execute within a parent Job's context inherit the `jobId` and therefore share `t/`:

| Scenario | `t/` scope | Why |
|---|---|---|
| **Goal tree subgoals** | Shared with root goal | Subgoals run within the same tool call loop, same Job |
| **Goal tree tool calls** (`covia:write`, etc.) | Shared with root goal | Tool dispatches inherit the goal's `jobId` |
| **Orchestration steps** | Shared with orchestration job | Steps execute within the orchestrator's context |
| **Direct invoke** (`POST /invoke`, MCP) | Own Job | New top-level Job, fresh `jobId` |
| **`agent:request`** → separate agent | Own Job | New agent trigger, new Job |
| **`grid:run`** to remote venue | Own Job | New Job on target venue |

**Chat-session implication:** each chat message triggers a fresh transition = fresh Job, so `t/` **resets between chat turns**. Subgoals within a single transition share `t/`; across transitions, use `c/` instead.

### Choosing the right tier

- **`t/`** — intra-transition scratch: intermediate values passed between subgoals or between tool calls. Dies at transition end.
- **`c/`** — cross-turn session memory: draft artefacts, running summaries, per-conversation state. Survives turns, scoped to one session.
- **`n/`** — per-agent persistent workspace: reusable notes, templates, accumulated knowledge the agent carries across all sessions.
- **`w/`** — user-level permanent storage: final outputs, shared data, anything other agents or future sessions should see.

Prefer the tightest scope that fits — it's cheaper to reason about and less likely to cause cross-contamination.

## Root Frame

The root frame is session-scoped, not transition-scoped. It is created when the session is created and persists for the life of the session. Transitions read from and append to the existing root frame — they do not rebuild it.

### Chat-style sessions (long-lived)

1. First chat/message arrives → session created, root frame created with origin as description
2. Envelope appended to `sessions/<sid>/pending`; run loop wakes
3. Transition drains pending into the root frame's live turns as `user` entries
4. LLM produces assistant turns, optionally with tool calls; tools execute, results appended as `tool` turns
5. Agent may call `subgoal` (pushes child frame) or `compact` (segments a run of live turns)
6. Transition ends; frame persists. Session is now ready for the next message.
7. Second chat arrives → appended as new user turn after all prior turns/segments. No new frame.

The root frame never calls `complete` in normal chat flow — chat sessions run indefinitely. Bracketing per question happens via `compact`.

**If `complete` is called at chat-root (mis-use):** the harness treats it as an implicit compact with the result as summary, appends the compact segment to the root conversation, and continues the session. The session is not terminated by a stray `complete`. This keeps the agent resilient to LLM confusion between "end this subgoal" and "end the session" — the agent can't accidentally close a chat. (For agents that *should* terminate on `complete`, the caller is invoking one-shot style — the one-shot path below.)

### One-shot invocations (short-lived)

1. Grid operation invoked on a fresh session
2. Session + root frame created; invocation input is the goal description
3. Agent works, possibly nesting subgoals
4. Agent calls `complete(result)` or produces a text-only response
5. Result returned to caller; session/frame remain on the lattice (audit trail)

Agent's first turn is always inside a frame — never frameless.

## Text-Only Response Handling

If the LLM generates text without any tool call, the goal is implicitly complete. The text becomes the result:

```
[ASSISTANT]
The analysis shows vendor A has 23% market share with slowing growth.

-> Implicit complete({result: "The analysis shows vendor A has 23% market share..."})
-> Frame pops, text returned to parent as result
```

This means every frame terminates on either an explicit `complete`/`fail` tool call or a text-only response. No bouncing needed — the natural LLM API contract ("text without tool calls = done") maps directly to frame completion.

## Nesting

`subgoal` nests naturally. Each call adds a frame. Each `complete` (or text-only response) pops one:

```
Parent calls: subgoal("Research all vendors")
  Child calls: subgoal("Analyse Acme Corp")
    Grandchild works... complete({share: 0.23})
    -> result returns to child
  Child calls: subgoal("Analyse Beta Inc")
    Grandchild works... complete({share: 0.15})
    -> result returns to child
  Child calls: complete({vendors: 3, strongest: "vendor-b"})
  -> result returns to parent
```

## Sequential Dependencies

`subgoal` branches to child. Results accumulate in the parent's conversation. Later children see earlier results in ancestor context:

```
// Parent's conversation after three sequential subgoal() calls:

subgoal("Gather vendor data")
-> {data: "w/notes/vendor-*", coverage: "products, financials"}

subgoal("Cross-reference findings")
-> {strongest: "vendor-b", risk: "A margins"}

subgoal("Draft report")
-> {report: "w/drafts/analysis"}
```

The draft step sees gather + analyse results in its ancestor context.

## Concurrent Intake During Subgoals

A session may be working inside a child frame (subgoal in progress) when a new chat or message arrives. The rule:

**Intake is uniform — envelopes always land in `sessions/<sid>/pending` regardless of frame depth.** The subgoal-aware logic is at drain time.

**Draining targets the root frame, not the active child.** When `mergeRunResult` drains `pending` into conversation, turns are appended to `frames[0].conversation`. Subgoals are the agent's internal decomposition; the user is in dialogue with the session, not with a subgoal.

**The active child sees the new user turn via ancestor context.** `renderAncestors` renders the root frame at decreasing budget; a newly-appended user turn at the tail of root's conversation shows up on the child's next inference. No extra plumbing.

**The agent has three choices once it notices:**
1. **Keep working** — appropriate when the new message is unrelated or lower priority (e.g. "actually make it concise too" during a long research task).
2. **`complete(partial_result)`** — wrap up the subgoal with what's done so far, return to root, then respond.
3. **`fail({reason: "superseded"})`** — abandon the subgoal and return to root.

The harness does not pre-empt. The agent decides.

### Soft nudge

When `pending` drains while a child frame is active, the harness injects a short `system` turn into the active frame's conversation: *"New user message at session root — see ancestor context."* This makes the event salient without changing the ancestor-rendering contract. Agents deep in tool loops don't have to audit ancestors every turn.

### Parallel tool calls including subgoal

An LLM can emit multiple tool calls in one assistant turn. If two or more are `subgoal`, the harness processes them **sequentially**: push frame 1, run to completion, pop, then push frame 2. This keeps the stack invariant intact (only one active frame) and preserves the "later subgoals see earlier results in ancestor context" guarantee. Non-subgoal tool calls in the same assistant turn execute in parallel as usual; subgoals are the exception.

### Cancellation

A user "stop" or "cancel" is not a chat message and doesn't go through this path — it's a control event (`agent:suspend` or explicit cancel). Chat intake is always additive conversation; the agent's judgement controls what happens to in-flight subgoals.

## Failure Handling

```
subgoal("Analyse Delta Corp")
-> {"status": "failed", "error": {"reason": "API returned 503", "retryable": true}}
```

Parent decides: retry, skip, escalate, or fail itself. The failed child frame is popped from the stack; its conversation is kept as a compacted segment in the parent's conversation with `summary` derived from the failure reason — useful for debugging and for the parent's next-inference context.

**Transition atomicity.** All frame-stack changes produced by a transition (pushes, pops, appends, compactions, pending drains) land in one CAS inside `mergeRunResult`. A transition that crashes mid-inference leaves the lattice at its pre-transition state; the run loop retries from there. No partial stack is ever visible to readers. Because the full stack is on the lattice, the failure point is already explorable — no separate `state.lastFailure` snapshot is needed.

## Zooming Into History

Frames live on the session record. Everything is lattice data — explore at any budget using existing operations:

```
// Session overview (root frame at index 0)
covia_inspect({path: "sessions/<sid>/frames/0", budget: 200})
-> {description: "Competitive analysis for vendors A, B, C",
    conversation: [/* Vec, 47 entries */],
    loads: {/* Map, 2 entries */}}

// Root conversation at summary level -- segments collapse to their summaries
covia_inspect({path: "sessions/<sid>/frames/0/conversation", budget: 500})
-> [{summary: "Setup and vendor A research...", turns: 45, /* items hidden */},
    {summary: "Vendor B research...", turns: 38, /* items hidden */},
    {role: "assistant", content: "Now analysing vendor C."},
    ...]

// Drill into a specific segment's full turns
covia_inspect({path: "sessions/<sid>/frames/0/conversation/0/items", budget: 5000})
-> [{role: "assistant", content: "...", toolCalls: [...]},
    {role: "tool", ...},
    ...]

// Child frame (if subgoal was active when the session was inspected)
covia_inspect({path: "sessions/<sid>/frames/1", budget: 500})
-> {description: "Analyse Beta Inc",
    conversation: [...],
    loads: {...}}
```

## Context Assembly Layout

The harness assembles the full LLM context for each inference. The ordering follows attention research: reference material at top (primacy), conversation in middle, goal and current turn at bottom (recency).

This extends `ContextBuilder` with new sections for ancestor context and goal framing:

```
+----------------------------------------------------------+
| A. SYSTEM PROMPT                              ~2,000 B   |
|    Agent identity, behavioural rules, response format.    |
|    Static per session.                                    |
+----------------------------------------------------------+
| B. TOOL SCHEMAS                               variable   |
|    4 harness tools (subgoal, complete, fail, compact)     |
|    + configured operation tools (covia_*, agent_*, etc.)  |
|    Resolved via ContextBuilder.buildConfigTools()         |
+----------------------------------------------------------+
| C. CONTEXT MAP                                  ~300 B   |
|    Budget tracking (bytes consumed/remaining).            |
|    Loaded paths with scope + inheritance info.            |
+----------------------------------------------------------+
| D. ANCESTOR CONTEXT                          ~200-500 B  |
|    Parent, grandparent, etc. conversations rendered       |
|    at decreasing budgets via CellExplorer.                |
|    Provides: chain of purpose, prior sibling results.     |
+----------------------------------------------------------+
| E. LOADED DATA                           agent-controlled |
|    Inherited loads + own scoped loads.                    |
|    Refreshed from lattice each turn via ContextLoader.    |
+----------------------------------------------------------+
| F. CONVERSATION                               remainder  |
|    [compacted segments] + live turns.                     |
|    Current frame only. Full detail.                       |
|    This is the largest and most variable section.         |
+----------------------------------------------------------+
| G. GOAL                                                   |
|    The subgoal description for the current frame.         |
|    Strong recency attention.                              |
+----------------------------------------------------------+
```

### Budget Allocation

```
Total context budget (default ~180,000 bytes, configurable)

Fixed:
  A. System prompt:       ~2,000 B
  B. Tool schemas:        variable (depends on configured tools)
  C. Context map:           ~300 B
  Subtotal fixed:         ~4,500 B typical

Variable (harness-managed):
  D. Ancestor context:    ~200-500 B (configurable per depth level)
  E. Loaded data:         sum of load budgets (agent-controlled)
  G. Goal:                ~200 B

Remainder -> F. Conversation:
  = total - fixed - ancestors - loaded - goal
```

### Ancestor Context (Section D)

Ancestors are an array of frames, ordered from outermost to innermost. Each frame has its description and conversation rendered via CellExplorer at decreasing budgets:

```
[
  // grandparent (budget ~150B)
  {description: "Competitive analysis for vendors A, B, C",
   conversation: [{/* Seg, 8 turns */}, subgoal("Research") -> pending...]},

  // parent (budget ~300B)
  {description: "Research vendors sequentially",
   conversation: [
     subgoal("Analyse Acme Corp") -> {share: 0.23, growth: 0.08, risk: "margin pressure"},
     "Vendor A complete. Starting B.",
     subgoal("Analyse Beta Inc") -> pending...]}
]
```

Each ancestor has its own living conversation — including compacted segments, completed child results, and the turns up to the branch point. The "pending..." on the last `subgoal` shows where the current child is executing.

**Drilling into truncated ancestors:** The agent uses existing tools. `covia_inspect` for a one-shot look, `context_load` for persistent access. No new mechanism needed.

## Compaction Is Just a Segment Boundary

There's no separate compaction mechanism. It's the same principle applied everywhere:

| Situation | What happens | What the LLM sees |
|-----------|-------------|-------------------|
| Ancestor frame | Full conversation stored in parent frame | Summarised at budget |
| Completed sibling | Full conversation stored at sibling's node | One-line result in parent conversation |
| Compacted segment | Full turns stored in segment | Summary at low budget, full at high budget |
| Live turns | In current conversation | Full detail |

One principle: **full data always stored, CellExplorer controls resolution.** Compaction, ancestry, and sibling results are all the same operation viewed differently.

## State Model

Frame state lives on the **session record**, not per-adapter agent state. Config remains on the agent; per-conversation data is owned by the session.

### Session record

```json5
// sessions/<sid>
{
  parties: [ /* DIDs participating */ ],
  meta:    { created: <ts>, turns: <N>, ... },
  pending: [ /* intake envelopes not yet drained into the frame */ ],
  frames: [
    {
      description: "Session origin (first message, task goal, invocation input)",
      conversation: [ /* segments + live turns -- the session's sole conversation record */ ],
      loads:        { /* frame-scoped loads */ }
    },
    {
      description: "Subgoal description (pushed by subgoal tool)",
      conversation: [ /* ... */ ],
      loads:        { /* ... */ }
    }
  ],
  result: null  // set when root frame completes (one-shot invocations only)
}
```

The frame stack is stored as a vector. The last element is the active frame. Push appends, pop removes the last element. `frames[0]` is the persistent root frame — its `conversation` is the canonical session history.

### Agent record

```json5
// g/<agent>
{
  config: { /* operation, model, systemPrompt, tools, caps, context */ },
  state:  { /* agent-level state -- NO per-session conversation data here */ },
  tasks:  { /* pending task Jobs */ },
  sessions: { /* session IDs this agent participates in */ }
}
```

Config is read on every transition and propagated into the transition input. Per-session data (frames, pending) is fetched from the session record, not the agent.

### Legacy fields being removed

- **`sessions/<sid>/history`** — a flat turn vector that was built as a parallel structure. The conversation lives inside the root frame; no separate history field exists in the target schema. The turn envelope shape (`{role, content, ts, source}`) carries over unchanged as entries in `frames[0].conversation`.
- **In-memory frame stack in `GoalTreeAdapter.processGoal`** — the adapter built a fresh root frame per transition and discarded it at the end. Frames are session state and read from the lattice; there is nothing to build.
- **`state.lastFailure`** — a failure-only snapshot kept as a debug aid because the real stack wasn't persisted. With frames on the lattice, the full stack at failure is already there; this field is redundant.

`llmagent:chat` and `goaltree:chat` write and read the same `sessions/<sid>/frames[0].conversation`. LLMAgent is a root-frame-only, no-harness-tools configuration.

## Full Example: What the LLM Sees

### Parent Context (root frame, researching vendors)

```
[A: SYSTEM]
You are a research analyst agent on the Covia lattice.

[B: TOOLS]
covia_inspect, covia_read, covia_write, covia_list,
context_load, context_unload,
subgoal, complete, fail, compact,
(+ other configured tools)

[C: CONTEXT MAP]
budget: 4500/180000 bytes (175500 remaining)
loaded:
  w/notes/methodology [200B, root]

[D: ANCESTOR CONTEXT]
(none -- this is the root frame)

[E: LOADED DATA]
w/notes/methodology (200B):
"Compare revenue growth, margins, share. Flag risks."

[F: CONVERSATION]
<covia_inspect path="w/vendors" budget=300>
-> {a: "Acme Corp", b: "Beta Inc", c: "Gamma Ltd", /* +9 more */}
"Found all three. Starting sequential research."

<subgoal description="Analyse Acme Corp: products, financials, market position.">
-> {status: "complete", result: {share: 0.23, growth: 0.08, risk: "margin pressure"}}
"Acme done. Now Beta."

[G: GOAL]
Generate competitive analysis report for vendors A, B, C.
```

### Child Context (vendor-b frame)

```
[A: SYSTEM]
(same system prompt)

[B: TOOLS]
(same tool palette)

[C: CONTEXT MAP]
budget: 5200/180000 bytes (174800 remaining)
loaded:
  w/notes/methodology [200B, inherited]
  w/vendors/b/profile [500B, current]

[D: ANCESTOR CONTEXT]
[{description: "Competitive analysis for vendors A, B, C",
  conversation: [
    covia_inspect("w/vendors") -> {a: "Acme", b: "Beta", c: "Gamma", /* +9 */},
    "Methodology loaded. Starting sequential research.",
    subgoal("Analyse Acme Corp") -> {share: 0.23, growth: 0.08, risk: "margin pressure"},
    "Vendor A done.",
    subgoal("Analyse Beta Inc") -> pending...
  ]}]

[E: LOADED DATA]
w/notes/methodology (200B, inherited):
"Compare revenue growth, margins, share. Flag risks."

w/vendors/b/profile (500B):
{name: "Beta Inc", founded: 2018, employees: 1200,
 hq: "Austin TX", sector: "enterprise software"}

[F: CONVERSATION]
(empty -- fresh frame)

[G: GOAL]
Analyse Beta Inc: products, financials, market position.
```

### Child Working (several turns in)

```
[F: CONVERSATION]
<covia_inspect path="w/vendors/b/products" budget=800>
-> {flagship: ["Bolt"], total: 8, share: 0.15}
"Focused portfolio -- 8 products vs Acme's 14, but 15% share."

<covia_inspect path="w/vendors/b/financials" budget=600>
-> {revenue: 2100000000, growth: 0.22, margin: 0.24, r_and_d: 0.34}
"22% growth, accelerating. High R&D (34%) explains margins."

<covia_write path="w/notes/vendor-b" value="Smaller but fastest growing...">
-> stored

[ASSISTANT]
Analysis complete. Beta is smaller but growing fastest with strong margins.
<complete result={share: 0.15, growth: 0.22, risk: "margins may not sustain at scale"}>
```

### After Child Pops -- Parent Sees

```
[F: CONVERSATION]
...previous turns...

<subgoal description="Analyse Beta Inc: products, financials, market position.">
-> {status: "complete", result: {share: 0.15, growth: 0.22, risk: "margins may not sustain"}}
"Beta done. Now Gamma."
```

One tool call, one result. The turns inside are stored in the child's conversation on the lattice, explorable later but not in the parent's context.

### Compaction Mid-Goal

After a long research session (200+ turns):

```
[ASSISTANT]
Context is getting large. Let me checkpoint.
<compact summary="Researched vendors A (23% share, slowing) and B (15% share, fast growth). Compared product portfolios -- A broader, B more focused. Financials show A's margins under pressure while B's R&D investment is paying off. Wrote detailed notes for both to w/notes/. Starting vendor C research next.">

[TOOL_RESULT]
Compacted 187 turns into segment. Context freed.

[ASSISTANT]
Now researching vendor C.
<subgoal description="Analyse Gamma Ltd...">
```

The conversation is now:

```
[
  {summary: "Researched vendors A and B...", turns: 187, items: [... all 187 turns ...]},
  // live turns:
  {role: "tool", content: "Compacted 187 turns..."},
  {role: "assistant", content: "Now researching vendor C."},
  // ... subgoal call and result
]
```

## Minimal Agent

A basic agent needs only a few tools and no concern for budgets, scoping, or context management:

```
covia_inspect  -- look at lattice data
covia_write    -- save notes
subgoal        -- decompose into sub-tasks
complete       -- finish and return result
compact        -- checkpoint when prompted
```

These are a subset of the tools the harness always provides. The agent doesn't need to know about load scoping, budget arithmetic, ancestor rendering, or frame management — the harness handles all of that.

### Graduating to Advanced Features

As goals get more complex, the agent can opt into more tools:

| Complexity | Tools used | Why |
|-----------|------------|-----|
| Need reference data across turns | `context_load` | Keep data in context without re-exploring |
| Need to scope data to subgoals | `context_load` (frame-scoped) | Auto-cleanup on completion |
| Need intra-transition scratch | `t/` namespace with `covia_write` | Working files for one transition; shared across subgoals |
| Need cross-turn session memory | `c/` namespace with `covia_write` | Survives turns within the session; scoped to this conversation |
| Context getting long | `compact` | Agent-controlled checkpointing |
| Load rejected, context full | `context_unload` | Free space by removing paths |

## Design Decisions

1. **`subgoal` is a tool call.** Branches to child, returns result. LLMs already know how tool calls work.
2. **`complete`/`fail` are return/throw.** Explicit completion. Text-only response is implicit complete.
3. **`compact` requires an LLM-written summary.** Only the LLM knows what matters. The harness prompts when compaction is needed but never auto-generates summaries.
4. **Segments contain full data.** `items` holds every turn. `summary` is for CellExplorer truncation. Nothing is ever discarded.
5. **No separate archive.** The conversation structure IS the archive. CellExplorer controls visibility.
6. **One resolution principle.** Ancestors, siblings, compacted segments, and live turns all use the same mechanism: full data stored, CellExplorer renders at budget.
7. **Ancestor context is an array of frames.** Each rendered at decreasing budgets. Parent ~300B, grandparent ~150B.
8. **Description is the child's goal.** The `subgoal` description becomes the goal message in the child frame.
9. **Loads are lexically scoped.** Children inherit parent loads. Shadowing supported. Pop restores parent's version.
10. **Four scoped namespaces, distinct lifetimes.** `w/` (user, permanent), `n/` (agent, permanent), `c/` (session, cross-turn), `t/` (Job, per-transition). Agents pick the tightest scope that fits. `t/` is shared across subgoals within a transition but resets between transitions; cross-turn memory goes in `c/`.
11. **Sequential execution.** `subgoal` blocks the parent. Later children see earlier results in ancestor context.
12. **Nesting is natural.** A child can call `subgoal`. The stack grows. Each `complete` pops one frame.
13. **Session IS a root frame.** Root frame is created at session creation and persists for the session's life. Transitions append to it; they do not rebuild it. Chat sessions never `complete` the root; one-shot invocations do.
14. **The frame stack is the session's conversation record.** `sessions/<sid>/frames[0].conversation` holds everything — user turns, assistant turns, tool calls, tool results, segments, and child-frame references. There is no `session.history` field, no per-adapter transcript, no debug snapshot on failure; the full stack on the lattice is the only record.
15. **Text-only = implicit complete.** Natural LLM API contract. No bouncing.
16. **GoalTreeAdapter and LLMAgentAdapter share the session schema.** LLMAgentAdapter is the degenerate case: root frame only, no harness tools enabled. Agents choose adapter via config; readers consume both the same way.
17. **Existing tools reused.** `context_load`, `context_unload`, all `covia:*` operations, `agent:*` operations — everything from the existing tool palette. Goal tree adds 7 harness tools, not a new tool universe.
18. **Budget is harness-managed by default.** Agent gets yes/no on loads, not arithmetic. Context map shows numbers for advanced agents.
19. **Context layout follows attention research.** Reference at top (primacy), conversation in middle, current turn at bottom (recency).
20. **Frame stack is lattice data on the session record.** Stored in `sessions/<sid>/frames`. Explorable, mergeable, recoverable. Survives across transitions and agent restarts.
21. **Compacted segments accumulate.** Each `compact` appends a segment. Phase boundaries preserved. Per-question bracketing is the primary use in chat sessions.
22. **Intake queue stays at session level.** `sessions/<sid>/pending` is the pre-transition staging area; envelopes are drained into the root frame as `user` turns atomically inside `mergeRunResult`. Keeps concurrent intake separate from the append path.

## Implementation Notes

### GoalTreeAdapter Structure

```
covia.adapter.agent.GoalTreeAdapter extends AAdapter
  - processGoal(RequestContext, ACell input) -- entry point; reads session record from input
  - loadRootFrame(sessionRecord) -- reads existing frames vector from session
  - runFrame(frames, activeIdx, RequestContext) -- iterative frame execution
  - buildFrameContext(frames) -- context assembly with ancestors
  - handleSubgoal(input, frames) -- push frame; transition exits so next cycle runs child
  - handleComplete(input, frames) -- pop frame, return result to parent frame
  - handleFail(input, frames) -- pop frame, return error
  - handleCompact(input, frames) -- segment live turns in active frame
  - appendTurnsAtomic(frames, newTurns) -- append inside mergeRunResult CAS
```

### Session-frame integration

The run-loop merge step (`mergeRunResult`) atomically:
1. Drains the prefix of `sessions/<sid>/pending` that the transition saw
2. Appends user turns (converted from drained envelopes) to `frames[0].conversation`
3. Appends the transition's assistant / tool / subgoal turns to the active frame's conversation
4. Writes the agent timeline entry

All inside one CAS on the session record. Readers never see partial state.

### Reuse from Existing Code

| Component | Reuse | Notes |
|-----------|-------|-------|
| `ContextBuilder` | Extend with `withAncestorFrames()` | Budget-aware ancestor rendering across the frame stack |
| `ContextLoader` | Reuse as-is | Resolves loaded paths and context entries |
| `CellExplorer` | Reuse as-is | Renders ancestors, segments at budget |
| `CapabilityChecker` | Reuse as-is | Same capability enforcement |
| Tool dispatch | Reuse `executeToolCall` pattern | Same config tool resolution, grid dispatch |
| `AgentState` | Frame data moves to session record | `state.goalTree.frames` removed. Agent state holds only `config` and adapter-agnostic fields. |
| Session record | Add `frames` vector | `sessions/<sid>/frames[0].conversation` is canonical history. Replaces `sessions/<sid>/history`. |
| `LLMAgentAdapter` | Reads/writes same session shape | Degenerate case: single root frame, no harness tools. Single reader for both adapters. |
| Level 3 contract | Identical | Same `{messages, tools, responseFormat}` input/output |
| `CoviaAdapter` | `NamespaceResolver` registry | Virtual namespace dispatch for `t/`, `n/`, `c/` |
| `RequestContext` | `jobId`, `sessionId`, `agentId` fields | Needed by `t/` / `c/` / `n/` resolvers to rewrite paths |
| Job record | `temp` field | MapLattice within job record for `t/` backing storage |

### Cutover plan

The flat-history structure and in-memory frame stack are two halves of the same gap — removed together. Sessions are a recent primitive and not widely persisted across upgrades, so the cutover is a direct replacement rather than a data migration.

1. **Session record.** Add `frames` to `ensureSession`'s initial shape; initialise with a single empty root frame. Drop `history` from the initial shape.
2. **Writer path.** `mergeRunResult` drops its `history` append branch. In the same CAS it (a) drains `pending` envelopes as user turns into `frames[0].conversation` (root, regardless of active depth — see Concurrent Intake) and (b) appends the transition's assistant / tool / compact / subgoal entries to the active frame's conversation (`frames[last]`). A push from `subgoal` grows `frames` by one; a pop from `complete`/`fail` shrinks it by one, with the child's conversation folded into the parent's as a segment on failure.
3. **Reader path.** `AgentAdapter.sessionHistory(input)` removed. Adapters read `input.session.frames` (a full stack) and render via `GoalTreeContext.renderAncestors` + `renderConversation`. `effectiveMessages(input)` unchanged.
4. **`ContextBuilder`.** `withSessionHistory` removed; new `withFrameStack(frames)` delegates to the existing GoalTreeContext renderers — these already handle segments and preserve tool-call interleaving.
5. **`GoalTreeAdapter.processGoal`.** Stop constructing a fresh root frame. Read frames from the session map, append during execution via the existing `updateFrame` path, emit the final stack back as part of the transition output so `mergeRunResult` persists it. `state.lastFailure` code path removed — the stack on the lattice is the post-mortem.
6. **`LLMAgentAdapter.processChat`.** Writes assistant response as a live turn into the root frame via the same framework path — no adapter-owned conversation state.
7. **Tests.** Assertions referencing `session.history` redirect to `session.frames[0].conversation`. Turn envelope shape unchanged; most assertions need only a path update.

### Virtual Namespace Resolvers

`t/`, `n/`, and `c/` are virtual prefixes resolved by `NamespaceResolver` implementations registered on `CoviaAdapter`. See GRID_LATTICE_DESIGN.md §4.5 for the full design.

- **`t/` resolver** (`TempNamespaceResolver`) — navigates to `j/{ctx.jobId}/temp/` within the user's job index. Set by `JobManager` on every Job. Dies with the Job.
- **`n/` resolver** (`AgentNamespaceResolver`) — navigates to `g/{ctx.agentId}/n/` within the agent's record. Uses `ctx.agentId`. Persistent per-agent.
- **`c/` resolver** (`SessionNamespaceResolver`) — rewrites to `g/{ctx.agentId}/sessions/{ctx.sessionId}/c/`. Requires both `agentId` and `sessionId`; errors otherwise. Persistent per-session, survives transitions.
