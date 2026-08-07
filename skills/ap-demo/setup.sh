#!/bin/bash
# AP Demo Setup — seeds reference data, documents, pipeline, and agents.
# Usage: bash skills/ap-demo/setup.sh [VENUE_URL]
# Default venue: http://localhost:8080
#
# All data lives in assets/ as JSON files. This script just pipes them
# to the venue API via curl. No other dependencies.

set -e
VENUE="${1:-http://localhost:8080}"
API="$VENUE/api/v1/run"
DIR="$(cd "$(dirname "$0")/assets" && pwd -W 2>/dev/null || pwd)"

# Write a {path, value} JSON file to the lattice
write() { curl -sf -X POST "$API" -H "Content-Type: application/json" -d "{\"operation\":\"v/ops/covia/write\",\"input\":$(cat "$DIR/$1")}" > /dev/null; }

# Recreate an agent explicitly: ignore "not found" from delete, then create.
agent() {
  local name="$1"
  local file="$2"
  curl -s -X POST "$API" -H "Content-Type: application/json" \
    -d "{\"operation\":\"v/ops/agent/delete\",\"input\":{\"agentId\":\"$name\",\"remove\":true}}" > /dev/null
  curl -sf -X POST "$API" -H "Content-Type: application/json" \
    -d "{\"operation\":\"v/ops/agent/create\",\"input\":$(cat "$DIR/$file")}" > /dev/null
}

echo "=== AP Demo Setup ==="
echo "Venue: $VENUE"
curl -sf "$VENUE/api/v1/status" > /dev/null || { echo "ERROR: Venue not reachable"; exit 1; }

echo "Seeding reference data..."
write vendor-acme.json
write vendor-globex.json
write vendor-initech.json
write po-acme.json
write po-globex.json
write po-initech.json

echo "Storing documents..."
write doc-policy-rules.json
write doc-data-guide.json

echo "Storing pipeline..."
write pipeline.json

echo "Creating agents..."
agent Alice alice.json
agent Bob bob.json
agent Carol carol.json
agent Dave dave.json

echo ""
echo "=== Done ==="
curl -sf -X POST "$API" -H "Content-Type: application/json" -d '{"operation":"v/ops/agent/list","input":{}}' | python -c "
import json,sys
for a in json.load(sys.stdin).get('agents',[]):
    print(f'  {a[\"agentId\"]}: {a[\"status\"]}')
" 2>/dev/null || echo "  (install python to see agent status)"
