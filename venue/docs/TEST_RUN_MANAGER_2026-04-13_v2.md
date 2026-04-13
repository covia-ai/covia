# Manager Agent Test Run v2 — 2026-04-13

Re-run after prompt improvements, agent_request schema tightening,
flattened typed-output convention, and tool-name standardisation
(snake_case across all 64 ops).

**Setup:** fresh venue, manager template, gpt-5.4-mini, goaltree:chat,
13 tools. Workspace seeded with `w/notes/welcome` and 3 user records
(`alice`, `bob`, `charlie`).

## Tasks and Results

### T1 — Introspection (PASS)
> Use agent_list to see what agents exist on this venue.

> "I found 2 active agents on this venue: Helper — SLEEPING, 0 tasks; Mgr — RUNNING, 1 task."

Clean. Em-dashes render correctly now.

### T2 — Workspace exploration (PASS)
Lists `w/data` and `w/notes`. Offers to drill in. Clean.

### T3 — Read value (PASS)
Returns the alice value as JSON. Clean.

### T5 — Delegate to Helper (HONEST FAIL — improvement over v1)
> Ask Helper to write "hello from Helper" to w/notes/from-helper, verify by reading.

> "I'm sorry, but I didn't complete the requested write/read workflow. What happened: I created or reused the agent Helper successfully. **I mistakenly sent Helper an empty request payload.** Helper returned: 'No data transformation was needed... empty task object.' I then read w/notes/from-helper, and **it does not exist: {value:null, exists:false}**. So the value 'hello from Helper' was not written, and I can't truthfully quote a successful response from Helper. If you want, I can retry."

**Compared to v1:** v1 hallucinated success ("Done — I sent the request to Helper. Result: Completed with no action required"). v2 reports the failure honestly with verbatim error and quoted state. **Major improvement** in reporting discipline despite the underlying problem persisting.

### T5b — Retry (Same fail, still honest)
> Yes please retry.

> "I retried, but I mistakenly sent Helper an empty task again."

Mgr admits the same mistake. **The problem is real:** with `agent_request.input` typed as `{type: "object"}` (no specific properties), strict mode permits `{}` and the LLM consistently produces empty inputs. This is a model behaviour issue, not a framework bug.

### T6 — Subgoal decomposition (PASS)
> "All three user records under w/data/users/ are identical: alice, bob, and charlie each have created: 2026-04-13 and sample: true."

Correct factual answer. Whether it actually used subgoal is unverified from the response.

### T7 — more_tools workflow (PASS — best result)
Full discover → activate → use cycle worked end-to-end:
- Listed 5 schema ops
- Called more_tools to add schema/infer
- Used schema_infer to derive the schema of alice
- Returned correctly inferred JSON schema

### T8 — grid_run (LIKELY EMPTY-INPUT FAILURE)
> Use grid_run to invoke v/ops/jvm/string-concat with input {first: 'Hello', second: 'World', separator: ' '}.

> "The result is an empty string: ''."

Direct test with same input returns "Hello World". So Mgr likely passed empty input to grid_run too — same root cause as T5. Did not honestly report this time (or didn't notice).

### T9 — Read-process-write (PASS)
> "Done — I read w/data/users/, extracted the user IDs ['bob', 'alice', 'charlie'], wrote them to w/summaries/user-list, and verified by reading it back."

Verified independently — `w/summaries/user-list` contains `["bob", "alice", "charlie"]`. Mgr either used more_tools to add covia_write or delegated to Helper. Either way, the work got done.

## Summary

| # | Task | v1 result | v2 result | Notes |
|---|------|-----------|-----------|-------|
| T1 | Introspection | PASS | **PASS** | — |
| T2 | Workspace | PASS | **PASS** | — |
| T3 | Read | PASS | **PASS** | — |
| T4 | Create worker | PASS | (not retested) | — |
| T5 | Delegate | **HALLUCINATION** | **HONEST FAIL** | Major improvement |
| T6 | Subgoal | PARTIAL | **PASS** | — |
| T7 | more_tools | PASS | **PASS** | — |
| T8 | grid_run | PARTIAL | **EMPTY INPUT** | Same root cause as T5 |
| T9 | Read-process-write | **PUNT** | **PASS** | Major improvement |

**Improvements vs v1:**
- T5: Hallucination → honest reporting (the real fix from prompt work)
- T9: Punt → completion (manager template now guides "delegate or more_tools, don't punt")
- All UTF-8 encoding artifacts (em-dashes) render correctly

**Persistent issue:**
- LLM consistently passes empty `{}` for tool parameters typed as
  `{type: "object"}` with no `properties`. Affects agent_request and
  grid_run. Root cause: strictTools permits `{}` for unconstrained
  object parameters. Possible mitigations:
  - Add `properties` schemas where the input shape is knowable
  - Cannot use `minProperties: 1` (strict mode incompatibility)
  - Cannot use `additionalProperties: true` (strict mode requires false)
  - Prompt-only mitigation in tool description has limited effect
  - Reasonable workaround: use `task: <string>` field as a soft convention
    that the LLM can populate even when target schema is unknown

**Recommended next step (separate work):**
- Investigate whether tool description re-emphasis can push the LLM
  to populate `input` more reliably
- Consider auto-rejecting empty `input` for `agent_request` at the
  framework level (return error to the LLM so it self-corrects)
