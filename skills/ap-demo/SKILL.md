---
name: ap-demo
description: Set up and run the AP Invoice Audit Trail demo. Creates three agents (Alice, Bob, Carol) on a Covia venue, seeds reference data, and runs an invoice through the pipeline. Use when the user wants to demo or test the AP workflow.
argument-hint: [setup|run|run bad|run batch|trace|reset|status|setup-desktop]
---

# AP Invoice Audit Trail Demo

**Prerequisite:** The venue must be running and connected as an MCP server. If tools like `agent_list` are not available, tell the user to run `/venue-setup local` first, then add the MCP endpoint (`http://localhost:8080/mcp`) to their Claude Code MCP settings.

You are setting up or running a demo of Covia's accounts payable pipeline. Four agents process invoices with capability-scoped permissions, shared context documents, and a complete immutable audit trail.

## Key Features

- **Orchestration** — Alice → Bob → Carol runs as a single declarative pipeline job
- **Context loading** — policy rules and data guide stored as immutable artifacts, loaded into agent context automatically
- **Capability enforcement** — each agent can only read/write specific workspace paths
- **Provenance** — every task and message shows who sent it

## Agents

| Agent | Role | What they do | Caps |
|-------|------|-------------|------|
| **Alice** | Invoice Scanner | Extracts structured data from raw invoice text | No caps (extraction only, no tools) |
| **Bob** | Data Enricher | Validates vendor records, checks duplicates, matches POs — **autonomously calls tools** | Read vendors/POs/invoices, write enrichments only |
| **Carol** | Payment Approver | Policy gate — applies 6 named rules and records reasoning | Read all workspace, write decisions only |
| **Dave** | AP Manager | Conversational manager — runs pipeline, investigates, answers questions | Read-only workspace, invoke orchestrations |

## Commands

Based on `$ARGUMENTS`:

### `setup` (or no argument)

Run all setup steps — secret, reference data, artifacts, orchestration, agents:

1. **Ask for OpenAI API key** if not already stored, then `secret_set`
2. **Seed reference data** — three vendors (Acme Corp, Globex Ltd, Initech Systems) and their purchase orders (see [setup.md](setup.md)). Initech is suspended/sanctioned to enable rejection demos.
3. **Store shared artifacts** — read `assets/ap-policy-rules.md` and `assets/ap-data-guide.md`, store each as an artifact via `asset_store` with `contentText`. Note the returned hashes.
4. **Store the orchestration** — read `assets/ap-pipeline.json`, store via `asset_store`. Store the pipeline hash at `w/config/ap-pipeline`.
5. **Create all four agents** in parallel (see [setup.md](setup.md) for full config). Agent prompts are short — reference material loads from context. Each agent has `caps` scoping their workspace access.
6. **Verify** with `agent_list` — Alice, Bob, Carol, Dave all SLEEPING
7. **Confirm context and caps** — query Bob or Carol and verify `state.config.context` and `state.config.caps` are present

Key config rules:
- `config.operation` must be a **plain string** `"v/ops/goaltree/chat"`, not a map
- `state.config.context` is an array of asset hashes or workspace paths — loaded as system messages before each run
- `state.config.caps` is an array of `{with, can}` attenuations — enforced on every tool call. No caps = full access.
- `state.config.responseFormat` must have `additionalProperties: false` at every object level for OpenAI strict mode
- Use `agent_request` with `input` parameter (not `task`) for submitting work

### `run` (or `run <scenario>`)

Run the invoice pipeline via the stored orchestration:

1. Read the pipeline hash from `w/config/ap-pipeline`
2. Run via `grid_run operation=<hash> input={"invoice_text": "..."}`
3. The orchestration chains Alice → Bob → Carol as a single job, returning `{extraction, enrichment, decision}`
4. Present the result: Alice's extraction, Bob's validation, Carol's policy rule matrix
5. **Verify workspace persistence**: `covia_read path=w/enrichments/{invoice_number}` and `covia_read path=w/decisions/{invoice_number}`
6. Show the structured audit trail and provenance chain (see run.md Steps 4–5)

Alternatively, ask Dave to process the invoice — he knows the orchestration hash from his context and will run it via `grid_run`.

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

## Optional Enhancements

These are not implemented but would improve the demo:

- **Orchestration-driven persistence.** Currently Bob and Carol write to workspace via LLM tool calls, which is non-deterministic — they sometimes skip the write. A more reliable approach: add `covia:write` steps to the orchestration after Bob and Carol, so persistence is guaranteed by the pipeline rather than the LLM. Requires a `concat` input spec type (or `jvm:stringConcat` steps) to build dynamic paths like `w/enrichments/{invoice_number}`.

- **Duplicate detection across runs.** Bob checks `w/invoices/{vendor}/{invoice_number}` for duplicates but nothing writes there. Add an orchestration step that writes to `w/invoices/` after Alice extracts, so subsequent runs of the same invoice are caught.

- **Carol reads Bob's persisted enrichment.** Instead of receiving enrichment data inline from the orchestration, Carol could read `w/enrichments/{invoice_number}` directly. This validates that Bob actually wrote it and creates a cleaner data dependency.

- **Dave as orchestration manager.** Dave could discover and invoke orchestrations by listing assets with `type=orchestration` rather than needing the hash in his context.

### `setup-desktop`

Show the user the Claude Desktop project instructions so they can paste them into a Claude Desktop project. Read and display the contents of [desktop-instructions.md](desktop-instructions.md). Explain:

1. The venue must be running and agents set up (via `/ap-demo setup` in Claude Code)
2. In Claude Desktop, create a new Project and paste the instructions into the project's custom instructions
3. Add the venue as an MCP server in Claude Desktop's settings: `http://localhost:8080/mcp`
4. Then the user can run the demo conversationally in Claude Desktop
