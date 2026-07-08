---
name: agent
description: Create, configure, and manage Covia agents. Handles config gotchas, system prompts, LLM backend setup, and lifecycle operations. Use when working with agents on a venue.
argument-hint: [create|list|query|reset] [agent-name]
---

# Agent Management

**Prerequisite:** The venue must be running and connected as an MCP server (`http://localhost:8080/mcp`). If MCP tools are not available, tell the user to run `/venue-setup local` first.

Manage agents on a connected Covia venue via MCP.

## Key Config Rules

These are critical — getting them wrong produces silent failures:

1. **`config.operation` must be a plain string** — e.g. `"v/ops/llmagent/chat"`, never `{"name": "v/ops/llmagent/chat"}`. The agent runner calls `RT.ensureString()` on this field.

2. **Use `agent_request` with `input` parameter** to submit work — not `task`, not `message`. Requests create trackable Jobs with immutable records.

3. **`config` is the single home for ALL agent settings (#144)** — `operation`, `llmOperation`, `model`, `systemPrompt`, `tools`, `caps` all live in the top-level `config` map. Passing a `config` key inside `state` is rejected with an error.

4. **Reset state when changing prompts** — use `agent_update` to clear conversation history, otherwise the LLM carries forward context from previous runs.

5. **Operation references are lattice paths, not adapter shorthand** — `config.operation`, `llmOperation`, and every entry in `tools` must be a resolvable `v/ops/...` path (e.g. `v/ops/covia/write`), never the `adapter:op` form (`covia:write`). Adapter shorthand does **not** resolve — the agent silently ends up without that tool (you'll see `ContextBuilder -- Config tool: cannot resolve operation 'covia:write'` in the venue log). Harness tools (`subgoal`, `complete`, `fail`, `compact`, `context_load`, `context_unload`, `more_tools`) are the lone exception — they're bare names, not operations. A custom agent that must read/write the workspace has to list those ops explicitly (e.g. `"tools": ["v/ops/covia/read", "v/ops/covia/write"]`) or start from a template such as `worker`.

## Commands

### `create <name>` — Create an LLM-backed agent

```
agent_create
  agentId: "<name>"
  config: { "operation": "v/ops/llmagent/chat" }
  state: { "config": {
    "llmOperation": "v/ops/langchain/openai",
    "model": "gpt-5.4-mini",
    "systemPrompt": "<prompt>"
  }}
```

Ask the user for the system prompt if not provided. Ensure an OpenAI API key is stored (`secret_set`).

### Create from a template

Pre-built agent configs ship at **`v/agents/templates/<name>`** (browse with `covia_list path=v/agents/templates`). Pass the path as `config`:

```
agent_create  agentId="Bob"  config="v/agents/templates/worker"
```

Available: `minimal` (pure reasoning, no tools), `reader` (read-only covia), `worker` (covia CRUD), `analyst` (covia + schema), `manager` / `goaltree` (goal-tree planners), `full` (full default toolset).

Templates default to `llmOperation: v/ops/langchain/openai` + `model: gpt-5.4-mini`. To run on another provider, override after creating — for template-made agents the LLM settings live in the **top-level `config`**:

```
agent_update  agentId="Bob"  config={ "llmOperation": "v/ops/langchain/anthropic", "model": "claude-sonnet-4-6" }
```

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

### `reset <name>` — Reset an agent (clear history, keep config)

Config lives on the record and survives state changes (#144) — reset by
replacing state only:

```
agent_update  agentId=<name>  state={}
```

This clears conversation working state while keeping config untouched.

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

Run `covia_list path=v/ops/langchain` to see every provider installed on the venue (e.g. `xai`).

## System Prompt Tips

- Include **data paths** if the agent should read workspace data (e.g. `w/vendor-records/{name}`)
- Include **output format** expectations (e.g. "Output a clean JSON object")
- Include **escalation rules** (e.g. "If critical fields cannot be validated, escalate for human review")
- Give the agent a **name and role** for clarity in multi-agent pipelines
