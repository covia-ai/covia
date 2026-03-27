# AP Demo — Running the Pipeline

## Default Invoice

> Invoice from Acme Corp, INV-2024-0892, dated 2024-11-15. Line items: Cloud hosting Q4 $12,400, API gateway licence $3,200. Total: $15,600 USD. Payment terms: Net 30. PO# PO-2024-0456.

## Pipeline Steps

### Step 1: Alice — Extract

```
agent_request
  agentId: "Alice"
  input: { "invoice_text": "<invoice text>" }
  wait: true
```

**Expected:** Structured JSON with vendor_name, invoice_number, date, line_items, total_amount, currency, payment_terms, po_number.

**Narrate:** "Alice extracted the key fields — vendor, line items, amounts, payment terms, and PO number."

### Step 2: Bob — Enrich

Forward Alice's output with provenance:

```
agent_request
  agentId: "Bob"
  input: {
    "invoice": { <Alice's structured output> },
    "source_agent": "Alice",
    "source_job": "<Alice's job ID>"
  }
  wait: true
```

**Expected:** Enriched record with vendor validation (ACTIVE, sanctions CLEAR), duplicate check (none found), PO match (within authorised amount), confidence score.

**Narrate:** "Bob didn't just reformat — he autonomously looked up the vendor record, checked for duplicate invoices, and matched the purchase order. All validated against real data in the workspace."

### Step 3: Carol — Approve

Forward enriched data with full pipeline provenance:

```
agent_request
  agentId: "Carol"
  input: {
    "enriched_invoice": { <enriched data including vendor/PO validation> },
    "source_pipeline": [
      { "agent": "Alice", "job": "<Alice's job ID>" },
      { "agent": "Bob", "job": "<Bob's job ID>" }
    ]
  }
  wait: true
```

**Expected for $15,600:** ESCALATED — amount is in $5k-$50k range, requires manager approval from J. Martinez.

**Narrate:** "Carol applied the approval policy: the amount is above the $5,000 auto-approve threshold, so it needs manager sign-off. She names the specific manager and documents every rule she applied. This is the compliance record."

### Step 4: Show the Audit Trail

Query Bob to show the richest audit data:

```
agent_query  agentId=Bob
```

Highlight from Bob's timeline:
- His tool calls to `covia_read` (vendor lookup, PO match, duplicate check)
- The complete input/output at each step
- Timestamps showing when each action occurred
- The provenance chain linking back to Alice's job ID

**Narrate:** "Every step is recorded immutably on the Convex lattice — the inputs, the reasoning, the tool calls, the outputs. A compliance officer can reconstruct exactly what happened at any point. And because the lattice is content-addressed with SHA256, these records are tamper-proof by construction."

## Variations

If the user wants to try different scenarios:

| Scenario | Invoice Amount | Expected Decision |
|----------|---------------|-------------------|
| Auto-approve | < $5,000 | APPROVED |
| Manager approval | $5,000 - $50,000 | ESCALATED (manager) |
| VP approval | > $50,000 | ESCALATED (VP) |
| Unknown vendor | Any | Bob fails enrichment — no vendor record |

## Presentation Tips

- Be conversational, not robotic — narrate like a live walkthrough
- After each agent, give a brief plain-English summary before showing data
- Don't dump raw JSON walls — highlight the interesting parts
- When showing the audit trail, pick 2-3 specific details (e.g. "notice Bob's covia_read call returned sanctions_check: CLEAR")
- Use agent names (Alice, Bob, Carol) — it makes the flow relatable
- If asked about identity: the local demo uses a shared public identity; in production each user has a cryptographic DID with isolated namespaces
- If asked about scalability: the Convex lattice supports conflict-free replication across distributed peers
