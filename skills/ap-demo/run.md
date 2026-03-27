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

### Step 5: Trace Provenance

Walk the full chain backward from Carol's decision to Alice's raw input. Query all three timelines:

```
agent_query  agentId=Carol
agent_query  agentId=Bob
agent_query  agentId=Alice
```

For each agent, extract the most recent timeline entry and show:

1. **Carol's decision** — the approval/escalation/rejection with reasoning, and her `source_pipeline` referencing Bob's and Alice's job IDs
2. **Bob's enrichment** — his tool calls (`covia_read` for vendor lookup, PO match, duplicate check), the `source_agent: Alice` and `source_job` linking back to Alice
3. **Alice's extraction** — the raw invoice text in and the structured JSON out

Present this as a provenance chain:

```
Carol (ESCALATED — $15,600 requires manager approval)
  ← Bob (enriched — vendor ACTIVE, sanctions CLEAR, PO matched, confidence 0.95)
    ← Alice (extracted — Acme Corp, INV-2024-0892, $15,600)
      ← Raw invoice text
```

**Narrate:** "We can walk backward from any decision to the original source material. Every link in the chain is recorded with timestamps and job IDs. This is the full compliance trail — who did what, when, with what evidence, and why."

---

## Scenarios

The default invoice above demonstrates the **manager escalation** path. Use these additional scenarios to show different outcomes. Each follows the same Alice → Bob → Carol pipeline — just substitute the invoice text in Step 1.

### Rejection — Sanctioned Vendor

> Invoice from Initech Systems, INV-2024-1105, dated 2024-11-20. Line items: Annual software maintenance $6,500. Total: $6,500 USD. Payment terms: Net 45. PO# PO-2024-0312.

**What happens:**
- **Alice** extracts fields normally
- **Bob** looks up the vendor record, finds `status: SUSPENDED` and `sanctions_check: FLAGGED` with OFAC SDN detail. His output should include strong warnings and a low confidence score.
- **Carol** sees the sanctions flag and **REJECTS** — policy prohibits payment to sanctioned vendors regardless of amount.

**Narrate:** "This is where the pipeline earns its keep. Bob found the sanctions flag in the vendor record — not because someone told him to check, but because his system prompt includes data validation as a core responsibility. Carol then applied the policy: sanctioned vendor, automatic rejection. The audit trail shows exactly why this payment was blocked."

### Auto-Approve — Small Amount

> Invoice from Globex Ltd, INV-2024-1201, dated 2024-12-01. Line items: Consulting — API review 4 hours at $200/hr $800. Total: $800 USD. Payment terms: Net 15. PO# PO-2024-0790.

**What happens:**
- **Alice** extracts fields
- **Bob** validates — Globex Ltd is ACTIVE, sanctions CLEAR, PO has $150,000 authorised (well within range)
- **Carol** sees $800 is under the $5,000 auto-approve threshold → **APPROVED**

**Narrate:** "Small invoice, clean vendor, valid PO — Carol auto-approved. No human in the loop needed. The full audit trail is still recorded for compliance."

### VP Escalation — High Value

> Invoice from Globex Ltd, INV-2024-1215, dated 2024-12-15. Line items: Platform migration phase 1 $45,000, Data migration services $22,000, Project management $8,500. Total: $75,500 USD. Payment terms: Net 60. PO# PO-2024-0790.

**What happens:**
- **Alice** extracts fields (multiple line items)
- **Bob** validates — Globex is clean, PO authorised for $150,000 (covers it)
- **Carol** sees $75,500 is above $50,000 → **ESCALATED** requiring VP approval from D. Chen

**Narrate:** "Same vendor, same PO, but the amount triggers a different policy tier. Carol escalated to VP level and named the specific approver from the purchase order. The policy rules are documented in her decision."

### Unknown Vendor

> Invoice from Nexus Dynamics, INV-2024-1300, dated 2024-12-20. Line items: Security audit $12,000. Total: $12,000 USD. Payment terms: Net 30.

**What happens:**
- **Alice** extracts fields (no PO number in this invoice)
- **Bob** queries `w/vendor-records/Nexus Dynamics` — gets `exists: false`. No vendor record, no PO to match. His output flags multiple warnings: unknown vendor, no PO, cannot validate.
- **Carol** sees Bob's warnings and **REJECTS** or **ESCALATES for human review** — cannot approve without validated vendor and PO match.

**Narrate:** "Bob couldn't find this vendor in the system. No record, no PO, no way to validate. The pipeline doesn't just wave it through — it flags exactly what's missing and stops. This is the safety net."

---

## Batch Processing

To demonstrate the async job model and parallel execution, submit multiple invoices concurrently:

```
# Submit all four invoices to Alice in parallel (no wait)
agent_request  agentId=Alice  input={"invoice_text": "Invoice from Acme Corp, INV-2024-0892..."}
agent_request  agentId=Alice  input={"invoice_text": "Invoice from Initech Systems, INV-2024-1105..."}
agent_request  agentId=Alice  input={"invoice_text": "Invoice from Globex Ltd, INV-2024-1201..."}
agent_request  agentId=Alice  input={"invoice_text": "Invoice from Nexus Dynamics, INV-2024-1300..."}
```

Each returns a job ID immediately. Alice processes them sequentially (one agent, one LLM conversation at a time), but the jobs queue up and complete in order.

After all four complete, query Alice's timeline to show the batch:

```
agent_query  agentId=Alice
```

Then forward each result through Bob and Carol. Present a summary table:

| Invoice | Vendor | Amount | Bob's Assessment | Carol's Decision |
|---------|--------|--------|-----------------|-----------------|
| INV-2024-0892 | Acme Corp | $15,600 | Clean — vendor active, PO matched | ESCALATED (manager) |
| INV-2024-1105 | Initech Systems | $6,500 | FLAGGED — vendor suspended, sanctions | REJECTED |
| INV-2024-1201 | Globex Ltd | $800 | Clean — vendor active, PO matched | APPROVED |
| INV-2024-1300 | Nexus Dynamics | $12,000 | WARNINGS — unknown vendor, no PO | REJECTED / ESCALATED |

**Narrate:** "Four invoices, four different outcomes — auto-approve, manager escalation, rejection for sanctions, rejection for unknown vendor. Each one has its own complete audit trail. The pipeline handled them all consistently, applying the same rules to every invoice."

## Troubleshooting

| Problem | Likely Cause | Fix |
|---------|-------------|-----|
| Alice returns unstructured text or garbage | LLM call failed silently or returned an error wrapped in text | Check `agent_query agentId=Alice` — look at the last timeline entry for errors. Verify `OPENAI_API_KEY` is set (`/secret set OPENAI_API_KEY`). |
| Bob says he can't find vendor records | Reference data not seeded, or Bob's system prompt missing data paths | Re-run `/ap-demo setup` (step 2). Check `covia_read path=w/vendor-records/Acme Corp` returns data. |
| Bob returns low confidence or flags warnings | Invoice fields don't match seeded data (e.g. vendor name mismatch, PO amount exceeded) | Check Alice's output — vendor name must be exactly "Acme Corp" and PO must be "PO-2024-0456". If using a custom invoice, seed matching reference data first. |
| Carol rejects instead of escalating | Carol's LLM interpreted the policy differently | Reset Carol (`/agent reset Carol`) and re-submit. LLM responses vary — the exact wording may differ between runs. |
| Any agent stuck in RUNNING | LLM call hanging (network, API rate limit) | Wait 30-60 seconds. If still stuck, check the venue logs for timeout errors. Reset the agent and retry. |
| `agent_request` returns immediately with no result | Forgot `wait: true` | Re-submit with `wait: true`. Without it, you get a job ID and need to poll manually. |

## Presentation Tips

- Be conversational, not robotic — narrate like a live walkthrough
- After each agent, give a brief plain-English summary before showing data
- Don't dump raw JSON walls — highlight the interesting parts
- When showing the audit trail, pick 2-3 specific details (e.g. "notice Bob's covia_read call returned sanctions_check: CLEAR")
- Use agent names (Alice, Bob, Carol) — it makes the flow relatable
- If asked about identity: the local demo uses a shared public identity; in production each user has a cryptographic DID with isolated namespaces
- If asked about scalability: the Convex lattice supports conflict-free replication across distributed peers
