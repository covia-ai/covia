# Covia Grid Lattice Structure

How Covia lays out its state in the Convex lattice: addressing, namespaces, how
a caller's path reaches stored data, and how venue state is merged, signed and
persisted. `covia.lattice.Covia` defines the lattice in code.

---

## 1. Overview

Assets, operations, agents, jobs, workspaces and secrets are all structured
data in one addressing scheme. The lattice gives P2P replicated state,
content-addressed immutable values, federation across venues, and
capability-based security with UCANs. The on-chain CVM anchors identity and
settles payments; grid data flows over the lattice, not through consensus.

---

## 2. Principles

- **Everything is structured data**, navigable by path.
- **Immutable values and mutable locations.** A content hash (`/a/<hash>`)
  always resolves to the same value; any other path names a location whose
  value can change.
- **The lattice is permissive; convention pays.** Any CAD3 value can be stored
  anywhere, but tooling, chaining and UIs work best with JSON-like maps with
  string keys. Blobs, vectors and sets store and hash correctly but get less
  tooling support.
- **Freedom in the workspace, discipline elsewhere.** Framework-managed
  namespaces keep their record shapes; workspaces take any structure.
- **Operations receive values, not paths.** References are resolved at
  invocation and capabilities are granted explicitly.
- **One kind per level.** A level holds records or sub-namespaces, never both:
  `v/skills` holds skillsets and `v/skills/root` holds skills. A reference's
  kind is declared by the field it appears under (`skills` versus `skillsets`),
  never inferred from what it resolves to, so a reference of the wrong kind is
  reported rather than silently mis-resolved.

---

## 3. Addressing

### 3.1 Address Format

```
<DID> / <namespace> / <path...>
```

- **DID** — the owner of the data: a user, agent or venue. Any DID method that
  resolves to a public key works (`did:key:`, `did:web:`, …).
- **Namespace** — one of the namespaces in §4.1.
- **Path** — keys within the namespace.

### 3.2 Relative and Absolute Paths

Without a DID, a path belongs to the request's user: `/w/my/op`. A content
hash needs no owner: `/a/cafebabe...`. A DID is needed only to reach another
owner's data, such as `did:key:zBob.../o/transform`.

---

## 4. Namespaces

### 4.1 Namespace List

| Prefix | Namespace | Holds or resolves to | Changed through |
|--------|-----------|----------------------|-----------------|
| `a/` | Assets | The user's content-addressed assets | Asset operations; never mutated |
| `o/` | Operations | The user's named operation definitions | `covia:write`, `covia:delete` |
| `j/` | Jobs | Job records by ID, read as each job's current state | Job operations |
| `g/` | Agents | Agent records by ID | Agent operations |
| `s/` | Secrets | Encrypted secrets | `secret:set`; `covia:delete` of a whole secret |
| `w/` | Workspace | User and agent data | `covia:write`, `covia:delete` |
| `h/` | HITL inbox | Human-in-the-loop requests | The `hitl` operations |
| `n/` | Agent workspace | `g/<agent>/n` for the running agent | `covia:write` within the agent |
| `c/` | Session scratch | `g/<agent>/sessions/<session>/c` | `covia:write` within the session |
| `t/` | Job scratch | `j/<job>/temp` for the current job | `covia:write` within the job |
| `v/` | Venue definitions | `<venue>/w/global`: `v/ops`, `v/skills`, `v/adapters`, `v/models` | The venue's own identity |
| `x/<adapter>/` | Adapter view | That adapter's view for the addressed user | As the adapter allows; read-only by default |
| `dlfs/<drive>/` | DLFS drives | The owner's drive in the separate `:dlfs` region | The `dlfs` and `file` operations |

A user's record also holds the venue's bookkeeping, its activity times
(Appendix A, *User meta record*) and its account lifecycle, which no path
reaches.

`n/`, `c/`, `t/`, `v/` and `x/` are context-dependent (§4.5). An `x/` route
exposes nothing unless the adapter opts in; see
[ADAPTERS.md](ADAPTERS.md#user-visible-extension-routes). DLFS addressing and
authority are in [UCAN.md](UCAN.md).

### 4.2 Storage Types

`j/` is an Index keyed by job ID, so jobs list in ID order and support range
scans. The other namespaces are hashmaps. Inside an entry any structure may
appear: a job record is a hashmap, and a list inside it may be a vector.

### 4.3 Namespace Details

#### `/a/` — Assets

Immutable values keyed by CAD3 hash. Any peer can check a value against its
hash. An asset stays resolvable until it is garbage-collected.

#### `/o/` — Operations

The user's own operation definitions under names they choose, such as
`/o/my-transform`. A definition is operation metadata whose `operation` field
names the adapter operation that runs it, so a stable name can move to another
implementation without breaking its callers. Definitions are written like
`/w/` values and checked when invoked.

`invoke()` accepts an operation from `/o/`, `/w/`, `v/ops/…`, a content hash,
or another venue's path such as `did:key:zBob.../o/transform`. The job's `op`
records the reference that was invoked; an inline definition is recorded by its
hash. See [OPERATIONS.md](OPERATIONS.md).

#### `/j/` — Jobs

Execution records, created by `invoke()`. A job answers the caller's question
"what happened with my request?"

**Lifecycle:** `PENDING → STARTED → COMPLETE | FAILED | CANCELLED | REJECTED`
(terminal, sticky), plus the paused family `PAUSED`, `INPUT_REQUIRED`,
`AUTH_REQUIRED` (resume to `STARTED`).

A job is one current-state record at `/j/<id>`. Updates replace it without
keeping earlier versions; legacy top-level `prev` links are ignored on reads
and removed on the next write. The update path refuses to leave a terminal
state, so a terminal record's `output` and `error` never change; the record
can still be deleted, and secrets are redacted on every durable write.
[JOBS.md](JOBS.md) defines the record.

#### `/g/` — Agents

Stateful actors with pluggable transition functions, run by the venue. Agent
IDs are strings the user chooses; the canonical address is
`did:key:zAlice.../g/my-assistant`.

Each agent is one atomic record, changed by compare-and-swap so concurrent
threads cannot lose updates. Its `ts` is last-modified metadata; replication
merges the whole signed venue value (Appendix A.2), not individual records.

Callers see:

- **`config`** — the agent's declared behaviour: transition operation, name,
  description, tags, skills, tool palette, protocol settings;
- **`status`** — `SLEEPING`, `RUNNING`, `SUSPENDED` or `TERMINATED`. The
  venue checks `RUNNING` against its live launcher and clears stale markers at
  startup;
- **`n/`** and each session's **`c/`** — scratch the agent writes (§4.5).

The other fields (sessions, tasks, pending, timeline, caps, scheduling state)
belong to the runtime and are documented where they are defined: sessions and
tasks in [AGENT_SESSIONS.md](AGENT_SESSIONS.md), the run loop, transition
contract and timeline in [AGENT_LOOP.md](AGENT_LOOP.md), and wake scheduling in
AGENT_LOOP.md §4.6 over the [GRID_SCHEDULER.md](GRID_SCHEDULER.md) engine.

**Secrets.** Agents name secrets by path (`/s/anthropic-key`). The runtime is
the one place capability checks and decryption happen; adapters handle
plaintext on the agent's behalf; the transition function never sees it.

**Authority** for each agent operation is listed in
[UCAN.md §5.3](UCAN.md#53-enforcement-points); the operations offered to the
LLM as tools are in AGENT_LOOP.md §3.6.

**Timeline.** Each entry is a raw lattice value. CAD3 structural sharing means
unchanged subtrees cost nothing, so the timeline can grow without proportional
storage.

**Forking.** `agent:fork` creates an agent from another's config and state;
the timeline is copied only on request, tasks and pending start empty, and the
new agent starts `SLEEPING`. Supplied config is merged over the source's.

#### `/w/` — Workspace

The user's and their agents' general data: documents, records, configuration,
outputs. Any structure is allowed. Operations may default to a workspace path,
such as `w/memory`. Adapter-owned state lives in the venue's
`:adapters/<name>` region instead; see
[ADAPTERS.md](ADAPTERS.md#private-state-and-user-managed-storage).

#### `/s/` — Secrets

Encrypted at rest: reading `/s/` returns ciphertext only. A secret is set only
through `secret:set`, which encrypts it, and `covia:delete` removes a whole
secret (`s/<name>`), never part of one. An operation uses a secret's value
without revealing it under `secret/use`; revealing it needs `secret/extract`
(UCAN.md, *Secret extraction*).

### 4.4 Operations, Jobs and Agents

| | `/o/` Operations | `/j/` Jobs | `/g/` Agents |
|---|---|---|---|
| **What** | Definition | Execution record | Persistent actor |
| **Identity** | User-chosen name | System-assigned ID | User-chosen ID |
| **Lifetime** | Until changed | One execution | Many transitions |
| **Started by** | Invoked by others | `invoke()` | `agent:request`, or the scheduler |
| **Answers** | "What logic ran?" | "What happened with this request?" | "What did this agent do and why?" |

Every `invoke()` creates a job recording what ran (`op`), what went in
(`input`), what came out (`output` or `error`) and who asked (`caller`). An
agent's transition may invoke operations, message other agents or create
agents; its timeline records what it saw, decided and did. A job names its
caller and the agent's timeline names the jobs it created, so the same work is
auditable from both sides:

```
/j/2456543/                          /g/my-assistant/timeline/
  op: "/o/my-transform"                42: {
  caller: "<owner>:g:my-assistant"         start: 1719500000,
  status: "COMPLETE"                       end: 1719500005,
  input: { ... }                           op: "test:echo",
  output: { ... }                          state: { ... },
                                           tasks: [resolved task data...],
                                           messages: [session pending...],
                                           result: { ... },
                                           taskResults: { ... }
                                         }
```

**How agents interact:**

| Pattern | Mechanism | Creates a job? |
|---------|-----------|----------------|
| Message | `agent:message` appends to a session's `pending` queue | No |
| Chat | `agent:chat` asks for a reply on a session | Yes |
| Task | `agent:request` adds the request job to the agent's `tasks`; that job is the task, and the agent must complete or reject it | Yes |
| Delegation | `invoke()` with sub-delegated UCAN capabilities | Yes |

Use a task when the result must be accounted for, and a message for informal
coordination. Running an agent's transition is internal to the runtime (an
`agent:trigger` job); callers track their request job instead.

### 4.5 Context-dependent Namespaces

Some namespaces resolve through the request's context to a path elsewhere:

| Prefix | Resolves to | Needs on the request |
|--------|-------------|----------------------|
| `n/x` | `g/<agent>/n/x` — the running agent's workspace | the agent |
| `c/x` | `g/<agent>/sessions/<session>/c/x` — the session's scratch | the agent and session |
| `t/x` | `j/<job>/temp/x` — the job's scratch | the job |
| `v/x` | `<venue>/w/global/x` — the venue's shared definitions | nothing |
| `x/<adapter>/…` | that adapter's view for the addressed user | the user |

`n/`, `c/` and `t/` are user-level scratch for operation code and agent tool
calls. They never reach fields the framework manages (status, `wakeTime`, task
input and result, timeline), which have their own operations, so the framework
can restructure its records without breaking user code. They write inside an
existing agent, session or job record and never create that record.

- **`n/`** persists across sessions and tasks: long-term memory, learned
  patterns, persona.
- **`c/`** is working memory for one conversation, meaningful while the
  session is active (AGENT_SESSIONS.md).
- **`t/`** holds intermediate results for one job. Any operation under a job
  can use it, and an agent task is a job, so tasks use it too. It is stored on
  the job record, so it survives restarts and sits beside the job's input and
  output.

#### Scoping Rules

`JobManager` sets `jobId` on the `RequestContext` when it dispatches to an
adapter, keeping a parent job's ID when there is one. Sub-operations of a job
therefore share its `t/`:

| Scenario | `t/` scope |
|---|---|
| Goal-tree subgoals and tool calls | The root goal's job |
| Orchestration steps | The orchestration job |
| Direct invoke (REST, MCP) | Its own new job |
| `agent:request` / `agent:trigger` | Its own new job |
| `grid:run` to a remote venue | A new job on that venue |

For isolation between steps, use `grid:run`, which creates a job with its own
`t/`.

| Scope | Use for | Lifetime |
|---|---|---|
| `t/` | Intermediate results passed between steps or subgoals | One job |
| `c/` | Conversational context, dialogue notes, topic state | One session |
| `n/` | Persona, learned patterns, long-term memory | The agent's life |
| `w/` | Outputs and anything other agents or users should see | Permanent |

A context-dependent path is resolved by the component its context names
(§4.6).

### 4.6 Path Resolution (design, covia#617)

A caller's path does not match the stored keys one for one. `w/foo` crosses the
`{updated, data}` wrapper, `j/<hex>` is a Blob key read as the job's current
state, `g/<id>` enters an agent record with its own `ts` stamp, `w/list/0` is a
vector index, and `n/x` depends on who is asking. Path resolution maps one onto
the other. Which reads need a grant is still decided by lists of namespaces
(`Engine.requireReadable`, `ToolPalette`, `CoviaAPI`), and write policy is an
allowlist in `CoviaAdapter`. This section is the design that replaces them.

**A path is strings.** A Covia path is the sequence of string segments a caller
writes (`w/list/0` is `"w"`, `"list"`, `"0"`), and it never holds a lattice
key. The component a segment reaches maps it to a key: `Jobs` reads hex as a
job's Blob id, `User` reads a namespace letter as a record field, and below the
last component a segment means what the value it meets needs. A path is a
`CoviaPath`, made only by its own factories: `parse` splits a string on `/`,
`of` takes a caller's string or array, and `segments` builds one from known
segments. Each refuses a segment that is not a string or that contains `/`
(`CoviaPath.SEGMENT_NOT_STRING`, `SEGMENT_HAS_SLASH`), so a path names the same
place as its segments joined with `/`, the address its authority is checked on.

**Resolution starts at a component.** A path is resolved by the component it
starts at, and the request decides only which component that is:

| Path | Starts at |
|------|-----------|
| a leading DID | the venue (`VenueState`), which passes the rest to that user |
| `n/x` | the request's agent |
| `c/x` | the request's session |
| `t/x` | the scratch of the request's task or job, which resolves `x` |
| `v/x` | the venue's user, at `w/global/x` |
| `x/<adapter>/…` | the adapter's view for the request's user |
| any other | the request's user |

`CoviaAdapter.resolve(ctx, path)` makes that choice and then hands the path to
the component, so resolution itself never sees the request; it checks no
authority, changes nothing and always returns a position. A request without
the scope its path starts at (an agent for `n/`, a session for `c/`, a task
or job for `t/`, a user for a path without an owner DID) is refused with
`WrongScopeException`. An `x/` view is the exception while adapters are
not components: the view depends on the request, so `AdapterView` checks the
caller's authority itself. The operation acting for the request checks the
caller's authority and applies its own write rules (*Components carry no
authority*, below). Authority is checked on the address of the data (`n/x` as
`<owner>/g/<agent>/n/x`), so a grant covers every route to it. Reads, writes,
deletes, the job-free read API, context loading and lattice paths in operation
references start their paths this way. Content hashes and other venues' paths
stay with `Engine.resolvePath` and federation.

**Components define their namespaces.** `ACoviaComponent extends
ALatticeComponent` is the base of every component a caller can address.
`resolve(path)` takes the stretch of the caller's path below the component and
returns the `Position` where it ends: the component that owns that place and
the stretch of the path it reads. A stretch is the segments `[start, end)` of a
whole path, sharing them without copying; it formats as the whole address up to
its end, so refusals name the path as the caller wrote it. `resolve` sees no
request, takes as many segments per step as the component's rules cover (as
cursor paths do) and passes the rest to the child, and builds each child only
from its own cursor, so a walk from a draft root stays in the draft.
`read()` returns the value as callers see it: a job reads as its current state.

| Component | Routes |
|-----------|--------|
| `VenueState` | a path's first segment, its owner's DID, to `Users`; `x/<adapter>` to the adapter's `Workspace` view |
| `Users` | a user's DID to their `User` (`Users.at`) |
| `User` | the names in its record's lattice (`Covia.USER_RECORD`): `g/<id>` to `AgentState`, `j` to `Jobs`, `s` to `SecretStore`, `h` to `Inbox`, `a` to `AssetStore`, and `w`, `o` to a `Workspace` |
| `Jobs` | a job id, in hex, to its `JobState` |
| `JobState` | `temp` to the job's `Workspace` |
| `AgentState` | `n` to the agent's `Workspace`; `sessions/<id>`, the id in hex, to its `SessionState` |
| `SessionState` | `c` to the session's `Workspace` |

Segments below the last component a path reaches are read by the value they
meet (`ValuePath`): a map key, a vector index written in digits, an Index key
as the string itself or, in hex, as a Blob. In a `Workspace` the path ends at
an `Entry`, whose keys are fixed when it is resolved (`ValuePath.keys`); a
level that does not exist yet takes the segment, the key of the map created
there. A stored null counts as present.

**Resolution follows the path's shape, not the data.** A component chooses a
child by its key whether or not the child exists, because children come and
go, within a transaction too: a position resolved early in a draft sees an
agent the draft creates later. `ACoviaComponent.exists` and `Position.exists`
say whether data is there now. A user, agent, session or job that does not
exist reads as absent, and a workspace in it refuses changes. An account is
reached through `Users.at`, which reads a record that is not live (never
registered, or a deleted account's tombstone) as absent, refuses changes to
it, and fences off a later account with the same DID as `Users.get` does.

A path that names no place data could be ends at an invalid position
(`Position.invalid`): a name a component does not have, such as any name in a
user's record outside its lattice, where the venue keeps its bookkeeping; a
first segment under the venue that is not a DID; or a job or session id that
is not hex. Reading, testing or changing an invalid position is refused with
`ACoviaComponent.NO_SUCH_PATH`, which names the path, and `Position.isValid`
asks without the refusal. The covia read operations and `/values` report that
refusal to the caller (READ_API.md). Code that tries several readings of a
string takes an invalid path for something else: `Engine.resolvePath` returns
null, and a context entry is text (`CoviaAdapter.isLatticePath`).

**Operations define writability.** A component's domain operations are the
only ways to change it. Each makes its own checks and then changes its cursor
in one atomic update; cursors carry no write policy, except that an `x/` view
applies its adapter's on each change. `Workspace` is the
component for a flexible data area reached by path, and an `Entry` is a value
in one: a path cursor at the entry's keys under the workspace's cursor, so its
changes land in the workspace's data. Its operations are `write`, `append`,
`delete`, `update` (a function of the current value) and `createLevels`. Each
changes the entry's key in the level above it in one atomic update, so a
stored null counts as present and a deletion removes the key. A change needs
every level above the entry to exist, each still the kind of value its key was
resolved against; otherwise it is refused and nothing is written. Only
`createLevels` creates missing levels, and an absent workspace root counts as
an empty map. The workspace's own operations, for its owner's code, take a
path and act through the entry it resolves to; only `createPath` among them
creates a level. A workspace inside a record (`n`, `c`, `temp`) refuses a
change while the record does not exist. A position in any other component is
read-only, except that `SecretStore`
accepts removal of a whole secret. Refusals are typed and carry a reason from a
constant on the refusing component.

**Paths are created above the component.** `covia:write`, `append` and `copy`
create the missing levels of their path and make their change in one
transaction over the workspace (`Entry.transact`). Creating a key changes the
map that holds it, so they ask for write authority at the nearest level that
exists, inside the transaction.

**Components carry no authority.** A component trusts the code that calls it:
Java code holding the component API is privileged venue code, tested, and
access-controlled where it acts for a request. Authority belongs to the
operation acting for a request. It checks the address it acts on
(`engine.requireLocalAccess`) before the change, and runs any check that depends
on current state inside the component's transaction, as `covia:write` does for
the levels it creates. A component refuses only what its data does not allow,
such as a change to a missing record or a deleted account; it never decides who
may act. `VenueContext`, shared by the venue's components and passed down
unchanged, holds the write clock and no access check.

`ACoviaComponent` is shaped so that it could move onto the Convex component
hierarchy.

---

## 5. Invocation and Security

### 5.1 Invocation

```
invoke("someop",
  { arg1: lookup(/my/data), arg2: "constant" },
  [ { with: "did:key:zAlice.../a/cafebabe...", can: "crud/read" } ]
)
```

`lookup()` resolves paths on the caller's side, so the operation receives plain
values and no lattice access. The job records the input. Capabilities travel as
UCAN `with`/`can` pairs (§6).

The invocation boundary is the security boundary: an operation sees data, not
cursors, and can reach no lattice path that a granted capability does not
cover.

### 5.2 Capability Scope by Path

A capability's resource is a path, and a path covers everything below it. The
risk follows the namespace: code is inspectable, data is sensitive, secrets are
encrypted.

| Capability | Risk |
|-----------|------|
| `with: did:.../o/`, `can: crud/read` | Low — code is inspectable |
| `with: did:.../o/cafebabe`, `can: invoke` | Medium — consumes compute |
| `with: did:.../a/cafebabe`, `can: crud/read` | Medium — exposes user data |
| `with: did:.../a/`, `can: crud/read` | High — exposes all user data |
| `with: did:.../w/projects/foo/`, `can: crud/read` | Medium-high |
| `with: did:.../w/`, `can: crud/read` | High — live working state |
| `with: did:.../s/`, `can: crud/read` | Low — ciphertext only |
| `with: did:.../s/key`, `can: secret/extract` | Highest — reveals plaintext |
| `with: did:.../w/results/`, `can: crud/write` | Medium |
| `with: did:...`, `can: *` | Maximum — trusted agents only |

Another user's data, `/o/` included, needs a grant (UCAN.md §5.2); `/s/` reads
return ciphertext even then.

---

## 6. Capability Model — UCAN

[UCAN.md](UCAN.md) is the full design: token structure, ability hierarchy,
delegation, verification, enforcement points and proof channels.

A capability pairs a resource (a DID URL) with an ability (a slash-delimited
verb). Tokens are signed by their issuer, so delegation chains verify without
a central authority. Covia stores tokens as CAD3 and transports them as JWTs;
the authorisation model is the UCAN specification's.

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

- **Resources** are DID URLs into an owner's namespaces; a sub-path attenuates
  its parent.
- **Abilities** form a prefix hierarchy: `crud` proves `crud/read`; `*` is the
  top ability.
- **Delegation** only narrows. Verification needs only the token, its proof
  chain and DID-to-key resolution.

---

## 7. Federation

An agent on one venue can name another venue's data or operations by DID, such
as `did:key:zAlice.../o/transform → did:key:zBob.../a/cafebabe...`.

- A content hash crosses venues freely: it verifies itself.
- A mutable path on another venue is resolved by asking that venue.
- Jobs can span venues. UCANs travel with the request, and each venue verifies
  the chain itself.

| Scenario | What is passed |
|----------|----------------|
| Local operation, local data | Values or paths (one trust domain) |
| Remote operation, local data | Values, resolved before sending |
| Any operation, content hash | The hash; safe to share |
| Any operation, mutable path | Never leaves the venue unless exported |

---

## 8. MCP and A2A

Venue adapters bridge external protocols to grid operations. They translate,
record and enforce capabilities, and never bypass the runtime: an inbound
request becomes an `invoke()` scoped to its caller, and an agent's outbound
calls are recorded in its timeline. Outbound, the `mcp` adapter wraps external
MCP servers' tools as operations (CONFIG.md, MCP tool bridging).

| MCP | Grid |
|-----|------|
| Tool | Operation |
| Resource | Asset (`/a/`) or workspace path (`/w/`) |
| Tool call | `invoke()` with the caller's capabilities |
| Tool result | Job output |

A2A agent exposure, agent cards, task mapping and authority are in
[A2A_AGENTS.md](A2A_AGENTS.md).

---

## 9. CVM Integration

The lattice carries data. The on-chain CVM anchors DIDs to keys, settles
payments for compute, storage and bandwidth, pins critical content for
high-assurance uses, and registers agents that take part in the economy.

---

## Appendix A: Lattice Mechanics

### A.1 Cursor Model

The engine holds one lattice cursor at its own venue's root. A cursor offers
`get()`, `set(value)`, `updateAndGet(fn)` (atomic read-modify-write), `fork()`
(an isolated working copy) and `sync()` (merging the copy back). A sub-cursor
such as `venueCursor.path(Covia.ASSETS)` writes through to the root atomically.
The venue reaches the global lattice only to sync with other venues.

Writes go through the component accessors — `VenueState`, `Users`, `User`, the
agent and job records — because each applies its record's stamp and creates
missing containers. A raw cursor path does neither.

### A.2 Merge Semantics

The signed venue value stores `:version` next to `:did` and `:timestamp`: the
integer storage format version, not the software version or the LWW stamp.
Missing means legacy `0`. Initialisation checks compatibility and applies
ordered migrations atomically with their version changes, before bootstrap or
workers start. Version `1` permits lazy adapter-record migration without
startup scans. See [Venue versions and migrations](PERSISTENCE.md#venue-versions-and-migrations).

**The venue `:value` is one whole-value LWW node.** Its wall-clock
`:timestamp` is re-stamped on every write, never inflated past real time. On
merge, the value with the newer `:timestamp` wins wholesale; the merge never
recurses into the interior. That makes deletions durable: a removed key is
absent from the winning value and cannot return through a per-entry union.

Distinct values can carry equal stamps (two writes in one clock tick). Local
writes never meet LWW: connected cursor updates are atomically ordered, and the
last successful update is current. For an external snapshot with an equal
stamp, the merge keeps `own`, the venue's current value, so merge sites must
put local state in the `own` position (covia#214, Convex-Dev/convex#641).

This needs **one authoritative running instance** per venue. `OwnerLattice →
SignedLattice` authenticates snapshots but cannot stop two processes running
with the same key, so deployment and failover must fence the old writer.
Reconciliation compares snapshots of one lineage, for backup, replication and
recovery. Choosing between independently executing forks discards one fork's
changes and cannot undo its external effects.

The **interior** of `:value` (`:version`, `:assets`, `:storage`, `:did`,
`:users`, `:schedule`, `:adapters`, `:adapter-versions`, `:user-deletions`,
`:user-data → <DID> → {j, g, s, w, o, h, a}`) is navigable but has no merge of
its own. Writes are stamped on the way up by a stamp-on-write boundary (Convex
`StampedCursor`) whose time comes from the `VenueContext` write clock.

The `w`, `o` and `h` namespaces are `{updated, data}` containers whose `data`
is what callers see; `User` presents each `data` through a stamp-on-write
boundary on its container. Navigating `w/foo` crosses the container and reads
or replaces `w.data.foo`; changed writes keep the container and ratchet
`updated` from the write clock. Callers never address `data`.

`:schedule` keeps its extensible `{updated, events, ...}` record shape. Writes
under `events` go through a stamp-on-write boundary, so they keep sibling
metadata and refresh `updated`.

| Region | Merge | Why |
|--------|-------|-----|
| `:grid → :venues` | `OwnerLattice` — per account key, signature-verified | One signing key per venue |
| Venue `:value` | `SignedLattice` over whole-value LWW by `:timestamp`, tie → own | One writer's lineage; deletions durable |
| `:grid → :meta` | `CASLattice` (union) | Shared, multi-writer content-addressed metadata |
| `:dlfs` | Per-user `OwnerLattice` → per-file DLFS merge | Independent per-user drives |

`:meta` and DLFS use ordinary commutative, associative, idempotent joins. The
venue `:value` is deliberately directional on an equal timestamp, relying on
its single writer; distinct authorities must not write the same venue entry.

### User meta record

Each user record carries a framework-owned `meta` slot beside its namespaces:

```
:user-data → <DID> → { meta: {created, updated},
                       j, g, s, a, w, o, h }
```

- **`created`** — epoch millis of the identity's first activity (first write
  into the record). Set once.
- **`updated`** — epoch millis of the latest write anywhere in the user's
  subtree. The record sits behind its own stamping boundary, so any deep write
  (an agent cycle, a session frame, a job, a secret, a workspace entry)
  refreshes it with no application code involved. Unchanged writes and merges
  do not.

`updated` measures write activity, the signal an identity-lifecycle policy
would use: an identity's idle time is `now - meta.updated`. Job-free reads do
not count, by design. Deleting a user (`user:delete`) removes their subtree,
so store GC can reclaim its cells.

`meta` is the venue's bookkeeping, not the user's data, so no path reaches it
(§4.6) and users cannot forge their activity. Operators see it in `user:info`
and `user:list`; it is not part of a user's export. New per-identity
fields belong in `meta`, not as further top-level keys.

### A.3 Update Logic

Engine holds one connected `VenueState`. Component mutations atomically replace
the shared root through the signed owner boundary:

```
startup: Engine → connected VenueState
request A: component update job X → signed root
request B: component update job Y → signed root
publication: application.host().sync() → current root
```

`updateAndGet` on a sub-cursor is atomic because it ends in a compare-and-swap
of the root. Compound domain edits use upstream
`TransactionalComponent.transact`: callbacks work on isolated views of the same
domain objects, and commit retries them if the state changed meanwhile.
Exceptions discard the attempt. Bootstrap uses this over the venue's
`w/global`. Request duration and persistence cadence are not transaction
boundaries.

`CoviaApplication` hosts `Covia.ROOT` through upstream `LatticeApplication`,
sharing its host's live cursor. `VenueState` holds the venue value as a Convex
`Timestamped` record: local edits stamp it, and incoming merges keep the newest
without re-stamping. `Users` gives lifecycle-guarded user views, and DLFS uses
upstream `DLFSRegion` and `DLFSDrives`.

`VenueState.fork().sync()` selects a snapshot; it is not a way to commit
concurrent work, because cross-component invariants and external outputs have
no general conflict-free merge. Live operations use `transact`. Interior
components, adapters included, have no publication policy of their own.

### A.4 Signing

The venue's Ed25519 key signs each connected component mutation as it crosses
the `SignedCursor` boundary. A transaction signs the completed value at commit:
an uncontended changed transaction signs once, and an unchanged one does not
sign. A signing failure leaves live state
unchanged. Rebinding restores editing policies on a draft, and commit targets
bound storage, so policies are not applied twice.

```
[Component mutation] → atomic cursor update → sign venue owner value
                                               |
                              application.host().sync() publishes current root
```

Signing makes a state transition authoritative in memory;
`application.host().sync()` publishes the root and `application.flush()` is the
durability barrier.

On receiving foreign state (disk recovery, replication, federation),
`SignedLattice.merge()` verifies the signature, merges if valid, re-signs a
result that differs from both inputs, and rejects an invalid signature.

### A.5 Persistence

[PERSISTENCE.md](PERSISTENCE.md) is the full design. In brief:

- **Startup** loads signed state with `store.getRootData()` and verifies it.
  Job recovery fails `PENDING` and interrupted `STARTED` jobs and restores the
  waiting family (`PAUSED`, `INPUT_REQUIRED`, `AUTH_REQUIRED`). AgentAdapter
  clears stale `RUNNING` and session fences, drops intake for terminal jobs,
  keeps jobless tasks, and wakes only remaining queued work. No agent execution
  is resumed mid-run.
- **Publication** runs every 100 ms, with a store durability barrier every 10
  seconds; request hooks may publish sooner. `engine.flush()` is the
  synchronous barrier, and `Engine.close()` publishes and flushes before the
  host and store close.
- **A crash** loses only writes since the last persistence point; the store
  always holds a coherent snapshot, so a client polling a job may see it return
  to that point. A backup may restore a newer snapshot signed by the same venue.

### A.6 Sharing and Replication

A venue's state is private by default. Because the lattice is hierarchical, a
venue can choose what to share:

| Shared | Use |
|--------|-----|
| Nothing but the DID document | Private venue, API access only |
| `:assets` | Discoverable operations and artefacts |
| `:assets` and `:meta` | Federated operation discovery |
| Everything | Public venue or backup replica |

Sharing is an export policy. A partial view is never merged back: `:value` is
one whole-snapshot LWW node, so a subset would delete what it omits. A replica
accepts complete snapshots signed by the venue's key and keeps the newer
`:timestamp`; equal stamps resolve to `own` (A.2). Entries under `:venues`,
`:meta` and DLFS keep their multi-writer joins.

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
| `LatticeContext` | Application policy for clock, signing and owner verification, used by the root merge; installed once on `CoviaApplication`, which also signs the venue record with it |
| `VenueContext` (Covia) | Policy shared by every Covia component, built once by `CoviaApplication` and passed down unchanged; holds the write clock (the application's) |
| `ACoviaComponent`, `Position` (Covia) | Base of addressable components (`resolve`, `read`); a position is the component that owns a path and the stretch of it that the component reads (§4.6) |
| `Workspace` (Covia) | A flexible data area reached by path (`w`, `o`, `n`, `c`, a job's `temp`, `x/` views); its changes create no missing level except `createPath` (§4.6) |
| `Entry` (Covia) | A value in a workspace, at keys fixed when its path was resolved; a path cursor under the workspace's cursor, changed at its key in the level above; no missing level is created except by `createLevels` (§4.6) |
| `Inbox` (Covia) | A user's HITL requests at `h`, keyed by request id; only the venue writes them |
| `Jobs` (Covia) | A user's jobs at `j`, keyed by job id, each its `JobState`; only the venue admits a job |
| `ACursor<V>` | Mutable reference with atomic get/set/update/fork/sync |

### Covia Definitions

`covia.lattice.Covia` defines the hierarchy from standard convex-core types:

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
| `w` / `o` / `h` | `StringKeyedLattice` (`Covia.WORKSPACE_RECORD`) | Existing `{updated, data}` container; `User` presents its `data` through a `StampedCursor` on the container |
| `:grid → :meta` | `CASLattice` | Shared content-addressed metadata (multi-writer union) |
| `:dlfs` | `OwnerLattice` → `MapLattice(DLFSLattice)` | Per-user signed drives, per-file rsync-like merge |
