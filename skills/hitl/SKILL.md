---
name: hitl
description: Drive Human-in-the-Loop requests on a Covia venue — send asks, review and answer the h/ inbox (including capability grant approval), teach resident agents to ask their humans, and smoke-test the full request → response → job-completion loop.
argument-hint: [ask|inbox|respond|agent|test]
---

# Human-in-the-Loop (HITL)

**Prerequisite:** The venue must be running and connected as an MCP server (`http://localhost:8080/mcp`). If MCP tools are not available, tell the user to run `/venue-setup local` first.

HITL (COG-16) lets agents and operations ask a human for decisions, approvals, or information. Three primitives, cleanly separated:

- **The record** — a durable document in the target user's `h/` inbox. This is where HITL semantics live; discover pending asks by listing `h/`, never by scanning job statuses.
- **The Job** — the requester's handle, parked `INPUT_REQUIRED` until the human acts. Resolves `COMPLETE` with the response, or `FAILED` on rejection/expiry. There is **no framework timeout** — an ask can wait days.
- **The response** — the inbox owner's action (`hitl_respond`), which resolves both record and job in one step.

**Ask types** (each ask: `{id, type, prompt, required?, options?, grants?}`):

| Type | Answer form | Grants |
|------|-------------|--------|
| `text` | string | — |
| `approval` | boolean | on the ask — conferred only if approved |
| `choice` | one option id | on options — conferred only if selected |
| `checkboxes` | array of option ids | on options — conferred only if selected |

**Grants are choice-bound with echo-consent**: an offer `{with, can, exp?}` rides an approval ask or an option; it is issued (as a UCAN audienced to the requester) only when the responder makes that choice **and echoes the grant** in the response. No echo → nothing conferred. Bare `with` paths mean the responder's own namespace.

## Commands

### `ask` — send a HITL request

Gather title, context, and asks from the user, then:

```
hitl_request
  title: "Pay invoice INV-4711"
  description: "Acme Ltd, £12,400, matched to PO-2231. Due Friday."   # the human sees ONLY this record — include everything needed to decide
  asks: [
    { "id": "pay", "type": "approval", "prompt": "Approve payment of £12,400?", "required": true },
    { "id": "notes", "type": "text", "prompt": "Anything to flag?" }
  ]
  timeout: 86400        # optional, seconds; omit for no expiry
  # user: "did:key:z..."  # optional — omit to ask yourself; cross-user needs a hitl/request delegation (see Cross-user below)
```

Returns a job record. The MCP tool call waits up to ~2 minutes — if the human answers in that window you get the completed output directly; otherwise the call returns/times out with the **job id**. Check later with:

```
covia_read path=j/<jobId>        # status INPUT_REQUIRED while waiting; COMPLETE carries output; FAILED carries the rejection/expiry reason
```

### `inbox` — review pending asks

```
hitl_list status="open"          # {items: [{id, from, title, status, created, expires?}], count}
covia_read path=h/<id>           # the full record: asks, options, offered grants
```

When presenting a request to the user, always show: **who** it is from (`from` is the venue-verified requester DID — trustworthy), the title/description, each ask, and **every offered grant prominently, attached to the choice that confers it** (resource, ability, expiry). Never bury a grant.

### `respond` — answer or reject

Walk the user through each ask, collect answers in the right forms, then:

```
hitl_respond
  id: "<request id>"
  outcome: "answer"                       # or "reject"
  answers: { "pay": true, "notes": "Check the PO number" }
  comment: "Approved for this invoice only"     # for reject: the reason the requester sees
  grants: [ { "with": "w/payments/", "can": "crud/read" } ]   # ECHO — only if the user explicitly approves the offered grant
```

**Grant approval rules** (enforce these in conversation, not just in the call):
1. Before echoing anything, show the user each triggered grant and ask for explicit confirmation.
2. Echo only offers the user's choices actually triggered — echoing anything else fails the whole response (by design).
3. Omitting `grants` entirely is always valid: answer the questions, confer nothing.

After responding, confirm the loop closed: `covia_read path=j/<id>` should show the requester's job `COMPLETE` (or `FAILED` with your rejection reason).

### Cross-user asks

Delivering into **another** user's inbox needs a `hitl/request` delegation from them:

```
ucan_issue                                   # run as the TARGET user
  aud: "<requester DID>"
  att: [{ "with": "<targetDID>/h/", "can": "hitl/request" }]
  exp: <unix seconds>
```

The requester presents that token (transport `ucans` / bearer) when calling `hitl_request user=<targetDID>`. Without it, delivery fails with a `hitl/request` denial and **no record is created**.

### `agent` — teach a resident Covia agent to use HITL

Resident agents get HITL from the venue skill library (`v/skills/hitl`) — every standard template already declares `skills: ["w/skills", "v/skills"]`, so the skill appears in the agent's [Skills] index and `skill_load` activates the `hitl_request`/`hitl_list` tools mid-transition. Agents ask and watch; they **cannot** answer — `hitl_respond` is refused for an agent sub-principal, since resolving an ask issues grants under the inbox owner's authority (see `venue/docs/UCAN.md` §5.4). To make an agent actually use it:

1. **Check the skill is visible** — `agent_context` (or ask the agent "what skills do you see?") should list `hitl` in the index. If the agent's config overrides `skills`, ensure `v/skills` is in the sources.
2. **Instruct it in the system prompt** — add HITL policy to `config.systemPrompt` at creation (see `/agent` for creation mechanics):

   > "For any action that is irreversible, spends money, or exceeds your authority: load the `hitl` skill and send a `hitl_request` to your owner FIRST. Put the full decision context in the description. Batch related questions into one request. If the answer is a rejection, accept it — never retry. If the tool call times out, note the job id and check it on your next turn instead of re-asking."

3. **Or instruct per-task** — include "confirm with me via a HITL request before X" in an `agent_request` task; a skills-capable agent will load the skill and ask.
4. **Timing** — an agent's HITL tool call blocks up to its `toolCallTimeoutMs` (default 5 min). If the human answers within that window the agent continues in the same cycle; otherwise the tool returns the job id and the agent should park the work and re-check the job (`covia_read path=j/<id>`) on a later wake — this is normal, not an error.
5. **Authority** — the ask goes to the agent's **owner** by default (omit `user`); that needs no delegation. Asking anyone else requires the `hitl/request` token above, and a caps-pinned agent additionally needs its ceiling to permit the op (skill loading grants no authority).

### `test` — smoke-test the full loop

Run these in order against a dev venue; each step states its expected outcome. (Engine-level coverage lives in `HITLAdapterTest` / `HitlValidationTest` — `mvn test -pl venue -Dtest=HITLAdapterTest,HitlValidationTest`.)

1. **Round trip** — `hitl_request` with one required approval ask (no timeout) → job `INPUT_REQUIRED`; `hitl_list status=open` shows it; `hitl_respond` answering `true` → `covia_read path=j/<id>` shows `COMPLETE` with `answers.pay=true`; record status `answered`.
2. **Reject** — new request; respond `outcome=reject, comment="testing"` → job `FAILED`, error contains `rejected: testing`; record `rejected`.
3. **Expiry** — request with `timeout: 5`; wait ~6s without responding → job `FAILED` with `expired`; record `expired`; responding now fails with "not open".
4. **Echo-consent** — request with an approval ask offering `{with: "w/test-grant/", can: "crud/read"}`:
   - answer `true` WITHOUT echoing → `COMPLETE`, output has **no** `token` ✓
   - new request, answer `false` WHILE echoing → response fails, record stays open ✓
   - new request, answer `true` AND echo → output carries `token`; `ucan_verify token=<jwt>` shows audience = requester, resource = `<responderDID>/w/test-grant/` ✓
5. **Adversarial input** — respond with an unknown ask id, a wrong-typed answer, or an unoffered echoed grant → each fails, record stays open, job unresolved; a subsequent valid response still completes it ✓
6. **Cross-user** (needs two identities) — request with `user=<other>` and no delegation → job `FAILED` with `hitl/request` denial and no record; issue the delegation, retry → delivered; target answers → requester's job completes ✓

## Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| Job `FAILED`: "requires hitl/request on <did>/h/" | Cross-user ask without a delegation — have the target `ucan_issue` one (see Cross-user) |
| "No HITL request <id> in your inbox" | You are not the target — only the inbox owner can respond (the requester never can) |
| "echoed grant ... was not offered" | The echo doesn't match an offer your choices triggered — echo exactly the offered `{with, can}`, only for choices you made |
| "HITL request ... is not open" | Already answered/rejected/expired/cancelled — check `covia_read path=h/<id>` status |
| Tool call timed out | Normal for long asks — the job id is in the result; poll `covia_read path=j/<id>` |
| Record not found at `h/<id>` | Record ids are bare hex; strip a leading `0x` from a job id for `covia_read` paths (`hitl_respond` accepts both forms) |
| Job stuck `INPUT_REQUIRED` after venue restart | Expected — open asks survive restarts (expiry timers re-arm at boot); just respond normally |
