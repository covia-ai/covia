# Agent Context — Assembly Design

How the messages an agent sends to its model are assembled: the output contract, the section sequence, and what each section does.

**Status:** Design — §3's banded sequence and §4's phases are the target shape. The sections themselves all exist; what is being consolidated is the *ordering and ownership*, which currently differ between the two runtimes (see §8).

See [AGENT_LOOP.md](./AGENT_LOOP.md) §3.2 for the surrounding level-2 architecture, [SKILLS.md](./SKILLS.md) for skills, and [AGENT_TEMPLATES.md](./AGENT_TEMPLATES.md) for how configuration is composed.

---

## 1. Overview

Every agent turn ends in one call to an LLM operation with one input:

```json
{
  "model": "claude-sonnet-5",
  "messages": [ {"role": "system", "content": "..."}, ... ],
  "tools": [ ... ]
}
```

`messages` is a **flat array of `{role, content}`** — two keys, both strings. There is no nested structure, no typed section, no ordering metadata. Everything that looks structured to the model is a **labelled text element** inside a content string.

So assembly is: *compute each element, in order, and concatenate*. The value of the design is not in the data structure — it is in **which elements, in what order, produced by whom**.

This document covers that. The grammar of what an individual context entry may be lives in §6; the scope chain that decides which entries are in play lives in §7.

### 1.1 Element labels

Elements are self-describing by a label convention, because substring recognition is genuinely the interface between the prompt and everything that reads it back:

| Form | Used by |
|------|---------|
| `[Skills]` | the skills index |
| `[Skill: <name> — <path>]` | a loaded skill; the path is its unload key |
| `[Context: <label>]` | a rendered context entry |
| `[<label> — unavailable: <reason>]` | any element whose source failed |

**One function renders these.** They were previously built by string concatenation in three files, which is why changing a header broke six tests and a substring probe in `TestAdapter`. A single renderer over `{label, key?, body}` makes the convention one definition.

---

## 2. Design goals

1. **One assembly, every runtime.** The order is defined once. A runtime chooses *what it puts in*, never *where it lands*. Divergence should be impossible to express, not merely discouraged.

2. **Cache-efficient by construction.** Providers cache on a prefix. Anything that changes invalidates everything after it, so elements are ordered by **how often their bytes change** — stable first, volatile last. This is a property of the sequence, not of anyone remembering to be careful.

3. **Maintainable: sections are functions.** Each section is a function of a spec returning messages. It can be read, tested and changed alone. Assembly is a mutable accumulator that knows only *append* and *bytes so far* — it holds no subsystem knowledge.

4. **Different agent setups supported by input, not by forking the pipeline.** goaltree renders a frame stack and synthesises a goal message; llmagent renders a session frame stack and an inbox. Both hand the assembler messages.

---

## 3. The section sequence

### 3.1 Bands

Four bands, ordered by change frequency. The band is the *reason* an element sits where it does:

| Band | Changes when | Cache consequence |
|------|--------------|-------------------|
| **Fixed head** | configuration changes | Identical across turns — the cacheable prefix |
| **Live surface** | the agent loads or unloads | Stable within a task; moves only on an explicit act |
| **Conversation** | a turn is added | Append-only, so the prefix survives |
| **Volatile tail** | every turn, or daily | Must be last: invalidates only itself |

**The rule for placing a new section:** it goes in the earliest band whose change frequency it does not exceed. That is the whole answer to "where does this go", and it is what was missing when the current-date element was added — its own comment argues correctly about prefix caching, but there was no band for it to belong to.

### 3.2 The sequence

| # | Section | Band | Contents |
|---|---------|------|----------|
| 1 | Identity prompt | fixed head | `config.systemPrompt` or the default identity |
| 2 | Lattice reference | fixed head | Namespace and addressing cheat sheet |
| 3 | Capability notice | fixed head | Declared `config.caps`, so bounds are known before they are hit |
| 4 | Pinned context | live surface | `config.context` entries (§6) — operator-owned, agent cannot drop |
| 5 | Skills index | live surface | `[Skills]` — one line per discoverable skill (SKILLS.md §4.3) |
| 6 | Loaded elements | live surface | Every effective load (§7), each with its unload key |
| 7 | Conversation | conversation | The rendered frame stack / session history |
| 8 | Pending results | conversation | Job results that arrived for this transition |
| 9 | Current input | conversation | The inbox message(s) driving this turn |
| 10 | Tool-loop messages | conversation | Assistant/tool turns accumulated within this transition |
| 11 | Outstanding task | volatile tail | The task the agent must complete or fail |
| 12 | Budget warning | volatile tail | **Only** when the loads budget is under pressure |
| 13 | Current date | volatile tail | One line; changes daily |
| 14 | Unavailable tools | volatile tail | Configured tools that did not resolve this turn |

### 3.3 The top-level function

The sequence above is not a description of the code — it *is* the code:

```java
static Prompt assemble(Engine engine, RequestContext ctx, Spec spec) {
    Prompt p = new Prompt(spec.budget());

    // Fixed head — identical every turn; the cacheable prefix
    p.add(identityPrompt(spec));
    p.add(latticeReference(spec));
    p.add(capabilityNotice(spec));

    // Live surface — re-resolved each turn; moves only when loads move
    p.add(pinnedContext(engine, ctx, spec, p.remaining()));
    p.add(skillsIndex(engine, ctx, spec));
    p.add(loadedElements(spec));

    // Conversation — append-only
    p.add(conversation(spec));
    p.add(pendingResults(spec));
    p.add(currentInput(spec));
    p.add(toolLoopMessages(spec));

    // Volatile tail — changes every turn; last, so it busts only itself
    p.add(outstandingTask(spec));
    p.add(budgetWarning(p.remaining()));
    p.add(currentDate());
    p.add(unavailableTools(spec));

    return p;
}
```

`Prompt` is a mutable accumulator: `add(messages)` appends and tracks bytes, `remaining()` reports what is left. It knows nothing about skills, tools or capabilities. Every section is a plain function returning messages, and an empty return contributes nothing.

`p.remaining()` is passed explicitly in the two places that genuinely need it — §5.4, which sizes structured rendering from it, and §5.12, which reports on it. That is the *only* order-dependence in the system, and two visible arguments state it more honestly than a running total threaded invisibly through every method.

---

## 4. Three phases

Assembly is the last of three steps, because the first two produce inputs it needs:

```
resolveAuthority(config, ctx)   →  caps, capsCtx
resolvePalette(config, harness) →  tools, routes, unavailable
assemble(spec)                  →  messages
```

- **Authority** must come first: loads resolve *under the agent's capability-narrowed context*, so `capsCtx` is a prerequisite for reading anything.
- **Palette** must precede loads resolution too: loads-contributed tools are deduplicated against the names already fixed by config and harness tools.
- **Assembly** then has everything and produces only messages.

These are three functions with three return values. Fusing them is what produced a nine-field result object where one field was context and the rest were tool maps, capability contexts, config passthrough and byte counters — and what forces a runtime to `build()` a half-context purely to extract `capsCtx`.

---

## 5. Section notes

### 5.1 Identity prompt
`config.systemPrompt`, else a default identity. Rebuilt every turn from live config, so an `agent_update` applies on the next turn with no freeze-on-first-use caching.

### 5.2 Lattice reference
The namespace and addressing cheat sheet. Always present, so every agent knows the prefixes and resolution rules. Fixed text — pure prefix, ideal cache material.

### 5.3 Capability notice
Rendered only when `config.caps` is declared. Without it an agent discovers its boundaries by hitting them, which wastes a turn and produces a confusing denial. This states them up front.

### 5.4 Pinned context
`config.context` entries, resolved through the entry grammar of §6. Operator-owned: the agent may mask an entry for a conversation but cannot remove it.

Structured values are rendered as budget-bounded JSON5, sized from the remaining budget (`max(MIN_ENTRY_BUDGET, remaining/20)`) so early entries cannot starve later ones. Strings render verbatim.

### 5.5 Skills index
One line per discoverable skill, with `(loaded)` against those already in context. Resolved fresh each turn from the agent's effective sources. Absent entirely when the agent declares no skill sources. See SKILLS.md §4.3.

### 5.6 Loaded elements
Every entry in the effective loads chain (§7), rendered through the same resolver as pinned context. A skill-flagged entry renders `[Skill: <name> — <path>]` plus its body; other entries render `[Context: <label>]`.

Each element carries its **unload key** in its header. `context_unload` takes a path, and a skill is otherwise only ever named, so without this the key is invisible. A non-skill load never had the problem — its label is its ref.

Failures are visible, never silent: a load that stops resolving renders `[… — unavailable: <reason>]` rather than vanishing, because a missing element changes behaviour too much to hide.

### 5.7 Conversation
Runtime-supplied. llmagent renders its session frame stack; goaltree renders the active frame via `ConversationRenderer`. The assembler takes messages and does not know the difference.

### 5.8 Pending results
Job results that completed for this transition — the mechanism by which asynchronous work re-enters the conversation.

### 5.9 Current input
The inbox message(s) driving this turn. goaltree synthesises a goal description here instead; same slot, different producer.

### 5.10 Tool-loop messages
Assistant and tool messages accumulated *within* this transition. These are conversation, not preamble — placing them in-band rather than concatenating them after assembly is what keeps the ordering honest.

### 5.11 Outstanding task
Present only when the agent has a task it must complete or fail. Volatile: it appears and disappears within a transition, so it sits after the conversation rather than inside the cacheable region.

### 5.12 Budget warning
**Silent unless the loads budget is under pressure** (≥70%). Budgets are advisory: byte accounting guides the model, but the runtime never silently evicts based on a guessed provider token ratio. A per-turn inventory used to print here and was removed — it restated the elements rendered immediately above, and its byte counts invalidated the cache for everything after it.

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

An **op entry** runs fresh every turn under the caller's identity, so assemble ops must be **read-only** — a write-on-assemble fires every turn. Operation entries generalise the rest: a workspace read, a cross-venue fetch and a purpose-built assembler are all just operations.

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
| `conversation` | Session frame stack | Active frame via `ConversationRenderer` |
| `currentInput` | Inbox messages | Synthesised goal description |
| `harnessToolNames` | Context tools | The harness registry (`subgoal`, `complete`, …) |
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
| Skills index and skill elements | `Skills` |
| llmagent spec | `LLMAgentAdapter` |
| goaltree spec | `GoalTreeAdapter` |

### 9.1 Known divergences to close

Recorded so the reshaping has an acceptance test:

- goaltree injects the **current date before loads and the conversation**, so once a day it invalidates both. Under §3.1 this is a band violation, visible by reading.
- Both runtimes assemble a partial context and then concatenate the rest imperatively, so §3.2's ordering is currently only half-enforced.
- goaltree constructs a context in three separate methods, including its inspection path — the invariant that inspection matches inference is maintained by agreement rather than by construction.
