# Agent Context — Assembly Design

The canonical description of how an agent's context — the messages and tools sent to its model — is assembled. Every runtime assembles through this design; a runtime-specific document (GOAL_TREE.md for the goal-tree harness) describes only what it adds, and refers back here for the rest.

**Status:** The code follows this document. Where it still differs, the difference is recorded in §9.1 and nowhere else.

See [AGENT_LOOP.md](./AGENT_LOOP.md) §3.2 for the surrounding level-2 architecture, [SKILLS.md](./SKILLS.md) for skills, [AGENT_TEMPLATES.md](./AGENT_TEMPLATES.md) for how configuration is composed, [GOAL_TREE.md](./GOAL_TREE.md) for the goal-tree harness, and [OPERATIONS.md](./OPERATIONS.md) (*The `model` facet*) for what a model declares about itself.

---

## 1. Overview

Three words are used precisely throughout:

- **Inference** — one call to an LLM operation.
- **Cycle** — one transition: from an input (or a wake-up with none) through the tool loop to the final reply. One or more inferences.
- **Turn** — one message in the conversation.

Every inference is one call with one input:

```json
{
  "model": "claude-sonnet-5",
  "messages": [ {"role": "system", "content": "..."}, ... ],
  "tools": [ ... ]
}
```

`messages` is a **flat array of `{role, content}`** in four roles — `system`, `user`, `assistant`, `tool`. There is no nested structure, no typed section, no ordering metadata. Everything that looks structured to the model is a **labelled text element** inside a content string. Two message kinds carry more than text, and the assembler passes them through untouched: an assistant message may carry `toolCalls`, a `tool` message carries its call id and result, and a user message may carry an array of content blocks for vision.

`tools` is part of the prompt too — section 0 of the sequence (§3.2.3): every provider serialises the definitions ahead of the messages, so they are the first bytes of the cached prefix.

So assembly is: *compute each element, in order, and concatenate*. The value of the design is not in the data structure — it is in **which elements, in what order, produced by whom**.

**The unit of assembly is one inference, not one cycle.** Within a tool loop the whole prompt is rebuilt before every call: loads are re-read, so a tool's write to a loaded path is visible on the next inference; the tool-loop messages so far are appended in band; the tail is rendered again after them.

**Assembly is a pure function**: `assemble(Spec) → Prompt`. The Spec (§8) carries everything, the clock included; the assembler reads nothing else. The same Spec yields the same Prompt, which is what makes `agent:context` inspection exact and every section testable without a venue.

This document covers that. The grammar of what an individual context entry may be lives in §6; the scope chain that decides which entries are in play lives in §7.

### 1.1 Element labels

Every element the assembler renders is `{kind, key?, body}`, and **one function renders it**, in one of three dialects chosen by the model's declared `labels` option: `bracket` (the default), `xml` or `header`. Labels are the interface between the prompt and everything that reads it back — the model, `agent:context` inspection and the tests — which is why there is one renderer and not a header convention per section: changing a label changes it everywhere it is read, including the tests that probe for it.

The complete set:

| Element | `bracket` (default) | `xml` | `header` |
|---------|---------------------|-------|----------|
| Skills index — one line per skill, `(loaded)` against those in context | `[Skills]` | `<skills>` | `## Skills` |
| Loaded skill — the path is its unload key | `[Skill: <name> — <path>]` | `<skill name="…" path="…">` | `## Skill: <name> — <path>` |
| Context entry — the label is its unload key | `[Context: <label>]` | `<context label="…">` | `## Context: <label>` |
| Entry whose source failed | `[Context: <label> — unavailable: <reason>]` | `<context label="…" unavailable="…"/>` | `## Context: <label> — unavailable: <reason>` |
| Late system message (§3.2.1) | `[system: …]` | `<system>` | `## System` |
| Compacted conversation segment | `[Compacted: <N> turns] <summary>` | `<compacted turns="…">` | `## Compacted: <N> turns` |
| Ancestor context (goal tree) | `[Ancestor Context]` | `<ancestors>` | `## Ancestor context` |
| Tool-failure diagnostic | `[Tool failure: <name>] <reason>` | `<tool-failure name="…">` | `## Tool failure: <name>` |
| Pending results | `[Pending job results]` | `<pending-results>` | `## Pending job results` |
| Outstanding task | `[Tasks assigned to you]` | `<tasks>` | `## Tasks assigned to you` |
| Empty-state signal | `[No pending tasks, messages, or job results. …]` | `<no-input>` | `## No input` |
| Budget warning | `[Context budget] <pct>% …` | `<context-budget>` | `## Context budget` |
| Unavailable tools | `[Configured tools unavailable in this session. …]` | `<unavailable-tools>` | `## Unavailable tools` |
| Current date | `Current date: <date>.` | `Current date: <date>.` | `Current date: <date>.` |

A block is its label followed by its body; in `xml` the body is followed by the closing tag. A one-line element — the date — is a line in every dialect. Names, paths, labels and reasons are rendered verbatim in each.

`bracket` is cheap in tokens, reads naturally in logs, and is plain text: it never collides with markdown a body may contain, and it reads the same whether an element is one of many in a system block or a message on its own. `xml` marks where an element **ends** as well as where it begins — which matters when a long body is followed by another element in the same system block — and is the delimiter Anthropic documents for multi-document prompts. `header` suits models whose guidance favours markdown sections, at two costs: a heading reads oddly when the element is a message on its own, and it competes with headings inside a body. A model that benefits from either alternative opts in on its asset. The dialect is applied at render time to ephemeral elements, and segments and diagnostics are rendered from stored data, so nothing persisted carries a dialect: an agent can change model without its history changing.

---

## 2. Design goals

1. **One assembly, every runtime.** The order is defined once. A runtime chooses *what it puts in*, never *where it lands*. Divergence should be impossible to express, not merely discouraged.

2. **Cache-efficient by construction.** Providers cache on a prefix. Anything that changes invalidates everything after it, so elements are ordered by **how often their bytes change** — stable first, volatile last — and the band boundaries are the cache boundaries (§3.1). This is a property of the sequence, not of anyone remembering to be careful.

3. **Maintainable: sections are functions.** Each section is a function of the Spec returning messages. It can be read, tested and changed alone. Assembly is a mutable accumulator that knows only *append*, *mark* and *bytes so far* — it holds no subsystem knowledge.

4. **Different agent setups supported by input, not by forking the pipeline.** goaltree renders a frame stack and carries the frame's goal as its task; llmagent renders a session frame, an inbox and its assigned tasks. Both hand the assembler a Spec.

5. **Flexible over providers**, to support the quirks of different LLMs. Sections never know which provider they are writing for. The facts about a provider that change how a prompt must be shaped or sized — whether it has a system role, whether a prefix is cached, how much context is appropriate — are declared as data on the model's operation asset (the `model` facet) and applied at the edge (§3.5) — or, for the label dialect, by the one renderer (§1.1). The assembler emits output legal for every declared provider; adding a provider means declaring its facts, not branching in a section.

---

## 3. The section sequence

### 3.1 Bands

Four bands, ordered by change frequency. The band is the *reason* an element sits where it does:

| Band | Changes when | Cache consequence |
|------|--------------|-------------------|
| **Fixed head** | configuration changes | Identical across cycles — the cacheable prefix |
| **Live surface** | the working set changes, or the data it reflects changes | Stable while the agent's loads and their sources are stable |
| **Conversation** | a turn is added | Append-only within a cycle; rewritten only by the two sanctioned rewrites (§5.6) |
| **Volatile tail** | every inference, or daily | Never cached; invalidates only itself |

**Band boundaries are cache boundaries.** On a provider that caches an explicitly marked prefix (`cachePrefix`), the edge marks the tool definitions, the system slot (head and live surface), the conversation as it stood when the cycle began, and the tool loop so far — four breakpoints, Anthropic's maximum; the tail is never marked. Within a tool loop the last mark moves forward each inference while the previous one stays a valid read point, so each inference reads the one before. The bands are therefore not a convention about ordering — they are the literal cache structure of every request.

**The rule for placing a new section:** it goes in the earliest band whose change frequency it does not exceed. That is the whole answer to "where does this go". A section that cannot name its band has not been placed; it has been appended.

The band is the *expected* change frequency. Content can be more volatile than its band: an op entry in pinned context that returns a listing changes whenever the listing does, and busts everything after it from the live surface down. The ordering cannot prevent that; it can only make the cost legible. Within `config.context`, entries render in declared order, so stable entries go first.

### 3.2 The sequence

| # | Section | Band | Role | Contents |
|---|---------|------|------|----------|
| 0 | Tool definitions | fixed head (loads-contributed: live surface) | — | The palette (§4): harness, configured, then loads-contributed tools. A parameter, not a message; every provider places it first (§3.2.3) |
| 1 | Identity prompt | fixed head | `system` | `config.systemPrompt` or the default identity, plus one line of session identity |
| 2 | Capability notice | fixed head | `system` | Declared `config.caps` — **only for an agent with tools** — so bounds are known before they are hit |
| 3 | Pinned context | live surface | `system` | `config.context` entries (§6) — operator-owned, agent cannot drop |
| 4 | Skills index | live surface | `system` | `[Skills]` — one line per discoverable skill (SKILLS.md §4.3) |
| 5 | Loaded elements | live surface | `system` | Every effective load (§7), each with its unload key |
| 6 | Conversation | conversation | `user` / `assistant` / `tool` | The rendered frame(s) |
| 7 | Pending results | conversation | `user` | Job results that arrived for this cycle |
| 8 | Current input | conversation | `user` | The inbox message(s) driving this cycle |
| 9 | Tool-loop messages | conversation | `assistant` / `tool` | Assistant/tool turns accumulated within this cycle |
| 10 | Budget warning | volatile tail | `system` | **Only** when the budget is under pressure (§3.4) |
| 11 | Current date | volatile tail | `system` | One line; changes daily |
| 12 | Unavailable tools | volatile tail | `system` | Configured tools that did not resolve this cycle |
| 13 | Outstanding task | volatile tail | `user` | The task the agent must complete or fail — the last thing before the reply |
| — | Empty-state signal | conversation | `user` | Replaces 7–8 when there is nothing to act on |

The *Role* column is the role a section emits — the role that is true of its content. Sections 1–2 are **one `system` message** — just the identity for an agent without tools: they change together and are the first thing in every request. Sections 10–12 are **one `system` message**, composed of whichever parts are present: the tail is re-rendered every inference, and one message is the cheapest shape for it. How a system message that follows the conversation reaches a given provider is the edge's business (§3.2.1, §3.5), never a section's.

### 3.2.1 The role rule

Roles are **semantic**, not positional. `system` is what the venue, the operator or the runtime says — identity and reference, the working set, a notice, a diagnostic, a compaction summary. `user` is what has happened or must be acted on — pending results, the input, the goal, the empty-state signal. `assistant` and `tool` are the agent's own turns and their results. A section emits the role that is true of its content, and the stored conversation keeps it, so provenance survives. Nothing in assembly depends on the provider.

Where a `system` message that follows the conversation actually lands is provider-dependent, and model-dependent on top. Anthropic and Gemini have no system role in the message list — system content is a top-level parameter — so a client must do *something* with a late one, and the naive thing, hoisting it, is wrong: it is cached as part of the head, so a "tail" date or a compaction nudge busts the cached head on every inference, and a compaction summary leaves its place in the conversation. OpenAI-compatible APIs keep a late system message in place, with its meaning left to the chat template. Local models served through Ollama may honour only the leading one.

So the **edge normalises, where the provider requires it** (§3.5), driven by the model's declared `systemMessages`:

- `"multiple"` — nothing to do. System messages reach the model where they were placed.
- `"single"` — the **leading run** of system messages (the head and the live surface) coalesces into the provider's system parameter, as a list of blocks where the API takes them, so the head/live boundary still carries a cache mark. Any **later** system message becomes a `user` message with its content wrapped `[system: …]` (§1.1), in place: the model still sees who is speaking, and the message stays exactly where it was put.
- `"none"` — as `"single"`, with the leading run folded into the first user message.

An operator whose model mishandles late system messages declares `"single"` for it and gets the same treatment. No section ever knows.

Two consequences are load-bearing. Every request contains at least one non-system message, because band 3 always ends with a `user` or `tool` message — the input, pending results, the empty-state signal, or the last tool result — so the edge never has to invent a turn. And consecutive same-role messages are normal — every provider merges or accepts them — so the §1.1 labels, not the message boundaries, are what keep elements distinct.

### 3.2.2 Ephemeral and persisted

Bands 1, 2 and 4 are **ephemeral**: rendered for one inference from live sources and never written to the conversation. Band 3 is **persisted as rendered**: pending results, the input and the tool-loop messages land in the frame at the end of the cycle in exactly the form the model saw them, which is what makes the band append-only for the next cycle. Two exceptions, both deliberate: the empty-state signal occupies the input slot for its inference and is not persisted; and a diagnostic the runtime must add to the conversation — a tool failure it could not return as a tool result — is persisted as a `system` turn with its source recorded, because it is an event in the sequence.

**Warnings are never conversation.** The budget warning, the compaction nudge, the date and the unavailable-tools notice describe the state of *this inference*. They belong in the tail, render once, and vanish when the condition does. A warning written into the conversation would be re-read on every later inference as though it were still true.

### 3.2.3 Tool definitions

Tool definitions are **section 0**. They are not a message — every provider takes them as a separate parameter — but they are prompt bytes, and every provider serialises them **ahead of everything else**: Anthropic documents its cache order as tools, then system, then messages; chat templates inject them into the system region. The assembler therefore controls two things about them and not a third: **which** are present (the palette, §4) and their **order** — harness tools, then configured tools, then loads-contributed tools, each group in declared order — never their position, which is first.

That position has a cost the bands cannot hide. Harness and configured tools change with configuration and belong to the fixed head; loads-contributed tools change with the working set and belong to the live surface — yet they sit *before* the head, so **a change to the tools array invalidates the whole prefix**, head included. It is the one place where live-surface content precedes fixed-head content, and it is the provider's doing, not ours. Two consequences for design:

- A skill that contributes tools is a heavier load than one that contributes text. Load it at the start of a task, not in the middle of one.
- A skill can name operations the agent calls through the generic invoke tool instead of contributing definitions, keeping the array stable. The trade is a definition the model can see against a prefix that survives.

Tool definitions are charged to the budget first (§3.4) and need no cache mark of their own: the head mark covers them.

### 3.3 The top-level function

The sequence above is not a description of the code — it *is* the code:

```java
static Prompt assemble(Spec spec) {
    Prompt p = new Prompt(spec.budget());

    // Section 0 — a parameter, not a message; charged first, placed first by every provider
    p.tools(spec.tools());

    // Fixed head — one system message; identical every inference
    p.add(systemMessage(identityPrompt(spec), capabilityNotice(spec)));
    p.mark(Band.HEAD);

    // Live surface — re-resolved each inference; moves only when the working set moves
    p.add(pinnedContext(spec, p.remaining()));
    p.add(skillsIndex(spec));
    p.add(loadedElements(spec));
    p.mark(Band.LIVE);

    // Conversation — append-only within a cycle
    p.add(conversation(spec, p.remaining()));
    p.add(pendingResults(spec));
    p.add(currentInput(spec));
    p.add(toolLoopMessages(spec));
    p.mark(Band.CONVERSATION);

    // Volatile tail — re-rendered every inference, never cached
    p.add(systemMessage(budgetWarning(p.used(), p.budget()), currentDate(spec), unavailableTools(spec)));
    p.add(outstandingTask(spec));                 // user — the last thing before the reply
    return p;
}
```

`Prompt` is a mutable accumulator and the whole request: `tools(defs)` sets the tool array and charges its bytes, `add(messages)` appends and charges theirs, `mark(band)` records where a band ends, `remaining()` and `used()` report the budget position. It knows nothing about skills, tools or capabilities. Every section is a plain function of the Spec returning messages; an empty return contributes nothing, and `systemMessage` joins the parts that are present into one message.

`p.remaining()` is passed explicitly in the three places that need it — §5.3, which sizes structured rendering from it; §5.6, which gives the conversation renderer its allowance; and the tail, which reports on it. That is the *only* order-dependence in the system, and visible arguments state it more honestly than a running total threaded invisibly through every section.

### 3.4 The budget

**There is one budget**: the model's declared context size — `model.budget.bytes` on the LLM operation asset, resolved for the agent's model (OPERATIONS.md, *The `model` facet*), else `DEFAULT_BUDGET`. The size of a context is a fact about the model, declared beside the model, not a venue-wide constant. It bounds the **input**, counted as UTF-8 bytes of every message's content plus the tool definitions, which are charged first. The reply is bounded separately by `maxTokens`.

How it is spent:

- The **fixed head** and the **live surface** are what they are. Loads are never evicted: the agent put them there and only the agent takes them out.
- The **conversation** is the elastic band. It receives the remaining allowance and meets it by the two sanctioned rewrites of §5.6 — elision, which is automatic, and compaction, which needs the agent.
- The **tail** is small; it is charged like everything else and never cached.

What the budget does, and nothing else:

| Position | Behaviour |
|----------|-----------|
| any | per-entry render sizing: a structured entry is capped at a twentieth of what remains when it renders (§5.3) |
| ≥ 70% | the tail carries one line — `[Context budget]` — saying the budget is filling and that every element's header is its unload key |
| ≥ 90% | the line says compaction is required before further work; the harness offers `compact` |
| over | the prompt is sent anyway; if the provider rejects it the cycle fails with the size and the remedy (`agent:context`, `context_unload`, `compact`) |

**Never silent.** Byte accounting is not a token bound across providers, so the runtime never removes context on its own authority. An inventory of what is loaded does not belong in the prompt either: it restates the elements rendered above it, and its byte counts change every inference.

### 3.5 The edge

The assembler's output is provider-neutral. The level-3 adapter, reading the model's declared options, does these things and no others:

| Declared | The edge |
|----------|----------|
| `systemMessages: "multiple"` | passes system messages through where they are placed |
| `systemMessages: "single"` | delivers the leading system run as the provider's system parameter — a list of blocks where the API takes them, one joined text where it does not — and converts every later system message to a `[system: …]` user message in place (§3.2.1) |
| `systemMessages: "none"` | as `"single"`, with the leading run folded into the first user message |
| `labels` | nothing at the edge beyond the wrapper above — the dialect is applied by the one renderer (§1.1), which the edge also uses for that wrapper |
| `cachePrefix` | turns the band marks into the provider's cache controls: tools and the system slot by the client's own flags, the conversation-at-cycle-start and tool-loop marks as per-message breakpoints carried in the L3 input as `cacheMarks`; `cache: false` on the call switches all of it off |
| always | maps the tool definitions to the provider's schema, in the given order, and `tool` messages and `toolCalls` to its shapes, merging consecutive same-role messages where the API requires alternation |
| always | **never reorders, never drops, never adds content** |

Nothing else about a provider needs handling: its context size is already in the budget.

---

## 4. Four phases

Assembly is the last of four steps; each produces an input the next needs:

```
resolveAuthority(config, ctx)                 →  capsCtx
resolveLoads(engine, capsCtx, chain, fixed)   →  elements, loadTools, routes
resolvePalette(config, harness, loadTools)    →  tools, routes, unavailable
assemble(spec)                                →  prompt
```

- **Authority** first: everything that reads the lattice reads under the agent's capability-narrowed context.
- **Loads** next, and they produce more than messages: a loaded skill contributes tool operations and their routes. The snapshot is resolved **once per inference** and never persisted.
- **Palette** merges harness tools, configured tools and loads-contributed tools, in that order; a name already fixed by harness or config is never shadowed by a load. The palette also knows which configured tools failed to resolve.
- **Assembly** then has everything and produces only the prompt.

Four functions, four return values. A runtime that needs `capsCtx` calls `resolveAuthority`; it does not build a context to extract it.

---

## 5. Section notes

### 5.1 Identity prompt
`config.systemPrompt`, else a default identity, followed by one line of session identity: the venue name, the model when configured, and the session id when one is in scope — the agent's handle for reporting back into this conversation from deferred work. Rebuilt every cycle from live config, so an `agent_update` applies on the next cycle with no freeze-on-first-use caching. A runtime may append a notice of its own — goaltree's subgoal notice for child frames — provided it is stable for the life of its scope. Nothing that changes within a session belongs here.

**Head discipline:** the head holds what every cycle of *this* agent needs and nothing more. It is cached, but providers without caching pay for it on every inference, and an agent that answers questions needs neither a namespace cheat sheet nor capability bounds. Depth belongs in skills, loaded when needed (SKILLS.md).

The lattice reference — namespace prefixes and addressing rules — is therefore a **skill**, not a head section: `v/skills/data/lattice`, mirrored into `root`, pinned through `config.loads` by the templates of agents that have lattice tools and discoverable by any agent that meets a path. Not every agent has tools, and not every agent with tools touches the lattice.

### 5.2 Capability notice
Rendered only for an agent that **has tools** and declares `config.caps`. Capabilities bound what the agent can *do*; an agent with no tools can do nothing the notice would inform. With tools, stating the bounds up front saves the cycle an agent otherwise spends discovering them by hitting them, and the confusing denial that follows.

### 5.3 Pinned context
`config.context` entries, resolved through the entry grammar of §6. Operator-owned: the agent may mask an entry for a conversation but cannot remove it.

Structured values are rendered as budget-bounded JSON5; any one entry is capped at a twentieth of the budget remaining when it renders (`max(MIN_ENTRY_BUDGET, remaining/20)`), so no single entry can consume the context. Strings render verbatim.

### 5.4 Skills index
One line per discoverable skill, with `(loaded)` against those already in context. Resolved fresh each inference from the agent's effective sources. Absent entirely when the agent declares no skill sources. See SKILLS.md §4.3.

### 5.5 Loaded elements
Every entry in the effective loads chain (§7), rendered through the same resolver as pinned context and re-read from the lattice on every inference (§7.2). A skill-flagged entry renders `[Skill: <name> — <path>]` plus its body; any other renders `[Context: <label>]` plus its body (§1.1).

Each element carries its **unload key** in its header. `context_unload` takes a path, and a skill is otherwise only ever named, so without this the key is invisible. A non-skill element's label is its ref, so it already carries its key.

Failures are visible, never silent: a load that stops resolving renders `[… — unavailable: <reason>]` rather than vanishing, because a missing element changes behaviour too much to hide.

### 5.6 Conversation
Runtime-supplied frames, rendered by one `ConversationRenderer` for every runtime. llmagent supplies its session's single frame; goaltree supplies its frame stack, ancestors first at decreasing budgets and the active frame last (GOAL_TREE.md). The assembler does not know the difference.

The band is append-only **within a cycle**: every inference of a tool loop sees exactly what the previous one saw, plus the new tool-loop messages. Across cycles there are exactly two sanctioned rewrites, both deliberate trades of cache for context size:

- **Scratch elision** (the default; `renderHistory: "full"` opts out). A completed cycle renders as its user input and final assistant reply; its tool calls and tool results are dropped *together*, because providers require a call and its result to be both present or both absent. It happens at the latest possible point — the cycle that has just completed — which is the rewrite that invalidates the least, and it is the automatic half of the budget's elasticity (§3.4).
- **Compaction.** A range of turns collapses into a `[Compacted: N turns] summary` segment whose summary the agent wrote, because only the agent knows what mattered. It rewrites from the segment onward, so it happens in coarse steps when the budget asks for it, never every cycle. The `compact` tool is a context tool, available to every runtime.

Everything else in the band is an append. Segments and diagnostic turns are `system` turns in the stored conversation — authored by the runtime, in sequence — and the edge keeps them in place on every provider (§3.2.1).

### 5.7 Pending results
Job results that completed for this cycle — the mechanism by which asynchronous work re-enters the conversation. Placed before the current input so that the input, the thing to act on, is closest to the reply.

### 5.8 Current input
The inbox message(s) driving this cycle; goaltree has none — its goal rides in the task slot (§5.13). When there is neither input nor pending results, the **empty-state signal** takes the slot: one `user` line saying so, so the agent can act on its role or report idle. It is content, not padding — its role as the message that keeps a system-only request legal (§3.2.1) is a consequence, not its purpose.

### 5.9 Tool-loop messages
Assistant and tool messages accumulated *within* this cycle. These are conversation, not preamble: they sit inside the band, before the tail, so each inference of the loop shares its prefix with the previous one through the last tool result and only the tail is re-rendered.

### 5.10 Budget warning
The line described in §3.4, present at ≥ 70%, escalating at ≥ 90%. Silence is the normal case, so it costs nothing until it means something.

### 5.11 Current date
One line, changing daily, taken from the Spec's clock — never from the system clock inside a section, so assembly stays pure. Kept out of the head precisely so the cacheable prefix contains no changing value; in the tail it busts only itself.

### 5.12 Unavailable tools
Configured tools that did not resolve this cycle — reported so the agent adapts rather than calling into a void. Resolution is live, so a fixed path or restored grant makes the tool available on the next cycle with no recreate.

### 5.13 Outstanding task
Present only when the agent has a task it must complete or fail. A `user` message rendered last on every inference — after the tool-loop messages and after the notices — so it is the thing nearest the reply, and never baked into history: the model sees only tasks still outstanding. The same on every runtime, with `complete_task` / `fail_task` offered only while it is present — the framework's task boundary (`TaskTools`). A goal-tree frame's *goal* is its opening turn, not this slot (§9.1).

---

## 6. Context entries — the grammar

The entry model shared by pinned context (§5.3), loaded elements (§5.5), and `context_load`.

An entry is a **string** or a **map**.

### 6.1 String entries

| Pattern | Resolution |
|---------|------------|
| `w/…`, `g/…`, `o/…`, `j/…`, `s/…` | Namespace path — read via `user.readPath()` |
| 64-char hex | Asset ID — `engine.resolveAsset()` |
| `/a/…`, `/o/…`, `did:…`, registered op name | `engine.resolveAsset()` |
| Anything else | **Literal text**, injected as-is |

The literal fallback means a mistyped *prefix* (`ws/notes`) is injected as text rather than reported. When the intent must be unambiguous, use a map entry with `ref`.

### 6.2 Map entries

| Field | Description |
|-------|-------------|
| `ref` | Reference to resolve (§6.1 rules) |
| `text` | Literal text (mutually exclusive with `ref`) |
| `op` + `input` | Invoke a grid operation; its output is the content |
| `job` + `path?` | A job result, optionally navigated into |
| `label` | Heading; defaults to the ref |
| `required` | Failure throws and fails the cycle |
| `budget` | Render cap for a structured value (§6.4); not an accounting charge |
| `wait` | Job entries: ms to wait for a running job |

An **op entry** runs fresh every inference under the caller's identity, so assemble ops must be **read-only** — a write-on-assemble fires every inference. Operation entries generalise the rest: a workspace read, a cross-venue fetch and a purpose-built assembler are all just operations.

### 6.3 Resolution outcomes

The contract every entry obeys, in either role:

| Outcome | Behaviour |
|---------|-----------|
| Absent / empty / returns null | **Skipped** quietly — context is supplementary; nothing to add adds no noise |
| Resolution **errors** | Visible `[… — unavailable: <reason>]` element, so the model can adapt |
| `required: true` and fails | **Throws** — fails the cycle |
| Malformed `context` value (not an array) | **Throws** — a configuration error to fix, not mask |

*Bad shape → throw; required failure → throw; error → visible; absent → skip.*

### 6.4 Rendering

A **string renders verbatim** (markdown and newlines preserved). A **structured value renders as budget-bounded JSON5**. So an assemble op returns a string when exact formatting matters, a map or vector when a compact view is fine, and `null` to contribute nothing.

### 6.5 Asset resolution

Content payload decoded as UTF-8 if present; else the `description` from metadata (which is why an operation or agent asset makes a usable entry); else skipped.

---

## 7. Loads and the scope chain

Which entries are in play at all.

### 7.1 Two roles, one pipeline

- **Configured** (`config.context`, `config.loads`) — operator-owned, loaded every cycle, **pinned**: the agent may mask it for a conversation but not remove it.
- **Agent-managed** — the working set the agent curates via `context_load` / `context_unload`.

They share the entry grammar, the resolver, the rendering contract and the budget. They differ only in *ownership and lifetime*. The agent can therefore pin a computed result exactly as configuration can — an op entry, a job result, a literal note.

### 7.2 The chain

Tiers, outer → inner:

```
agent (config.loads) → session (sessions.<sid>.loads) → frame (frame.loads, goaltree)
```

Each tier has automatic loads declared when its container is created, plus dynamic entries written while it is the innermost tier. Rules (implemented in `ContextChain`, pure functions):

- **Assembly is a union down the chain; inner shadows outer** on a path collision.
- **Masking:** unloading an outer-tier path writes a nil **tombstone** at the innermost tier — excluded from there inward, leaving the outer entry and every sibling untouched. A later load overwrites the tombstone. goaltree's copy-on-push frame inheritance copies tombstones, so masks propagate to children.
- **Inner tiers read outer tiers, never mutate them** — one writer per tier.
- **Budgets are advisory** — never a basis for silent eviction.
- **No session in scope → no writable tier**: `context_load`/`unload` fail with a diagnosable result.

The chain is resolved immediately before **every** LLM call, including successive calls within one tool loop, so a tool write to an already-loaded path is visible on the next inference without reloading.

### 7.3 Frame scoping

A goaltree subgoal inherits configured context but starts with its own empty agent-managed set, curating loads for its sub-task without polluting parent or siblings. This is the one structural reason the agent-managed store is not a flat agent-level list.

---

## 8. The Spec and runtime profiles

The Spec is the whole interface between a runtime and the assembler. Every runtime fills the same fields; they differ only in what they put in them:

| Field | Meaning | llmagent | goaltree |
|-------|---------|----------|----------|
| `config` | The agent's merged configuration | — | — |
| `capsCtx` | Capability-narrowed request context (§4) | — | — |
| `headNotice` | Runtime text appended to the head, stable within its scope (§5.1) | — (none) | Subgoal notice for child frames |
| `model` | The resolved model profile: `budget`, `options` — including the label dialect the renderer uses | — | — |
| `tools` | The palette (§4): tool definitions in order — harness, configured, loads-contributed | Context tools, configured tools, loads | Harness tools, typed completion tools, configured tools, loads |
| `loads` | The resolved loads snapshot (§4): elements, in chain order | agent → session | agent → session → frame |
| `frames` | What the conversation renders | The session's single frame | The frame stack: ancestors compacted, active frame full |
| `pending` | Results that arrived for this cycle | Job results | Drained into the active frame's conversation (GOAL_TREE.md) |
| `input` | What drives this cycle | Inbox messages | — (none) |
| `toolLoop` | Messages accumulated within this cycle | — | — |
| `task` | What must be completed or failed, rendered last (§5.13) | The open task, if any | The open task, if any (root frame) |
| `unavailable` | Configured tools the palette could not resolve | — | — |
| `now` | The clock | — | — |

A dash means the runtimes agree. **The table is the whole difference.** Anything a runtime needs that is not in it is a signal that the Spec is missing a field, not that the runtime needs its own pipeline.

`agent:context` builds the same Spec and calls the same `assemble`, so an inspected context matches a live inference **by construction** rather than by two call chains agreeing. It takes the hypothetical call — an inbox message or several, pending results, a task, a session — and returns the level-3 input with `cacheMarks`, the `budget` (`bytes`, `used`, `remaining`), the `marks` at each band's end and the label dialect.

`agent:step` takes the same call plus the reply the model would give and runs one harness iteration on it: text-as-control recognised, the tool calls dispatched through the runtime's own registry — same routes, capability checks and authority, so the tools' side effects are real — their results rendered, and the next prompt assembled from the same Spec with this iteration in the tool-loop band. The agent is untouched: a terminal control tool is reported (`terminal`), never resolved; goaltree's `subgoal` is not run. It returns `assistant`, `turns`, `calls` (`id, name, arguments, result, isError?, ms`), `terminal?`, `done`, `response?` and `next?` (an `agent:context` report).

The same bands drive the record a live cycle leaves on its timeline entry (`AGENT_LOOP.md` §2.4): a message is recorded in the inference that first sends it — head and live messages once (a frame's first inference pulls them up as its `context`), the tail as it appears; the root conversation is the session's, a child frame's is recorded as the frame opens, and the tool loop is recorded as replies and calls.

---

## 9. Implementation map

| Concern | Home |
|---------|------|
| `Spec`, `Prompt`, `assemble`, the section functions, attribution | `ContextAssembler` |
| The label renderer (§1.1) | `Labels` |
| The palette (§3.2.3): resolution, merge, unavailable diagnostic | `ToolPalette` |
| The loads phase (§4): elements, contributed tools, routes | `Loads` |
| Entry resolution and rendering (§6) | `ContextLoader` |
| Scope chain algebra (§7) | `ContextChain` |
| Frame rendering, elision, segments, turn normalisation (§5.6) | `ConversationRenderer` |
| Skills index and skill elements | `Skills` |
| Authority and the model profile: `capsContext`, `modelProfileFor` | `AbstractLLMAdapter` |
| The edge (§3.5): `normaliseSystemMessages` | `LangChainAdapter` |
| llmagent spec | `LLMAgentAdapter` |
| goaltree spec | `GoalTreeAdapter` |

### 9.1 Known divergences

Everything above is written as the target; this list is the whole of the current difference.

- **The head and the live surface share one breakpoint.** The client marks only the last block of the system parameter, so a load or unload re-writes the head along with the live surface; a separate head mark means shaping the Anthropic request directly. Breakpoints are the 5-minute ephemeral kind — langchain4j exposes no 1-hour TTL.
- `compact` exists only in the goal-tree harness; llmagent has no compaction, so the 90% line asks there for what it cannot offer.
- **goaltree persists the goal as the frame's opening user turn** and re-appends it after compaction, rather than rendering it in the task slot. Kept deliberately for now: the root frame's "goal" of a chat session is its origin description, which must not be re-read on every inference; the task-slot rendering is right for subgoal frames and is the pending change.
- The agent-facing text of `skill_load` and SKILLS.md §4.3 name the `[Skills]` index by its bracket label whatever the dialect.
