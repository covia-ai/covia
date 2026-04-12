# AP Demo Setup

## Quick Start

```bash
bash skills/ap-demo/setup.sh [VENUE_URL]
```

This seeds reference data, documents, the pipeline orchestration, and creates all four agents. Default venue: `http://localhost:8080`. You must store the OpenAI API key separately first (step 1 below).

## Authentication Note

The local venue runs with public access enabled. All unauthenticated MCP clients share the same identity (`{venueDID}:public`), so agents created in Claude Code are visible in Claude Desktop and vice versa. In production, each client authenticates with a cryptographic identity (DID) and gets isolated namespaces.

## 1. Store OpenAI API Key

```
secret_set  name=OPENAI_API_KEY  value=<ask-user>
```

## 2. Seed Reference Data

Three vendors (Acme Corp active, Globex Ltd active, Initech Systems suspended/sanctioned) and their purchase orders. Seeded by `setup.sh` via `covia_write` to `w/vendor-records/` and `w/purchase-orders/`.

## 3. Store Shared Documents and Orchestration

Everything lives at named lattice paths — no opaque content hashes.

| What | Path | Source file |
|------|------|-------------|
| Policy rules | `w/docs/policy-rules` | `assets/ap-policy-rules.md` |
| Data guide | `w/docs/data-guide` | `assets/ap-data-guide.md` |
| Pipeline | `o/ap-pipeline` | `assets/ap-pipeline.json` |

The pipeline is callable by name: `grid_run operation=o/ap-pipeline input={...}`.

## 4. Create Agents

Agent configs are standalone JSON files in `assets/`. Each is a complete `agent_create` input — pipe directly to the API or use `setup.sh`.

| Agent | Config | Model | Tools | Caps | Outputs |
|-------|--------|-------|-------|------|---------|
| Alice | [alice.json](assets/alice.json) | gpt-4o-mini | none | `[]` (deny all) | responseFormat (extraction schema) |
| Bob | [bob.json](assets/bob.json) | gpt-4.1-mini | covia_read, covia_write, covia_list | read vendors/POs/docs, write invoices/enrichments | typed complete (enrichment schema) |
| Carol | [carol.json](assets/carol.json) | gpt-4.1-mini | covia_read, covia_write | read all w/, write decisions only | typed complete (decision schema) |
| Dave | [dave.json](assets/dave.json) | gpt-4.1-mini | 8 ops + subgoal, compact, more_tools | read w/, agent message/request, invoke | none (conversational) |

All use `goaltree:chat` transition with `defaultTools: false`. Typed `outputs` auto-inject `complete`/`fail` tools with schema enforcement. `overwrite: true` allows re-running setup to update configs without losing timelines.

### Design principles

- **Curated tools** — each agent gets only the tools its workflow needs. Bob gets 3, Carol gets 2, Alice gets 0. Fewer tools = less confusion, fewer tokens.
- **Typed outputs** — Bob and Carol declare `outputs.complete.schema`. The harness synthesises a schema-enforced `complete()` tool. Text-only responses are rejected — the LLM must call `complete()`.
- **Context loading** — `state.config.context` injects reference material as system messages before each run. Keeps system prompts short (identity + behaviour) and shared docs in one place.
- **Capability enforcement** — `caps` scope what each agent can read/write. Denied tool calls return an error listing the agent's actual capabilities.
- **Harness tools opt-in** — Dave gets `subgoal`, `compact`, `more_tools` for complex work. Pipeline agents don't need them.

### Context entries

| Agent | Context |
|-------|---------|
| Bob | AP Data Guide (workspace ref), Known Vendors (op: covia_list at load time) |
| Carol | AP Policy Rules (workspace ref), Vendor Records (workspace ref) |
| Dave | Policy Rules, Data Guide, Pipeline orchestration (all workspace/operation refs) |

## 5. Verify

```
agent_list  → Alice, Bob, Carol, Dave all SLEEPING
```

Confirm configs:
```
agent_info  agentId=Bob   → state.config.tools has 3 entries, caps has 5, outputs.complete.schema present
agent_info  agentId=Carol → state.config.tools has 2 entries, caps has 2, outputs.complete.schema present
agent_info  agentId=Dave  → state.config.tools has 11 entries (8 ops + 3 harness), caps has 4
agent_info  agentId=Alice → state.config.caps is [], defaultTools is false
```
