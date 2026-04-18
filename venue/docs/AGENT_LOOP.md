# Agent Design — Data Structure and Transitions

Design for Covia agent state and the transitions that mutate it.

**Status:** April 2026

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
   agent runs, config updates — are serialised on that venue via atomic per-agent
   cursor updates (`cursor.updateAndGet`). Cross-venue sync is replication: the
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
| `state` | any | User-defined state. Opaque to the framework. Passed to and returned from the transition function. Transition-function-specific configuration (e.g. LLM provider, model) lives here, not in `config`. Set at creation via optional initial state. Note: `state.transcript` is no longer used — `session.history` is the canonical conversation record. |
| `tasks` | index | `Index<Blob, ACell>` of inbound request Job IDs. Persistent until resolved. Ordered by Job ID. |
| `pending` | index | `Index<Blob, ACell>` of outbound Job IDs the agent is waiting on. Ordered by Job ID. |
| `sessions` | index | `Index<Blob, ACell>` of sessions. Each session contains history, pending messages, metadata, and per-session state (`c/`). See AGENT_SESSIONS.md. |
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
`session.history` as turns. See AGENT_SESSIONS.md for the session model.

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
Level 1: Agent Update          agent:trigger (AgentAdapter)
  │  manages sessions, timeline, status
  │  invokes level 2 as a grid operation
  ▼
Level 2: Agent Transition      llmagent:chat (LLMAgentAdapter)
  │  manages conversation history, tool call loop, state
  │  invokes level 3 as a grid operation
  ▼
Level 3: LLM Call              langchain:openai (LangChainAdapter)
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
- Reads the **session history** from `input.session.history` — turn envelopes
  `{role, content, ts, source}` from prior transitions, converted to LLM
  `{role, content}` messages (`ts`/`source` stripped)
- Builds a **per-turn LLM context** with FRESH ephemeral additions every turn:
  - System message (identity prompt + lattice cheat sheet, rebuilt fresh)
  - Resolved context entries
  - Resolved loaded paths
  - `[Context Map]` budget summary
  - Then appends the session history
  - Then appends pending job results and new messages
- Invokes level 3 (LLM call) as a grid operation
- Handles tool call responses: execute tools, feed results back, call level 3
  again (loop until the LLM returns a text response or a limit is reached)
- The assistant's final response is returned to the framework, which appends
  it as a turn to `session.history`
- No per-adapter transcript persistence — `session.history` is the canonical
  record, managed by the framework. The system prompt, context entries, and
  `[Context Map]` are NEVER persisted — they rebuild fresh each turn so
  updates apply immediately to existing agents.

Level 2 does not import or depend on any LLM library. It invokes level 3 as a
grid operation and works with structured message maps. This makes it pluggable:
swap the level 3 operation to change provider, use a remote venue via federation,
or a test mock.

The level 3 operation to invoke is specified in `state.config.llmOperation`
(default: `langchain:openai`). The agent creator picks both the agent loop
strategy (level 2) and the LLM backend (level 3).

The other level 2 adapter, `goaltree:chat` (GoalTreeAdapter), is documented
separately in [GOAL_TREE.md](./GOAL_TREE.md). It reads `session.history` for
cross-transition context (same as LLMAgentAdapter). Each transition builds a
fresh root frame; the frame stack is not persisted across transitions. It
supports typed outputs (schema-enforced `complete`/`fail`), opt-in harness
tools, runtime tool discovery via `more_tools`, and auto-compact nudges.

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
operation paths and harness tool names. Set `defaultTools: false` to disable
the legacy 18-tool default set. Zero tools by default — a bare chatbot just
responds with text.

```json
"tools": ["v/ops/covia/read", "v/ops/covia/write", "subgoal", "compact"]
```

Entries are either **operation paths** (resolved via `ContextBuilder.buildConfigTools`
to LLM tool definitions with name, description, parameters from the asset
metadata) or **harness tool names** (resolved by `GoalTreeAdapter.resolveHarnessTools`
to built-in tool definitions).

#### Harness tools (GoalTreeAdapter)

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
captured the old `state.config` at the start of `processGoal` (level 2). Mutating
`config` mid-run produces a Frankenstein agent — old prompt for the current
turn, new prompt for the next — that surfaces as a hard-to-debug "why is the
agent using the old policy" mystery. Forcing the caller to wait for SLEEPING (or
explicitly cancel) eliminates the race.

**Why SLEEPING / SUSPENDED are safe.** Both are dormant: no run loop is
executing a transition, no thread is reading the config. The
`updateConfigAndState` mutation is atomic (under `cursor.updateAndGet`), so
there is no partial-write window. The next `wakeAgent` reads the new config.

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
5. On success — **merge with current state** via `mergeRunResult`, a
   single atomic `cursor.updateAndGet` on the agent record:
   - Re-read the record inside the CAS (not the stale snapshot from step 1).
   - Remove completed tasks from the **current** `tasks` index (not the
     stale snapshot). This prevents overwriting tasks added by concurrent
     `addTask` calls during the transition.
   - Drain the presented count of pending messages from the picked
     session's `pending` queue. Messages appended by concurrent
     `appendSessionPending` calls during the transition (including the
     agent's own self-chat / self-message) are preserved at indices
     beyond the presented count and picked up by the next iteration's
     `hasWork` check.
   - Update `state` from the returned value.
   - Append timeline entry with full audit data (§2.4).
   - Status is set to `RUNNING` if work remains, else `SLEEPING`.
6. Complete any picked chat Job's `CompletableFuture` and drain
   `deferredCompletions` to finish task Jobs parked by
   `agent:complete-task` / `agent:fail-task` — both strictly AFTER the
   merge, so external awaiters see timeline and state writes first.
7. Loop back to step 1. The top-of-loop check at step 2 is the sole
   exit gate.
8. On exception:
   - Leave `state`, `tasks`, `pending`, and `sessions` unchanged.
   - Set status → `"SUSPENDED"`, set `error`.
   - Fail all pending task Jobs and clear parked completions for this
     agent.
   - Note: task completions made via tool calls during execution are
     **not** rolled back (§3.4.1).

After the loop exits (break or exception), a `finally` block removes
the agent from `runningLoops` (via `remove(key, value)` so a concurrent
new run is not clobbered) and re-checks `hasWork` on the agent. If
work landed during exit, a fresh `wakeAgent` call is issued — any wake
whose lattice write preceded this re-check triggers a new loop; any
wake landing after sees an empty slot and wins its own CAS.

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

**All mutations are atomic.** Each operation is a single `cursor.updateAndGet`
on the agent's lattice cell, so concurrent operations against the same agent
serialise cleanly without explicit locking. The run loop's cycle is a
read → transition → merge sequence; concurrency with external mutations
is handled by reading the current record inside `mergeRunResult`'s CAS
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

All writes to an agent are serialised on one venue through atomic cursor
updates (`cursor.updateAndGet`). Message delivery, task addition, and run loop
merges are all compare-and-swap operations against the same lattice slot, so
there are no lost updates even though multiple threads may write concurrently.

The run loop never holds a lock. It reads a snapshot at the top of each
iteration, runs the transition (which may take seconds or minutes), then
merges the result via a single atomic `cursor.updateAndGet`. Concurrent
`addTask` and `appendSessionPending` calls proceed while the transition runs;
their writes are preserved because `mergeRunResult` reads the *current*
sessions/tasks inside the CAS (not the stale pre-transition snapshot).

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
