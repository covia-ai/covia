# Agent Design — Data Structure and Transitions

Design for Covia agent state and the transitions that mutate it.

**Status:** Draft — February 2026

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
   agent runs, config updates — are serialised on that venue. Cross-venue sync is
   replication: the more-recent state replaces the stale one.

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
| `config` | map | Framework-level configuration. Opaque to the transition function. |
| `state` | any | User-defined state. Opaque to the framework. Passed to and returned from the transition function. Transition-function-specific configuration (e.g. LLM provider, model) lives here, not in `config`. Set at creation via optional initial state. |
| `inbox` | vector | Messages awaiting processing. Drained on successful run — the timeline is the permanent record. |
| `timeline` | vector | Append-only log of transition records (§2.3). Grows with each successful agent run. Timestamped for audit. |
| `caps` | map | Capability sets (placeholder — Phase C enforcement) |
| `error` | string? | Last error message, or null. Set when the transition function fails. |

All fields are framework-managed except `state`, which is owned by the transition
function. CAD3 structural sharing means the timeline (which only appends) shares
all existing entries with the previous version.

### 2.3 Timeline Entry

Each entry in the `timeline` vector records one successful agent run:

| Field | Type | Description |
|-------|------|-------------|
| `start` | long | Timestamp when the run started (step 3). |
| `end` | long | Timestamp when the run completed (step 5). |
| `op` | string | The operation reference used for the transition function. |
| `state` | any | The starting state passed to the transition function. |
| `messages` | vector | The inbox messages passed to the transition function. |
| `result` | any | The `result` returned by the transition function. |

The output state is not stored in the timeline entry — it is the `state` field in
the agent record (for the latest run) or the `state` field in the next timeline
entry (for earlier runs). This avoids redundant storage.

Timeline entries are only appended on success. On error, no timeline entry is
written — the error is recorded in the agent record's `error` field and the inbox
is preserved for retry.

---

## 3. Three Levels

The agent system separates concerns into three levels. Each level is a grid
operation, pluggable and replaceable independently.

```
Level 1: Agent Update          agent:run (AgentAdapter)
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

Reads the inbox, invokes the transition function, records the result in the
timeline, manages status, writes the complete agent record. The same for every
agent. Defined in §4.3.

The agent update owns the agent record. The transition function owns `state`
within it. The agent update never inspects `state`; the transition function never
manages framework fields.

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
grid operation and works with structured input/output. This makes it pluggable:
swap the level 3 operation to change provider, use a remote venue via federation,
or a test mock.

The level 3 operation to invoke is part of `state.config` — the agent creator
picks both the agent loop strategy (level 2) and the LLM backend (level 3).

### 3.3 Level 3 — LLM Call (Single Step)

A single, stateless LLM invocation. Takes a list of messages (with tool
definitions if applicable), calls a specific LLM API, returns the response.
The response may be:
- A text response (assistant message)
- One or more tool call requests (function name + arguments)

Level 3 is a standard grid operation (e.g. `langchain:openai`, `langchain:ollama`).
It knows about HTTP clients, API serialisation, authentication, and provider
quirks. It does not know about agents, conversation history, or tool execution.

This is essentially what `LangChainAdapter` already provides — the existing
LLM adapter operations serve as level 3.

### 3.4 Transition Function Contract

The contract between level 1 and level 2:

**Input:**

| Field | Type | Description |
|-------|------|-------------|
| `agent-id` | string | The agent's identifier. |
| `state` | any | Current `state` from the agent record. Null on first run. |
| `messages` | vector | The inbox. Each entry is a message record. |

**Output:**

| Field | Type | Description |
|-------|------|-------------|
| `state` | any | Updated user-defined state. Written back to the agent record. |
| `result` | any | Summary of what happened. Recorded in the timeline entry by the agent update. |

The transition function does not manage ts, status, timeline, or inbox. It is a
pure function from (state, messages) → (state, result).

The transition function must handle its own errors internally. If it throws, the
agent update treats this as a severe failure: the agent is suspended, the inbox is
preserved, and the error is recorded. No timeline entry is written.

### 3.5 Invocation

Each level invokes the next as a grid operation — same dispatch path as any other
operation in the venue. This means any level can be:
- A local adapter operation (e.g. `llmagent:chat`, `langchain:openai`)
- A remote venue operation via federation
- A composite operation via orchestration
- A test mock (e.g. `test:echo`)

The level 2 operation is specified by the caller in the `agent:run` input.
The level 3 operation is specified by the agent creator in `state.config`.

### 3.6 Credential Access

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
input (or null), empty inbox, empty timeline, no error.

Initial state allows the creator to seed transition-function-specific configuration
(e.g. LLM provider, model, system prompt) that the transition function will read
and preserve across runs. This keeps the framework `config` clean of
transition-function internals, and allows an agent to switch transition functions.

**Idempotent:** If the agent record already exists, create is a no-op.

### 4.2 Message

**Trigger:** `agent:message`

Reads current agent record, appends the message to `inbox`, writes agent record.

The agent is not woken. The message sits in the inbox until the next run.

**Fails if:** the agent does not exist, or status is `"TERMINATED"`.

### 4.3 Run — Agent Update

**Trigger:** `agent:run`

**Sequence:**

1. Read current agent record.
2. If inbox is empty: no-op.
3. Set status → `"RUNNING"`, write agent record.
4. Invoke the transition function (§3.2) with `agent-id`, current `state`,
   and `inbox`.
5. On success:
   - Update `state` from the returned value.
   - Append a timeline entry (op, starting state, inbox, returned `result`,
     start/end timestamps).
   - Clear `inbox`.
   - Set status → `"SLEEPING"`.
   - Write agent record.
6. On error:
   - Leave `state` and `inbox` unchanged.
   - Set status → `"SUSPENDED"`, set `error`.
   - Write agent record.

The agent update writes twice: once to mark running (step 3), once to record the
outcome (step 5 or 6). Each write is a complete atomic agent record.

On error, the inbox is preserved — the same messages will be available for retry
after the error is resolved and the agent is resumed.

### 4.4 Other Lifecycle Events

| Event | Mutation |
|-------|----------|
| **Config update** | Read agent record, update `config`, write agent record. |
| **Terminate** | Set status → `"TERMINATED"`, write agent record. |
| **Clear error** | Set error → null, status → `"SLEEPING"`, write agent record. |

All read-modify-write on the single value.

---

## 5. Merge Semantics

### 5.1 Last Writer Wins

The LWW lattice compares `ts` values. The record with the later timestamp wins
unconditionally.

- **Monotonic:** ts only increases on the hosting venue → state only advances.
- **Complete:** the winner is a full consistent snapshot, never a mix of fields.
- **Simple:** standard LWW lattice, no custom merge function.

### 5.2 Why One Value?

All writes to an agent are serialised on one venue. There are no concurrent writers.
Message delivery, agent runs, and config updates are all atomic read-modify-write
on the same value.

Cross-venue sync is replication: the venue hosting the agent always has the latest
ts. Replicas receive the complete state.

Per-field merge (the previous design) was wrong because it could produce Frankenstein
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

### 6.3 Tool palette

Tools available to an LLM agent are managed by level 2 (agent transition).
Level 2 passes tool definitions to level 3 (LLM call), receives tool call
requests back, executes the tools, and loops until the LLM returns a text
response. Tool definitions may come from `state.config` or operation metadata.
Per-agent tool configuration is a Phase C concern.

### 6.4 Credential resolution

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
| **B2** | Decouple level 2 from LangChain4j — invoke level 3 as grid operation, tool call loop | Next |
| **C** | Capability enforcement (UCAN `with`/`can`), tool palette | Planned |
| **D** | Cross-user messaging, advanced wake triggers | Planned |
| **E** | Agent forking and cross-venue migration | Planned |
