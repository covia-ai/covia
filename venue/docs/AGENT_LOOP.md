# Agent Loop Adapter — Design Document

Design for the `AgentLoopAdapter`, implementing the primary agentic state transition loop for Covia agents (`/g/<agent-id>`).

**Status:** Prototype — February 2026

---

## 1. Purpose

The Agent Loop Adapter bridges the gap between the GRID_LATTICE_DESIGN's four-layer agent architecture and the existing venue runtime. It implements the runtime-managed execution loop (Layer 1) and hosts the transition function call (Layer 2), while delegating tool execution and LLM inference to existing adapters (Layers 3 and 4).

The adapter is deliberately **lattice-aware but lattice-flexible** — it reads and writes agent state through cursor-based abstractions rather than hardcoded paths, so the lattice structure can evolve without rewriting the loop logic.

---

## 2. Pre-requisite: Per-User Namespace

Agents and secrets are **per-user** resources. A user's agents belong to them. A user's API keys belong to them. This means the per-user namespace (`USER` in `Covia.java`) must be extended before the agent loop can work.

### 2.1 Current State

The `USER` KeyedLattice currently has only one slot:

```
USER → KeyedLattice
  :jobs → IndexLattice + LWW (user's job references)
```

This is accessed via `User.java` which wraps a cursor at `:user-data → <DID>` and exposes `jobs()`.

### 2.2 Target State

The per-user namespace becomes the user's complete working environment:

```
USER → KeyedLattice
  :jobs     → IndexLattice + LWW    (user's job references — exists today)
  :agents   → MapLattice + AGENT    (user's agents)
  :secrets  → MapLattice + LWW      (user's encrypted credentials)
  :workspace → MapLattice + LWW     (user scratch space — future)
  :assets   → CASLattice            (user-created assets — future)
  :ops      → MapLattice + LWW      (user's named operations — future)
```

This aligns with the GRID_LATTICE_DESIGN Phase 2 vision (§12, `:user-data → <DID> → ...`) which already anticipates `:workspace`, `:assets`, `:ops`.

### 2.3 Why Per-User, Not Venue-Level

| Concern | Venue-level | Per-user |
|---------|-------------|----------|
| **Ownership** | Venue owns all agents/secrets | User owns their agents/secrets |
| **Access control** | Must check ownership on every access | Natural — cursor scoping gives isolation |
| **Multi-tenancy** | Single namespace, collision risk | Each user has independent namespace |
| **Secret isolation** | All secrets in one pool | User can only see their own |
| **Agent isolation** | All agents in one pool | User manages only their agents |
| **Data sovereignty** | Venue controls everything | User's data is structurally theirs |
| **Cross-venue** | Secrets can't move with user | Per-user state can migrate with user identity |

The venue still owns venue-level state (adapter registrations, shared assets, venue config). But user-created resources — agents, secrets, workspace — live under the user.

### 2.4 Implementation: Extending `User.java`

The `User` wrapper gains new accessors:

```java
public class User extends ALatticeComponent<ACell> {

    // Existing
    public JobStore jobs();

    // New — Phase A
    public AgentState agent(AString agentId);    // navigate to specific agent
    public AMap<AString, ACell> getAgents();      // list all user's agents
    public SecretStore secrets();                  // user's secret store

    // Future
    // public ACursor<?> workspace();
    // public AssetStore assets();
    // public ACursor<?> ops();
}
```

### 2.5 Lattice Definition Changes (`Covia.java`)

```java
// AGENT lattice (defined in full in §4)
public static final KeyedLattice AGENT = KeyedLattice.create(...);

// Extended USER lattice
public static final KeyedLattice USER = KeyedLattice.create(
    JOBS,    IndexLattice.create(LWW),           // user's job references (exists today)
    AGENTS,  MapLattice.create(AGENT),           // user's agents
    SECRETS, MapLattice.create(LWW)              // user's encrypted credentials
);
```

The `VENUE` lattice does **not** gain `:agents` or `:secrets` slots. These live exclusively under user data.

### 2.6 Addressing

An agent's full address is:

```
:user-data → <owner-DID> → :agents → <agent-id> → ...
```

Or in grid path notation: `<venue-DID>/u/<owner-DID>/g/<agent-id>`

A secret's full address is:

```
:user-data → <owner-DID> → :secrets → <secret-name> → ...
```

Or in grid path notation: `<venue-DID>/u/<owner-DID>/s/<secret-name>`

---

## 3. Design Principles

1. **Transition function is a grid operation.** The agent's behaviour is defined by a configurable operation reference (`/o/my-transition-fn` or an asset hash). The loop adapter invokes it — it does not contain the intelligence itself.

2. **State in, state out.** Each transition receives `(state, inbox, jobs, config)` and returns `{new_state, actions}`. The loop adapter manages everything around this pure function: wake evaluation, state loading, action dispatch, timeline recording.

3. **Lattice-flexible.** Agent state is accessed through an `AgentState` cursor wrapper (following the `VenueState` / `AssetStore` / `JobStore` pattern). If we restructure the lattice, only the wrapper changes — the loop logic stays the same.

4. **Multi-turn by nature.** An agent job is long-lived. The loop adapter overrides `invoke()` (not `invokeFuture()`) and uses the job message queue for external input. Each message can trigger a new transition cycle.

5. **Actions are validated before dispatch.** The transition function returns proposed actions; the runtime validates them against the agent's capabilities before executing any of them. (Capability enforcement is initially permissive; the validation point is established for Phase 4.)

6. **Timeline is append-only.** Every transition appends a record to the agent's timeline. CAD3 structural sharing keeps this efficient.

---

## 4. AgentState — Lattice Cursor Wrapper

Follows the established pattern (`VenueState` → `AssetStore`, `JobStore`, `Users`). Wraps a cursor at the agent's lattice path and provides typed accessors.

### 4.1 Lattice Path

Agents live under `:agents` within each user's namespace:

```
VENUE
  :user-data → MapLattice (DID → per-user KeyedLattice)
    <owner-DID> → KeyedLattice (USER)
      :jobs    → ...  (existing)
      :agents  → MapLattice (agent-id → agent state)
        <agent-id> → KeyedLattice (AGENT)
          :status    → LWW (sleeping | waking | running | suspended | terminated)
          :seq       → FunctionLattice (max — monotonically increasing)
          :config    → MapLattice + LWW (user-configurable)
          :inbox     → IndexLattice + LWW (append-like, keyed by message ID)
          :jobs      → IndexLattice + LWW (assigned jobs)
          :timeline  → IndexLattice (append-only, keyed by seq number)
          :caps      → MapLattice + LWW (capability sets)
          :error     → LWW (last error, nullable)
      :secrets → MapLattice + LWW
        <secret-name> → { "encrypted": <blob>, "updated": <ts> }
```

Owner is implicit from the path — the agent lives under the user's DID, so the user is the owner. No separate `:owner` field needed.

### 4.2 AGENT Lattice Definition

```java
public static final KeyedLattice AGENT = KeyedLattice.create(
    STATUS_K, LWW,                                // agent lifecycle status
    SEQ,      FunctionLattice.create(Math::max),   // monotonically increasing
    CONFIG,   MapLattice.create(LWW),              // user config
    INBOX,    IndexLattice.create(LWW),            // incoming messages
    JOBS,     IndexLattice.create(LWW),            // assigned jobs
    TIMELINE, IndexLattice.create(CASLattice.create()), // append-only
    CAPS,     MapLattice.create(LWW),              // capability sets
    ERROR_K,  LWW                                  // last error
);
```

### 4.3 AgentState API

```java
public class AgentState extends ALatticeComponent<ACell> {

    // --- Lifecycle ---
    AString getStatus();                    // sleeping, running, etc.
    void setStatus(AString status);
    long getSeq();
    void incrementSeq();

    // --- Configuration ---
    AMap<AString, ACell> getConfig();       // user-provided config block
    AString getTransitionOp();              // config.model — the transition function ref
    ACell getLLMConfig();                    // config.llm

    // --- State I/O (for transition function) ---
    ACell getState();                       // latest state from timeline
    void appendTimeline(AMap<AString, ACell> entry);  // append transition record

    // --- Inbox ---
    Index<Blob, ACell> drainInbox();        // read + clear inbox messages
    void deliverToInbox(AMap<AString, ACell> message);

    // --- Assigned Jobs ---
    Index<Blob, ACell> getPendingJobs();
    void updateJobStatus(Blob jobId, AString status);

    // --- Capabilities ---
    ACell getCaps();

    // --- Factory ---
    static AgentState fromUser(User user, AString agentId);
    static AgentState create(User user, AString agentId,
                             AMap<AString, ACell> config);
}
```

The key insight: `AgentState` is a **thin cursor wrapper**. It navigates paths and reads/writes values. All merge semantics come from the lattice definition in `Covia.java`. If we change the lattice structure, we update the wrapper's path navigation — the loop logic is untouched.

Note the factory methods take a `User`, not a `VenueState` — the agent lives under the user. The adapter resolves the user from the caller DID, then navigates to the agent.

---

## 5. Secrets (`/s/`) — Per-User Credential Store

### 5.1 Why Secrets Are a Pre-requisite

Agents need credentials to call external services (LLM API keys, OAuth tokens, database passwords). Without `/s/`, API keys end up in plaintext JSON in the config — which is the current state of `LangChainAdapter` and is flagged as a P1 issue.

The trust boundary:

1. Agent config references a secret: `{ "llm": { "api_key": "/s/anthropic-key" } }`
2. The transition function needs to call the LLM
3. The adapter (trusted, venue-provided) resolves `/s/anthropic-key` to plaintext
4. The plaintext is injected into the LLM API call
5. The transition function (untrusted) never sees the key

### 5.2 SecretStore

```java
public class SecretStore extends ALatticeComponent<ACell> {

    // Store a secret (encrypts with venue key pair)
    void store(AString name, AString plaintext);

    // Retrieve decrypted value
    AString decrypt(AString name, AKeyPair venueKeyPair);

    // Check if a secret exists
    boolean exists(AString name);

    // List secret names (not values)
    AMap<AString, ACell> list();
}
```

Secrets are scoped to the user — `user.secrets().store("anthropic-key", apiKey)`. No additional ownership check needed; the user cursor already provides isolation.

### 5.3 Encryption (Prototype)

- Secrets encrypted with the venue's Ed25519-derived key (X25519 conversion for NaCl box)
- At rest: only ciphertext in the lattice
- Decryption: venue runtime decrypts on behalf of the owning user's operations
- Single-venue encryption — cross-venue secret sharing is future work

### 5.4 API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/secrets` | GET | List caller's secret names (not values) |
| `/api/v1/secrets/{name}` | PUT | Store a secret (encrypted for caller) |
| `/api/v1/secrets/{name}` | DELETE | Remove a caller's secret |

No GET for secret values — decryption happens only internally via the runtime on behalf of authorised operations. The API scopes automatically to the caller's user namespace.

### 5.5 Integration with Agent Loop

```java
// In AgentLoop, before calling the LLM adapter:
ACell llmConfig = agentState.getLLMConfig();
AString apiKeyRef = RT.ensureString(RT.getIn(llmConfig, "api_key"));
if (apiKeyRef != null && apiKeyRef.toString().startsWith("/s/")) {
    AString secretName = Strings.create(apiKeyRef.toString().substring(3));
    AString plaintext = ownerUser.secrets().decrypt(secretName, venueKeyPair);
    // Inject into adapter call, not into transition function input
}
```

---

## 6. AgentLoopAdapter

### 6.1 Adapter Identity

```java
getName()        → "agent"
getDescription() → "Manages agent lifecycle and state transitions. Implements the
                    runtime execution loop for Covia agents — loads state, drains
                    inbox, calls the transition function, validates and dispatches
                    actions, and records timeline entries."
```

### 6.2 Operations

| Operation | Purpose | Input |
|-----------|---------|-------|
| `agent:run` | Start or resume an agent's execution loop | `{ "agent": "<agent-id>" }` |
| `agent:create` | Create a new agent with initial config | `{ "config": {...} }` |
| `agent:message` | Deliver a message to an agent's inbox | `{ "agent": "<agent-id>", "message": {...} }` |

Note: `agent:create` does not need an `owner` field — the caller DID is the owner. The agent is created under the caller's user namespace.

### 6.3 Multi-Turn Lifecycle

`agent:run` creates a long-lived job. The loop runs on a virtual thread:

```
1. Resolve owner user from caller DID
2. Navigate to AgentState via user.agent(agentId)
3. Job status → STARTED
4. AgentState.status → "running"
5. LOOP:
   a. Load current state from latest timeline entry
   b. Drain inbox → inbox_items
   c. Read pending jobs → pending_jobs
   d. If nothing to process and no schedule trigger → sleep (await message)
   e. Increment seq
   f. Resolve secrets referenced in config (adapter-side, not in transition input)
   g. Call transition function via engine.jobs().invokeOperation()
      Input:  { state, inbox: inbox_items, jobs: pending_jobs, config }
      Output: { new_state, actions: [...] }
   h. Validate actions against caps (permissive for now)
   i. Append timeline entry: { seq, state: new_state, input, actions, ts }
   j. Dispatch actions:
      - "invoke"       → engine.jobs().invokeOperation(...)
      - "complete_job" → complete assigned job with result
      - "fail_job"     → fail assigned job with reason
      - "message"      → deliver to target agent's inbox
   k. Update agent state
   l. If terminated → break
   m. Sleep until next trigger (inbox message, job complete, schedule)
6. AgentState.status → "sleeping" (or "terminated")
7. Job completes
```

### 6.4 Wake / Sleep Mechanism

The agent sleeps by blocking on a `LinkedBlockingQueue<ACell>` of wake events. Wake sources:

- **Message delivery** — `handleMessage()` enqueues a wake event
- **Job completion** — callback from sub-job's `onFinish()`
- **Schedule** — future: a scheduled executor posts wake events on cron
- **External** — `agent:message` operation delivers to inbox and wakes

When a wake event arrives, the loop reads the current inbox and pending jobs, then runs a transition.

### 6.5 Transition Function Contract

The transition function is any grid operation. It receives:

```json
{
  "state": <previous agent state — any JSON value>,
  "inbox": [<inbox messages drained since last transition>],
  "jobs":  [<pending/completed jobs assigned to this agent>],
  "config": { <agent configuration — secrets resolved by runtime> }
}
```

It returns:

```json
{
  "new_state": <updated agent state — any JSON value>,
  "actions": [
    { "type": "invoke", "op": "langchain:openai", "input": {...} },
    { "type": "complete_job", "job": "0x1234...", "result": {...} },
    { "type": "fail_job", "job": "0x1234...", "reason": "..." },
    { "type": "message", "agent": "<agent-id>", "payload": {...} }
  ]
}
```

The transition function can be:
- A **simple adapter operation** (e.g. `langchain:openai` with a system prompt that outputs structured JSON)
- A **custom JVM adapter** that implements a ReAct loop internally
- An **orchestration** that chains multiple operations

For the prototype, we provide a built-in `agent:default-transition` operation that wraps an LLM call with the agent tool palette, implementing a basic ReAct loop.

---

## 7. Action Dispatch

Actions returned by the transition function are dispatched by the runtime after validation.

### 7.1 Action Types

| Type | Fields | Runtime behaviour |
|------|--------|-------------------|
| `invoke` | `op`, `input` | `engine.jobs().invokeOperation(op, input, ownerDID)` — fire and forget or await |
| `complete_job` | `job`, `result` | Find job in agent's assigned jobs, call `job.completeWith(result)` |
| `fail_job` | `job`, `reason` | Find job in agent's assigned jobs, call `job.fail(reason)` |
| `message` | `agent`, `payload` | Deliver message to target agent's inbox |
| `invoke_await` | `op`, `input` | Like `invoke` but blocks until result, feeds back into next transition |

### 7.2 Validation (Future)

For the prototype, all actions are allowed. The validation point is established:

```java
private boolean validateAction(AgentState agent, AMap<AString, ACell> action) {
    // Phase 4: check action against agent.getCaps()
    // For now: always returns true
    return true;
}
```

---

## 8. Built-in Default Transition Function

The `agent:default-transition` operation provides a basic agentic loop:

1. Construct a prompt from state + inbox + jobs + config
2. Call the configured LLM (via LangChain adapter)
3. Parse the LLM response for tool calls (structured output)
4. Execute tool calls sequentially
5. Feed results back to LLM (multi-turn within single transition)
6. Return `{ new_state, actions }` when LLM signals done

This is implemented as a separate operation (not baked into the loop adapter) so users can replace it entirely.

---

## 9. Architecture Diagram

```
                    ┌─────────────────────────────────────────────┐
                    │              AgentLoopAdapter                │
                    │                                             │
  invoke(job)  ──>  │  ┌──────────┐    ┌───────────────┐         │
                    │  │ AgentLoop │───>│ AgentState    │         │
  message(job) ──>  │  │ (Runnable)│    │ (cursor wrap) │         │
                    │  │           │    └───────┬───────┘         │
                    │  │           │            │                  │
                    │  │           │    ┌───────┴───────┐         │
                    │  │           │    │ SecretStore    │         │
                    │  │           │    │ (per-user)     │         │
                    │  │           │    └───────────────┘         │
                    │  │           │                               │
                    │  │  wake ──> load state                     │
                    │  │       ──> drain inbox                    │
                    │  │       ──> read pending jobs              │
                    │  │       ──> resolve secrets ────────────>  │ user.secrets()
                    │  │       ──> call transition fn ─────────>  │ engine.jobs()
                    │  │       ──> validate actions               │   .invokeOperation()
                    │  │       ──> append timeline                │
                    │  │       ──> dispatch actions ───────────>  │ engine.jobs()
                    │  │       ──> sleep / await trigger          │   .invokeOperation()
                    │  └──────────┘                               │
                    └─────────────────────────────────────────────┘

Lattice path:  :user-data → <DID> → :agents → <agent-id> → ...
                                   → :secrets → <name> → ...
```

### 9.1 Relationship to Existing Components

| Component | Role in agent loop |
|-----------|-------------------|
| `AAdapter` | Base class — AgentLoopAdapter extends it |
| `Job` | The agent's lifecycle job. Long-lived, multi-turn. |
| `JobManager` | Invokes sub-operations on behalf of the agent |
| `Engine` | Provides adapter lookup, asset resolution, venue key pair |
| `VenueState` | Parent cursor — navigates to user data |
| `Users` / `User` | **Extended** — per-user namespace with agents + secrets |
| `AgentState` | **New** — cursor wrapper for agent within user namespace |
| `SecretStore` | **New** — cursor wrapper for secrets within user namespace |

---

## 10. File Plan

| File | Purpose |
|------|---------|
| `covia/lattice/Covia.java` | Add AGENT lattice, extend USER with `:agents` + `:secrets` |
| `covia/venue/User.java` | Add `agent()`, `secrets()`, `getAgents()` accessors |
| `covia/venue/AgentState.java` | Lattice cursor wrapper for agent state |
| `covia/venue/SecretStore.java` | Lattice cursor wrapper for per-user secrets |
| `covia/adapter/AgentLoopAdapter.java` | Adapter + AgentLoop inner class |
| `resources/adapters/agent/run.json` | Asset metadata for agent:run |
| `resources/adapters/agent/create.json` | Asset metadata for agent:create |
| `resources/adapters/agent/message.json` | Asset metadata for agent:message |
| Tests: | |
| `covia/venue/SecretStoreTest.java` | Secret store unit tests |
| `covia/adapter/AgentLoopAdapterTest.java` | Agent loop tests |
| `covia/venue/UserExtendedTest.java` | Extended user namespace tests |

---

## 11. Example: Running an Agent

```java
// Store an API key in the user's secret store
User user = engine.getVenueState().users().ensure(callerDID);
user.secrets().store(Strings.create("openai-key"), Strings.create("sk-..."));

// Create an agent (lives under the caller's namespace)
Job createJob = engine.jobs().invokeOperation(
    Strings.create("agent:create"),
    Maps.of(
        "config", Maps.of(
            "model", Strings.create("agent:default-transition"),
            "llm", Maps.of(
                "provider", Strings.create("openai"),
                "model", Strings.create("gpt-4"),
                "api_key", Strings.create("/s/openai-key")  // secret reference
            ),
            "systemPrompt", Strings.create("You are a helpful assistant.")
        )
    ),
    callerDID
);
AString agentId = RT.ensureString(RT.getIn(createJob.awaitResult(), "agentId"));

// Start the agent loop
Job agentJob = engine.jobs().invokeOperation(
    Strings.create("agent:run"),
    Maps.of("agent", agentId),
    callerDID
);

// Send a message (triggers transition)
engine.jobs().deliverMessage(agentJob.getID(),
    Maps.of("content", Strings.create("What is the weather?")),
    callerDID
);

// The agent loop wakes, runs a transition, and dispatches actions.
// The runtime resolves /s/openai-key from the user's secret store,
// injects it into the LLM call, and the transition function never sees it.
```

---

## 12. Testing Strategy

### Unit Tests (no LLM required)

1. **Extended USER lattice CRDT properties** — commutative, associative, idempotent merge with agents + secrets slots
2. **SecretStore** — store, decrypt, list, exists
3. **AgentState CRDT properties** — commutative, associative, idempotent merge
4. **Agent creation** — creates agent with config under user's namespace
5. **Inbox delivery** — messages arrive and can be drained
6. **Timeline append** — entries are recorded with correct seq numbers
7. **Wake mechanism** — message delivery wakes a sleeping loop
8. **Action dispatch** — invoke, complete_job, fail_job, message actions work
9. **Action validation** — placeholder passes all (ready for Phase 4)
10. **User isolation** — user A cannot see user B's agents or secrets

### Integration Tests (mock transition function)

11. **Full loop cycle** — create agent, send message, transition runs, actions dispatched
12. **Multi-turn** — multiple messages trigger multiple transitions
13. **Job assignment** — external job assigned to agent, agent completes it
14. **Secret resolution** — agent config with `/s/` refs, adapter resolves before LLM call
15. **Error handling** — transition function failure → agent error state, not crash

### With TestAdapter as Transition Function

Use `test:echo` as a trivial transition function that echoes input as `{new_state: input, actions: []}`. This validates the loop mechanics without requiring an LLM.

---

## 13. Open Questions

1. **Agent ID format** — use the same Blob ID generation as jobs? Or a separate namespace? (Starting with job-style IDs for consistency.)

2. **Agent-to-job mapping** — each running agent has one long-lived job. Should the agent ID and job ID be the same, or linked? (Starting with separate IDs, linked via agent state.)

3. **Concurrent transitions** — should the loop allow overlapping transitions if a new wake arrives while a transition is in progress? (Starting with serial: one transition at a time, queued wakes.)

4. **Timeline pruning** — the timeline grows indefinitely. Should we support compaction (retain last N entries, archive older ones)? (Deferred — CAD3 structural sharing makes this less urgent.)

5. **Agent lifecycle across restarts** — agents in "sleeping" state should be recoverable. Should `recoverJobs()` also recover sleeping agents? (Yes, via `AgentLoopAdapter.recoverAgents()` walking all users' `:agents`.)

6. **Cross-user agent messaging** — user A's agent sends a message to user B's agent. The runtime needs to resolve the target user + agent from a DID path. Start with same-user only? (Yes — cross-user messaging is Phase D+.)

---

## 14. Phasing

### Phase 0: Per-User Namespace Foundation (pre-requisite)
- Extend `USER` KeyedLattice with `:agents` and `:secrets` slots
- Implement `SecretStore` cursor wrapper
- Extend `User.java` with `agent()`, `secrets()`, `getAgents()` accessors
- Add secret management API endpoints
- Tests for extended user namespace and secret store

### Phase A: Agent Loop Prototype
- `AgentState` cursor wrapper
- `AgentLoopAdapter` with run/create/message operations
- Serial transition loop with wake-on-message
- Action dispatch (invoke, complete_job, fail_job, message)
- Secret resolution in agent config
- Tests with mock/echo transition function

### Phase B: Default Transition Function
- `agent:default-transition` operation with LLM + tool palette
- Integration with LangChainAdapter
- Structured output parsing for actions

### Phase C: Capability Enforcement
- Validate actions against agent caps
- UCAN `with`/`can` checking
- Secret decryption capability gating

### Phase D: Cross-User Messaging and Advanced Wake
- Cross-user agent-to-agent messaging
- Cron-like wake schedules
- Watch-based triggers (lattice path changes)
- Job completion triggers

### Phase E: Forking and Mobility
- `agent:fork` operation
- Provenance tracking
- Cross-venue agent migration
