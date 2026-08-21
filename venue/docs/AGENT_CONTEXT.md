# Agent Context — Assembly Design

How the messages an agent sends to its model are assembled: the output contract, the section sequence, and what each section does.

**Status:** Design. The body describes the **target** shape in the present tense, so it can become the reference without a rewrite; the code is being converged on it. Where the two differ today, the difference is recorded in §9.1 and nowhere else.

See [AGENT_LOOP.md](./AGENT_LOOP.md) §3.2 for the surrounding level-2 architecture, [SKILLS.md](./SKILLS.md) for skills, [AGENT_TEMPLATES.md](./AGENT_TEMPLATES.md) for how configuration is composed, and [OPERATIONS.md](./OPERATIONS.md) (*The `model` facet*) for what a model declares about itself.

---

## 1. Overview

Every inference — one per call of the tool loop — is one call to an LLM operation with one input:

```json
{
  "model": "claude-sonnet-5",
  "messages": [ {"role": "system", "content": "..."}, ... ],
  "tools": [ ... ]
}
```

`messages` is a **flat array of `{role, content}`**. There is no nested structure, no typed section, no ordering metadata. Everything that looks structured to the model is a **labelled text element** inside a content string. Two message kinds carry more than a string, and the assembler passes both through untouched: an assistant message may carry `toolCalls`, and a user message may carry an array of content blocks for vision.

`tools` is part of the prompt too. Providers that cache do so over tools, system and messages **in that order**, so the tools array is the head of the cacheable prefix. It is ordered deterministically — harness tools, then configured tools, then tools contributed by loads — and a change to any definition invalidates everything after it. A skill that contributes tools is therefore a heavier load than one that contributes text.

So assembly is: *compute each element, in order, and concatenate*. The value of the design is not in the data structure — it is in **which elements, in what order, produced by whom**.

**The unit of assembly is one inference, not one turn.** Within a tool loop the whole prompt is rebuilt before every call: loads are re-read, so a tool's write to a loaded path is visible on the next inference; the tool-loop messages so far are appended in band; the tail is rendered again after them.

This document covers that. The grammar of what an individual context entry may be lives in §6; the scope chain that decides which entries are in play lives in §7.

### 1.1 Element labels

Elements are self-describing by a label convention, because substring recognition is genuinely the interface between the prompt and everything that reads it back — the model, `agent:context` inspection, and the tests. The complete set:

| Label | Section |
|-------|---------|
| `[Skills]` | skills index; one line per skill, `(loaded)` against those in context |
| `[Skill: <name> — <path>]` | a loaded skill; the path is its unload key |
| `[Context: <label>]` | a rendered context entry; the label is its unload key |
| `[Context: <label> — unavailable: <reason>]` | an entry whose source failed |
| `[Compacted: <N> turns] <summary>` | a compacted conversation segment |
| `[Pending job results]` | results that arrived for this transition |
| `[Tasks assigned to you]` | the outstanding task |
| `[No pending tasks, messages, or job results. …]` | the empty-state signal |
| `[Context budget] <pct>% …` | the budget warning |
| `[Configured tools unavailable in this session. …]` | unavailable tools |
| `Current date: <date>.` | the date |

**One function renders these.** A label is one definition over `{label, key?, body}`; a section never concatenates its own header. Changing a label then changes it everywhere it is read back, including the tests that probe for it.

---

## 2. Design goals

1. **One assembly, every runtime.** The order is defined once. A runtime chooses *what it puts in*, never *where it lands*. Divergence should be impossible to express, not merely discouraged.

2. **Cache-efficient by construction.** Providers cache on a prefix. Anything that changes invalidates everything after it, so elements are ordered by **how often their bytes change** — stable first, volatile last. This is a property of the sequence, not of anyone remembering to be careful.

3. **Maintainable: sections are functions.** Each section is a function of a spec returning messages. It can be read, tested and changed alone. Assembly is a mutable accumulator that knows only *append* and *bytes so far* — it holds no subsystem knowledge.

4. **Different agent setups supported by input, not by forking the pipeline.** goaltree renders a frame stack and synthesises a goal message; llmagent renders a session frame and an inbox. Both hand the assembler messages.

5. **Flexible over providers**, to support the quirks of different LLMs. Sections never know which provider they are writing for. The facts about a provider that change how a prompt must be shaped or sized — whether separate system messages survive to the wire, whether a user message is required, whether a prefix is cached, how much context is appropriate — are declared as data on the model's operation asset (the `model` facet) and applied at the edge: the assembler emits output legal for the strictest declared provider, and the level-3 adapter folds it to what each API accepts. Adding a provider means declaring its facts, not branching in a section.

---

## 3. The section sequence

### 3.1 Bands

Four bands, ordered by change frequency. The band is the *reason* an element sits where it does:

| Band | Changes when | Cache consequence |
|------|--------------|-------------------|
| **Fixed head** | configuration changes | Identical across turns — the cacheable prefix |
| **Live surface** | the working set changes, or the data it reflects changes | Stable while the agent's loads and their sources are stable |
| **Conversation** | a turn is added | Append-only within a cycle; rewritten only by the two sanctioned rewrites (§5.7) |
| **Volatile tail** | every inference, or daily | Must be last: invalidates only itself |

**The rule for placing a new section:** it goes in the earliest band whose change frequency it does not exceed. That is the whole answer to "where does this go". A section that cannot name its band has not been placed; it has been appended.

The band is the *expected* change frequency. Content can be more volatile than its band: an op entry in pinned context that returns a listing changes whenever the listing does, and busts everything after it from the live surface down. The ordering cannot prevent that; it can only make the cost legible. Within `config.context`, entries render in declared order, so stable entries go first.

### 3.2 The sequence

| # | Section | Band | Role | Contents |
|---|---------|------|------|----------|
| 1 | Identity prompt | fixed head | `system` | `config.systemPrompt` or the default identity, plus one line of session identity |
| 2 | Lattice reference | fixed head | `system` | Namespace and addressing cheat sheet |
| 3 | Capability notice | fixed head | `system` | Declared `config.caps`, so bounds are known before they are hit |
| 4 | Pinned context | live surface | `system` | `config.context` entries (§6) — operator-owned, agent cannot drop |
| 5 | Skills index | live surface | `system` | `[Skills]` — one line per discoverable skill (SKILLS.md §4.3) |
| 6 | Loaded elements | live surface | `system` | Every effective load (§7), each with its unload key |
| 7 | Conversation | conversation | `user` / `assistant` | The rendered frame(s) |
| 8 | Pending results | conversation | `user` | Job results that arrived for this transition |
| 9 | Current input | conversation | `user` | The inbox message(s) driving this turn |
| 10 | Tool-loop messages | conversation | `assistant` / `tool` | Assistant/tool turns accumulated within this transition |
| 11 | Outstanding task | volatile tail | `user` | The task the agent must complete or fail |
| 12 | Budget warning | volatile tail | `system` | **Only** when the budget is under pressure |
| 13 | Current date | volatile tail | `system` | One line; changes daily |
| 14 | Unavailable tools | volatile tail | `system` | Configured tools that did not resolve this turn |
| — | Empty-state signal | volatile tail | `user` | Replaces 8–11 when there is nothing to act on |

Sections 1–3 are composed into a **single** `system` message. They change together, are always all present, and form the prefix every provider caches first: one message is the cache unit, and it does not depend on how a provider treats consecutive system messages.

### 3.2.1 The role rule

**Standing instruction and reference are `system`. What has happened, or must be acted on now, is `user`.** That is why pending results, the outstanding task, the empty-state signal and goaltree's synthesised goal are all `user` turns: they represent events arriving, not instructions.

Two constraints make the roles load-bearing rather than cosmetic — both declared per provider in `model.options`:

- **A system-only request is illegal on some providers** (`requiresUserMessage`; Anthropic's Messages API rejects one). An agent triggered with no input would fail if every element were `system`. The `user`-role empty-state signal keeps such a turn legal on every provider, so it is emitted unconditionally — do not "tidy" it into a system message.
- **Multiple `system` messages are a fiction at the wire** on providers with one system parameter (`systemMessages: "single"`; Anthropic and Gemini among them). Each becomes a `SystemMessage` and they are concatenated. The message boundary between `[Skills]` and `[Skill: …]` therefore carries no semantics downstream — only the §1.1 text labels do. That is what the label convention is for, and why it must stay disciplined.

**Open question — data vs instruction.** Pinned context and loaded elements are `system` today, which hands a workspace document, a job result or a skill body *instruction authority*. SKILLS.md §11 already flags the trust assumption; the role choice is what makes it maximal. The defensible alternative is `system` for venue- and operator-authored instruction (identity, lattice, capabilities, skills index, skill bodies) and `user` for resolved data (documents, job results, op output). It also removes an accident: a job result reaches the model as `system` when loaded via `context_load {job: …}` but as `user` when it arrives as a pending result. Not changed yet — it alters how models weight loaded content.

### 3.3 The top-level function

The sequence above is not a description of the code — it *is* the code:

```java
static Prompt assemble(Spec spec) {
    Prompt p = new Prompt(spec.budget());

    // Fixed head — identical every turn; the cacheable prefix
    p.add(identityPrompt(spec));
    p.add(latticeReference());
    p.add(capabilityNotice(spec));

    // Live surface — re-resolved each inference; moves only when loads move
    p.add(pinnedContext(spec, p.remaining()));
    p.add(skillsIndex(spec));
    p.add(loadedElements(spec));

    // Conversation — append-only
    p.add(conversation(spec));
    p.add(pendingResults(spec));
    p.add(currentInput(spec));
    p.add(toolLoopMessages(spec));

    // Volatile tail — changes every inference; last, so it busts only itself
    p.add(outstandingTask(spec));
    p.add(budgetWarning(p.remaining()));
    p.add(currentDate());
    p.add(unavailableTools(spec));

    return p;
}
```

Everything a section needs is in the `Spec`: the config; the capability-narrowed context (`capsCtx`, §4) under which pinned context and the skills index resolve; the loads snapshot already resolved in §4; the frames; this transition's inputs; the palette's unavailable list. `assemble` takes no engine and no raw request context — a section that reads the lattice reads it under the agent's authority or not at all.

`Prompt` is a mutable accumulator: `add(messages)` appends and tracks bytes, `remaining()` reports what is left. It knows nothing about skills, tools or capabilities. Every section is a plain function returning messages, and an empty return contributes nothing.

`spec.budget()` is the model's declared context budget — `model.budget.bytes` on the LLM operation asset, resolved for the agent's model (OPERATIONS.md, *The `model` facet*) — falling back to `ContextBuilder.DEFAULT_BUDGET`. The size of the context is a fact about the model, declared beside the model, not a venue-wide constant.

`p.remaining()` is passed explicitly in the two places that genuinely need it — §5.4, which sizes structured rendering from it, and §5.12, which reports on it. That is the *only* order-dependence in the system, and two visible arguments state it more honestly than a running total threaded invisibly through every method.

---

## 4. Four phases

Assembly is the last of four steps; each produces an input the next needs:

```
resolveAuthority(config, ctx)                 →  capsCtx
resolveLoads(engine, capsCtx, chain, fixed)   →  elements, loadTools, routes
resolvePalette(config, harness, loadTools)    →  tools, routes, unavailable
assemble(spec)                                →  messages
```

- **Authority** first: everything that reads the lattice reads under the agent's capability-narrowed context.
- **Loads** next, and they produce more than messages: a loaded skill contributes tool operations and their routes. The snapshot is resolved **once per inference** and never persisted.
- **Palette** merges harness tools, configured tools and loads-contributed tools, in that order; a name already fixed by harness or config is never shadowed by a load. The palette also knows which configured tools failed to resolve.
- **Assembly** then has everything and produces only messages.

Four functions, four return values. A runtime that needs `capsCtx` calls `resolveAuthority`; it does not build a context to extract it.

---

## 5. Section notes

### 5.1 Identity prompt
`config.systemPrompt`, else a default identity, followed by one line of session identity: the venue name, the model when configured, and the session id when one is in scope — the agent's handle for reporting back into this conversation from deferred work. Rebuilt every turn from live config, so an `agent_update` applies on the next turn with no freeze-on-first-use caching. Nothing that changes within a session belongs here.

### 5.2 Lattice reference
The namespace and addressing cheat sheet. Always present, so every agent knows the prefixes and resolution rules. Fixed text — pure prefix, ideal cache material.

**Head discipline:** the fixed head holds what every turn needs and nothing more. It is cached, but providers without caching pay for it on every turn; depth belongs in skills, loaded when needed (SKILLS.md).

### 5.3 Capability notice
Rendered only when `config.caps` is declared. Without it an agent discovers its boundaries by hitting them, which wastes a turn and produces a confusing denial. This states them up front.

### 5.4 Pinned context
`config.context` entries, resolved through the entry grammar of §6. Operator-owned: the agent may mask an entry for a conversation but cannot remove it.

Structured values are rendered as budget-bounded JSON5; any one entry is capped at a twentieth of the budget remaining when it renders (`max(MIN_ENTRY_BUDGET, remaining/20)`), so no single entry can consume the context. Strings render verbatim.

### 5.5 Skills index
One line per discoverable skill, with `(loaded)` against those already in context. Resolved fresh each turn from the agent's effective sources. Absent entirely when the agent declares no skill sources. See SKILLS.md §4.3.

### 5.6 Loaded elements
Every entry in the effective loads chain (§7), rendered through the same resolver as pinned context and re-read from the lattice on every inference (§7.2). A skill-flagged entry renders `[Skill: <name> — <path>]` plus its body; other entries render `[Context: <label>]`.

Each element carries its **unload key** in its header. `context_unload` takes a path, and a skill is otherwise only ever named, so without this the key is invisible. A non-skill element's label is its ref, so it already carries its key.

Failures are visible, never silent: a load that stops resolving renders `[… — unavailable: <reason>]` rather than vanishing, because a missing element changes behaviour too much to hide.

### 5.7 Conversation
Runtime-supplied frames, rendered by one `ConversationRenderer` for both runtimes. llmagent supplies its session's single frame; goaltree supplies its frame stack, ancestors first at decreasing budgets and the active frame last. The assembler does not know the difference.

The band is append-only **within a cycle**: every inference of a tool loop sees exactly what the previous one saw, plus the new tool-loop messages. Across cycles there are exactly two sanctioned rewrites, both deliberate trades of cache for context size:

- **Scratch elision** (the default; `renderHistory: "full"` opts out). A completed cycle renders as its user input and final assistant reply; its tool calls and tool results are dropped *together*, because providers require a call and its result to be both present or both absent. The rewrite touches only the most recently completed cycle, so the cache survives to the start of that cycle's scratch.
- **Compaction.** A range of turns collapses into a `[Compacted: N turns] summary` segment whose summary the agent wrote (GOAL_TREE.md). It rewrites from the segment onward, so it should happen in coarse steps, not every turn.

Everything else in the band is an append.

### 5.8 Pending results
Job results that completed for this transition — the mechanism by which asynchronous work re-enters the conversation. Placed before the current input so that the input, the thing to act on, is closest to the reply.

### 5.9 Current input
The inbox message(s) driving this turn. goaltree synthesises a goal description here instead; same slot, different producer.

### 5.10 Tool-loop messages
Assistant and tool messages accumulated *within* this transition. These are conversation, not preamble: they sit inside the band, before the tail, so each inference of the loop shares its prefix with the previous one through the last tool result and only the tail is re-rendered.

### 5.11 Outstanding task
Present only when the agent has a task it must complete or fail. Rendered after the tool-loop messages on every inference, so it is the last thing before the reply, and never baked into history — the model sees only tasks still outstanding.

### 5.12 Budget warning
**There is one budget:** the model's declared context size (`model.budget.bytes`, §3.3), counted in encoded bytes (`Cells.storageSize`, approximately UTF-8 bytes) over everything the accumulator has been given. Three things follow from it and nothing else does:

- per-entry render sizing (§5.4);
- this warning, **silent below 70%** — one line saying the budget is filling and that each element's header carries its unload key;
- **never eviction.** Byte accounting is not a token bound across providers, so the runtime never silently removes context. When a provider rejects the prompt as too large, the transition fails with a message naming the approximate size and pointing at `agent:context` and `context_unload`.

An inventory of what is loaded does not belong here: it restates the elements rendered above, and its byte counts change every turn.

### 5.13 Current date
One line, changing daily. Kept out of the system prompt precisely so the cacheable head contains no changing value; in the tail it busts only itself.

### 5.14 Unavailable tools
Configured tools that did not resolve this turn — reported so the agent adapts rather than calling into a void. Resolution is live, so a fixed path or restored grant makes the tool available on the next turn with no recreate.

---

## 6. Context entries — the grammar

The entry model shared by pinned context (§5.4), loaded elements (§5.6), and `context_load`.

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
| `required` | Failure throws and fails the turn |
| `budget` | Advisory per-entry byte budget |
| `wait` | Job entries: ms to wait for a running job |

An **op entry** runs fresh every inference under the caller's identity, so assemble ops must be **read-only** — a write-on-assemble fires every inference. Operation entries generalise the rest: a workspace read, a cross-venue fetch and a purpose-built assembler are all just operations.

### 6.3 Resolution outcomes

The contract every entry obeys, in either role:

| Outcome | Behaviour |
|---------|-----------|
| Absent / empty / returns null | **Skipped** quietly — context is supplementary; nothing to add adds no noise |
| Resolution **errors** | Visible `[… — unavailable: <reason>]` element, so the model can adapt |
| `required: true` and fails | **Throws** — fails the turn |
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

- **Configured** (`config.context`, `config.loads`) — operator-owned, loaded every turn, **pinned**: the agent may mask it for a conversation but not remove it.
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

## 8. Runtime profiles

Both runtimes call the same assembler. They differ only in what they put in the spec:

| Spec field | llmagent | goaltree |
|------------|----------|----------|
| `frames` | The session's single frame | The goal-tree frame stack: ancestors compacted, active frame full |
| `currentInput` | Inbox messages | Synthesised goal description |
| `pendingResults` | Job results for this transition | Drained into the active frame's conversation (GOAL_TREE.md) |
| `harnessToolNames` | Context tools (`context_load`, `skill_load`, …) | The harness registry (`subgoal`, `complete`, …) plus the context tools |
| `effectiveLoads` | agent → session | agent → session → frame |
| `outstandingTask` | Task message when one is open | Typed completion tools instead |

**This is the whole difference.** Anything a runtime needs that is not in this table is a signal that the spec is missing a field, not that the runtime needs its own pipeline.

`agent:context` inspection uses the same assembler with the same spec, so an inspected context matches a live inference **by construction** rather than by two call chains agreeing.

---

## 9. Implementation map

| Concern | Home |
|---------|------|
| Section functions, `Prompt` accumulator, `assemble` | `ContextBuilder` (to be reshaped) |
| Entry resolution and rendering (§6) | `ContextLoader` |
| Scope chain algebra (§7) | `ContextChain` |
| Frame rendering, elision, segments (§5.7) | `ConversationRenderer` |
| Skills index and skill elements | `Skills` |
| Model facts: `model.options`, `model.budget` | `AbstractLLMAdapter.modelProfile` |
| llmagent spec | `LLMAgentAdapter` |
| goaltree spec | `GoalTreeAdapter` |

### 9.1 Known divergences to close

Recorded so the reshaping has an acceptance test. Everything above is written as the target; this list is the whole of the current difference.

- **Two builders, two budgets.** Loads are resolved by a second `ContextBuilder` with its own default budget, so the budget warning measures the loads alone rather than the prompt, and `model.budget.bytes` is consumed by nothing yet.
- **The budget warning is emitted inside the load snapshot** — in the live surface, before the conversation — not in the tail. Under §3.1 it is a band violation, of the same kind as the inventory it replaced.
- goaltree injects the **current date before loads and the conversation**, so once a day it invalidates both.
- llmagent appends **tool-loop messages and the outstanding task after the tail** (date, unavailable tools), so within a tool loop the tail is not last.
- Both runtimes build a partial context and then concatenate the rest imperatively, so §3.2's ordering is only half-enforced and §4's phases interleave (palette → loads → palette again).
- goaltree constructs a context in three separate methods, including its inspection path; the invariant that inspection matches inference is maintained by agreement rather than by construction.
- Labels are built by string concatenation in several places (`ContextBuilder`, `ContextLoader`, `Skills`, `LLMAgentAdapter`), not by one renderer.
- Stale references to the removed `[Context Map]` remain in a `ContextBuilder` comment and in GOAL_TREE.md's *Context Assembly Layout*.
