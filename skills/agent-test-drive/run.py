#!/usr/bin/env python3
"""Agent test drive: exercise live agents on a running venue and report issues.

Creates a small fleet from the standard templates, runs a task matrix across
them, and writes a report. Each task states what it PROBES, so a failure is
read as evidence about the venue rather than about the model's mood.

Usage:
    python run.py [--venue http://localhost:8099] [--out report.md]

Requires a venue already running with a ready LLM provider (see SKILL.md).
"""

import argparse
import json
import sys
import time
import urllib.request

# --------------------------------------------------------------------------
# The fleet: one agent per template family worth exercising.
# --------------------------------------------------------------------------
FLEET = [
    ("scout",  "v/agents/templates/skilled",
     "General agent with the standard skill sources. Probes discovery and skills."),
    ("worker", "v/agents/templates/worker",
     "Data-processing agent. Probes workspace and asset operations."),
    ("reader", "v/agents/templates/reader",
     "Capability-pinned read-only agent. Probes that enforcement is real, not prompt fiction."),
    ("boss",   "v/agents/templates/manager",
     "Coordination agent. Probes delegation to another agent."),
]

# --------------------------------------------------------------------------
# The task matrix. `expect` is a human-readable success criterion; the run
# records the actual result and never auto-grades a natural-language answer.
# `check` (optional) is a callable over the venue for objective verification.
# --------------------------------------------------------------------------
def tasks(venue):
    return [
        dict(id="orient", agent="scout", probe="Venue orientation via the venue skill",
             prompt="What venue are you running on? Give its name and DID. "
                    "Load a skill first if you need to.",
             expect="Names this venue and a did:key:... value, obtained from the venue, not invented."),

        dict(id="skills-index", agent="scout", probe="The always-on [Skills] index is present and usable",
             prompt="List the skills currently available to you, by name only.",
             expect="Lists roughly the eight root skills; does not invent skills."),

        dict(id="skills-hierarchy", agent="scout",
             probe="Hierarchical discovery: loading a parent reveals its family",
             prompt="Load the 'workspace' skill. Then tell me which NEW skills became "
                    "available to you that were not listed before.",
             expect="Loads workspace, then names data-family skills (assets, files, memory)."),

        dict(id="skills-missing", agent="scout",
             probe="A wrong skill name yields an error the agent can act on",
             prompt="Load a skill called 'nonexistent-thing'. If that fails, tell me exactly "
                    "what the error said and what you would do instead.",
             expect="Reports a not-found error that NAMES available skills, and picks a real one."),

        dict(id="skillsets", agent="scout",
             probe="Skillset discovery via ordinary lattice reads (no registry)",
             prompt="What skillsets does this venue ship? List their names.",
             expect="Lists v/skills keys (root, data, grid, ...) via a lattice list."),

        dict(id="workspace-rw", agent="worker",
             probe="Workspace write then read-back through covia ops",
             prompt="Store the exact JSON value {\"drive\": \"ok\", \"n\": 42} at the workspace "
                    "path w/testdrive/note. Then read it back and tell me what you read.",
             expect="Writes and reads back {drive: ok, n: 42}.",
             check=lambda v: read_path(v, "w/testdrive/note")),

        dict(id="asset-store", agent="worker",
             probe="Content-addressed asset store and retrieval",
             prompt="Store a small text asset containing exactly 'test drive payload' and "
                    "tell me the resulting asset hash. Then retrieve it and confirm the content.",
             expect="Returns a hash and confirms the round-tripped content."),

        dict(id="caps-denied", agent="reader",
             probe="Capability enforcement is real for a read-only agent",
             prompt="Write the value \"should-not-happen\" to the workspace path "
                    "w/testdrive/forbidden. If you cannot, say exactly why.",
             expect="FAILS to write and reports a capability denial — not a claim of success.",
             check=lambda v: read_path(v, "w/testdrive/forbidden")),

        dict(id="delegate", agent="boss",
             probe="Agent-to-agent delegation through agent:request",
             prompt="Delegate this to the agent called 'worker': ask it to write the value "
                    "\"delegated-ok\" at w/testdrive/delegated. Report what it did.",
             expect="Uses the request operation against worker; the path ends up written.",
             check=lambda v: read_path(v, "w/testdrive/delegated")),

        dict(id="schema-first", agent="scout",
             probe="Reads an operation's schema before calling it",
             prompt="Without guessing, find out what inputs the operation v/ops/covia/inspect "
                    "takes, then use it to inspect the path v/info and summarise what you see.",
             expect="Inspects the op metadata, then calls it correctly."),

        dict(id="hallucination", agent="scout",
             probe="Refuses to invent a callable that is not in the palette",
             prompt="Call the operation 'v/ops/magic/doEverything' with input {}. "
                    "If it does not exist, say so plainly instead of pretending.",
             expect="Reports the operation does not exist; does not fabricate a result."),

        dict(id="structured", agent="worker",
             probe="Structured extraction into a given shape",
             prompt="From this text, extract a JSON object with keys 'vendor' and 'total': "
                    "'Invoice from Acme Corp for a total of 1234.56 GBP'. Reply with JSON only.",
             expect="Returns {\"vendor\": \"Acme Corp\", \"total\": 1234.56} or equivalent."),
    ]


# --------------------------------------------------------------------------
# Venue plumbing
# --------------------------------------------------------------------------
def post(venue, path, body, timeout=180):
    data = json.dumps(body).encode()
    req = urllib.request.Request(venue + path, data=data,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def get(venue, path, timeout=60):
    with urllib.request.urlopen(venue + path, timeout=timeout) as r:
        return json.loads(r.read().decode())


def invoke(venue, op, inp, wait=True, timeout=180):
    q = "?wait=true" if wait else ""
    return post(venue, "/api/v1/invoke" + q, {"operation": op, "input": inp}, timeout)


def read_path(venue, path):
    """Objective check: what is actually at a lattice path, venue-side."""
    try:
        return get(venue, "/api/v1/values/read?path=" + path).get("value")
    except Exception as e:
        return f"(unreadable: {e})"


def create_agents(venue, model):
    made = []
    for name, template, purpose in FLEET:
        # Re-runnable: create never overwrites, so clear a previous drive first.
        try:
            invoke(venue, "v/ops/agent/delete", {"agentId": name, "remove": True})
        except Exception:
            pass
        cfg = [template, {"llmOperation": "v/ops/langchain/anthropic", "model": model}]
        res = invoke(venue, "v/ops/agent/create",
                     {"agentId": name, "config": cfg})
        out = res.get("output") or {}
        made.append(dict(name=name, template=template, purpose=purpose,
                         status=res.get("status"), created=out.get("status"),
                         warnings=out.get("warnings"), error=res.get("error")))
    return made


def run_task(venue, task, timeout_ms=180000):
    """One agent request, waited out, with the timeline captured on failure."""
    started = time.time()
    try:
        res = invoke(venue, "v/ops/agent/request",
                     {"agentId": task["agent"], "input": task["prompt"],
                      "timeout": timeout_ms},
                     timeout=(timeout_ms // 1000) + 30)
    except Exception as e:
        return dict(status="TRANSPORT_ERROR", output=None, error=str(e),
                    seconds=round(time.time() - started, 1))
    out = res.get("output")
    # A slow agent returns a task handle; poll it to completion.
    if isinstance(out, dict) and out.get("status") in ("STARTED", "PENDING"):
        out = poll_task(venue, out.get("id"), timeout_ms)
    return dict(status=res.get("status"), output=out, error=res.get("error"),
                seconds=round(time.time() - started, 1))


def poll_task(venue, job_id, timeout_ms):
    deadline = time.time() + timeout_ms / 1000
    while time.time() < deadline:
        time.sleep(3)
        try:
            j = get(venue, "/api/v1/jobs/" + job_id)
        except Exception as e:
            return {"pollError": str(e)}
        if j.get("status") in ("COMPLETE", "FAILED", "CANCELLED", "REJECTED"):
            return j.get("output") or j
    return {"timeout": "task did not reach a terminal state"}


def text_of(output):
    """Best-effort extraction of the agent's reply for the report."""
    if output is None:
        return ""
    if isinstance(output, str):
        return output
    if isinstance(output, dict):
        for k in ("output", "result", "text", "message", "content", "reply"):
            if k in output:
                return text_of(output[k])
        return json.dumps(output)[:2000]
    return str(output)[:2000]


# --------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--venue", default="http://localhost:8099")
    ap.add_argument("--model", default="claude-sonnet-5")
    ap.add_argument("--out", default="test-drive-report.md")
    ap.add_argument("--only", default=None, help="comma-separated task ids")
    args = ap.parse_args()

    status = get(args.venue, "/api/v1/status")
    print(f"venue: {status.get('name')} ({status.get('did')})", flush=True)

    print("creating fleet...", flush=True)
    fleet = create_agents(args.venue, args.model)
    for a in fleet:
        print(f"  {a['name']:8} {a['created'] or a['status']}"
              f"{'  WARN ' + json.dumps(a['warnings']) if a['warnings'] else ''}"
              f"{'  ERROR ' + str(a['error']) if a['error'] else ''}", flush=True)

    selected = tasks(args.venue)
    if args.only:
        wanted = set(args.only.split(","))
        selected = [t for t in selected if t["id"] in wanted]

    results = []
    for t in selected:
        print(f"task {t['id']} ({t['agent']})...", end=" ", flush=True)
        r = run_task(args.venue, t)
        if t.get("check"):
            r["check"] = t["check"](args.venue)
        results.append((t, r))
        print(f"{r['status']} in {r['seconds']}s", flush=True)

    write_report(args.out, status, fleet, results)
    print(f"\nreport: {args.out}", flush=True)


def write_report(path, status, fleet, results):
    L = []
    L.append("# Agent test drive report\n")
    L.append(f"Venue: **{status.get('name')}** — `{status.get('did')}` "
             f"(v{status.get('version')})\n")
    L.append("## Fleet\n")
    L.append("| Agent | Template | Created | Warnings |")
    L.append("|---|---|---|---|")
    for a in fleet:
        L.append(f"| {a['name']} | `{a['template'].split('/')[-1]}` | "
                 f"{a['created'] or a['status']} | "
                 f"{json.dumps(a['warnings']) if a['warnings'] else '—'} |")
    L.append("\n## Tasks\n")
    L.append("| Task | Agent | Status | Time |")
    L.append("|---|---|---|---|")
    for t, r in results:
        L.append(f"| {t['id']} | {t['agent']} | {r['status']} | {r['seconds']}s |")
    L.append("\n## Detail\n")
    for t, r in results:
        L.append(f"### `{t['id']}` — {t['probe']}\n")
        L.append(f"**Agent:** {t['agent']}  |  **Status:** {r['status']}  "
                 f"|  **{r['seconds']}s**\n")
        L.append(f"**Prompt:** {t['prompt']}\n")
        L.append(f"**Expected:** {t['expect']}\n")
        if r.get("error"):
            L.append(f"**Error:** `{r['error']}`\n")
        if "check" in r:
            L.append(f"**Venue-side check:** `{json.dumps(r['check'])[:400]}`\n")
        reply = text_of(r.get("output"))
        L.append("**Reply:**\n")
        L.append("```\n" + (reply[:2500] if reply else "(no output)") + "\n```\n")
    open(path, "w", encoding="utf-8").write("\n".join(L) + "\n")


if __name__ == "__main__":
    sys.exit(main())
