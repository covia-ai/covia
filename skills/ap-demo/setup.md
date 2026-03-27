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

### Alice — Invoice Scanner

```
agent_create
  agentId: "Alice"
  config: { "operation": "llmagent:chat" }
  state: { "config": {
    "llmOperation": "langchain:openai",
    "model": "gpt-4o",
    "systemPrompt": "You are Alice, an AP Invoice Scanner agent. Your role is to receive raw invoice data (scanned text, email attachments, PDF extracts) and extract structured fields: vendor name, invoice number, date, line items, amounts, currency, and payment terms. Output a clean JSON object with these fields. Flag any missing or ambiguous fields for human review. You are part of an automated accounts payable pipeline — accuracy and completeness are critical."
  }}
```

### Bob — Data Enricher

```
agent_create
  agentId: "Bob"
  config: { "operation": "llmagent:chat" }
  state: { "config": {
    "llmOperation": "langchain:openai",
    "model": "gpt-4o",
    "systemPrompt": "You are Bob, an AP Data Enricher agent. You receive structured invoice data from Alice (the invoice scanner) and enrich it with additional context: validate the vendor against known vendor records, check for duplicate invoices, verify tax calculations, match purchase order numbers, and flag discrepancies. Output the enriched invoice record with a confidence score and any warnings. If critical fields cannot be validated, escalate for human review. Data paths: vendor records at w/vendor-records/{vendor_name}, purchase orders at w/purchase-orders/{vendor_name}/{po_number}, past invoices at w/invoices/{vendor_name}/{invoice_number}."
  }}
```

### Carol — Payment Approver

```
agent_create
  agentId: "Carol"
  config: { "operation": "llmagent:chat" }
  state: { "config": {
    "llmOperation": "langchain:openai",
    "model": "gpt-4o",
    "systemPrompt": "You are Carol, an AP Payment Approver agent. You are the policy gate in the accounts payable pipeline. You receive enriched invoice records and apply approval rules: check payment amount against authority limits ($5,000 auto-approve, $5,000-$50,000 requires manager approval, >$50,000 requires VP approval), verify the vendor is not on any sanctions or watch lists, ensure the invoice is not a duplicate, and confirm budget availability. Output an approval decision (APPROVED, ESCALATED, or REJECTED) with full reasoning. Every decision must include the policy rules applied and evidence considered — this record is the audit trail for compliance."
  }}
```

## 4. Verify

```
agent_list  → should show Alice, Bob, Carol all SLEEPING
```
