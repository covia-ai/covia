# Agent Design — Data Structure and Transitions

Design for Covia agent state and the transitions that mutate it.

**Status:** April 2026 (current behaviour). See **§8 Architectural Direction** for the in-flight redesign of the framework↔harness contract; §1–§7 describe how the code works today.

See GRID_LATTICE_DESIGN.md for: per-user namespace layout (§4.3), agent addressing
(`/g/<agent-id>`, §4.3.4), secret store (`/s/`, §4.3.6), and implementation phasing (§12).

---

## 1. Principles

1. **Single atomic value.** An agent's entire state is one map. Every operation
   (create, message, run, config update) atomically replaces the whole map. No child
   lattices, no per-field merge. The map is the unit of state.

2. **Last writer wins.** If two copies of an agent need to merge, the one with the
   later `ts` wins. All writes are serialised on the hosting venue, so `ts` is
   monotonic within an agent's lifetime.

3. **Single venue writes.** An agent lives on one venue. All writes — message delivery,
   agent runs, config updates — are serialised on that venue via atomic
   compare-and-swap on the agent record. Cross-venue sync is replication: the
   more-recent state replaces the stale one.

4. **Three levels.** The agent update (level 1) manages framework bookkeeping:
   status, timeline, sessions. The agent transition (level 2) manages domain logic:
   conversation history, tool call loops, state. The LLM call (level 3) is a single
   stateless invocation. Each level is a grid operation, pluggable independently.
   The framework never inspects user state; lower levels never manage framework fields.

5. **Transition function must succeed.** A failing transition function is a severe
   bug — it suspends the entire agent. The agent update restores the queues and
   records the error. The transition function is responsible for its own error
   handling; if it wants the agent to continue running, it must return successfully.

---

## 2. Data Structure

An agent lives at `:user-data → <owner-DID> → "g" → <agent-id>`.

### 2.1 Lattice Shape

```
"g" → MapLattice (agent-id → per-agent value)
  <agent-id> → LWW (latest ts wins)
```

One value. The LWW lattice compares `ts` and selects the winning map whole.
Inside the map there are no lattices — just fields, atomically replaced together.

### 2.2 The Agent Record

The agent's value is a plain map. Every write replaces the entire map atomically.

| Field | Type | Description |
|-------|------|-------------|
| `ts` | long | Timestamp of the last write. **The merge discriminator.** Set on every write. |
| `status` | string | `"SLEEPING"` \| `"RUNNING"` \| `"SUSPENDED"` \| `"TERMINATED"` |
| `config` | map | Framework-level configuration. Includes `operation` (default transition op). |
| `state` | any | User-defined state. Opaque to the framework. Passed to and returned from the transition function. Transition-function-specific configuration (e.g. LLM provider, model) lives here, not in `config`. Set at creation via optional initial state. Conversation content lives on the session record (`session.frames[0].conversation`), not here. |
| `tasks` | index | `Index<Blob, ACell>` of inbound request Job IDs. Persistent until resolved. Ordered by Job ID. |
| `pending` | index | `Index<Blob, ACell>` of outbound Job IDs the agent is waiting on. Ordered by Job ID. |
| `sessions` | index | `Index<Blob, ACell>` of sessions. Each session contains `frames` (the goal-tree frame stack — `frames[0].conversation` is the canonical transcript), pending messages, metadata, and per-session state (`c/`). See AGENT_SESSIONS.md and GOAL_TREE.md. |
| `timeline` | vector | Append-only log of transition records (§2.3). Grows with each successful agent run. Timestamped for audit. |
| `caps` | map | Capability attenuations — UCAN scoping for agent tool calls. See [UCAN.md §5.4](./UCAN.md) for Model A (user-scoped) vs Model B (independent DID). |
| `error` | string? | Last error message, or null. Set when the transition function fails. |

All fields are framework-managed except `state`, which is owned by the transition
function. CAD3 structural sharing means the timeline (which only appends) shares
all existing entries with the previous version.

### 2.3 Tasks vs Messages

An agent has two inbound channels with different semantics:

**Tasks** (`tasks`) are formal, persistent requests for the agent to complete.
Each task is a Job (managed by the venue's JobManager). The agent's `tasks` field
holds Job IDs. The agent decides when and how to fulfil each task. Tasks persist
until the agent completes or rejects them — they survive restarts and can span
days or weeks. Tasks can come from humans (via MCP), other agents, or the system.

**Messages** are delivered to `session.pending` — a per-session queue. Each
message is an envelope with content and caller metadata. Messages are drained
by the run loop on the next transition cycle for that session and appended to
`session.frames[0].conversation` as user turns. See AGENT_SESSIONS.md for the
session model.

An agent also tracks **pending** outbound jobs — jobs it has explicitly created
via the async invoke tool during the tool call loop (level 2). The agent
chooses which invocations to track; synchronous tool calls and internal
framework jobs are not tracked. When a pending job completes, the scheduler
wakes the agent to process the result.

### 2.4 Timeline Entry

Each entry in the `timeline` vector records one successful agent run. The
timeline is the **complete audit trail** — it captures everything the agent
received, decided, and accomplished in each run.

| Field | Type | Description |
|-------|------|-------------|
| `start` | long | Timestamp when the run started. |
| `end` | long | Timestamp when the run completed. |
| `op` | string | The operation reference used for the transition function. |
| `state` | any | The starting state passed to the transition function. |
| `tasks` | vector | Resolved task data (jobId, input, status) at run start (from the `tasks` index). |
| `messages` | vector | The session pending messages passed to the transition function. |
| `result` | any | The `result` returned by the transition function. |
| `taskResults` | map? | Task completions from output declarations (§3.4.1). `{<jobId>: {status, output}}`. |

The output state is not stored in the timeline entry — it is the `state` field in
the agent record (for the latest run) or the `state` field in the next timeline
entry (for earlier runs). This avoids redundant storage.

Timeline entries are only appended on success. On error, no timeline entry is
written — the error is recorded in the agent record's `error` field and all
queues are preserved for retry.

### 2.5 Status Lifecycle

The `status` field is the source of truth for what the agent is doing right now
and which operations may safely act on it. There are four states:

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
        │      │ run loop done   │      │
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
| `SLEEPING` | Idle, no transition active. Default after create or after a successful run with no remaining work. | Not running. `wakeAgent` triggers it. | All. Safe to update config in place. |
| `RUNNING` | A transition is currently in flight on a virtual thread. Record mutations go through atomic cursor updates. | Running. New work is appended to `tasks`/`session.pending` and picked up on next iteration. | Task/session appends only. **Config mutation is racy and rejected** — the in-flight transition has already captured the old config. |
| `SUSPENDED` | Last run failed with an error. Dormant — does not auto-retry. State, tasks, pending, and sessions preserved. | Not running. Resume via `tryResume` (clears error, returns to SLEEPING). | All. In-place config update is allowed and preserves the error so the caller can decide whether to resume after fixing the underlying cause. |
| `TERMINATED` | Logically deleted. Slot still occupies the namespace key (so the ID is reserved) but the agent is dead. | Cannot run. `agent:request` / `agent:message` / `agent:trigger` all fail. | None — only `agent:create overwrite:true` revives the slot, which **wipes timeline, sessions, tasks, pending, error** and starts fresh. |

**State transitions** are documented in §4: `create` (→SLEEPING), `wakeAgent`
(SLEEPING→RUNNING via CAS, §4.6), run loop completion (RUNNING→SLEEPING or
RUNNING→SUSPENDED, §4.4), `agent:resume` (SUSPENDED→SLEEPING, §4.5),
`agent:delete` (any→TERMINATED, §4.5), and `agent:create overwrite:true`
(SLEEPING/SUSPENDED→same status with new config, TERMINATED→fresh SLEEPING).

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

- Reads LLM configuration from `state.config`
- Reads the **session frame stack** from `input.session.frames` — turn
  envelopes `{role, content, ts, source}` live inside `frames[0].conversation`
  (plus any pushed child frames), converted to LLM `{role, content}` messages
  (`ts`/`source` stripped)
- Builds a **per-turn LLM context** with FRESH ephemeral additions every turn:
  - System message (identity prompt + lattice cheat sheet, rebuilt fresh)
  - Resolved context entries
  - Resolved loaded paths
  - `[Context Map]` budget summary
  - Then appends the rendered frame conversation
  - Then appends pending job results and new messages
- Invokes level 3 (LLM call) as a grid operation
- Handles tool call responses: execute tools, feed results back, call level 3
  again (loop until the LLM returns a text response or a limit is reached)
- The assistant's final response is returned to the framework, which appends
  it as a turn to `session.frames[0].conversation`
- The session frame stack is the canonical conversation record. The system
  prompt, context entries, and `[Context Map]` are ephemeral — they rebuild
  fresh each turn so config updates apply immediately to existing agents.

Level 2 does not import or depend on any LLM library. It invokes level 3 as a
grid operation and works with structured message maps. This makes it pluggable:
swap the level 3 operation to change provider, use a remote venue via federation,
or a test mock.

The level 3 operation to invoke is specified in `state.config.llmOperation`
(default: `langchain:openai`). The agent creator picks both the agent loop
strategy (level 2) and the LLM backend (level 3).

The other level 2 adapter, `goaltree:chat`, is documented separately in
[GOAL_TREE.md](./GOAL_TREE.md). It reads and writes the same `session.frames`
stack, with `frames[0]` persisting for the life of the session. Each transition
updates the stack atomically on the session record. It supports typed outputs
(schema-enforced `complete`/`fail`), opt-in harness tools, runtime tool
discovery via `more_tools`, and auto-compact nudges.

### 3.3 Level 3 — LLM Call (Single Step)

A single, stateless LLM invocation. Takes a list of messages (with tool
definitions if applicable), calls a specific LLM API, returns the response.

**Input:** `{messages: [...], tools?: [...], model?: string, url?: string}`

**Output:** An assistant message map:
```json
{"role": "assistant", "content": "Hello!"}
{"role": "assistant", "toolCalls": [{"id": "call_1", "name": "search", "arguments": "{...}"}]}
```

**Message types in the `messages` array:**
- `{role: "system"|"user", content: "..."}`
- `{role: "assistant", content: "...", toolCalls?: [{id, name, arguments}]}`
- `{role: "tool", id: "...", name: "...", content: "..."}`

Level 3 is a standard grid operation (e.g. `langchain:openai`, `langchain:ollama`).
It knows about HTTP clients, API serialisation, authentication, and provider
quirks. It does not know about agents, conversation history, or tool execution.

### 3.4 Transition Function Contract

The contract between level 1 and level 2:

**Input:**

| Field | Type | Description |
|-------|------|-------------|
| `agent-id` | string | The agent's identifier. |
| `state` | any | Current `state` from the agent record. Null on first run. |
| `tasks` | vector | Inbound task data resolved from the `tasks` index (jobId, input, status). |
| `pending` | vector | Outbound job data resolved from the `pending` index (jobId, status, result). |
| `session` | map | The picked session record: `{id, parties, meta, c, history, pending}`. See AGENT_SESSIONS.md §6.1. |

**Output:**

| Field | Type | Description |
|-------|------|-------------|
| `state` | any | Updated user-defined state. Written back to the agent record. |
| `result` | any | Summary of what happened. Recorded in the timeline entry and returned to callers. |
| `taskResults` | map? | Optional task completions: `{<jobId>: {status, output}}`. See §3.4.1. |

The transition function does not manage ts, status, timeline, sessions, or
scheduling. It declares what it has accomplished and the framework applies the
changes atomically.

Pending outbound jobs are not declared in the output — they are created as
side effects during execution via the async invoke tool (§3.4.2). Level 2
adds their Job IDs to `pending` directly.

The transition function must handle its own errors internally. If it throws, the
agent update treats this as a severe failure: the agent is suspended, all queues
are preserved, and the error is recorded. No timeline entry is written.

#### 3.4.1 Task Completion — Two Mechanisms

Tasks can be completed via two complementary mechanisms:

**1. Tool call during execution (LLM agents).** The transition function (level 2)
exposes `agent:completeTask` as a tool available to the LLM. When the LLM
decides to complete a task, it calls the tool during the tool call loop. The tool
completes the Job via JobManager immediately. This is the natural path for LLM
agents — task completion is just another tool call.

**2. Output declaration (non-LLM agents).** The transition function returns a
`taskResults` map in its output. The framework reads this after the transition
completes and applies each result to the corresponding Job via JobManager. This
path suits rule engines, workflows, and other transition functions that do not
use the tool call loop.

Both mechanisms may be used in the same run — tool-call completions happen during
execution, output-declared completions happen after. If the same task appears in
both, the tool-call result wins (it was applied first).

**Timeline capture:** All task completions from both mechanisms are merged and
recorded in the timeline entry's `taskResults` field. This ensures the timeline
is the complete record of what the agent accomplished in each run, regardless
of which mechanism was used. The framework collects tool-call completions during
execution and merges them with output-declared completions before writing the
timeline entry.

**Side effect semantics:** Task completions via tool calls are side effects —
they take effect immediately and are not rolled back if the transition function
subsequently fails. This is intentional: if the agent told a caller "done" then
crashed, the task *was* completed. The timeline entry will be missing (since the
run failed), but the Job result is durable. This matches how other side effects
(tool calls that send emails, invoke external APIs) behave.

#### 3.4.2 Async Invoke — Pending Jobs

Level 2 exposes an **async invoke** tool to the LLM. When called, it:

1. Invokes the specified operation asynchronously (creates a Job via JobManager).
2. Adds the Job ID to the agent's `pending` index via an atomic cursor update.
3. Registers a completion callback that calls `wakeAgent` (§4.6) when the
   job finishes.
4. Returns the Job ID to the LLM as the tool result.

The agent does not wait for the result — execution continues. On the next wake,
the framework resolves `pending` and passes the results to the transition
function.

Only jobs created through the async invoke tool appear in `pending`. Synchronous
tool calls, internal framework jobs, and any other jobs created indirectly are
not tracked — they are invisible to the agent.

This is the mechanism for delegated work, long-running computations, and HITL
requests (Phase D). The LLM decides what to fire-and-forget vs what to await.

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

Two layers: `state.config.context` (stable baseline, loaded every run) and
`state.context` (dynamic, mutable between runs). Config context loads first.

See [CONTEXT.md](./CONTEXT.md) for the full design: entry format, resolution
rules, load order, size considerations, and phasing.

### 3.6 Tool Palette

Agents declare their tools in `state.config.tools` — a curated list of
operation paths and harness tool names. Zero tools by default — a bare
chatbot just responds with text. Agents that want the built-in tool set
opt in via `defaultTools: true`.

```json
"tools": ["v/ops/covia/read", "v/ops/covia/write", "subgoal", "compact"]
```

Entries are either **operation paths** (resolved to LLM tool definitions
from the asset metadata: name, description, parameters) or **harness tool
names** (resolved to built-in tool definitions provided by the adapter).

#### Harness tools

All opt-in — include by name in `config.tools` if needed.

| Tool | Purpose |
|------|---------|
| `subgoal` | Delegate a sub-task to an isolated child frame |
| `complete` | Return structured result (auto-injected with typed outputs) |
| `fail` | Report failure with structured error (auto-injected with typed outputs) |
| `compact` | Archive conversation to a summary, freeing context space |
| `context_load` | Pin workspace data in context across turns |
| `context_unload` | Remove pinned data |
| `more_tools` | Discover and add operations at runtime |

**Typed outputs:** When `state.config.outputs` is set, `complete` and `fail`
are auto-injected with schema-enforced parameters — no need to list them in
`tools`. The LLM's tool call arguments must match the declared schema (enforced
by OpenAI `strictTools`). Text-only responses are rejected. Flattened: the
entire tool input IS the result — `complete({field: val})`, not
`complete({result: {field: val}})`.

**Auto-compact:** When conversation exceeds 20 turns and the agent has `compact`
in its tool set, a system hint nudges the LLM to compact before context overflow.

#### Operation tools

Any venue operation can be a tool. Common categories:

| Category | Examples |
|----------|---------|
| **Data** | `v/ops/covia/read`, `write`, `list`, `delete`, `append`, `slice`, `inspect`, `copy` |
| **Grid** | `v/ops/grid/run` (execute operations), `grid/invoke` (async) |
| **Agents** | `v/ops/agent/create`, `request`, `message`, `list`, `info`, `fork` |
| **Assets** | `v/ops/asset/store`, `get`, `list`, `pin`, `content` |
| **Schema** | `v/ops/schema/validate`, `infer` |

Each tool's description includes its operation path (e.g. `Operation: v/ops/covia/read`)
so the LLM can reason about provenance and discover related operations.

#### Capability enforcement

Tool calls are checked against the agent's `caps` before dispatch. Denied calls
return an error listing the agent's actual capabilities. The system prompt
includes a caps section so the LLM knows its boundaries upfront.

See [UCAN.md](./UCAN.md) for the capability model.

#### Additional tools

Level 2 also passes through any **external MCP tools** configured for the agent
(via `config.tools` or MCP server bindings). These are treated identically to
default tools in the tool call loop — the LLM sees a flat list of all available
tools regardless of source.

The agent creator controls the tool set:
- Default tools are always present (cannot be removed)
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
specified by the agent creator in `state.config.llmOperation`.

### 3.8 Credential Access

Operations that need API keys or other secrets resolve them from two sources,
in order:

1. **Input parameter** (optional `apiKey` field — testing only, will appear in
   plaintext in job results).
2. **User's secret store** (`/s/`), using the secret name declared in the
   operation's metadata (`operation.secretKey`).

The agent's `config` does not contain API keys. The operation metadata owns the
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

Initial state allows the creator to seed transition-function-specific configuration
(e.g. LLM provider, model, system prompt, capabilities) that the transition
function will read and preserve across runs. This keeps the framework `config`
clean of transition-function internals, and allows an agent to switch transition
functions.

**Inputs:**

| Field | Required | Description |
|-------|----------|-------------|
| `agentId` | yes | Identifier for the agent slot in the caller's `g/` namespace |
| `config` | no | Framework-level config (typically just `{operation: "..."}`) |
| `state` | no | Initial state (typically `{config: {systemPrompt, caps, tools, ...}}`) |
| `overwrite` | no | If true, an occupied slot is updated or wiped according to the slot resolution table below |

**Outputs:** `{agentId, status, created: bool, updated: bool}`. Exactly one of
`created` and `updated` is true on success.

**Slot resolution.** What happens when the target slot already contains an
agent depends on `overwrite` and the existing status:

| `overwrite` | Existing status | Result | Side effect | `created` | `updated` |
|---|---|---|---|---|---|
| any           | (empty)      | **CREATED** | fresh record initialised at SLEEPING | `true`  | `false` |
| absent / `false` | any state | **NOOP**    | record unchanged — idempotent re-run | `false` | `false` |
| `true`        | `SLEEPING`   | **UPDATED** | `config` and `state.config` replaced; `timeline`, `sessions`, `tasks`, `pending`, `status`, `ts` preserved | `false` | `true` |
| `true`        | `SUSPENDED`  | **UPDATED** | as SLEEPING; `error` and SUSPENDED status preserved (caller may resume separately) | `false` | `true` |
| `true`        | `RUNNING`    | **FAIL**    | job fails: "Cannot update agent X: currently RUNNING. Wait for the active transition to finish, or call agent:cancelTask first." | — | — |
| `true`        | `TERMINATED` | **CREATED** | `removeAgent` then fresh record — wipes timeline, sessions, tasks, pending, error | `true`  | `false` |

**Why RUNNING is rejected.** A transition currently in flight has already
captured the old `config` when it started. Mutating `config` mid-run produces
a Frankenstein agent — old policy for the current turn, new policy for the
next — that surfaces as a hard-to-debug "why is the agent using the old
policy" mystery. Forcing the caller to wait for SLEEPING (or explicitly
cancel) eliminates the race.

**Why SLEEPING / SUSPENDED are safe.** Both are dormant: no run loop is
executing a transition, no thread is reading the config. The update is
atomic on the agent record, so there is no partial-write window. The next
wake reads the new config.

**Why TERMINATED wipes.** TERMINATED is the explicit "throw it all out" signal.
If the caller wanted to preserve history, they would have updated in-place from
SLEEPING/SUSPENDED instead of deleting first. Reviving a TERMINATED slot
restarts at SLEEPING with a fresh timeline.

**Idempotent re-runs.** Without `overwrite`, calling `agent:create` on an
existing slot is a no-op. This makes setup scripts safe to re-run after partial
failures: the script does not need to track which agents already exist. To
update an existing agent on re-run, the script must opt in with `overwrite: true`.

### 4.2 Request

**Trigger:** `agent:request`

Submits a persistent task to an agent. The request Job itself IS the task — it
is left in STARTED state and its Job ID is added to the agent's `tasks` index.
No separate Job is created.

The agent decides when and how to fulfil the request. When the agent completes
the task (via `taskResults` output or a tool call), the framework completes
the request Job with the result. The caller can poll or SSE-subscribe to the
Job for status — the same Job they received from `invoke()`.

**Input:** `{agentId, input, ...}` — the `input` is stored in the Job.

**Output:** The Job stays in STARTED until the agent completes it. The caller
tracks progress via the Job ID returned by `invoke()`.

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
(manual state edit, diagnostics, resuming a stuck agent).

The `wait` parameter on trigger is a block on the loop's completion
future, not a result-await: it returns when the loop drains all
outstanding work and exits to SLEEPING. A transition that awaits an
async op (LLM, HTTP, HITL) simply keeps its virtual thread blocked on
`.join()` — there is no yield/resume handoff. A `SLEEPING` return means
the loop fully drained; a `RUNNING` snapshot from a bounded `wait` means
the loop is still processing. The transition always runs once per
trigger, even with no pending work, so an agent may act proactively on
a trigger.

**Run exclusion.** The in-memory `runningLoops`
(`ConcurrentHashMap<agentId, CompletableFuture>`) is the source of truth
for whether a loop is live. Launch is serialised by an atomic CAS via
`ConcurrentHashMap.compute` — the first wake installs a fresh future,
subsequent wakes observe the existing one and return it. A virtual
thread is started only by the winning wake. Mutations to the agent
record (`addTask`, `appendSessionPending`, run loop writes) use atomic
cursor updates directly — no shared lock.

The lattice `status` field is a durable hint; `runningLoops` is the
authoritative liveness signal. Phantom RUNNING (crash remnant or stale
write past a clean exit) is corrected inside `wakeAgent` before
installing a fresh slot (#64). `ConcurrentHashMap.remove(key, value)`
ensures a new run's future is never accidentally completed by an old
run's `finally` block.

**Sequence (per iteration):**

1. Read the current agent record: `sessions`, `tasks`, `pending`, `state`.
2. If not the first iteration and nothing to process (no tasks and no
   session with pending messages and no scheduled wake due): break out
   of the loop. Pending jobs are passed through but do not alone
   trigger a run.
3. Resolve job data from JobManager.
4. Invoke the transition function (§3.2) with `agent-id`, current `state`,
   `tasks`, `pending` (with resolved statuses), and the picked `session`.
   The virtual thread blocks on `.join()` of the returned future — no
   lock is held; other agents' loops run independently.
5. On success — **merge with current state** atomically on the agent record:
   - Re-read the record inside the merge (not the stale snapshot from step 1).
   - Remove completed tasks from the **current** `tasks` index. This prevents
     overwriting tasks added by concurrent task submissions during the
     transition.
   - Drain the presented count of pending messages from the picked
     session's `pending` queue. Messages appended concurrently during the
     transition (including the agent's own self-chat / self-message) are
     preserved beyond the presented count and picked up by the next
     iteration's work check.
   - Update `state` from the returned value.
   - Append timeline entry with full audit data (§2.4).
   - Status is set to `RUNNING` if work remains, else `SLEEPING`.
6. Complete any picked chat Job and drain parked task completions from
   `agent:complete-task` / `agent:fail-task` calls made during the
   transition — both strictly AFTER the merge, so external awaiters see
   timeline and state writes first.
7. Loop back to step 1. The top-of-loop check at step 2 is the sole
   exit gate.
8. On exception:
   - Leave `state`, `tasks`, `pending`, and `sessions` unchanged.
   - Set status → `"SUSPENDED"`, set `error`.
   - Fail all pending task Jobs and clear parked completions for this
     agent.
   - Note: task completions made via tool calls during execution are
     **not** rolled back (§3.4.1).

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
| **Update** | `agent:create overwrite:true` | Replace `config` and `state.config` in place; preserve timeline, sessions, tasks, pending, status, error. See §4.1 slot resolution table. | SLEEPING, SUSPENDED, TERMINATED (wipes) | unchanged (or SLEEPING if was TERMINATED) |
| **Suspend** | run loop error (§4.4) | Set `error`, set status → SUSPENDED. State, tasks, pending, sessions unchanged. | RUNNING (internal) | SUSPENDED |
| **Resume** | `agent:resume` | CAS SUSPENDED→SLEEPING via `tryResume`, clear `error`. Then wake via §4.6 if there is work. | SUSPENDED only | SLEEPING |
| **Suspend (manual)** | `agent:suspend` | Set status → SUSPENDED with caller-supplied reason. Stops the agent from being woken. | SLEEPING | SUSPENDED |
| **Trigger** | `agent:trigger` | Wake the agent and (optionally) wait for the run loop to drain. Fails on TERMINATED. See §4.6. | SLEEPING, RUNNING | RUNNING then SLEEPING |
| **Cancel task** | `agent:cancelTask` | Remove a pending task from the agent's `tasks` index. Does not affect the run loop directly. | any except TERMINATED | unchanged |
| **Delete** | `agent:delete` | Default: set status → TERMINATED (record retained for audit). With `remove:true`: physically remove the lattice slot. | any | TERMINATED (or absent) |
| **Fork** | `agent:fork` | Create a NEW agent at a different `agentId` from this one's config + optional state + optional timeline. The source is untouched; the target slot must be empty unless `overwrite:true` and TERMINATED. | source must not be TERMINATED | new agent SLEEPING |

**All mutations are atomic.** Each operation is a single compare-and-swap
on the agent's lattice cell, so concurrent operations against the same
agent serialise cleanly without explicit locking. The run loop's cycle
is a read → transition → merge sequence; concurrency with external
mutations is handled by reading the current record inside the merge
(not the pre-transition snapshot) so late writes are never lost.

**Status invariants:**

- Once an agent is TERMINATED, it cannot transition to any other status without
  going through `agent:create overwrite:true`, which wipes the record. There is
  no "un-terminate".
- RUNNING is a transient state held only while the run loop's transition function
  is executing or its merge step is pending. It is the only status under which
  config mutations are rejected.
- SUSPENDED preserves all queues so a resumed agent picks up where it stopped.
  In-place updates while SUSPENDED do **not** auto-resume — that is the caller's
  decision (typically: fix the cause, update the config, then `agent:resume`).

### 4.6 Scheduling — `wakeAgent`

Scheduling is **lattice-native**: the agent's `status` field and the contents
of `sessions`/`tasks` are the only inputs to the wake decision. There are no
separate Java-side running/wake flags that could drift from the lattice.

**`wakeAgent(agentId, ctx)`** is the single entry point for all agent wakes.
It is called by `agent:request`, `agent:message`, and async job completion
callbacks. The logic (atomic CAS on `runningLoops: ConcurrentHashMap` via
`compute()`):

1. Inside `runningLoops.compute(agentId, ...)`:
   - If slot already populated → live loop exists; return existing completion
     (caller observes RUNNING; new work is in the lattice and the live loop
     will see it on its next iteration).
   - Otherwise → read the agent's `status` from the lattice.
   - If `status == RUNNING` with no live launcher → phantom (#64); correct to
     SLEEPING under the same atomic update before launching.
   - If no work (no session with pending messages and no tasks) → return null
     (leave slot empty).
   - Resolve `config.operation` → if null, return null.
   - Set status → RUNNING on the lattice.
   - Create a fresh `CompletableFuture<ACell>`, put it in the slot, and launch
     `executeRunLoop` on a `Thread.ofVirtual()`.
2. The launcher wins the slot atomically; concurrent callers observing the
   populated slot simply return the same completion future.

An agent is eligible to run when any of:

- A new task has been added to `tasks` (via `agent:request`)
- A pending outbound job has completed or failed
- A new message has been delivered to a session's `pending` queue (via `agent:message`)

**No lost wakeups.** `addTask` / `appendSessionPending` write to the lattice
atomically, then call `wakeAgent`. The loop's top-of-iteration `hasWork` check
reads the current lattice. If work landed while a loop was running, the next
iteration sees it. If work landed after the loop wrote SLEEPING and removed
its slot, the `finally`-block double-check (§4.4) re-launches; failing that,
the next `wakeAgent` from the writer finds the slot empty and launches.

**No double runs.** `ConcurrentHashMap.compute()` serialises the
empty-slot → launch transition. Only one thread can populate the slot; all
others observe it populated and return the existing completion.

**Configurable sleep:** `config.sleepInterval` (milliseconds). If set, the agent
wakes periodically even without events. Useful for polling-style agents.
Exponential backoff when idle is a future refinement.

The scheduler is an internal venue concern — its implementation may change
without affecting the agent contract or MCP interface.

---

## 5. Merge Semantics

### 5.1 Last Writer Wins

The LWW lattice compares `ts` values. The record with the later timestamp wins
unconditionally.

- **Monotonic:** ts only increases on the hosting venue → state only advances.
- **Complete:** the winner is a full consistent snapshot, never a mix of fields.
- **Simple:** standard LWW lattice, no custom merge function.

### 5.2 Why One Value?

All writes to an agent are serialised on one venue through atomic
compare-and-swap on the agent's lattice slot. Message delivery, task
addition, and run loop merges all contend against the same slot, so there
are no lost updates even though multiple threads may write concurrently.

The run loop never holds a lock. It reads a snapshot at the top of each
iteration, runs the transition (which may take seconds or minutes), then
merges the result atomically. Concurrent task additions and session
message appends proceed while the transition runs; their writes are
preserved because the merge reads the *current* sessions/tasks (not the
stale pre-transition snapshot).

Cross-venue sync is replication: the venue hosting the agent always has the latest
ts. Replicas receive the complete state.

Per-field merge (a previous design) was wrong because it could produce Frankenstein
states — status from venue A, timeline from venue B, session fragments from both. An
inconsistent state that never actually existed. With a single atomic value, the winner
is always a state that genuinely existed on the hosting venue.

---

## 6. Design Decisions and Open Questions

### 6.1 Recovery (on hold)

An agent in `"RUNNING"` status after a venue restart was mid-transition.
`RUNNING` may be resumable if the transition function supports it, so no
automatic recovery to `"SUSPENDED"` for now. Revisit when transition functions
can declare resumability.

### 6.2 Effects

Transition functions implement their own effects. A transition function that
needs to send messages to other agents or invoke grid operations does so
directly during execution (it has access to the engine like any adapter).

Agent-level effect handling (an `actions` field in the output, processed by the
framework) is deferred. It may be useful for sandboxed or auditable agents but
adds complexity that is not needed for the initial LLM agent.

### 6.3 Human-in-the-loop (HITL) requests

When an agent needs human input, it creates an outbound Job targeting the human
user. This Job appears in the user's **request queue** at `/h/<job-id>` in the
user's lattice namespace. The human completes the Job (via MCP or REST), which
appears as a completed pending job on the agent's next wake.

HITL is not a special mechanism — it reuses the same Job infrastructure. The
difference is that the executor is a human rather than an adapter or agent.

**User request namespace:**

```
:user-data → <DID> → "h" → <job-id> → job reference
```

The `/h/` namespace is analogous to `/g/` (agents) and `/s/` (secrets) but
holds Job IDs for requests that the human user is expected to complete. MCP
clients can list and respond to these requests.

**TODO:** Define the MCP-facing operations for HITL: listing pending requests,
responding to requests, rejecting requests. This is a Phase D concern.

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

API keys are not stored in agent config. The operation metadata declares a
`secretKey` name (e.g. `"OPENAI_API_KEY"`); at runtime the adapter looks this
up in the caller's encrypted secret store (`/s/`). An optional plaintext
`apiKey` input parameter exists for testing only. No environment variable
fallback — credentials must be in the secret store for production use.

---

## 7. Phasing

See GRID_LATTICE_DESIGN.md §12 for the full roadmap.

| Phase | Focus | Status |
|-------|-------|--------|
| **0** | Per-user namespace (`"g"`, `"s"`), SecretStore | ✓ Complete |
| **A** | AgentState wrapper, AgentAdapter (create/message/run), lattice restructure | ✓ Complete |
| **B** | LLM transition function (`llmagent:chat`), conversation history, secret store integration, three-level architecture (§3) | ✓ Complete |
| **B2** | Decouple level 2 from LangChain4j — level 3 via grid operation, message-format API, tool call loop | ✓ Complete |
| **B3a** | Structured output / responseFormat for level 3, LangChainAdapter unit tests | ✓ Complete |
| **B3b** | Tool parameter schema mapping refinements (enum, array items, nested objects) | ✓ Complete |
| **B4** | MCP-first agent experience: default transition op from config, result in run output | ✓ Complete |
| **B5** | Task-based agent model: `agent:request`, tasks/pending queues, scheduling | ✓ Complete |
| **B6** | Agent query/list operations: `agent:query`, `agent:list`, RequestContext refactor, Index for tasks/pending | ✓ Complete |
| **B7** | Lattice-native run loop: per-agent lock, status-based exclusion, merge-at-write-time, `wakeAgent`, `Job.awaitResult(timeout)` | ✓ Complete |
| **B10** | Agent workspace CRUD: `/w/`, `/o/`, `/h/` namespaces, deep paths, vector indexing, JSONValueLattice, DID URL cross-user paths, default tools | ✓ Complete |
| **B11** | `/o/` operation resolution, `agent:create` default tool, `covia:adapters`, langchain cleanup, JobManager simplification | ✓ Complete |
| **C1** | UCAN proofs: venue-signed tokens (`ucan:issue`), per-request proof verification, full DID URL resources, `Capability.covers()`, cross-user reads with valid proof chain | ✓ Complete |
| **C2** | Delegation chains — proof chain walking, attenuation validation, agent sub-delegation, revocation | Planned |
| **D** | HITL requests (`/h/` namespace), cross-user messaging | Planned |
| **E** | Agent forking, cross-venue migration, federated UCAN validation | Planned |

---

## 8. Architectural Direction

**Status:** Proposed — April 2026. §1–§7 describe the current code; §8 describes the target. Each phase below promotes its content into the body and removes the corresponding stub. When §8 is empty, the body alone is the spec.

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
| Scheduled fire | Scheduler timer reaches `wakeTime` (B8.8); includes `config.sleepInterval` periodic wake | no | no |
| **Trigger** | `agent:trigger` MCP op | no | optional loop-drain `wait`, not a result-await |
| Self-wake | A previous step wrote new work to the agent's own queues | (whatever the previous step wrote) | n/a |

### 8.3 Gaps in the current design

#### 8.3.1 `state` is a vestigial pure-FSM accumulator

The step input today carries `state`; the output writes `state` back. In current code:

- `LLMAgentAdapter` reads `state` to extract `loads` (one slot), then writes back `{config, loads}`. The `config` field is a duplicate of the agent record's `K_CONFIG`.
- `GoalTreeAdapter` round-trips `state.config` solely to preserve config across cycles — explicit comment in `GoalTreeAdapter.java:473-476`: *"Wiping it would silently strip caps/schema enforcement on the second invocation."*

Pure duplication. The record's `K_CONFIG` is canonical and is already passed into the step input as `KEY_CONFIG` separately. The only legitimate per-step persistent state is `loads` (LLM only), which can move to a dedicated lattice slot.

#### 8.3.2 Cycle scope is over-fitted to "a picked task"

The framework picks at most one task and at most one session per cycle, then bakes them into the step input via `formatPickedTask` / `pickSessionForCycle`. This fits `agent:request` cleanly, but distorts every other wake event:

- **Trigger** fires with no task and no session — the framework synthesises empty inputs.
- **Scheduled fire** fires with no payload — the agent is woken to "think now."
- **Pending completion** has no task to pick.
- **Multi-task agents** (orchestrators, batch processors) cannot consume multiple queued tasks per cycle.

The step adapter ends up branching on "do I have a task? a session? neither?" — branching that should not be in the step adapter, and that the framework should not pre-perform.

#### 8.3.3 Wire shapes leak framework concerns into adapters

`formattedTasks`, `resolvedPending`, `effectiveMessages`, `presentedSessionPendingCount` are framework-internal scheduling artefacts. Step adapters depend on them. Changing the framework's wire format changes every step adapter. The right boundary is: framework gives the adapter a context, adapter reads typed lattice data via accessors.

### 8.4 Target principles

(Replaces §1.)

1. **Single atomic value.** Unchanged. The agent record is one map.
2. **Last writer wins.** Unchanged.
3. **Single venue writes.** Unchanged.
4. **Two layers, not three.** L1 (run loop) drives cycles and persists results. L2 (step adapter) does one cycle's worth of reasoning. L3 is outside the harness — a step adapter may invoke an L3 provider, but that's the adapter's business, not a layer of the contract. The framework never inspects the step adapter's reasoning; the step adapter never manages framework fields.
5. **Lattice is the state.** No FSM accumulator threaded through the step. Persistent agent state (config, sessions, tasks, pending, timeline) lives on the lattice. The step adapter reads what it needs via context and writes via venue ops.
6. **Step must succeed.** A failing step suspends the agent (#88). Same fail-fast semantics; the step adapter can return `error` to fail explicitly.

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
  error?:    ACell,          // fail-fast — suspend, drain queue, fail callers (#88)
  frames?:   AVector<ACell>, // optional session-frames replacement (goal-tree pattern)
  done?:     boolean         // explicit "no more work to do this run" signal
}
```

No `state` field. No `taskResults` field — task completions go through the existing `agent:complete-task` venue op (one mechanism, not two — see §8.5.5).

#### 8.5.4 Stop condition

Currently the run loop iterates while `hasWork(agent)`. Target: the step adapter signals `done: true` when it has nothing left to do. The framework re-invokes the step until `done: true` arrives, with `MAX_LOOP_ITERATIONS` retained as a misbehaving-agent safety cap.

Why adapter-driven: the framework cannot reliably tell "is there more work?" from lattice state alone. A scheduled-fire step might decide nothing changed and return immediately. A multi-task step might process several queued tasks per cycle and signal done when the queue empties. A goal-tree harness might need to plan even with no queued work.

#### 8.5.5 Task completion — one mechanism

§3.4.1 currently describes two mechanisms (tool-call during execution; output declaration). Target: only the tool-call mechanism. Step adapters that don't run a tool-call loop call the same venue op (`agent:complete-task`) directly. The output `taskResults` field is retired, removing `buildTaskResultsFromDeferred` and the two-source merge.

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
   7. On `error`: fail-fast (existing #88 path — suspend, drain, fail callers).
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
| Step output field `taskResults` | removed | Use `agent:complete-task` venue op |
| `state.config` (LLM/goaltree) | merged into record `K_CONFIG` | Already canonical; just stop duplicating |
| `state.loads` (LLM) | dedicated slot (`K_LOADS` or adapter namespace) | Only legitimate per-agent persistent state |
| Framework picks task/session each cycle | adapter reads queue and decides | Cycle scope is the agent, not a task |
| Loop exits on `hasWork == false` | loop exits on `done: true` from step | Adapter-driven stop condition |
| Two task-completion mechanisms (§3.4.1) | one mechanism (venue op) | Simpler bookkeeping |
| `RequestContext` carries `taskId`, `sessionId` | dropped | Adapter resolves these from the lattice |

### 8.8 Phased plan

Each phase is a self-contained PR with a doc commit that promotes its content from §8 into the main body and removes the corresponding stub.

#### Phase D-1 — Step input simplification

- Drop `tasks`, `pending`, `messages`, `session`, `config` from the step input wire shape.
- Step adapters read via `agent.*` accessors and `RequestContext`.
- Keep `state` in the step input/output for now to bound the change.
- Doc: rewrite §3.4 input table; update §4.4 step 4.
- Tests: `LLMAgentAdapterTest`, `AgentAdapterTest` exercise the new wire shape.

#### Phase D-2 — Output narrowing + done signal

- Step output becomes `{response?, error?, frames?, done?}`.
- Drop `state` from the output.
- Retire `taskResults` declaration; step adapters use `agent:complete-task` directly.
- Loop stops on `done: true` (or `MAX_LOOP_ITERATIONS`).
- Doc: rewrite §3.4 output table; simplify §3.4.1.
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

2. **Where does `loads` live post-D-3?** Options:
   - `K_LOADS` on the agent record (peer of `K_CONFIG`)
   - Under an `adapters/` namespace map on the agent record
   - In the session record (per-session loads — different semantics, bigger redesign)

   Lean: `K_LOADS` on the record for parity with `K_CONFIG`.

3. **Backwards compatibility for persistent agents.** Covia is alpha; assume any persistent agents can be re-created. If we ship a venue with persisted agents that have `state.config`, decide whether D-3 reads ignore-and-rewrite or fail-loudly.

4. **Does Phase D-4 break MCP clients?** The MCP-facing operations (`agent:request`, `agent:message`, `agent:chat`, `agent:trigger`) are unchanged — only the framework↔harness contract moves. External callers should see no difference.

5. **Class renames.** `LLMAgentAdapter` → `LLMHarness`? `GoalTreeAdapter` → `GoalTreeHarness`? Defer; doc terminology can lead the code.
