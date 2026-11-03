# Agent Design — Data Structure and Transitions

Design for Covia agent state and the transitions that mutate it.

The agent record may select an external implementation through its optional
`agent` facet (§2.2.1). The run-loop and transition contracts elsewhere in this
document describe the native Covia runtime, selected when that facet is absent.

**Status:** Current behaviour. See **§8 Architectural Direction** for the in-flight redesign of the framework↔harness contract; §1–§7 describe how the code works today.

See GRID_LATTICE_DESIGN.md for: per-user namespace layout (§4.3), agent addressing
(`/g/<agent-id>`, §4.3.4), secret store (`/s/`, §4.3.6), and implementation phasing (§12).

---

## 1. Principles

1. **Single atomic record.** An agent's entire state is one map. Every operation
   (create, message, run, config update) atomically replaces the whole map. The map
   is the local compare-and-swap unit; there is no per-field merge.

2. **Whole-venue replication.** The signed venue `:value` is the replication unit.
   Its outer `:timestamp` selects the newer complete venue snapshot through a
   whole-value LWW lattice. An agent's `ts` is ratcheted last-modified metadata; it
   does not independently choose a merge winner.

3. **Single venue writes.** An agent lives on one venue. All writes — message delivery,
   agent runs, config updates — are serialised on that venue via atomic
   compare-and-swap on the agent record. Cross-venue sync selects the newer complete
   signed venue snapshot.

4. **Three levels.** The agent update (level 1) manages framework bookkeeping:
   status, timeline, sessions. The agent transition (level 2) manages domain logic:
   conversation history, tool call loops, state. The LLM call (level 3) is a single
   stateless invocation. Each level is a grid operation, pluggable independently.
   The framework never inspects user state; lower levels never manage framework fields.

5. **A failure lands on what failed.** A failed transition fails the work it took
   — the picked task, the chats it was answering — and the agent carries on with
   the rest. Only an agent that cannot continue is suspended, and it keeps its
   queue (§3.4).

### 1.1 Agent references

Every native agent operation that accepts `agentId` uses the ordinary grid path
resolver. A bare value such as `Manager` is shorthand for the caller-owned
`g/Manager`; `g/Manager` and `/g/Manager` are equivalent. A DID-qualified path
such as `did:key:zAlice/g/Manager` explicitly selects that owner's agent.

Qualified references do not introduce a separate agent-address grammar or lookup
path: they resolve exactly like other physical user resources. The canonical
resource is `<ownerDID>/g/<agentId>`, capability checks apply to that resource at
the point of action, and the runtime key remains `(ownerDID, agentId)`. Accessing
another user's agent therefore requires a matching capability; metadata cannot
redirect or authorize the access. Results keep the local `agentId` for compatibility
and include the canonical `address`.

---

## 2. Data Structure

An agent lives at `:user-data → <owner-DID> → "g" → <agent-id>`.

### 2.1 Lattice Shape

```
signed venue :value
  → Timestamped venue record (:timestamp, whole-value LWW)
  → typed venue interior
      :user-data → MapLattice (DID → stamped user record)
        "g" → MapLattice (agent-id → stamped agent record)
          <agent-id> → stamped cursor (ts) → JSONLattice
```

The inner agent component supplies typed navigation and maintains `ts` when a
deep cursor write enters the record (including `n/` and `c/`). It adds no stored
envelope: the physical value remains the same plain map. Merge stops at the outer
venue LWW and therefore never combines individual agents or their fields.

### 2.2 The Agent Record

The agent's value is a plain map. Every write replaces the entire map atomically.

| Field | Type | Description |
|-------|------|-------------|
| `ts` | long | Ratcheted timestamp of the last write to the agent record. Metadata, not a merge discriminator. |
| `status` | string | `"SLEEPING"` \| `"RUNNING"` \| `"SUSPENDED"` \| `"TERMINATED"`. `RUNNING` is a persisted execution marker, validated against the live executor by the hosting venue. |
| `agent` | map? | Optional external implementation selector; absent for the native Covia runtime. See §2.2.1. |
| `config` | map | Framework-level configuration. Includes `operation` (default transition op). |
| `state` | any | User-defined state. Opaque to the framework. Passed to and returned from the transition function. Transition-function configuration (LLM operation, model, system prompt, tools, caps) lives in `config`, not here (#144). Set at creation via optional initial state. Conversation content lives on the session record (`session.frames[0].conversation`), not here. |
| `tasks` | index | `Index<Blob, ACell>` of inbound request Job IDs. Persistent until resolved. Ordered by Job ID. |
| `pending` | index | `Index<Blob, ACell>` of outbound Job IDs the agent is waiting on. Ordered by Job ID. |
| `sessions` | index | `Index<Blob, ACell>` of sessions. Each session contains `frames` (the goal-tree frame stack — `frames[0].conversation` is the canonical transcript), pending messages, metadata, and per-session state (`c/`). See AGENT_SESSIONS.md and GOAL_TREE.md. |
| `timeline` | vector | Append-only log of cycle records (§2.4), one per cycle whose transition ran — the complete record of each cycle's exchange with the model. |
| `caps` | map | Capability attenuations — UCAN scoping for agent tool calls. See [UCAN.md §5.4](./UCAN.md) for Model A (user-scoped) vs Model B (independent DID). |
| `error` | string? | Last error message, or null. Set when the transition function fails. |
| `suspension` | map? | Deliberate stop: `{by: <actual caller DID>, reason?: <up to 512 characters>, ts: <epoch millis>}`. Set by `agent:suspend`, separate from faults; cleared on resume. |

All fields are framework-managed except `state`, which is owned by the transition
function. CAD3 structural sharing means the timeline (which only appends) shares
all existing entries with the previous version.

### 2.2.1 Agent definitions and external implementations

The optional `agent` facet selects an external implementation. It follows the
same asset convention as `operation`: the framework understands the discriminator
and the selected adapter understands its remaining fields. It creates no
per-agent operation definitions and does not require an `operation` facet.

```json
{
  "agent": { "adapter": "claudecode", "project": "my-project", "model": "sonnet" },
  "config": { "accepts": [] },
  "state": {},
  "sessions": "<framework-managed session index>"
}
```

This is a live record at `g/<id>`. Its definition can also be carried by an
ordinary immutable asset and passed to `agent:create` as `definition`. The
adapter facet is copied, not the source's runtime sessions or tasks. Hashing a
whole live record produces a snapshot whose hash changes with its state; the
stable reference remains `g/<id>`.

Compatibility is deliberate:

- **No `agent` key in the live record:** existing native Covia runtime, including
  its config, task queue, sessions and run loop. No migration is required.
- **`agent` present:** it must be an object with `adapter` naming an available
  `ARemoteAgentAdapter`. Null, malformed, unknown and disabled adapters fail;
  they never fall back to native execution.
- Existing native template assets with `agent.config` remain construction-time
  config layers. Their facet is consumed by the existing template resolver and
  is not copied into the live record as an external implementation selector.

Create and call a Claude agent through the ordinary operations:

```jsonc
// v/ops/agent/create
{"agentId":"coder","agent":{"adapter":"claudecode","project":"my-project"}}
// v/ops/agent/chat
{"agentId":"coder","message":"Explain this project's entry points"}
// Continue with the Covia sessionId returned by chat
{"agentId":"coder","sessionId":"<Covia session hex>","message":"Explain the tests too"}
```

Supported definitions:

| `agent.adapter` | Adapter-specific fields |
|---|---|
| `a2a` | `endpoint`, `card`, optional `auth` secret binding; `a2a:import-agent` supplies the new facet while retaining its legacy profile |
| `claudecode` | `project` and ordinary Claude run options; provider sessions are framework-managed |
| `remoteagent` | Destination `agentId`, optional `venue` and `authenticateAs`; omitted venue uses the local venue |

`agent:chat` and `agent:request` preserve their existing result envelopes.
The common dispatcher checks normal agent admission and executes as the resident
agent with its `config.caps`. Requester credentials do not become the agent's
authority. Provider-specific project, secret and destination checks still apply.
See [AGENT_SESSIONS.md §2.2](./AGENT_SESSIONS.md#22-external-agent-sessions)
for conversation mapping, turn execution, messages and recovery.

Suspend and delete cancel active backend jobs; resume permits new calls.
Shared `config` and `state` updates remain available, but replacing `agent`
metadata requires creating a new agent, keeping established sessions bound to
their original implementation. Native-only operations (trigger, query, context,
step and fork), and external `loads`, `responseSchema`, `strict` and `outputPath`
options currently fail explicitly. Native agents retain their existing behaviour.
External session mutation restrictions are listed in the session contract above.

Adapter authors implement the shared dispatch hooks described in
[ADAPTERS.md](./ADAPTERS.md#agent-dispatch-hooks).

### 2.3 Tasks vs Messages

An agent has two inbound channels with different semantics:

**Tasks** (`tasks`) are formal, persistent requests for the agent to complete.
Each task is a Job (managed by the venue's JobManager). The agent's `tasks` field
holds Job IDs. The agent decides when and how to fulfil each task. Tasks persist
until the agent completes or rejects them — they survive restarts and can span
days or weeks. Tasks can come from humans (via MCP), other agents, or the system.

**Messages** are delivered to `session.pending` — a per-session queue. Each
message is an envelope with content and caller metadata. The next cycle on
that session takes them as it starts: in one transaction they leave the queue
and become user turns in `session.frames[0].conversation` (§4.4). See
AGENT_SESSIONS.md for the session model.

The **pending** outbound-Job index holds job references for outstanding waits.
HITL registers its request there until settlement, including after recovery;
the Job owns the current status. HITL still delivers its answer as a new
input, because its immediate receipt already completes the original tool
exchange: to the task whose cycle asked, else to the session inbox (§6.3). A
tool call still running past its time limit is tracked there too, with the
session and task its outcome goes to (§3.4.2); a cycle is not shown such an
entry as a job result. No generic async-invoke tool is exposed yet.

### 2.4 Timeline Entry

Each entry in the `timeline` vector is the complete record of one cycle's
exchange with the model: what the cycle was given, what the model was told
beyond the conversation, every inference it made, and what came of it. The
session keeps the conversation the next cycle continues from; the entry keeps
what happened. An entry is written for every cycle whose transition ran,
success or failure — a failure's entry carries the error and the inferences
that ran before it.

| Field | Type | Description |
|-------|------|-------------|
| `start`, `end` | long | When the cycle began and ended. |
| `op` | string | The transition operation (llmagent / goaltree). |
| `sessionId` | string? | The session the cycle ran on. |
| `tasks`, `messages`, `pending` | vector? | What the cycle was given: resolved tasks, inbox envelopes, job results that arrived. |
| `context` | vector | The root frame's standing context as rendered for its first inference — the head and live surface (system prompt, skills index, loads): the prompt minus the conversation. |
| `tools` | vector | Names of the tools offered to the first inference. |
| `inferences` | vector | One record per model call, in order — see below. |
| `result` | any | The cycle's response, or its error. |
| `taskResults` | map? | Task completions, `{<jobId>: {status, output}}`. |

An inference record:

| Field | Description |
|-------|-------------|
| `ts`, `ms` | Call start (wall clock) and provider round-trip. |
| `op`, `model?` | The level-3 operation and the model id it was asked for. |
| `sent?` | Messages first sent in this inference. The first inference's are the tail (date notice, task message, budget warning); later ones hold only what is new — a changed notice, a load added mid-cycle. |
| `tools?` | The tools offered, only when they differ from the previous inference. |
| `reply` \| `error` | The model's reply verbatim (`{content?, toolCalls?, tokens?}`), or why the call failed. |
| `calls?` | The tool batch the reply requested: `[{id, name, ms, result, isError?, frame?}]`. Arguments are in `reply.toolCalls`. |

A `subgoal` call's `frame` is the child frame's record — `{context, tools,
inferences}` — in the same shape, to any depth. The child is popped from the
session when it completes, so its exchange lives here: a subgoal pops out of
the parent's current context but loses none of its history.

**One rule.** A message is recorded once, in the inference that first sends it
— except turns of the root conversation, which the session records. The head
is recorded once per cycle; a child frame's head is new (it carries the child
notice) and its goal turn and ancestor rendering are its first inference's
`sent`; a reply is recorded under the inference that produced it, never
re-listed as sent by the next. Cycle token totals are the sum over every
`reply.tokens` in the entry (child frames included) and a failed call is
`calls[j].isError` with its `result` as the message — neither is stored
separately, because both are derivable.

Replies and tool results also appear as session turns. The two records have
different lifecycles — the session is mutable conversational state
(compaction, `context_unload`, popped child frames, `deleteSession`), the
timeline is append-only — and an audit record cannot depend on state that
later mutates.

### 2.5 Status and execution

The lattice `status` field includes a persisted `RUNNING` marker for lattice and
remote observability. Current execution ownership still comes from the venue's
live executor registry. A stored `RUNNING` without a matching local executor is
stale; startup clears it before scheduling durable work.

```
                    agent:create
                         │
                         ▼
                   ┌──────────┐
        ┌─────────▶│ SLEEPING │◀────────┐
        │          └────┬─────┘         │
        │               │ wakeAgent     │ tryResume
        │               │ (work to do)  │ (caller fixed cause)
        │               ▼               │
        │          ┌──────────┐         │
        │          │  RUNNING │         │
        │          └────┬─────┘         │
        │               │               │
        │      ┌────────┴────────┐      │
        │      │ attempt done    │      │
        │ ok   │                 │ error│
        └──────┘                 └─────▶┌───────────┐
                                        │ SUSPENDED │
                                        └───────────┘
                                  agent:delete (any state)
                                        │
                                        ▼
                                  ┌────────────┐
                                  │ TERMINATED │
                                  └────────────┘
```

| Status | Meaning | Run loop | Mutations allowed |
|--------|---------|----------|-------------------|
| `SLEEPING` | Durable runnable/idle state. | `wakeAgent` may start a live attempt. | All. |
| `RUNNING` | Persisted dirty marker: an attempt was launched. The hosting venue validates it against the live launcher slot. | Running when the matching slot is live; otherwise stale until startup cleanup. | Task/session appends are allowed. `agent:update` applies to future transitions; a started transition keeps its fire-time config. |
| `SUSPENDED` | Deliberately stopped (`suspension`) or last run failed (`error`). Dormant — does not auto-retry. State, tasks, pending messages, sessions and history are preserved (§3.4). | Not running. Resume via `tryResume` to keep the record, or delete it with `remove:true` before creating a replacement. | All. |
| `TERMINATED` | Logically deleted. Slot still occupies the namespace key (so the ID is reserved) but the agent is dead. | Cannot run. `agent:request` / `agent:message` / `agent:trigger` all fail. | None. Remove the record explicitly with `agent:delete remove:true` before reusing the ID. |

**Durable state transitions** are documented in §4: `create` (→SLEEPING), a
successful launcher install (SLEEPING→RUNNING), clean completion
(RUNNING→SLEEPING), run-loop error (RUNNING→SUSPENDED), `agent:resume`
(SUSPENDED→SLEEPING), and `agent:delete` (any→TERMINATED or absent). Startup
clears a crash-stale RUNNING marker after confirming there is no live executor.

Agent summaries (`agent:info`, `agent:list`, and their job-free REST reads)
include `awaiting: {input, auth}` (#557). These count the agent's pending jobs in
`INPUT_REQUIRED` or `AUTH_REQUIRED`, independently of its lifecycle
status. They do not assert that the viewer is the intended responder. The counts
are derived on each read: each pending ID gets one in-memory Job lookup. No
derived counts are persisted or separately maintained. Cost is O(pending jobs)
per agent, independent of other venue jobs and completed history, with no payload
traversal. HITL removes the pending reference in the transaction that delivers
its response. Existing job notifications can trigger a summary refresh; this adds no
new agent status or event type.

### 2.6 Live events

The run loop is observable as it runs (#394). Every run-loop transition emits
one event on the venue's `AgentEvents` bus (`engine.agentEvents()`), and the
same ordered stream is served over REST at `GET /api/v1/agents/{id}/sse`.
Everything else — `agent:info`, `agent:context`, `agent:step`, the timeline —
is post-hoc; this is the one live surface, and it carries nothing the
timeline entry does not keep, except the progress a running operation
reports, which is only ever the current state of work in flight.

| Event | When | Payload |
|-------|------|---------|
| `status` | The persisted status changed: RUNNING, SLEEPING, SUSPENDED, TERMINATED (§2.5) | `status`, `error?` |
| `run:start` | A run loop launched (§4.6) | `run` |
| `cycle:start` | A cycle picked its work and is invoking the transition | `op`, `tasks`, `messages`, `pending` (counts), `jobs?` (chat job ids presented) |
| `inference:start` | A model call is starting on an assembled prompt | `op`, `model?`, `messages`, `tools` (counts), `bytes`, `budget` |
| `inference:end` | The call returned, or failed | `ms`, `content?`, `toolCalls?: [{id, name}]`, `tokens?`, `model?` — or `ms`, `error` |
| `tool:start` | A tool call is being dispatched | `id`, `name`, `activityLabel` (operation metadata → asset name → raw tool name), `detail: {input}` |
| `tool:progress` | The operation the call invoked published `progress` on its Job — each change once, before the call's `tool:result` | `id`, `name`, `message?` (the operation's own line for a person), `detail: {progress}` (the whole `{message, data?}` record, JOBS.md) |
| `tool:result` | The call finished | `id`, `name`, `ms`, `isError?`, `detail: {result}` |
| `cycle:end` | The merge committed — timeline entry and session turns are persisted | `ms`, `response?` \| `error?`, `tokens?`, `timeline` (index of the entry), `detail: {turns?}`; a cycle stopped by suspend/delete carries `error` and `cancelled` |
| `run:end` | The run loop exited | `run`, `status` (the rest state reached), `cycles` |

Every event carries `seq` (strictly increasing per agent for the life of the
venue process — a restart resets it), `ts`, `type`, `agentId` and `address`.
Events inside a run carry `run`; events inside a cycle carry `cycle`, the
picked `sessionId` and, for a task cycle, the task's `jobId`. Inference and
tool events in a goal-tree child frame carry `depth`. A parallel tool wave
announces its calls from their own threads; emission for an agent is
serialised, so delivery order is `seq` order.

**Correlation is by handle, not by lookup.** The run loop creates a cycle
handle, stamps it on the cycle's `RequestContext` (`getCycle()`) beside the
cancellation token, and the transition opens its `CycleRecord` on it (§2.4).
Activity is attributed to exactly the cycle that made it; a transition run
outside a run loop — a direct `llmagent:chat`, `agent:step` — has no handle
and emits nothing. Calls fenced after a terminal tool request never ran and
are not announced. An operation a call invokes is correlated the same way:
the call is its thread's running call while it dispatches, and the Job it
invokes is watched from creation, before its adapter starts, until the call
has its result — so none of the operation's progress is missed and none
follows the result.

**Authority and display.** The stream is owner-level, the same authority as
`GET /agents/{id}`: it carries what the completed timeline entry exposes,
live. Names, counts, timing and the assistant's explicitly emitted `content`
sit at the top level; tool inputs, results and appended turns sit under
`detail`, which `?detail=false` (REST) or `Event.withoutDetail()` (in-process)
strips for a consumer that renders a display-safe summary — Thinking…,
Using *tool*… — without the payloads. Hidden model reasoning is never
carried: `content` is the text the provider adapter already surfaces as the
assistant's.

**Consuming.** An embedding host subscribes with
`engine.agentEvents().subscribe(ownerDID, agentId, listener)` (or venue-wide,
`subscribe(listener)`) and closes the returned `Subscription`; listeners run
on the emitting thread and must not block or throw. The REST stream opens
with a `status` frame carrying the agent's current observable status and
`seq`, then relays events with the type as the SSE event name and `seq` as
the SSE id; it closes after the TERMINATED status frame. A client builds its
live activity view from `inference:*` and `tool:*` (rows keyed by call id)
and reconciles it against the persisted timeline entry and session turns on
`cycle:end`.

**Per session.** Either surface narrows to one conversation —
`subscribe(ownerDID, agentId, sessionId, listener)` in-process, `?sessionId=<hex>`
over REST — delivering that session's cycle, inference and tool events plus
the agent's `status` events (a suspension or termination is why a conversation
stopped); run boundaries and other sessions' cycles are omitted, and `seq`
keeps its gaps. The initial REST frame echoes the filter. Session lifecycle
events (created, deleted, message queued, turn appended) are not emitted.

**Guards.** The tap watches; a guard decides. An embedding host registers one
with `engine.toolGuards().add(guard)` to see each operation call an agent is
about to make — owner, agent, session, tool name, operation, input — and
return null to let it run or a reason to refuse it. A refused call never runs:
its tool result is `Error: <reason>`, as for a capability denial, and its
`tool:result` follows the `tool:start` already announced. A guard may block
while it decides (holding a risky call while someone is asked), but runs
within the call's own `toolCallTimeoutMs`; one that times out or throws
refuses. Harness tools are never guarded. With no guard registered a check
costs nothing.

---

## 3. Three Levels

The agent system separates concerns into three levels. Each level is a grid
operation, pluggable and replaceable independently.

```
Level 1: Agent Update          agent:trigger
  │  manages sessions, timeline, status
  │  invokes level 2 as a grid operation
  ▼
Level 2: Agent Transition      llmagent:chat | goaltree:chat
  │  manages conversation, tool call loop, state
  │  invokes level 3 as a grid operation
  ▼
Level 3: LLM Call              langchain:openai | langchain:anthropic | ...
     single request → response, structured I/O
```

### 3.1 Level 1 — Agent Update (Framework)

Reads inbound tasks, pending job completions, and session pending messages. Invokes the
transition function, records the result in the timeline, manages status, writes
the complete agent record. The same for every agent. Defined in §4.3.

The agent update owns the agent record. The transition function owns `state`
within it. The agent update never inspects `state`; the transition function never
manages framework fields.

The agent update is triggered by the venue scheduler (§4.5), not by external
callers. MCP users submit requests via `agent:request`; the scheduler handles
when the agent runs.

### 3.2 Level 2 — Agent Transition (State Machine)

Receives current state and new messages, returns updated state. This is the
pluggable part — different agents use different transition functions (LLM chat,
rule engine, workflow, custom code).

For LLM agents (`llmagent:chat`), level 2:

- Reads LLM configuration from `config` (the single config slot, #144)
- Reads the **session frame stack** from `input.session.frames` — turn
  envelopes `{role, content, ts, source}` live inside `frames[0].conversation`
  (plus any pushed child frames), converted to LLM `{role, content}` messages
  (`ts`/`source` stripped)
- Builds a **per-turn LLM context** with FRESH ephemeral additions every turn:
  - System message (identity prompt + lattice cheat sheet, rebuilt fresh)
  - Resolved context entries
  - Resolved loaded paths, each headed with its own unload key
  - A loads-budget warning, only when the budget is under pressure
  - Then appends the rendered frame conversation
  - Then appends pending job results and new messages
- Invokes level 3 (LLM call) as a grid operation
- Handles tool call responses: execute tools, feed results back, call level 3
  again (loop until the LLM returns a text response or a limit is reached)
- The shared frame store appends assistant and tool turns live, under the
  cycle's claim on the session (`session.inCycle`), which the run loop set when
  it took the cycle's input (§4.4). A later cycle's claim fences the writes of
  an earlier one that is still running.
- The session frame stack is the canonical conversation record. Its rendered
  prefix is persisted and reused exactly; dynamic loads and tool-state changes
  append events. Only explicit boundaries such as compaction rebuild it.

Level 2 does not import or depend on any LLM library. It invokes level 3 as a
grid operation and works with structured message maps. This makes it pluggable:
swap the level 3 operation to change provider, use a remote venue via federation,
or a test mock.

The level 3 operation to invoke is specified in `config.llmOperation`
(built-in fallback: `v/models/anthropic/claude-sonnet-5-5`; venues may override it with
`defaultLlmOperation`). The agent creator picks both the agent loop
strategy (level 2) and the LLM backend (level 3).

The goal-tree level 2 adapter is documented separately in
[GOAL_TREE.md](./GOAL_TREE.md). It uses the same frame store, interruption
repair, compact operation and shared harness registry as `llmagent:chat`, then
adds child-frame hierarchy and typed subgoal completion.

### 3.3 Level 3 — LLM Call (Single Step)

A single, stateless LLM invocation. Takes a list of messages (with tool
definitions if applicable), calls a specific LLM API, returns the response.

**Input:** `{messages: [...], tools?: [...], model?: string, url?: string}`

**Output:** An assistant message map:
```json
{"role": "assistant", "content": "Hello!"}
{"role": "assistant", "toolCalls": [{"id": "call_1", "name": "search", "arguments": {"query": "..."}}]}
```

**Message types in the `messages` array:**
- `{role: "system"|"user", content: "..."}`
- `{role: "assistant", content: "...", toolCalls?: [{id, name, arguments}]}`
- `{role: "tool", id: "...", name: "...", content: "..."}`

Level 3 is a standard grid operation (e.g. `langchain:openai`, `langchain:ollama`).
It knows about HTTP clients, API serialisation, authentication, and provider
quirks. It does not know about agents, conversation history, or tool execution.
Its Covia-facing message contract uses structured `arguments` values. Provider
JSON strings are parsed on ingress and generated again only at the provider
boundary; legacy JSON-string history remains replayable. If a model emits
malformed JSON text, Covia retains that exact text long enough to record and
return a useful tool error instead of silently replacing it.

### 3.4 Transition Function Contract

The contract between level 1 and level 2:

**Input:**

| Field | Type | Description |
|-------|------|-------------|
| `agent-id` | string | The agent's identifier. |
| `state` | any | Current `state` from the agent record. Null on first run. |
| `tasks` | vector | Inbound task data resolved from the `tasks` index (jobId, input, status). |
| `pending` | vector | Outbound job data resolved from the `pending` index (jobId, status, output, error when the job did not complete). |
| `newInput` | any? | The picked task's input this cycle shows: its original input, then each continuation once. |
| `jobId` | string? | With `newInput`: the task's request Job id. |
| `session` | map? | The picked session record: `{id, parties, meta, c, frames, pending, inCycle}`. `pending` lists the messages this cycle took; they and `newInput` are already user turns in `frames` (§4.4). `inCycle` is the cycle's claim on the session. See AGENT_SESSIONS.md §6.1. |

**Output:**

| Field | Type | Description |
|-------|------|-------------|
| `state` | any | Updated user-defined state. Written back to the agent record; absent leaves it unchanged. |
| `response` | any? | The reply on the session. It answers the session's chats the agent has taken (§4.4); absent, the cycle yields and they keep waiting. |
| `error` | any? | The cycle failed (see below). |
| `turns` | vector? | Assistant and tool turns to append to the session's conversation, for a transition that does not write its frames itself. |
| `wakeTime` | long? | When to wake the picked task or session again (§4.6). |
| `cycle`, `tokens` | map? | The cycle's exchange with the model and its token usage, recorded in the timeline entry (§2.4). |

The ordinary transition contract does not require an adapter to manage ts,
status, timeline, sessions or scheduling. The run loop writes the cycle's input
into the conversation before the transition runs, and appends the transition's
`turns` and reply after it. The built-in LLM adapters implement `FramesOwning`:
they write their turns into the session's frames live under the cycle's claim,
and the run loop appends none of its own. Status, timeline, task settlement and
scheduling remain framework-owned.

Pending outbound jobs are not declared in transition output. The field is a
reserved framework surface; the built-in harnesses currently have no generic
writer for it (§3.4.2).

**Transition failures.** A transition fails by throwing, or by returning an
`error` in its output. The cycle's timeline entry records the error either way.
What happens next depends on what failed:

- **The work.** An expected failure ([ERRORS.md](ERRORS.md)) belongs to the work
  the cycle took. The model gave up on the task (`fail_task`), reached the
  tool-call iteration limit, sent a conversation too big for the model or a
  request the provider refused, or an LLM call outlasted `llmTimeoutMs`. The
  cycle's picked task fails, and so do the chats it was answering. Work it never
  took stays queued, and the agent carries on with it. A task the agent already
  resolved keeps its own outcome.
- **The agent cannot continue.** A transition raises `CannotContinueException`
  when the next unit of work would fail the same way. Both LLM runtimes raise
  it at the start of a cycle when the agent's model setup does not resolve:
  its `systemPrompt` entry, its LLM operation, or the provider a model
  operation names ([AGENT_CONTEXT.md](AGENT_CONTEXT.md) §4). Every cycle
  resolves the setup again, and `agent:context` and `agent:step` fail with the
  same reason. During the cycle they raise it when a model call fails with
  `ConfigurationException` (a refused key, a missing model, an invalid
  setting) or `UnavailableException` (the provider cannot be reached, or keeps
  failing after the provider library's own retries), or returns a failure
  value, which a level-3 operation returns only for a setup problem. An
  unexpected failure, a venue fault logged at ERROR where it happened, counts
  the same. The agent is suspended with the reason as its `error` (#88: an
  agent whose key is missing stops after one cycle).

A suspended agent keeps its queue, whether a fault or a deliberate
`agent:suspend` stopped it. The task the stopped cycle took stays queued and
runs again after resume. A chat whose message a cycle had already taken into
its conversation fails with the reason, since it would never be presented again.
Every Job still waiting on the agent, a queued task or a chat caller, carries a
progress note (`AgentAdapter.SUSPENDED_WAITING`) saying the agent is suspended
and why, until `agent:resume` clears it. New requests and chats are refused
while the agent is suspended (§4.5).

#### 3.4.1 Task Completion

A transition resolves its task by calling `agent:complete-task` or
`agent:fail-task` during the cycle: an LLM agent through its `complete_task`
and `fail_task` tools, any other transition by invoking the operation itself.
The call is scoped to the cycle's task and refused once a continuation has
arrived that the cycle has not shown. It removes the task from the queue at
once; the request Job is settled after the merge (§4.4 step 7), so its caller
sees the cycle's writes first. The timeline entry records each outcome in
`taskResults`.

A resolved task keeps its outcome when the cycle then fails: if the agent told
a caller "done", the task was done. This matches how other side effects (tool
calls that send emails, invoke external APIs) behave.

#### 3.4.2 Deferred results

The transition input and context assembler understand an agent-level `pending`
Job index and render resolved entries as `get_job_results`, but Covia does not
currently expose a generic async-invoke harness tool.
Ordinary operation tools remain synchronous and bounded by `toolCallTimeoutMs`.

A call still running at that limit is not cancelled. It runs as a recorded
job: the call's own when the agent sets `recordToolCalls` (or the venue
default does), otherwise a mirror of the unrecorded call (JOBS.md § Mirrors).
The model's tool result is an error naming that job, saying the call is still
running and should not be repeated unless that is safe
(`AbstractLLMAdapter.TOOL_STILL_RUNNING`), so it can carry on.

In a session, the job is tracked in the agent's pending index with the
session, the task whose cycle made the call, and the call's id and tool name
as the model made it. When the job ends, one transaction removes the entry
and delivers the outcome
`{jobId, toolCallId, toolName, operation, status, result | error}` from the
venue, marked as a tool call's outcome, which wakes the agent. It goes to the
task as its next input, so it runs that task and no other in the session;
when the call came from no task, or the task has finished, it goes to the
session. The model sees it as a message from the venue, or as a
`tool_call_outcome` tool result when its profile's `lateToolResults` asks
(AGENT_CONTEXT.md §5.7). A task that yields meanwhile
waits for it (§4.6), and one that receives it during its own cycle cannot
complete in that cycle (§3.4.1), so it runs again. Shutdown leaves the entry, and the restart delivers the job's outcome
instead (§6.1), so it is delivered exactly once. If the session has gone by
then, the entry goes with nothing delivered, and a task left parked on it
with nothing else to wait on fails (`AgentAdapter.TASK_OUTCOME_UNDELIVERED`).
A call with no session is told only its job (`TOOL_STILL_RUNNING_UNTRACKED`);
nothing follows, and `grid_job_status` gives the outcome.

Delivery follows the job's own status, not its result future, which a waiter
can cancel before the job has finished. The job ends in one commit and the
delivery is the next; between them the entry still backs a waiting task, and
a restart in between delivers from the job's record.

HITL is the deliberate long-lived exception (§6.3). A sessioned agent receives
the durable request ID immediately and the job is tracked in its pending index;
the HITL record remembers that session and
answer, rejection or expiry is delivered through its durable inbox, which wakes
the normal run loop. This avoids pretending a human-timescale request is an
ordinary synchronous tool call while leaving the broader async-operation design
open rather than documenting an unimplemented mechanism.

### 3.5 Context Loading

Level 2 resolves **context entries** declared in the agent's configuration and
state, injecting them as system messages before the conversation history. This
pre-loads reference material (policy documents, data schemas, procedures) without
tool calls or history pollution.

Context entries can be:
- **Literal text** — inline instructions
- **Workspace references** — paths like `w/docs/ap-rules` resolved via the user's namespace
- **Asset references** — hashes, `/a/` paths, `/o/` names, DID URLs resolved via `engine.resolveAsset()`
- **Grid operation results** — the output of any grid operation, resolved at load time

Two roles: `config.context` (operator-pinned, loaded every turn) and the
agent-managed working set curated with `context_load` / `context_unload`. The
retired `state.context` layer is gone — dynamic context is the loads scope chain.

See [AGENT_CONTEXT.md](./AGENT_CONTEXT.md) for the full design: the output
contract, the banded section sequence, entry grammar, and the scope chain.

### 3.6 Tool Palette

Agents declare their tools in `config.tools`: operation references and
harness tool names. With none, a bare chatbot answers with text.
`defaultTools: true` adds a read-only pack (`v/ops/covia/inspect`, `read` and
`list`).

```json
"tools": ["v/ops/covia/read", "v/ops/covia/write", "subgoal", "compact"]
```

An operation reference becomes a tool definition from the operation's
metadata (name, description, parameters); a harness tool name becomes the
runtime's own definition.

#### Harness tools

A harness tool is run by the agent runtime itself rather than dispatched as
an operation. This section defines them for both runtimes; GOAL_TREE.md
describes goaltree's frame controls in use.

| Tool | Runtime | Purpose |
|------|---------|---------|
| `complete_task`, `fail_task` | both | Resolve or fail the agent's task (§3.4.1) |
| `context_load` | both | Pin workspace data, an asset or an operation's result in context across turns |
| `context_unload` | both | Remove loads by the keys `context_load`, `skill_load` or `more_tools` returned |
| `skill_load` | both | Load a skill: its instructions and its tools |
| `more_tools` | both | Add operations as tools, as a load |
| `invoke_tool` | both | Call a tool a load added after the initial tool list |
| `compact` | both | Archive the conversation into a summary the agent writes, freeing context space |
| `subgoal` | goaltree | Delegate a sub-task to a child frame |
| `complete` | goaltree | End the frame with a result |
| `fail` | goaltree | End the frame with a structured error |

A frame is offered the harness tools listed in `config.tools`, and:

| Tool | Also offered | Never offered |
|------|--------------|---------------|
| `complete_task`, `fail_task` | At the root frame, always; a call with no task outstanding is refused | In a child frame: the task is the root's |
| `skill_load` | When the agent declares skills or skillsets | |
| `context_unload` | When the agent declares skills or skillsets, or with `more_tools`, whose results are loads | |
| `invoke_tool` | With `skill_load` or `more_tools`, which add tools later | |
| `complete`, `fail` | At the root with typed outputs, with schema-typed parameters; in every child frame, untyped, since they are how a subgoal returns | |
| `subgoal` | | In a frame at the depth limit: a stack holds the root and at most nine nested subgoals |

**Only offered tools run.** A call to a harness tool the frame is not offered
runs nothing and is answered as an unbound name (below); it is no barrier in
its batch. The check is one lookup in the set of names the frame is sent.

Loads made by `context_load`, `skill_load` and `more_tools` last as long as
the conversation they were made in. A call with no stored session has a
conversation of its own, so its loads last for that call.

**Typed outputs:** When `config.outputs` is set, the root frame's `complete`
and `fail` take schema-enforced parameters — no need to list them in `tools`.
The LLM's tool call arguments must match the declared schema (enforced by
OpenAI `strictTools`). Text-only responses are rejected. Flattened: the entire
tool input IS the result — `complete({field: val})`, not
`complete({result: {field: val}})`.

**Compaction:** the agent decides when to `compact`. Both runtimes prompt it
only through the context-budget warning (AGENT_CONTEXT.md §3.4).

#### Operation tools

Any venue operation can be a tool. Common categories:

| Category | Examples |
|----------|---------|
| **Data** | `v/ops/covia/read`, `write`, `list`, `delete`, `append`, `slice`, `inspect`, `copy` |
| **Grid** | `v/ops/grid/run` (execute operations), `grid/invoke` (async) |
| **Agents** | `v/ops/agent/create`, `request`, `message`, `list`, `info`, `fork` |
| **Assets** | `v/ops/asset/store`, `get`, `list`, `pin`, `content` |
| **Schema** | `v/ops/schema/validate`, `infer` |

Provider descriptions are the operation asset descriptions unchanged. Catalog
paths and source provenance remain available through agent-context inspection;
they are not repeated in every provider-facing tool schema.

#### Capability enforcement

The operation a tool call runs checks the agent's `caps` itself; the runtime
has no name-keyed gate. A denied call returns an error listing the agent's
actual capabilities. The system prompt
includes a caps section so the LLM knows its boundaries upfront.

See [UCAN.md](./UCAN.md) for the capability model.

#### Batch execution

A reply may request several tool calls at once. The batch runs as ordered
**waves** (`ToolCycleEngine`): ordinary operation calls that sit next to each
other in the batch run **concurrently**, one virtual thread each, so a model's
parallel reads and writes overlap instead of queueing; a harness tool the
frame is offered is a **barrier** — it runs alone, on the loop
thread, after the wave before it has finished and before the wave after it
starts, because it mutates the harness context or ends the cycle and its
position in the batch is its meaning. Whatever order calls finish in, results
are appended in call order and every call id gets exactly one result; a
successful terminal call still fences every later call (they receive an
"Error: not executed" result and never run); each call keeps its own dispatch
timeout, so a wave takes as long as its slowest member; and the timeline's
`calls[].ms` is each call's own wall clock. A single-call batch runs on the
loop thread with no hop.

#### Tool failures and unbound names

Every failed call returns an `"Error: …"` result the model can act on
(`AbstractLLMAdapter.toolFailure`). An expected failure, a `CoviaException`
with a reason, is its own reason. Anything else is a runtime or venue bug: it
is logged at ERROR with its stack, and the result says it was an unexpected
venue error, not caused by the call's input, naming only its class
([ERRORS.md](ERRORS.md)). Timeouts and interruptions have their own results.

A name the palette binds to no route (`dispatchUnbound`) runs nothing unless
it resolves to an operation, which is dispatched as one. Otherwise the result
says why, and what would make it callable when that is certain:

| Name | Answer |
|---|---|
| A harness tool of this runtime the frame is offered, through `invoke_tool` | Call it directly |
| A harness tool of this runtime the frame is not offered | It is not among the tools offered here |
| Another runtime's harness tool | Not available under this runtime |
| Equal to a callable tool up to case and `-`/`_` | The callable name |
| Provided by an advertised skill, with `skill_load` offered | `skill_load` one of the skills that provide it |
| Anything else | No such tool, and not an operation reference; then that no tools are available, the tools by name when there are at most five, or to use the tool list |

A skill counts as providing a tool when one of its tool entries binds exactly
that name, named as the palette names it. The lookup is only a hint, so it
stays cheap: skills resolve without their bodies, and operation definitions
only from this venue (`Engine.resolveLocalAsset`), never fetched from another.
An operation reference is resolved once and dispatched as resolved.

#### Additional tools

Level 2 also passes through any **external MCP tools** configured for the agent
(via `config.tools` or MCP server bindings). These are treated like any other
tool in the tool call loop — the LLM sees a flat list of all available tools
regardless of source.

The agent creator controls the tool set:
- External MCP tools are added via agent configuration
- Capability-gated tools appear only when the agent has the required `caps`

### 3.7 Invocation

Each level invokes the next as a grid operation — same dispatch path as any other
operation in the venue. This means any level can be:
- A local adapter operation (e.g. `llmagent:chat`, `langchain:openai`)
- A remote venue operation via federation
- A composite operation via orchestration
- A test mock (e.g. `test:echo`)

The level 2 operation is specified in the agent's `config.operation` field
(default set at creation, overridable per-run). The level 3 operation is
specified by the agent creator in `config.llmOperation`.

### 3.8 Credential Access

Operations that need API keys or other secrets resolve them from two sources,
in order:

1. **Input parameter** (optional `apiKey` field — testing only, will appear in
   plaintext in job results).
2. **User's secret store** (`/s/`), using the secret name declared in the
   operation's metadata (`operation.secretKey`).

The agent's `config` holds no key material: a `config.apiKey` is a secret reference (`s/NAME`) resolved at the call. The operation metadata owns the
credential concern — the agent specifies which operation to use, and the
operation declares which secret it needs. This keeps agent configuration clean
and credentials in the encrypted secret store.

Credentials are primarily a level 3 concern — the LLM call operation needs
the API key, not the agent transition. In the current implementation level 2
resolves credentials (transitional); the target is for level 3 operations to
declare and resolve their own secrets.

Example operation metadata (level 3):

```json
{
  "operation": {
    "adapter": "langchain:openai",
    "secretKey": "OPENAI_API_KEY",
    ...
  }
}
```

---

## 4. Operations

Every operation atomically replaces the agent record with a new `ts`.

### 4.1 Create

**Trigger:** `agent:create`

Writes the initial agent record: status=SLEEPING, config from input, state from
input (or null), empty tasks/pending indices, empty sessions, empty timeline, no error.

The `config` map supports:
- `operation` — default transition operation (e.g. `"llmagent:chat"`)
- Other framework configuration as needed

Initial state seeds transition-function runtime data (loads and similar) that the
transition function reads and preserves across runs; provider, model, system
prompt, tools and capabilities are `config` (#144). This keeps `state`
free of framework fields, and allows an agent to switch transition
functions.

**Inputs:**

| Field | Required | Description |
|-------|----------|-------------|
| `agentId` | yes | Identifier for the agent slot in the caller's `g/` namespace |
| `config` | no | Framework-level config (typically just `{operation: "..."}`) |
| `state` | no | Initial state (typically `{config: {systemPrompt, caps, tools, ...}}`) |

**Outputs:** `{agentId, status}` plus optional advisory `warnings`. Success means
a new record was created, so no redundant `created`, `updated`, or `replaced`
flag is returned.

**Exclusive create.** The target slot must be empty. Any existing record,
including a TERMINATED one, fails with `Agent already exists`. This keeps the
lifecycle operations distinct and prevents create authority from deleting an
existing agent.

To preserve history, use `agent:update`. To replace an agent completely, call
`agent:delete` with `remove:true`, wait for it to complete, and then call
`agent:create`. Delete owns the cancellation and removal semantics for RUNNING,
SLEEPING, SUSPENDED, and TERMINATED records.

### 4.2 Request

**Trigger:** `agent:request`

Submits a persistent task to an agent. The request Job itself IS the task — its
Job ID is added to the agent's `tasks` index, and it is PENDING until a cycle
takes the task, then STARTED. No separate Job is created.

The agent decides when and how to fulfil the request. When the agent completes
or fails the task (§3.4.1), the framework settles the request Job with the
result. The caller can poll or SSE-subscribe to the Job for status — the same
Job they received from `invoke()`.

**Input:** `{agentId, input, ...}` — the `input` is stored in the Job.

**Output:** The Job stays open until the agent completes or fails it. The
caller tracks progress via the Job ID returned by `invoke()`.

**Side effect:** The agent is woken immediately via `wakeAgent` (§4.6). If the
agent has a `config.operation` and is SLEEPING, the run loop starts. If the
agent is already RUNNING, the new task is in the lattice and the running loop
picks it up on its next iteration.

**Fails if:** the agent does not exist, or status is `"TERMINATED"`.

This is the primary MCP-facing operation for interacting with agents.

### 4.3 Message

**Trigger:** `agent:message`

Reads current agent record, appends the message to the session's `pending` queue
via `appendSessionPending`, writes agent record.
Then wakes the agent (§4.6) — if the agent has a `config.operation`, this
triggers a run automatically.

Messages are ephemeral notifications — they do not create Jobs and are not
tracked for completion. Use `agent:request` for work items that need a response.

**Fails if:** the agent does not exist, or status is `"TERMINATED"`.

### 4.4 Run — Agent Update (Internal)

**Trigger:** `wakeAgent` (§4.6), not directly by MCP users.

Also callable via `agent:trigger` — a **fallback kick**, not a result-getter.
Trigger nudges the run loop so a cycle executes; it carries no payload and
makes no guarantees about agent output. Callers who want results should
submit work via `agent:request` (task Job) or `agent:chat` (chat Job) and
await that Job. Use trigger only when the normal intake path isn't enough
(manual state edit, diagnostics, resuming a stuck agent). Trigger creates no
session: a supplied `sessionId` is resolved and echoed back, and without one
the trigger runs unsessioned.

`force` (default `true`) runs a cycle even when the agent has no work, so an
agent may act proactively on a trigger. The forced cycle takes a ready task if
there is one, and never a parked one (§4.6). `force: false` runs a cycle only
when there is work, and an idle agent with none returns its status rather than
failing; the scheduler's wake events pass `false`.

The `wait` parameter on trigger is a block on the loop's completion
future, not a result-await. `true` or absent blocks until the loop drains
all outstanding work and exits to SLEEPING, `false` returns at once, and a
number of milliseconds is a bounded block. A transition that awaits an ordinary
async op (LLM or HTTP) simply keeps its virtual thread blocked on `.join()` —
there is no yield/resume handoff. Sessioned HITL requests return their durable
handle immediately and re-enter through the asking task or session (§6.3). A `SLEEPING` return means
the loop fully drained; a `RUNNING` snapshot from a bounded `wait` means
the loop is still processing.

Cancelling the trigger Job ends its `wait` immediately: `job.cancel()`
completes a per-caller signal raced against the loop's completion future,
so the waiting thread unblocks at once. This stops only that caller's
wait — the run loop itself is untouched and continues for any other
waiters attached to the same future.

**Run exclusion.** The in-memory `runningLoops`
(`ConcurrentHashMap<agentId, CompletableFuture>`) is the source of truth
for whether a loop is live. Launch is serialised by an atomic CAS via
`ConcurrentHashMap.compute` — the first wake installs a fresh future,
subsequent wakes observe the existing one and return it. A virtual
thread is started only by the winning wake. Mutations to the agent
record (`addTask`, `appendSessionPending`, run loop writes) use atomic
cursor updates directly — no shared lock.

The lattice `status` field supplies remote observability; `runningLoops` is the
authoritative local liveness signal. Startup clears RUNNING markers that cannot
have a surviving executor. `ConcurrentHashMap.remove(key, value)`
ensures a new run's future is never accidentally completed by an old
run's `finally` block.

**Sequence (per iteration):**

1. Read the current agent record: `sessions`, `tasks`, `pending`, `state`.
2. If not the first iteration and nothing to process (no tasks and no
   session with pending messages and no scheduled wake due): break out
   of the loop. Pending jobs are passed through but do not alone
   trigger a run.
3. Pick the oldest ready task and one session (the task's, else one with
   pending messages), and resolve job data from JobManager. A picked task
   whose request Job has already finished is removed, and the loop goes back
   to step 1.
4. **Take the cycle's input**, in one transaction on the agent record:
   - Mark the task's next input shown: its original input, then each
     continuation once. If a continuation arrived since the pick, take
     nothing and go back to step 1.
   - Claim the session for this cycle (`session.inCycle`), so live frame
     writes of any earlier cycle still running are refused.
   - Close any tool call an interrupted cycle left without a result, with a
     result saying it did not return.
   - Append the pending messages and the task's input to
     `frames[0].conversation` as user turns, and remove exactly those
     messages from `pending`. A message that arrives later waits for the
     next cycle.

   A task whose session has gone takes only its input and runs without one.
   Each chat whose message was taken becomes STARTED; a chat still queued
   stays PENDING. Nothing after this step abandons the cycle, so only its
   outcome settles what it took.
5. Invoke the transition function (§3.4) with `agent-id`, current `state`,
   `tasks`, `pending` (with resolved statuses), the task's `newInput` and
   the picked `session`. The virtual thread blocks on `.join()` of the
   returned future — no lock is held; other agents' loops run
   independently.
6. On success — **merge with current state** atomically on the agent record:
   - Re-read the record inside the merge (not the stale snapshot from step 1).
   - Remove completed tasks from the **current** `tasks` index. This prevents
     overwriting tasks added by concurrent task submissions during the
     transition.
   - Append the transition's `turns` and its reply to the session's
     conversation, unless the transition writes its frames itself, and end
     the session's claim.
   - Update `state` from the returned value.
   - Append the cycle record (§2.4). The cycle's token usage — the sum
     over its inferences, provider-measured (#217) — is added to the picked
     session's `meta.tokens` in the same CAS and attached to the caller's
     task/chat job record; the final assistant turn carries its own
     inference's usage. Absent means "not measured", never zero.
   - Do not write executor status; the launcher slot remains the liveness truth.
7. Settle the task completions parked by `agent:complete-task` /
   `agent:fail-task` calls made during the transition, and complete every
   waiting chat on the session that is STARTED with the reply — this
   cycle's and any an earlier cycle took and yielded on. A cycle without a
   reply yields, and those chats keep waiting. Both happen strictly AFTER
   the merge, so external awaiters see timeline and state writes first.
8. Loop back to step 1. The top-of-loop check at step 2 is the sole
   exit gate.
9. On failure, thrown or returned as the output's `error` (§3.4):
   - Append the cycle record with the error and the inferences that ran
     before it (§2.4).
   - If the agent can continue, fail the picked task's Job and the
     session's STARTED chats, then loop back to step 1 for the remaining
     work.
   - If it cannot (a `CannotContinueException` or an unexpected failure),
     set status → `"SUSPENDED"` and `error`, keep the queue, fail the
     chats a cycle took, and give every waiting Job the suspension note.
   - Note: task completions made via tool calls during execution are
     **not** rolled back (§3.4.1).

**Iteration budget.** A single loop runs at most `MAX_LOOP_ITERATIONS`
(20) cycles before forcing sleep — a runaway backstop, not a normal exit.
A task that yields with nothing outstanding fails at once (§4.6), so the cap
is reached only by a task that is ready again every cycle, such as one whose
wake time is always already past.
A cap hit on genuinely varied work (a long task queue, chat traffic) is
benign: remaining work re-wakes a fresh loop via the post-exit re-check.
But if the **same task** was presented for the entire budget without
resolving, the cap is terminal for that task (#215): its job is FAILED
with a structured error and the task removed — otherwise the re-check
relaunches against the same stuck task and the wake/cap cycle burns LLM
spend forever while the caller's job pins STARTED. Younger queued tasks
are untouched. Related fallback: control tools emitted as plain assistant
text (`complete_task {...}` / `fail_task {...}` — common on small models)
are recognised and honoured by the tool loop, so most stuck cases resolve
properly before the cap.

After the loop exits (break or exception), the agent is released from
its running slot and work is re-checked; if work landed during exit, a
fresh wake is issued. The exit-vs-wake race is closed so no wake is
ever dropped.

The transition operation always comes from `config.operation` (set at creation).
Callers cannot override it — to change the transition function, update the config.

The run output includes the transition function's `result` field, making
the response visible to callers without querying agent state separately.

### 4.5 Other Lifecycle Events

| Operation | Trigger | Effect | Allowed status | New status |
|-----------|---------|--------|----------------|------------|
| **Suspend** | the agent cannot continue, or the run loop fails unexpectedly (§3.4) | Set `error`, set status → SUSPENDED. State, tasks, pending, sessions unchanged; waiting Jobs carry the suspension note. | RUNNING (internal) | SUSPENDED |
| **Resume** | `agent:resume` | CAS SUSPENDED→SLEEPING via `tryResume`, clear `error` and `suspension`, clear waiting Jobs' suspension notes. Then wake via §4.6 if there is work. | SUSPENDED only | SLEEPING |
| **Suspend (manual)** | `agent:suspend` | Atomically set SUSPENDED and `suspension`, clear `error`; latest stop replaces previous actor/reason. Cancel an active transition, recording the stop on its timeline. The queue is kept as for any suspension (§3.4). | SLEEPING, RUNNING, SUSPENDED | SUSPENDED |
| **Trigger** | `agent:trigger` | Wake the agent and (optionally) wait for the run loop to drain. Fails on TERMINATED. See §4.6. | SLEEPING, RUNNING | RUNNING then SLEEPING |
| **Cancel task** | `agent:cancelTask`, or cancelling the request Job | Remove a pending task from the agent's `tasks` index, so the agent never runs it. Does not affect the run loop directly. | any except TERMINATED | unchanged |
| **Delete** | `agent:delete` | Accepts exactly one `agentId` or an exact `agentIds` list (maximum 100; no prefixes/wildcards). One Job halts each named agent. Default: set status → TERMINATED (record retained for audit). With `remove:true`: physically remove each lattice slot, making unreachable storage eligible for Etch GC. The full list is validated before deletion starts. | any | TERMINATED (or absent) |
| **Fork** | `agent:fork` | Create a NEW agent at a different `agentId` from this one's config + optional state + optional timeline. The source is untouched and the target slot must be empty. | source must not be TERMINATED | new agent SLEEPING |

`agent:info`, the REST agent summary, and `agent:list` expose `suspension`.
Clients distinguish a deliberate stop by that field, never by error wording.
Legacy records without it remain unclassified. New requests/chats to a
suspended agent fail with `errorCode: "agent:suspended"`; their message names
the stop and advises asking the stopping caller, or retains fault-recovery
advice for an ordinary failure. `by` is `ctx.getCallerDID()`, including through
sudo; an intermediary is not relabelled as the agent on whose behalf it acted.

**All mutations are atomic.** Each operation is a single compare-and-swap
on the agent's lattice cell, so concurrent operations against the same
agent serialise cleanly without explicit locking. The run loop's cycle
is a read → transition → merge sequence; concurrency with external
mutations is handled by reading the current record inside the merge
(not the pre-transition snapshot) so late writes are never lost. That
holds for `state` too: the transition's result is applied as its *change*
against the snapshot it fired with (`AgentState.applyStateChange`) — keys it
changed or removed win, keys it left alone keep the record's current value.
So `agent:update` never waits for, or refuses, a running agent: config
applies to future transitions (a started transition keeps its fire-time
snapshot), state merges, and `agent:suspend` is how a transition in flight
is halted.

**Status invariants:**

- Once an agent is TERMINATED, it cannot transition to another status. Remove
  the record with `agent:delete remove:true` before creating a new agent with
  the same ID; there is no "un-terminate".
- RUNNING is persisted while a launcher future exists, but the stored value is
  not a concurrency lock or resume checkpoint. Config mutations consult the
  live future and are rejected while it exists.
- SUSPENDED preserves session/history state but failed transition tasks and
  cycle claims are settled before suspension. `agent:resume` preserves that
  record; explicit delete with `remove:true` discards it.

### 4.6 Scheduling — `wakeAgent`

Scheduling combines durable work (`sessions`/`tasks`) with one process-local
launcher slot. Stable lattice status gates suspended/terminated agents; the
launcher slot provides exclusion and observable liveness.

**`wakeAgent(ownerDID, agentId, force)`** is the single entry point for all
agent wakes. It is called by the intake operations (`agent:request`,
`agent:message`, `agent:chat`), HITL answers, `agent:resume`, `agent:trigger`
(including the scheduler's wake events), boot recovery and the run loop's own
exit re-check. The logic (atomic CAS on `runningLoops: ConcurrentHashMap` via
`compute()`):

1. Inside `runningLoops.compute(agentId, ...)`:
   - If slot already populated → live loop exists; return existing completion
     (caller observes RUNNING; new work is in the lattice and the live loop
     will see it on its next iteration).
   - Otherwise → read the stable agent status from the lattice.
   - A stale persisted `RUNNING` is allowed through defensively; normal startup
     has already cleared it.
   - Unless `force`, if there is no work (no session with pending messages
     and no ready task) → return null (leave slot empty).
   - Resolve `config.operation` → if null, return null.
   - Create a fresh `CompletableFuture<ACell>` and atomically put it in the slot.
   - Persist `RUNNING`, then launch `executeRunLoop` on a
     `Thread.ofVirtual()`.
2. The launcher wins the slot atomically; concurrent callers observing the
   populated slot simply return the same completion future.

An agent is eligible to run when any of:

- A session has a message in its `pending` queue (via `agent:message` or
  `agent:chat`, or a HITL answer or the outcome of a tool call that passed its
  time limit when no queued task waits for it)
- A task is ready (below)

**Task readiness and yields.** A task is ready unless it is parked. A parked
task is ready again once something new is there for it: an input it has not
been shown (a continuation through `agent:message` with its `taskId`, or the
answer or outcome its cycle waited for), a message in its session, or its
`wakeTime` having come. The work check and the
choice of task both use this test (`AgentState.isTaskReady`), and the cycle
takes the oldest ready task.

A cycle that leaves its picked task queued, neither completed nor failed and
without a transition error, has yielded it. The task is parked
(`parked: true` on its row) when something will bring it back:

- a HITL ask from its cycle that is still open (the ask's entry in the agent's
  `pending` index names the task);
- a tool call from its cycle still running past its time limit (§3.4.2),
  tracked in the agent's `pending` index;
- a `wakeTime` on the task;
- input or session messages that arrived during the cycle.

A yield with none of these could never resume, so the task's Job fails at once
with `AgentAdapter.TASK_YIELDED_IDLE`. Claiming a task for a cycle clears
`parked`, so a task that is not parked but has been shown its input was
interrupted mid-cycle, and runs again (§6.1).

**No lost wakeups.** `addTask` / `appendSessionPending` write to the lattice
atomically, then call `wakeAgent`. The loop's top-of-iteration `hasWork` check
reads the current lattice. If work landed while a loop was running, the next
iteration sees it. If work landed while the loop removed its slot, the
`finally`-block double-check (§4.4) re-launches; failing that,
the next `wakeAgent` from the writer finds the slot empty and launches.

**No double runs.** `ConcurrentHashMap.compute()` serialises the
empty-slot → launch transition. Only one thread can populate the slot; all
others observe it populated and return the existing completion.

**Scheduled wakes.** Wake times are kept per session and per task, not per
agent, so a sleep on one session never delays another. A session or task
record may carry `wakeTime` (absolute millis): "this thread wants to wake at
T". Only the framework writes it, never the `n/`, `c/` or `t/` scratch
namespaces. On merge, `mergeAndPostProcess` installs the `wakeTime` a
transition returns on the thread it picked, or clears the one that thread
carried when the transition returns none. The last writer wins, with no
min-merge, so a short retry cannot clobber an explicit long sleep. Intake
operations write `pending` or `tasks`, never `wakeTime`: new work runs at once
through `hasWork`.

The venue keeps one `agent:trigger {force: false}` event per agent in the grid
scheduler, at the earliest `wakeTime` across its threads
([GRID_SCHEDULER.md §8](./GRID_SCHEDULER.md)). When it fires, a parked task
whose `wakeTime` has come is ready and runs. Readiness applies to tasks only:
a session's own `wakeTime` firing with nothing pending runs nothing. No
production transition returns a `wakeTime` yet, and there is no fixed
periodic sleep interval. A `sleep` harness tool is future work (§8.3.4).

The scheduler is an internal venue concern — its implementation may change
without affecting the agent contract or MCP interface.

---

## 5. Atomic Updates and Merge Semantics

### 5.1 Local Agent Updates

All writes to an agent are serialised on one venue through atomic compare-and-swap
on the agent's record slot. Message delivery, task addition, and run-loop updates
all contend against the same slot, so concurrent venue threads do not lose updates.
Each successful mutation writes a complete coherent agent map and refreshes `ts`.

The run loop never holds a lock. It reads a snapshot at the top of each iteration,
runs the transition (which may take seconds or minutes), then applies its result
atomically against the current record. Concurrent task additions and session
message appends proceed while the transition runs; the merge code preserves them
by reading the current sessions/tasks rather than the stale pre-transition snapshot.

### 5.2 Cross-Venue Replication

Cross-venue sync does not merge agent records independently. The venue's outer
whole-value LWW lattice compares the venue `:timestamp`; the newer complete signed
`:value` snapshot wins. This is valid because the venue signing key is the single
authoritative writer for that state lineage. Replicas receive a coherent venue
state, including the complete agent map that actually existed on the host.

Per-field merge (a previous design) was wrong because it could produce Frankenstein
states — status from venue A, timeline from venue B, session fragments from both. An
inconsistent state that never actually existed. Whole-snapshot replication ensures
the winner is always a state that genuinely existed on the hosting venue.

---

## 6. Design Decisions and Open Questions

### 6.1 Recovery

A venue restart has no live launcher slots, so startup clears every persisted
RUNNING marker before scheduling remaining work.
Durable Jobs, queued inputs, stable lifecycle state, and external interaction
records survive independently. Recovery may start a fresh attempt from a
durable boundary; it never treats a persisted executor bit as resumable state.

Request and chat Jobs survive a restart: the adapter's `recoverJob` keeps a
request whose task is still in `tasks` and a chat whose envelope is
still pending on its session, so the boot wake completes them. Venue shutdown
likewise leaves them as they are (`suspendJob`) and stops the run loop without
relaunching it (see JOBS.md § Shutdown). A chat whose message a cycle had
already taken is not kept, since no cycle would take it again: shutdown
cancels it, and after a crash it fails at boot.

A parked task (§4.6) keeps waiting across a restart only while what it waits
on survives one. A wake time, an open HITL ask and a tool call tracked in the
pending index (§3.4.2) are durable. Before any agent is woken, every agent's
tracked calls are recovered: one whose job has ended has its outcome
delivered to its session (a mirror always has, since the restart fails it, so
the waiting task runs again on that failure); one whose job is live is
delivered when it ends; one whose job is gone has nothing to deliver. A
parked task then left with nothing to wait on fails at boot
(`AgentAdapter.TASK_WAIT_LOST`). A task that was not parked but had been shown
its input was interrupted mid-cycle, and the boot wake runs it again.

### 6.2 Effects

Transition functions implement their own effects. A transition function that
needs to send messages to other agents or invoke grid operations does so
directly during execution (it has access to the engine like any adapter).

Agent-level effect handling (an `actions` field in the output, processed by the
framework) is deferred. It may be useful for sandboxed or auditable agents but
adds complexity that is not needed for the initial LLM agent.

### 6.3 Human-in-the-loop (HITL) requests

When an agent needs human input, `hitl:request` creates a durable Job and record
in the target user's **request queue** at `/h/<job-id>`. From a sessioned agent
tool call it returns `{id, status: INPUT_REQUIRED, title}` immediately. The
record stores the verified requester, agent and session. When the human answers,
rejects, or the request expires, HITL resolves the Job, delivers the outcome
with the responder's provenance, and wakes the agent. The outcome goes to the
task whose cycle asked as its next input, so it runs that task and no other in
the session; when no task asked, or the task has finished, it goes to that
exact session as a message. The Job remains the polling and audit fallback; no
model turn waits for the human.

External callers keep the standard Job contract: the returned Job remains
`INPUT_REQUIRED` until it completes or fails.

**User request namespace:**

```
:user-data → <DID> → "h" → <job-id> → job reference
```

The `/h/` namespace is analogous to `/g/` (agents) and `/s/` (secrets) but
holds Job IDs for requests that the human user is expected to complete. MCP
clients can list and respond to these requests.

The MCP-facing operations are `hitl:list`, `hitl:respond`, and the ordinary Job
read/status surfaces.

### 6.4 MCP-facing agent operations

With the task-based model, MCP users interact with agents through a small set
of high-level operations. They never call `agent:trigger` directly.

| MCP Tool | Purpose |
|----------|---------|
| `agent:create` | Create and configure an agent |
| `agent:request` | Submit a task for the agent to complete (returns Job ID) |
| `agent:message` | Send an ephemeral notification |
| `agent:query` | Read agent state, tasks, pending jobs |
| `agent:list` | List user's agents |

The agent's response to a request is the Job result — the MCP client polls or
subscribes to the Job for status updates, same as any other Covia job.

### 6.5 Credential resolution

Key material is not stored in agent config; `config.apiKey` is a secret reference. The operation metadata declares a
`secretKey` name (e.g. `"OPENAI_API_KEY"`); at runtime the adapter looks this
up in the caller's encrypted secret store (`/s/`). An optional plaintext
`apiKey` input parameter exists for testing only. No environment variable
fallback — credentials must be in the secret store for production use.

---

## 7. Phasing

| Phase | Focus | Status |
|-------|-------|--------|
| **C2** | Delegation chains — proof chain walking, attenuation validation, agent sub-delegation, revocation | Planned |
| **E** | Agent forking, cross-venue migration, federated UCAN validation | Planned |

---

## 8. Architectural Direction

**Status:** Proposed — April 2026. §1–§7 describe the current code; §8 describes the target. Each phase below promotes its content into the body and removes the corresponding stub. When §8 is empty, the body alone is the spec.

### 8.0 Progress note

The goal-tree harness now reads and writes its session frames directly on the lattice during the run (epoch-fenced; see GOAL_TREE.md §Persistence) — the D-1 direction ("adapter reads everything from the lattice") is already real for the frames slot, and the merge's frames responsibilities have moved out of the framework for `FramesOwning` adapters.

### 8.1 Why this section exists

The run loop and step contract were fitted to an early agent model where:

- The step was a pure-FSM transition `(state, input) → (state', response)`
- Every cycle was scoped to a picked task and picked session
- The framework handed adapters wire-shaped inputs (tasks, pending, messages, session, config) and trusted them to thread state through

Two things have undermined that model:

1. **Session work landed.** Conversation now lives on `session.frames` (recent commit "Drop session.history; read transcript from session.frames"), not in `state`. The pure-FSM accumulator is no longer pulling its weight; in current code `state` mostly just round-trips a duplicate of `config`.
2. **Wake events generalised.** Runs fire from new tasks, new messages, pending-job completions, scheduled fires, triggers, and self-wakes. The "pick a task and process it" assumption fits `agent:request` cleanly; it distorts the rest into a task-shaped mould or skips them.

The target is a leaner contract where the step is a hook the framework calls each cycle, the harness reads what it needs from the lattice via context, and the framework's role shrinks to scheduling, persistence, and lifecycle.

### 8.2 Vocabulary

These terms are pinned in §8 and will replace inconsistent usage elsewhere during the cleanup pass.

| Term | Meaning |
|------|---------|
| **Harness** | The agent infrastructure that wraps a reasoning core (typically an LLM) into an agent. In Covia: framework run loop + step adapter. Spans L1 and L2. |
| **Run loop** | The framework's outer loop — wake routing, cycle iteration, merge, lifecycle. The L1 part of the harness. Implemented in `executeRunLoop`. |
| **Wake event** | Any event that schedules a run: task arrival, message arrival, chat arrival, pending-job completion, scheduled fire, trigger, self-wake. |
| **Trigger** | A specific kind of wake event — externally fired, manual, payload-less. Concretely the `agent:trigger` MCP op. Triggers carry no payload and have no result-await semantics; callers who want a result use `agent:request` or `agent:chat`. |
| **Run** | Full harness execution from a wake event to the next SLEEPING or SUSPENDED. Contains 1..N cycles. |
| **Cycle** | One iteration of the run loop = one **step** + one merge. |
| **Step** | One invocation of an L2 adapter — the agent's reasoning step. Synonym: **transition** (legacy; kept for compatibility). |
| **Step adapter** | The L2 implementation (`llmagent:chat`, `goaltree:chat`, …). Owns the agent's reasoning style. |
| **Tool-call loop** | The harness's internal LLM↔tool iteration *inside* a step. Not every step adapter has one — rule engines, workflows, and test stubs may not. |
| **LLM call** | One HTTP invocation of a level-3 provider (`langchain:openai`, …). Not part of the harness. |

Layered view:

```
Harness ┌──────────────────────────────────────────────────────────┐
        │ L1: Run loop  — wake routing, cycles, merge, lifecycle   │
        │ L2: Step      — reasoning step; may include a tool loop  │
        └──────────────────────────────────────────────────────────┘
L3: LLM call (provider) — single model invocation, no agent semantics
```

#### Wake-event taxonomy

| Wake event | Source | Carries payload? | Caller awaits? |
|------------|--------|:----------------:|:--------------:|
| Task arrival | `agent:request` adds a Job to `tasks` | yes | yes (the Job) |
| Message arrival | `agent:message` appends to `session.pending` | yes | no |
| Chat arrival | `agent:chat` reserves a chat slot | yes | yes (the chat Job) |
| Pending completion | An outbound Job in `pending` finishes; completion callback wakes the agent | result of the outbound Job | no |
| Scheduled fire | Grid scheduler fires the agent's `agent:trigger` event at the earliest thread `wakeTime` (§4.6) | no | no |
| **Trigger** | `agent:trigger` MCP op | no | optional loop-drain `wait`, not a result-await |
| Self-wake | A previous step wrote new work to the agent's own queues | (whatever the previous step wrote) | n/a |

### 8.3 Gaps in the current design

#### 8.3.1 `state` is a vestigial pure-FSM accumulator

The step input today carries `state`; the output writes `state` back. In current code:

- `LLMAgentAdapter` reads `state` to extract `loads` (one slot), then writes back `{config, loads}`. The `config` field is a duplicate of the agent record's `K_CONFIG`.
- ~~`GoalTreeAdapter` round-trips `state.config`~~ — resolved by #144: config has a single home (`record.config`); the runtime never writes it, so no carry-over exists.

Pure duplication. The record's `K_CONFIG` is canonical and is already passed into the step input as `KEY_CONFIG` separately. The only legitimate per-step persistent state is `loads` (LLM only), which can move to a dedicated lattice slot.

#### 8.3.2 Cycle scope is over-fitted to "a picked task"

The framework picks at most one task and at most one session per cycle, then bakes them into the step input via `formatPickedTask` / `pickSessionForCycle`. This fits `agent:request` cleanly, but distorts every other wake event:

- **Trigger** fires with no task and no session — the framework synthesises empty inputs.
- **Scheduled fire** fires with no payload — the agent is woken to "think now."
- **Pending completion** has no task to pick.
- **Multi-task agents** (orchestrators, batch processors) cannot consume multiple queued tasks per cycle.

The step adapter ends up branching on "do I have a task? a session? neither?" — branching that should not be in the step adapter, and that the framework should not pre-perform.

#### 8.3.3 Wire shapes leak framework concerns into adapters

`formattedTasks`, `resolvedPending`, `effectiveMessages` are framework-internal scheduling artefacts. Step adapters depend on them. Changing the framework's wire format changes every step adapter. The right boundary is: framework gives the adapter a context, adapter reads typed lattice data via accessors.

#### 8.3.4 Wake features not built

- **`sleep` harness tool** — let an LLM defer its current thread by writing
  `wakeTime = now + ms`. A future `wakeTime` is set only by a transition
  result (§4.6).
- **Async invoke** — a harness tool that starts an operation without waiting
  and delivers its outcome to the asking task or session when it ends. Today
  only sessioned HITL asks (§6.3) and tool calls past their time limit
  (§3.4.2) re-enter this way. Retrying slow work belongs inside the operation's own Job, not in the
  run loop or the scheduler.
- **Session readiness** — a session's own `wakeTime` running a cycle with
  nothing pending, as a task's does (§4.6).
- **Task/session unification** — one `threads` shape with a single
  `pickReadyThread`, replacing separate tasks and sessions. A large refactor
  needing its own design.
- **Observability** — each thread's `wakeTime`, whether a task is parked and
  on what, and the agent's next wake, in `agent:info`.

### 8.4 Target principles

(Replaces §1.)

1. **Single atomic value.** Unchanged. The agent record is one map.
2. **Last writer wins.** Unchanged.
3. **Single venue writes.** Unchanged.
4. **Two layers, not three.** L1 (run loop) drives cycles and persists results. L2 (step adapter) does one cycle's worth of reasoning. L3 is outside the harness — a step adapter may invoke an L3 provider, but that's the adapter's business, not a layer of the contract. The framework never inspects the step adapter's reasoning; the step adapter never manages framework fields.
5. **Lattice is the state.** No FSM accumulator threaded through the step. Persistent agent state (config, sessions, tasks, pending, timeline) lives on the lattice. The step adapter reads what it needs via context and writes via venue ops.
6. **A failure lands on what failed.** Unchanged (§3.4): a failing step fails the work it took, and suspends the agent only when the agent cannot continue. The step adapter can return `error` to fail explicitly.

### 8.5 Target step contract

#### 8.5.1 Context

Every step receives a `RequestContext` carrying:

- `callerDID` — who owns this agent
- `agentId` — which agent
- `cause` — what wake event fired this run (enum; see §8.2 taxonomy)

Notably absent: `taskId`, `sessionId`. The step adapter chooses which task or session (if any) to act on, by reading the lattice. The framework no longer pre-picks.

#### 8.5.2 Input

The step input is minimal:

```
{ cause: "task" | "message" | "chat" | "pending" | "scheduled" | "trigger" | "self" }
```

No `state`, no `tasks`, no `pending`, no `messages`, no `session`, no `config`. The step adapter resolves what it needs:

| Want | Read |
|------|------|
| Agent's config | `agent.getConfig()` |
| Queued tasks | `agent.getTasks()` |
| Outbound pending | `agent.getPending()` |
| Sessions | `agent.getSessions()`, `agent.getSession(sid)` |
| Session frames | `agent.getSession(sid).get("frames")` |
| Session inbox | `agent.getSession(sid).get("pending")` |

`cause` is a hint, not a contract — the step adapter is free to read the full lattice state and decide what to do regardless of cause.

#### 8.5.3 Output

```
{
  response?: ACell,          // recorded as the cycle's timeline result
  error?:    ACell,          // fails the work this step took (§3.4)
  frames?:   AVector<ACell>, // optional session-frames replacement (goal-tree pattern)
  done?:     boolean         // explicit "no more work to do this run" signal
}
```

No `state` field. Task completions go through `agent:complete-task` and `agent:fail-task`, as they already do (§3.4.1).

#### 8.5.4 Stop condition

Currently the run loop iterates while `hasWork(agent)`. Target: the step adapter signals `done: true` when it has nothing left to do. The framework re-invokes the step until `done: true` arrives, with `MAX_LOOP_ITERATIONS` retained as a misbehaving-agent safety cap.

Why adapter-driven: the framework cannot reliably tell "is there more work?" from lattice state alone. A scheduled-fire step might decide nothing changed and return immediately. A multi-task step might process several queued tasks per cycle and signal done when the queue empties. A goal-tree harness might need to plan even with no queued work.

### 8.6 Target run loop

(Replaces §4.4.)

Per cycle:

1. Inside `runningLoops.compute(agentId, ...)`: serialise launch via CAS (unchanged).
2. Loop:
   1. Re-check status; exit on SUSPENDED / TERMINATED.
   2. Determine `cause` from the wake event (or "self" for in-loop iterations).
   3. Build minimal `RequestContext` (`callerDID`, `agentId`, `cause`) — no `taskId`, no `sessionId`.
   4. Invoke the step: `engine.jobs().invokeInternal(stepOp, {cause}, ctx)`.
   5. On success: write timeline entry from `response` / `error`; replace `frames` if emitted.
   6. On `done: true` or after `MAX_LOOP_ITERATIONS`: exit.
   7. On `error`: fail the work the step took; on `CannotContinueException` or an unexpected failure, suspend the agent with its queue kept (§3.4).
3. After loop exits, mark SLEEPING (or honour SUSPENDED/TERMINATED), release `runningLoops` slot, re-check for wakes.

The framework no longer:

- Picks a task or session
- Builds wire-shaped `formattedTasks` / `resolvedPending` / `effectiveMessages`
- Drains `session.pending` based on a presented count (the step adapter calls a venue op when it consumes messages)
- Resolves Job IDs to job data

### 8.7 Migration map

| Current | Target | Notes |
|---------|--------|-------|
| `K_STATE` slot on agent record | retired | Adapter-specific persistent state moves to dedicated slots |
| Step input field `state` | removed | Adapter reads from lattice |
| Step input fields `tasks`, `pending`, `messages`, `session`, `config` | removed | Adapter reads via `agent.*` accessors |
| Step output field `state` | removed | No round-trip |
| `state.config` (LLM/goaltree) | ~~merged into record `K_CONFIG`~~ DONE (#144) | Collapse shipped: single config slot, state.config rejected at create/update |
| `state.loads` (LLM) | dedicated slot (`K_LOADS` or adapter namespace) | Only legitimate per-agent persistent state |
| Framework picks task/session each cycle | adapter reads queue and decides | Cycle scope is the agent, not a task |
| Loop exits on `hasWork == false` | loop exits on `done: true` from step | Adapter-driven stop condition |
| `RequestContext` carries `taskId`, `sessionId` | dropped | Adapter resolves these from the lattice |

### 8.8 Phased plan

Each phase is a self-contained PR with a doc commit that promotes its content from §8 into the main body and removes the corresponding stub.

#### Phase D-1 — Step input simplification

- Drop `tasks`, `pending`, `messages`, `session`, `config` from the step input wire shape.
- Step adapters read via `agent.*` accessors and `RequestContext`.
- Keep `state` in the step input/output for now to bound the change.
- Doc: rewrite §3.4 input table; update §4.4 step 5.
- Tests: `LLMAgentAdapterTest`, `AgentAdapterTest` exercise the new wire shape.

#### Phase D-2 — Output narrowing + done signal

- Step output becomes `{response?, error?, frames?, done?}`.
- Drop `state` from the output.
- Loop stops on `done: true` (or `MAX_LOOP_ITERATIONS`).
- Doc: rewrite §3.4 output table.
- Tests: cover the done signal, no-task scheduled wake, multi-task cycle.

#### Phase D-3 — Retire `K_STATE`

- Remove `K_STATE` from the agent record.
- LLMAgentAdapter's `loads` moves to a dedicated slot.
- Migration: ignore `state` on read for existing agents; never write it.
- Doc: rewrite §2.2 record shape; update §1 principles.

#### Phase D-4 — Generalise the cycle

- Framework no longer picks task or session.
- Step input becomes `{cause}`; context drops `taskId` and `sessionId`.
- Wake events explicitly enumerated and passed as `cause`.
- Doc: rewrite §4.4 sequence; update §4.6 wake events; reframe §6.4 MCP-facing ops.

After D-4, §8 is empty. The body of the doc reflects the new model and the banner at the top of the file is restored to a plain status line.

### 8.9 Open questions

1. **Should the step input carry `cause` at all?** Pro: gives the adapter a hint without forcing a lattice diff. Con: adapters might over-trust it. Lean: include it as a non-authoritative hint.

2. **Where does `loads` live post-D-3?** Resolved: dynamic loads live on the
   active durable frame, while operator loads remain in config. The canonical
   state and scope rules are in [AGENT_CONTEXT.md](./AGENT_CONTEXT.md) §1.1 and §7.

3. **Backwards compatibility for persistent agents.** Resolved (#144): fail-loudly — `agent:create`/`agent:update` reject `state.config` with a clear error; no migration (alpha, agents re-creatable).

4. **Does Phase D-4 break MCP clients?** The MCP-facing operations (`agent:request`, `agent:message`, `agent:chat`, `agent:trigger`) are unchanged — only the framework↔harness contract moves. External callers should see no difference.

5. **Class renames.** `LLMAgentAdapter` → `LLMHarness`? `GoalTreeAdapter` → `GoalTreeHarness`? Defer; doc terminology can lead the code.
