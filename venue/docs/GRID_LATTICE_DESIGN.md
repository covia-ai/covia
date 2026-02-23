# Covia Grid Lattice Structure

This is a PROPOSAL for the target state of the Covia venue-backed grid

## Design Document — February 2026

---

## 1. Overview

The Covia Grid is a distributed execution and data platform built on the Convex lattice. All entities in the system — assets, operations, agents, jobs, workspaces, and secrets — are represented as structured data within a unified lattice addressing scheme. The lattice provides P2P replicated state with content-addressable immutable snapshots, federated execution across venue boundaries, and a capability-based security model using UCANs.

The on-chain CVM layer handles identity anchoring, economic settlement, and trust. The actual grid data flow operates over the P2P lattice (DLFS), not consensus-ordered blocks.

---

## 2. Core Principles

- **Everything is structured data.** Assets, operations, jobs, agent state — all are JSON-like values navigable by path.
- **Immutable snapshots + mutable cursors.** Mutable paths provide named references for development and iteration. Immutable content-addressed refs provide reproducibility and auditability.
- **The lattice is permissive, the ecosystem rewards convention.** All namespaces store arbitrary CAD3 values, but tooling, interop, and chaining work best with JSON-like (hashmap-based) structures.
- **Freedom in workspace, discipline everywhere else.** System-managed namespaces enforce typed integrity; user workspaces allow arbitrary structure.
- **Operations receive values, not paths.** Security is enforced by resolving references at invocation time and explicitly granting capabilities.

---

## 3. Addressing Scheme

### 3.1 Global Address Format

```
did:venue:<identity> / <namespace> / <path...>
```

- **DID prefix** — globally unique identity anchoring the lattice context (venue + account).
- **Namespace** — one of the hardcoded typed namespaces (see §4).
- **Path** — either a content-addressed hash or a mutable cursor path within the namespace.

### 3.2 Two Addressing Modes

**Mutable path (cursor):** `/w/my/draft` — a named location in the lattice. The value at this location can change over time.

**Immutable ref (snapshot):** `/a/0xcafebabe...` — a content-addressed reference. Permanently resolves to exactly one value. Self-verifying via CAD3 hash.

A mutable path can be **pinned** to an immutable ref at any point, capturing a snapshot. The history of a mutable path is implicitly a sequence of immutable refs — versioning for free.

### 3.3 Relative vs. Absolute Paths

Within a session, the DID is implicit:

- `/w/my/op` — resolves against the current identity/venue context
- `/a/cafebabe...` — content-addressed, no identity context needed

Full qualification is required only when crossing boundaries:

- `did:venue:B:99/o/transform` — referencing another venue's operation

The lattice resolves implicit context based on the active session identity.

---

## 4. Namespaces

Each top-level namespace is hardcoded in the lattice app definition. This is not user-configurable — the namespace typing defines mutation rules, integrity constraints, access control, and execution semantics. Under the hood, each namespace is a lattice cursor with specialised sub-keys that enforce these rules.

### 4.1 Namespace Summary

| Namespace | Prefix | Mutability | Storage Type | Managed By |
|-----------|--------|------------|--------------|------------|
| Assets | `/a/` | Immutable, content-addressed | Index | System |
| Operations | `/o/` | Mutable, schema-enforced (pin to `/a/` on invoke) | Hashmap | System |
| Jobs | `/j/` | Lifecycle state machine (freeze to `/a/` on complete) | Index | System |
| Agents | `/g/` | Identity-bound mutations | Index | System |
| Workspace | `/w/` | Freely mutable | Hashmap | User/Agent |
| Secrets | `/s/` | Encrypted, capability-gated | Index | System |

### 4.2 Storage Type Rationale

**Index** — used for all system-managed namespaces. Provides ordered keys, range scans (critical for performance monitoring, garbage collection, auditing), and enforced structure. Keys are typically hashes, IDs, or timestamps (short blob-like values).

**Hashmap** — used for user/agent-managed data. Flexible arbitrary keys, JSON-like navigation, no ordering guarantees. Suitable for workspace and metadata within records.

**Mixed** — within any entry, the internal structure may use hashmaps for metadata regardless of the namespace root type. For example, `/j/` root is an index (ordered job listing), but `/j/2456543/` is a hashmap (job record), and `/j/2456543/logs/` may be an index (ordered log entries).

### 4.3 Namespace Details

#### `/a/` — Assets

Immutable, content-addressed blobs. Write-once, reference by CAD3 hash. The universal immutable layer — both data assets and operation snapshots pin here.

- Keys are content hashes
- Append-only — no mutation, no deletion (except GC)
- Self-verifying — any peer can validate data against its hash
- Range scans support garbage collection and enumeration
- Operations pin to `/a/` on invoke; jobs freeze to `/a/` on completion

#### `/o/` — Operations

Mutable, schema-enforced registry of operation definitions. Each entry must conform to operation schema (input spec, output spec, executor binding). Unlike `/w/`, values in `/o/` are typed and validated by the system.

```json
{
  "input": { "schema": "..." },
  "output": { "schema": "..." },
  "executor": "did:venue:B:99/g/7",
  "cost": 450,
  "permissions": ["..."]
}
```

Operations are authored and iterated in `/o/` under stable mutable names (e.g. `/o/my-transform`). When `invoke()` is called, the system **pins the current value to `/a/`** as an immutable content-addressed snapshot. The job record captures both the mutable name and the pinned ref:

```json
{
  "op_name": "/o/my-transform",
  "op_pinned": "/a/cafebabe...",
  ...
}
```

This gives human-readable provenance ("which op") and exact reproducibility ("which version") in the same record. The mutable name can be updated without breaking references — callers always get the latest, while past job records are pinned to the exact version that ran.

`invoke()` also accepts operations from other sources — `/w/my-draft-op` (workspace draft, validated and pinned at invoke time), `/a/cafebabe...` (already pinned), or `did:venue:B:99/o/transform` (federated). In all cases the job record contains a pinned `/a/` reference.

**Separation from `/w/`:** Both `/o/` and `/w/` are mutable, but `/o/` is a typed registry with schema enforcement, open-read defaults (code is inspectable), and operation-specific indexing (schema matching, dependency graphs, cost/performance tracking). `/w/` is untyped scratch space with private-read defaults.

**Separation from `/a/`:** Operations are kept in a distinct mutable namespace from immutable data assets because code and data have different security profiles. `read(/o/)` is low-risk (open-source mindset), while `read(/a/)` exposes potentially sensitive user data. `/a/` serves as the universal immutable layer that both data and operations pin to.

#### `/j/` — Jobs

Lifecycle-managed execution records. Created by `invoke()`, transition through a state machine. Jobs are the **caller-facing accountability unit** — they answer "what happened with my request?"

**Lifecycle:** `created → queued → running → complete | failed`

- `/j/<id>/status` — mutable during execution, enforces valid state transitions only
- `/j/<id>/inputs` — pinned immutable values at invocation time
- `/j/<id>/outputs` — written on completion
- `/j/<id>/logs/` — ordered append-only log (index)

On completion, the entire job record **freezes** — inputs, outputs, execution record become immutable and content-addressable.

```json
{
  "op_name": "/o/my-transform",
  "op_pinned": "/a/cafebabe...",
  "caller": "did:venue:A:42/g/3",
  "inputs": { "arg1": "<resolved value>", "arg2": "constant" },
  "caps": ["<ucan token>"],
  "executor": "did:venue:B:99/g/7",
  "status": "complete",
  "outputs": { "result": "<computed value>" },
  "cost": 450
}
```

### 4.4 Relationship Between `/g/` (Agents), `/j/` (Jobs), and `/o/` (Operations)

This is a critical design distinction. The three execution-related namespaces serve fundamentally different roles:

| | `/o/` Operations | `/j/` Jobs | `/g/` Agents |
|---|----------------|-----------|-------------|
| **What** | Transformation definition | Execution record | Persistent actor |
| **Identity** | User-named (hashmap) | System-assigned ID | System-assigned ID, DID-bound |
| **Lifecycle** | Mutable, pins to `/a/` on invoke | Single execution, freezes on complete | Long-lived, many transitions |
| **State** | Stateless definition | Scoped to one invocation | Accumulates across transitions |
| **Initiation** | Passive — invoked by others | Created by `invoke()` | Autonomous — wakes on triggers |
| **Audit question** | "What logic ran?" | "What happened with this request?" | "What did this agent do and why?" |

**How they interact:**

**Operations are definitions.** They describe a transformation but don't execute. They're the "what."

**Jobs are executions of operations.** Every `invoke()` creates a job. The job records exactly what ran (pinned op ref), what went in (pinned inputs), what came out (outputs), and who did it (executor). Jobs are the **economic and accountability unit** — billing, SLA tracking, and dispute resolution happen at the job level. Jobs answer the caller's question: "what happened with my request?"

**Agents are actors that create and execute jobs.** An agent's transition function may invoke operations (creating jobs), send messages to other agents (inbox writes), or spawn new agents. The agent's timeline records all of this from the agent's perspective — what it saw, what it decided, what it did. The timeline answers the agent's question: "what did I do and why?"

**The critical cross-reference:** A job record references the executing agent. An agent's timeline entry references the jobs it created. This dual record provides two audit perspectives on the same work:

```
/j/2456543/                          /g/agent-3/timeline/
  op_name: "/o/my-transform"           42: {
  op_pinned: "/a/cafebabe..."              state: { ... },
  caller: "/g/agent-3"                     input: [inbox items...],
  executor: "/g/agent-7"                   actions: [
  inputs: { ... }                            { type: "invoke",
  outputs: { ... }                             op: "/o/my-transform",
  cost: 450                                    job: "/j/2456543",
                                               inputs: { ... } },
                                             ...
                                           ],
                                           ts: ...
                                         }
```

**Interaction patterns between agents:**

| Pattern | Mechanism | Creates job? | Use case |
|---------|-----------|-------------|----------|
| **Async message** | Write to `/g/<id>/inbox/` | No | Notifications, FYI, loose coordination |
| **Task assignment** | Create job in `/j/`, add to `/g/<id>/jobs/` | Yes | A2A tasks, delegated work — agent must complete or fail |
| **Task request** | `invoke()` targeting an agent-backed op | Yes | Request-response, tracked work, billable |
| **Delegation** | `invoke()` with sub-delegated UCAN caps | Yes | Agent delegates subtask to another agent |
| **Broadcast** | Write to multiple inboxes | No | Announcements, pub-sub patterns |

The key principle: **if you need accountability (cost, result, SLA), assign a job. If you need informal coordination, use inbox messages.** Jobs assigned to an agent appear in its `jobs/` index — the agent is contractually responsible for completing or failing them. Inbox messages are best-effort. Both appear in the agent's timeline for full auditability.

**Agent transitions are not jobs.** When the runtime wakes an agent and runs its transition function, this is not a job — it's an internal lifecycle event recorded in the agent's timeline. The distinction matters: jobs are created explicitly by callers and carry economic weight (cost, billing, caps). Agent transitions are managed by the runtime and are an operational concern of the agent's owner. An agent's transition may create zero, one, or many jobs as part of its processing.

#### `/g/` — Agents

Stateful actors with pluggable transition functions. Each agent is a state machine managed by the venue runtime, with user-definable behaviour. The agent namespace enforces a four-layer execution architecture.

**Lattice structure:**

```
/g/<agent-id>/

  # SYSTEM LAYER — owned by venue runtime
  status: "sleeping"            # sleeping | waking | running | suspended | terminated
  seq: 5                        # current sequence number
  caps: [<ucan>, ...]           # capability boundary — enforced on all actions
  owner: "did:venue:A:42"       # who controls this agent
  name: "my-assistant"          # human-readable name (metadata, queryable)
  description: "Code review agent"
  tags: ["code", "review"]
  wake/
    schedule: "*/5 * * * *"     # cron-like
    triggers: [                 # event-driven wake conditions
      { on: "inbox" },
      { on: "job-assigned" },
      { on: "job-complete", job: "/j/..." },
      { on: "watch", path: "/w/some/path" }
    ]
    last_run: 1719500000
    next_run: 1719500300
  inbox/                        # index — fire-and-forget messages
    0x0001: { from: "...", payload: {...}, ts: ... }
    0x0002: { from: "...", payload: {...}, ts: ... }
  jobs/                         # index — managed job assignments, system-maintained
    0x1001: { job: "/j/2456543", status: "pending", assigned: 1719500000 }
    0x1002: { job: "/j/2456544", status: "running", assigned: 1719500001 }
  timeline/                     # append-only vector of raw lattice values
    0: { state: {...}, input: [...], actions: [...], ts: ... }
    1: { state: {...}, input: [...], actions: [...], ts: ... }
    5: { state: {...}, input: [...], actions: [...], ts: ... }
  error: null

  # USER LAYER — owner configures
  config/
    model: "/o/my-transition-fn"  # transition function (any grid op)
    tools: ["/o/abc...", ...]   # grid ops available to tool loop
    llm/
      provider: "anthropic"
      model: "claude-sonnet-4-5-20250929"
      endpoint: "https://api.anthropic.com/v1"
      api_key: "/s/anthropic-key"   # ref to secrets namespace
    framework: "langchain"      # tool loop adapter
    params: { ... }             # model-specific config
```

**Four-layer execution architecture:**

| Layer | Role | Provided by | Flexibility |
|-------|------|-------------|-------------|
| **1. Runtime** | Agent lifecycle, capability enforcement on lattice actions, timeline management, action dispatch | Venue — hardcoded | None. Trust anchor. |
| **2. Transition function** | Agent behaviour. Receives `(state, inbox, jobs, config)`, returns `(new_state, actions)` | Any grid op (`/o/` ref) | Pluggable. User picks or writes their own. |
| **3. Tool loop** | ReAct / function-calling cycle within transition function. Calls grid ops, accumulates context. | Framework choice (LangChain, CrewAI, custom) | Flexible adapter. |
| **4. LLM** | Stateless inference. Pure function: prompt → completion. No lattice access, no side effects. | User's choice. API key in `/s/`. | Any provider. |

Trust decreases downward, freedom increases downward. The runtime is maximally constrained but fully trusted. The LLM is maximally free but completely untrusted.

**Secret handling:** The transition function never sees plaintext secrets. It references secrets by path (e.g. `/s/anthropic-key`). The adapter — venue-provided trusted code sitting between the transition function and external services — manages plaintext on behalf of the agent:

1. Runtime wakes agent, loads caps into the invoke context
2. Transition function requests a secret by path (`/s/anthropic-key`)
3. Adapter requests decryption from runtime within the invoke context
4. Runtime checks `decrypt(/s/anthropic-key)` against the caps already loaded for this invocation — rejects if not granted
5. Runtime decrypts and returns plaintext to the adapter
6. Adapter injects plaintext directly into the LLM call
7. Transition function receives only the completion, never the key

The runtime is the **single enforcement point** for all capability checks — lattice actions (reads, writes, invokes) and secret decryption alike. It knows the agent's full capability set within the context of each invocation. The adapter is the **trust boundary** that prevents plaintext from leaking to untrusted code — it requests decryption from the runtime within the invoke context, and the runtime enforces at that point.

| Component | Role |
|-----------|------|
| **Runtime** | Holds caps for invoke context. Single enforcement point for all lattice actions and secret decryption |
| **Adapter** | Trusted handler of plaintext. Requests decryption from runtime within invoke context, injects into external calls |
| **Transition fn** | Untrusted. References secrets by path only, never sees plaintext |

**Execution loop (runtime-managed):**

1. Evaluate wake conditions → wake if met
2. `status` → `"running"`
3. Read current state from latest timeline entry
4. Drain inbox items (messages)
5. Read pending jobs from `jobs/` index
6. Call transition function: `(state, inbox_items, pending_jobs, config)` → `{ new_state, actions }`
7. Validate all actions against `caps`
8. Append `{ state: new_state, input: { inbox: inbox_items, jobs: pending_jobs }, actions: actions, ts: now }` to timeline
9. Dispatch validated actions (invoke ops, complete/fail jobs, send messages, spawn agents)
10. Update `jobs/` status for any jobs acted on
11. `status` → `"sleeping"`, update wake conditions

The transition function is responsible for deciding what to retain in `new_state` — accumulated context, conversation history, pending tasks. Anything not written to state is discarded. Layers 3 and 4 are ephemeral; they exist only during execution with no persistent lattice presence.

**Agent tool palette:** During the tool loop (Layer 3), the agent has access to a set of MCP-style tools provided by the venue runtime. These are the agent's interface to the lattice and the outside world. The runtime validates every tool call against the agent's `caps`.

*Lattice operations (no job created):*

| Tool | Description | Caps required |
|------|-------------|---------------|
| `read` | Read a lattice path | `{ with: "did:.../path", can: "/crud/read" }` |
| `write` | Write to workspace | `{ with: "did:.../w/path", can: "/crud/write" }` |
| `list` | List keys at a path | `{ with: "did:.../path", can: "/crud/read" }` |
| `complete_job` | Complete an assigned job with result | Job must be in agent's `jobs/` index |
| `fail_job` | Fail an assigned job with reason | Job must be in agent's `jobs/` index |
| `message` | Send message to another agent's inbox | `{ with: "did:.../g/<id>/inbox/", can: "/agent/message" }` |
| `assign_job` | Create job and assign to another agent | `{ with: "did:.../g/<id>", can: "/agent/message" }` + invoke caps |
| `read_secret` | Request secret decryption (adapter handles plaintext) | `{ with: "did:.../s/key", can: "/secret/decrypt" }` |
| `get_state` | Read own current state | Implicit — always available |
| `log` | Append to agent notes (included in timeline) | Implicit — always available |

*Job-creating operations:*

| Tool | Description | Caps required |
|------|-------------|---------------|
| `invoke` | Invoke a grid operation — creates a job in `/j/` | `{ with: "did:.../o/op-name", can: "/invoke" }` |
| `mcp_call` | Call an external MCP server tool — creates a job for tracking | Caps on the MCP server binding |

*Lifecycle operations:*

| Tool | Description | Caps required |
|------|-------------|---------------|
| `spawn_agent` | Create a new agent | `{ with: "did:.../g/", can: "/crud/write" }` |
| `fork_agent` | Fork an existing agent | `{ with: "did:.../g/<id>", can: "/agent/fork" }` |
| `delegate` | Sub-delegate UCAN capabilities | `{ with: "did:...", can: "/ucan/delegate" }` |

The tool palette maps directly to MCP tool definitions — each tool has a name, description, and JSON schema for args. This means the LLM (Layer 4) sees these as standard tool calls, regardless of framework. External MCP servers add additional tools via the adapter.

**Mapping to standard LLM agent patterns (LangChain, OpenAI, etc.):**

| Standard LLM concept | Covia equivalent | Location |
|---|---|---|
| System prompt | Agent config params or baked into transition fn | `/g/<id>/config/params/` or `/o/transition-fn` |
| Chat history / messages | Reconstructed from agent state | Previous `new_state` in timeline |
| Tool definitions | Agent tool palette (above) + external MCP tools | Runtime-provided + `/g/<id>/config/tools` |
| User input / task | Inbox messages + pending jobs | Passed to transition fn as `inbox_items` + `pending_jobs` |
| Agent scratchpad | Ephemeral — lives only during Layer 3 tool loop | Not persisted to lattice |
| Tool call → result loop | Tool loop calls `read`, `invoke`, `mcp_call`, etc. | Within single transition fn execution |
| Final answer | `complete_job` / `message` actions in output | Returned as `actions` from transition fn |
| Persisted memory | `new_state` returned by transition fn | Written to timeline, available next wake |

The key insight: the agent scratchpad (intermediate tool calls and results during a single reasoning turn) is **ephemeral within Layer 3**. The lattice only sees the inputs (inbox + jobs), the outputs (new_state + actions), and the tool calls are recorded in the timeline for audit. The LLM (Layer 4) is completely stateless — it receives a prompt and returns a completion. All persistence is managed by the transition function's `new_state`.

**Timeline efficiency:** Each timeline entry is a raw lattice value, not a pinned `/a/` ref. CAD3 structural sharing means unchanged subtrees between transitions share storage automatically. The timeline can grow indefinitely without proportional storage cost.

**Communication:** Sending a message to another agent is writing to their inbox — local: `/g/0x0042/inbox/`, remote: `did:venue:B:99/g/0x0089/inbox/` — with appropriate write capability via UCAN.

**Discoverability:** Agents have system-assigned IDs (index keys), not user-chosen names. Human-readable names, descriptions, and tags are metadata in the agent record, queryable for discovery (e.g. "find all agents tagged 'code-review'"). Users can optionally create workspace aliases for convenience (e.g. `/w/agents/my-assistant → /g/0x0042`), but this is a user convention, not a system feature. The canonical reference is always the system ID via DID: `did:venue:A:42/g/0x0042`.

**Agent mobility and forking:**

Because agent state is an immutable lattice value with no hidden mutable references — no in-memory state, no file handles, no connections, just data — agents are inherently portable. Nothing binds an agent to a specific venue beyond the runtime (which is the same everywhere) and DID resolution.

**Migration** moves an agent between venues:

1. Suspend agent at venue A (status → suspended, drain inbox)
2. Read full agent state: timeline, config, caps
3. Create agent at venue B with same DID, same state
4. Update DID resolution to point to venue B
5. Resume at venue B

**Forking** creates a new agent from an existing agent's state with optionally modified config:

```
fork(/g/agent-3, {
  owner: "did:venue:A:99",           # new owner
  config: {
    model: "/o/newmodel...",          # different transition function
    llm: { provider: "openai", ... } # different LLM
  },
  caps: [<new ucan set>],            # scoped for new owner
  state: "latest"                     # or specific timeline seq
})
```

The forked agent gets a new DID but carries full state history up to the fork point. The timeline is shared via CAD3 structural sharing — no duplication. From the fork point, the two agents diverge independently.

This makes **"create a pre-configured agent to do X"** a simple grid operation. A vendor or community publishes a well-tuned agent — trained state, proven transition function, tested config. A user forks it with their own ownership, caps, and secrets:

```
invoke("/o/fork-agent", {
  source: "did:venue:vendor:1/g/smart-assistant",
  overrides: {
    owner: "did:venue:A:42",
    caps: [<my caps>],
    config: {
      llm: { api_key: "/s/my-anthropic-key" }
    }
  }
})
```

The result is a fully configured agent with the vendor's accumulated knowledge and behaviour, running under the user's ownership, with the user's capabilities and secrets. The vendor's original is unchanged.

**Use cases enabled by forking:**

| Pattern | Description |
|---------|------------|
| **Marketplace agents** | Vendor publishes base agent, users fork with own config/ownership |
| **Team provisioning** | Fork a team-standard agent for each new team member |
| **A/B testing** | Fork an agent, modify config, compare outcomes |
| **Checkpoint & rollback** | Fork from a specific timeline point to recover from bad state |
| **Specialisation** | Fork a general agent, fine-tune config for a specific domain |
| **Cross-venue redundancy** | Fork to multiple venues for resilience |

**Identity and provenance:** The forked agent's metadata records its lineage:

```
/g/<new-agent-id>/
  provenance: {
    forked_from: "did:venue:vendor:1/g/smart-assistant",
    fork_point: 42,          # timeline sequence number
    fork_ts: 1719500000
  }
```

This enables provenance tracking, licensing verification, and update propagation — a vendor can publish state updates that downstream forks can optionally merge.

#### `/w/` — Workspace

Mutable working space for agents and users. Scratch data, drafts, intermediate results. The "hot" layer.

- Freely mutable — no transition constraints
- Hashmap-based — user defines arbitrary structure
- Any CAD3 type is valid, but JSON-like structures get the best tooling support
- Non-JSON structures will store and hash correctly but won't render nicely in UIs or chain through operations cleanly

#### `/s/` — Secrets

Encrypted, capability-gated data. Different encryption and access semantics from all other namespaces.

- Encrypted at rest — `read(/s/)` returns ciphertext only
- Decryption requires explicit `decrypt` capability via UCAN with key delegation
- Convergent encryption ties back to CAD3
- Reading the namespace is low-risk; the real security boundary is the decrypt capability

---

## 5. Operations and Invocation

### 5.1 Invocation Model

Operations are invoked with explicit inputs and capabilities:

```
invoke("someop",
  { arg1: lookup(/my/data), arg2: "constant" },
  [ { with: "did:venue:A:42/a/cafebabe...", can: "/crud/read" } ]
)
```

- `lookup()` resolves mutable paths **caller-side** at invocation time
- The operation receives **pure JSON** — no paths, no lattice access
- A job record is created in `/j/` with pinned inputs
- Capabilities are passed as an array of UCAN `with`/`can` pairs (see §6)

### 5.2 Security Model

Operations never have implicit lattice access. The invocation boundary is the security boundary:

- **Inputs are values** — `lookup()` resolves references before passing to the operation. The operation sees data, not cursors.
- **Capabilities are explicit** — if an input contains a DID ref that the operation needs to resolve, a corresponding capability must be granted in the caps argument.
- **No path escalation** — the operation cannot read or write any lattice path not covered by a granted capability.

### 5.3 Capability Scoping via Paths

Capabilities inherit the namespace path hierarchy. The risk profile follows from namespace semantics — code is inspectable, data is sensitive, secrets are encrypted. Each capability is a `with` (resource URI) + `can` (command) pair per the UCAN spec (see §6.2 for full details).

| Capability | Risk Level |
|-----------|------------|
| `with: did:.../o/`, `can: /crud/read` | Low — code is inspectable, open-source model |
| `with: did:.../o/cafebabe`, `can: /invoke` | Medium — consumes compute resources |
| `with: did:.../a/cafebabe`, `can: /crud/read` | Medium — exposes user data |
| `with: did:.../a/`, `can: /crud/read` | High — exposes all user data |
| `with: did:.../w/projects/foo/`, `can: /crud/read` | Medium-High |
| `with: did:.../w/`, `can: /crud/read` | High — live working state |
| `with: did:.../s/`, `can: /crud/read` | Low — returns ciphertext only |
| `with: did:.../s/key`, `can: /secret/decrypt` | Highest — reveals plaintext |
| `with: did:.../w/results/`, `can: /crud/write` | Medium |
| `with: did:.../`, `can: /` | Maximum — trusted agents only |

### 5.4 Namespace Security Symmetry

The six namespaces form a natural grid. `/a/` is the universal immutable layer that all other namespaces pin to. The remaining namespaces are mutable with different constraints:

| | Mutable Registry | Working State | Controlled |
|---|-----------------|---------------|------------|
| **Data** | — | `/w/` — workspace | `/s/` — secrets |
| **Execution** | `/o/` — operations | `/g/` — agents | `/j/` — jobs |
| **Immutable** | `/a/` — universal pinning target for both data and operations |

Default read postures: `/o/` defaults open (code is inspectable), `/w/` and `/a/` default private (user data), `/s/` read returns ciphertext (decryption requires explicit capability).

### 5.4 Output Convention

Operation output is arbitrary CAD3. The lattice stores and hashes any valid CAD3 value. However, JSON-like outputs enable path navigation into results, chaining into subsequent operations, human-readable inspection in UIs, and interop with the wider ecosystem.

---

## 6. Capability Model — UCAN

Capabilities are expressed as UCAN tokens, represented as lattice-native JSON-like values (not JWT/base64). The capability model follows the UCAN specification's `with`/`can` structure, where each capability pairs a resource (noun) with a command (verb). Each UCAN is cryptographically signed by the issuer, enabling verifiable delegation chains without any central authority.

### 6.1 UCAN Structure

```json
{
  "iss": "did:venue:A:42",
  "aud": "did:venue:B:99/g/7",
  "att": [
    { "with": "did:venue:A:42/w/projects/foo/", "can": "/crud/read" },
    { "with": "did:venue:A:42/o/cafebabe...", "can": "/invoke" }
  ],
  "exp": 1719500000,
  "nbf": 1719400000,
  "nnc": "a1b2c3d4e5",
  "fct": [{ "grid-version": "1.0" }],
  "prf": ["/a/cafebabe..."],
  "sig": "0xdeadbeef..."
}
```

- **`iss`** — Issuer DID. Signs this token with their Ed25519 private key.
- **`aud`** — Audience DID. The agent/operation receiving the capability.
- **`att`** — Attenuations. Array of capabilities, each a `with` (resource URI) + `can` (command) pair.
- **`exp`** — Expiry. Unix timestamp, token invalid after this time.
- **`nbf`** — Not Before. Unix timestamp, token invalid before this time.
- **`nnc`** — Nonce. Ensures uniqueness, prevents replay attacks.
- **`fct`** — Facts. Additional signed assertions (metadata, context).
- **`prf`** — Proof chain. References to parent UCANs (as `/a/` immutable refs), forming the delegation chain.
- **`sig`** — Ed25519 signature over the CAD3 hash of all fields except `sig`.

### 6.2 Covia Resource and Command Scheme

**Resources** use the DID URI directly as the resource identifier. Since DIDs are already valid URIs, no custom scheme is needed — the DID identifies the authority and the path scopes into the lattice:

| Resource URI | Meaning |
|-------------|---------|
| `did:venue:A:42/` | Everything in this identity's lattice |
| `did:venue:A:42/w/` | All workspace data |
| `did:venue:A:42/w/projects/foo/` | Specific workspace subtree |
| `did:venue:A:42/a/cafebabe...` | Specific immutable asset |
| `did:venue:A:42/o/` | All operations |
| `did:venue:A:42/s/anthropic-key` | Specific secret |

Sub-path URIs are valid attenuations of their parent — `did:.../w/projects/foo/` is a valid delegation from `did:.../w/`.

**Commands** follow UCAN's slash-delimited convention. Command hierarchy works via path prefix — shorter commands prove longer ones (e.g. `/crud` proves `/crud/read`). `/` (top) proves any command.

| Command | Meaning | Typical use |
|---------|---------|------------|
| `/` | Top — proves any command | Full delegation to trusted agents |
| `/crud/read` | Read data | Asset access, workspace reads |
| `/crud/write` | Write data | Workspace mutations |
| `/crud/delete` | Delete data | Workspace cleanup |
| `/invoke` | Execute an operation | Calling grid ops |
| `/invoke/async` | Execute asynchronously | Fire-and-forget job submission |
| `/agent/fork` | Fork an agent | Creating agent copies |
| `/agent/message` | Write to agent inbox | Agent-to-agent communication |
| `/secret/decrypt` | Decrypt a secret | Accessing plaintext via adapter |
| `/ucan/delegate` | Sub-delegate capabilities | Agent delegation chains |
| `/ucan/revoke` | Revoke a UCAN | Cancelling granted capabilities |

### 6.3 Capability Risk Hierarchy

The combination of resource URIs and commands creates a natural risk hierarchy:

| Capability | Risk Level |
|-----------|------------|
| `with: did:.../o/`, `can: /crud/read` | Low — code is inspectable |
| `with: did:.../o/cafebabe`, `can: /invoke` | Medium — consumes compute |
| `with: did:.../a/cafebabe`, `can: /crud/read` | Medium — specific data |
| `with: did:.../a/`, `can: /crud/read` | High — all user data |
| `with: did:.../w/`, `can: /crud/read` | High — live working state |
| `with: did:.../s/key`, `can: /secret/decrypt` | Highest — reveals plaintext |
| `with: did:.../`, `can: /` | Maximum — full delegation |

### 6.4 Signing and Verification

The signing chain works because every DID resolves to a public key (anchored on-chain via CVM). Verification is purely local:

1. **Verify signature** — resolve issuer's DID to public key, verify `sig` against CAD3 hash of UCAN content
2. **Check time bounds** — reject if before `nbf` or after `exp`
3. **Check nonce** — reject if previously seen (replay prevention)
4. **Check revocation** — look up UCAN's `/a/` hash in revocation lists
5. **Walk proof chain** — for each parent UCAN in `prf`:
   - Verify the parent's signature the same way
   - Confirm the parent's `aud` matches this UCAN's `iss` (delegation is continuous)
   - Confirm attenuation is valid — each `with` must be equal or sub-path of parent's, each `can` must be equal or sub-command of parent's
6. **Verify root** — the chain terminates at a root UCAN where the issuer is the resource owner

No callbacks, no token servers, no online verification. The UCAN, its proof chain, and the on-chain DID→key mapping are sufficient. This is critical for P2P and federated execution where the verifying venue may have no relationship with the issuer.

### 6.5 Key Properties

**Lattice-native.** UCANs are first-class lattice objects, stored as content-addressable values in `/a/`. No separate token format — they're stored, referenced, delegated, and verified using the same addressing and content-addressing as everything else. The CAD3 hash of a UCAN is its canonical identifier.

**Delegation chains.** An agent can sub-delegate a narrower capability:

1. User grants agent `{ with: "did:.../w/", can: "/crud/read" }` — signs UCAN with user's key
2. Agent invokes an operation granting only `{ with: "did:.../w/projects/foo/", can: "/crud/read" }` — signs new UCAN with agent's key, references parent in `prf`
3. Any verifier walks the chain: resource attenuated (sub-path), command unchanged, signatures valid

**Self-contained.** UCANs travel with job submissions across P2P boundaries. The full proof chain is embedded or referenced via `/a/` hashes. Any executing venue validates locally.

**Revocation.** Publish a signed revocation record referencing the UCAN's `/a/` hash. Verifiers check revocation lists as part of chain validation. Revocation propagates through the lattice like any other data.

---

## 7. Federation

The DID prefix enables federated execution across venue boundaries.

### 7.1 Cross-Boundary References

An agent in venue A can reference an operation in venue B:

```
did:venue:A:42/o/transform → did:venue:B:99/a/cafebabe...
```

- **Immutable refs cross boundaries trivially.** `/a/cafebabe...` is self-verifying regardless of origin. Any peer, any venue — same hash, same data.
- **Mutable refs require trust negotiation.** Resolving another venue's mutable path requires a federation handshake to access their current lattice state.

### 7.2 Federated Job Execution

Jobs can span venues — a DAG where some operations execute in venue A, some in venue B.

- Job submission pins all inputs to immutable refs
- UCAN capability tokens travel with the job request
- Each venue verifies the UCAN chain independently
- Economic settlement crosses back to CVM — venue A pays venue B for compute via on-chain settlement

### 7.3 Data Flow Rules

| Scenario | Data Model |
|----------|-----------|
| Local operation, local data | Value-passing or path-passing (same trust domain) |
| Remote operation, local data | Value-passing — resolve and pin before sending |
| Any operation, immutable ref | Safe to share — self-verifying, read-only |
| Any operation, mutable path | Never leaves boundary unless explicitly exported |

---

## 8. Protocol Interoperability — MCP and A2A

Covia agents interoperate with external AI protocols through venue-provided adapters. All external interactions are recorded as structured lattice data, making them managed and auditable alongside native grid operations.

### 8.1 MCP (Model Context Protocol)

MCP maps naturally to the tool loop (Layer 3). The adapter bridges between MCP's client-server model and grid ops.

**Outbound MCP** — Covia agent calls external MCP servers:

The adapter wraps external MCP tools so the transition function can call them like grid ops. Results flow back into agent state.

**Inbound MCP** — External LLMs/agents call Covia grid ops via MCP:

The adapter exposes selected grid ops as MCP tools and grid assets as MCP resources. Inbound requests are translated into `invoke()` calls with caps scoped to the caller.

| MCP Concept | Grid Equivalent |
|-------------|-----------------|
| Tool | Grid op (`/o/`) |
| Resource | Asset (`/a/`) or workspace path (`/w/`) |
| Tool call | `invoke()` with caps |
| Tool result | Job output |

### 8.2 A2A (Agent-to-Agent Protocol)

A2A maps to the runtime layer (Layer 1) — agent discovery and task delegation align with the inbox/job model.

| A2A Concept | Grid Equivalent |
|-------------|-----------------|
| Agent Card | Generated from agent metadata + venue context (see §8.2.1) |
| Task | Inbox item → Job (`/j/`) |
| Task status | Job lifecycle states (created → running → complete) |
| Artifact | Job outputs / assets in `/a/` |
| Message | Inbox items with job correlation |
| AgentSkill | Maps to ops in `/o/` with schema metadata |

#### 8.2.1 Agent Card Generation

A2A Agent Cards are **generated at request time** by the venue adapter, not stored as a single blob. Most fields are derived from venue infrastructure or agent metadata already present in `/g/`. Only a few fields need to be explicitly stored in the agent record.

**What the agent stores (in `/g/<id>/`):**

| Field | Location | Notes |
|-------|----------|-------|
| `name` | `/g/<id>/name` | User-defined, human-readable |
| `description` | `/g/<id>/description` | User-defined |
| `tags` | `/g/<id>/tags` | User-defined, queryable |
| `skills` | `/g/<id>/config/skills` | User-defined — the key field (see below) |
| `defaultInputModes` | `/g/<id>/config/protocols/a2a/inputModes` | Optional override of venue default |
| `defaultOutputModes` | `/g/<id>/config/protocols/a2a/outputModes` | Optional override of venue default |

**What the venue derives at generation time:**

| A2A Field | Derived from | Why not stored |
|-----------|-------------|----------------|
| `url` | Venue endpoint + agent ID | Venue knows its own URL |
| `version` | Agent `seq` or config hash | Changes on every transition |
| `provider` | Venue identity metadata | Same for all agents in venue |
| `protocolVersion` | Venue's A2A adapter version | Venue infrastructure |
| `capabilities.streaming` | Venue runtime capabilities | Venue knows what it supports |
| `capabilities.pushNotifications` | Venue runtime capabilities | Venue infrastructure |
| `capabilities.stateTransitionHistory` | Always true — timeline exists | Inherent to grid model |
| `securitySchemes` | Venue auth infrastructure | Venue-level config |
| `supportedInterfaces` | Venue transport bindings | Venue-level config |

#### 8.2.2 Skills Definition

Skills are the critical user-defined field — they describe what this specific agent can do. Skills follow the A2A AgentSkill schema directly:

```
/g/<agent-id>/config/
  skills: [
    {
      id: "code-review",
      name: "Code Review",
      description: "Reviews code for bugs, style, and security issues",
      tags: ["code", "review", "security"],
      examples: ["Review this PR for security issues", "Check my Python code"],
      inputModes: ["text/plain"],
      outputModes: ["text/plain", "application/json"]
    },
    {
      id: "summarize",
      name: "Document Summarization",
      description: "Summarizes documents and extracts key points",
      tags: ["text", "summarization"],
      examples: ["Summarize this report", "What are the key findings?"],
      inputModes: ["text/plain", "application/pdf"],
      outputModes: ["text/plain"]
    }
  ]
```

Skills describe *what* the agent can do, not *how*. How the agent fulfils a skill is opaque — it may use its transition function, tool loop, direct op invocation, or any combination. This aligns with A2A's principle that agents are opaque actors.

`inputModes` and `outputModes` are optional per-skill overrides. If omitted, the agent's defaults (or venue defaults) apply.

When the venue adapter generates an A2A Agent Card, it:

1. Reads `name`, `description`, `tags` from agent metadata
2. Copies `skills` directly — they already match the A2A AgentSkill schema
3. Fills in venue-derived fields (`url`, `provider`, `capabilities`, `securitySchemes`, etc.)
4. Returns a complete A2A-compliant Agent Card

This means the agent author only defines what's unique to their agent. Everything else comes from the venue. And if the venue upgrades its A2A support (e.g. adds streaming), all agent cards automatically reflect the new capability without any per-agent changes.

### 8.3 Protocol Configuration

Protocol support is declared in the agent's user-layer config:

```
/g/<agent-id>/config/
  protocols/
    mcp/
      server: true
      tools: ["/o/abc..."]
      resources: ["/a/...", "/w/public/..."]
    a2a/
      inputModes: ["text/plain", "application/json"]   # optional override
      outputModes: ["text/plain", "application/json"]   # optional override
    webhooks/
      endpoints: [...]
```

Skills are defined at the top level of config (not nested under `a2a/`) because they describe the agent's capabilities regardless of protocol — the same skills are relevant for native grid discovery, A2A, and any future protocol adapters.

### 8.4 Structured Interaction Records

All protocol interactions are recorded as structured lattice data within the agent's timeline and the jobs namespace. This ensures full auditability regardless of whether the interaction was native or bridged.

**Inbound interactions** (external → grid) are recorded as inbox items with protocol metadata:

```
/g/<agent-id>/inbox/
  0x0042: {
    protocol: "a2a",
    type: "task",
    from: "did:external:agent-xyz",
    task_id: "a2a-task-9876",
    payload: { ... },
    ts: 1719500000
  }
  0x0043: {
    protocol: "mcp",
    type: "tool_call",
    from: "did:external:client-abc",
    method: "transform",
    params: { ... },
    ts: 1719500001
  }
```

**Outbound interactions** (grid → external) are recorded as actions in the timeline entry:

```
/g/<agent-id>/timeline/
  0x0010: {
    state: { ... },
    input: [{ protocol: "a2a", task_id: "a2a-task-9876", ... }],
    actions: [
      { type: "invoke", op: "/o/abc...", inputs: { ... } },
      { type: "mcp_call", server: "did:external:tool-server", method: "fetch", params: { ... }, result: { ... } },
      { type: "a2a_respond", task_id: "a2a-task-9876", status: "completed", artifacts: ["/a/eee..."] }
    ],
    ts: 1719500005
  }
```

**Jobs created from external requests** carry protocol provenance:

```
/j/2456543/
  op_name: "/o/my-transform"
  op_pinned: "/a/cafebabe..."
  inputs: { ... }
  provenance: {
    protocol: "a2a",
    task_id: "a2a-task-9876",
    from: "did:external:agent-xyz",
    received: 1719500000
  }
  status: "complete"
  outputs: { ... }
```

This means every interaction — native grid, MCP, A2A, webhook — ends up as structured lattice data with the same path-navigable, auditable, content-addressable properties. You can query "all A2A tasks this agent handled", "all MCP tool calls in this job", or "full interaction history with agent-xyz" by walking the lattice.

### 8.5 Native vs. Bridged

| Communication | Mechanism | Overhead |
|--------------|-----------|----------|
| **Native** (grid agent → grid agent) | Direct inbox write with UCAN caps | Minimal — lattice-native |
| **Bridged** (MCP / A2A / webhook) | Venue-provided protocol adapter | Adapter translates, records, enforces caps |

The grid's security model (caps, runtime enforcement) applies uniformly regardless of protocol origin. The adapters are the bridge between external protocols and the grid's native model — they translate, record, and enforce, but never bypass the runtime.

---

## 9. CVM Integration

The P2P lattice handles data flow. The on-chain CVM handles:

- **Identity anchoring** — DID to public key binding
- **Economic settlement** — payments for compute, storage, bandwidth
- **Pinning critical refs** — anchoring immutable snapshots for high-assurance use cases
- **Agent registration** — on-chain identity for agents participating in the economy

The CVM is the economic and trust layer, not the data layer.

---

## 10. Data Type Conventions

### 10.1 CAD3 Foundation

All data in the lattice is CAD3 — content-addressable, hash-verified. Any valid CAD3 type can be stored at any path.

### 10.2 JSON-like Convention

The ecosystem is optimised for JSON-like structures (hashmaps with string keys, standard value types). Benefits of following this convention: operations can destructure inputs and chain outputs, UIs render data as readable documents, paths navigate cleanly into nested structures, and agents and tools interoperate seamlessly.

### 10.3 When to Diverge

Using non-JSON CAD3 types (blobs, vectors, sets) is valid in `/w/` workspaces and as operation outputs. The data will store, hash, and replicate correctly. However, tooling support (UIs, chaining, path navigation) will be limited.

---

## 11. Summary

The Covia Grid lattice structure provides a unified data and execution model where everything reduces to structured immutable data with references. Mutable cursors allow live iteration while immutable snapshots provide auditability. Typed namespaces enforce security and execution semantics at the lattice level. Operations are pure transformations invoked with resolved values and explicit UCAN capabilities. Federation falls out naturally from DID-qualified addressing and self-verifying content-addressed data.

| Layer | Mechanism |
|-------|-----------|
| Addressing | `did:venue:<id>/<namespace>/<path>` |
| Immutability | Content-addressed `/a/` refs, frozen job records |
| Mutability | Named cursors in `/w/`, lifecycle state in `/j/` |
| Security | UCAN capability tokens, lattice-native |
| Execution | `invoke(op, inputs, caps)` → job lifecycle |
| Federation | DID-scoped paths, self-verifying immutable refs |
| Economics | CVM on-chain settlement |
| Data format | CAD3 universal, JSON-like convention |
