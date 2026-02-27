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

4. **Two levels.** The agent update (framework) manages bookkeeping: status,
   timeline, inbox. The transition function (user-defined) manages domain logic:
   receives state and messages, returns new state. The framework never inspects user
   state; the transition function never manages framework fields.

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
| `status` | string | `"sleeping"` \| `"running"` \| `"suspended"` \| `"terminated"` |
| `config` | map | User-provided configuration (transition op, LLM settings, etc.) |
| `state` | any | User-defined state. Opaque to the framework. Passed to and returned from the transition function. |
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

## 3. Transition Function

### 3.1 Two Levels

The agent loop separates framework bookkeeping from domain logic:

1. **Agent update** (framework). Reads the inbox, invokes the transition function,
   records the result in the timeline, manages status, writes the complete agent
   record. The same for every agent. Defined in §4.3.

2. **Transition function** (user-defined). Receives current state and new messages,
   returns updated state. This is the pluggable part — different agents use different
   transition functions (LLM chat, rule engine, workflow, custom code).

The agent update owns the agent record. The transition function owns `state` within it.

### 3.2 Contract

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

### 3.3 Invocation

The transition function is identified by an operation reference stored in the agent's
`config`. The runtime invokes it as a grid operation — same dispatch path as any other
operation in the venue.

This means transition functions can be:
- Local adapter operations (e.g. `test:echo`, `langchain:openai`)
- Remote venue operations via federation
- Composite operations via orchestration

---

## 4. Operations

Every operation atomically replaces the agent record with a new `ts`.

### 4.1 Create

**Trigger:** `agent:create`

Writes the initial agent record: status=sleeping, config from input, state=null,
empty inbox, empty timeline, no error.

**Idempotent:** If the agent record already exists, create is a no-op.

### 4.2 Message

**Trigger:** `agent:message`

Reads current agent record, appends the message to `inbox`, writes agent record.

The agent is not woken. The message sits in the inbox until the next run.

**Fails if:** the agent does not exist, or status is `"terminated"`.

### 4.3 Run — Agent Update

**Trigger:** `agent:run`

**Sequence:**

1. Read current agent record.
2. If inbox is empty: no-op.
3. Set status → `"running"`, write agent record.
4. Invoke the transition function (§3.2) with `agent-id`, current `state`,
   and `inbox`.
5. On success:
   - Update `state` from the returned value.
   - Append a timeline entry (op, starting state, inbox, returned `result`,
     start/end timestamps).
   - Clear `inbox`.
   - Set status → `"sleeping"`.
   - Write agent record.
6. On error:
   - Leave `state` and `inbox` unchanged.
   - Set status → `"suspended"`, set `error`.
   - Write agent record.

The agent update writes twice: once to mark running (step 3), once to record the
outcome (step 5 or 6). Each write is a complete atomic agent record.

On error, the inbox is preserved — the same messages will be available for retry
after the error is resolved and the agent is resumed.

### 4.4 Other Lifecycle Events

| Event | Mutation |
|-------|----------|
| **Config update** | Read agent record, update `config`, write agent record. |
| **Terminate** | Set status → `"terminated"`, write agent record. |
| **Clear error** | Set error → null, status → `"sleeping"`, write agent record. |

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

## 6. Open Questions

1. **Recovery.** An agent in `"running"` status after a venue restart was mid-
   transition. Recovery should set it to `"suspended"` (not silently resume).

2. **Transition function effects.** Should the output include an `actions` field
   for side effects (send messages to other agents, invoke operations)? Or should the
   transition function trigger effects through the grid directly during execution?
   Defining the action vocabulary is a Phase B concern.

---

## 7. Phasing

See GRID_LATTICE_DESIGN.md §12 for the full roadmap.

| Phase | Focus | Status |
|-------|-------|--------|
| **0** | Per-user namespace (`"g"`, `"s"`), SecretStore | ✓ Complete |
| **A** | AgentState wrapper, AgentAdapter (create/message/run) | ✓ Complete (needs lattice restructure per this doc) |
| **B** | Default transition function (LLM + tool palette) | Planned |
| **C** | Capability enforcement (UCAN `with`/`can`) | Planned |
| **D** | Cross-user messaging, advanced wake triggers | Planned |
| **E** | Agent forking and cross-venue migration | Planned |
