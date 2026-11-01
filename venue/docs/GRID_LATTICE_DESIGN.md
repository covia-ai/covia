# Covia Grid Lattice Structure

How Covia lays out its state in the Convex lattice: addressing, namespaces, how
a caller's path reaches stored data, and how venue state is merged, signed and
persisted. `covia.lattice.Covia` defines the lattice in code.

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
- **One kind per level.** A level holds records or sub-namespaces, never both: `v/skills` holds skillsets and `v/skills/root` holds skills. A reference's kind is declared by the field it appears under (`skills` versus `skillsets`), never inferred from what it resolves to, so a reference of the wrong kind is reported rather than silently mis-resolved.

---

## 3. Addressing Scheme

### 3.1 Global Address Format

```
<DID> / <namespace> / <path...>
```

- **DID prefix** — globally unique identity anchoring the lattice context (venue + account). Any standard DID method works — `did:key:` for self-certifying identities (venues, agents with key pairs), `did:web:` for server-backed identities, or any method that resolves to a public key for signature verification. The addressing scheme is DID-method-agnostic.
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

- `did:key:zBob.../o/transform` — referencing another venue's operation

The lattice resolves implicit context based on the active session identity.

---

## 4. Namespaces

Each user record holds the stored namespaces below. Request-relative
shortcuts (`n/`, `c/`, `t/`, `v/`, `x/`, §4.5) name places inside them or
elsewhere in the venue.

### 4.1 Namespace Summary

| Namespace | Prefix | Holds | Changed through `covia:write` / `covia:delete` |
|-----------|--------|-------|-------------------------------------------------|
| Assets | `/a/` | Content-addressed asset references | No |
| Operations | `/o/` | The user's named operation definitions | Yes |
| Jobs | `/j/` | Job records by ID | No; `t/` scratch within a job |
| Agents | `/g/` | Agent records by ID | No; `n/` and `c/` scratch within an agent run |
| Workspace | `/w/` | User and agent data | Yes |
| Secrets | `/s/` | Encrypted secrets | Delete of a whole secret only; set through `secret:set` |
| HITL inbox | `/h/` | Human-in-the-loop requests | No; the `hitl` operations |
| Extensions | `/x/<adapter>/` | An adapter's view for the addressed user | As the adapter allows; read-only by default |

An extension route selects the active adapter's view for the addressed user.
It is independent of private `:adapters/<name>` storage and exposes nothing
unless the adapter opts in. Standard accessors and CRUD capabilities apply;
writable views use the authoritative venue's atomic updates. See
[ADAPTERS.md](ADAPTERS.md#user-visible-extension-routes) for the hook contract.

### 4.2 Storage Types

`j/` is an Index keyed by job ID, so jobs list in ID order and support range
scans. The other namespaces are hashmaps with JSON-like navigation. Inside an
entry any structure may appear: a job record is a hashmap, and a list inside it
may be a vector.

### 4.3 Namespace Details

#### `/a/` — Assets

Immutable, content-addressed data, referenced by CAD3 hash.

- Keys are content hashes
- No mutation; an asset stays resolvable until it is garbage-collected
- Self-verifying — any peer can validate data against its hash

#### `/o/` — Operations

The user's own operation definitions under names they choose, such as
`/o/my-transform`. A definition is operation metadata with an `operation` field
naming the adapter operation that runs it, so a stable name can move to a
different implementation without breaking its callers. Values are written with
`covia:write` like `/w/`; the definition is read and checked when it is invoked.

`invoke()` accepts an operation from any of these places: `/o/my-transform`,
`/w/my-draft-op`, a venue operation such as `v/ops/json/merge`, a content hash,
or another venue's path such as `did:key:zBob.../o/transform`. The job's `op`
records the reference that was invoked; an inline definition is recorded by its
hash. See [OPERATIONS.md](OPERATIONS.md).

#### `/j/` — Jobs

Lifecycle-managed execution records. Created by `invoke()`, transition through a state machine. Jobs are the **caller-facing accountability unit** — they answer "what happened with my request?"

**Lifecycle:** `PENDING → STARTED → COMPLETE | FAILED | CANCELLED | REJECTED` (terminal, sticky), plus the paused family `PAUSED`, `INPUT_REQUIRED`, `AUTH_REQUIRED` (resume to `STARTED`).

A job is stored as a single current-state record at `/j/<id>` — `{status, op, input, output?, error?, caller, created, updated, ...}`. Updates replace the state without retaining earlier versions. Legacy top-level `prev` links are ignored on reads and removed on the next write, without a startup scan. Terminal states are **sticky**: the CAS update path refuses transitions out of a terminal state, so the terminal record's `output`/`error` never change (the record entry itself remains deletable and secret-redacted on every durable write). [JOBS.md](JOBS.md) defines the record and its lifecycle.

### 4.4 Relationship Between `/g/` (Agents), `/j/` (Jobs), and `/o/` (Operations)

This is a critical design distinction. The three execution-related namespaces serve fundamentally different roles:

| | `/o/` Operations | `/j/` Jobs | `/g/` Agents |
|---|----------------|-----------|-------------|
| **What** | Transformation definition | Execution record | Persistent actor |
| **Identity** | User-named (hashmap) | System-assigned ID | User-chosen string, DID-bound |
| **Lifecycle** | Mutable, pins to `/a/` on invoke | Single execution, freezes on complete | Long-lived, many transitions |
| **State** | Stateless definition | Scoped to one invocation | Accumulates across transitions |
| **Initiation** | Passive — invoked by others | Created by `invoke()` | Triggered via `agent:request` (external) or scheduler (internal) |
| **Audit question** | "What logic ran?" | "What happened with this request?" | "What did this agent do and why?" |

**How they interact:**

**Operations are definitions.** They describe a transformation but don't execute. They're the "what."

**Jobs are executions of operations.** Every `invoke()` creates a job. The job records exactly what ran (pinned op ref), what went in (pinned inputs), what came out (outputs), and who did it (executor). Jobs are the **economic and accountability unit** — billing, SLA tracking, and dispute resolution happen at the job level. Jobs answer the caller's question: "what happened with my request?"

**Agents are actors that create and execute jobs.** An agent's transition function may invoke operations (creating jobs), send messages to other agents (via session.pending), or spawn new agents. The agent's timeline records all of this from the agent's perspective — what it saw, what it decided, what it did. The timeline answers the agent's question: "what did I do and why?"

**The critical cross-reference:** A job record references the executing agent. An agent's timeline entry references the jobs it created. This dual record provides two audit perspectives on the same work:

```
/j/2456543/                          /g/my-assistant/timeline/
  op_name: "/o/my-transform"           42: {
  op_pinned: "/a/cafebabe..."              start: 1719500000,
  caller: "/g/my-assistant"                end: 1719500005,
  executor: "/g/helper"                     op: "test:echo",
  inputs: { ... }                          state: { ... },
  outputs: { ... }                         tasks: [resolved task data...],
  cost: 450                                messages: [session pending...],
                                           result: { ... },
                                           taskResults: { ... }
                                         }
```

**Interaction patterns between agents:**

| Pattern | Mechanism | Creates job? | Use case |
|---------|-----------|-------------|----------|
| **Async message** | `agent:message` — appends to session's `pending` queue | No | Notifications, FYI, loose coordination |
| **Chat** | `agent:chat` — synchronous request for a response on a session | Yes | Conversational interaction, A2A `message/send` |
| **Task request** | `agent:request` — adds Job ID to agent's `tasks` index. The request Job IS the task. | Yes | Primary mechanism. MCP users, A2A, delegated work. Agent must complete or reject. |
| **Delegation** | `invoke()` with sub-delegated UCAN caps | Yes | Agent delegates subtask to another agent |

The key principle: **if you need accountability (cost, result, SLA), use `agent:request`. If you need informal coordination, use messages.** Tasks persist until the agent explicitly completes or rejects them. Messages are session-scoped and recorded in session history. Both appear in the agent's timeline for full auditability.

**Agent runs are internal.** When the runtime wakes an agent and runs its transition function, this is an internal lifecycle event (currently implemented as an `agent:trigger` Job for infrastructure reasons, but conceptually an operational concern of the runtime). The `agent:trigger` Job is not visible to external callers — they interact with the agent via `agent:request` and track the request Job. An agent's transition may create zero, one, or many additional jobs as part of its processing.

#### `/g/` — Agents

Stateful actors with pluggable transition functions. Each agent is a state machine managed by the venue runtime, with user-definable behaviour.

Agent IDs are human-readable strings chosen by the user (e.g. `"my-assistant"`, `"code-reviewer"`). The canonical lattice address is `did:key:zAlice.../g/my-assistant`.

Each agent is a single atomic record — local mutations replace the record through compare-and-swap, so concurrent venue threads cannot lose updates. The record's `ts` is last-modified metadata, not a nested merge discriminator: replication merges the complete signed venue `:value` snapshot through the outer whole-value LWW lattice.

**What users see.** The user-visible agent surface is:

- **`config`** — the agent's declared behaviour: transition operation, name, description, tags, A2A skills, tool palette, protocol settings. This is what an author writes when creating an agent.
- **`status`** — lifecycle plus persisted execution marker (`SLEEPING` / `RUNNING` / `SUSPENDED` / `TERMINATED`). The hosting venue validates RUNNING against its live launcher and clears stale markers at startup.
- **`n/`** — the agent's own workspace, and **`c/`** in each session — scratch the agent writes through the shortcuts in §4.5.

Internal framework fields (sessions, tasks, pending, timeline, caps, scheduling state) are managed by the runtime and are not a stable user-writable surface. Their structure is documented where it's authoritative:

- **Sessions and tasks:** [AGENT_SESSIONS.md](./AGENT_SESSIONS.md)
- **Scheduling (wake times):** [AGENT_LOOP.md §4.6](./AGENT_LOOP.md); the grid scheduler engine: [GRID_SCHEDULER.md](./GRID_SCHEDULER.md)
- **Transition function contract and timeline:** [AGENT_LOOP.md](./AGENT_LOOP.md)

**Execution architecture, transition function contract, operations, scheduling, and timeline entry structure** are defined in AGENT_LOOP.md. In summary: the venue runtime manages agent lifecycle through a three-level architecture (agent update → transition function → LLM call), where only the transition function is pluggable. See AGENT_LOOP.md §2–§4 for full details.

**Secret handling:** Agents reference secrets by path (e.g. `/s/anthropic-key`). The runtime is the single enforcement point for capability checks including secret decryption. The adapter is the trust boundary — it handles plaintext on behalf of the agent and injects it into external calls. The transition function never sees plaintext secrets.

| Component | Role |
|-----------|------|
| **Runtime** | Holds caps for invoke context. Single enforcement point for all lattice actions and secret decryption. |
| **Adapter** | Trusted handler of plaintext. Requests decryption from runtime, injects into external calls. |
| **Transition fn** | Untrusted. References secrets by path only, never sees plaintext. |

**Authority over agents:** the capability each agent operation needs is listed
in [UCAN.md §5.3](UCAN.md#53-enforcement-points); the subset exposed as LLM
tools is in AGENT_LOOP.md §3.6.

**Timeline efficiency:** Each timeline entry is a raw lattice value, not a pinned `/a/` ref. CAD3 structural sharing means unchanged subtrees between transitions share storage automatically. The timeline can grow indefinitely without proportional storage cost.

**Communication:** Sending a message to another agent uses `agent:message`, which appends to the target agent's session `pending` queue — local: `/g/my-assistant`, remote: `did:key:zBob.../g/helper` — with appropriate capability via UCAN.

**Discoverability:** Agent IDs are human-readable strings chosen by the user. The canonical reference is via DID: `did:key:zAlice.../g/my-assistant`. Additional metadata (`name`, `description`, `tags`) in the agent's `config` supports discovery queries (e.g. "find all agents tagged 'code-review'").

**Agent mobility and forking:**

Because agent state is an immutable lattice value with no hidden mutable references — no in-memory state, no file handles, no connections, just data — agents are inherently portable. Nothing binds an agent to a specific venue beyond the runtime (which is the same everywhere) and DID resolution.

**Migration** moves an agent between venues:

1. Suspend agent at venue A (status → suspended, drain session pending)
2. Read full agent state: timeline, config, caps
3. Create agent at venue B with same DID, same state
4. Update DID resolution to point to venue B
5. Resume at venue B

**Forking** creates a new agent from an existing agent's state with optionally modified config:

```
fork(/g/my-assistant, {
  owner: "did:key:zAlice...",           # new owner
  config: {
    model: "/o/newmodel...",          # different transition function
    llm: { provider: "openai", ... } # different LLM
  },
  caps: [<new ucan set>],            # scoped for new owner
  state: "latest"                     # or specific timeline index
})
```

The forked agent gets a new DID but carries full state history up to the fork point. The timeline is shared via CAD3 structural sharing — no duplication. From the fork point, the two agents diverge independently.

This makes **"create a pre-configured agent to do X"** a simple grid operation. A vendor or community publishes a well-tuned agent — trained state, proven transition function, tested config. A user forks it with their own ownership, caps, and secrets:

```
invoke("/o/fork-agent", {
  source: "did:web:vendor.example.com/g/smart-assistant",
  overrides: {
    owner: "did:key:zAlice...",
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
    forked_from: "did:web:vendor.example.com/g/smart-assistant",
    fork_point: 42,          # timeline vector index
    fork_ts: 1719500000
  }
```

This enables provenance tracking, licensing verification, and update propagation — a vendor can publish state updates that downstream forks can optionally merge.

#### `/w/` — Workspace

Mutable data space for agents and users. Persistent storage for user and agent data — documents, records, configuration, outputs. The primary read/write namespace.

- Freely mutable — no transition constraints
- Hashmap-based — user defines arbitrary structure
- Any CAD3 type is valid, but JSON-like structures get the best tooling support
- Non-JSON structures will store and hash correctly but won't render nicely in UIs or chain through operations cleanly

Operations may default to a workspace path, such as `w/memory`. Adapter-owned
state lives in the venue's `:adapters/<name>` region, not in user workspaces;
see [ADAPTERS.md](ADAPTERS.md#private-state-and-user-managed-storage).

#### `/s/` — Secrets

Encrypted at rest: reading `/s/` returns ciphertext only. A secret is set only
through `secret:set`, which encrypts it, and `covia:delete` removes a whole
secret (`s/<name>`), never part of one. An operation uses a secret's value
without revealing it under `secret/use`; revealing it needs `secret/extract`
(UCAN.md, *Secret extraction*).

### 4.5 Request-relative Shortcuts

Some path prefixes name no stored namespace. They are shortcuts whose meaning
depends on the request, and each stands for a real path:

| Prefix | Stands for | Needs on the request |
|--------|------------|----------------------|
| `n/x` | `g/<agent>/n/x` — the running agent's workspace | the agent |
| `c/x` | `g/<agent>/sessions/<session>/c/x` — the session's scratch | the agent and session |
| `t/x` | `j/<job>/temp/x` — the job's scratch | the job |
| `v/x` | `<venue>/w/global/x` — the venue's shared definitions | nothing |
| `x/<adapter>/…` | that adapter's view for the addressed user | the user |

**The scratch shortcuts are user-level space.** `n/`, `c/` and `t/` give
operation code and agent tool calls somewhere to keep arbitrary data. They never
expose fields the framework manages (status, `wakeTime`, task input and result,
timeline), which are reached through their own operations, so the framework can
restructure its records without breaking user code. A shortcut writes inside an
existing agent, session or job record and never creates that record.

**`n/` — agent workspace.** Persists across sessions and tasks: long-term
memory, learned patterns, persona.

**`c/` — session scratch.** Working memory for one conversation, meaningful only
while the session is active. See AGENT_SESSIONS.md for session lifecycle.

**`t/` — job scratch.** Intermediate results for one job. Any operation running
under a job can use it, not only agents; an agent task is a job, so tasks use
it too. It is persisted on the job record, so it survives restarts and sits
alongside the job's input and output. Different jobs get different scratch,
even for the same agent or user.

#### Scoping Rules

`JobManager` automatically sets `jobId` on the `RequestContext` when dispatching to an adapter. If the context already carries a `jobId` (set by a parent job), the parent's ID is preserved. This means sub-operations within a job share its `t/`:

| Scenario | `t/` scope | Reason |
|---|---|---|
| Goal tree subgoals and tool calls | Shared with root goal's job | GoalTreeAdapter sets `jobId`; sub-operations inherit it |
| Orchestration steps | Shared with orchestration job | Steps execute within the orchestrator's context |
| Direct invoke (REST, MCP) | Own job | New top-level job, `JobManager` auto-sets fresh `jobId` |
| `agent:request` / `agent:trigger` | Own job | New agent execution, new job |
| `grid:run` to remote venue | Own job | New job on target venue |

**Key principle:** `t/` is shared across all sub-work within a single job. If isolation between steps is needed, use `grid:run` which creates a new job with its own `t/`.

**Choosing the right scratch scope:**

| Scope | Use for | Lifetime |
|---|---|---|
| `t/` | Intermediate calculations, data passed between orchestration steps or subgoals | Single job |
| `c/` | Conversational context, dialogue notes, topic state | Session |
| `n/` | Agent persona, learned patterns, long-term memory | Agent lifetime |
| `w/` | Final outputs, documents, anything other agents or users should see | Permanent (user workspace) |

Each shortcut is currently a `NamespaceResolver` registered on `CoviaAdapter`
by prefix; §4.6 replaces them.

### 4.6 Path Resolution (design, covia#617)

A caller's path does not match the stored keys one for one. `w/foo` crosses the
`{updated, data}` wrapper, `j/<hex>` is a Blob key read as the job's current
state, `g/<id>` enters an agent record with its own `ts` stamp, `w/list/0` is a
vector index, and `n/x` depends on who is asking. Path resolution maps one onto
the other. Today it is done in several places, each with its own list of
namespaces, and write policy is an allowlist in `CoviaAdapter`. This section is
the design that replaces them.

**One entry point.** `engine.paths().resolve(ctx, path, ability)` is the only
request-aware step. It:

1. parses the path; a leading DID names the owner, otherwise the request's user
   does;
2. expands the shortcuts of §4.5 to their real paths, from the request. A
   shortcut is accepted bare or with the caller's own DID, never another's;
3. checks authority on the real path (`engine.requireLocalAccess`), so one piece
   of data has one address and a grant covers every route to it;
4. walks the components from the venue root to the position;
5. returns the component there, or throws if the principal may not act.

Reads, writes, deletes, the job-free read API, context loading and operation
references all use it.

**Components define their namespaces.** `ACoviaComponent extends
ALatticeComponent` is the base of every component a caller can address.
`resolve(keys, from)` returns the component at that position. It sees no
request, takes as many keys per step as the component's rules cover (as cursor
paths do), and builds each child only from its own cursor, so a walk from a
draft root stays in the draft.

| Component | Routes |
|-----------|--------|
| `VenueState` | a user's DID to their `User`; `x/<adapter>` to the adapter component |
| `User` | `g/<id>` to `AgentState`, `j/<id>` to `JobState`, `s` to `SecretStore`, `a`, and `w`, `o`, `h` to a `WorkspaceRegion` |
| `AgentState` | `sessions/<id>` to `SessionState` |

`DataComponent` is the fallback for a position no other component claims,
reading keys by value type: a map key, a vector index, an Index key.

**Operations define writability.** `read()`, `update(ctx, change)` and
`delete(ctx)` on the component at the position are the only ways to use it.
Each makes its own checks and then changes its cursor in one atomic update;
cursors carry no write policy. A `DataComponent` refuses changes unless the
component above it opts its data in: `WorkspaceRegion` for `w` and `o`,
`AgentState` for `n`, `SessionState` for `c`, `JobState` for `temp`.
`SecretStore` accepts only removal of a whole secret. Refusals are typed and
carry a reason from a constant on the refusing component. Checks that depend on
the stored value run inside the update: the owning record must exist, and
creating missing levels needs write authority at the nearest level that exists.

`ACoviaComponent` is shaped so that it could move onto the Convex component
hierarchy.

---

## 5. Operations and Invocation

### 5.1 Invocation Model

Operations are invoked with explicit inputs and capabilities:

```
invoke("someop",
  { arg1: lookup(/my/data), arg2: "constant" },
  [ { with: "did:key:zAlice.../a/cafebabe...", can: "crud/read" } ]
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

Capabilities inherit the namespace path hierarchy. The risk profile follows from namespace semantics — code is inspectable, data is sensitive, secrets are encrypted. Each capability is a `with` (resource URI) + `can` (command) pair per the UCAN spec (see [UCAN.md](UCAN.md)).

| Capability | Risk Level |
|-----------|------------|
| `with: did:.../o/`, `can: crud/read` | Low — code is inspectable, open-source model |
| `with: did:.../o/cafebabe`, `can: invoke` | Medium — consumes compute resources |
| `with: did:.../a/cafebabe`, `can: crud/read` | Medium — exposes user data |
| `with: did:.../a/`, `can: crud/read` | High — exposes all user data |
| `with: did:.../w/projects/foo/`, `can: crud/read` | Medium-High |
| `with: did:.../w/`, `can: crud/read` | High — live working state |
| `with: did:.../s/`, `can: crud/read` | Low — returns ciphertext only |
| `with: did:.../s/key`, `can: secret/extract` | Highest — reveals plaintext |
| `with: did:.../w/results/`, `can: crud/write` | Medium |
| `with: did:...`, `can: *` | Maximum — trusted agents only |

### 5.4 Namespace Security Symmetry

The six namespaces form a natural grid. `/a/` is the universal immutable layer that all other namespaces pin to. The remaining namespaces are mutable with different constraints:

| | Mutable Registry | Working State | Controlled |
|---|-----------------|---------------|------------|
| **Data** | — | `/w/` — workspace | `/s/` — secrets |
| **Execution** | `/o/` — operations | `/g/` — agents | `/j/` — jobs |
| **Immutable** | `/a/` — universal pinning target for both data and operations |

Default read postures: `/o/` defaults open (code is inspectable), `/w/` and `/a/` default private (user data), `/s/` read returns ciphertext (decryption requires explicit capability).

### 5.5 Output Convention

Operation output is arbitrary CAD3. The lattice stores and hashes any valid CAD3 value. However, JSON-like outputs enable path navigation into results, chaining into subsequent operations, human-readable inspection in UIs, and interop with the wider ecosystem.

---

## 6. Capability Model — UCAN

> **Full specification:** See [UCAN.md](./UCAN.md) for the complete design including
> token structure, ability hierarchy, delegation chains, verification algorithm,
> lattice storage, enforcement points, and implementation phasing.

Capabilities are expressed as lattice-native UCAN tokens following the UCAN specification's `with`/`can` structure. Each capability pairs a resource (DID URL) with an ability (slash-delimited verb). UCANs are signed by the issuer, enabling verifiable delegation chains without any central authority. Covia uses CAD3 encoding instead of IPLD/DAG-CBOR but the authorisation model is identical to the UCAN spec.

### Example

```json
{
  "iss": "did:key:zAlice...",
  "aud": "did:key:zBob.../g/helper",
  "att": [
    { "with": "did:key:zAlice.../w/projects/foo", "can": "crud/read" },
    { "with": "did:key:zAlice.../o/cafebabe...", "can": "invoke" }
  ],
  "exp": 1719500000,
  "prf": ["<cad3-hash-of-parent-ucan>"],
  "sig": "0xdeadbeef..."
}
```

### Key Properties

- **Lattice-native.** UCANs are content-addressable values in `/a/`. CAD3 value hash is the canonical identifier. (Transport uses the JWT encoding — see UCAN.md §4.3; the lattice-native CAD3 form is the storage/canonical representation.)
- **DID URL resources.** Resource URIs are DID URLs scoping into the user's lattice namespace (e.g. `did:key:zAlice.../w/projects/foo`). Sub-paths attenuate parents.
- **Standard abilities.** `*` is the top ability. Abilities are slash-delimited without leading slash: `crud/read`, `invoke`, `secret/extract`, `agent/message`, `ucan/delegate`. Prefix hierarchy — `crud` proves `crud/read`.
- **Delegation chains.** Agents sub-delegate narrower capabilities via proof chain references. Attenuation only — never widen.
- **Self-contained verification.** No callbacks or token servers. The UCAN, its proof chain, and DID→key resolution are sufficient.
- **Revocation.** Signed records referencing the UCAN's CAD3 hash, propagated through the lattice.

---

## 7. Federation

The DID prefix enables federated execution across venue boundaries.

### 7.1 Cross-Boundary References

An agent in venue A can reference an operation in venue B:

```
did:key:zAlice.../o/transform → did:key:zBob.../a/cafebabe...
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

MCP maps naturally to the tool loop (level 2). The adapter bridges between MCP's client-server model and grid ops.

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

A2A maps to the runtime layer (Layer 1) — agent discovery and task delegation align with the session/job model.

| A2A Concept | Grid Equivalent |
|-------------|-----------------|
| Agent Card | Generated from agent metadata + venue context (see §8.2.1) |
| Task | Inbox item → Job (`/j/`) |
| Task status | Job lifecycle states (created → running → complete) |
| Artifact | Job outputs / assets in `/a/` |
| Message | Inbox items with job correlation |
| AgentSkill | Maps to ops in `/o/` with schema metadata |

#### 8.2.1 Agent Card Generation

A2A Agent Cards are **generated at request time** by the venue adapter, not stored as a single blob. Most fields are derived from venue infrastructure or agent config already present in `/g/`. Only a few fields need to be explicitly stored in the agent's `config`.

**What the agent stores (in `/g/<id>/config`):**

| Field | Location | Notes |
|-------|----------|-------|
| `name` | `/g/<id>/config/name` | User-defined, human-readable |
| `description` | `/g/<id>/config/description` | User-defined |
| `tags` | `/g/<id>/config/tags` | User-defined, queryable |
| `skills` | `/g/<id>/config/skills` | User-defined — the key field (see below) |
| `defaultInputModes` | `/g/<id>/config/protocols/a2a/inputModes` | Optional override of venue default |
| `defaultOutputModes` | `/g/<id>/config/protocols/a2a/outputModes` | Optional override of venue default |

**What the venue derives at generation time:**

| A2A Field | Derived from | Why not stored |
|-----------|-------------|----------------|
| `url` | Venue endpoint + agent ID | Venue knows its own URL |
| `version` | Agent `ts` or config hash | Changes on every transition |
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

1. Reads `name`, `description`, `tags` from agent `config`
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

**Inbound interactions** (external → grid) are recorded as session pending messages and subsequently appended to session history with protocol metadata:

```
/g/<agent-id>/sessions/<sid>/pending (vector)
  [
    {
      message: "...",
      caller: "did:external:agent-xyz",
      ts: 1719500000
    }
  ]
```

**Outbound interactions** (grid → external) are recorded in the timeline entry (see AGENT_LOOP.md §2.3 for timeline entry structure):

```
/g/<agent-id>/timeline (vector)
  [
    ...,
    {
      start: 1719500000,
      end: 1719500005,
      op: "langchain:openai",
      state: { ... },
      messages: [{ protocol: "a2a", task_id: "a2a-task-9876", ... }],
      result: { ... }
    }
  ]
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
| **Native** (grid agent → grid agent) | Direct session.pending write with UCAN caps | Minimal — lattice-native |
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
| Addressing | `<DID>/<namespace>/<path>` |
| Immutability | Content-addressed `/a/` refs, frozen job records |
| Mutability | Named cursors in `/w/`, lifecycle state in `/j/` |
| Security | UCAN capability tokens, lattice-native |
| Execution | `invoke(op, inputs, caps)` → job lifecycle |
| Federation | DID-scoped paths, self-verifying immutable refs |
| Economics | CVM on-chain settlement |
| Data format | CAD3 universal, JSON-like convention |

---

## Appendix A: Lattice Mechanics

This appendix describes the operational mechanics of venue lattice state management — how cursors work, how merges happen, how state is signed and persisted, and how venues share state.

### A.1 Cursor Model

Each venue engine holds a **lattice cursor** pointing to its own venue root within the global structure. The cursor provides:

- **`get()`** — Read current state
- **`set(value)`** — Write new state
- **`updateAndGet(fn)`** — Atomic read-modify-write
- **`fork()`** — Create isolated working copy
- **`sync()`** — Merge working copy back to parent

Sub-cursors navigate to specific fields (e.g. `venueCursor.path(Covia.ASSETS)`). Sub-cursors write through to the venue root atomically — updating a job via the jobs sub-cursor updates the venue root in a single operation.

For day-to-day operations, the venue works directly with its cursor. It does not interact with the global lattice structure unless syncing with other venues.

Writes go through the component accessors — `VenueState`, `Users`, `User`, the
agent and job records — because each applies its record's stamp and creates
missing containers. A raw cursor path does neither.

### A.2 Merge Semantics

The signed venue value stores `:version` next to `:did` and `:timestamp`.
It is the integer storage format version, not the software version or the LWW
ordering stamp. Missing means legacy `0`. Initialization validates compatibility
and applies ordered migrations atomically with their version changes, before
bootstrap or workers. Version `1` permits lazy adapter-record migration without
startup scans. See [Venue versions and migrations](PERSISTENCE.md#venue-versions-and-migrations).

The venue **`:value` is a single navigable whole-value-LWW node.** It carries a
wall-clock `:timestamp`, re-stamped on every write (never inflated — a stamp is
real time, never a `+1` logical bump). On merge, the value with the newer
`:timestamp` wins **wholesale** (tie → own value); the merge does not recurse
into the interior. This is what makes **deletions durable** — a removed key is
simply absent from the newer winning value, so it cannot be reintroduced by a
per-entry union on merge-back (the failure mode of the previous per-entry-LWW
model).

Distinct values CAN carry equal stamps (writes within one clock tick). Local
writes never resolve this through LWW: connected cursor updates are atomically
ordered, and the last successful local update is the current value. For an
external equal-stamp snapshot, the directional merge keeps `own` — the venue's
current authoritative value. Merge sites must therefore place current local
state in the `own` position. See covia#214 and Convex-Dev/convex#641 for the
analysis.

This relies on a venue having **one authoritative running instance**, whose
updates are atomically ordered. `OwnerLattice → SignedLattice` authenticates its
snapshots; possession of the signing key does not prevent two processes from
running. Deployment and failover must fence the previous writer. Supported
reconciliation compares snapshots of that one lineage for backup, replication
and recovery. Independently executing forks are outside this contract: choosing
one snapshot discards the other's changes and cannot undo its external effects.
Per-entry LWW cannot preserve arbitrary application invariants either.

The **interior** of `:value` (`:version`, `:assets`, `:storage`, `:did`, `:users`, `:schedule`, `:adapters`, `:adapter-versions`, `:user-deletions`,
`:user-data → <DID> → {j, g, s, w, o, h, a}`) is structurally navigable but
carries no independent merge semantics — the merge happens only at the `:value`
root. Writes are stamped on the way up by a
**stamp-on-write boundary** that sources its timestamp from the write clock on the
`LatticeContext` (the convex `StampedCursor`). The `w`/`o`/`h`
user namespaces are `WorkspaceRoot` regions, stored as a `{updated, data}`
container whose `data` is the caller-visible value. The container is a **view
boundary in the lattice**, not a path convention: navigating `w/foo` crosses it
and reads or replaces `w.data.foo`. Changed writes preserve the physical
container and ratchet `updated` from the same write clock, using the ordinary
stamp-on-write cursor machinery. Callers — and the code above the lattice —
never address `data`.

The `:schedule` child retains its extensible `{updated, events, ...}` record
shape. Its `events` child is structurally navigable through a stamp-on-write
boundary, so event mutations preserve sibling metadata and refresh `updated`.

### User meta record

Each per-DID user record carries a framework-owned `meta` slot alongside its
namespace keys:

```
:user-data → <DID> → { meta: {created, updated},
                       j, g, s, a, w, o, h }
```

- **`created`** — epoch millis of the identity's first *activity* (first write
  into the record). Minted once, never changed.
- **`updated`** — epoch millis of the most recent write anywhere in the user's
  subtree. The whole record sits behind its own stamping boundary, so ANY deep
  write — an agent cycle, a session frame, a persisted job, a secret, a
  workspace entry — refreshes it automatically at the lattice layer, with no
  application code involved. Unchanged writes and CRDT merges do not bump it.

`updated` is the activity signal for identity-lifecycle policy: an ephemeral
identity's idle time is `now - meta.updated`, read by a future TTL/reaper
mechanism (which pairs with Etch GC for physical reclamation — logical removal
of an expired user's subtree makes its cells unreachable and therefore
collectable). Note the signal is *write*-activity: job-free reads do not bump
it, which is the intended semantics — identities stay alive by doing things.

`meta` is **framework-owned**: it is not a writable namespace (`covia:write`
addresses `w` and `o` only), so users cannot forge their own activity. It is
readable through the ordinary read path (`covia:read <did>/meta`, values API).
New per-identity fields belong in `meta` rather than as further top-level record
keys, so identity metadata stays in one place with one ownership rule.

| Region | Merge | Rationale |
|--------|-------|-----------|
| `:grid → :venues` | OwnerLattice — per-AccountKey, signature-verified | One authoritative signing key per venue |
| Venue `:value` (whole node) | SignedLattice authenticity wrapping **whole-value LWW** by `:timestamp`, tie → own | Single-writer lineage; deletions durable; interior is structurally navigable but not independently merged |
| `:grid → :meta` | CASLattice (union) | Genuinely shared, multi-writer content-addressed metadata |
| `:dlfs` (sibling region) | Per-user OwnerLattice → per-file rsync-like DLFS merge | Independent per-user drives — the one legitimate fine-grained CRDT |

The genuinely multi-writer regions (`:meta` and DLFS) use ordinary
commutative/associative/idempotent CRDT joins. Venue `:value` reconciliation is
deliberately directional on an equal timestamp (`own` wins), relying on its
single authoritative writer and connected updates that present the local edit
as `own`. Distinct concurrent authorities must not write the same venue entry.

### A.3 Update Logic

**Connected component pattern:** Engine holds one connected `VenueState` as the
live representative of the venue. Component mutations atomically replace the
shared root through the signed owner boundary:

```
startup: Engine → connected VenueState
request A: component update job X → signed root
request B: component update job Y → signed root
publication: application.host().sync() → current root
```

Direct `updateAndGet` calls on sub-cursors remain atomic because they ultimately
replace the shared application root with compare-and-swap. Compound domain edits
use upstream `TransactionalComponent.transact`: callbacks operate on isolated
views of the same domain objects, and commit retries the domain operations if
concurrent state changed. Exceptions discard the attempt. Bootstrap uses this
contract over the venue-owned `w/global` workspace. No manual write replay or
local transaction framework is involved. Request duration and persistence cadence
are not transaction boundaries.

**Venue execution and snapshot recovery are different operations.** A venue is
an atomically updated running lattice application instance with one authoritative
writer. LWW selects a complete venue snapshot for backup, replication and
failover. It cannot generally merge independently executing forks: cross-component
invariants, decisions and external outputs have no arbitrary conflict-free merge.
`VenueState.fork().sync()` therefore selects a snapshot; it is not a way to commit
concurrent work. Live operations use `transact`, which retries against the current
state. Interior components, including adapters, have no independent publication
policy. Failover must fence the old writer before activating its replacement;
snapshot selection alone neither elects a leader nor reverses external effects.

The timestamp is not a local sequence number. Local writes are not merged with
the preceding local value: their successful atomic update order determines the
current root, including multiple updates in the same millisecond. The wall-clock
timestamp exists to reconcile an externally received snapshot; on an equal stamp
the venue keeps its own current authoritative value.

### A.4 Signing Strategy: Sign Connected Updates, Verify Early

The venue's Ed25519 keypair signs each successful connected component mutation
as it crosses the `SignedCursor` boundary. A transaction accumulates private
writes and signs the completed venue value at commit. An uncontended changed
transaction signs once; an unchanged transaction does not sign. Signing failure
leaves live state unchanged. Rebinding restores editing policies on the draft,
while commit targets bound storage so those policies are not applied twice.

```
[Component mutation] → atomic cursor update → sign venue owner value
                                               |
                              application.host().sync() publishes current root
```

Signing and persistence are deliberately separate. Signing makes a successful
state transition authoritative in memory; `application.host().sync()` publishes the
current root and `application.flush()` supplies the physical durability barrier.
This keeps Engine state coherent for local and embedded consumers without a
second long-lived working copy.

**Verification on merge:** When receiving foreign lattice state (from disk recovery, peer replication, or federation), `SignedLattice.merge()` handles verification automatically — verify Ed25519 signature, merge inner state if valid, re-sign the merged result if it differs from both inputs, reject if signature invalid.

**Re-signing after merge:** If a merge combines state from two sources and produces a new value, the merging venue re-signs the result. The merging venue is asserting: "I verified both inputs and computed this merge."

### A.5 Persistence

**Startup:** Load signed state from Etch store via `store.getRootData()`, verify signature (own keypair), and initialise cursors. Job recovery stabilises PENDING/interrupted STARTED Jobs as FAILED and restores the stable waiting family (PAUSED/INPUT_REQUIRED/AUTH_REQUIRED). AgentAdapter then clears stale RUNNING/session fences, removes intake belonging to terminal Jobs, preserves jobless lattice tasks, and wakes only remaining durable queued work. No internal agent execution attempt is resumed.

**Periodic publication:** Engine publishes the connected root every 100 ms and
invokes the store durability barrier every 10 seconds. Role-selected request
hooks may publish sooner, and callers that require synchronous durability use
`engine.flush()`. `Engine.close()` performs a final publication and flush before
the host and store close.

**Crash recovery:** If a venue crashes between persistence points, it loses operations since the last sync. The persisted lattice still contains a coherent snapshot (never a partial write); clients polling for job status may see the job revert to that point. A backup may restore a newer authentic snapshot previously signed by the same venue authority.

### A.6 Sharing and Replication

Venues are **not required** to share their lattice state. The default is private — a venue's state lives only in its own process and persistent storage.

**Selective sharing:** Because the lattice is hierarchical, venues can share at different levels:

| Level | What's Shared | Use Case |
|-------|--------------|----------|
| Nothing | Only DID document (`.well-known/did.json`) | Private venue, API-only access |
| `:assets` only | Published operations and artifacts | Discoverable venue, jobs stay private |
| `:assets` + `:meta` | Operations with full metadata | Federated operation discovery |
| Full venue state | Everything including jobs and users | Public venue or backup replica |

Selective sharing is an application-level read/export policy. A partial view is
not merged back into the authoritative venue entry: because `:value` is one
whole-snapshot LWW node, treating a subset as an update would delete the omitted
regions.

**Replication protocol:** A replica of a venue accepts authentic complete
snapshots signed by that venue's authority and retains the newer `:timestamp`.
Equal-stamp reconciliation is directional (`own` wins), so publication code must
preserve lineage ordering as described in Appendix A.2. Independently owned
entries under `:venues`, shared `:meta`, and DLFS drives retain their normal
multi-writer lattice joins.

**Public venues** expose their state openly — appropriate for shared infrastructure or transparent AI services. **Private venues** keep state local and participate via API calls only. **Hybrid venues** share some components (e.g. published assets) while keeping others private (e.g. job history, user records).

---

## Appendix B: Lattice Type Reference

### Convex Primitives

| Type | Purpose |
|------|---------|
| `ALattice<V>` | Abstract base — defines merge, zero, path, checkForeign |
| `KeyedLattice` | Maps different keywords to different child lattices |
| `SignedLattice<V>` | Wraps values in Ed25519 signatures |
| `OwnerLattice<V>` | Per-owner signed values with authorisation |
| `StringKeyedLattice` | Like `KeyedLattice` but `AString` keys over an `AHashMap` (natively JSON-compatible) |
| `IndexLattice<K,V>` | Sorted radix-tree map with recursive value merge |
| `MapLattice<K,V>` | Hashmap with recursive value merge |
| `CASLattice<K,V>` | Content-addressed union merge |
| `JSONLattice` | Structural navigation only — builds a container per key shape; recursive `path`; `merge` throws (merge belongs to the enclosing node, never per-key) |
| `LWWLattice<V>` | **Whole-value** last-writer-wins merge layer by extracted timestamp (non-recursive; tie → own); delegates navigation to an inner lattice |
| `StampedCursor<V>` | Explicit editing view — applies the owning component's timestamp function on changed writes; merge remains on the component |
| `FunctionLattice<V>` | Custom merge function (e.g. first-writer-wins) |
| `WorkspaceRoot` (Covia) | View boundary — presents a `{updated, data}` container as its `data`, stamping `updated` on write; merge remains at the enclosing venue |
| `LatticeContext` | Live application policy for clock, signing and owner verification; installed once at the hosted root and resolved per logical write |
| `ACursor<V>` | Mutable reference with atomic get/set/update/fork/sync |

### Covia Definitions

The `covia.lattice.Covia` class defines the complete lattice hierarchy using
standard convex-core generics plus one narrow Covia view component for the
legacy-compatible workspace envelope:

| Definition | Type | Purpose |
|------------|------|---------|
| `Covia.ROOT` | `KeyedLattice` | Root — `:grid` (containing `:venues` and `:meta`) and the sibling `:dlfs` region |
| `Covia.VENUE` | `StampedCursor` → `LWWLattice` → `KeyedLattice` | The whole per-venue `:value` as a single navigable **whole-value-LWW** node with a stamp-on-write boundary |
| `:grid → :venues` | `OwnerLattice` → `SignedLattice` → `Covia.VENUE` | Per-AccountKey signed venue state (one authoritative signing key) |
| Venue interior | `KeyedLattice` (structural children) | `:version`, `:assets`, `:storage`, `:did`, `:users`, `:schedule`, `:adapters`, `:adapter-versions`, `:user-deletions`, `:user-data` — navigated structurally; merged only at `:value` |
| `:schedule` | `StampedCursor` → `StringKeyedLattice` | Extensible `{updated, events, ...}` record; writes under `events` refresh `updated` and preserve sibling metadata |
| `:user-data → <DID>` | `MapLattice` → `StampedCursor` → `StringKeyedLattice` | Per-user record; every deep write refreshes `meta.updated` |
| `j` / each Job | `IndexLattice` → `StampedCursor` → `JSONLattice` | Existing Job map shape; deep writes such as `t/` refresh `updated` |
| `g` / each agent | `MapLattice` → `StampedCursor` → `JSONLattice` | Existing agent map shape; deep writes such as `n/` and `c/` refresh `ts` |
| `s` / `a` | `JSONLattice` | Plain structural navigation |
| `w` / `o` / `h` | `WorkspaceRoot` | Existing `{updated, data}` container presented as its `data` |
| `:grid → :meta` | `CASLattice` | Shared content-addressed metadata (multi-writer union) |
| `:dlfs` | `OwnerLattice` → `MapLattice(DLFSLattice)` | Per-user signed drives, per-file rsync-like merge |
