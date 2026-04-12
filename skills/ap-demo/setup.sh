#!/bin/bash
# AP Demo Setup — seeds reference data, documents, pipeline, and agents.
# Usage: bash skills/ap-demo/setup.sh [VENUE_URL]
# Default venue: http://localhost:8080

set -e
VENUE="${1:-http://localhost:8080}"
API="$VENUE/api/v1/invoke"
# Use Windows-compatible path on MSYS/Git Bash
DIR="$(cd "$(dirname "$0")" && pwd -W 2>/dev/null || pwd)"

invoke() {
  curl -sf -X POST "$API" -H "Content-Type: application/json" -d "$1" > /dev/null
}

invoke_file() {
  local op="$1" file="$2"
  curl -sf -X POST "$API" -H "Content-Type: application/json" \
    -d "$(printf '{"operation":"%s","input":%s}' "$op" "$(cat "$file")")" > /dev/null
}

echo "=== AP Demo Setup ==="
echo "Venue: $VENUE"

# 1. Check venue is up
curl -sf "$VENUE/api/v1/status" > /dev/null || { echo "ERROR: Venue not reachable at $VENUE"; exit 1; }

# 2. Seed vendor records
echo "Seeding vendor records..."
invoke '{"operation":"v/ops/covia/write","input":{"path":"w/vendor-records/Acme Corp","value":{"vendor_id":"V-1042","name":"Acme Corp","status":"ACTIVE","tax_id":"US-84-2917345","payment_method":"ACH","bank_account":"****7892","sanctions_check":"CLEAR","last_reviewed":"2024-09-15"}}}'
invoke '{"operation":"v/ops/covia/write","input":{"path":"w/vendor-records/Globex Ltd","value":{"vendor_id":"V-2087","name":"Globex Ltd","status":"ACTIVE","tax_id":"GB-12-8834521","payment_method":"WIRE","bank_account":"****3310","sanctions_check":"CLEAR","last_reviewed":"2024-11-01"}}}'
invoke '{"operation":"v/ops/covia/write","input":{"path":"w/vendor-records/Initech Systems","value":{"vendor_id":"V-3201","name":"Initech Systems","status":"SUSPENDED","tax_id":"US-91-5567890","payment_method":"ACH","bank_account":"****4455","sanctions_check":"FLAGGED","sanctions_detail":"OFAC SDN List \u2014 added 2024-08-20","last_reviewed":"2024-08-22"}}}'

# 3. Seed purchase orders
echo "Seeding purchase orders..."
invoke '{"operation":"v/ops/covia/write","input":{"path":"w/purchase-orders/Acme Corp/PO-2024-0456","value":{"po_number":"PO-2024-0456","vendor":"Acme Corp","amount_authorised":20000,"currency":"USD","department":"Engineering","budget_code":"ENG-INFRA-2024","status":"OPEN","approver":"J. Martinez"}}}'
invoke '{"operation":"v/ops/covia/write","input":{"path":"w/purchase-orders/Globex Ltd/PO-2024-0790","value":{"po_number":"PO-2024-0790","vendor":"Globex Ltd","amount_authorised":150000,"currency":"USD","department":"Operations","budget_code":"OPS-PLATFORM-2024","status":"OPEN","approver":"D. Chen"}}}'
invoke '{"operation":"v/ops/covia/write","input":{"path":"w/purchase-orders/Initech Systems/PO-2024-0312","value":{"po_number":"PO-2024-0312","vendor":"Initech Systems","amount_authorised":8000,"currency":"USD","department":"IT","budget_code":"IT-MAINT-2024","status":"OPEN","approver":"R. Kapoor"}}}'

# 4. Store documents (read from assets/ and write as string values)
echo "Storing documents..."
python -c "
import json, urllib.request
api = '$API'
for path, file in [('w/docs/policy-rules', '$DIR/assets/ap-policy-rules.md'), ('w/docs/data-guide', '$DIR/assets/ap-data-guide.md')]:
    with open(file) as f: text = f.read()
    data = json.dumps({'operation': 'v/ops/covia/write', 'input': {'path': path, 'value': text}}).encode()
    urllib.request.urlopen(urllib.request.Request(api, data=data, headers={'Content-Type': 'application/json'}))
"

# 5. Store pipeline orchestration
echo "Storing pipeline..."
invoke_file "v/ops/covia/write" <(python -c "
import json
with open('$DIR/assets/ap-pipeline.json') as f: pipeline = json.load(f)
print(json.dumps({'path': 'o/ap-pipeline', 'value': pipeline}))
")

# 6. Create agents (pipe JSON files directly)
echo "Creating agents..."
for agent in alice bob carol dave; do
  invoke_file "v/ops/agent/create" "$DIR/assets/$agent.json" &
done
wait

# 7. Verify
echo ""
echo "=== Verification ==="
curl -sf -X POST "$API" -H "Content-Type: application/json" \
  -d '{"operation":"v/ops/agent/list","input":{}}' | \
  python -c "
import json, sys
data = json.load(sys.stdin)
agents = data.get('output', {}).get('agents', [])
for a in agents:
    name = a.get('agentId', '?')
    status = a.get('status', '?')
    print(f'  {name}: {status}')
print(f'\n{len(agents)} agents ready.')
"
