# Manager Agent Test Run — 2026-04-13

Live test of `v/agents/templates/manager` (gpt-5.4-mini, goaltree:chat, 13 tools including subgoal/compact/more_tools).

**Setup:** fresh venue, OpenAI key set, workspace seeded with `w/notes/welcome`, `w/data/users/alice`, `w/data/users/bob` (each `{created, sample}`).

**Manager tools:** agent/create, fork, message, request, list, info, cancel-task; covia/read, list; grid/run; subgoal, compact, more_tools.
**Manager caps:** none specified (full access in default test setup).

## Tasks and Results

### T1 — Introspection (PASS)

> Use agent_list to see what agents exist on this venue.

Mgr called `agent_list`, reported "1 active agent: Mgr — RUNNING — 1 task pending."

Note: Mgr correctly observed itself as RUNNING (the very task it was processing). Slightly self-referential but accurate.

### T2 — Workspace exploration (PASS)

> Use covia_list to explore my workspace at w/.

Mgr listed `w/data` and `w/notes` correctly, offered to drill in.

### T3 — Read specific value (PASS)

> Read the value at w/data/users/alice.

Returned the JSON value cleanly with markdown code block.

### T4 — Create worker agent from template (PASS)

> Create a worker agent called Helper using config = v/agents/templates/worker.

Created Helper, reported SLEEPING / 0 tasks / 0 timeline. Verified successful.

### T5 — Delegate task to Helper (FAIL — hallucinated success)

> Ask Helper to write a value to w/notes/from-helper, then verify by reading it back.

**First attempt (T5):** Mgr punted: "I need to retry this with the actual tools now available... If you want, I can proceed immediately by..." — Mgr never actually called agent_request.

**Continuation (T5b):** "Yes proceed." Mgr replied: "Done — I sent the request to Helper. Result: Completed with no action required."

**Reality check:** Helper's timeline length = 0. The value at `w/notes/from-helper` does not exist. Mgr **hallucinated** that it sent the request.

This is the most concerning finding. The agent confidently reported completion without making the tool call. No "agent_request denied" error in Mgr's logs — it simply skipped the call.

### T6 — Multi-step with subgoal hint (PARTIAL)

> Use subgoal to decompose into 2 parts: read users, summarise.

Mgr returned a correct factual answer ("2 user records, alice and bob, both created 2026-04-13 with sample: true") but it's unclear if subgoal was actually used (timeline shows the work but not subgoal frame depth). Mgr **may have just ignored the subgoal instruction** and done it flat.

### T7 — Discover and use new tool via more_tools (PASS — best result)

> List schema operations under v/ops/schema, use more_tools to add schema/infer, then infer schema of w/data/users/alice.

Full workflow worked end-to-end:
1. Listed 5 schema ops
2. Called `more_tools({operations: ["v/ops/schema/infer"]})`
3. Called `schema_infer` on the alice value
4. Returned the inferred JSON schema correctly

This is the headline success — the runtime tool discovery/expansion model works as designed.

### T8 — Grid run with operation name (PARTIAL — good error handling)

> Use grid_run on v/ops/jvm/stringConcat with input {strings: ["Hello", " ", "World"]}.

The actual operation is `v/ops/jvm/string-concat` (kebab-case). Mgr called the wrong path, got "Cannot resolve operation" error, and **correctly reported the failure** rather than fabricating a result. Offered to discover the correct name.

This confirms grid_run wires through and error messages propagate.

### T9 — Read-process-write (FAIL — but for valid reason)

> Build a list of user IDs and write them to w/summaries/user-list.

Mgr collected `["bob", "alice"]` correctly but failed to write — "rejected by the available tool/schema." Mgr **does not have covia_write** in its toolset (manager template excludes write — managers coordinate, workers write).

Mgr could have either:
- Used `more_tools` to add `v/ops/covia/write` (the right Covia move)
- Delegated to Helper (the right manager-pattern move)

Mgr did neither — it just punted. So this is a **prompt/training issue**, not a framework issue. The capability exists but the agent didn't reach for it.

## Summary

| # | Task | Result | Notes |
|---|------|--------|-------|
| T1 | Introspection | **PASS** | Clean |
| T2 | Workspace listing | **PASS** | Clean |
| T3 | Read value | **PASS** | Clean |
| T4 | Create worker | **PASS** | Template resolution works |
| T5 | Delegate to worker | **FAIL** | Hallucinated success — never called agent_request |
| T6 | Subgoal decomposition | **PARTIAL** | Correct answer; unclear if subgoal used |
| T7 | more_tools workflow | **PASS** | Full discovery+expand+use cycle works |
| T8 | grid_run | **PARTIAL** | Wrong op name; good error handling |
| T9 | Read-process-write | **FAIL** | Punted on missing capability instead of using more_tools or delegating |

**6/9 successful interactions, 2 framework-correct failures, 1 hallucination.**

## Findings

### Framework / infrastructure (all working)

- **Templates as lattice data** — `config="v/agents/templates/manager"` resolves correctly, all 13 tools materialise
- **Harness tool resolution** — subgoal, compact, more_tools all in the LLM's tool palette
- **more_tools end-to-end** — discovery → activation → use cycle works cleanly
- **Error propagation** — operation resolution failures surface to the LLM clearly
- **Auto-compact** — not triggered (conversations were short)
- **Caps** — not exercised (no caps on Mgr in this test)

### LLM behaviour issues (not framework bugs)

1. **T5 hallucination** — Mgr confidently claimed completion without making the tool call. Most concerning. Possible causes:
   - LLM confused by self-reflection ("I'll send the request" → narrating intent as outcome)
   - Tool call was attempted but failed silently (need to inspect L3 messages)
   - Need to verify by enabling more L3 tracing

2. **T6 subgoal usage** — explicit "use subgoal" instruction may have been ignored. Need to inspect frame depth in timeline to confirm.

3. **T9 missing tool** — Mgr knew it couldn't write but didn't try `more_tools` to add `covia_write` or delegate to Helper. The workflow is documented in the system prompt for `more_tools`, but Mgr didn't reach for it. May benefit from stronger system prompt guidance: "If you cannot do something due to missing tools, use more_tools or delegate."

### Other observations

- **agent_info timeline length wrong** — reported 0 even after 7 timeline entries existed. Likely a bug in the info handler returning a different field.
- **Encoding artifacts** — `\u00e2\u20ac\u201d` (em-dash) and `\u00e2\u20ac\u2122` (apostrophe) in output. Probably the venue's UTF-8 → JSON serialisation. Worth a separate look.
- **Default operation** — manager template originally had no `operation` field, so agents created from it defaulted to `llmagent:chat` not `goaltree:chat`. Fixed by adding `operation: v/ops/goaltree/chat` to the template.

## Filed / to file

- **Bug**: agent_info reports timeline length 0 even when timeline has entries
- **Bug**: encoding artifacts in agent output (em-dash, apostrophe rendered as escaped UTF-8 surrogates)
- **Issue**: Mgr T5 hallucinated tool call — investigate whether L3 actually called the tool or LLM just narrated intent
- **Improvement**: manager template system prompt should explicitly guide "if missing capability, try more_tools or delegate"

## Conclusion

The framework primitives all work — templates resolve, harness tools dispatch, more_tools discovers and activates, errors propagate cleanly. The interesting failures are LLM-level: hallucinated completion (T5) and not reaching for available recovery patterns (T9). These are addressable through prompt engineering and possibly stricter validation that "you said you called X" matches "X was actually called."
