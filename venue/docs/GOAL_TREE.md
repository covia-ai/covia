# Covia Goal Tree

**Version:** 2.0 Draft
**Purpose:** Hierarchical goal decomposition, execution, and history for lattice-aware agents. Agents pursue goals; sub-goals open scoped frames with their own conversation; results return up the tree.

## Core Idea

`subgoal` is a tool call that brackets a section of work. Inside, the agent has a clean conversation with summarised ancestry above. `complete` returns a structured result to the parent. `compact` checkpoints long conversations into segments without losing data. Everything is lattice data, rendered by CellExplorer at any budget.

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
  {role: "assistant", content: "..."},           // live turn
  {role: "assistant", content: "...",            // live turn with tool calls
   toolCalls: [{id: "tc_1", name: "covia_inspect", arguments: {...}}]},
  {role: "tool", id: "tc_1", name: "covia_inspect", content: "..."},
]
```

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

The harness maintains a stack of frames internally. The LLM doesn't see the stack — it just calls tools. The stack is how the harness assembles context for each inference.

### Frame Contents

| Field | Description |
|-------|-------------|
| description | The `subgoal` description that created this frame |
| conversation | List of segments + live turns |
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

## Harness Tools (4)

These are built-in tools handled directly by GoalTreeAdapter, following the same pattern as `complete_task` / `fail_task` / `context_load` / `context_unload` in LLMAgentAdapter. They are always included in the tool palette alongside the agent's configured operation tools.

### `subgoal` -- Open a subgoal

```json5
{
  name: "subgoal",
  description: "Open a subgoal frame. Brackets a section of work -- the child sees its description, ancestor context (summarised), and inherited loads. Runs until the child calls complete() or fail(), or produces a text-only response (implicit complete). Returns the child's structured result.",
  parameters: {
    description: { type: "string", description: "What the subgoal should accomplish" }
  },
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

### `complete` -- Return result to parent

```json5
{
  name: "complete",
  description: "Finish current goal with structured result. Returns to parent.",
  parameters: {
    result: { type: "object", description: "Structured result value" }
  }
}
```

### `fail` -- Return error to parent

```json5
{
  name: "fail",
  description: "Fail current goal with error info. Parent can retry, skip, or escalate.",
  parameters: {
    error: { type: "object", description: "Structured error information" }
  }
}
```

### `compact` -- Checkpoint conversation

```json5
{
  name: "compact",
  description: "Checkpoint your conversation so far. Live turns become a compacted segment with your summary. Frees context space for continued work. Data is not lost -- the segment retains all turns, just rendered at reduced detail by CellExplorer.",
  parameters: {
    summary: { type: "string", description: "Your summary of the work done so far (required -- only you know what matters)" }
  }
}
```

When the agent calls `compact`:
1. Live turns archived into a new segment with the LLM's summary
2. Segment appended to the conversation's segment list
3. Live conversation starts fresh after the segments
4. Agent continues with more context space

The `summary` parameter is required — only the LLM knows what matters in the work done so far. The harness prompts when compaction is needed but the agent writes the summary:

- **70%**: harness appends system note: "Context at 72% capacity. Consider calling compact(summary) to free space."
- **90%**: harness appends stronger note: "Context at 92% capacity. You must compact now or context will be truncated."

If the LLM still doesn't compact after the 90% warning, the harness truncates the oldest live turns (they're still on the lattice, just no longer in the active context).

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

| Storage | Lifetime | Purpose |
|---------|----------|---------|
| `t/` | Root goal | Scratch space. Full agent permissions. Cleaned up on root completion. |
| `w/` | Permanent | Curated outputs: notes, drafts, refs. Survives everything. |
| Conversation data | Permanent | Full turns stored in segments. Always on lattice. Zoom via CellExplorer. |

`t/` is a new namespace in the per-user lattice, alongside `w/`, `g/`, `s/`, `j/`, `o/`, `h/`. It provides root-goal-scoped scratch space. The harness clears `t/` when the root frame completes or fails. Children can see and overwrite each other's files — it's simple shared space for the duration of the root goal.

Operations that work with `t/`: `covia:read`, `covia:write`, `covia:delete`, `covia:append`, `covia:list`, `covia:inspect` — all existing operations, just with the `t/` prefix.

## Root Frame

The harness creates the root frame when a request arrives:

1. Request arrives (user message, queued task, grid operation)
2. Harness creates root frame with request as description
3. Agent's first turn is inside a frame — never frameless
4. Agent works, potentially calling `subgoal` for sub-tasks
5. Agent calls `complete(result)` or produces a text-only response — root result returned to caller

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

## Failure Handling

```
subgoal("Analyse Delta Corp")
-> {"status": "failed", "error": {"reason": "API returned 503", "retryable": true}}
```

Parent decides: retry, skip, escalate, or fail itself. The failed frame's conversation is preserved in the segment — useful for debugging.

## Zooming Into History

Everything is lattice data. Explore at any budget using existing operations:

```
// Plan overview
covia_inspect({path: "g/alice/state/plan", budget: 200})
-> {name: "Competitive analysis", status: "complete",
   result: {report: "w/drafts/analysis-final"}}

// Child results
covia_inspect({path: "g/alice/state/plan/research", budget: 500})
-> {children: {
     vendor-a: {result: {share: 0.23}},
     vendor-b: {result: {share: 0.15}},
     vendor-c: {result: {share: 0.31}}}}

// Full conversation of a specific child
covia_inspect({path: "g/alice/state/plan/research/vendor-b/conversation", budget: 5000})
-> [{summary: "...", turns: 12, items: [
     {role: "assistant", content: "...", toolCalls: [...]},
     {role: "tool", ...},
     ...]}]
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

GoalTreeAdapter stores its state in the agent's `state` field on the lattice, extending the existing `AgentState` structure:

```json5
{
  config: { /* agent config -- operation, model, systemPrompt, tools, caps, context */ },
  history: [ /* existing field -- not used by goal tree, kept for compatibility */ ],
  loads: { /* existing field -- not used by goal tree, kept for compatibility */ },
  goalTree: {
    frames: [
      {
        description: "Root goal description",
        conversation: [ /* segments + live turns */ ],
        loads: { /* frame-scoped loads */ }
      },
      {
        description: "Subgoal description",
        conversation: [ /* segments + live turns */ ],
        loads: { /* frame-scoped loads */ }
      }
    ],
    result: null  // set when root completes
  }
}
```

The frame stack is stored as a vector. The last element is the active frame. Push appends, pop removes the last element.

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
| Need scratch space | `t/` namespace with `covia_write` | Working files for root goal duration |
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
10. **`t/` is root-goal scoped.** Per-user lattice namespace. Cleaned up on root completion. Shared scratch for child frames.
11. **Sequential execution.** `subgoal` blocks the parent. Later children see earlier results in ancestor context.
12. **Nesting is natural.** A child can call `subgoal`. The stack grows. Each `complete` pops one frame.
13. **Harness creates root frame.** Request creates frame. Agent never runs frameless.
14. **Text-only = implicit complete.** Natural LLM API contract. No bouncing.
15. **GoalTreeAdapter is a peer of LLMAgentAdapter.** Same Level 2 slot, same Level 3 contract. Agents choose via config.
16. **Existing tools reused.** `context_load`, `context_unload`, all `covia:*` operations, `agent:*` operations — everything from the existing tool palette. Goal tree adds 4 new harness tools, not a new tool universe.
17. **Budget is harness-managed by default.** Agent gets yes/no on loads, not arithmetic. Context map shows numbers for advanced agents.
18. **Context layout follows attention research.** Reference at top (primacy), conversation in middle, current turn at bottom (recency).
19. **Frame stack is lattice data.** Stored in `state.goalTree.frames`. Explorable, mergeable, recoverable.
20. **Compacted segments accumulate.** Each `compact` appends a segment. Phase boundaries preserved.

## Implementation Notes

### GoalTreeAdapter Structure

```
covia.adapter.agent.GoalTreeAdapter extends AAdapter
  - processGoal(RequestContext, ACell input) -- entry point
  - runFrame(FrameStack, RequestContext) -- recursive frame execution
  - buildFrameContext(FrameStack) -- context assembly with ancestors
  - handleSubgoal(input, FrameStack) -- push frame, recurse
  - handleComplete(input, FrameStack) -- pop frame, return result
  - handleFail(input, FrameStack) -- pop frame, return error
  - handleCompact(input, FrameStack) -- segment live turns
```

### Reuse from Existing Code

| Component | Reuse | Notes |
|-----------|-------|-------|
| `ContextBuilder` | Extend with `withAncestorFrames()` | Budget-aware ancestor rendering |
| `ContextLoader` | Reuse as-is | Resolves loaded paths and context entries |
| `CellExplorer` | Reuse as-is | Renders ancestors, segments at budget |
| `CapabilityChecker` | Reuse as-is | Same capability enforcement |
| Tool dispatch | Reuse `executeToolCall` pattern | Same config tool resolution, grid dispatch |
| `AgentState` | Extend state model | Add `goalTree` field alongside existing `history` |
| Level 3 contract | Identical | Same `{messages, tools, responseFormat}` input/output |
| `CoviaAdapter` | Add `t/` namespace support | New writable namespace in `readPath`/`writePath` |

### New `/t/` Namespace

Add `t/` to the per-user lattice alongside `w/`, `o/`, `h/`. Implementation in `CoviaAdapter`:
- Writable (like `w/`)
- Readable by all existing `covia:*` operations
- Cleared by GoalTreeAdapter on root frame completion
- Stored at `:user-data -> <DID> -> :t` in the lattice
