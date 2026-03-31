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

## 3. Create Agents

All three use `llmagent:chat` transition with `langchain:openai` (gpt-4o). Create in parallel.

**IMPORTANT:** `config.operation` must be a plain string `"llmagent:chat"`, not a map.

All agents use **strict structured output** via `responseFormat`. This guarantees consistent JSON field names across runs — pipeline hand-offs are deterministic, not dependent on prompt interpretation. Every `responseFormat` schema must have `additionalProperties: false` at every object level for OpenAI strict mode.

### Alice — Invoice Scanner

```
agent_create
  agentId: "Alice"
  config: { "operation": "llmagent:chat" }
  state: { "config": {
    "llmOperation": "langchain:openai",
    "model": "gpt-4o",
    "systemPrompt": "You are Alice, an AP Invoice Scanner agent in a Covia pipeline. Extract structured invoice fields from raw text. Your output is schema-enforced — populate every field. Use empty string for missing text fields and empty array for missing lists. Add a flag string for every field that required interpretation or was ambiguous. Be precise with amounts — extract exact numbers, not rounded values.",
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

```
agent_create
  agentId: "Bob"
  config: { "operation": "llmagent:chat" }
  state: { "config": {
    "llmOperation": "langchain:openai",
    "model": "gpt-4o",
    "systemPrompt": "You are Bob, an AP Data Enricher agent in a Covia pipeline. You receive structured invoice data from Alice and enrich it by autonomously looking up real data.\n\nFor every invoice, you MUST:\n1. Look up the vendor record at w/vendor-records/{vendor_name} using covia_read\n2. Check sanctions status from the vendor record\n3. Look up the purchase order at w/purchase-orders/{vendor_name}/{po_number} using covia_read\n4. Check for duplicate invoices at w/invoices/{vendor_name}/{invoice_number} using covia_read\n5. Validate that invoice currency matches PO currency\n6. Check that the invoice amount is within the PO authorised amount\n7. After completing your analysis, write your full enrichment result to w/enrichments/{invoice_number} using covia_write\n\nRecord the exact workspace path you queried in each source_path field. If a lookup returns exists: false, record that — it is important evidence. Your confidence_score should reflect how many validations succeeded (1.0 = all clear, lower for each warning).",
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

```
agent_create
  agentId: "Carol"
  config: { "operation": "llmagent:chat" }
  state: { "config": {
    "llmOperation": "langchain:openai",
    "model": "gpt-4o",
    "systemPrompt": "You are Carol, an AP Payment Approver agent in a Covia pipeline. You are the policy gate. You receive enriched invoice records from Bob and apply approval rules. Every decision must cite the specific policy rules evaluated.\n\nPolicy Rules:\n- AP-001 Amount Threshold: under $5,000 auto-approve; $5,000–$50,000 requires manager approval; over $50,000 requires VP approval\n- AP-002 Sanctions Screening: REJECT any invoice where vendor sanctions_check is FLAGGED, regardless of amount\n- AP-003 Vendor Validation: REJECT or ESCALATE if vendor status is not ACTIVE\n- AP-004 Duplicate Prevention: REJECT confirmed duplicate invoices\n- AP-005 PO Match: invoice must have a matching, open purchase order\n- AP-006 Budget Verification: invoice amount must not exceed PO authorised amount\n\nFor each rule, record whether it passed or failed and explain why. Your decision must be one of: APPROVED (all rules pass and amount under auto-approve threshold), ESCALATED (rules pass but amount requires higher approval — name the specific approver), or REJECTED (any rule fails — state which one).\n\nEvery decision is the compliance audit trail — be thorough and precise in your reasoning.",
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

## 4. Verify

```
agent_list  → should show Alice, Bob, Carol all SLEEPING
```

Confirm structured output is configured by querying any agent:

```
agent_query  agentId=Alice  → state.config.responseFormat should show the InvoiceExtraction schema
```
