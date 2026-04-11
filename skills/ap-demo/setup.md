# AP Demo Setup

## Authentication Note

The local venue runs with public access enabled. All unauthenticated MCP clients share the same identity (`{venueDID}:public`), so agents created in Claude Code are visible in Claude Desktop and vice versa. In production, each client authenticates with a cryptographic identity (DID) and gets isolated namespaces.

## 1. Store OpenAI API Key

```
secret_set  name=OPENAI_API_KEY  value=<ask-user>
```

## 2. Seed Reference Data

Seed all vendor records and purchase orders below. This gives Bob real data to query and enables multiple demo scenarios (happy path, rejection, VP escalation).

### Vendor records

```
covia_write  path=w/vendor-records/Acme Corp
value: {
  "vendor_id": "V-1042",
  "name": "Acme Corp",
  "status": "ACTIVE",
  "tax_id": "US-84-2917345",
  "payment_method": "ACH",
  "bank_account": "****7892",
  "sanctions_check": "CLEAR",
  "last_reviewed": "2024-09-15"
}
```

```
covia_write  path=w/vendor-records/Globex Ltd
value: {
  "vendor_id": "V-2087",
  "name": "Globex Ltd",
  "status": "ACTIVE",
  "tax_id": "GB-12-8834521",
  "payment_method": "WIRE",
  "bank_account": "****3310",
  "sanctions_check": "CLEAR",
  "last_reviewed": "2024-11-01"
}
```

```
covia_write  path=w/vendor-records/Initech Systems
value: {
  "vendor_id": "V-3201",
  "name": "Initech Systems",
  "status": "SUSPENDED",
  "tax_id": "US-91-5567890",
  "payment_method": "ACH",
  "bank_account": "****4455",
  "sanctions_check": "FLAGGED",
  "sanctions_detail": "OFAC SDN List — added 2024-08-20",
  "last_reviewed": "2024-08-22"
}
```

### Purchase orders

```
covia_write  path=w/purchase-orders/Acme Corp/PO-2024-0456
value: {
  "po_number": "PO-2024-0456",
  "vendor": "Acme Corp",
  "amount_authorised": 20000,
  "currency": "USD",
  "department": "Engineering",
  "budget_code": "ENG-INFRA-2024",
  "status": "OPEN",
  "approver": "J. Martinez"
}
```

```
covia_write  path=w/purchase-orders/Globex Ltd/PO-2024-0790
value: {
  "po_number": "PO-2024-0790",
  "vendor": "Globex Ltd",
  "amount_authorised": 150000,
  "currency": "USD",
  "department": "Operations",
  "budget_code": "OPS-PLATFORM-2024",
  "status": "OPEN",
  "approver": "D. Chen"
}
```

```
covia_write  path=w/purchase-orders/Initech Systems/PO-2024-0312
value: {
  "po_number": "PO-2024-0312",
  "vendor": "Initech Systems",
  "amount_authorised": 8000,
  "currency": "USD",
  "department": "IT",
  "budget_code": "IT-MAINT-2024",
  "status": "OPEN",
  "approver": "R. Kapoor"
}
```

## 3. Store Shared Documents and Orchestration

Everything in this demo lives at named lattice paths — no opaque content
hashes anywhere. Agents reference docs and the pipeline by name; the LLM
can see exactly what's there via `covia:list` / `covia:read`.

### Documents in workspace (for agent context)

```
covia_write  path=w/docs/policy-rules  value=<contents of assets/ap-policy-rules.md as string>
covia_write  path=w/docs/data-guide    value=<contents of assets/ap-data-guide.md as string>
```

### Pipeline orchestration

Write the orchestration metadata directly to your operation pins at
`o/ap-pipeline`. Per OPERATIONS.md §5, any map with an `operation` field
under `/o/<name>` is callable by name via `grid_run operation=o/<name>` —
no separate `asset_store` step, no hash dereferencing.

```
covia_write  path=o/ap-pipeline  value=<contents of assets/ap-pipeline.json>
```

After this, `grid_run operation=o/ap-pipeline input={...}` runs the
pipeline. `covia_read path=o/ap-pipeline` shows the full orchestration
structure (steps, schemas, result mapping) in plain JSON — transparent
to agents and humans alike.

## 4. Create Agents

All four use `goaltree:chat` transition with `langchain:openai` (gpt-4o-mini). Create in parallel.

**IMPORTANT:** `config.operation` must be a plain string `"v/ops/goaltree/chat"`, not a map.

Agents use **context loading** to receive shared reference material. The `context` array in `state.config` lists asset hashes, workspace paths, and op-based entries that are resolved and injected as system messages before each run. This keeps system prompts short (identity + behaviour only) and shared docs in one place.

### Context entry types used in this demo

| Type | Example | What it does |
|------|---------|-------------|
| **Lattice ref** | `{"ref": "w/vendor-records", "label": "..."}` | Reads any resolvable path (workspace, `/o/`, `/v/`, asset, DID URL), rendered via CellExplorer with budget control |
| **Op-based** | `{"op": "v/ops/covia/list", "input": {...}, "label": "..."}` | Runs an operation at context-load time, injects result |

All pipeline agents use **strict structured output** via `responseFormat`. Every `responseFormat` schema must have `additionalProperties: false` at every object level for OpenAI strict mode.

**Re-running setup:** every `agent_create` block below passes `overwrite: true`. On a fresh venue this is a no-op (slot empty); on a venue that already has the agent, it updates the config in place — preserving the timeline, inbox, and tasks. Re-run setup any time you tweak a system prompt, capability set, or context entry; the change applies on the next agent run. RUNNING agents are rejected (race-unsafe) — wait for them to return to SLEEPING first.

### Alice — Invoice Scanner

Alice does pure text extraction — no tools needed. Empty `caps: []` denies all tool calls, making her capability surface explicit.

```
agent_create
  agentId: "Alice"
  overwrite: true
  config: { "operation": "v/ops/goaltree/chat" }
  state: { "config": {
    "llmOperation": "v/ops/langchain/openai",
    "model": "gpt-4o-mini",
    "caps": [],
    "systemPrompt": "You are Alice, an AP Invoice Scanner. Extract structured invoice fields from raw text. Your output is schema-enforced — populate every field. Use empty string for missing text fields and empty array for missing lists. Add a flag for every field that required interpretation or was ambiguous. Be precise with amounts.",
    "responseFormat": {
      "name": "InvoiceExtraction",
      "schema": {
        "type": "object",
        "properties": {
          "vendor_name": { "type": "string", "description": "Exact vendor name as it appears on the invoice" },
          "invoice_number": { "type": "string" },
          "date": { "type": "string", "description": "Invoice date in ISO 8601 format (YYYY-MM-DD)" },
          "line_items": { "type": "array", "items": {
            "type": "object",
            "properties": {
              "description": { "type": "string" },
              "amount": { "type": "number" },
              "currency": { "type": "string" }
            },
            "required": ["description", "amount", "currency"],
            "additionalProperties": false
          }},
          "total_amount": { "type": "number" },
          "currency": { "type": "string", "description": "Three-letter currency code" },
          "payment_terms": { "type": "string", "description": "e.g. Net 30, Net 45" },
          "po_number": { "type": "string", "description": "Purchase order number, or empty string if not present" },
          "flags": { "type": "array", "items": { "type": "string" }, "description": "Missing or ambiguous fields that may need human review" }
        },
        "required": ["vendor_name", "invoice_number", "date", "line_items", "total_amount", "currency", "payment_terms", "po_number", "flags"],
        "additionalProperties": false
      }
    }
  }}
```

### Bob — Data Enricher

Bob uses **tools and structured output together**. During processing he autonomously calls `covia_read` to look up vendor records and purchase orders. After all tool calls complete, his final response is schema-enforced. He also writes his enrichment to workspace for the permanent record.

Bob's `context` loads: (1) the AP Data Guide artifact for workspace layout, and (2) an op-based entry that lists known vendors at context-load time — so Bob knows which vendors exist before making any tool calls.

Bob's caps grant exactly what his role needs — read vendor/PO data and the data guide, write enrichments. Any attempt to write decisions or modify vendor records is denied.

```
agent_create
  agentId: "Bob"
  overwrite: true
  config: { "operation": "v/ops/goaltree/chat" }
  state: { "config": {
    "llmOperation": "v/ops/langchain/openai",
    "model": "gpt-4o-mini",
    "caps": [
      {"with": "w/vendor-records/", "can": "crud/read"},
      {"with": "w/purchase-orders/", "can": "crud/read"},
      {"with": "w/docs/",            "can": "crud/read"},
      {"with": "w/invoices/",        "can": "crud"},
      {"with": "w/enrichments/",     "can": "crud"}
    ],
    "systemPrompt": "You are Bob, an AP Data Enricher. You receive structured invoice data and enrich it by autonomously looking up vendor records, purchase orders, and checking for duplicates. Use your tools to read and write workspace data. Record the exact path you queried in each source_path field. Your confidence_score should reflect how many validations succeeded (1.0 = all clear, lower for each warning).",
    "context": [
      {"ref": "w/docs/data-guide", "label": "AP Data Guide"},
      {"op": "v/ops/covia/list", "input": {"path": "w/vendor-records"}, "label": "Known Vendors"}
    ],
    "responseFormat": {
      "name": "InvoiceEnrichment",
      "schema": {
        "type": "object",
        "properties": {
          "invoice_number": { "type": "string" },
          "vendor_validation": {
            "type": "object",
            "properties": {
              "status": { "type": "string", "description": "ACTIVE, SUSPENDED, or UNKNOWN" },
              "sanctions_check": { "type": "string", "description": "CLEAR, FLAGGED, or UNKNOWN" },
              "sanctions_detail": { "type": "string", "description": "Detail if flagged, empty string otherwise" },
              "source_path": { "type": "string", "description": "Workspace path queried for vendor record" }
            },
            "required": ["status", "sanctions_check", "sanctions_detail", "source_path"],
            "additionalProperties": false
          },
          "po_match": {
            "type": "object",
            "properties": {
              "matched": { "type": "boolean" },
              "po_number": { "type": "string" },
              "amount_authorised": { "type": "number", "description": "PO authorised amount, or 0 if no PO found" },
              "within_budget": { "type": "boolean" },
              "approver": { "type": "string", "description": "PO approver name, or empty string" },
              "source_path": { "type": "string" }
            },
            "required": ["matched", "po_number", "amount_authorised", "within_budget", "approver", "source_path"],
            "additionalProperties": false
          },
          "duplicate_check": {
            "type": "object",
            "properties": {
              "is_duplicate": { "type": "boolean" },
              "detail": { "type": "string" }
            },
            "required": ["is_duplicate", "detail"],
            "additionalProperties": false
          },
          "currency_match": {
            "type": "object",
            "properties": {
              "invoice_currency": { "type": "string" },
              "po_currency": { "type": "string", "description": "PO currency, or empty string if no PO" },
              "match": { "type": "boolean" }
            },
            "required": ["invoice_currency", "po_currency", "match"],
            "additionalProperties": false
          },
          "confidence_score": { "type": "number", "description": "0.0 to 1.0 — reflects how many validations passed" },
          "warnings": { "type": "array", "items": { "type": "string" } },
          "source_references": { "type": "array", "items": { "type": "string" }, "description": "All workspace paths consulted during enrichment" }
        },
        "required": ["invoice_number", "vendor_validation", "po_match", "duplicate_check", "currency_match", "confidence_score", "warnings", "source_references"],
        "additionalProperties": false
      }
    }
  }}
```

### Carol — Payment Approver

Carol applies named policy rules and must cite each one in her response. The schema forces her to enumerate every rule she evaluated.

Carol's `context` loads: (1) the AP Policy Rules artifact, and (2) a workspace ref for all vendor records — rendered via CellExplorer with budget control, giving Carol vendor status at a glance. She receives both Alice's extraction (for the actual invoice total) and Bob's enrichment (for validation results) via the orchestration. She writes her decision to `w/decisions/{invoice_number}` for the permanent audit trail.

Carol can read everything in the workspace (she needs to inspect any reference data) but can only write to `w/decisions/`. Critically, she cannot modify enrichments or vendor records — preserving the audit trail.

```
agent_create
  agentId: "Carol"
  overwrite: true
  config: { "operation": "v/ops/goaltree/chat" }
  state: { "config": {
    "llmOperation": "v/ops/langchain/openai",
    "model": "gpt-4o-mini",
    "caps": [
      {"with": "w/",           "can": "crud/read"},
      {"with": "w/decisions/", "can": "crud"}
    ],
    "systemPrompt": "You are Carol, the AP Payment Approver and policy gate. You receive both the original extraction (with the invoice total_amount) and Bob's enrichment (with validation results). Use extraction.total_amount as the invoice amount for threshold rules — not the PO authorised amount. Apply the AP policy rules to every invoice. Every decision must cite each rule evaluated with PASS or FAIL and specific evidence. Write your decision to w/decisions/{invoice_number} for the audit trail.",
    "context": [
      {"ref": "w/docs/policy-rules", "label": "AP Policy Rules"},
      {"ref": "w/vendor-records", "label": "Vendor Records (reference)"}
    ],
    "responseFormat": {
      "name": "ApprovalDecision",
      "schema": {
        "type": "object",
        "properties": {
          "decision": { "type": "string", "description": "APPROVED, ESCALATED, or REJECTED" },
          "invoice_summary": {
            "type": "object",
            "properties": {
              "vendor": { "type": "string" },
              "invoice_number": { "type": "string" },
              "amount": { "type": "number" },
              "currency": { "type": "string" }
            },
            "required": ["vendor", "invoice_number", "amount", "currency"],
            "additionalProperties": false
          },
          "policy_rules_applied": { "type": "array", "items": {
            "type": "object",
            "properties": {
              "rule": { "type": "string", "description": "Policy rule ID and name, e.g. AP-001 Amount Threshold" },
              "result": { "type": "string", "description": "PASS or FAIL" },
              "detail": { "type": "string", "description": "Specific evidence for the result" }
            },
            "required": ["rule", "result", "detail"],
            "additionalProperties": false
          }},
          "escalation_target": { "type": "string", "description": "Name and role of the person to escalate to, or empty string if not escalating" },
          "evidence_considered": { "type": "array", "items": { "type": "string" }, "description": "Key data points that informed the decision" },
          "risk_flags": { "type": "array", "items": { "type": "string" }, "description": "Any risk concerns, even if rules passed" },
          "reasoning": { "type": "string", "description": "Full narrative reasoning for the decision" }
        },
        "required": ["decision", "invoice_summary", "policy_rules_applied", "escalation_target", "evidence_considered", "risk_flags", "reasoning"],
        "additionalProperties": false
      }
    }
  }}
```

### Dave — AP Manager

Dave is the general-purpose manager. No structured output — he's conversational. His context loads the policy rules, data guide, and pipeline orchestration hash so he can answer questions and run the pipeline without hardcoded knowledge.

Dave is read-only on the workspace, can run orchestrations, and can query/message the pipeline agents. He cannot write anywhere — proving managerial oversight without write privilege. Act 4 of the demo script depends on Dave being denied a write to `w/vendor-records/`.

```
agent_create
  agentId: "Dave"
  overwrite: true
  config: { "operation": "v/ops/goaltree/chat" }
  state: { "config": {
    "llmOperation": "v/ops/langchain/openai",
    "model": "gpt-4o-mini",
    "caps": [
      {"with": "w/",  "can": "crud/read"},
      {"with": "g/",  "can": "agent/message"},
      {"with": "g/",  "can": "agent/request"},
      {"with": "",    "can": "invoke"}
    ],
    "systemPrompt": "You are Dave, the AP Manager. You oversee Alice (scanner), Bob (enricher), and Carol (approver). To process an invoice through the pipeline, use grid_run with operation=o/ap-pipeline (your context shows the full orchestration structure). You can also investigate workspace data, query agent state, and answer questions. Summarise pipeline results highlighting the decision, policy rules, and risk flags.",
    "context": [
      {"ref": "w/docs/policy-rules", "label": "AP Policy Rules"},
      {"ref": "w/docs/data-guide", "label": "AP Data Guide"},
      {"ref": "o/ap-pipeline", "label": "AP Invoice Pipeline (callable as grid_run operation=o/ap-pipeline)"}
    ]
  }}
```

## 5. Verify

```
agent_list  → should show Alice, Bob, Carol, Dave all SLEEPING
```

Confirm context and caps are configured:

```
agent_info  agentId=Bob   → state.config.context lists the data guide hash; state.config.caps has 5 entries
agent_info  agentId=Carol → state.config.context lists the policy rules hash; state.config.caps has 2 entries
agent_info  agentId=Dave  → state.config.caps has 4 entries (workspace read + agent/g message + agent/g request + invoke)
agent_info  agentId=Alice → state.config.caps is [] (no tools needed)
```
