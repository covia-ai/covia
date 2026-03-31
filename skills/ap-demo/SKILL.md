---
name: ap-demo
description: Set up and run the AP Invoice Audit Trail demo. Creates three agents (Alice, Bob, Carol) on a Covia venue, seeds reference data, and runs an invoice through the pipeline. Use when the user wants to demo or test the AP workflow.
argument-hint: [setup|run|run bad|run batch|trace|reset|status|setup-desktop]
---

# AP Invoice Audit Trail Demo

**Prerequisite:** The venue must be running and connected as an MCP server. If tools like `agent_list` are not available, tell the user to run `/venue-setup local` first, then add the MCP endpoint (`http://localhost:8080/mcp`) to their Claude Code MCP settings.

You are setting up or running a demo of Covia's accounts payable pipeline. Three LLM-backed agents process invoices with a complete immutable audit trail. All agents use **strict structured output** — every response is schema-enforced JSON with guaranteed field names.

## Agents

| Agent | Role | What they do | Output Schema |
|-------|------|-------------|---------------|
| **Alice** | Invoice Scanner | Extracts structured data from raw invoice text | `InvoiceExtraction` — vendor, line items, amounts, PO, flags |
| **Bob** | Data Enricher | Validates vendor records, checks duplicates, matches POs — **autonomously calls tools** | `InvoiceEnrichment` — validations, confidence, source refs |
| **Carol** | Payment Approver | Policy gate — applies 6 named rules and records reasoning | `ApprovalDecision` — decision, policy matrix, reasoning |

## Commands

Based on `$ARGUMENTS`:

### `setup` (or no argument)

Run all setup steps — secret, reference data, agents:

1. **Ask for OpenAI API key** if not already stored, then `secret_set`
2. **Seed reference data** — three vendors (Acme Corp, Globex Ltd, Initech Systems) and their purchase orders (see [setup.md](setup.md)). Initech is suspended/sanctioned to enable rejection demos.
3. **Create all three agents** in parallel (see [setup.md](setup.md) for full config including `responseFormat` schemas)
4. **Verify** with `agent_list` — all three should be SLEEPING
5. **Confirm structured output** — query any agent and verify `state.config.responseFormat` is present

Key config rules:
- `config.operation` must be a **plain string** `"llmagent:chat"`, not a map
- `state.config.responseFormat` must be a map with `name` (string) and `schema` (JSON Schema with `additionalProperties: false` at every object level) — this enables OpenAI strict structured output
- Use `agent_request` with `input` parameter (not `task`) for submitting work
- Include data paths in Bob's system prompt so he knows where to look

### `run` (or `run <scenario>`)

Run the invoice pipeline (Alice → Bob → Carol):

1. Send the invoice to Alice via `agent_request` with `wait: true`
2. Forward Alice's `InvoiceExtraction` to Bob (include `source_agent` and `source_job` for provenance). Because Alice uses strict schema output, you can reliably reference `vendor_name`, `po_number`, and other fields by name.
3. After Bob responds, **verify workspace persistence**: `covia_read path=w/enrichments/{invoice_number}` — mention this to the audience, it shows Bob wrote to the Convex lattice.
4. Forward Bob's `InvoiceEnrichment` to Carol (include `source_pipeline` with both job IDs)
5. Present Carol's `ApprovalDecision` with the policy rule matrix — each rule has a name, PASS/FAIL, and evidence
6. Show the structured audit trail and provenance chain (see run.md Steps 4–5)

**Scenarios** (see [run.md](run.md) for full invoice texts and expected outcomes):

| Argument | Invoice | Expected Outcome |
|----------|---------|-----------------|
| `run` | Acme Corp, $15,600 | ESCALATED — AP-001 (manager approval) |
| `run bad` | Initech Systems (sanctioned), $6,500 | REJECTED — AP-002 (sanctions) |
| `run small` | Globex Ltd, $800 | APPROVED — all rules pass |
| `run large` | Globex Ltd, $75,500 | ESCALATED — AP-001 (VP approval) |
| `run unknown` | Nexus Dynamics (not in system) | REJECTED — AP-003/AP-005 |

### `run batch`

Submit all four scenario invoices to Alice concurrently (without `wait`), then process each through Bob and Carol. Demonstrates the async job model and parallel execution. Present the comparison table from [run.md](run.md) — Batch Processing section.

### `trace`

Walk the provenance chain backward from Carol's most recent decision to Alice's raw input. Query all three agent timelines:

```
agent_query  agentId=Carol
agent_query  agentId=Bob
agent_query  agentId=Alice
```

Because all agents use strict schemas, the `result` field in each timeline entry contains guaranteed structured output. Extract the latest entry from each and build the provenance chain:

```
PROVENANCE CHAIN — {invoice_number}

[3] Carol: {decision} — {policy_rules_applied.length} rules evaluated
    ↑
[2] Bob: confidence {confidence_score} — {source_references.length} lookups
    ↑
[1] Alice: {vendor_name}, {total_amount} {currency}
    ↑
[0] Raw invoice text
```

See [run.md](run.md) — Step 5 for the full structured format.

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

Report agent statuses, timeline lengths, whether `responseFormat` is configured, and any errors.

### `setup-desktop`

Show the user the Claude Desktop project instructions so they can paste them into a Claude Desktop project. Read and display the contents of [desktop-instructions.md](desktop-instructions.md). Explain:

1. The venue must be running and agents set up (via `/ap-demo setup` in Claude Code)
2. In Claude Desktop, create a new Project and paste the instructions into the project's custom instructions
3. Add the venue as an MCP server in Claude Desktop's settings: `http://localhost:8080/mcp`
4. Then the user can run the demo conversationally in Claude Desktop
