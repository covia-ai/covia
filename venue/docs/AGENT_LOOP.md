# Agent Design — Data Structure and Transitions

Design for Covia agent state and the transitions that mutate it.

**Status:** Draft — March 2026

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
   agent runs, config updates — are serialised on that venue via a per-agent lock.
   Cross-venue sync is replication: the more-recent state replaces the stale one.

4. **Three levels.** The agent update (level 1) manages framework bookkeeping:
   status, timeline, inbox. The agent transition (level 2) manages domain logic:
   conversation history, tool call loops, state. The LLM call (level 3) is a single
   stateless invocation. Each level is a grid operation, pluggable independently.
   The framework never inspects user state; lower levels never manage framework fields.

5. **Transition function must succeed.** A failing transition function is a severe
   bug — it suspends the entire agent. The agent update restores the inbox and
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
| `state` | any | User-defined state. Opaque to the framework. Passed to and returned from the transition function. Transition-function-specific configuration (e.g. LLM provider, model) lives here, not in `config`. Set at creation via optional initial state. |
| `tasks` | index | `Index<Blob, ACell>` of inbound request Job IDs. Persistent until resolved. Ordered by Job ID. |
| `pending` | index | `Index<Blob, ACell>` of outbound Job IDs the agent is waiting on. Ordered by Job ID. |
| `inbox` | vector | Ephemeral messages awaiting processing. Drained on successful run. |
| `timeline` | vector | Append-only log of transition records (§2.3). Grows with each successful agent run. Timestamped for audit. |
| `caps` | map | Capability sets (placeholder — Phase C enforcement) |
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

**Messages** (`inbox`) are ephemeral notifications. They inform the agent of
events but do not represent commitments. Messages are consumed on each run and
recorded in the timeline. They may or may not trigger agent action.

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
| `messages` | vector | The inbox messages passed to the transition function. |
| `result` | any | The `result` returned by the transition function. |
| `taskResults` | map? | Task completions from output declarations (§3.4.1). `{<jobId>: {status, output}}`. |

The output state is not stored in the timeline entry — it is the `state` field in
the agent record (for the latest run) or the `state` field in the next timeline
entry (for earlier runs). This avoids redundant storage.

Timeline entries are only appended on success. On error, no timeline entry is
written — the error is recorded in the agent record's `error` field and all
queues are preserved for retry.

---

## 3. Three Levels

The agent system separates concerns into three levels. Each level is a grid
operation, pluggable and replaceable independently.

```
Level 1: Agent Update          agent:trigger (AgentAdapter)
  │  manages inbox, timeline, status
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

Reads inbound tasks, pending job completions, and inbox messages. Invokes the
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
- Reconstructs conversation history from `state.history`
- Appends inbox messages as user turns
- Invokes level 3 (LLM call) as a grid operation
- Handles tool call responses: execute tools, feed results back, call level 3
  again (loop until the LLM returns a text response or a limit is reached)
- Appends the assistant response to history
- Returns updated state (with config preserved) and a result summary

Level 2 does not import or depend on any LLM library. It invokes level 3 as a
grid operation and works with structured message maps. This makes it pluggable:
swap the level 3 operation to change provider, use a remote venue via federation,
or a test mock.

The level 3 operation to invoke is specified in `state.config.llmOperation`
(default: `langchain:openai`). The agent creator picks both the agent loop
strategy (level 2) and the LLM backend (level 3).

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
| `messages` | vector | The inbox. Ephemeral message records. |

**Output:**

| Field | Type | Description |
|-------|------|-------------|
| `state` | any | Updated user-defined state. Written back to the agent record. |
| `result` | any | Summary of what happened. Recorded in the timeline entry and returned to callers. |
| `taskResults` | map? | Optional task completions: `{<jobId>: {status, output}}`. See §3.4.1. |

The transition function does not manage ts, status, timeline, inbox, or
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
2. Adds the Job ID to the agent's `pending` index (inside the per-agent lock).
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

### 3.5 Default Tool Palette

Level 2 provides a set of tools to the LLM during the tool call loop. These are
the agent's interface to the outside world. Each tool maps directly to an MCP
tool definition (name, description, JSON schema for args).

The tool palette is split into **default tools** (always available),
**workspace tools** (available by default for own namespace), and
**capability-gated tools** (require explicit `caps` — Phase C).

#### Default tools

These are provided by the Level 2 loop to the LLM agent. Level 2 handles
execution and maps results back to the tool call loop.

**Task management:**

| Tool | Description | Args | Returns |
|------|-------------|------|---------|
| `complete_task` | Complete an inbound task with a result | `{jobId, output}` | `{status: "COMPLETE"}` |
| `fail_task` | Reject/fail an inbound task with a reason | `{jobId, reason}` | `{status: "FAILED"}` |

These are side effects — they complete the Job immediately via JobManager.
Not rolled back if the transition subsequently fails (§3.4.1).

**Communication:**

| Tool | Description | Args | Returns |
|------|-------------|------|---------|
| `message_agent` | Send an ephemeral message to another agent's inbox | `{agentId, message}` | `{delivered: true}` |
| `request_agent` | Submit a persistent task to another agent | `{agentId, input, wait?}` | Task job status |

#### Workspace and introspection tools

These are in the default tool palette. They operate on the authenticated
user's own lattice namespace. Paths support mixed map/vector navigation
(e.g. `w/records/1/name`). Writable namespaces: `w/` (workspace) and
`o/` (operations). All namespaces are readable.

| Tool | Description | Args | Returns |
|------|-------------|------|---------|
| `covia:read` | Read a value at any lattice path | `{path, maxSize?}` | `{exists, value}` or `{exists, truncated, size}` |
| `covia:write` | Write a value to `/w/` or `/o/` at any depth | `{path, value}` | `{written: true}` |
| `covia:delete` | Delete a key from `/w/` or `/o/` at any depth | `{path}` | `{deleted: true}` |
| `covia:append` | Append an element to a vector in `/w/` or `/o/` | `{path, value}` | `{appended: true}` |
| `covia:slice` | Read a paginated slice from a collection | `{path, offset?, limit?}` | `{type, values, count, offset}` |
| `covia:list` | Describe structure at a path (type, count, keys) | `{path, limit?, offset?}` | `{type, count, keys?}` |
| `covia:functions` | List all operations available on this venue | `{}` | `{functions: [...]}` |
| `covia:describe` | Get full metadata for a named operation | `{name}` | Asset metadata |

**Deep path navigation:** Paths like `w/data/nested/field` create intermediate
maps on write. Paths into vectors use integer indices (e.g. `w/events/0`).
Type-aware navigation resolves maps by string key and vectors by parsed index.

**`maxSize` guard:** `covia:read` defaults to 1MB max response. If the value's
encoding exceeds the limit, returns `{exists: true, truncated: true, size: N}`
instead — use `covia:list` to inspect or `covia:slice` to page.

#### Capability-gated tools (Phase C)

These require explicit capabilities in the agent's `caps` field. Available
once UCAN capability enforcement is implemented. The current workspace tools
(`covia:write`, `covia:delete`, `covia:append`) restrict to `/w/` and `/o/`
namespaces — UCAN enforcement will replace this static check with per-agent
capability delegation.

| Tool | Caps required | Description |
|------|---------------|-------------|
| `read_secret` | `/secret/decrypt` | Decrypt a secret (adapter handles plaintext) |
| `spawn_agent` | `/crud/write` on `/g/` | Create a new agent |
| `fork_agent` | `/agent/fork` | Fork an existing agent |
| `delegate` | `/ucan/delegate` | Sub-delegate UCAN capabilities |
| cross-user read | `/crud/read` on other DID | Read another user's `/w/` namespace |

#### Additional tools

Level 2 also passes through any **external MCP tools** configured for the agent
(via `config.tools` or MCP server bindings). These are treated identically to
default tools in the tool call loop — the LLM sees a flat list of all available
tools regardless of source.

The agent creator controls the tool set:
- Default tools are always present (cannot be removed)
- External MCP tools are added via agent configuration
- Capability-gated tools appear only when the agent has the required `caps`

### 3.6 Invocation

Each level invokes the next as a grid operation — same dispatch path as any other
operation in the venue. This means any level can be:
- A local adapter operation (e.g. `llmagent:chat`, `langchain:openai`)
- A remote venue operation via federation
- A composite operation via orchestration
- A test mock (e.g. `test:echo`)

The level 2 operation is specified in the agent's `config.operation` field
(default set at creation, overridable per-run). The level 3 operation is
specified by the agent creator in `state.config.llmOperation`.

### 3.7 Credential Access

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
input (or null), empty tasks/pending indices, empty inbox, empty timeline, no error.

The `config` map supports:
- `operation` — default transition operation (e.g. `"llmagent:chat"`)
- Other framework configuration as needed

Initial state allows the creator to seed transition-function-specific configuration
(e.g. LLM provider, model, system prompt) that the transition function will read
and preserve across runs. This keeps the framework `config` clean of
transition-function internals, and allows an agent to switch transition functions.

**Idempotent:** If the agent record already exists, create is a no-op.

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

Reads current agent record, appends the message to `inbox`, writes agent record.
Then wakes the agent (§4.6) — if the agent has a `config.operation`, this
triggers a run automatically.

Messages are ephemeral notifications — they do not create Jobs and are not
tracked for completion. Use `agent:request` for work items that need a response.

**Fails if:** the agent does not exist, or status is `"TERMINATED"`.

### 4.4 Run — Agent Update (Internal)

**Trigger:** `wakeAgent` (§4.6), not directly by MCP users.

Also callable via `agent:trigger` for synchronisation and manual control. The
trigger has **wait-for-completion** semantics: it returns when the agent has
finished processing (reached SLEEPING). If the agent is already RUNNING, the
trigger parks on a completion future and completes when the current run finishes.
The trigger always invokes the transition function, even with no pending work —
the transition decides what to do (it may act proactively).

**Run exclusion.** The agent's `status` field in the lattice is the source of
truth for whether a run is active. A per-agent lock serialises the status check
with all record mutations (`addTask`, `deliverMessage`, run loop writes). There
are no separate Java-side running/wake flags — all decisions are based on lattice
data.

A per-agent `CompletableFuture` is created when the agent transitions to RUNNING
(whether via `wakeAgent` or `agent:trigger`). Multiple triggers can park on the
same future. When the loop finishes, all waiting triggers are notified.
`ConcurrentHashMap.remove(key, value)` ensures that a new run's future is never
accidentally completed by an old run.

**Sequence (per iteration):**

1. Acquire the per-agent lock. Read a consistent snapshot of the agent record:
   `inbox`, `tasks`, `pending`, `state`.
2. If nothing to process (no tasks and empty inbox): set status → `"SLEEPING"`,
   release lock, return. Pending jobs are passed through but do not alone
   trigger a run.
3. Release the lock.
4. Resolve job data from JobManager (read-only, no lock needed).
5. Invoke the transition function (§3.2) with `agent-id`, current `state`,
   `tasks`, `pending` (with resolved statuses), and `inbox`. **No lock held**
   during the transition — it may take seconds or minutes.
6. On success — **merge with current state** (acquire lock):
   - Re-read the current agent record (not the stale snapshot from step 1).
   - Remove completed tasks from the **current** `tasks` index (not the stale
     snapshot). This prevents overwriting tasks added by concurrent `addTask`
     calls during the transition.
   - Remove only the **processed** messages from the **current** `inbox`. Messages
     appended by concurrent `deliverMessage` calls during the transition are
     preserved (they appear at indices beyond the processed count).
   - Update `state` from the returned value.
   - Append timeline entry with full audit data (§2.4).
   - If remaining work exists (tasks or inbox non-empty after merge): set
     status → `"RUNNING"`, release lock, loop to step 1.
   - Otherwise: set status → `"SLEEPING"`, release lock.
   - Apply `taskResults` to JobManager — complete or fail each task Job
     (outside the lock).
7. On error:
   - Leave `state`, `tasks`, `pending`, and `inbox` unchanged.
   - Set status → `"SUSPENDED"`, set `error` (inside the lock).
   - Note: task completions made via tool calls during execution are **not**
     rolled back (§3.4.1).

The transition operation always comes from `config.operation` (set at creation).
Callers cannot override it — to change the transition function, update the config.

The run output includes the transition function's `result` field, making
the response visible to callers without querying agent state separately.

### 4.5 Other Lifecycle Events

| Event | Mutation |
|-------|----------|
| **Config update** | Read agent record, update `config`, write agent record. |
| **Terminate** | Set status → `"TERMINATED"`, write agent record. |
| **Clear error** | Set error → null, status → `"SLEEPING"`, write agent record. |

All read-modify-write on the single value.

### 4.6 Scheduling — `wakeAgent`

Scheduling is **lattice-native**: the agent's `status` field and the contents
of `inbox`/`tasks` are the only inputs to the wake decision. There are no
separate Java-side running/wake flags that could drift from the lattice.

**`wakeAgent(agentId, ctx)`** is the single entry point for all agent wakes.
It is called by `agent:request`, `agent:message`, and async job completion
callbacks. The logic (all inside the per-agent lock):

1. Read the agent's `status` from the lattice.
2. If RUNNING → return (new work is in the lattice; the loop will see it).
3. If no work (empty inbox and tasks) → return.
4. Resolve `config.operation` → if null, return (no transition op configured).
5. Set status → RUNNING.
6. Start `executeRunLoop` on a virtual thread.

An agent is eligible to run when any of:

- A new task has been added to `tasks` (via `agent:request`)
- A pending outbound job has completed or failed
- A new message has been delivered to `inbox` (via `agent:message`)

**No lost wakeups.** The per-agent lock serialises `addTask` / `deliverMessage`
with the run loop's status check. If work is added before the lock is acquired,
the loop sees it. If work is added after the loop writes SLEEPING, the
subsequent `wakeAgent` call sees SLEEPING and starts a new loop.

**No double runs.** The lock serialises the SLEEPING → RUNNING CAS. Only one
thread can win the transition; all others see RUNNING and return.

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

All writes to an agent are serialised on one venue via a per-agent lock. Message
delivery, task addition, and run loop writes are all guarded by this lock, so
there are no concurrent writers to the agent record.

The run loop holds the lock only briefly — for reading the snapshot and for
writing the merged result. The transition invocation (which may take seconds or
minutes) runs without the lock held. Concurrent `addTask` and `deliverMessage`
calls proceed while the transition runs; their writes are preserved by the
merge-at-write-time strategy (§4.4 step 6).

Cross-venue sync is replication: the venue hosting the agent always has the latest
ts. Replicas receive the complete state.

Per-field merge (a previous design) was wrong because it could produce Frankenstein
states — status from venue A, timeline from venue B, inbox fragments from both. An
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
| **B10** | Agent workspace CRUD: `/w/` and `/o/` namespaces, write/delete/append/slice, deep path navigation, type-aware vector indexing, `maxSize` guard, default tools | ✓ Complete |
| **C** | Capability enforcement (UCAN `with`/`can`) — `validateWritablePath` is the insertion point | Planned |
| **D** | HITL requests (`/h/` namespace), cross-user messaging, cross-agent workspace reads | Planned |
| **E** | Agent forking and cross-venue migration | Planned |
