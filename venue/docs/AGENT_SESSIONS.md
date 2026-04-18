# Agent Sessions — Design Proposal

**Status:** Implemented — April 2026. Stages 1–3 complete.

This doc proposes **sessions** as a first-class agent primitive: **scoped interactions between parties, with their own history and context, jointly owned by the participants**. A session is a communication primitive — a channel. Whatever the parties build together (documents, deliverables, long-lived work state) lives elsewhere (see [PROJECTS.md](./PROJECTS.md) for that layer).

The current agent model assumes 1 transition = 1 timeline entry = 1 result snapshot. This is fundamentally a request handler model. It cannot express:

- Long-running conversations between an agent and a counterparty
- Multiple concurrent scoped channels (agent has conversation A with user X about topic P while holding conversation B with agent Y about topic Q)
- Persistent working memory beyond the next transition's input
- Coherent "what channel is this message on" routing

Sessions fix this by introducing a layer between *agent* (long-lived identity) and *transition* (single compute step). Symptom issues #67, #68, #69 fall out naturally — they're all consequences of the missing session layer.

---

## 1. Problem

### 1.1 Current model

An agent record contains a flat `timeline` vector. Every transition appends one entry that snapshots the full state, config, result, and task results. There is no notion of:

- **Conversation continuity** — turn N+1 has no link to turn N other than re-reading the timeline
- **Logical work units** — a multi-step task is N independent timeline entries
- **Multiple parallel contexts** — all work is interleaved in one timeline
- **Memory beyond the next transition** — state is opaque to the framework, history is reconstructed from timeline

### 1.2 Symptoms (existing issues)

- **#67** — Config duplicated in every timeline entry (no notion of "config snapshot per session")
- **#68** — Result duplicated in `result.response` and `taskResults` (no notion of "session result")
- **#69** — Empty `messages`/`pending`/`inbox` per timeline entry (channels are agent-level, not session-level)
- **#71** — Manager agents lose context between pipeline steps
- **#57** — Multi-hop delegation timeout (no session = no resumable state)
- **General** — Cannot build a chatbot without faking conversation history

### 1.3 What's missing

A persistent context that an agent maintains state and history within. Independent of any single request. Discoverable, addressable, and pruneable.

---

## 2. Core Abstraction: Session

A **session** is a scoped interaction between parties with its own history and context, jointly owned by the participants.

- **Identifier** — opaque, venue-minted, MCP/A2A-compatible (matches `Mcp-Session-Id` semantics and A2A `contextId`)
- **Parties** — the DIDs participating. Typically one agent + one counterparty; multi-party is a future question (§9.3)
- **Metadata** — parties, title, status, timestamps, optional counterparty-facing subject
- **Own history / context** — turns, tool calls, tool results, pinned context entries
- **Jointly owned** — every listed party holds read/append capability; neither is subordinate

Sessions live at `g/<agent>/sessions/<sid>/` in the agent's lattice namespace. The hosting venue mints the id; counterparties echo it on subsequent requests, exactly as in MCP.

A session is *just data* — the framework doesn't enforce a particular shape for state or history beyond the standard fields. Different adapter configurations can use sessions differently.

### 2.1 What a session is NOT

- Not a job (jobs are per-request execution units; sessions span many jobs)
- Not a task (tasks are discrete actionable items, often within a session)
- Not a transition (transitions are ephemeral compute steps within a session)
- Not a project (projects are domain state — documents, plans, deliverables — that sessions may reference but don't own; see [PROJECTS.md](./PROJECTS.md))
- Not a memory store (cross-session facts/decisions belong in a separate memory layer — §9.2)

---

## 3. Patterns Supported

All variations of the same primitive:

| Pattern | Session shape | Example |
|---------|---------------|---------|
| **Chatbot** | One session per (agent, user); long-lived dialogue | Personal assistant |
| **Customer support** | Session per ticket; counterparty = customer DID | Helpdesk agent |
| **Investigation** | Session per topic; agent accumulates findings in-channel | Audit agent |
| **Pipeline worker** | Session per pipeline run; ephemeral, completes | AP demo (Bob, Carol) |
| **One-shot** | Single-turn session that completes immediately; effectively stateless from caller's POV | Pure extraction (Alice) |

The same agent can run many sessions concurrently — a counterparty may hold several concurrent sessions with the same agent, distinguished by id and title.

Long-lived work (projects, plans, deliverables) that persists beyond any one conversation is not modelled as a session — see [PROJECTS.md](./PROJECTS.md).

---

## 4. Storage Layout

Each scope has **one** state bucket accessed via a single shorthand. No parallel `state` fields — the shorthand IS the scope's state.

```
g/<agent>/
  config              — agent-level config (identity, default tools, caps, model, transitionOp)
  timeline            — audit log of session/task events
  tasks/              — Index of tasks (jobs assigned to this agent), keyed by
                        taskId. **Task ID == caller's request Job ID** — the
                        request Job is the system of record; the task entry is
                        the in-flight instruction keyed by that same ID.
  sessions/           — Index of sessions, keyed by sid
    <sid>/
      meta            — {id, parties, title, status, started, lastActivity, turnCount}
      history         — vector of turns/events (full transcript)
      pending         — messages queued for next transition (§5.5.2)
      config?         — session-level config overrides (rare)
```

Other framework-managed fields on the agent record (status, scheduling, error, caps, timeline entries) are documented where they are authoritative — see [GRID_LATTICE_DESIGN.md §4.3](./GRID_LATTICE_DESIGN.md) for the top-level shape, [SCHEDULER.md](./SCHEDULER.md) for wake state, and [AGENT_LOOP.md](./AGENT_LOOP.md) for timeline entry structure.

**User scratch space** for the agent, session, and task lives in the virtual namespaces `n/`, `c/`, and `t/` respectively — see [GRID_LATTICE_DESIGN.md §4.5](./GRID_LATTICE_DESIGN.md). These are for arbitrary user data; framework-managed state (status, `wakeTime`, task input/result, etc.) is accessed through dedicated APIs, not via these shortcuts.

Tasks and sessions are **orthogonal**. A task is a Covia job assigned to the agent; a session is a conversational scope. A task MAY reference a session it was spawned from (via `sessionId` inside its taskdata) but does not live under it. A session MAY reference the tasks it spawned, but its history doesn't require one.

**Data persists forever** (subject to user deletion). The scope lifecycle only governs *where writes are directed based on the current context*, not when data is removed. Task completion, session archive, and agent termination do not wipe the corresponding scratch — it remains at the full lattice path as audit data.

### 4.1 User-scratch shorthands

Three virtual namespaces expose user-writable scratch scoped to the agent, session, and job/task:

| Shorthand | Scope | Typical use |
|-----------|-------|-------------|
| `t/` | Job (= task when task is focused) | Working notes for this unit of work |
| `c/` | Session | Conversational context, topic notes |
| `n/` | Agent | Long-term memory, persona, cross-session state |

`w/` and `o/` are the user-level workspace and operation-registry namespaces (see [GRID_LATTICE_DESIGN.md §4](./GRID_LATTICE_DESIGN.md)).

Resolution rules, lifetimes, scoping inheritance, and the split between user scratch and framework-managed state are defined in [GRID_LATTICE_DESIGN.md §4.5](./GRID_LATTICE_DESIGN.md).

### 4.3 Session metadata shape (suggested)

```json
{
  "id": "01HXYZABC123...",
  "parties": ["did:key:z6MkAgent...", "did:key:z6MkUser..."],
  "title": "AP demo improvements",
  "status": "active",
  "started": 1776079053991,
  "lastActivity": 1776082534100,
  "turnCount": 47
}
```

The id is opaque and venue-minted (UUID-ish, MCP/A2A-compatible). `parties` is the authoritative access-control list — capability checks read it directly. Title is free-form, human-facing. Status tracks lifecycle (§7).

---

## 5. API Surface

### 5.1 Three intake operations

The boundary surface has three distinct ops that encode the caller's intent. The agent's transition contract is uniform — the framework dispatches the agent's response based on which op queued the work (§6.3).

```
agent_request   agentId=X  sessionId=Y?  input=...      — submit a tracked task. Caller awaits result.
agent_chat      agentId=X  sessionId=Y?  message=...    — synchronous request for a response. Caller awaits next response.
agent_message   agentId=X  sessionId=Y   content=...    — fire-and-forget notification. No response expected.
agent_trigger   agentId=X  sessionId=Y?                 — wake the agent. No payload, no response.
```

| Op | Queue | Caller waits? | Completion semantics |
|----|-------|---------------|----------------------|
| `agent_request` | `tasks` Index | yes (Job) | Agent must produce `response` (auto-completes task) or `error`, or yield (task stays pending until external wake) |
| `agent_chat` | session-scoped chat slot | yes (Job) | Agent's next `response` on this session completes the chat job. Typically continues an existing session (`sessionId` supplied); mints a new one on first contact |
| `agent_message` | session `pending` | no | Agent processes whenever; any `response` lands in session history |
| `agent_trigger` | none | block on cycle quiesce/yield | **Fallback kick** — nudges the run loop, no payload, no result-await. Callers wanting output should wait on a task/chat Job from `agent_request` / `agent_chat`. |

Session ids are always venue-minted — callers never supply their own. Per-op rules are detailed in §5.5; summary:

- `sessionId` supplied + session known → continue that session (caller echoes the id, A2A/MCP-style)
- `sessionId` supplied + session unknown → error
- `sessionId` omitted on `agent_request` / `agent_chat` → venue mints a new session, returns the id
- `sessionId` omitted on `agent_message` → **error** (messages require a session)
- `session={title: ..., parties: [...]}` on `agent_request` → create new session with explicit metadata; the response includes the minted id

**Why three ops, not flags on one op.** Each op encodes a distinct caller intent (long-running task vs. wait-for-reply vs. notify). The agent doesn't have to declare its mode — the framework already knows what to do with the agent's response based on which queue picked the work. This eliminates the "is this response a task completion or a chat reply or both?" ambiguity that comes from a single intake op + per-call flags.

### 5.2 New operations

| Op | Purpose |
|----|---------|
| `agent:sessionList` | List sessions for an agent (filter by type, counterparty, status) |
| `agent:sessionInfo` | Get metadata + summary for a session |
| `agent:sessionArchive` | Mark a session archived (no longer active) |
| `agent:sessionDelete` | Permanently remove (rare; audit-sensitive) |

Or — alternatively, no new operations: sessions are just lattice data, use `covia_list path=g/<agent>/sessions` to discover. This is more in keeping with the lattice-everything philosophy.

### 5.3 Discovery via standard lattice ops

```
covia_list  path=g/Assistant/sessions                       — all sessions
covia_read  path=g/Assistant/sessions/<sid>/meta            — one session's metadata
covia_slice path=g/Assistant/sessions/<sid>/history limit=20 — recent turns
```

### 5.4 Default session rule

**Every interaction belongs to a session.** Missing `sessionId` → venue mints a new one, returns it in the response, caller echoes it on subsequent requests to continue.

| Request | Behaviour |
|---------|-----------|
| `sessionId` absent | Venue mints new session, creates it, returns id in response alongside the result |
| `sessionId` present and known | Continue that session |
| `sessionId` present and unknown | Error (caller should retry without id to get a fresh session) |

This matches both A2A `contextId` and MCP `Mcp-Session-Id`:

> **A2A** — "Agents **MAY** generate a new `contextId` when processing a Message that does not include a `contextId` field. If an agent generates a new `contextId`, it **MUST** be included in the response... A `contextId` logically groups multiple related Task and Message objects, providing continuity across a series of interactions."
> — [a2a-protocol.org/latest/specification/](https://a2a-protocol.org/latest/specification/)
>
> **MCP** — server mints `Mcp-Session-Id` at initialisation, client echoes on every subsequent request; stale id returns 404 and forces re-initialisation.
> — [modelcontextprotocol.io/specification/2025-06-18/basic/transports](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)

**Why this is right:**

- **Protocol-compliant** — matches A2A and MCP without translation layers
- **No implicit continuity** — a fresh request always gets a fresh session; caller opts into continuity by echoing the id (this is different from "per-caller default" which silently re-binds across calls)
- **Uniform audit** — every interaction has a `{sessionId, parties, started}` record, whether one-shot or long-lived
- **Symptoms #67/#68/#69 fix uniformly** — session history lives in the session; agent-level timeline reduces to a thin audit log of session events
- **One transition path** — adapters always see a session; no two contracts to maintain (§6.1/6.2)

**What about pipeline agents (Alice, Bob, Carol)?** Each invocation gets a short-lived session that completes in one or two turns. The orchestration supplies `sessionId` if it wants Bob's enrichment and Carol's decision grouped under the invoice's pipeline session; otherwise each agent gets its own per-invocation session. Mild overhead (one session record per invocation) buys uniform provenance.

**What about per-caller continuity (old Option A)?** Not a framework default — it's a caller pattern. A chatbot client that wants "one ongoing conversation per user" stores the minted `sessionId` alongside the user profile and echoes it. The framework doesn't need to know.

### 5.5 Messages and session routing

The four intake ops play different roles, so they have different rules for missing `sessionId`:

| Op | `sessionId` absent | `sessionId` present and known | `sessionId` present and unknown |
|----|--------------------|-------------------------------|---------------------------------|
| `agent_request` | Mint new session, return id with task result | Continue that session | Error |
| `agent_chat` | Mint new session, return id with response | Continue that session | Error |
| `agent_message` | **Error** — messages require a session | Append to session history, wake agent if sleeping | Error |
| `agent_trigger` | Wake agent (no session scope) | Wake agent scoped to that session | Error |

**Why the asymmetry.** `agent_request` and `agent_chat` can both mint on missing — but for `agent_chat` that's the *first turn* case, not the typical one. The normal case for `agent_chat` is **continuing an existing session** (sessionId supplied) — that's what conversational interaction looks like once it's underway. Mint-on-missing exists so first contact doesn't require a separate "start session" call. By contrast, `agent_message` is inherently conversational and a message without a conversation doesn't make sense, so it errors on missing.

This matches A2A: their `message/send` is effectively our `agent_chat` — synchronous, returns a response, normally continues a context (and may create one on first contact). Our `agent_request` is the heavier "submit a task" cousin (Job tracked, may take long, completion is explicit). Pure conversational continuation (our `agent_message`) requires an existing context.

**Conversational flow:**

```
client → agent_request {agentId, input: "hello"}            ← no sessionId
         ← {sessionId: "s-01HX...", result: "hi there!"}

client → agent_message {agentId, sessionId: "s-01HX...",     ← echoes id
                        content: "what's the weather?"}
         ← {queued: true}

client → agent_message {agentId, sessionId: "s-01HX...",
                        content: "thanks"}
```

#### 5.5.1 Where messages live

Routed messages are appended directly to `g/<agent>/sessions/<sid>/history`. There is no agent-level `messages` queue for conversational traffic — that's what #69 was complaining about, and it goes away.

There is no agent-level inbox. All messages are routed to `session.pending` and drained by the run loop on the next transition cycle for that session.

#### 5.5.2 Multiple messages before the agent transitions

If a caller sends several messages on the same session while the agent is still sleeping or mid-transition, they queue at session level, not agent level:

```
g/<agent>/sessions/<sid>/
  history    — appended turns (processed)
  pending    — messages received but not yet presented to a transition
```

When the agent next transitions, all items in `pending` are moved into the transition input (as a batch of new messages in a single turn boundary) and then into `history` once the transition completes. This preserves ordering per-session and prevents interleaving across sessions — conversation A can't disrupt conversation B's turn assembly.

#### 5.5.3 Agent-to-agent messages (pipelines)

When one agent messages another as part of a shared pipeline run, the orchestration mints a single "pipeline session" up front and passes the `sessionId` to every agent it invokes. Each agent appends its turn to the shared session's history, and Carol can see that Bob has already recorded his enrichment in the same session without cross-agent lookups.

When agents correspond outside an orchestration (Bob asks Carol a question on his own initiative), each participant's `agent_message` / `agent_request` still follows the mint-on-missing rule: the first outbound call mints a session, subsequent calls echo the id. The two agents share one session because they share the id, not because of any special "agent-to-agent" machinery.

This keeps §9.3 (multi-party sessions) open — a shared pipeline session already has >2 parties in practice; formalising it is a future question, not a Phase 1 blocker.

---

## 6. Transition Contract

Under §5.4, every transition runs inside a session — there is **one contract**, not two. The "sessionless" legacy shape is retired; existing agents migrate by being wrapped in a per-invocation session (see §11.3).

### 6.1 Unified contract

Transitions see each scope's state by its shorthand name. The output is a single **response value** — what the agent wants to emit this turn (chat reply, task progress note, or task result). State writes happen during the transition via lattice ops; task completion/failure happens via dedicated venue ops invoked by the adapter (or by the LLM via the tool loop).

```
input: {
  agentId,
  n,                   — agent state snapshot (from g/<agent>/n/). Rare updates.
  session: {
    sessionId, parties, meta,
    c,                 — session state snapshot (from sessions/<sid>/c/). Common updates.
    history,           — conversation transcript
    pending            — messages queued since last transition (§5.5.2)
  },
  task?: {             — present only when this transition is servicing a task
    taskId,
    sessionId?,
    t,                 — task state snapshot (the `t` field of the taskdata map
                         at g/<agent>/tasks/<taskId>)
    input              — the task's input payload
  },
  newInput             — the new request/message that triggered this transition
}

output: ACell           — the response value (or null), appended to c/history
```

If the return value is non-null, the framework appends it as a turn to `c/history`. For chat work, the return value also completes the chat Job. For task work, completion is **explicit**: the agent (or LLM tool loop) invokes `agent:complete_task` to finish; if no completion op was invoked during the transition, the task yields and stays in the queue.

**State updates during a transition.** All routed by RequestContext scoped to `(agent, session, task?)`:

| Intent | Action |
|---|---|
| Update agent state | `covia_write n/...` → `g/<agent>/n/...` |
| Update session state | `covia_write c/...` → `sessions/<sid>/c/...` |
| Update task WIP | `covia_write t/...` → current task's `t` |

**Completion ops invoked during the transition.** These are venue operations, not return-value flags. They read `(agentId, taskId)` from RequestContext — and since **task ID == caller's request Job ID**, no separate jobId is needed (the live Job is recovered via `engine.jobs().getJob(taskId)`):

| Op | Effect |
|---|---|
| `agent:complete_task` (input: `result`) | Job → COMPLETE with `result`, task entry removed |
| `agent:fail_task` (input: `error`) | Job → FAILED with `error`, task entry removed |

There is no `agent:yield` op — yield is the natural state when no completion op was invoked. There is no `agent:complete_chat` op — chat completes via the return value.

**Framework-populated scope.** The run loop builds a per-cycle `RequestContext` with `withAgentId(agentId)` always, plus `withTaskId(pickedTask.getKey())` and `withSessionId(...)` when applicable. This ctx is what the transition Job runs under, so any venue op invoked from inside the transition (directly or via the LLM tool loop) sees the correct scope without explicit input args.

**Completion ordering (deferred drain).** `agent:complete_task` / `agent:fail_task` do not finish the caller's pending Job inline. They park a completion envelope (`{id, status, output|error}`) into a per-agent `deferredCompletions` map and return synchronously. The run loop, after the transition returns, drains this map to build the cycle's `taskResults` field, then commits via `mergeRunResult`, and only **then** completes (or fails) the pending Jobs identified in the drained envelopes. This ordering is load-bearing: it guarantees that any caller blocked on `Job.awaitResult` observes the timeline and lattice writes for its task before its `awaitResult` call returns. Without the deferral, the venue op would race the framework's timeline write and callers could see an empty `taskResults` immediately after their awaitResult unblocked.

The framework around the transition:

1. Resolves or mints the session before invoking (§5.4)
2. Reads `n/`, `c/`, `history`, `pending` (and `t` if a task is focused) from the lattice
3. Builds a RequestContext scoped to `(agent, session, task?, jobId)` so writes and completion ops route correctly
4. Provides the snapshots as transition input under their shorthand names
5. Invokes the transition adapter
6. On adapter return:
   - If return value non-null → append turn to `c/history`
   - If chat picked: complete chat Job with return value (yield only if return is null — rare)
   - If task picked: completed iff `agent:complete_task` / `agent:fail_task` was invoked during transition; otherwise yield, apply falloff (§6.3)
   - If message picked: no completion concept (return value already emitted to history)
7. On adapter throw → if task/chat picked, Job → FAILED with *technical* error (distinct from `fail_task` semantic failure)
8. Returns the sessionId in the response envelope (always)

Stateless adapters (pure extraction, one-shot classifiers) just invoke `agent:complete_task(result)` and return. Their session becomes a one-turn audit record.

### 6.3 Yield, falloff, and timeouts

For task work, the framework distinguishes **completed** from **yield** by observing whether `agent:complete_task` / `agent:fail_task` was invoked during the transition. Queue state is authoritative — the Job is the system of record (set by the completion op), the task entry is the in-flight instruction (removed by the completion op). For chat work, completion is determined by the return value (non-null = complete; null = yield, rare).

**Yield semantics.** A task cycle that exits without invoking a completion op yields — the run loop exits the active wake, the agent returns to SLEEPING, and the task stays in the `tasks` Index for next time. Yield is the natural way to express:

- **Long-running task in progress** — agent kicked off a delegated op via async invoke (§3.4.2), recorded it in `t`, and is waiting for the callback
- **Multi-step planning** — agent did internal work (lattice writes to `n` / `c` / `t`) but isn't ready to complete
- **Agent had nothing useful to say** — transient false wake; loop exits cleanly

The picked task resumes on the next wake. The four wake paths (explicit trigger, work arrival, scheduled, async completion) are defined in [SCHEDULER.md §4](./SCHEDULER.md).

**Per-task exponential backoff.** A task that yields without progress is not capped or auto-failed — it is rescheduled by the framework with growing delay between cycles. Any progress (completion, fail, message arrival, delegated op callback, explicit trigger) resets the backoff. Yielded tasks are dormant, not burning resources. See [SCHEDULER.md](./SCHEDULER.md) for wake semantics, field names, and backoff policy.

**Optional task timeout.** Tasks may carry an optional `timeout` field (caller-settable on `agent_request`); default very long (e.g. weeks) or absent. The framework checks `timeout` at pick time — if exceeded, the task is auto-failed with `timeout` error before invoking the agent. Callers that want a tight deadline set their own; the framework imposes none by default.

**Technical errors.** If the transition adapter throws (e.g., LLM API failure, lattice error), the framework catches it. If a task or chat was picked, the Job is failed with a *technical* error (distinct from `agent:fail_task`'s semantic failure). The task entry is removed / chat slot cleared in both cases; the difference is recorded in the Job's failure reason.

**Layering: framework vs tool loop.** The framework only sees venue ops being invoked (`agent:complete_task`, `agent:fail_task`) and the adapter's return value. There is no LLM-specific concept at the framework layer. Inside LLMAgentAdapter, the tool loop wraps these as named tools the LLM can call:

- `complete_task` tool → invokes `agent:complete_task`, exits loop with the result as the return value
- `fail_task` tool → invokes `agent:fail_task`, exits loop
- Plain text response from the LLM (no tool calls) → exits loop with the text as the return value

For chat, plain text response naturally completes the chat Job (return value flows through). For tasks, plain text response does *not* complete the task — the LLM must call `complete_task` explicitly. This matches the explicit-completion model for tool-using LLMs.

There is no separate `yield` tool. Yield is what happens when the loop exits for a task without `complete_task` having been called — purely emergent from tool-loop behaviour, not a framework primitive.

Non-LLM adapters call the venue ops directly with no tool-loop layer.

### 6.2 Adapter selection

No separate "session-aware" ops needed. All transition adapters receive the unified input shape. The session-awareness dimension collapses into the adapter-unification matrix in §6.4 (`framed`, `persistent`); there is no `chatagent:reply` vs `llmagent:chat` split.

### 6.4 Adapter unification (design amendment)

`llmagent:chat` is structurally a **depth-1 goaltree with persistent transcript**: a single frame, no subgoals, whose history carries across transitions. `goaltree:chat` is the general case (N frames, stateless per transition). The overlap is large enough that the two adapters should collapse into one.

**Proposed shape:** a single transition adapter (working name `agent:chat`) parameterised by:

| Flag | Effect |
|------|--------|
| `framed: false` (default) | Flat single-frame mode. `subgoal` disallowed. Equivalent to today's `llmagent:chat`. |
| `framed: true` | Multi-frame mode. `subgoal` allowed, child frames summarise on return. Equivalent to today's `goaltree:chat`. |
| `persistent: true` (default for flat) | Persist transcript / frame stack across transitions. |
| `persistent: false` (default for framed) | Fresh root on every transition. |

Note: `sessionAware` is no longer a flag — under §5.4 every transition is session-scoped by default. The flag matrix collapses to `{framed, persistent}`.

**What this buys:**

- **Harness tools become uniform.** `more_tools`, `context_load`, `context_unload`, typed `complete`/`fail` are available in both modes. This directly fixes the gap where a flat-mode agent (today's `llmagent`) has no way to self-expand its tools mid-run.
- **Sessions drop in cleanly.** A flat agent inside a session is a chatbot. A framed agent inside a session is a resumable goal tree. No new transition ops needed — the session is supplied by the framework per §5.4/§6.1, not opted into per adapter.
- **One migration story.** Existing `llmagent:chat` agents map to `{framed: false, persistent: true}`; existing `goaltree:chat` agents map to `{framed: true, persistent: false}`. Transition-op names remain as aliases.
- **Smaller conceptual surface.** Agent authors pick flags rather than learning which of N adapters matches their pattern.

**What this costs:**

- A one-time refactor to merge `LLMAgentAdapter` and `GoalTreeAdapter`. Most of the code in each already does the same work (tool-call loop, context assembly, harness resolution) with subtle divergence.
- Harness-tool semantics that today depend on the frame model (`subgoal`, `compact`) need explicit guards when `framed: false`.

If pursued, this collapses the decision in §10 OQ #12.

---

## 7. Lifecycle

```
Created → Active → Idle → Archived
                 ↓
              Suspended (on error)
```

- **Created** — new session, no turns yet
- **Active** — recent activity (configurable window, default 24h)
- **Idle** — no recent activity but not archived; can resume
- **Archived** — explicitly closed; read-only; counts toward audit but not active list
- **Suspended** — error state; needs manual resume (mirrors agent suspension)

Sessions are **never deleted** by the framework (audit). Archived sessions can be pruned by external tooling if storage demands it.

### 7.1 Session vs agent lifecycle

- Agent SLEEPING ↔ no active sessions running (but sessions persist)
- Agent RUNNING ↔ one or more sessions executing transitions
- Agent SUSPENDED ↔ all sessions paused
- Agent TERMINATED ↔ sessions still readable (audit) but no new transitions

---

## 8. Timeline as State Snapshots

### 8.1 Mental model

Timeline entries are **agent state snapshots at transition boundaries**, not events or conversation logs. Each entry captures "what the agent looked like at moment T" — not "what happened." This is already true today; the framing matters when sessions enter the picture.

### 8.2 With sessions

A snapshot is the **full agent state at the transition boundary**, including every active session in full:

```json
{
  "ts": 1776079053991,
  "transitionId": "...",
  "config": { ... full agent config ... },
  "state": { ... agent-level state ... },
  "sessions": {
    "conv-mike":  { "meta": {...}, "state": {...}, "history": [...] },
    "support-01HXYZ": { "meta": {...}, "state": {...}, "history": [...] }
  },
  "status": "RUNNING"
}
```

Lattice content addressing means unchanged sub-structures are deduplicated automatically — the inactive session's history isn't recopied, the unchanged config map points to the same cell as before. Each snapshot is "logically the whole agent at that moment" but physically only writes what changed.

### 8.3 What this gives

- **Complete reconstruction** — any past state of the agent is fully retrievable from one snapshot, no walking required
- **Multi-session captured naturally** — every active session is in the snapshot in full
- **Audit semantics straightforward** — "what was the agent at transition T?" is just reading the snapshot
- **Lattice dedup makes it free** — only changed cells take new storage; the snapshot's *logical* completeness is the correct mental model, the *physical* delta is the lattice's job

### 8.4 Cross-session view

To see all transitions across all sessions, the timeline is the right place — each snapshot already lists all active sessions. To see detail within one session, walk that session's history at `g/<agent>/sessions/<sid>/history`.

### 8.5 Could go further (out of scope for v1)

The lattice already tracks state changes via cursor history. Timeline could become a *view* over that history with transition-boundary annotations rather than a separately-appended structure. That's a deeper architectural change deferred for now.

---

## 9. Cross-Session Concerns

### 9.1 Lookups (rare but real)

- "Did this user mention X in any prior session?" — query across sessions filtered by counterparty
- "Across all my support tickets, what are common issues?" — analytics over sessions
- "Resume the conversation titled Alpha" — find session by title

Done via standard lattice ops (`covia_list`, filter, read). No special API.

### 9.2 Cross-session memory (future epic)

Sessions hold conversation history. **Memory** — facts, decisions, learnings — should be a separate layer:

- Episodic (what happened)
- Semantic (what's true)
- Procedural (how to do things)

Memory can be summarised from old sessions, accumulated across sessions, and queried independently. Out of scope for v1; design separately.

### 9.3 Multi-party sessions (open question)

A single session with multiple counterparties (e.g. a group chat). Not supported in v1. If needed, `meta.counterparty` becomes a vector and routing rules need defining.

---

## 10. Open Questions

1. **Session creation authority** — can callers, the framework, and the agent all create sessions? Who owns the id namespace?
2. ~~**Default session rule**~~ — **Resolved (§5.4):** every interaction is in a session; missing `sessionId` → venue mints + returns, caller echoes to continue. Matches A2A `contextId` and MCP `Mcp-Session-Id`.
3. **Caps scope** — does each session have its own caps scope, or inherit from agent? Probably inherit by default, with optional per-session attenuation.
4. **Sub-sessions** — can a session spawn sub-sessions (e.g. a support session branching to a specialist)? Or is the relationship flat with `meta.parent` references?
5. **Multi-party** — one session, multiple counterparties? See §9.3.
6. **GoalTree integration** — does each session get its own frame stack persisted? This unlocks resumable goal trees but adds storage. Ties directly to §6.4: under adapter unification, a session simply owns whatever `{framed, persistent}` state its agent's config declares — the question becomes "is `persistent: true` the default for framed sessions?" rather than a separate integration project.
7. **Storage limits** — per-session size cap? Per-agent total cap? Auto-archive on overflow?
8. **Session merge/fork** — should sessions support merge (combine two contexts) or fork (branch from a point)? Probably not v1.
9. **Visibility** — can other users/agents read sessions (with caps)? Default: only the agent and its owner.
10. **Scheduling** — can a session declare "wake me at time T" without an external request? See #65 (scheduled wake).
11. **Migration** — existing agents have flat timelines; do they get a synthetic "legacy" session covering all past turns, or does the timeline stay as audit-only with new sessions starting fresh from the Phase 1 cutover?
12. ~~**Session contract for transitions**~~ — **Resolved (§6.1):** one unified contract, no sessionless path. Session-awareness is not opt-in per adapter. Under §6.4 adapter unification, the remaining knobs are `{framed, persistent}`.

---

## 11. Migration Path

### 11.1 Compatibility

The API surface is additive at the envelope level (new `sessionId` field in requests and responses) but semantically the transition contract changes — there is no longer a sessionless path (§6.1). Existing callers that never supply `sessionId` continue to work unchanged: the venue mints one per invocation and returns it. Callers that ignore the returned id get one-shot behaviour (identical to today's pipeline agents). Callers that echo the id get continuity for free.

### 11.2 Phasing

| Phase | Scope | Outcome |
|-------|-------|---------|
| **0 (now)** | Design doc + open questions | This file |
| **1** | Session storage + session envelope + single transition contract | Every invocation runs in a session; `sessionId` in request/response envelope; per-session history at `g/<agent>/sessions/<sid>/`; chatbot demo |
| **2.1–2.6** | Session lattice slot + sessionId on intake + ensureSession + one-session-per-cycle demux | ✓ Done (Stage 2.6 complete; sessions auto-minted, session.pending is sole intake, one session active per transition cycle) |
| **2.7** | Single response value contract; explicit task completion via venue ops | ✓ Done. Transition adapter returns `ACell response` (or null) — appended to `c/history` if non-null. For chat picked: return value completes chat Job. For task picked: completion is explicit via `agent:complete_task` / `agent:fail_task` ops invoked during transition (framework reads RequestContext for `agentId`/`taskId`/`jobId`). State writes via `covia_write` (RequestContext-scoped to `n`/`c`/`t`). No `agent:yield` op — yield is the natural state when no completion op was invoked. No `agent:complete_chat` — chat completes via return. LLM tool loop wraps `complete_task` / `fail_task` as tools; plain-text response = return value (auto-completes chat, yields for task). Yields → exponential falloff on per-task `nextWake`; optional caller-settable `timeout`, default very long. |
| **2.8** | Add `agent_chat` op + session-scoped chat slot | ✓ Done. Caller awaits next `response` on the session; mints session on first contact, continues existing session normally. A2A `message/send` analogue. |
| **3** | Per-session history population | ✓ Done (S3a–d, S3f). Session.history populated per transition. Agent inbox removed (S3d) — session.pending is sole intake. No history cap (S3e) — summarisation deferred to ContextBuilder. |
| **4** | Lifecycle automation | Auto-idle, auto-archive, suspended sessions |
| **5** | Adapter unification (§6.4) | Merge `llmagent:chat` and `goaltree:chat` into one flag-driven adapter; uniform harness tools (`more_tools` etc.) across both modes; persistable frame stack per session |
| **6** | Memory layer | Separate epic; episodic/semantic/procedural memory across sessions |
| **7** | Multi-party / scheduling | Advanced features |

Phase 1 is a breaking change at the framework level (one contract, not two) but backward compatible at the wire level (envelope gains a field that old callers ignore).

### 11.3 Existing agents

At the Phase 1 cutover, existing agents begin running each new invocation inside a freshly minted session. Their historical flat timeline is preserved read-only as audit data — no retroactive session is synthesised for pre-cutover turns (see §10 OQ #11). Agent authors don't change configs; the framework handles the envelope transparently.

---

## 12. Why This is Right (and Why Not)

### 12.1 Why it's right

- **General primitive** — covers chatbots, support, investigations, pipelines without baking any one pattern into the framework
- **Lattice-native** — sessions are just data at `g/<agent>/sessions/<sid>/`; standard tools work for discovery and inspection
- **Protocol-aligned** — session semantics match A2A `contextId` and MCP `Mcp-Session-Id` (§5.4); federated interop is trivial
- **One contract** — every transition runs in a session; no "sessionless vs session-aware" split to reason about (§6.1)
- **Wire-compatible** — old callers that ignore the new `sessionId` field keep working; they just get one-shot sessions
- **Solves real symptoms** — #67/#68/#69 fall out naturally; chatbots become possible
- **Phased** — chatbot in v1, advanced features later

### 12.2 Why it might be wrong

- **New primitive to learn** — agent authors now need to think about sessions in addition to state, config, tasks
- **Per-invocation session overhead** — pure one-shot callers (Alice-style extraction) pay the cost of a session record they never reuse; acceptable price for uniform audit but not free
- **Storage layout change** — moving heavy data to per-session history is a non-trivial migration if applied retroactively
- **Open questions still load-bearing** — creation authority (§10 OQ #1), caps scope (#3), and migration of flat timelines (#11) remain unresolved

---

## 13. Related

- [AGENT_LOOP.md](./AGENT_LOOP.md) — current agent transition model that this generalises
- [GOAL_TREE.md](./GOAL_TREE.md) — frame stack abstraction that could plug in as session-scoped
- [LATTICE_CONTEXT.md](./LATTICE_CONTEXT.md) — context loading that sessions can leverage per-context
- [PROJECTS.md](./PROJECTS.md) — long-lived work state that sessions reference but don't own
- Issues: #67, #68, #69, #57, #71 — symptoms this addresses
- Future: AGENT_MEMORY.md (not yet written) — separate cross-session memory layer
