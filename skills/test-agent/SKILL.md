---
name: test-agent
description: Create, test, and diagnose Covia agents on a live venue. Runs the full feedback loop — create agent, send task, inspect timeline, diagnose issues, propose fixes. Use for developing and debugging agent behaviour.
argument-hint: [create|test|diagnose|reset] [agent-name]
---

# Agent Testing

Test and diagnose agents on a running venue via MCP tools or REST API.

## Prerequisites

- Venue running on `localhost:8080` with MCP connected, OR use REST fallback
- OpenAI API key set: `secret_set name=OPENAI_API_KEY value=sk-...`
- If MCP tools are unavailable, all operations work via REST: `curl -s http://localhost:8080/api/v1/invoke -H "Content-Type: application/json" -d '{"operation": "...", "input": {...}}'`

## Commands

### `create <name>` — Create a test agent

Create an agent with a specific configuration. Prompt for:
- System prompt (what the agent does)
- Tools (which operations it can call)
- Caps (capability restrictions — what paths it can read/write)
- Structured output (responseFormat schema)
- Context (asset hashes or workspace paths to load as system messages)

Example:

```
agent_create  agentId=TestBot  config={
  "operation": "v/ops/llmagent/chat",
  "llmOperation": "v/ops/langchain/openai",
  "model": "gpt-4o",
  "systemPrompt": "You are TestBot. ...",
  "tools": ["v/ops/covia/read", "v/ops/covia/write"],
  "caps": [{"with": "w/output/", "can": "crud/write"}, {"with": "w/", "can": "crud/read"}]
}
```

### `test <name>` — Send a task and observe

1. Send a task:
```
agent_request  agentId=<name>  input={"task": "..."}  wait=true
```

2. Check the result — did it complete? What output?

3. Verify side effects:
```
covia_read  path=w/<expected-path>
```

### `diagnose <name>` — Inspect what happened

Read the agent's full state to understand behaviour:

```
covia_read  path=g/<name>/status          → SLEEPING, RUNNING, SUSPENDED
covia_read  path=g/<name>/error           → error message if SUSPENDED
covia_read  path=g/<name>/config          → framework config (caps, tools, model)
covia_read  path=g/<name>/state/history   → full LLM conversation with tool calls
covia_read  path=g/<name>/timeline        → all run records with timing
covia_read  path=g/<name>/tasks           → pending tasks
covia_read  path=g/<name>/inbox           → unread messages
```

The **conversation history** (`state/history`) is the most important diagnostic — it shows every LLM turn, every tool call with arguments, and every tool result. Look for:

- **Missing tool calls** — LLM was supposed to call a tool but didn't
- **Wrong arguments** — LLM called the right tool with wrong params
- **Caps denial** — tool returned "Error: Capability denied: ..."
- **Tool errors** — tool returned an error string
- **Hallucinated values** — LLM used values not from tool results

### `reset <name>` — Start fresh

Delete and recreate an agent to test from clean state:

```
agent_delete  agentId=<name>
agent_create  agentId=<name>  config={...}  overwrite=true
```

Or resume a suspended agent:

```
agent_resume  agentId=<name>
```

## Diagnostic patterns

### Agent suspended — find the error

```
covia_read  path=g/<name>/error
```

Common causes:
- `invalid_request_error` — API key not set (`secret_set name=OPENAI_API_KEY`)
- `model_not_found` — wrong model name in config
- `rate_limit_exceeded` — too many requests, wait and retry

### Agent completed but wrong output

Read the conversation history to see what the LLM decided:

```
covia_read  path=g/<name>/state/history
```

Walk through tool calls:
1. Did it read the right data?
2. Did it pass the right values to the next tool?
3. Did it call `complete_task` with the right output?

### Caps not enforced

Check that config has caps at the record level:

```
covia_read  path=g/<name>/config/caps
```

If caps are present but not enforced, check the timeline for tool results — denied operations return `"Error: Capability denied: ..."` as the tool result.

### Agent didn't call complete_task

The LLM sometimes produces a text response instead of calling `complete_task`. Look for the last message in history — if it's `{"role": "assistant", "content": "..."}` without `toolCalls`, the LLM chose to respond conversationally instead of completing the task.

Fix: strengthen the system prompt with "You MUST call complete_task for every task" or use structured output (`responseFormat`) to force the schema.

## Caps testing

To test capability enforcement:

1. Create agent with restricted caps:
```json
"caps": [
  {"with": "w/allowed/", "can": "crud/write"},
  {"with": "w/", "can": "crud/read"}
]
```

2. Give it a task that tries to write outside the allowed path

3. Verify: allowed path should have data, forbidden path should be `exists: false`

4. Check history for the denied tool call — should show `"Error: Capability denied: ..."`

## Schema validation testing

To test strict mode in orchestrations:

1. Create orchestration with `"strict": true`
2. Run it with input that produces mismatched output
3. The orchestration should fail with a clear schema violation error naming the step and field

## Trigger vs Request

**`agent:trigger`** — Run the agent's transition loop. Always runs, even with no pending work — the agent may act proactively. When no tasks/messages are pending, the LLM receives a clear signal: "[No pending tasks, messages, or job results. You may act proactively or report idle.]"

**`agent:request`** — Send a specific task and (optionally) wait for the result. Preferred for most interactions since it gives the agent concrete input.

**System prompt guidance:** Agent prompts should say what to do with no work:
- Monitoring agent: "If no tasks are pending, check workspace for anomalies."
- Task processor: "If no tasks are pending, report idle and stop."
- The framework provides accurate context; the prompt determines behaviour.

## REST API fallback

When MCP tools aren't available, use curl. The request format:

```bash
curl -s http://localhost:8080/api/v1/invoke \
  -H "Content-Type: application/json" \
  -d '{"operation": "<op>", "input": <json>}'
```

Parse output with `| python -m json.tool` for readability.

Common operations:
```bash
# List agents
curl ... -d '{"operation": "v/ops/agent/list"}'

# Create agent
curl ... -d '{"operation": "v/ops/agent/create", "input": {"agentId": "...", "config": {...}}}'

# Send request
curl ... -d '{"operation": "v/ops/agent/request", "input": {"agentId": "...", "input": {...}, "wait": true}}'

# Read workspace
curl ... -d '{"operation": "v/ops/covia/read", "input": {"path": "..."}}'

# Read agent timeline
curl ... -d '{"operation": "v/ops/covia/read", "input": {"path": "g/<name>/timeline"}}'
```
