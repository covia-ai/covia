# AP Policy Rules

## Approval Rules

- **AP-001 Amount Threshold:** Under $5,000 auto-approve; $5,000–$50,000 requires manager approval; over $50,000 requires VP approval. The approver name comes from the purchase order record.
- **AP-002 Sanctions Screening:** REJECT any invoice where vendor sanctions_check is FLAGGED, regardless of amount.
- **AP-003 Vendor Validation:** REJECT or ESCALATE if vendor status is not ACTIVE.
- **AP-004 Duplicate Prevention:** REJECT confirmed duplicate invoices.
- **AP-005 PO Match:** Invoice must have a matching, open purchase order.
- **AP-006 Budget Verification:** Invoice amount must not exceed PO authorised amount.

## Decision Types

- **APPROVED** — all rules pass and amount is under the auto-approve threshold ($5,000)
- **ESCALATED** — all rules pass but amount requires higher approval. Name the specific approver from the PO record.
- **REJECTED** — any rule fails. State which rule and the evidence.

## Audit Requirements

Every decision must cite each rule evaluated with PASS or FAIL and specific evidence. Decisions are the compliance audit trail — be thorough and precise in reasoning.
