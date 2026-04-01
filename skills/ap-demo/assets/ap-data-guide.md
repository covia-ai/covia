# AP Data Layout

## Workspace Paths

### Vendor Records
Path: `w/vendor-records/{vendor_name}`

Fields: vendor_id, name, status (ACTIVE/SUSPENDED), tax_id, payment_method, bank_account, sanctions_check (CLEAR/FLAGGED), sanctions_detail, last_reviewed

### Purchase Orders
Path: `w/purchase-orders/{vendor_name}/{po_number}`

Fields: po_number, vendor, amount_authorised, currency, department, budget_code, status (OPEN/CLOSED), approver

### Duplicate Check
Path: `w/invoices/{vendor_name}/{invoice_number}`

If exists, the invoice is a duplicate.

### Enrichment Results
Path: `w/enrichments/{invoice_number}`

Write enrichment analysis here after completing validation.

### Decision Records
Path: `w/decisions/{invoice_number}`

Write approval decisions here for the audit trail.
