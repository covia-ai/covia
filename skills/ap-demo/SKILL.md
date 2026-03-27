---
name: ap-demo
description: Set up and run the AP Invoice Audit Trail demo. Creates three agents (Alice, Bob, Carol) on a Covia venue, seeds reference data, and runs an invoice through the pipeline. Use when the user wants to demo or test the AP workflow.
argument-hint: [setup|run|reset|status]
---

# AP Invoice Audit Trail Demo

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
2. **Seed reference data** — vendor record and purchase order (see [setup.md](setup.md))
3. **Create all three agents** in parallel (see [setup.md](setup.md) for full config)
4. **Verify** with `agent_list` — all three should be SLEEPING

Key config rules:
- `config.operation` must be a **plain string** `"llmagent:chat"`, not a map
- Use `agent_request` with `input` parameter (not `task`) for submitting work
- Include data paths in Bob's system prompt so he knows where to look

### `run`

Run the invoice pipeline:

1. Send the default invoice to Alice via `agent_request` with `wait: true`
2. Forward Alice's structured output to Bob (include `source_agent` and `source_job` for provenance)
3. Forward Bob's enriched record to Carol (include `source_pipeline` with both job IDs)
4. Summarise the result at each step
5. After Carol decides, query Bob's timeline (`agent_query`) to show his autonomous tool calls

See [run.md](run.md) for the full pipeline flow and presentation guidance.

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
