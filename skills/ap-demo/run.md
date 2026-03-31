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

**Expected:** `InvoiceExtraction` JSON with all fields populated. Key fields to highlight: `vendor_name`, `total_amount`, `po_number`, `flags` (should be empty for clean invoices).

**Narrate:** Show the key fields in a compact summary:

```
Alice extracted: {vendor_name}, {invoice_number}, {total_amount} {currency}
  Line items: {line_items.length} ({descriptions...})
  PO: {po_number} | Terms: {payment_terms} | Flags: {flags.length}
```

"Alice's output is schema-enforced — every field is guaranteed present with consistent names. That's what makes the hand-off to Bob reliable."

### Step 2: Bob — Enrich

Forward Alice's structured output to Bob with provenance:

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

**Expected:** `InvoiceEnrichment` JSON with vendor validation, PO match, duplicate check, currency match, confidence score, and source references.

**This is the highlight of the demo.** Bob autonomously called tools to look up real data — vendor records, purchase orders, duplicate checks. His `source_references` array lists every workspace path he consulted.

**Narrate:** Show a compact enrichment summary:

```
Bob's enrichment of {invoice_number}:
  Vendor: {vendor_validation.status}, sanctions {vendor_validation.sanctions_check}
    ↳ queried {vendor_validation.source_path}
  PO Match: {po_match.po_number} [${po_match.amount_authorised} authorised] — {within_budget ? "WITHIN BUDGET" : "OVER BUDGET"}
    ↳ queried {po_match.source_path}
  Currency: {currency_match.invoice_currency}/{currency_match.po_currency} — {match ? "MATCH" : "MISMATCH"}
  Duplicate: {duplicate_check.is_duplicate ? "YES" : "None found"}
  Confidence: {confidence_score}
  Tool calls: {source_references.length} workspace lookups
```

"Bob didn't just reformat — he autonomously queried {source_references.length} workspace records. Every lookup path is recorded in his output for the audit trail."

**Step 2.5 — Verify workspace persistence:**

```
covia_read  path=w/enrichments/{invoice_number}
```

"Bob also wrote his enrichment to workspace. This record persists on the Convex lattice — any future agent or auditor can read it."

### Step 3: Carol — Approve

Forward enriched data with full pipeline provenance:

```
agent_request
  agentId: "Carol"
  input: {
    "enriched_invoice": { <Bob's enriched output> },
    "source_pipeline": [
      { "agent": "Alice", "job": "<Alice's job ID>" },
      { "agent": "Bob", "job": "<Bob's job ID>" }
    ]
  }
  wait: true
```

**Expected for $15,600:** `ApprovalDecision` with `decision: "ESCALATED"`, 6 policy rules evaluated, `escalation_target` naming J. Martinez.

**Narrate:** Show Carol's decision matrix:

```
Carol's decision: {decision}

Policy Rules:
  {for each rule in policy_rules_applied}
  {rule.result == "PASS" ? "✓" : "✗"} {rule.rule} — {rule.detail}

Escalation: {escalation_target}
Risk flags: {risk_flags.length > 0 ? risk_flags : "None"}
```

"Carol evaluated {policy_rules_applied.length} named policy rules. AP-001 triggered the escalation — $15,600 is above the $5,000 auto-approve threshold. She named the specific manager from the purchase order. Every rule and its evidence is in the compliance record."

### Step 4: Audit Trail

Query Bob to show the richest audit data:

```
agent_query  agentId=Bob
```

From Bob's latest timeline entry, present a structured audit card:

```
AUDIT RECORD — Bob (Data Enricher)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Job:      {job_id}
Duration: {end - start}ms
Status:   COMPLETE

Tool calls (from timeline):
  1. covia_read  w/vendor-records/Acme Corp      → ACTIVE, sanctions CLEAR
  2. covia_read  w/purchase-orders/Acme Corp/PO-2024-0456  → $20,000 authorised, OPEN
  3. covia_read  w/invoices/Acme Corp/INV-2024-0892  → not found (no duplicate)
  4. covia_write w/enrichments/INV-2024-0892     → persisted enrichment

Input:  structured invoice from Alice (job {alice_job_id})
Output: enrichment, confidence {confidence_score}
```

**Narrate:** "Every tool call Bob made is recorded in his timeline on the Convex lattice — the paths he queried, the data he got back, when it happened. This is the immutable audit trail. A compliance officer can reconstruct exactly what happened at any point. And because the lattice is content-addressed with SHA-256, these records are tamper-proof by construction."

### Step 5: Trace Provenance

Query all three agent timelines and build the full chain:

```
agent_query  agentId=Carol
agent_query  agentId=Bob
agent_query  agentId=Alice
```

Present a structured provenance chain using the structured output fields:

```
PROVENANCE CHAIN — {invoice_number}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

[3] Carol (Payment Approver)               Job: {carol_job}  {timestamp}
    Decision: {decision}
    Rules: {for each rule: rule.result rule.rule}
    Escalation: {escalation_target}

    ↑ input from:
[2] Bob (Data Enricher)                    Job: {bob_job}    {timestamp}
    Vendor: {vendor_validation.status}, sanctions {vendor_validation.sanctions_check}
    PO: {po_match.matched ? "matched" : "no match"} {po_match.po_number}, {within_budget ? "within" : "over"} budget
    Confidence: {confidence_score}
    Tool calls: {source_references.length}
    Workspace: w/enrichments/{invoice_number}

    ↑ input from:
[1] Alice (Invoice Scanner)                Job: {alice_job}  {timestamp}
    Extracted: {vendor_name}, {invoice_number}, {total_amount} {currency}
    Fields: {count populated}, {flags.length} flags

    ↑ input:
[0] Raw invoice text
```

**Narrate:** "We can walk backward from any decision to the original source material. Every link in the chain is recorded with timestamps and job IDs. Carol's structured decision references Bob's job, Bob's references Alice's, Alice's references the raw input. This is the full compliance trail — who did what, when, with what evidence, and why."

---

## Scenarios

The default invoice above demonstrates the **manager escalation** path. Use these additional scenarios to show different outcomes. Each follows the same Alice → Bob → Carol pipeline — just substitute the invoice text in Step 1.

### Rejection — Sanctioned Vendor

> Invoice from Initech Systems, INV-2024-1105, dated 2024-11-20. Line items: Annual software maintenance $6,500. Total: $6,500 USD. Payment terms: Net 45. PO# PO-2024-0312.

**What happens:**
- **Alice** extracts fields normally
- **Bob** looks up the vendor record, finds `status: SUSPENDED` and `sanctions_check: FLAGGED` with OFAC SDN detail. Low confidence score, multiple warnings.
- **Carol** sees the sanctions flag and **REJECTS** — AP-002 (Sanctions Screening) fails. AP-003 (Vendor Validation) also fails (SUSPENDED).

**Differentiator:** "Bob discovered the sanctions flag through autonomous data lookup — not because a rule engine fired, but because an intelligent agent queried the right records. The audit trail shows exactly which workspace path returned the flag."

### Auto-Approve — Small Amount

> Invoice from Globex Ltd, INV-2024-1201, dated 2024-12-01. Line items: Consulting — API review 4 hours at $200/hr $800. Total: $800 USD. Payment terms: Net 15. PO# PO-2024-0790.

**What happens:**
- **Alice** extracts fields
- **Bob** validates — Globex Ltd is ACTIVE, sanctions CLEAR, PO has $150,000 authorised (well within range)
- **Carol** sees $800 is under the $5,000 auto-approve threshold → **APPROVED**. All 6 rules pass.

**Differentiator:** "Sub-threshold auto-approval still generates the full audit trail with all 6 policy rules evaluated. A compliance officer can verify the decision was correct months later — same record depth whether the amount is $800 or $80,000."

### VP Escalation — High Value

> Invoice from Globex Ltd, INV-2024-1215, dated 2024-12-15. Line items: Platform migration phase 1 $45,000, Data migration services $22,000, Project management $8,500. Total: $75,500 USD. Payment terms: Net 60. PO# PO-2024-0790.

**What happens:**
- **Alice** extracts fields (multiple line items)
- **Bob** validates — Globex is clean, PO authorised for $150,000 (covers it)
- **Carol** sees $75,500 is above $50,000 → **ESCALATED** requiring VP approval from D. Chen

**Differentiator:** "Same vendor, same PO, but the amount triggers a different policy tier. Carol escalated to VP level and named the specific approver from the purchase order — pulling together data from multiple sources autonomously."

### Unknown Vendor

> Invoice from Nexus Dynamics, INV-2024-1300, dated 2024-12-20. Line items: Security audit $12,000. Total: $12,000 USD. Payment terms: Net 30.

**What happens:**
- **Alice** extracts fields (no PO number in this invoice — `po_number` will be empty string)
- **Bob** queries `w/vendor-records/Nexus Dynamics` — gets `exists: false`. No vendor record, no PO to match. Multiple warnings, low confidence.
- **Carol** sees Bob's warnings and **REJECTS** — AP-003 (unknown vendor), AP-005 (no PO match). Multiple rules fail.

**Differentiator:** "The pipeline failed safe. Three missing validations each independently warranted rejection. The structured output makes the failure reasons machine-readable — an automated system could route this to vendor onboarding."

---

## Batch Processing

Submit multiple invoices concurrently to demonstrate the async job model:

```
# Submit all four invoices to Alice in parallel (no wait)
agent_request  agentId=Alice  input={"invoice_text": "Invoice from Acme Corp, INV-2024-0892, dated 2024-11-15. Line items: Cloud hosting Q4 $12,400, API gateway licence $3,200. Total: $15,600 USD. Payment terms: Net 30. PO# PO-2024-0456."}
agent_request  agentId=Alice  input={"invoice_text": "Invoice from Initech Systems, INV-2024-1105, dated 2024-11-20. Line items: Annual software maintenance $6,500. Total: $6,500 USD. Payment terms: Net 45. PO# PO-2024-0312."}
agent_request  agentId=Alice  input={"invoice_text": "Invoice from Globex Ltd, INV-2024-1201, dated 2024-12-01. Line items: Consulting — API review 4 hours at $200/hr $800. Total: $800 USD. Payment terms: Net 15. PO# PO-2024-0790."}
agent_request  agentId=Alice  input={"invoice_text": "Invoice from Nexus Dynamics, INV-2024-1300, dated 2024-12-20. Line items: Security audit $12,000. Total: $12,000 USD. Payment terms: Net 30."}
```

Each returns a job ID immediately. After all four complete, forward each through Bob and Carol. Present a comparison table:

```
AP PIPELINE RESULTS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Invoice        Vendor            Amount    Vendor Status  Sanctions  PO Match  Decision    Trigger Rule
─────────────────────────────────────────────────────────────────────────────────────────────────────────
INV-2024-0892  Acme Corp         $15,600   ACTIVE         CLEAR      YES       ESCALATED   AP-001 (>$5k)
INV-2024-1105  Initech Systems    $6,500   SUSPENDED      FLAGGED    YES       REJECTED    AP-002 (sanctions)
INV-2024-1201  Globex Ltd           $800   ACTIVE         CLEAR      YES       APPROVED    All rules pass
INV-2024-1300  Nexus Dynamics    $12,000   UNKNOWN        UNKNOWN    NO        REJECTED    AP-003/AP-005
```

**Narrate:** "Four invoices, four different outcomes — auto-approve, manager escalation, sanctions rejection, unknown vendor rejection. Each one has its own complete audit trail with all 6 policy rules evaluated. The pipeline handled them all consistently, applying the same named rules to every invoice. The structured output makes the results directly comparable."

## Troubleshooting

| Problem | Likely Cause | Fix |
|---------|-------------|-----|
| Alice returns error instead of structured JSON | LLM call failed (API key, network) | Check `agent_query agentId=Alice` timeline for errors. Verify `OPENAI_API_KEY` is set. Structured output means format issues are impossible — errors are always platform-level. |
| Bob says he can't find vendor records | Reference data not seeded, or vendor name mismatch | Re-run `/ap-demo setup` (step 2). Check `covia_read path=w/vendor-records/Acme Corp` returns data. Alice's `vendor_name` must exactly match the seeded key. |
| Bob's confidence score is unexpectedly low | Invoice fields don't match seeded data | Check Alice's output — `vendor_name` must be exactly "Acme Corp", `po_number` must be "PO-2024-0456". |
| Carol rejects instead of escalating | Policy interpretation varies between runs | Reset Carol (`/agent reset Carol`) and re-submit. Carol's structured output should cite the specific rule — check which one she flags. |
| Any agent stuck in RUNNING | LLM call hanging (network, rate limit) | Wait 30–60 seconds. If still stuck, check venue logs. Reset the agent and retry. |
| `agent_request` returns immediately with no result | Forgot `wait: true` | Re-submit with `wait: true`. Without it, you get a job ID and need to poll. |
| Enrichment not in workspace | Bob didn't execute `covia_write` | Check Bob's timeline for tool calls. Reset Bob and re-run — the system prompt instructs the write. |

## Presentation Tips

- **Lead with the structured output** — this is what makes the demo reliable. Show how every field is guaranteed present.
- **Bob is the star** — his autonomous tool use is the most impressive part. Show his `source_references` and the workspace paths he queried. Verify his enrichment in workspace.
- **Carol's policy matrix is the closer** — 6 named rules, each with PASS/FAIL and evidence. This is the compliance record that enterprises need.
- **Use the provenance chain** — walk backward from Carol's decision to the raw invoice. Every link has a job ID and timestamp.
- **Don't dump raw JSON** — use the compact summary formats above to highlight the interesting fields.
- **Be conversational** — "Notice Bob queried 4 workspace paths and found no duplicates" is better than showing the full JSON.
