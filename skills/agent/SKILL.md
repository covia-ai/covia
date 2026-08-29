---
name: agent
description: Create, configure, and manage Covia agents. Handles config gotchas, system prompts, LLM backend setup, skills and hierarchical skill discovery, and lifecycle operations. Use when working with agents on a venue.
argument-hint: "<create|list|query|reset> <agent-name>"
---

# Agent Management

**Prerequisite:** The venue must be running and connected as an MCP server (`http://localhost:8080/mcp`). If MCP tools are not available, tell the user to run `/venue-setup local` first.

Manage agents on a connected Covia venue via MCP.

## Key Config Rules

These are critical — the venue rejects malformed stable shapes and reports
unavailable operation tools, but a semantically poor config can still create an
agent that cannot do the intended work:

1. **`config.operation` must be a plain string** — e.g. `"v/ops/llmagent/chat"`, never `{"name": "v/ops/llmagent/chat"}`. The agent runner calls `RT.ensureString()` on this field.

2. **Use `agent_request` with `input` parameter** to submit work — not `task`, not `message`. Requests create trackable Jobs with immutable records.

3. **`config` is the single home for ALL agent settings (#144)** — `operation`, `llmOperation`, `model`, `systemPrompt`, `tools`, `caps` all live in the top-level `config` map. Passing a `config` key inside `state` is rejected with an error.

4. **Conversation history is session-scoped** — updating a prompt affects new contexts; an existing session keeps its exact rendered prefix until explicit compaction or `agent_reload_context`. Omit `sessionId` to start a new conversation; use context reload to adopt current config while preserving history, and delete/recreate only when you explicitly need a completely fresh identity and audit record.

5. **Operation references are lattice paths, not adapter shorthand** — `config.operation`, `llmOperation`, and operation entries in `tools` must be resolvable paths such as `v/ops/covia/write`, never `covia:write`. Create returns warnings for unavailable configured tools. Harness tools (`subgoal`, `complete`, `fail`, `compact`, `context_load`, `context_unload`, `more_tools`, and `skill_load` when `config.skills` or `config.skillsets` is non-empty) are bare names. A custom read/write agent must declare those operations or start from `worker`.

## Commands

### `create <name>` — Create an LLM-backed agent

```
agent_create
  agentId: "<name>"
  config: [
    "v/agents/templates/skilled",
    {"systemPrompt": "<prompt>"}
  ]
```

Ask for the system prompt if not provided. Call `langchain_models` first when
provider readiness matters; the template is provider-neutral and otherwise uses
the venue default.

### Create from a template

Pre-built agent configs ship at **`v/agents/templates/<name>`** (browse with `covia_list path=v/agents/templates`). Pass the path as `config`:

```
agent_create  agentId="Bob"  config="v/agents/templates/worker"
```

Available: `minimal` (on-demand only), `skilled` (recommended lean default),
`reader` (capability-enforced read-only), `worker` (data processing), `analyst`
(evidence + schema), `manager` / `goaltree` (goal-tree planners), and `full`
(broad, context-heavy core palette).

Templates are provider-neutral. Compose the provider at create time so the agent
never starts under an unintended backend:

```
agent_create agentId="Bob" config=[
  "v/agents/templates/worker",
  {"llmOperation": "v/ops/langchain/anthropic", "model": "claude-sonnet-5"}
]
```

**Tool-capable models (agents with `tools`).** An agent that has `tools` needs a
model that supports function calling, or every run fails when it first tries a
tool. `agent:create` emits an advisory when Ollama tool support cannot be
confirmed. Use the capabilities returned by `langchain_models` rather than
assuming support from a model family. Hosted provider capabilities still depend
on the selected model.

### `list` — List all agents

```
agent_list
```

Shows agent IDs, statuses, and task counts.

### `query <name>` — Inspect an agent

```
agent_info  agentId=<name>
```

Show status, config, pending tasks, timeline length, and last run result.

### `reset <name>` — Start a fresh conversation or replace the agent

For a fresh conversation with the same agent, omit `sessionId` on the next chat
or request. To erase the complete runtime record and audit history, explicitly
`agent_delete remove=true` and recreate it; create never overwrites.

## Skills — What an Agent Can Pick Up

Skills are named bundles of instructions, context, and tools that an agent
loads **on demand**, so a lean agent stays lean until a task needs more. Full
reference: **`venue/docs/SKILLS.md`**.

Sources have two declared kinds: **`config.skills` contains individual skill
refs**, while **`config.skillsets` contains directories of skills**. Do not put
`w/skills` or `v/skills` under `skills`; `v/skills` is itself a directory of
skillsets, so the standard venue entry point is `v/skills/root`:

```
agent_create agentId="Bob" config=[
  "v/agents/templates/skilled",
  {"skillsets": ["w/skills", "v/skills/root"],
   "skills": ["v/skills/data/workspace", "a/<hash>"]}
]
```

Standard broad templates declare `skillsets: ["w/skills", "v/skills/root"]`;
role-specific templates combine those directories with a compact list of
individual `skills`. Explicit skills are resolved before skillsets, and each
kind is declaration-ordered, so **first source wins** within a kind.

A non-empty declaration of either kind switches on both halves of the feature:
the `[Skills]` index injected each turn (one `- name — description` line per
skill) and the `skill_load` harness tool. An agent with no sources has neither,
and behaves exactly as it did before skills existed.

### Hierarchical discovery

A loaded skill can contribute **further sources** through its own
`skill.skills` facet, so a short index opens onto a deeper library:

```
{"description": "Find data-engineering specialists",
 "skill": {"skillsets": ["v/skills/data"],
           "skills": ["w/team-skills/sql-review"]}}
```

Children are **discovered, not auto-loaded**. Loading the parent returns a
refreshed `skillIndex`, makes its children loadable by name from the next
step, and lists them in the following turn's index; loading a child may reveal
another layer. The resolver never walks an unloaded subtree, so cycles are
inert and a broad hierarchy never floods the prompt.

Effective sources are configured individual skills and skillsets followed by
the corresponding sources contributed by loaded skills, deduplicated
first-wins — configured sources keep precedence. Unloading a parent
(`context_unload`) retracts its contributed sources; children already loaded
stay loaded independently.

Contributing a ref grants no authority to read it — every source is
capability-checked as usual, and a skill's tools are still checked at
invocation. A skill's instructions enter the prompt verbatim and its tools
join the palette, so point `config.skills` and `config.skillsets` only at
sources you trust.

### Inspecting and authoring

| Want | Do |
|------|-----|
| See what a venue offers | `skills_list` (optionally `skillset=<dir>`; default = the venue's configured entry skillsets) |
| Read one without loading it | `skills_read skill=<path or a/hash>` |
| Import a SKILL.md from a file root or DLFS drive | `skills_import source=file://<root>/<dir>/SKILL.md` — one file per call, lands at `w/skills/<name>`; the venue's `v/skills/building/skill-import` skill covers the options |
| See exactly what an agent would send its model | `agent_context agentId=<name> message="…"` (or `task=…`, `sessionId=…`) — the level-3 input for that call with cache marks, budget and band marks |
| List/read safe past conversations | `agent_sessions agentId=<name>` then `agent_session_read agentId=<name> sessionId=<id>`; add `archiveDepth=N` to open nested compactions within the normal safety and size bounds |
| Compact an idle session as its owner | `agent_compact_session agentId=<name> sessionId=<id> summary="…"` — keeps the exact old vector under the compaction record and returns metadata only |
| Rebuild an idle session's provider prefix | `agent_reload_context agentId=<name> sessionId=<id>` — preserves conversation and loads, then applies current prompt/tools/pinned context on the next inference |
| Audit what an agent did in a cycle | `covia_read path=g/<name>/timeline` — each entry holds the cycle's `context`, `tools` and `inferences` (per call: `sent`, `reply`, `calls` with results and `ms`; a `subgoal` call carries its child `frame`) |
| Dry-run one harness iteration — "why was my tool denied?" | `agent_step agentId=<name> message="…" assistant={"toolCalls":[{"name":"<tool>","arguments":{…}}]}` — dispatches the calls as live (real side effects), returns `calls` with results/errors/ms and `next` (the following prompt); the agent's state is untouched |
| Write a personal skill | `covia_write path=w/skills/<name>` with `{description, content: {inline: "..."}, skill: {tools: [...], skills: [...]}}` |

A skill with no content is a pure toolset; one carrying an `operation` facet
offers itself as a tool. For the full authoring recipe, load the venue's own
`v/skills/skill-authoring`.

## Available Transition Operations

The `config.operation` field takes a resolvable operation **lattice path**:

| Operation path | Purpose |
|----------------|---------|
| `v/ops/llmagent/chat` | LLM-backed agent with conversation history, tool calls, task completion |
| `v/ops/goaltree/chat` | Goal-tree planning agent with structured decomposition |

Test transitions (`test:echo`, `test:taskcomplete`) exist for unit testing but are not pinned under `v/ops/`, so they aren't used as agent operation paths in normal use.

## LLM Backend Options

Set via `llmOperation` (a `v/ops/langchain/...` path):

| Operation path | Provider | Notes |
|----------------|----------|-------|
| `v/ops/langchain/openai` | OpenAI | Requires `OPENAI_API_KEY` secret |
| `v/ops/langchain/anthropic` | Anthropic (Claude) | Requires `ANTHROPIC_API_KEY` secret |
| `v/ops/langchain/ollama` | Ollama (local) | Requires Ollama running locally |
| `v/ops/langchain/mistral` | Mistral | Requires `MISTRAL_API_KEY` secret (`mistral-medium-latest` default) |
| `v/ops/langchain/openrouter` | OpenRouter (many vendors, one key) | Requires `OPENROUTER_API_KEY` secret; model ids like `anthropic/claude-sonnet-5`, default `openrouter/auto` |

Run `covia_list path=v/ops/langchain` to see every provider installed on the venue (e.g. `xai`).

## System Prompt Tips

- Include **data paths** if the agent should read workspace data (e.g. `w/vendor-records/{name}`)
- Include **output format** expectations (e.g. "Output a clean JSON object")
- Include **escalation rules** (e.g. "If critical fields cannot be validated, escalate for human review")
- Give the agent a **name and role** for clarity in multi-agent pipelines
