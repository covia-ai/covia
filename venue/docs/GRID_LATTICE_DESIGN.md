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
  anywhere, and Covia assumes no JSON shape: only an adapter that chooses
  JSON-only data for its own state relies on one. Tooling, chaining and UIs
  work best with JSON-like maps with string keys. Blobs, vectors and sets store
  and hash correctly but get less tooling support.
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
<DID>/<namespace>/<path...>     a principal's own data
[/]<namespace>/<path...>         from the request's root
```

- **DID** — the principal whose data it is: a user, agent or venue. Any DID
  method that resolves to a public key works (`did:key:`, `did:web:`, …).
- **Namespace** — one of the namespaces in §4.1.
- **Path** — keys within the namespace.

### 3.2 Where a Path Starts

Every name is resolved in a namespace, and its first element decides where it
starts. The namespace is only where the name is resolved, not whose authority
uses it. These rules hold on every route, for a path and for a capability's
`with` alike, and have one implementation each: `CoviaPath` for the syntax of
a path, `CapabilityChecker.canonicalResource` for a name resolved in a
namespace, `Engine.ownerOf` for the owner of a DID URL and
`AssetAdapter.parseAssetId` for an asset's hash. `ResourceNamesTest` tests
them.

- **Leading slashes change nothing**, however many there are and before a DID
  as well: `/w/notes` is `w/notes`. A trailing slash adds nothing to a path.
- **A DID starts at that principal's own namespaces:** `w`, `o`, `g`, `j`,
  `s`, `h`, `a`, and `x/<adapter>`, the adapter's view for that principal.
  The DID is everything up to the first `/`, exactly as written, so a
  `did:web` DID keeps its colons and `%3A`, and another spelling of a DID is
  another DID.
- **Any other path starts at the request's root:** the request's user's
  namespaces, plus the context-rooted namespaces `n`, `c`, `t` and `v`, whose
  root the request's context supplies (§4.5).
- **Every segment is kept as written**, with an owner or without: `.` and
  `..` never navigate, an empty segment is a key, and `%`, `?` and `#` are
  ordinary characters. DLFS paths are the exception, being filesystem paths
  ([ADAPTERS.md](ADAPTERS.md)).
- **A path reaches a namespace.** An empty path, `/` or a DID alone names no
  place, because a principal's record is never one value to hand out; the
  refusal names the namespaces of §4.1 a caller can use. As a capability's
  `with`, `/` and a DID alone are scopes: the whole namespace.
- **A context-rooted namespace after a DID names no place**, because no
  principal's data holds one: an agent's workspace is
  `<did>/g/<agent>/n/…`, and the venue's view shows `<venue>/w/global/…`.
- **A path given as an array** is its segments, none containing `/`; one
  whose first element is a DID is that DID URL.
- **A content hash names an asset, not a place.** `a/<hash>` is in the
  caller's asset store; a bare hash is the caller's own asset when its store
  holds it, and otherwise the venue catalog's (§4.3). Only those forms and
  `<did>/a/<hash>` name an asset: an `a` further along a path is just a key.

| Path | Names |
|------|-------|
| `w/notes`, `/w/notes`, `//w/notes`, `["w", "notes"]` | the request's user's `w/notes` |
| `did:key:zBob…/o/transform`, `/did:key:zBob…/o/transform`, `["did:key:zBob…", "o", "transform"]` | Bob's `o/transform` |
| `n/memory` | the request's agent's `g/<agent>/n/memory` |
| `v/ops/json/merge` | the venue's `w/global/ops/json/merge`, as every request sees it |
| `did:key:zBob…/x/preferences` | the `preferences` adapter's view for Bob |
| `did:key:zBob…/n/memory`, `<venue DID>/v/ops` | no place |

A path that names no place is refused with `CoviaPath.NO_SUCH_PATH`,
which names the path and the part of it up to the first segment that names
nothing, such as `No such path: /wrok/notes (nothing can be at /wrok)`.
Validity follows only the path's shape, so the refusal comes before any
access check and says nothing about the data. `AddressingTest` covers this
section.

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
| `v/` | Venue definitions | `<venue>/w/global`: `v/ops`, `v/skills`, `v/adapters`, `v/models` | Nothing: the venue changes its `w/global` |
| `x/<adapter>/` | Adapter view | That adapter's view for the addressed user | As the adapter allows; read-only by default |
| `dlfs/<drive>/` | DLFS drives | The owner's drive in the separate `:dlfs` region | The `dlfs` and `file` operations |

A user's record also holds the venue's bookkeeping, its activity times
(Appendix A, *User meta record*) and its account lifecycle. No path names
them.

`n/`, `c/`, `t/` and `v/` are context-rooted (§4.5). An `x/` route is the
adapter's own view, which it presents per principal under its own rules, and
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
keeping earlier versions. The update path refuses to leave a terminal
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

### 4.5 Context-rooted Namespaces

Every path is resolved in a request's context. For these namespaces the
context also supplies the root, so each is a short form of a full address:

| Prefix | Resolves to | Needs on the request |
|--------|-------------|----------------------|
| `n/x` | `g/<agent>/n/x` — the running agent's workspace | the agent |
| `c/x` | `g/<agent>/sessions/<session>/c/x` — the session's scratch | the agent and session |
| `t/x` | `j/<job>/temp/x` — the job's scratch | the job |
| `v/x` | `<venue>/w/global/x` — the venue's shared definitions, read-only | nothing |

`v/` is the venue's public read-only view. Every principal on the venue reads
it, whatever its caps, and nothing changes through it: the venue changes what
it shows in its own `w/global`, which needs the venue's authority.

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

A context-rooted path is resolved by the component its context names (§4.6).

### 4.6 Path Resolution and Access (covia#617)

A caller's path does not match the stored keys one for one. `w/foo` crosses the
`{updated, data}` wrapper, `j/<hex>` is a Blob key read as the job's current
state, `g/<id>` enters an agent record with its own `ts` stamp, `w/list/0` is a
vector index, and `n/x` depends on who is asking. Path resolution maps one onto
the other, and the component that owns the data decides who may use it. Write
policy is still an allowlist in `CoviaAdapter` (covia#617, step 7), and `x/`
views are still built outside the adapters' components (step 11).

**A path is strings.** A Covia path is the sequence of string segments a caller
writes (`w/list/0` is `"w"`, `"list"`, `"0"`), and it never holds a lattice
key. The component a segment reaches maps it to a key: `Jobs` reads hex as a
job's Blob id, `User` reads a namespace letter as a record field, and below the
last component a segment means what the value it meets needs. A path is a
Convex `Path`, made from what a caller writes only by `CoviaPath`, which holds
Covia's path syntax: `parse` splits a string on `/`, `of` takes a caller's
string or array, and `segments` builds one from known segments. Each refuses a
segment that is not a string or that contains `/`
(`CoviaPath.SEGMENT_NOT_STRING`, `SEGMENT_HAS_SLASH`), so a path names the same
place as its address, its segments joined with `/` (`CoviaPath.address`), on
which its authority is checked. A component passes the rest of a path to its
child as a slice of the caller's path, which still formats as the caller's
address up to its end.

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

`CoviaAdapter` makes that choice and then hands the path to the component, so
resolution itself never sees the request; it checks no authority and changes
nothing. A request without
the scope its path starts at (an agent for `n/`, a session for `c/`, a task
or job for `t/`, a user for a path without an owner DID) is refused with
`WrongScopeException`. An `x/` view is the exception while adapters are
not components: the view depends on the request, so `AdapterView` checks the
caller's authority itself. A request reaches the data only through
`CoviaAdapter.resolve(ctx, path)`, which returns an `Access` (*Owners decide
who may use their data*, below); `CoviaAdapter.locate(ctx, path)`, which
returns the component the path resolves to or null when it names no place, is
for venue code acting for itself.
Reads, writes, deletes, the job-free read API, context loading, tool and
skill references and lattice paths in operation references start their paths
this way. Other venues' paths stay with federation.

**Components define their namespaces.** Covia's components are Convex
`ALatticeComponent`s, with no base class of Covia's. Each that a path reaches
overrides Convex's resolution hook, `resolve(segments, start, end)`, which
takes the segments of the caller's request from `start` and returns the
component at the place they name; an empty range is the component itself. A
component reads the segments it owns and hands the rest to its child as
`child.resolve(segments, start + n, end)`, so `start` stays a position in the
caller's request; the array is the caller's, lent for the call, and anything
kept is copied. Resolution sees no request, takes as many segments per step as
the component's rules cover (as cursor paths do), and builds each child only
from its own cursor, so a walk from a draft root stays in the draft. A
component whose data only its own operations change hands the segments it
does not route to `CoviaValue.within`, which reads them in its value,
read-only; one that takes no path keeps Convex's default and refuses any
segment. Resolution returns Convex's `ALatticeComponent`, so a path may end at
a component that is not Covia's.

**An entity is a collection and its entries.** A kind of domain entity is two
components with different operations: the collection adds and finds entries
(`Inbox.open`, `Tasks.add`, `Jobs.create`), and an entry changes itself
(`HitlRequest.resolve`, `Task.park`). Each manages its own state as a lattice
component.

**Keyed collections are Convex's map components.** `Jobs`, `JobIndex`,
`AssetStore`, `ContentStore` and `Tasks` extend Convex's `IndexComponent`, and
`Users`, `ManagedUsers`, `SecretStore` and `Inbox` its `MapComponent`, so `get`,
`put`, `remove` and `size` are Convex's and `get(key)` is the entry as stored. A collection binds a
child component to one entry through `path(key)`, which reads a missing key as
absent, under a name of its own (`Users.user`, `Jobs.job`). Its domain
operations (`Users.create`, `Jobs.create`, `SecretStore.store`) keep the rules
that a `put` or `remove` of a raw entry bypasses, so only venue code that keeps
those rules itself uses them.

**A read returns the value as stored.** Reading a path gives the value at the
cursor of the component it resolves to, exactly as that cursor holds it, and
the reader interprets it; no component filters or reshapes what a read
returns. An account's record has no path of its own (§3.2): its layout is
internal and may move, so a caller reads what it needs by the paths and
operations that name it, not by its place in a larger value.

| Component | Routes |
|-----------|--------|
| `VenueState` | a path's first segment, its owner's DID, to `Users`; `x/<adapter>` to the adapter's `Workspace` view |
| `Users` | a user's DID to their `User` (`Users.at`) |
| `User` | its namespaces: `g/<id>` to `Agent`, `j` to `Jobs`, `s` to `SecretStore`, `h` to `Inbox`, `a` to `AssetStore`, and `w`, `o` to a `Workspace` |
| `Jobs` | a job id, in hex, to its `JobRecord` |
| `JobRecord` | `temp` to the job's `Workspace`; the rest of the job is read-only |
| `SecretStore` | a secret's name to its `SecretStore.Record`, which its store deletes |
| `Agent` | `n` to the agent's `Workspace`; `sessions/<id>`, the id in hex, to its `Session`; `tasks` to `Tasks` |
| `Tasks` | a task's id, in hex, to its `Task`; the agent's access rule covers both |
| `Session` | `c` to the session's `Workspace` |

Segments below the last component a path reaches are read by the value they
meet (`ValuePath`): a map key, a vector index written in digits, an Index key
as the string itself or, in hex, as a Blob. In a `Workspace` the path ends at
an `Entry`, whose keys are fixed when it is resolved (`ValuePath.keys`); a
level that does not exist yet takes the segment, the key of the map created
there. The entry keeps a copy of the request up to its end, so its refusals
name the path as the caller wrote it; segments below an entry continue it.
Below any other component the path ends at a `CoviaValue`: Convex's generic
`ValueComponent` over a read-only `PathCursor` at the keys the segments name,
taken the same way, so resolving it further keeps Covia's rules at any depth.
A stored null counts as present.

**Resolution follows the path's shape, not the data.** A component chooses a
child by its key whether or not the child exists, because children come and
go, within a transaction too: a component resolved early in a draft sees an
agent the draft creates later. A component's `exists()`, Convex's, says
whether data is there now, a stored null counting. A user, agent, session or job that does not
exist reads as absent, and a workspace in it refuses changes. An account is
reached through `Users.at`, which reads a record that is not live (never
registered, or a deleted account's tombstone) as absent, refuses changes to
it, and fences off a later account with the same DID as `Users.user` does.

A segment that names no place data could be throws Convex's
`NoSuchPathException` at that segment's position in the request: a name a
component does not have, such as any name in a user's record outside its
lattice, where the venue keeps its bookkeeping, or a context-rooted namespace
after a DID; a first segment under the venue that is not a DID; or a job or
session id that is not hex. An `Access` to such a path is still made: reading,
testing or changing it is refused with `CoviaPath.NO_SUCH_PATH`, which
names the path and where it stops naming a place (`Access.reasonInvalid`,
built by `CoviaPath.noSuchPath`), and `Access.isValid` asks without the
refusal. The covia read operations and `/values` report that
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
change while the record does not exist. `Entry.of` gives the entry to change
where a path ends (a workspace's root for the workspace itself); anything else
is read-only, except that `SecretStore` accepts removal of a whole secret. Refusals are typed and carry a reason from a
constant on the refusing component.

**Paths are created above the component.** `covia:write`, `append` and `copy`
create the missing levels of their path and make their change in one
transaction over the workspace (`Access.changeCreatingLevels`). Creating a key
changes the map that holds it, so they need write authority at the nearest
level that exists: it is checked before the transaction, and again inside it
only when more levels are missing by then.

**Owners decide who may use their data.** A component that owns a kind of data
is an `Owner`: it knows whose the data is and says who may use it, through its
`AccessPolicy`. An owner names the owners below it with one routing step
(`route`: the child owner the path's first segments name, and how many
segments that takes), which both its `locate` and `Owner.decide` use, so the
two agree. `decide` walks those steps from the owner a request starts at, and
the innermost owner on the way that has a policy decides, so the rule holds
however the path was written or where resolution started:

| Owner | Data | Who may use it |
|-------|------|----------------|
| `User` | `w`, `o`, `s`, `h`, `a`, `g` and `j` where nothing below claims them | authority over `<did>/<path>`: the user within its own scope, or a grantee |
| `Agent` | the agent's record and `n` | authority over `<owner>/g/<agent>/<path>` |
| `Session` | the session, and `c` | the agent serving the session reaches `c` of right; otherwise authority over `<owner>/g/<agent>/sessions/<id>/<path>` |
| `Jobs`, `JobRecord` | job records, and `temp` | work running in the job reaches `temp` of right; otherwise authority over `<owner>/j/<id>/<path>` |
| the venue's view | `v/` | any principal reads of right; nothing changes through it |
| `AdapterView` | `x/` | authority over `<did>/x/<path>`, and the adapter's own write policy |

A policy reads only the request and the stretch of the path below its owner,
never the data, and checks nothing itself: it says whether the request acts
of right, needs authority over an address, or is refused outright. An
`Access` asks it before each read or change and makes the one check through
Engine (`requireLocalAccess` for an address, `requireStanding` for a request
acting of right, so a deleted account is refused either way). An invalid
position is refused as no such path before any policy is asked. The owner
builds the address from its own identity, so a grant written as `w/foo` or as
`<did>/w/foo` covers the same data on every route.

A read is of data at a path or of an asset. A covia operation, `/values` and
a reference resolved by `Engine.resolvePath` read a path as data
(`Engine.requirePathRead`, `crud/read` through the owner's access), `a/`
included. Reading an asset as an asset (`asset/read`, or `crud/read` over its
hash) is for content-addressed references; a path given where an asset is
wanted, as by `/assets` or `/content`, is still read as data.
`Engine.requireMetadataRead` decides which.

Components themselves implement their data independently of whatever mediates
requests to them. Java code holding the component API is privileged venue
code, tested, and acts for the venue; code acting for a request goes through
an `Access`. A component refuses only what its data does not allow, such as a
change to a missing record or a deleted account. `VenueContext`, shared by the
venue's components and passed down unchanged, holds the write clock and no
access check.

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
`:jobs`, `:user-data → <DID> → {j, g, s, w, o, h, a}`) is navigable but has no merge of
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

`:jobs` holds the venue's entry for each unfinished job, by job id: what the
venue needs to carry the job to its end ([JOBS.md](JOBS.md#the-venues-job-entries)).
No path reaches it.

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

`meta` is the venue's bookkeeping, not the user's data, so no path names it
and no change by path reaches it (§4.6): users cannot forge their activity.
Operators see it in `user:info` and `user:list`; it is not part of a user's export. New per-identity
fields belong in `meta`, not as further top-level keys.

### A.3 Update Logic

Engine works on the application's one copy of its venue
(`CoviaApplication.venue`). Component mutations atomically update that copy,
unsigned, and publication signs it into the venue's record:

```
startup: Engine → application.venue(key), the venue's working copy
request A: component update job X → working copy
request B: component update job Y → working copy
publication: application.sync() → signed venue record
             application.host().sync() → published root
```

`updateAndGet` on a sub-cursor is atomic because it ends in a compare-and-swap
of the copy's root. Compound domain edits use upstream
`TransactionalComponent.transact`: callbacks work on isolated views of the same
domain objects, and commit retries them if the state changed meanwhile.
Exceptions discard the attempt. Bootstrap uses this over the venue's
`w/global`. Request duration and persistence cadence are not transaction
boundaries.

`CoviaApplication`, an upstream `ALatticeApplication` sharing its host's live
cursor, composes `:grid` from standard components: a `Regions` holding
`:venues` (an `OwnedMap` of signed venue records) and `:meta` (a
`LatticeComponent` over `CASLattice`). Incoming state goes to those components,
and other regions are left alone. `:dlfs` is a `DLFSRegion` that the host's
service registers beside it and hands to it. `VenueState` works on an unsigned
draft of the venue's signed entry (`OwnedMap.draft`), holding the venue value as
a Convex `Timestamped` record: local edits stamp it, and incoming merges keep
the newest without re-stamping. `Users` gives lifecycle-guarded user views, and DLFS uses
upstream `DLFSRegion` and `DLFSDrives`.

`VenueState.fork().sync()` selects a snapshot; it is not a way to commit
concurrent work, because cross-component invariants and external outputs have
no general conflict-free merge. Live operations use transactions. Interior
components, adapters included, have no publication policy of their own.

### A.4 Signing

The venue's Ed25519 key signs the venue's record when the application syncs.
Edits and transactions sign nothing. `CoviaApplication.sync()` syncs each
venue's copy, which reconciles it with the record and signs the result once,
then publishes the regions; an unchanged venue does not sign. A signing failure
publishes nothing and keeps the copy, edits included, for the next sync. The
application holds a copy only for a venue mounted in it, which brought its key
pair, refusing any other (`CoviaApplication.NOT_MOUNTED`) before an edit is
made; unmounting signs the copy. Rebinding restores editing policies on a
transaction draft, and commit targets bound storage, so policies are not
applied twice.

```
[Component mutation] → atomic update of the venue's working copy
                              |
   application.sync() signs the record → application.host().sync() publishes the root
```

The signed record is what the store and other nodes receive;
`application.host().sync()` publishes the root and `engine.flush()` is the
durability barrier. Incoming venue state delivered through the application
lands in the record, and the copy takes it at its next sync: whole-venue LWW
decides when both changed.

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
- **Publication** runs on the host's sync and flush workers, which whoever opens
  the host starts ([PERSISTENCE.md](PERSISTENCE.md#snapshot-implementation-contract));
  request hooks may publish sooner. `engine.flush()` is the synchronous
  barrier, and `Engine.close()` publishes and flushes before the host and store
  close.
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
| `IndexComponent<K,V>`, `MapComponent<K,V>` | Atomic operations on a keyed collection, an `Index` or a hash map, that the first write creates: `get`, `put`, `remove`, `size`, and `path(key)` to bind a child component to one entry (§4.6) |
| `VenueContext` (Covia) | Policy shared by every Covia component and passed down unchanged: the write clock and the venue's signer |
| `CoviaValue` (Covia) | Convex's `ValueComponent` resolving segments by Covia's rules (`ValuePath.keys`); read-only where a path ends below any component but a workspace, which hands it the rest of the path with `CoviaValue.within` (§4.6) |
| `Workspace` (Covia) | A flexible data area reached by path (`w`, `o`, `n`, `c`, a job's `temp`, `x/` views); its changes create no missing level except `createPath` (§4.6) |
| `Entry` (Covia) | A value in a workspace, at keys fixed when its path was resolved; a path cursor under the workspace's cursor, changed at its key in the level above; no missing level is created except by `createLevels` (§4.6) |
| `Inbox` (Covia) | A user's HITL requests at `h`, a `MapComponent` keyed by request id; only the venue writes them |
| `Jobs` (Covia) | A user's jobs at `j`, an `IndexComponent` keyed by job id, each bound as its `JobRecord`; only the venue admits a job |
| `ACursor<V>` | Mutable reference with atomic get/set/update/fork/sync |

### Covia Definitions

The hierarchy, built from standard convex-core types (`covia.lattice.Covia`
names its levels). Merges stop at `:value`; below it each component creates the
container it holds on the first change, so the stored shapes are plain data:

| Level | Component and stored shape | Purpose |
|-------|----------------------------|---------|
| Root | `CoviaApplication`: `Regions` over a keyword `Index` | `:grid`, a `Regions` of `:venues` and `:meta`; `:dlfs` is a sibling region its service registers |
| `:grid → :venues` | `OwnedMap` → `SignedLattice` → `Covia.VENUE` | Per-AccountKey signed venue state (one authoritative signing key) |
| Venue `:value` | `VenueState`: `Timestamped` keyword `Index`, ordered by `Covia.VENUE` | The whole per-venue value as one **whole-value-LWW** node with a stamp-on-write boundary; holds `:version`, `:assets`, `:storage`, `:did`, `:users`, `:schedule`, `:adapters`, `:adapter-versions`, `:user-deletions`, `:jobs`, `:user-data` |
| `:schedule` | `Schedule`: `StampedCursor` over a string-keyed map | Extensible `{updated, events, ...}` record; writes under `events` refresh `updated` and preserve sibling metadata |
| `:user-data → <DID>` | `Users`, `User`: string-keyed map of `StampedCursor` records | Per-user record; every deep write refreshes `meta.updated` |
| `j` / each Job | `Jobs` (an `Index` by job id), `JobRecord` (`StampedCursor`) | Existing Job map shape; deep writes such as `t/` refresh `updated` |
| `g` / each agent | string-keyed map, `Agent` (`StampedCursor`) | Existing agent map shape; deep writes such as `n/` and `c/` refresh `ts` |
| `s` / `a` | `SecretStore`, `AssetStore` | Plain maps |
| `w` / `o` / `h` | `Workspace`, `Inbox` over an `{updated, data}` map | `User` presents its `data` through a `StampedCursor` on the container |
| `:grid → :meta` | `LatticeComponent` → `CASLattice` | Shared content-addressed metadata (multi-writer union) |
| `:dlfs` | `DLFSRegion` → `MapLattice(DLFSLattice)` | Per-user signed drives, per-file rsync-like merge |
