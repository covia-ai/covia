---
name: grid-test
description: Smoke test a Covia venue by running basic operations — echo, LLM, grid. Use to verify a venue is healthy and adapters are working.
argument-hint: "<quick|full>"
---

# Venue Smoke Test

**Prerequisite:** The venue must be running and connected as an MCP server (`http://localhost:8080/mcp`). If MCP tools are not available, tell the user to run `/venue-setup local` first.

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
   covia_list  path=v/info/adapters
   ```
   Expected: should show adapters (test, agent, llmagent, grid, langchain, convex, http, jvm, covia, secret, ucan, mcp, orchestrator).

3. **List operations catalog** — verify operations are installed:
   ```
   covia_list  path=v/ops
   ```
   Expected: 40+ operations across all adapters under their per-adapter sub-namespaces.

Report a summary table: adapter count, function count, echo test pass/fail.

## `full`

Run all quick tests plus the tests below. **Requires one configured LLM provider.** Prefer Anthropic when `ANTHROPIC_API_KEY` is ready; otherwise use a provider/model the venue reports ready. Do not assume OpenAI is configured.

4. **LLM test** — verify provider connectivity (Anthropic example):
   ```
   grid_run  operation=v/ops/langchain/anthropic
     input={ "messages": [{"role": "user", "content": "Say hello in exactly 3 words"}] }
   ```
   Expected: assistant message response. If Anthropic is not ready, select another ready provider rather than treating that as a venue failure.

5. **Agent lifecycle** — create, request, query, delete:
   ```
   agent_create  agentId=_smoke_test  config={"operation": "v/test/ops/taskcomplete"}
   agent_request  agentId=_smoke_test  input={"test": true}  timeout=30000
   agent_info  agentId=_smoke_test
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
