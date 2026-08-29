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

`messages` is a **flat array of messages** in four roles — `system`, `user`, `assistant`, `tool`. Most carry text in `content`; a tool result may instead carry its typed map/vector in `structuredContent`. An assistant message may carry `toolCalls`, a `tool` message carries its matching call id and result, and a user message may carry an array of content blocks for vision.

`tools` is part of the prompt too — section 0 of the sequence (§3.2.3): every provider serialises the definitions ahead of the messages, so they are the first bytes of the cached prefix.

So assembly is: *compute each element, in order, and concatenate*. The value of the design is not in the data structure — it is in **which elements, in what order, produced by whom**.

**The unit of submission is one inference.** The stable representation is simpler: a fixed tool vector and one append-only message vector. Each inference reuses them, appends the current turn, and renders only the volatile tail again. Persistent sources are resolved when their load is appended, not polled and re-rendered before every call.

**Assembly is a pure function**: `assemble(Spec) → Prompt`. The Spec (§8) carries everything, the clock included; the assembler reads nothing else. The same Spec yields the same Prompt, which is what makes `agent:context` inspection exact and every section testable without a venue.

This document covers that. The grammar of what an individual context entry may be lives in §6; the scope chain that decides which entries are in play lives in §7.

### 1.1 Instruction labels and data boundaries

Labels are for **instruction and diagnostic blocks**, not for untrusted loaded data. Operator-trusted context and skill bodies use them in `system` messages. Data has a stronger interface: an injected assistant tool call followed by a provider-native `tool` result whose `structuredContent` carries source, label and content/error/absence. Adding textual wrappers around the same data duplicates metadata, costs tokens and blurs the security boundary.

The label renderer therefore handles only blocks that genuinely travel as instructions or requests, in one of three dialects chosen by the model's declared `labels` option: `bracket` (the default), `xml` or `header`:

| Element | `bracket` (default) | `xml` | `header` |
|---------|---------------------|-------|----------|
| Skills index — one line per skill, `(loaded)` against those in context | `[Skills]` | `<skills>` | `## Skills` |
| Pinned skill — no unload handle | `[Pinned skill: <name> — <source>]` | `<pinned-skill name="…" source="…">` | `## Pinned skill: <name> — <source>` |
| Agent-loaded skill — the path is its unload key | `[Loaded skill: <name> — unload key: <path>]` | `<loaded-skill name="…" unload-key="…">` | `## Loaded skill: <name> — unload key: <path>` |
| Trusted operator context | `[Pinned context: <label>]` | `<pinned-context label="…">` | `## Pinned context: <label>` |
| Late system message (§3.2.1) | `[system: …]` | `<system>` | `## System` |
| Compacted conversation segment | `[Compacted: <N> turns] <summary>` | `<compacted turns="…">` | `## Compacted: <N> turns` |
| Ancestor context (goal tree) | `[Ancestor Context]` | `<ancestors>` | `## Ancestor context` |
| Tool-failure diagnostic | `[Tool failure: <name>] <reason>` | `<tool-failure name="…">` | `## Tool failure: <name>` |
| Outstanding task | `[Tasks assigned to you]` | `<tasks>` | `## Tasks assigned to you` |
| Empty-state signal | `[No pending tasks, messages, or job results. …]` | `<no-input>` | `## No input` |
| Budget warning | `[Context budget] <pct>% …` | `<context-budget>` | `## Context budget` |
| Unavailable tools | `[Configured tools unavailable in this session. …]` | `<unavailable-tools>` | `## Unavailable tools` |
| Current date | `Current date: <date>.` | `Current date: <date>.` | `Current date: <date>.` |

A block is its label followed by its body; in `xml` the body is followed by the closing tag. A one-line element — the date — is a line in every dialect. Fields interpolated into an instruction label come only from operator configuration or validated runtime metadata and are escaped for the chosen dialect. Labels on untrusted declarations remain data fields in tool results.

`bracket` is cheap in tokens, reads naturally in logs, and is plain text. `xml` marks where an instruction block ends as well as where it begins. `header` suits models whose guidance favours markdown sections, at the cost of competing with headings inside an instruction body. A model that benefits from either alternative opts in on its asset. The dialect is fixed for a persisted context; changing it rebuilds the prefix deliberately.

**Prompt-injection boundary.** Trust and lifetime are independent: `pinned` says who owns/removes an entry; `trusted` says whether its resolved bytes have instruction authority. Untrusted values stay under `content` in `structuredContent`, alongside sibling provenance fields, and the edge preserves the native call/result boundary. A provider that accepts only textual tool content receives canonical JSON in the `tool` role; it is never flattened into instruction text. Only an operator declaration can admit ordinary context as trusted. A skill body is also instruction because `skill_load` explicitly selects a trusted skill asset; data bundled by the skill still travels as a tool result. This is defence in depth, not a security decision delegated to the model: capabilities and dispatch checks remain authoritative even if the model follows malicious text.

---

## 2. Design goals

1. **One assembly, every runtime.** The order is defined once. A runtime chooses *what it puts in*, never *where it lands*. Divergence should be impossible to express, not merely discouraged.

2. **Cache-efficient by construction.** The rendered message vector is deterministic and append-only. Existing cells are reused byte-for-byte; ordinary change appends, and only a small set of explicit operations may rebuild the prefix (§3.1). This is a property of the representation, not of anyone remembering to be careful.

3. **Maintainable: sections are functions.** Each section is a function of the Spec returning messages. It can be read, tested and changed alone. Assembly is a mutable accumulator that knows only *append*, *mark* and *bytes so far* — it holds no subsystem knowledge.

4. **Different agent setups supported by input, not by forking the pipeline.** goaltree renders a frame stack and carries the frame's goal as its task; llmagent renders a session frame, an inbox and its assigned tasks. Both hand the assembler a Spec.

5. **Flexible over providers**, to support the quirks of different LLMs. Sections never know which provider they are writing for. The facts about a provider that change how a prompt must be shaped or sized — whether it has a system role, whether a prefix is cached, how much context is appropriate — are declared as data on the model's operation asset (the `model` facet) and applied at the edge (§3.5) — or, for the label dialect, by the one renderer (§1.1). The assembler emits output legal for every declared provider; adding a provider means declaring its facts, not branching in a section.

---

## 3. The section sequence

### 3.1 Stable prefix and cache boundaries

There is no separate cache lifecycle object. Persist exactly two stable values: the fixed tool vector and the rendered message vector. The latter begins with the identity and initial context, then grows in the order the model sees conversation, loads, skills and tool-state changes:

```
tools + messages + workingTurn + volatileTail
```

`tools`, `messages` and `workingTurn` are immutable CVM vectors. Completing a cycle is `messages = messages.concat(workingTurn)`; adding a load or other dynamic change is the same append operation. Assembly does not reconstruct those vectors from mutable configuration, workspace paths, a skill registry or an adapter registry.

No parallel cache-key system is needed. Compare successive tool vectors with ordinary Convex equality; when they are equal, locate the reusable message prefix with the vectors' common-prefix operation. Both are hash-accelerated by the CVM representation. If the tool vectors differ, the reusable provider prefix starts at zero and the change must have been an explicit rebuild. Tool-call ids required by a provider are persisted transport references pairing a call with its result; they are not cache identities and are never regenerated while replaying a call.

Determinism applies when the venue renders a cell: tool and entry order comes from canonical declared/load order, structured data uses the canonical renderer, and inference-local clocks, registry iteration order, random synthetic ids and byte counts never enter that rendering. Provider-returned call ids are conversation data and are persisted verbatim rather than regenerated. Given equal starting vectors and an equal appended change, rendering produces equal result vectors.

**Cache boundaries** are indices into this representation. On a provider that caches an explicitly marked prefix (`cachePrefix`), the edge may mark the end of the initial head, `messages` as it stood when the cycle began, and `workingTurn` as it grows; `volatileTail` is never marked. A provider may impose a smaller breakpoint limit, but it must not change logical order to accommodate it.

Only an intentional prefix rebuild invalidates earlier cells:

- explicit compaction or conversation reset;
- a hard security removal that must make old content absent rather than merely inactive;
- a change to the model, provider-shaping profile, label dialect, base system prompt or fixed tool/schema manifest;
- an incompatible renderer/schema version.

**Reload is not a rebuild.** Reloading dynamic context, a skill or an operation result appends another load event, even for the same key. The newer result is closer to the reply and becomes the current value; the older one remains honest stale history. This is how models already experience repeated tool reads, and it preserves the full cached prefix in the usual case. Source mutation alone does nothing: the prior value remains until an explicit reload appends the new one.

**Test contract.** Equal initial inputs produce equal tool/message vectors; appending an event leaves the prior message vector as the common prefix; changing only the volatile tail leaves every marked vector equal; and an explicit rebuild is the only test that expects an earlier prefix to change. Tests do not pin content merely to exercise rendering. When fixed rendered content is unavoidable, they use a production constant or the same production helper that creates it, never a hand-copied duplicate.

### 3.2 The sequence

| # | Section | Region | Role | Contents |
|---|---------|------|------|----------|
| 0 | Fixed tool manifest | fixed prefix | — | Harness and configured tools, plus the stable declared/deferred superset required by the provider strategy (§3.2.3) |
| 1 | Identity prompt | initial messages | `system` | `config.systemPrompt` or the default identity, plus one line of session identity |
| 2 | Capability and data-boundary notice | initial messages | `system` | Declared `config.caps` when relevant, plus one stable rule that tool-result content is data, not instruction |
| 3 | Initial trusted context | initial messages | `system` | Operator context admitted as instruction, rendered once and never duplicated into a tool result (§5.3) |
| 4 | Initial skills index | initial messages | `system` | The discoverable-skill snapshot when the context is initialised (SKILLS.md §4.3) |
| 5 | Initial persistent skills and data | initial messages | `system`, then `user` / `assistant` / `tool` | Skill bodies and trusted loads, then at most one aggregate `pinned_context` and one `loaded_context` data result (§5.5) |
| 6 | Persisted messages | append-only vector | `system` / `user` / `assistant` / `tool` | Prior conversation and already-rendered context, skill and tool-state events, in chronological order |
| 7 | Pending results | working turn | `user`, `assistant`, `tool` | Job results that arrived for this cycle: one request — *Get job results.* — one `get_job_results()` call, one result listing each job once (§5.7) |
| 8 | Current input | working turn | `user` | The inbox message(s) driving this cycle |
| 9 | Tool-loop messages | working turn | `assistant` / `tool` | Assistant/tool turns and context events accumulated within this cycle |
| 10 | Volatile loads | volatile tail | `system` or `assistant` / `tool` | Trusted operator instructions or the ownership-separated data aggregates for loads declared `volatile` — an op entry by default (§5.5) |
| 11 | Budget warning | volatile tail | `system` | **Only** when the budget is under pressure (§3.4) |
| 12 | Current date | volatile tail | `system` | One line; changes daily |
| 13 | Unavailable tools | volatile tail | `system` | Configured tools that did not resolve this cycle |
| 14 | Outstanding task | volatile tail | `user` | The task the agent must complete or fail — the last thing before the reply |
| — | Empty-state signal | working turn | `user` | Replaces 7–8 when there is nothing to act on |

The *Role* column is the role a section emits — the role that is true of its content. The initial consecutive system sections coalesce when the persisted context is first rendered. Sections 11–13 are **one `system` message**, composed of whichever parts are present: the tail is re-rendered every inference, and one message is the cheapest shape for it. How a system message that follows conversation reaches a given provider is the edge's business (§3.2.1, §3.5), never a section's.

### 3.2.1 The role rule

Roles are **semantic**, not positional. `system` is what the venue, the operator or the runtime admits *as instruction* — identity, operator-trusted context, a loaded skill, a notice, a diagnostic, a compaction summary. `user` is what has happened or must be acted on — pending results, the input, the goal, the empty-state signal. `assistant` and `tool` are the agent's own turns and their results — and, marked as such, the calls the venue makes on the agent's behalf to bring context data in (§5.5). Ordinary context loads are data and never acquire system authority merely because they persist or are pinned. A section emits the role that is true of its content, and the stored conversation keeps it, so provenance survives. Nothing in assembly depends on the provider.

Where a `system` message that follows conversation actually lands is provider- and model-dependent. Some APIs accept native mid-conversation system blocks, some have only a top-level system parameter, and some local templates honour only the leading instruction. The naive fallback — hoisting a late instruction into the head — is wrong: it rewrites the cached prefix and moves the instruction away from the event that made it relevant.

So the **edge normalises, where the provider requires it** (§3.5), driven by the model's declared `systemMessages`:

- `"multiple"` — nothing to do. System messages reach the model where they were placed.
- `"midConversation"` — the leading run becomes the provider's system parameter and later system events become its native mid-conversation system blocks, in place.
- `"single"` — the **leading run** of system messages coalesces into the provider's system parameter, as a list of blocks where the API takes them. Any **later** system message becomes a `user` message with its content wrapped `[system: …]` (§1.1), in place: the model still sees who is speaking, and the message stays exactly where it was put.
- `"none"` — as `"single"`, with the leading run folded into the first user message.

An operator whose model mishandles late system messages declares `"single"` for it and gets the same treatment. No section ever knows.

Two consequences are load-bearing. Every request contains at least one non-system message — initial context data, persisted conversation, the input, pending results, the empty-state signal or the last tool result — so the edge never has to invent a turn. And consecutive same-role messages are normal — every provider merges or accepts them. Instruction blocks use §1.1 labels; data blocks use their tool name and structured result schema.

### 3.2.2 Ephemeral and persisted

The message vector is **persisted as rendered**. Pending results, input, tool-loop messages and context events append in exactly the form the model saw them. The working turn is the only uncommitted prefix state. Two exceptions are deliberate: the empty-state signal occupies the input slot for its inference and is not committed; and a diagnostic the runtime must add — a tool failure it could not return as a tool result — is committed as a `system` event with its source recorded.

The volatile tail is **ephemeral**. Its values may be resolved for every inference because its declaration explicitly opts out of prefix caching. Nothing else is made fresh implicitly.

**Warnings are never conversation.** The budget warning, the compaction nudge, the date and the unavailable-tools notice describe the state of *this inference*. They belong in the tail, render once, and vanish when the condition does. A warning written into the conversation would be re-read on every later inference as though it were still true.

### 3.2.3 Tool definitions

Tool definitions are **section 0**. They are not a message — providers take them as a separate parameter — but they are prompt bytes placed ahead of the messages. The base manifest is therefore fixed for the persisted context, canonically ordered and rendered once.

A tool becoming available later must not cause the venue to rebuild that array silently. The provider strategy chooses one of three representations, in order:

1. declare the stable/deferred superset required by the provider, then append native `tool_addition` / `tool_removal` events when support exists;
2. keep the fixed generic search/invoke tools and append a capability-description event, with dispatch through the generic tool;
3. if the exact direct schema must change and neither representation is available, rebuild the prefix explicitly.

An adapter or plugin loading or unloading mutates the venue capability registry, not the fixed tool vector of an existing context. Revocation is always enforced at dispatch even if an earlier event remains visible to the model. A soft removal appends a removal event; only an explicit rebuild makes the historical definition absent.

The same rule applies to conditional capabilities. The static `complete_task` / `fail_task` schemas stay in the fixed harness manifest and reject calls when no task is in scope. Skill- and `more_tools`-contributed operations append exact addition/removal state and are invoked through the fixed generic dispatcher where the provider has no native representation. `context_unload` also stays declared whenever skill loading makes persistent agent-managed context possible.

Tool definitions are charged to the budget first (§3.4). Dynamically appended definitions are charged where their addition event enters the message vector.

### 3.3 The top-level function

Initialisation resolves and renders the starting inputs once. Inference assembly is deliberately smaller:

```java
static Prompt assemble(Spec spec) {
    Prompt p = new Prompt(spec.budget());

    p.tools(spec.tools());
    p.addAll(spec.messages());
    p.markAt(Band.INITIAL, spec.initialEnd());
    p.mark(Band.COMMITTED);

    p.addAll(spec.workingTurn());
    p.mark(Band.WORKING);

    // Volatile tail — re-rendered every inference, never cached
    p.add(contextExchanges(spec.volatileEntries()));
    p.add(systemMessage(budgetWarning(p.used(), p.budget()), currentDate(spec), unavailableTools(spec)));
    p.add(outstandingTask(spec));
    return p;
}
```

`Prompt` is a mutable request accumulator: `tools(defs)` sets and charges the tool array, `addAll(messages)` appends and charges immutable vectors, `markAt` / `mark` record cache boundaries, and `remaining()` / `used()` report the budget position. It knows nothing about skills, tools or capabilities. Initial rendering and event append own resolution; replay does not.

`p.remaining()` is passed explicitly when an entry is first appended, when compaction builds a replacement prefix, and when the tail renders. Already persisted entries are never re-truncated to fit a later request.

### 3.4 The budget

**There is one budget**: the model's declared context size — `model.budget.bytes` on the LLM operation asset, resolved for the agent's model (OPERATIONS.md, *The `model` facet*), else `DEFAULT_BUDGET`. The size of a context is a fact about the model, declared beside the model, not a venue-wide constant. It bounds the **input**, counted as UTF-8 bytes of every message's content plus the tool definitions, which are charged first. The reply is bounded separately by `maxTokens`.

How it is spent:

- The **fixed tools and persisted messages** are what they are. Persistent content is never auto-evicted or silently re-rendered.
- The **working turn** appends to the message vector. Explicit compaction rebuilds the prefix when space must be reclaimed (§5.6).
- The **tail** is small; it is charged like everything else and never cached.

What the budget does, and nothing else:

| Position | Behaviour |
|----------|-----------|
| any | per-entry render sizing: a structured entry is capped at a twentieth of what remains when it renders (§5.3) |
| ≥ 70% | the tail carries one line — `[Context budget]`; it names exact `loaded_context` keys only when agent-managed entries exist, otherwise says the persistent material is pinned |
| ≥ 90% | the line says compaction is required before further work; the harness offers `compact` |
| over | the prompt is sent anyway; if the provider rejects it the cycle fails with the size and the remedy (`agent:context`, `context_unload`, `compact`) |

**Never silent.** Byte accounting is not a token bound across providers, so the runtime never removes or rewrites context on its own authority. A soft `context_unload` prevents future use but cannot reclaim already rendered bytes; explicit compaction or reset does that. An inventory of what is loaded does not belong in every prompt either: it restates the events already rendered and its byte counts would change every inference.

### 3.5 The edge

The assembler's output is provider-neutral. The level-3 adapter, reading the model's declared options, does these things and no others:

| Declared | The edge |
|----------|----------|
| `systemMessages: "multiple"` | passes system messages through where they are placed |
| `systemMessages: "midConversation"` | delivers the leading run through the provider's system parameter and maps later system events to native mid-conversation blocks in place |
| `systemMessages: "single"` | delivers the leading system run as the provider's system parameter — a list of blocks where the API takes them, one joined text where it does not — and converts every later system message to a `[system: …]` user message in place (§3.2.1) |
| `systemMessages: "none"` | as `"single"`, with the leading run folded into the first user message |
| `labels` | nothing at the edge beyond the wrapper above — the dialect is applied by the one renderer (§1.1), which the edge also uses for that wrapper |
| `cachePrefix` | turns the initial, committed-message and working-turn marks into the provider's cache controls; `cache: false` on the call switches all of it off |
| dynamic-tool support | maps persisted tool-state events to native addition/removal blocks; without it, uses the fixed generic-tool strategy or requires an explicit rebuild (§3.2.3) |
| always | maps the base tool manifest to the provider's schema, in the given order, and `tool` messages and `toolCalls` to its shapes, merging consecutive same-role messages where the API requires alternation |
| always | **never reorders, never drops, never adds content** |

Nothing else about a provider needs handling: its context size is already in the budget.

---

## 4. Resolution and assembly

Mutable state is consulted only when initialising context, appending an explicit event or rendering a declared volatile value:

```
resolveAuthority(config, ctx)                 →  capsCtx
initialise(engine, capsCtx, config, chain)     →  tools, messages, initialEnd
appendEvent(engine, capsCtx, messages, change) →  messages.concat(renderedEvent)
assemble(tools, messages, working, volatile)  →  prompt
```

- **Authority** first: everything that reads the lattice reads under the agent's capability-narrowed context.
- **Initialisation** snapshots the model/rendering profile, fixed palette, skills index and starting effective loads. It resolves and renders that state once.
- **Event append** handles an explicit load, reload, unload, skill or tool-state change. It resolves the affected value once, renders a canonical event once and concatenates it. It does not regenerate unrelated context.
- **Assembly** concatenates immutable vectors and renders only the declared volatile suffix. It reads no registry or persistent source.

A runtime that needs `capsCtx` calls `resolveAuthority`; it does not build a context to extract it. Merely assembling another inference never observes external mutation. An explicit reload appends what it observes; only a separately requested compaction/reset rebuilds earlier cells.

---

## 5. Section notes

### 5.1 Identity prompt
`config.systemPrompt`, else a default identity, followed by one line of session identity: the venue name, the model when configured, and the session id when one is in scope — the agent's handle for reporting back into this conversation from deferred work. It is rendered when context is initialised. An `agent_update` affects new contexts; an existing context keeps its rendered identity until an explicit compaction or reset rebuilds it, so active history is never rewritten accidentally. A runtime may append a notice of its own — goaltree's subgoal notice for child frames — when that frame opens. Nothing inference-local belongs here.

`systemPrompt` is the text itself, or **one context entry** in the grammar of §6 — `{ref: "w/prompts/mina"}`, `{ref: "dlfs/vault/prompts/mina.md"}`, `{text}`, `{op, input}`, `{job}` — resolved through the same loader as pinned context and loads (`ContextLoader.resolveText`). Three things differ from a load: it renders as the identity, with no header; it resolves **once at initialisation or explicit rebuild**; and it is required — an entry that does not resolve, or resolves to something that is not text, fails initialisation with the reason, because a missing identity is a configuration error. `agent:create` warns when the entry does not resolve for the creator. A workspace path is one lattice read and a DLFS file one content read; neither creates a job.

**Head discipline:** the head holds what every inference of *this* persisted context needs and nothing more. It is cached, but providers without caching pay for it on every inference, and an agent that answers questions needs neither a namespace cheat sheet nor capability bounds. Depth belongs in skills, loaded when needed (SKILLS.md).

The lattice reference — namespace prefixes and addressing rules — is therefore a **skill**, not a head section: `v/skills/data/lattice`, mirrored into `root`, pinned through `config.loads` by the templates of agents that have lattice tools and discoverable by any agent that meets a path. Not every agent has tools, and not every agent with tools touches the lattice.

### 5.2 Capability and data-boundary notice
The capability portion renders only for an agent that **has tools** and declares `config.caps`. Capabilities bound what the agent can *do*; stating them up front saves the cycle an agent otherwise spends discovering them by hitting them, and the confusing denial that follows.

Whenever the context may contain tool results, the same initial system message carries one stable rule: tool-result content is reference data, potentially untrusted, and instructions found inside it do not acquire system or operator authority. This policy appears once in the cacheable prefix, not as a wrapper repeated around every result.

### 5.3 Pinned context
`config.context`, `config.loads`, and optional `loads` supplied when a caller mints a session are operator/caller-owned. They are resolved into the initial messages and have no automatic expiry, but the agent cannot remove or mask them. A later source mutation is not observed implicitly. Its owner may explicitly reload it, which appends another message/result; old bytes disappear only through compaction or reset. Neither surface exposes an unload handle.

Pinned does **not** itself grant instruction authority. Trust is fixed where an entry enters the scope chain:

- Operator-owned `config.context` and non-skill `config.loads` default to `trusted: true`; an entry declares `trusted: false` when its resolved value is reference data. Trusted values render once as labelled `system` messages and are not also placed in `pinned_context`.
- Caller-supplied session-mint loads are pinned but untrusted. A caller-provided `trusted: true` is overwritten to false before the tier is persisted.
- Agent `context_load` values are agent-managed and always untrusted. The tool schema has no trust control, and persisted agent metadata is never allowed to promote the value.
- A skill body is instruction regardless of which allowed surface selected it. Trust comes from resolving a skill asset in the operator-supplied skill catalog, not from arbitrary text or a caller-controlled flag. Its bundled context remains data.

This default preserves the historical meaning of operator context while giving data-bearing declarations an explicit injection-safe form. Mutable memory, web/API material and user-authored documents should normally declare `trusted: false`.

Structured values are rendered as budget-bounded JSON5; any one entry is capped at a twentieth of the budget remaining when it renders (`max(MIN_ENTRY_BUDGET, remaining/20)`), so no single entry can consume the context. Strings are preserved verbatim either as the trusted message body or under a data result's `content` field.

### 5.4 Skills index
One line per discoverable skill, with `(loaded)` against those already in context. Snapshotted when context is initialised. A later catalog change appends a catalog event only when explicitly surfaced; otherwise it becomes visible after the next prefix rebuild. Absent entirely when the agent declares no skill sources. See SKILLS.md §4.3.

### 5.5 Persistent elements
Every entry in the effective loads chain (§7) uses the same resolver, but it is resolved **once per load**. Skill bodies and operator entries stamped trusted are instructions. Pinned skills render as `[Pinned skill: <name> — <source>]`; agent-loaded skills render as `[Loaded skill: <name> — unload key: <key>]`; trusted ordinary entries render as `[Pinned context: <label>]`. Every untrusted value renders behind a provider-native tool-result boundary.

At initialisation the starting data is aggregated once per ownership class:

- `pinned_context` returns an ordered vector. It contains only untrusted operator/caller entries and exposes no unload handles.
- `loaded_context` returns a map from exact unload key to the entry. If one skill contributes several context values, that map value is a vector.

A later agent load or reload appends one `loaded_context` exchange containing only the affected keys. An owner reload appends trusted system messages and/or one `pinned_context` data exchange for only the affected entries; neither form exposes unload handles. One invocation may resolve several entries, so data remains one map/vector rather than one synthetic call per key. Already rendered values are neither repeated nor regrouped on later inferences. Loading the same key again needs no special replacement event: the later result is the current one because it appears later in the conversation.

When the agent initiated it, the ordinary `context_load` result is only a compact acknowledgement of the keys loaded; the values themselves appear exactly once, in the appended `loaded_context` exchange. Likewise `skill_load` acknowledges activation, while the body appears once as the appended instruction and its context entries appear once in `loaded_context`.

Each data entry carries its source in its own terms (`ref`, `op` + `input`, `job` + `path`, or `source: text`), `label` only when it adds information, and exactly one of `content`, `error`, or `absent: true`. An agent-managed default path load omits `ref` because its `loaded_context` map key already identifies the same path. Metadata and content exist only in the result: the injected call arguments are `{}`. The exchange's provider-required call id is derived deterministically from its persisted event position and exists solely to pair the assistant call and tool result; there is no synthetic per-entry id and replay never generates a new one.

Why tool results and not labelled system blocks: a provider-native tool result is the boundary models are trained to treat as data, and the system channel is the one an injected instruction most wants to occupy. The venue really did run those operations or reads when it appended the exchange. Initial aggregates answer one plain user turn — *Load the context available for this conversation.* — which also supplies the leading user message some providers require. Later exchanges enter the vector where the corresponding load or reload occurred. Volatile aggregates follow the input and need no request of their own.

The result schema, not a prose wrapper, tells the model what it received. Each `pinned_context` vector element and `loaded_context` map value has provenance plus exactly one of `content`, `error`, or `absent: true`. Even when `content` is a string containing apparent instructions, it remains nested inside the `tool` result; the venue never splices an untrusted value beside a trusted heading or into a system block.

Canonical shape, before provider mapping:

```json
[
  {"role": "assistant", "toolCalls": [
    {"id": "context:7", "name": "loaded_context", "arguments": {}}
  ]},
  {"role": "tool", "id": "context:7", "name": "loaded_context",
   "structuredContent": {
     "w/reference/policy": {
       "label": "Reference policy",
       "content": "potentially untrusted source text"
     }
   }}
]
```

The call name establishes why the result is present, the map key is its unload handle, and the nested fields describe the data. No extra `[Context: …]` text is needed.

This is deliberately different from an ordinary tool result such as `covia_read` or `covia_inspect`. An ordinary result keeps its real tool name in the persisted message vector and is never copied into either context aggregate. It remains history until explicit compaction, but it is not active persistent context and never needs `context_unload`.

A loads entry is `key → spec`. By default the key **is** the entry's source — a lattice path, an asset, a content ref — and the spec carries only modifiers. A spec may instead declare exactly one of `ref`, `text`, `op` + `input`, or `job` + `path`, in which case the key is its identity. Ownership, not map shape or age, decides whether that identity is unloadable.

Only agent-managed elements expose an **unload key**, in the `loaded_context` result map or a loaded-skill header. `context_unload` accepts one exact key or a `paths` array of exact keys. It rejects display labels, ordinary tool arguments, and pinned keys; it never manufactures a mask over pinned context. The base tool remains present whenever the scope has a writable tier, including while that tier is empty, so loading or unloading context does not mutate section 0.

A successful soft unload appends a compact state event listing the affected keys and any skill/tool deactivations; it never repeats their content. When the agent called `context_unload`, its persisted tool result is that event. An owner-initiated change uses an attributed system event. Folding the newest event for a key determines whether it is active, while the prior bytes remain honest history until compaction or reset.

**Placement.** Starting elements render in declared/load order in the initial messages; later elements append where they are loaded, so nothing already rendered moves. An element declared `volatile: true` — the default for an `op` entry — renders in the **tail** (§3.2 section 10), after every cache mark. A non-volatile entry is resolved once per load; loading it again appends the newer result. The placement is chosen by declaration, never by observing the content. Declare a queue, status or other genuinely current-state view volatile; use an ordinary tool call when freshness is needed only on demand.

A volatile element is re-sent uncached on every inference and sits between the latest input and the reply. It therefore renders **within its budget whatever its shape** — a structured value through the explorer as always, a string cut at the budget with one trailer naming the bytes left out and the two ways to get them (reload with a larger `budget`, or fetch the value with a tool). A volatile load is a compact current-state view; a long one belongs in an ordinary tool call. Its declaration persists until explicitly unloaded or changed by its owner; its rendered result does not. Neither kind auto-expires.

Failures are visible, never silent: a skill or trusted entry keeps its instruction label and reports unavailability; a data entry carries `error` in its ownership aggregate. A later reload appends a successful result; inference assembly does not retry it. An absent optional pinned source remains visible as a trusted `absent` diagnostic or an untrusted `{absent: true}` data entry. An absent agent-managed dynamic load contributes no value.

### 5.6 Conversation
Runtime-supplied frames, rendered by one `ConversationRenderer` for every runtime. llmagent supplies its session's single frame; goaltree supplies its frame stack, ancestors first at decreasing budgets and the active frame last (GOAL_TREE.md). The assembler does not know the difference.

The message vector is append-only **across cycles as well as within one**: every inference sees exactly the previous immutable vector plus newly appended events and the working turn. Calls and results remain paired and byte-identical. No end-of-cycle scratch-elision pass rewrites the conversation.

**Compaction is the one ordinary rewrite.** A selected range collapses into a `[Compacted: N turns] summary` whose summary the agent wrote, because only the agent knows what mattered. The runtime replaces the persisted message vector with an explicitly compacted representation. It may omit obsolete tool chatter and superseded/unloaded context because the cache invalidation is intentional and observable. The `compact` tool is a context tool, available to every runtime.

Everything else is an append. Context state is obtained by folding its events in order; a repeated load makes its newest result current without pretending the model never saw the older one. That small amount of stale history is normal for an LLM conversation, while recency puts the relevant value nearest the reply. Segments and diagnostic events are `system` turns — authored by the runtime, in sequence — and the edge keeps them in place on every provider (§3.2.1).

### 5.7 Pending results
Job results that completed for this cycle — the mechanism by which asynchronous work re-enters the conversation. A result is data, so it arrives as every result does (§5.5): one plain request — *Get job results.* — one `get_job_results()` call, and one tool result listing each job once: its id and status, then its output (strings verbatim, structured values bounded) or, for a job that did not complete, its recorded `error`. One call rather than one per job — the ids would only be repeated, and a listing is the natural answer to the plural request; a failed job is data about that job, not a failed fetch, so the result is not a tool error. Placed before the current input so that the input, the thing to act on, is closest to the reply.

The line this draws, once: **a result renders as a tool exchange; a request renders as a user turn.** Loaded context and job results are results. Tasks (§5.13) and inbox messages are requests from a principal — the user channel is theirs, with attribution.

### 5.8 Current input
The inbox message(s) driving this cycle; goaltree has none — its goal rides in the task slot (§5.13). When there is neither input nor pending results, the **empty-state signal** takes the slot: one `user` line saying so, so the agent can act on its role or report idle. It is content, not padding — its role as the message that keeps a system-only request legal (§3.2.1) is a consequence, not its purpose.

### 5.9 Tool-loop messages
Assistant and tool messages accumulated *within* this cycle. These are conversation, not preamble: they sit in the working vector before the tail, so each inference of the loop shares its prefix with the previous one through the last tool result and only the tail is re-rendered. A provider reply whose `finishReason` is `length` is incomplete: the runtimes do not execute or persist its partial tool calls or content. They append one runtime diagnostic asking for a complete concise regeneration and retry once; a second truncated reply fails the cycle explicitly.

### 5.10 Budget warning
The line described in §3.4, present at ≥ 70%, escalating at ≥ 90%. Silence is the normal case, so it costs nothing until it means something.

### 5.11 Current date
One line, changing daily, taken from the Spec's clock — never from the system clock inside a section, so assembly stays pure. Kept out of the head precisely so the cacheable prefix contains no changing value; in the tail it busts only itself.

### 5.12 Unavailable tools
Tool resolution failures observed during initialisation or a current append — reported so the agent adapts rather than calling into a void. The adapter registry is not polled by assembly; a restored operation becomes available through an explicit tool-state event or prefix rebuild.

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
| `label` | Optional name for the entry. It labels an operator-trusted instruction; on untrusted data it remains display metadata inside the structured result. Never an unload key |
| `trusted` | Operator `config.context` / `config.loads` only: render the resolved value as a `system` instruction. Defaults true there. Forced false for caller/session and agent context loads; skill bodies have their own trusted type semantics |
| `required` | Failure throws and fails initialisation or the requested load |
| `budget` | Render cap for a structured value (§6.4); not an accounting charge. A hard cap on a volatile loads entry whatever its shape (§5.5) |
| `wait` | Job entries: ms to wait for a running job |
| `volatile` | Loads tiers only (§5.5): render afresh in the tail rather than commit a result. Defaults to true for an `op` entry, false otherwise |

An **op entry** runs once per load unless it is volatile. A volatile op runs every inference under the caller's identity, so it must be **read-only** — a write-on-assemble would fire every inference. Operation entries generalise the rest: a workspace read, a cross-venue fetch and a purpose-built assembler are all just operations. In `config.context` an op is part of the initial snapshot; at a loads tier it is volatile by default and renders in the tail, where a changing result costs only itself.

### 6.3 Resolution outcomes

The contract every entry obeys, in either role:

| Outcome | Behaviour |
|---------|-----------|
| Optional pinned source absent / empty / returns null | Visible without invented content: a trusted labelled diagnostic or `{absent: true}` in `pinned_context` |
| Agent-managed source absent | Skipped; the initiating tool acknowledgement already records the attempted load |
| Resolution **errors** | Visible `error` in a data tool result, or `unavailable: <reason>` on an instruction block, so the model can adapt |
| `required: true` and fails | **Throws** — fails initialisation or the requested load |
| Malformed `context` value (not an array) | **Throws** — a configuration error to fix, not mask |

*Bad shape → throw; required failure → throw; error → visible; pinned absence → visible.*

### 6.4 Rendering

A **string is preserved verbatim** (markdown and newlines included) as either a trusted system-message body or the `content` field of its structured tool result. A **structured value renders as budget-bounded JSON5** in the same selected channel. So an assemble op returns a string when exact formatting matters and a map or vector when a compact view is fine. Only `systemPrompt`, skill bodies and operator entries admitted as trusted leave the data-result path.

### 6.5 Asset resolution

Content payload decoded as UTF-8 if present; else the `description` from metadata (which is why an operation or agent asset makes a usable entry); else skipped.

---

## 7. Loads and the scope chain

Which entries are in play at all.

### 7.1 Two roles, one pipeline

- **Pinned** (`config.context`, `config.loads`, and caller-supplied session-mint `loads`) — owned outside the agent, snapshotted into the initial messages, never removable by the agent.
- **Agent-managed** — the working set the agent creates via `context_load` / `skill_load` and may deliberately remove via `context_unload`.

They share the entry grammar, resolver, append contract and budget. They differ in *ownership and control*, made explicit by an internal ownership marker rather than inferred from timestamps. Trust is a second stamped property and is never inferred from persistence or ownership after tiers merge. Both kinds are persistent: their appended events reappear across model calls and turns and have no time-based expiry. `context_load` takes a `path`, or `text` / `op` / `job` under an `id`; any loads entry may declare `tools`, `skills` and `skillsets`.

### 7.2 The chain

Tiers, outer → inner:

```
agent (config.loads) → session (sessions.<sid>.loads) → frame (frame.loads, goaltree)
```

Each tier may contain declarations installed by its owner plus dynamic entries written by the agent while it is the innermost writable tier. Rules:

- **The effective starting set is a union down the chain; inner shadows outer** on a path collision. It is folded once when context is initialised.
- **Unload is ownership-safe:** only an agent-managed entry in the innermost writable tier can be removed. Removing a local shadow reveals any pinned outer entry. Pinned entries cannot be hidden with `context_unload`.
- **Legacy tombstones remain readable** for existing stored sessions, but the agent harness no longer creates new tombstones over pinned context.
- **Inner tiers read outer tiers, never mutate them** — one writer per tier.
- **Budgets are advisory** — never a basis for silent eviction.
- **No session in scope → no writable tier**: `context_load`/`unload` fail with a diagnosable result.

The chain is resolved when context is initialised. Later loads and reloads resolve only their requested keys and append results to the message vector. A tool write to an already-loaded path does **not** change the model's context on the next inference; the agent or owner must load it again, or read the current value with an ordinary tool call. This is the freshness boundary that protects the cache from unrelated mutable state.

### 7.3 Frame scoping

A goaltree subgoal initialises its own rendered message vector from the effective pinned context but starts with an empty agent-managed set, curating loads for its sub-task without polluting parent or siblings. This is the one structural reason the agent-managed store is not a flat agent-level list.

---

## 8. Initialisation and inference Specs

Initial rendering and inference assembly have different inputs because only the former may build the fixed prefix.

The **InitialSpec** is consumed only by `initialise`:

| Field | Meaning |
|-------|---------|
| `config` | The merged agent configuration to snapshot |
| `capsCtx` | Capability-narrowed request context used for all initial resolution |
| `model` | The model, provider-shaping options, budget and label dialect fixed for the persisted context |
| `headNotice` | Runtime text captured in the head; absent for llmagent, the subgoal notice for a goaltree child |
| `loads` | The effective starting chain: agent → session for llmagent; agent → session → frame for goaltree |
| `seedMessages` | Empty for a new conversation; an explicit summary/current-state representation after compaction or reset |

The per-call **InferenceSpec** contains the persisted vectors and values allowed to vary without rebuilding them:

| Field | Meaning | llmagent | goaltree |
|-------|---------|----------|----------|
| `tools` | Persisted immutable fixed tool vector | Session tools | Active-frame tools |
| `messages` | Persisted immutable append-only message vector | Session messages | Active-frame messages |
| `initialEnd` | Index of the initial-head cache boundary in `messages` | — | — |
| `capsCtx` | Authority for dispatch and explicitly volatile reads; not a rendering source | — | — |
| `pending` | Results appended for this cycle | Job results | Drained into the active frame (GOAL_TREE.md) |
| `input` | What drives this cycle | Inbox messages | — (none) |
| `toolLoop` | Messages and appended events accumulated within this cycle | — | — |
| `volatile` | Declarations intentionally resolved into the uncached suffix | — | — |
| `task` | What must be completed or failed, rendered last (§5.13) | Open task, if any | Open task, if any (root frame) |
| `unavailable` | Failures produced by the current event/volatile work, not a registry rescan | — | — |
| `now` | The clock used by tail rendering | — | — |

A dash means the runtimes agree. Anything a runtime needs that is not in these interfaces is a signal that an input is missing, not that the runtime needs its own assembly pipeline.

`agent:context` reads the same persisted vectors and calls the same `assemble`, so an inspected existing context matches a live inference **by construction**. For a session with no rendered context it previews `initialise` without persisting it. It returns the level-3 input with `cacheMarks`, the `budget` (`bytes`, `used`, `remaining`), the marks at the initial, committed-message and working-turn boundaries, and the label dialect. Sidecars explain the stored state without changing or re-resolving it:

- `palette.tools` records the base manifest and appended tool-state events with their source and operation route.
- `loads` folds appended context events to report each key's ownership, source, current/stale/unloaded state, rendered bytes, budget and truncation status.

Consumers compare the returned immutable `tools` and `messages` vectors directly. `.equals()` answers whether a whole logical request component is unchanged; the vector common-prefix operation locates the reusable prefix. Separate synthetic prefix hashes add bookkeeping without adding information.

`agent:step` takes the same inference plus the reply the model would give and runs one harness iteration on it: text-as-control recognised, tool calls dispatched through the runtime's registry with the same routes, capability checks and authority, their results appended to the working turn, and any context/tool-state event appended once. The agent is untouched: a terminal control tool is reported (`terminal`), never resolved; goaltree's `subgoal` is not run. It returns `assistant`, `turns`, `calls` (`id, name, arguments, result, isError?, ms`), `terminal?`, `done`, `response?` and `next?`.

The same regions drive the timeline record (`AGENT_LOOP.md` §2.4): initial messages are recorded once, later events once when appended, the working turn as replies and calls append, and the volatile tail only for the inference in which it appeared. Recording never regenerates prompt content.

---

## 9. Implementation map

| Concern | Home |
|---------|------|
| `InitialSpec`, `InferenceSpec`, `Prompt`, concatenation and attribution | `ContextAssembler` |
| Persisted tool/message vectors and event append | Session/frame state (`renderedContext` + frame conversation) |
| The label renderer (§1.1) | `Labels` |
| Base manifest and dynamic-tool strategy (§3.2.3) | `ToolPalette` |
| Initial loads and context-event append (§4) | `Loads` |
| Entry resolution and rendering (§6) | `ContextLoader` |
| Scope chain algebra (§7) | `ContextChain` |
| Frame rendering, segments and turn normalisation (§5.6) | `ConversationRenderer` |
| Skills index and skill elements | `Skills` |
| Authority and the model profile: `capsContext`, `modelProfileFor` | `AbstractLLMAdapter` |
| The edge (§3.5): system normalisation, cache marks and native tool-state events | `LangChainAdapter` |
| llmagent spec | `LLMAgentAdapter` |
| goaltree spec | `GoalTreeAdapter` |

### 9.1 Known divergences

Everything above is implemented except the provider-edge refinements listed here.

- **Native provider tool-state blocks are not mapped yet.** The canonical conversation already persists trusted events carrying exact `toolAddition` / `toolRemoval` state, and every supporting context has the fixed `invoke_tool` fallback, so the initial tool vector remains unchanged and dispatch is authoritative. Provider edges currently receive the generic late-system representation; a native edge may map the same fields to its tool-addition/removal blocks without changing stored history.
- **The head and current live surface share one provider breakpoint.** The client marks only the last block of the system parameter. The target initial/committed/working marks require shaping the provider request directly; langchain4j currently exposes only the 5-minute ephemeral cache kind, not a longer TTL.
- `compact` exists only in the goal-tree harness; llmagent has no compaction, so the 90% line asks there for what it cannot offer.
- **goaltree persists the goal as the frame's opening user turn** and re-appends it after compaction, rather than rendering it in the task slot. Kept deliberately for now: the root frame's "goal" of a chat session is its origin description, which must not be re-read on every inference; the task-slot rendering is right for subgoal frames and is the pending change.
- The agent-facing text of `skill_load` and SKILLS.md §4.3 name the `[Skills]` index by its bracket label whatever the dialect.
