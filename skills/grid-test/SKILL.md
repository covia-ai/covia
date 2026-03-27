---
name: grid-test
description: Smoke test a Covia venue by running basic operations — echo, LLM, grid. Use to verify a venue is healthy and adapters are working.
argument-hint: [quick|full]
---

# Venue Smoke Test

Run a quick health check on the connected Covia venue.

## `quick` (default)

Run these in parallel and report results:

1. **Echo test** — verify basic operation execution:
   ```
   grid_run  operation=test:echo  input={"ping": "pong"}
   ```
   Expected: returns `{"ping": "pong"}` unchanged.

2. **List adapters** — verify all adapters are registered:
   ```
   covia_adapters
   ```
   Expected: should show adapters (test, agent, llmagent, grid, langchain, convex, http, jvm, covia, secret, ucan, mcp, orchestrator).

3. **List functions** — verify operations are installed:
   ```
   list_functions
   ```
   Expected: 40+ functions across all adapters.

Report a summary table: adapter count, function count, echo test pass/fail.

## `full`

Run all quick tests plus:

4. **LLM test** — verify OpenAI connectivity (requires API key):
   ```
   grid_run  operation=langchain:openai
     input={ "messages": [{"role": "user", "content": "Say hello in exactly 3 words"}] }
   ```
   Expected: assistant message response.

5. **Agent lifecycle** — create, request, query, delete:
   ```
   agent_create  agentId=_smoke_test  config={"operation": "test:taskcomplete"}
   agent_request  agentId=_smoke_test  input={"test": true}  wait=true
   agent_query  agentId=_smoke_test
   agent_delete  agentId=_smoke_test  remove=true
   ```
   Expected: agent runs, task completes, timeline has one entry.

6. **Workspace CRUD** — write, read, delete:
   ```
   covia_write  path=w/_smoke_test  value={"ts": <now>}
   covia_read  path=w/_smoke_test
   covia_delete  path=w/_smoke_test
   ```
   Expected: write succeeds, read returns value, delete succeeds.

Report a full summary table with pass/fail for each test and any errors encountered.
