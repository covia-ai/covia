---
name: agent-test-drive
description: Take a Covia venue for a test drive — launch it, create a fleet of agents from the standard templates, run a broad task matrix against them with a real LLM, and report what broke. Use to exercise a venue end-to-end before a release, after a subsystem change, or to see how agents actually behave rather than how they are supposed to.
argument-hint: "[run|setup|report] [--only task1,task2]"
---

# Agent Test Drive

Exercises a live venue the way a user would: real agents, a real LLM, real
tasks. Unit tests prove a mechanism works in isolation; this shows whether an
agent can *find and use* it.

## Why it is shaped this way

- **Objective checks beat reading replies.** An agent claiming it wrote a value
  proves nothing. Tasks that change state carry a `check` that reads the venue
  directly, so "it said it worked" and "it worked" stay separate.
- **Each task names what it PROBES.** A failure is then evidence about the
  venue, not about the model's mood on the day.
- **Nothing is auto-graded.** Natural-language replies are recorded verbatim
  for a human to judge. Automatic grading of prose invents confidence.
- **Templates, not one agent.** Different templates have different tools and
  capabilities; a single agent would only exercise one shape.

## Setup

The venue needs a ready LLM provider. Keep the key **out of any file** — the
venue falls back to the process environment for provider secrets:

```bash
# dev/ is gitignored; the config carries no credential
cat > dev/test-drive.json <<'JSON'
{ "venues": [ {
    "name": "Test Drive Venue", "hostname": "localhost", "port": 8099,
    "bindAddress": "127.0.0.1", "allowPrivateNetwork": true,
    "users": { "autoCreate": true },
    "auth": { "public": { "enabled": true, "caps": "unrestricted" } },
    "mcp": {}
} ] }
JSON

export ANTHROPIC_API_KEY='sk-ant-...'          # never committed, never in config
java -jar venue/target/covia.jar dev/test-drive.json &
```

`auth.public.caps: unrestricted` is safe **only** because `bindAddress` is
loopback — it lets the runner drive the venue without minting tokens. Never on
anything LAN-reachable.

Confirm the provider before spending a run:

```bash
curl -s -X POST "http://localhost:8099/api/v1/invoke?wait=true" \
  -H "Content-Type: application/json" \
  -d '{"operation":"v/ops/langchain/models","input":{}}'
```

## Run

```bash
python skills/agent-test-drive/run.py --venue http://localhost:8099 \
  --model claude-sonnet-5 --out report.md

python skills/agent-test-drive/run.py --only skills-missing,caps-denied   # subset
```

The runner deletes and recreates its fleet each time, so it is re-runnable.
A full drive is roughly 3 minutes and a few hundred LLM calls' worth of work.

## The fleet

| Agent | Template | Exercises |
|---|---|---|
| `scout` | `skilled` | Discovery, the skills system, schema-before-call |
| `worker` | `worker` | Workspace and asset operations, extraction |
| `reader` | `reader` | That capability pinning is real, not prompt fiction |
| `boss` | `manager` | Delegation to another agent |

## The task matrix

Twelve tasks spanning orientation, the skills system (index, hierarchy,
error recovery, skillset discovery), data (workspace round-trip, assets),
enforcement (a read-only agent attempting a write), coordination
(delegation), and discipline (reading a schema before calling; refusing to
invent a callable).

Read `run.py` for the exact prompts — each carries its `probe` and `expect`.

## Reading the report

Look for, in order:

1. **A `check` that disagrees with the reply.** The agent claimed something
   the venue does not show. The worst class of failure.
2. **A task that took far longer than its siblings.** Usually an agent
   flailing against a confusing surface.
3. **Replies that quote an error.** These are the best evidence you have
   about whether a diagnostic is actually actionable.
4. **The venue log.** Warnings during a drive that no task provoked.

## Extending it

Add a `dict(...)` to `tasks()`. Give it a `probe` that names the venue
behaviour under test, and a `check` if it changes state. Prefer tasks that
would *fail loudly* on a regression over tasks that merely produce output.
