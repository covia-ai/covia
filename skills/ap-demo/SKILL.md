---
name: ap-demo
description: Set up and run the AP Invoice Audit Trail demo. Creates three agents (Alice, Bob, Carol) on a Covia venue, seeds reference data, and runs an invoice through the pipeline. Use when the user wants to demo or test the AP workflow.
argument-hint: [setup|run|run bad|run batch|trace|reset|status|setup-desktop]
---

# AP Invoice Audit Trail Demo

**Prerequisite:** The venue must be running and connected as an MCP server. If tools like `agent_list` are not available, tell the user to run `/venue-setup local` first, then add the MCP endpoint (`http://localhost:8080/mcp`) to their Claude Code MCP settings.

You are setting up or running a demo of Covia's accounts payable pipeline. Three LLM-backed agents process invoices with a complete immutable audit trail.

## Agents

| Agent | Role | What they do |
|-------|------|-------------|
| **Alice** | Invoice Scanner | Extracts structured data from raw invoice text |
| **Bob** | Data Enricher | Validates vendor records, checks duplicates, matches POs |
| **Carol** | Payment Approver | Policy gate — applies approval rules and records reasoning |

## Commands

Based on `$ARGUMENTS`:

### `setup` (or no argument)

Run all setup steps — secret, reference data, agents:

1. **Ask for OpenAI API key** if not already stored, then `secret_set`
2. **Seed reference data** — three vendors (Acme Corp, Globex Ltd, Initech Systems) and their purchase orders (see [setup.md](setup.md)). Initech is suspended/sanctioned to enable rejection demos.
3. **Create all three agents** in parallel (see [setup.md](setup.md) for full config)
4. **Verify** with `agent_list` — all three should be SLEEPING

Key config rules:
- `config.operation` must be a **plain string** `"llmagent:chat"`, not a map
- Use `agent_request` with `input` parameter (not `task`) for submitting work
- Include data paths in Bob's system prompt so he knows where to look

### `run` (or `run <scenario>`)

Run the invoice pipeline (Alice → Bob → Carol):

1. Send the invoice to Alice via `agent_request` with `wait: true`
2. Forward Alice's structured output to Bob (include `source_agent` and `source_job` for provenance)
3. Forward Bob's enriched record to Carol (include `source_pipeline` with both job IDs)
4. Summarise the result at each step
5. After Carol decides, query Bob's timeline (`agent_query`) to show his autonomous tool calls

**Scenarios** (see [run.md](run.md) for full invoice texts and expected outcomes):

| Argument | Invoice | Expected Outcome |
|----------|---------|-----------------|
| `run` | Acme Corp, $15,600 | ESCALATED — manager approval |
| `run bad` | Initech Systems (sanctioned), $6,500 | REJECTED — sanctions flag |
| `run small` | Globex Ltd, $800 | APPROVED — auto-approve |
| `run large` | Globex Ltd, $75,500 | ESCALATED — VP approval |
| `run unknown` | Nexus Dynamics (not in system) | REJECTED — unknown vendor |

### `run batch`

Submit all four scenario invoices to Alice concurrently (without `wait`), then process each through Bob and Carol. Demonstrates the async job model and parallel execution. Present a summary table of outcomes. See [run.md](run.md) — Batch Processing section.

### `trace`

Walk the provenance chain backward from Carol's most recent decision to Alice's raw input. Query all three agent timelines, extract the latest entries, and present the chain:

```
Carol (decision + reasoning)
  ← Bob (enrichment + tool calls)
    ← Alice (extraction)
      ← Raw invoice text
```

See [run.md](run.md) — Step 5: Trace Provenance.

### `reset`

Delete all three agents and recreate them fresh:

```
agent_delete  agentId=Alice  remove=true
agent_delete  agentId=Bob    remove=true
agent_delete  agentId=Carol  remove=true
```

Then re-run setup (step 3 only — secret and reference data persist).

### `status`

Check current state:

```
agent_list
agent_query  agentId=Alice   (if exists)
agent_query  agentId=Bob     (if exists)
agent_query  agentId=Carol   (if exists)
```

Report agent statuses, timeline lengths, and any errors.

### `setup-desktop`

Show the user the Claude Desktop project instructions so they can paste them into a Claude Desktop project. Read and display the contents of [desktop-instructions.md](desktop-instructions.md). Explain:

1. The venue must be running and agents set up (via `/ap-demo setup` in Claude Code)
2. In Claude Desktop, create a new Project and paste the instructions into the project's custom instructions
3. Add the venue as an MCP server in Claude Desktop's settings: `http://localhost:8080/mcp`
4. Then the user can run the demo conversationally in Claude Desktop
