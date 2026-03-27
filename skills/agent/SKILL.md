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

1. **`config.operation` must be a plain string** — e.g. `"llmagent:chat"`, never `{"name": "llmagent:chat"}`. The agent runner calls `RT.ensureString()` on this field.

2. **Use `agent_request` with `input` parameter** to submit work — not `task`, not `message`. Requests create trackable Jobs with immutable records.

3. **`state.config` holds the LLM settings** — `llmOperation`, `model`, `systemPrompt`. This is separate from the framework `config`.

4. **Reset state when changing prompts** — use `agent_update` to clear conversation history, otherwise the LLM carries forward context from previous runs.

## Commands

### `create <name>` — Create an LLM-backed agent

```
agent_create
  agentId: "<name>"
  config: { "operation": "llmagent:chat" }
  state: { "config": {
    "llmOperation": "langchain:openai",
    "model": "gpt-4o",
    "systemPrompt": "<prompt>"
  }}
```

Ask the user for the system prompt if not provided. Ensure an OpenAI API key is stored (`secret_set`).

### `list` — List all agents

```
agent_list
```

Shows agent IDs, statuses, and task counts.

### `query <name>` — Inspect an agent

```
agent_query  agentId=<name>
```

Show status, config, pending tasks, timeline length, and last run result.

### `reset <name>` — Reset an agent (clear history, keep config)

Read the agent's current state.config, then update with fresh state:

```
agent_update  agentId=<name>  state={ "config": { <preserved LLM config> } }
```

This clears conversation history while keeping the system prompt and model settings.

## Available Transition Operations

| Operation | Purpose |
|-----------|---------|
| `llmagent:chat` | LLM-backed agent with conversation history, tool calls, task completion |
| `test:taskcomplete` | Auto-completes all tasks (testing only) |
| `test:echo` | Echoes input back (testing only) |

## LLM Backend Options

Set via `state.config.llmOperation`:

| Operation | Provider | Notes |
|-----------|----------|-------|
| `langchain:openai` | OpenAI | Requires `OPENAI_API_KEY` secret |
| `langchain:ollama` | Ollama (local) | Requires Ollama running locally |
| `langchain:ollama:qwen3` | Ollama Qwen3 | Specific model variant |

## System Prompt Tips

- Include **data paths** if the agent should read workspace data (e.g. `w/vendor-records/{name}`)
- Include **output format** expectations (e.g. "Output a clean JSON object")
- Include **escalation rules** (e.g. "If critical fields cannot be validated, escalate for human review")
- Give the agent a **name and role** for clarity in multi-agent pipelines
