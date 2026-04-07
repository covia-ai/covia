# Covia Goal Tree

**Version:** 1.1 Draft
**Purpose:** Hierarchical goal decomposition, execution, and history for lattice-aware agents. Agents pursue goals; sub-goals open scoped frames with their own conversation; results return up the tree.

## Core Idea

`subgoal()` is a tool call that brackets a section of work. Inside, the agent has a clean conversation with summarised ancestry above. `complete()` returns a structured result to the parent. `compact()` checkpoints long conversations into segments without losing data. Everything is lattice data, rendered by CellExplorer at any budget.

## How LLMs Work With Tools

An LLM generates text plus optional tool-call blocks. The harness executes the tools and feeds results back. When the LLM generates text with no tool calls, the response is final. This is the standard API contract:

```
[SYSTEM]
You are a research analyst agent...

[TOOLS]
explore, load, unload, copy, write, compare, subgoal, complete, fail, compact

[USER]
Analyse the competitive landscape for vendors A, B, C.

[ASSISTANT]
I'll explore the available data first.
<tool_use name="explore">{"paths": "/w/vendors", "budget": 300}</tool_use>

[TOOL_RESULT]
{a: {name: "Acme Corp"}, b: {name: "Beta Inc"}, c: {name: "Gamma Ltd"},
 /* +9 more */} /* Map, 12 entries, 45KB */

[ASSISTANT]
Found all three. I'll research vendor A first.
<tool_use name="subgoal">{"description": "Analyse Acme Corp..."}</tool_use>

[TOOL_RESULT]
{"status": "complete", "result": {"share": 0.23, "growth": 0.08}}
```

From the parent's perspective, `subgoal()` is just a tool call that branches to the child and returns structured data. The child's 15 turns of work are invisible.

## Conversation Structure

A frame's conversation is a list. Each entry is either a **live turn** or a **compacted segment**:

```
[
  {summary: "...", turns: 200, items: [...]},   // segment 0
  {summary: "...", turns: 200, items: [...]},   // segment 1
  {role: "assistant", text: "..."},              // live turn
  {role: "tool_use", ...},                       // live turn
  {role: "tool_result", ...},                    // live turn
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
     {role: "assistant", text: "Let me check products."},
     {role: "tool_use", name: "explore", ...},
     {role: "tool_result", content: {share: 0.23, ...}},
     /* +197 more */]},
   ...],
  turn 401, turn 402
```

## The Frame Stack

The harness maintains a stack of frames internally. The LLM doesn't see the stack — it just calls tools. The stack is how the harness assembles context for each inference.

### Frame Contents

| Field | Description |
|-------|-------------|
| Description | The `subgoal()` description that created this frame |
| Conversation | List of segments + live turns |
| Own loads | Lattice paths loaded at this scope |

### Context Assembly

For each inference, the harness assembles context from the stack. The key rule: **current frame's conversation is full detail, ancestor frames are progressively summarised.**

```
Stack:
  Frame 0 (root):     conversation rendered at budget ~100
  Frame 1 (research): conversation rendered at budget ~300
  Frame 2 (vendor-b): conversation at full detail        ← active
```

The LLM sees:

```
[SYSTEM]
(system prompt, tool schemas, context map)

[ANCESTOR CONTEXT]
[{description: "Competitive analysis for A, B, C",
  conversation: ["Explored vendors. Methodology loaded.",
    subgoal("Research vendors") → pending...]},
 {description: "Research vendors sequentially",
  conversation: [
    subgoal("vendor-a") → {share: 0.23, growth: 0.08},
    subgoal("vendor-b") → pending...]}]

[LOADED DATA]
/g/alice/state/w/notes/methodology (200B, inherited from root):
"Compare revenue growth, margins, share. Flag risks."

/w/vendors/b/profile (500B, own scope):
{name: "Beta Inc", founded: 2018, employees: 1200, ...}

[CONVERSATION — full detail]
(live turns from current frame)

[GOAL]
Analyse Beta Inc: products, financials, market position.
```

Ancestor budget is configurable. Rule of thumb: parent ~300B, grandparent ~150B, great-grandparent ~80B. CellExplorer renders each ancestor's conversation at its budget — segments show as summaries, live turns may be truncated.

## Harness Tools (4)

### `subgoal` — Open a subgoal

```json5
{
  name: "subgoal",
  description: "Open a subgoal frame. Brackets a section of work — the child sees its description, ancestor context (summarised), and inherited loads. Branches to the child sequence which runs until it calls complete() or fail(). Returns the child's structured result.",
  parameters: {
    description: "string — what the subgoal should accomplish"
  },
  returns: "{status: 'complete', result: any} or {status: 'failed', error: any}"
}
```

When the parent calls `subgoal()`:
1. Harness pushes a new frame onto the stack
2. Description becomes the child's first user message
3. Assembles child context (summarised ancestry + inherited loads)
4. Branches to child — runs child's agentic loop
5. Child calls `complete()` or `fail()`
6. Harness pops the frame, restores parent context
7. Result delivered to parent as a tool result

No concurrency. The stack exists for the same reason a CPU has a call stack — to know where to return to and what context to restore.

### `complete` — Return result to parent

```json5
{
  name: "complete",
  description: "Finish current goal with structured result. Returns to parent.",
  parameters: {
    result: "any — structured result value"
  }
}
```

### `fail` — Return error to parent

```json5
{
  name: "fail",
  description: "Fail current goal with error info. Parent can retry, skip, or escalate.",
  parameters: {
    error: "any — structured error information"
  }
}
```

### `compact` — Checkpoint conversation

```json5
{
  name: "compact",
  description: "Checkpoint your conversation so far. Live turns become a compacted segment with your summary. Frees context space for continued work. Data is not lost — the segment retains all turns, just rendered at reduced detail by CellExplorer.",
  parameters: {
    summary: "string — your summary of the work done so far"
  }
}
```

When the agent calls `compact()`:
1. Live turns archived into a new segment with the LLM's summary
2. Segment appended to the conversation's segment list
3. Live conversation starts fresh after the segments
4. Agent continues with more context space

The harness also has a safety valve — if the LLM doesn't compact and live turns exceed a threshold:
- 70%: harness prompts "Context filling up. Consider compact()."
- 90%: harness forces compaction (auto-generates summary via separate LLM call)

## Compacted Segment Structure

```json5
{
  summary: "Explored vendor A products (23% share, 14 products). Explored financials (4.2B revenue, 8% growth). Identified margin pressure. Wrote notes to w/notes/vendor-a.",
  turns: 45,
  items: [
    {role: "assistant", text: "Let me check products."},
    {role: "tool_use", name: "explore", input: {paths: "/w/vendors/a/products", budget: 800}},
    {role: "tool_result", content: {flagship: ["Atlas", "Beacon", "Core"], total: 14, share: 0.23}},
    {role: "assistant", text: "Strong portfolio, 23% share. Now financials."},
    {role: "tool_use", name: "explore", input: {paths: "/w/vendors/a/financials", budget: 600}},
    {role: "tool_result", content: {revenue: 4200000000, growth: 0.08, margin: 0.18}},
    {role: "assistant", text: "Growth slowing from 12%. Margin pressure."},
    {role: "tool_use", name: "write", input: {dest: "w/notes/vendor-a", value: "..."}},
    {role: "tool_result", content: "Stored at /g/alice/state/w/notes/vendor-a"},
    // ... all 45 turns
  ]
}
```

`summary` is LLM-provided (via `compact()` call). `turns` and `items` are harness-provided. CellExplorer uses `summary` for low-budget rendering and `items` for high-budget rendering. Everything is always there — resolution is the only variable.

## Scoped Loads

Loads are lexically scoped. Children inherit parent loads.

### Inheritance

```
// Root loads methodology
load("/g/alice/state/w/notes/methodology", budget: 200, scope: "root")

// Child frame inherits it — no action needed
// Visible in child's loaded data section
```

### Shadowing

```
// Parent has overview
load("/w/market-data", budget: 200)

// Child needs detail
load("/w/market-data", budget: 2000, scope: "current")
// → child sees 2000B version, parent's 200B is hidden

// Child completes → parent's 200B restored
```

### On Pop

Child's own scoped loads freed. Shadowed parent loads restored. Inherited loads continue.

## Storage Tiers

| Storage | Lifetime | Purpose |
|---------|----------|---------|
| `/t/` | Root goal | Scratch space. Full agent permissions. Cleaned up on root completion. |
| `w/` (`/g/{agent}/state/w/`) | Permanent | Curated outputs: notes, drafts, refs. Survives everything. |
| Conversation data | Permanent | Full turns stored in segments. Always on lattice. Zoom via CellExplorer. |

`/t/` is a simple shared space for the root goal's duration. No forking, no overlays. Children can see and overwrite each other's files. Informal sibling data sharing.

## Root Frame

The harness creates the root frame when a request arrives:

1. Request arrives (user message, queued job, grid operation)
2. Harness creates root frame with request as description
3. Agent's first turn is inside a frame — never frameless
4. Agent works, potentially calling `subgoal()` for sub-goals
5. Agent calls `complete(result)` — root result returned to caller

## Text-Only Response Handling

If the LLM generates text without any tool call inside a `subgoal()`, the goal isn't properly finished. The harness bounces:

```
[ASSISTANT]
The analysis shows vendor A has 23% market share with slowing growth.

[CONTINUATION]
Continue working, or call complete(result) when finished.
```

This ensures every frame gets an explicit `complete()` or `fail()`.

## Nesting

`subgoal()` nests naturally. Each call adds a frame. Each `complete()` pops one:

```
Parent calls: subgoal("Research all vendors")
  Child calls: subgoal("Analyse Acme Corp")
    Grandchild works... complete({share: 0.23})
    → result returns to child
  Child calls: subgoal("Analyse Beta Inc")
    Grandchild works... complete({share: 0.15})
    → result returns to child
  Child calls: complete({vendors: 3, strongest: "vendor-b"})
  → result returns to parent
```

## Sequential Dependencies

`subgoal()` branches to child. Results accumulate in the parent's conversation. Later children see earlier results in ancestor context:

```
// Parent's conversation after three sequential subgoal() calls:

subgoal("Gather vendor data")
→ {data: "w/notes/vendor-*", coverage: "products, financials"}

subgoal("Cross-reference findings")
→ {strongest: "vendor-b", risk: "A margins"}

subgoal("Draft report")
→ {report: "w/drafts/analysis"}
```

The draft step sees gather + analyse results in its ancestor context.

## Failure Handling

```
subgoal("Analyse Delta Corp")
→ {"status": "failed", "error": {"reason": "API returned 503", "retryable": true}}
```

Parent decides: retry, skip, escalate, or fail itself. The failed frame's conversation is preserved in the segment — useful for debugging.

## Zooming Into History

Everything is lattice data. Explore at any budget:

```
// Plan overview
explore("/g/alice/state/plan", budget: 200)
→ {name: "Competitive analysis", status: "complete",
   result: {report: "w/drafts/analysis-final"}}

// Child results
explore("/g/alice/state/plan/research", budget: 500)
→ {children: {
     vendor-a: {result: {share: 0.23}},
     vendor-b: {result: {share: 0.15}},
     vendor-c: {result: {share: 0.31}}}}

// Full conversation of a specific child
explore("/g/alice/state/plan/research/vendor-b/conversation", budget: 5000)
→ [{summary: "...", turns: 12, items: [
     {role: "tool_use", name: "explore", ...},
     {role: "tool_result", ...},
     {role: "assistant", text: "Focused portfolio..."},
     ...]}]
```

## Full Example: What the LLM Sees

### Parent Context (root frame, researching vendors)

```
[SYSTEM]
You are a research analyst agent on the Covia lattice.
Tools: explore, load, unload, copy, write, compare, subgoal, complete, fail, compact

[CONTEXT MAP]
{budget: {total: 180000, loaded: 200, conversation: 1200,
  available: 174200, tokens: {total: 45000, avail: 43550}},
 paths: {"/g/alice/state/w/notes/methodology": {budget: 200, scope: "root"}}}

[LOADED DATA]
/g/alice/state/w/notes/methodology (200B):
"Compare revenue growth, margins, share. Flag risks."

[CONVERSATION]
explore("/w/vendors", budget: 300)
→ {a: "Acme Corp", b: "Beta Inc", c: "Gamma Ltd", /* +9 more */}
"Found all three. Starting sequential research."

subgoal("Analyse Acme Corp: products, financials, market position. Return share, growth, risks.")
→ {status: "complete", result: {share: 0.23, growth: 0.08, risk: "margin pressure"}}
"Acme done. Now Beta."

[GOAL]
Generate competitive analysis report for vendors A, B, C.

[CONTINUATION]
Continue: research vendor B.
```

### Child Context (vendor-b frame)

```
[SYSTEM]
(same system prompt and tool schemas)

[CONTEXT MAP]
{budget: {total: 180000, loaded: 700, conversation: 0,
  available: 174900, tokens: {total: 45000, avail: 43725}},
 paths: {
   "/g/alice/state/w/notes/methodology": {budget: 200, inherited: true},
   "/w/vendors/b/profile": {budget: 500, scope: "current"}}}

[ANCESTOR CONTEXT]
[{description: "Competitive analysis for vendors A, B, C",
  conversation: [
    explore("/w/vendors") → {a: "Acme", b: "Beta", c: "Gamma", /* +9 */},
    "Methodology loaded. Starting sequential research.",
    subgoal("Analyse Acme Corp") → {share: 0.23, growth: 0.08, risk: "margin pressure"},
    "Vendor A done.",
    subgoal("Analyse Beta Inc") → pending...
  ]}]

[LOADED DATA]
/g/alice/state/w/notes/methodology (200B, inherited):
"Compare revenue growth, margins, share. Flag risks."

/w/vendors/b/profile (500B):
{name: "Beta Inc", founded: 2018, employees: 1200,
 hq: "Austin TX", sector: "enterprise software"}

[CONVERSATION]
(empty — fresh frame)

[GOAL]
Analyse Beta Inc: products, financials, market position. Return share, growth, risks.
```

### Child Working (several turns in)

```
[CONVERSATION]
explore("/w/vendors/b/products", budget: 800)
→ {flagship: ["Bolt"], total: 8, share: 0.15}
"Focused portfolio — 8 products vs Acme's 14, but 15% share."

explore("/w/vendors/b/financials", budget: 600)
→ {revenue: 2100000000, growth: 0.22, margin: 0.24, r_and_d: 0.34}
"22% growth, accelerating. High R&D (34%) explains margins."

write("w/notes/vendor-b", "Smaller but fastest growing. 15% share, 22% growth...")
→ stored

[ASSISTANT]
Analysis complete. Beta is smaller but growing fastest with strong margins.
<tool_use name="complete">{"result": {"share": 0.15, "growth": 0.22, "risk": "margins may not sustain at scale"}}</tool_use>
```

### After Child Pops — Parent Sees

```
[CONVERSATION]
...previous turns...

subgoal("Analyse Beta Inc: products, financials, market position...")
→ {status: "complete", result: {share: 0.15, growth: 0.22, risk: "margins may not sustain"}}
"Beta done. Now Gamma."
```

One tool call, one result. The 6 turns inside are stored in the child's conversation on the lattice, explorable later but not in the parent's context.

### Compaction Mid-Goal

After a long research session (200+ turns):

```
[ASSISTANT]
Context is getting large. Let me checkpoint.
<tool_use name="compact">{"summary": "Researched vendors A (23% share, slowing) and B (15% share, fast growth). Compared product portfolios — A broader, B more focused. Financials show A's margins under pressure while B's R&D investment is paying off. Wrote detailed notes for both to w/notes/. Starting vendor C research next."}</tool_use>

[TOOL_RESULT]
Compacted 187 turns into segment. Context freed.

[ASSISTANT]
Now researching vendor C.
<tool_use name="subgoal">{"description": "Analyse Gamma Ltd..."}</tool_use>
```

The conversation is now:

```
[
  {summary: "Researched vendors A and B...", turns: 187, items: [... all 187 turns ...]},
  // live turns:
  {role: "tool_result", content: "Compacted 187 turns..."},
  {role: "assistant", text: "Now researching vendor C."},
  {role: "tool_use", name: "subgoal", ...},
  {role: "tool_result", ...}
]
```

## Compaction Is Just a Segment Boundary

There's no separate compaction mechanism. It's the same principle applied everywhere:

| Situation | What happens | What the LLM sees |
|-----------|-------------|-------------------|
| Ancestor frame | Full conversation stored in parent frame | Summarised at budget |
| Completed sibling | Full conversation stored at sibling's node | One-line result in parent conversation |
| Compacted segment | Full turns stored in segment | Summary at low budget, full at high budget |
| Live turns | In current conversation | Full detail |

One principle: **full data always stored, CellExplorer controls resolution.** Compaction, ancestry, and sibling results are all the same operation viewed differently.

## Context Insertion

### Layout

The harness assembles the full LLM context for each inference. The ordering follows attention research: reference material at top (primacy), conversation in middle, goal and current turn at bottom (recency).

```
┌──────────────────────────────────────────────────────────┐
│ A. SYSTEM PROMPT                              ~2,000 B  │
│    Agent identity, behavioural rules, response format.   │
│    Static per session.                                   │
├──────────────────────────────────────────────────────────┤
│ B. TOOL SCHEMAS                               ~2,200 B  │
│    10 tools: explore, load, unload, copy, write,        │
│    compare, subgoal, complete, fail, compact.                │
│    Convex MCP tools deferred (loaded on demand).         │
├──────────────────────────────────────────────────────────┤
│ C. CONTEXT MAP                                  ~300 B  │
│    Budget tracking (bytes + tokens).                     │
│    Loaded paths with scope + inheritance info.           │
├──────────────────────────────────────────────────────────┤
│ D. ANCESTOR CONTEXT                          ~200-500 B │
│    Parent, grandparent, etc. conversations rendered      │
│    at decreasing budgets via CellExplorer.               │
│    Provides: chain of purpose, prior sibling results.    │
├──────────────────────────────────────────────────────────┤
│ E. LOADED DATA                           agent-controlled│
│    Inherited loads + own scoped loads.                   │
│    Refreshed from lattice each turn.                     │
├──────────────────────────────────────────────────────────┤
│ F. CONVERSATION                               remainder │
│    [compacted segments] + live turns.                    │
│    Current frame only. Full detail.                      │
│    This is the largest and most variable section.        │
├──────────────────────────────────────────────────────────┤
│ G. CURRENT TURN                                          │
│    Latest tool result + harness continuation prompt.     │
│    Strong recency attention.                             │
└──────────────────────────────────────────────────────────┘
```

### Budget Allocation

```
Total context window (e.g., 200K tokens / ~800KB)

Fixed:
  A. System prompt:       ~2,000 B
  B. Tool schemas:        ~2,200 B
  C. Context map:           ~300 B
  Subtotal fixed:         ~4,500 B

Variable (harness-managed):
  D. Ancestor context:    ~200-500 B (configurable per depth level)
  E. Loaded data:         sum of load() budgets (agent-controlled)
  G. Current turn:        ~500 B (tool result + continuation)

Remainder → F. Conversation:
  = total - fixed - ancestors - loaded - current turn
```

The context map (section C) exposes this accounting so the agent can make informed decisions:

```json5
{
  budget: {
    total: 800000,
    fixed: 4500,
    ancestors: 400,
    loaded: 1700,
    conversation: 12800,
    current_turn: 500,
    available: 780100,
    tokens: {total: 200000, used: 4975, available: 195025}
  },
  paths: {
    "/g/alice/state/w/notes/methodology":
      {budget: 200, scope: "root", inherited: true},
    "/w/vendors/b/profile":
      {budget: 500, scope: "current"},
    "/w/vendors/b/financials":
      {budget: 1000, scope: "current"}
  }
}
```

### Ancestor Context (Section D)

Ancestors are an **array of frames**, ordered from outermost to innermost. Each frame has its path, description, and conversation state at the time it branched into the current path. CellExplorer renders each frame at its budget.

```
[D: ANCESTOR CONTEXT]
[
  // grandparent (budget ~150B)
  {path: "/g/alice/state/plan",
   description: "Competitive analysis for vendors A, B, C",
   conversation: [
     {summary: "Setup. Explored /w/vendors, found 12.", turns: 8,
      items: [/* Vec, 8 turns */]},
     "Loaded methodology. Starting research.",
     subgoal("Research vendors") → pending...
   ]},

  // parent (budget ~300B)
  {path: "/g/alice/state/plan/research",
   description: "Research vendors sequentially",
   conversation: [
     subgoal("Analyse Acme Corp") → {share: 0.23, growth: 0.08, risk: "margin pressure"},
     "Vendor A complete. Starting B.",
     subgoal("Analyse Beta Inc") → pending...
   ]}
]
```

Each ancestor has its own living conversation — including compacted segments, completed child results, and the turns up to the branch point. The "pending..." on the last `subgoal()` shows where the current child is executing.

**Drilling into truncated ancestors:** The `path` field on each frame lets the agent drill in using existing tools. If vendor-a's result is truncated:

```
// Quick peek — result in conversation, ephemeral
explore("/g/alice/state/plan/research/conversation", budget: 2000)

// Ongoing reference — added to loaded data section
load("/g/alice/state/plan/research/conversation", budget: 2000, scope: "current")
```

The agent decides: `explore()` for a one-shot look, `load()` for persistent access. No new mechanism needed. Temporary duplication with the ancestor summary is acceptable — explore results compact away, load results are explicitly managed.

At low ancestor budget, CellExplorer truncates aggressively — compacted segments become one-line summaries, only the most recent turns survive. At the grandparent level:

```
// grandparent at budget ~80B
{path: "/g/alice/state/plan",
 description: "Competitive analysis for A, B, C",
 conversation: [{/* Seg, 8 turns */}, subgoal("Research") → pending...]}
```

Just enough to know why it exists — but the path is there if more detail is needed.

### What the Continuation Prompt Contains

Section G is appended by the harness after every tool result or at the start of each turn. It tells the agent what to do next:

**After a tool result:**
```
[TOOL_RESULT]
{flagship: ["Bolt"], total: 8, share: 0.15}

Continue: analyse Beta Inc financials. Call complete(result) when finished.
```

**After a child subgoal() returns:**
```
[TOOL_RESULT from subgoal()]
{status: "complete", result: {share: 0.15, growth: 0.22}}

Continue: vendor B research complete. Proceed with vendor C, or call complete() if all vendors done.
```

**On a new child frame (first turn):**
```
[GOAL]
Analyse Beta Inc: products, financials, market position. Return share, growth, risks.
```

**Harness safety prompt (context filling):**
```
[SYSTEM NOTE]
Context at 72% capacity. Consider calling compact(summary) to free space.
```

### Full Context Example (vendor-b frame, mid-work)

```
[A: SYSTEM]
You are a research analyst agent on the Covia lattice.

[B: TOOLS]
explore: Read lattice data... | load: Add path to context...
subgoal: Open subgoal frame... | complete: Return result...
fail: Return error... | compact: Checkpoint conversation...
(+ copy, write, unload, compare)

[C: CONTEXT MAP]
{budget: {total: 800000, fixed: 4500, ancestors: 300,
  loaded: 1700, conversation: 3200, available: 790300},
 paths: {
   ".../methodology": {budget: 200, inherited: true},
   "/w/vendors/b/profile": {budget: 500, scope: "current"},
   "/w/vendors/b/financials": {budget: 1000, scope: "current"}}}

[D: ANCESTOR CONTEXT]
[{description: "Competitive analysis for vendors A, B, C",
  conversation: [
    explore("/w/vendors") → {a: "Acme", b: "Beta", c: "Gamma", /* +9 */},
    "Methodology loaded.",
    subgoal("Analyse Acme Corp") → {share: 0.23, growth: 0.08, risk: "margin pressure"},
    "Vendor A done. Starting B.",
    subgoal("Analyse Beta Inc") → pending...
  ]}]

[E: LOADED DATA]
/g/alice/state/w/notes/methodology (200B, inherited):
"Compare revenue growth, margins, share. Flag risks."

/w/vendors/b/profile (500B):
{name: "Beta Inc", founded: 2018, employees: 1200,
 hq: "Austin TX", sector: "enterprise software"}

/w/vendors/b/financials (1000B):
{revenue: 2100000000, growth: 0.22, margin: 0.24,
 r_and_d: 0.34, prior_year_growth: 0.19,
 breakdown: {/* Map, 4 entries, 2KB */}}

[F: CONVERSATION]
explore("/w/vendors/b/products", budget: 800)
→ {flagship: ["Bolt"], total: 8, share: 0.15}
"Focused portfolio. 15% share on 8 products — more efficient than Acme's 14."

[G: CURRENT TURN]
Continue: analyse financials. Call complete(result) when finished.
```

## Budget Management

The harness manages context budget by default. The agent doesn't do arithmetic — it gets binary feedback:

```
// Agent calls load. Harness checks budget.
load("/w/vendors/b/financials", budget: 1000)

// If space available:
→ "Loaded."

// If insufficient space:
→ "Cannot load — context is 94% full. Unload something or compact first."
```

The context map still shows budget numbers for agents that want fine control, but a typical agent ignores them and relies on the harness to say yes or no.

Compact remains an agent tool — the harness can't write good summaries because only the LLM knows what matters. But the harness prompts when compaction is needed:

```
[SYSTEM NOTE]
Context at 72% capacity. Consider calling compact(summary) to free space.
```

The agent decides when and what to summarise. The harness just signals when it's time.

## Minimal Agent

A basic agent needs only 5 tools and no concern for budgets, scoping, or context management:

```
explore  — look at lattice data
write    — save notes
subgoal  — decompose into subgoals
complete — finish and return result
compact  — checkpoint when prompted
```

### Minimal Example: Generate a Report

```
[GOAL]
Write a summary report on vendor performance.

[AGENT]
<explore paths="/w/vendors" budget=500>

[TOOL_RESULT]
{a: {name: "Acme Corp", revenue: 4200000000},
 b: {name: "Beta Inc", revenue: 2100000000},
 c: {name: "Gamma Ltd", revenue: 5800000000}}

[AGENT]
Three vendors. I'll research each one.
<subgoal description="Analyse Acme Corp. Return revenue, growth, and key risk.">

[TOOL_RESULT]
{status: "complete", result: {revenue: "4.2B", growth: "8%", risk: "margin pressure"}}

[AGENT]
<subgoal description="Analyse Beta Inc. Return revenue, growth, and key risk.">

[TOOL_RESULT]
{status: "complete", result: {revenue: "2.1B", growth: "22%", risk: "may not sustain"}}

[AGENT]
<subgoal description="Analyse Gamma Ltd. Return revenue, growth, and key risk.">

[TOOL_RESULT]
{status: "complete", result: {revenue: "5.8B", growth: "-4%", risk: "declining share"}}

[AGENT]
All three done. Writing report.
<write dest="w/drafts/vendor-report"
  value="## Vendor Performance Summary\n\nAcme: $4.2B, 8% growth, margin pressure.\nBeta: $2.1B, 22% growth, sustainability risk.\nGamma: $5.8B, -4% growth, declining.\n\nRecommendation: Beta strongest trajectory despite smaller size.">

[TOOL_RESULT]
Stored at /g/alice/state/w/drafts/vendor-report

[AGENT]
<complete result={report: "w/drafts/vendor-report", summary: "Beta strongest growth, Gamma largest but declining"}>
```

**What the agent did NOT need to know:**
- Budget numbers or token accounting
- Scope parameters on load()
- Ancestor context assembly rules
- Lexical scoping or shadowing
- `/t/` workspace
- compare() or copy()
- How CellExplorer works

The harness handled all of that. The agent just explored, decomposed with subgoal(), wrote output, and completed.

### What Each subgoal() Child Saw

Inside "Analyse Acme Corp", the child had:

```
[ANCESTOR CONTEXT]
[{path: "/g/alice/state/plan",
  description: "Write a summary report on vendor performance",
  conversation: [
    explore("/w/vendors") → {a: "Acme", b: "Beta", c: "Gamma"},
    subgoal("Analyse Acme Corp") → pending...]}]

[GOAL]
Analyse Acme Corp. Return revenue, growth, and key risk.
```

The child explored, reasoned, and called `complete()`. It didn't need to know about budgets, scoping, or context management either.

### Graduating to Advanced Features

As goals get more complex, the agent can opt into more tools:

| Complexity | Tools added | Why |
|-----------|-------------|-----|
| Need reference data across turns | `load()` | Keep data in context without re-exploring |
| Need to compare state over time | `copy()` + `compare()` | Snapshot and diff |
| Need to scope data to subgoals | `load(scope:)` | Auto-cleanup on completion |
| Need scratch space | `t/` namespace | Working files for root goal duration |
| Context getting long | `compact()` | Agent-controlled checkpointing |
| Load rejected, context full | `unload()` | Free space by removing paths |

The minimal agent and the advanced agent use the same architecture. The advanced agent just exercises more of it.

## Summary

```
subgoal(description) = CALL               (push frame, branch to child, return result)
complete(result)     = RET                (pop frame, return value to caller)
fail(error)          = THROW              (pop frame, return error to caller)
compact(summary)     = CHECKPOINT         (segment live turns, continue)

Frame stack          = harness-internal   (assembles context per frame)
Context layout       = system | tools | map | ancestors | loads | conversation | current turn
Context assembly     = current full + ancestors at budget
Conversation         = [segments...] + live turns  (one structure, zoom via CellExplorer)
Loads                = lexically scoped   (inherit from ancestors, shadow supported)
Budget               = harness-managed    (yes/no on loads, prompts for compact)
/t/                  = root-goal scoped   (shared scratch space, simple)
Resolution           = CellExplorer everywhere  (budget controls detail at every level)
Minimal agent        = explore + write + subgoal + complete + compact  (5 tools)
```

## Design Decisions

1. **`subgoal()` is a tool call.** Branches to child, returns result. LLMs already know how tool calls work.
2. **`complete()`/`fail()` are return/throw.** Explicit completion. No implicit "stop talking" semantics.
3. **`compact()` is a checkpoint.** LLM writes its own summary. Live turns become a segment. Context freed.
4. **Segments contain full data.** `items` holds every turn. `summary` is for CellExplorer truncation. Nothing is ever discarded.
5. **No separate archive.** The conversation structure IS the archive. Segments are the storage. CellExplorer controls visibility.
6. **One resolution principle.** Ancestors, siblings, compacted segments, and live turns all use the same mechanism: full data stored, CellExplorer renders at budget.
7. **Ancestor context is an array of frames.** Each frame has its description and conversation state at the branch point. Rendered at decreasing budgets. Parent ~300B, grandparent ~150B.
8. **Description is the child's prompt.** The `subgoal()` description becomes the first user message in the child frame.
9. **Loads are lexically scoped.** Children inherit. Shadowing supported. Pop restores parent's version.
10. **`/t/` is root-goal scoped.** Simple shared scratch. No forking or overlays. Cleaned up on root completion.
11. **Sequential execution.** `subgoal()` branches to child. Later children see earlier results in ancestor context.
12. **Nesting is natural.** A child can call `subgoal()`. The stack grows. Each `complete()` pops one frame.
13. **Harness manages the stack.** LLM just calls tools. Harness handles context assembly, push, pop, segment storage.
14. **Harness creates root frame.** Request → frame. Agent never runs frameless.
15. **Text-only response bounced.** Inside a frame, harness requires explicit `complete()` or `fail()`.
16. **Compacted segments accumulate.** Each `compact()` appends a segment. Phase boundaries preserved. No merging or flattening.
17. **Harness safety valve.** 70% prompts for compact. 90% forces auto-compact.
18. **Structured return is recursive.** Child→parent, root→caller — same mechanism at every level.
19. **10 tools total.** 6 lattice + 4 harness. ~2,200 B. Under 0.7% of context window.
20. **Context layout follows attention research.** Reference at top (primacy), conversation in middle, current turn at bottom (recency).
21. **Budget accounting in context map.** Bytes + tokens. Fixed + ancestors + loaded + conversation + current turn = total. Agent sees available space.
22. **Ancestor budget decreases with distance.** Parent ~300B, grandparent ~150B. Each frame rendered independently. Bounded total under 1KB even at depth 10.
23. **Continuation prompt always appended.** Harness tells agent what to do next.
24. **Ancestor frames include path.** Agent drills into truncated ancestor content using explore() or load() — existing tools, no new mechanism.
25. **Budget is harness-managed by default.** Agent gets yes/no on loads, not arithmetic. Context map shows numbers for advanced agents.
26. **Compact is an agent tool, not auto.** Only the LLM can write good summaries. Harness prompts when needed, agent decides timing and content.
27. **Minimal agent needs 5 tools.** explore, write, subgoal, complete, fail, compact. Everything else is opt-in for advanced use.
