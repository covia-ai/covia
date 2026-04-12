#!/usr/bin/env python3
"""AP Demo Setup — seeds reference data, documents, pipeline, and agents.

Usage: python setup.sh [VENUE_URL]
       Default venue: http://localhost:8080

All data lives in assets/ as JSON or Markdown files. This script just
pipes them to the venue API — no transformation, no templating.
"""

import json, os, sys, urllib.request, urllib.error

VENUE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
API = f"{VENUE}/api/v1/invoke"
DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets")


def invoke(operation, input_data):
    """POST an operation to the venue and return the output."""
    body = json.dumps({"operation": operation, "input": input_data}).encode()
    req = urllib.request.Request(API, data=body, headers={"Content-Type": "application/json"})
    resp = json.loads(urllib.request.urlopen(req).read())
    return resp.get("output", {})


def write(path, value):
    """covia_write a value to a lattice path."""
    invoke("v/ops/covia/write", {"path": path, "value": value})


def write_file(path, filepath):
    """covia_write the contents of a JSON file (parsed) to a lattice path."""
    with open(filepath) as f:
        write(path, json.load(f))


def write_text(path, filepath):
    """covia_write the contents of a text file (as string) to a lattice path."""
    with open(filepath) as f:
        write(path, f.read())


def write_data(filepath):
    """covia_write using a JSON file that contains {path, value}."""
    with open(filepath) as f:
        data = json.load(f)
    write(data["path"], data["value"])


def create_agent(filepath):
    """agent_create using a JSON file that is the complete input."""
    with open(filepath) as f:
        invoke("v/ops/agent/create", json.load(f))


# ── Check venue ──────────────────────────────────────────────
print(f"=== AP Demo Setup ===")
print(f"Venue: {VENUE}")
try:
    urllib.request.urlopen(f"{VENUE}/api/v1/status")
except Exception:
    print(f"ERROR: Venue not reachable at {VENUE}")
    sys.exit(1)

# ── Reference data ───────────────────────────────────────────
print("Seeding reference data...")
for f in ["vendor-acme.json", "vendor-globex.json", "vendor-initech.json",
          "po-acme.json", "po-globex.json", "po-initech.json"]:
    write_data(os.path.join(DIR, f))

# ── Documents ────────────────────────────────────────────────
print("Storing documents...")
write_text("w/docs/policy-rules", os.path.join(DIR, "ap-policy-rules.md"))
write_text("w/docs/data-guide", os.path.join(DIR, "ap-data-guide.md"))

# ── Pipeline orchestration ───────────────────────────────────
print("Storing pipeline...")
write_file("o/ap-pipeline", os.path.join(DIR, "ap-pipeline.json"))

# ── Agents ───────────────────────────────────────────────────
print("Creating agents...")
for agent in ["alice", "bob", "carol", "dave"]:
    create_agent(os.path.join(DIR, f"{agent}.json"))

# ── Verify ───────────────────────────────────────────────────
print()
print("=== Verification ===")
agents = invoke("v/ops/agent/list", {}).get("agents", [])
for a in agents:
    print(f"  {a.get('agentId', '?')}: {a.get('status', '?')}")
print(f"\n{len(agents)} agents ready.")
