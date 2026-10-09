# Projects — the `project` adapter

**Status:** design (v1); the adapter skeleton and the `projects` skill family
are shipped (§7, §11 P0), the operations are not. This document specifies
what a project is and why it is not a session, the project tree, the
adapter, and how agents are commissioned to execute and maintain it
autonomously.

A **project** is a long-running objective owned by a **principal** and
executed by an **agent**. It is a **tree**: a work breakdown structure
(WBS) in which every node — the project itself, a stage, a work package, a
single deliverable — has the same shape: a brief, deliverables with
quality criteria, a deadline, a budget, tolerances, an accountable
principal and an assignee. The assignee of a node is the principal of the
node's children. That one rule gives the PRINCE2 chain — Board commissions
Project Manager, Project Manager commissions Team Managers — as a property
of the data, and lets an agent decompose its objective into sub-objectives
it delegates, tracks, accepts and rolls up exactly as its own principal
does for it.

See [AGENT_SESSIONS.md](./AGENT_SESSIONS.md) for the communication
primitive a node uses, [AGENT_LOOP.md](./AGENT_LOOP.md) for the run loop
that does the work, [GRID_SCHEDULER.md](./GRID_SCHEDULER.md) for the
heartbeat, [UCAN.md](./UCAN.md) for how an assignee's authority is bounded,
and [ADAPTERS.md](./ADAPTERS.md) for the adapter contract this follows.

---

## 1. Problem

Today an agent is given work in one of two shapes:

- a **task** (`agent:request`) — one Job, one input, one result; the caller
  waits or polls;
- a **conversation** (`agent:chat` / `agent:message`) — turns in a session,
  no defined end.

Neither is the shape of "produce this by then, for no more than that, to
this standard, and tell me if you can't" — and neither survives
decomposition. A multi-week objective breaks into parts; the parts have
their own deadlines, budgets and acceptance criteria that must fit inside
the whole; some are delegated to other agents; spend and slippage in a
part are spend and slippage in the whole. Today that structure lives, if
anywhere, in a manager agent's system prompt and a free-form `w/`
document. Nothing in the venue knows the tree, wakes each assignee
tomorrow, stops a branch when its budget is gone, rolls a delay up to the
person who owns the deadline, or routes "I need a decision" one level up
rather than straight to the top.

### 1.1 Not a session

[AGENT_SESSIONS.md](./AGENT_SESSIONS.md) narrows a session to "a scoped
interaction between parties with its own history and context". A project
outlives any single session and is the artefact, not the channel:

| Concern | Session | Project |
|---------|---------|---------|
| Lifetime | Bounded dialogue | Weeks to years |
| Shape | Turns, tool calls, history | Briefs, plans, deliverables, a tree of typed records |
| Owners | Parties of the conversation | A principal, with an assignee per node |
| Access | Fixed participant list | Capabilities on the record and its delivery area, changing as nodes are assigned |
| Mutation | Append-only history | Structured edits to typed state, by role |
| Transport | MCP / A2A session id | Lattice path or DID URL |

Mixing them into one primitive conflates *how we talked* with *what we
built*. Keeping them separate lets each be simpler, and lets many sessions
reference one project (every assigned node has its own session with its
assignee; the principal may open others) or one session touch many
projects.

How the two interact:

- A session's metadata may include `projectRefs: [<node path>, ...]` —
  purely informational, written when a node's session is opened (§6.1).
- During a cycle the agent reads and writes the tree through the project
  operations (or, until they exist, the ordinary workspace operations). The
  tree's own authority model governs (§6.6); a session capability never
  grants project capability.
- Cross-session facts and decisions that are not project state belong to
  the memory layer (AGENT_SESSIONS.md §9.2), not to the tree.

## 2. Principles

1. **The project is the artefact, not the channel.** A project tree is a
   durable, lattice-addressed record in the root principal's workspace.
   Sessions reference nodes; the tree outlives them (§1.1).
2. **Every node is the same shape.** A work package is a small project.
   There is one record schema, one status machine, one set of operations,
   applied at any depth. The root differs only in having no parent.
3. **Hierarchy is delegation.** A node's assignee is its children's
   principal. Whoever executes a node may decompose it, commission its
   parts, set their targets within its own, accept their deliverables, and
   answer their exceptions — and is answerable upward in exactly the same
   terms.
4. **Manage by exception, one level at a time.** Targets and tolerances are
   set once per node, within the parent's. Inside tolerance the assignee
   decides; a forecast or actual breach is an exception to the node's
   principal, who decides or re-raises to their own principal. A human is
   only reached when the chain reaches a human.
5. **The 100 % rule.** A node's children together cover the node's work and
   nothing more. Children's deliverables compose the parent's; children's
   budgets sum to no more than the parent's; children's deadlines are no
   later than the parent's. The framework enforces the arithmetic; the
   assignee owns the decomposition.
6. **One authored fact, one home.** Each node has three authorship domains —
   principal, assignee, framework — and each field belongs to one. Rollups
   are framework-computed from children, never authored.
7. **Reuse the machinery.** Communication is a session, cadence is the grid
   scheduler, human contact is HITL, authority is UCAN. The adapter adds a
   record, a vocabulary, a heartbeat and rollup; no new lattice namespace,
   no new ability strings.
8. **The lattice is truth.** Status, spend, rollups and the log are on the
   tree. A restarted venue re-arms heartbeats from it; a client reconstructs
   history from it; nothing lives only in memory.

## 3. Roles

Roles are **per node**:

| Role | Who | Does |
|------|-----|------|
| **Principal** | The DID accountable for the node from above. Root: the human (or agent) who created the project. Any other node: the parent's *effective assignee*, by construction. | Authors the node's brief, deliverables, targets and tolerances. Assigns it. Accepts its deliverables. Answers its exceptions. Closes it. |
| **Assignee** | An agent `<owner>/g/<id>` explicitly assigned to the node, or absent. | Plans, executes, decomposes, commissions children, reports, delivers, raises exceptions. |
| **Effective assignee** | The assignee of the node, or of the nearest assigned ancestor. Every node under an assigned root has one. | Executes unassigned nodes directly, as items in its own plan, without a session of their own. |

Mapping to PRINCE2: root principal = Project Board / Executive; root
assignee = Project Manager; assignees of child nodes = Team Managers;
unassigned leaves = work the Team Manager does themself. Senior User and
Senior Supplier are not modelled; `acceptance.approver` on a deliverable
may name a DID other than the principal (§4.6).

Exactly **one** assignee per node. Collaboration is children beneath.

## 4. The project tree

### 4.1 Addressing

A project lives at `<principalDID>/w/projects/<pid>` — a plain map in the
root principal's workspace — the path UCAN.md already uses as its example
scope. Children live under `nodes/`:

```
w/projects/q3-report                       root node
w/projects/q3-report/nodes/data            child
w/projects/q3-report/nodes/data/nodes/fr   grandchild
w/projects/q3-report/out/...               conventional delivery area (§4.6)
```

`<pid>` and every `<nid>` are caller-chosen slugs matching
`[a-z][a-z0-9-]*`, like agent ids. A **node reference** is that physical
path — bare (the caller's own namespace) or a DID URL — with the root
shorthand `<pid>` accepted. `project:tree` reports each node's `path` in
this form and a display-only WBS code (`1.2.1`) derived from sibling
`order`; operations take the path, never the code.

The tree is ordinary workspace data: readable with `covia:read`, coverable
by `crud/*` capabilities on any subtree path, replicated like anything
under `/w/`. Its *shape* is enforced by the `project:*` operations that
write it, not by the namespace (§9, decision 1). Each node is written by
CAS on the root user record with a fresh `meta.updated`, as agent records
are; structural sharing keeps a deep tree cheap.

### 4.2 The node record

Root and child differ only in `meta.parent` and in who the principal is.

```json
{
  "meta": {
    "id": "q3-report",
    "title": "Q3 European market report",
    "principal": "did:key:zAlice...",
    "assignee": "did:key:zAlice.../g/pm",
    "status": "ACTIVE",
    "parent": null,
    "order": 0,
    "created": 1788000000000,
    "updated": 1788600000000
  },
  "brief": {
    "purpose": "Give the board a defensible view of Q3 demand in DE/FR/UK before the October pricing review.",
    "objectives": ["Quantify Q3 demand by country", "Explain variance against forecast", "Recommend Q4 pricing posture"],
    "scope": { "in": ["DE", "FR", "UK", "Q3 2026"], "out": ["Other regions", "Competitor teardown"] },
    "background": "w/docs/pricing-review-2026",
    "constraints": ["Use only data under w/data/sales/", "No external paid data sources"],
    "assumptions": ["Sales extract for September lands by 3 Oct"]
  },
  "targets": {
    "deadline": 1791244800000,
    "budget": { "tokens": 5000000, "cost": { "amount": 40, "currency": "GBP" } }
  },
  "tolerances": {
    "time": 172800000,
    "cost": { "percent": 10 },
    "scope": "May drop the pricing recommendation if data quality prevents it; must say so.",
    "quality": "Figures must reconcile to w/data/sales/ totals within 1%.",
    "risk": "Escalate any finding that implies a forecast miss over 15%."
  },
  "products": [
    {
      "id": "report",
      "title": "Market report",
      "purpose": "The board-facing document.",
      "composition": ["Executive summary", "nodes/data → demand.csv", "nodes/draft → per-country sections", "Recommendation"],
      "format": "Markdown, under 3000 words, tables for figures",
      "location": "w/projects/q3-report/out/report.md",
      "qualityCriteria": [
        { "id": "reconciles", "text": "Country totals reconcile to w/data/sales/ within 1%",
          "method": "op", "op": "o/reconcile-sales" },
        { "id": "sourced", "text": "Every figure cites its source path", "method": "review" }
      ],
      "acceptance": { "approver": "principal" },
      "status": "IN_PROGRESS"
    }
  ],
  "cadence": { "checkpoint": 3600000, "highlight": 86400000 },
  "team": ["did:key:zAlice.../g/pm", "did:key:zAlice.../g/analyst", "did:web:agents.example.com/g/writer"],
  "dependsOn": [],

  "plan": {
    "summary": "Reconcile data first (data), then draft sections in parallel (draft), then assemble and review.",
    "milestones": [ { "id": "draft-complete", "title": "Full draft assembled", "due": 1790500000000, "status": "IN_PROGRESS" } ],
    "next": ["Assemble sections from nodes/draft into report.md", "Run reconcile-sales on the draft"],
    "forecast": { "completion": 1790900000000, "tokens": 4200000 }
  },
  "registers": {
    "issues":    [ { "id": "i1", "raised": 1788300000000, "text": "FR September extract missing week 38", "status": "OPEN", "node": "nodes/data/nodes/fr" } ],
    "risks":     [ { "id": "r1", "raised": 1788100000000, "text": "Late September extract slips deadline", "likelihood": "medium", "impact": "high", "response": "Draft on 8 weeks, patch week 9" } ],
    "decisions": [ { "id": "d1", "ts": 1788400000000, "by": "did:key:zAlice...", "text": "Drop week 38 rather than wait", "ref": "h/0x3f..." } ]
  },

  "spend":  { "tokens": 400000, "jobs": 3, "cost": { "amount": 3.1, "currency": "GBP" }, "updated": 1788600000000 },
  "rollup": {
    "spend": { "tokens": 2100000, "jobs": 14, "cost": { "amount": 17.2, "currency": "GBP" } },
    "nodes": { "total": 6, "PENDING": 1, "ACTIVE": 2, "EXCEPTION": 0, "DELIVERED": 1, "CLOSED": 2, "BLOCKED": 0 },
    "products": { "total": 5, "delivered": 1, "accepted": 3 },
    "forecast": { "completion": 1790900000000 },
    "atRisk": ["nodes/data/nodes/fr"],
    "updated": 1788600000000
  },
  "session": { "sessionId": "0x9a7c...", "wake": "1788603600000/0x12" },
  "log": [
    { "ts": 1788000000000, "kind": "created",  "by": "did:key:zAlice..." },
    { "ts": 1788000500000, "kind": "assigned", "by": "did:key:zAlice...", "assignee": "did:key:zAlice.../g/pm" },
    { "ts": 1788003600000, "kind": "decomposed", "by": "did:key:zAlice...:g:pm", "nodes": ["data", "draft", "review"] },
    { "ts": 1788007200000, "kind": "checkpoint", "by": "did:key:zAlice...:g:pm", "summary": "Data and draft commissioned; review pending on draft", "tokens": 60000 },
    { "ts": 1788300000000, "kind": "child-exception", "by": "did:key:zAlice...:g:analyst", "node": "nodes/data", "summary": "FR week 38 missing threatens quality tolerance" },
    { "ts": 1788300900000, "kind": "exception", "by": "did:key:zAlice...:g:pm", "summary": "Re-raised: FR week 38 gap exceeds my quality tolerance", "ref": "h/0x3f..." },
    { "ts": 1788400000000, "kind": "decision", "by": "did:key:zAlice...", "text": "Drop week 38", "ref": "h/0x3f..." }
  ]
}
```

A child, in brief:

```json
{
  "meta": { "id": "data", "title": "Reconciled demand dataset", "principal": "did:key:zAlice...:g:pm",
            "assignee": "did:key:zAlice.../g/analyst", "status": "DELIVERED", "parent": "w/projects/q3-report", "order": 0 },
  "brief": { "purpose": "One reconciled CSV of weekly demand by country for the report to build on.",
             "scope": { "in": ["DE", "FR", "UK"], "out": ["Analysis", "Narrative"] } },
  "targets": { "deadline": 1789000000000, "budget": { "tokens": 1500000 } },
  "tolerances": { "time": 86400000, "cost": { "percent": 10 }, "quality": "Must reconcile to source totals within 1%" },
  "products": [ { "id": "dataset", "title": "demand.csv", "location": "w/projects/q3-report/out/data/demand.csv",
                  "qualityCriteria": [ { "id": "schema", "text": "Conforms to w/schemas/demand", "method": "op", "op": "v/ops/schema/validate" } ],
                  "acceptance": { "approver": "op" }, "status": "ACCEPTED" } ],
  "plan": { "summary": "One child per country; FR needs the missing week handled.", "next": [] },
  "spend": { "tokens": 900000, "jobs": 6 },
  "rollup": { "spend": { "tokens": 1300000, "jobs": 9 }, "nodes": { "total": 3, "CLOSED": 2, "DELIVERED": 1 } },
  "session": { "sessionId": "0x41d0..." },
  "log": [ "..." ]
}
```

Its children `nodes/data/nodes/{de,fr,uk}` have no assignee: the analyst
executes them itself, and they exist so that their deadlines, spend and
status are tracked and rolled up individually rather than lost inside the
analyst's plan.

### 4.3 Authorship domains

| Domain | Fields | Written by | Through |
|--------|--------|-----------|---------|
| **Principal** | `brief`, `targets`, `tolerances`, `products[*]` except `status`, `cadence`, `team`, `dependsOn`, `meta.assignee`, `meta.order` | The node's principal | `project:add`, `project:update`, `project:assign`, `project:move` |
| **Assignee** | `plan`, `registers`, `products[*].status` (to `DELIVERED` only), the *existence and principal-domain fields of child nodes* | The node's effective assignee | `project:plan`, `project:report`, `project:deliver`, and — as the children's principal — the operations above on them |
| **Framework** | `meta.status`, `meta.principal`, `meta.parent`, `meta.created/updated`, `spend`, `rollup`, `session`, `log`, `products[*].status` (to `ACCEPTED` / `REJECTED`) | The adapter | Side effects of every operation, of the heartbeat, and of child writes (rollup) |

A field is never written from two domains. An assignee cannot move its own
deadline, but it sets its children's — within its own. `meta.principal` is
derived (the parent's effective assignee at the time of the write) so the
chain can never be edited into inconsistency. `log` is append-only. The
root principal, holding `crud/write` on the whole record, may act as
principal *of any node* — the board can always reach in — and such writes
are logged with `by` so the intervention is visible.

### 4.4 Times, durations, money, and the 100 % rule

Every instant is milliseconds since the epoch and every duration is
milliseconds, as elsewhere in the venue (`created`, scheduler `time`,
`repeat.every`). API boundaries may accept ISO-8601 strings for
`targets.deadline` and `milestones[*].due` and normalise them on write.

`budget.tokens` and `spend.tokens` are model tokens — the unit the venue
already measures (`cycle:end` carries `tokens`; session `meta.tokens`
accumulates them). `budget.cost` is a declared amount in a currency the
principal chooses; the venue has no price list, so `spend.cost` is what
the assignee reports (§5.2). Tokens are enforced mechanically (§6.3); cost
is enforced by report and review.

`spend` is the node's **own** spend: its assignee's session for this node,
plus Jobs whose `parent` chain (JOBS.md) reaches a cycle of that session.
`rollup.spend` is own spend plus every descendant's rollup, and is what
tolerances are evaluated against. Unassigned nodes have no session and no
own spend; their assignee's session spend sits on the nearest assigned
ancestor. Work an assignee does itself on an unassigned leaf is therefore
counted at the ancestor, which is the node whose budget it was drawn from.

The **100 % rule** is checked on every `add`, `update` and `move`:

| Check | Rule | On violation |
|-------|------|--------------|
| Deadline | `child.targets.deadline ≤ parent.targets.deadline + parent.tolerances.time` | Rejected |
| Tokens | `Σ children.targets.budget.tokens ≤ parent.targets.budget.tokens × (1 + parent.tolerances.cost.percent)` | Rejected |
| Cost | Same, on `budget.cost` when both sides declare the same currency | Rejected |
| Tolerance | A child's tolerances may be narrower than the parent's, never wider | Rejected |

A child may omit a target, in which case it inherits the parent's for
evaluation and contributes nothing to the sum. Scope and quality
tolerances are text and are not checked, only inherited into the load the
assignee sees.

### 4.5 Status

Every node runs the same machine:

```
     project:add / create
            │
            ▼
      ┌──────────┐  assign, or first write    ┌────────┐
      │ PENDING  │───by effective assignee───▶│ ACTIVE │◀──────────────┐
      └──────────┘                            └───┬────┘               │
       (BLOCKED while a                            │                    │ resume
        dependsOn is open)     ┌───────────────────┼───────────┐        │ / decided
                               ▼                   ▼           ▼        │
                        ┌───────────┐       ┌───────────┐ ┌─────────┐   │
                        │ DELIVERED │       │ EXCEPTION │ │ PAUSED  │───┘
                        └─────┬─────┘       └─────┬─────┘ └─────────┘
                              │ all products      │ principal
                              │ accepted and all  │ decides
                              │ children CLOSED   └──────────────────────┘
                              ▼
                        ┌───────────┐
                        │  CLOSED   │◀── project:close from any state (COMPLETE | ABANDONED)
                        └───────────┘
```

| Status | Meaning | Heartbeat to this node | Assignee may write |
|--------|---------|------------------------|--------------------|
| `PENDING` | Defined, not started. `BLOCKED` is `PENDING` with an open `dependsOn` (reported in `tree`, not stored). | No | Yes (planning ahead) |
| `ACTIVE` | Being executed. Entered by `assign`, or by the effective assignee's first `plan`/`report`/`deliver`/`add` on it, once unblocked. | Yes, if assigned | Yes |
| `EXCEPTION` | An exception is open with the node's principal (§6.4). Work may continue within tolerance. | Yes | Yes |
| `PAUSED` | Paused by a principal, or a hard cap tripped (§6.3). Cascades to the subtree. | No | No (rejected with the reason) |
| `DELIVERED` | Every product is `DELIVERED` or `ACCEPTED`; awaiting acceptance. | Only on rejection | Yes (re-delivery) |
| `CLOSED` | Terminal. `meta.outcome` is `COMPLETE` (all products accepted, all children closed `COMPLETE`) or `ABANDONED`. Closing `ABANDONED` cascades to open children. | No | No |

Rules that only make sense in a tree:

- A node cannot close `COMPLETE` while a child is open. Closing a parent
  `ABANDONED` closes its open children `ABANDONED` with the parent's
  `comment`, and pauses their sessions.
- A node whose products are all accepted but whose children are not all
  closed stays `DELIVERED`; the framework closes it `COMPLETE` when the last
  child closes, and logs `closed` on it and `child-closed` on its parent.
- Pausing or resuming a node applies to its subtree; a child paused by its
  parent cannot be resumed on its own.
- A `CLOSED` node is never reopened. Continuation is a new sibling with
  `dependsOn` pointing at it.

### 4.6 Products

A product entry is a PRINCE2 Product Description trimmed to what an agent
can act on and a framework can check:

| Field | Meaning |
|-------|---------|
| `id`, `title`, `purpose` | Identity and why it exists. |
| `composition` | What it consists of — sections, files, records. Free text or list. By convention a parent's composition names the child nodes whose products it assembles (`nodes/data → demand.csv`), which is how the 100 % rule reads for deliverables. |
| `format` | Presentation requirements. Free text. |
| `location` | Where the delivered product lives: a path under the root principal's workspace (conventionally `w/projects/<pid>/out/<node path>/...`), a DLFS path, or an asset reference returned at delivery. Relative paths resolve in the *root principal's* namespace at every depth. |
| `qualityCriteria[]` | Each `{id, text, method}`. `method` is `review` (a human judges it against `text`), `op` (an operation decides: `{op, input?}`, invoked with the product's location merged into `input`, PASS iff the op succeeds — the same shape as a capability gate), or `test` (an `op` whose output is inspected for `{pass: true}` and a report). |
| `acceptance` | `{approver, timeout?}` — `principal` (default: the node's principal accepts, by HITL if human, by `project:accept` if an agent), `op` (accepted automatically iff every `op`/`test` criterion passes and there are no `review` criteria), or a DID (a HITL approval to that reviewer, who must hold a `hitl/request` delegation from the root principal). |
| `status` | `PENDING` → `IN_PROGRESS` → `DELIVERED` → `ACCEPTED` \| `REJECTED` (→ `IN_PROGRESS` on re-delivery). |

A node may have no products of its own: a pure decomposition node whose
deliverable *is* its children's. Such a node is `COMPLETE` when its
children are.

### 4.7 Rollup

`rollup` is framework-computed on every write to a node or descendant and
on every tick, bottom-up along the changed path only:

| Field | Computed as |
|-------|-------------|
| `spend` | Own `spend` + Σ children `rollup.spend` |
| `nodes` | Counts by status over the subtree, root excluded |
| `products` | Totals over the subtree |
| `forecast.completion` | max(own `plan.forecast.completion`, children's rollup forecasts) |
| `atRisk` | Descendant paths whose rollup spend or forecast is outside their own tolerance, or that are overdue |

`project:tree` returns rollups so a principal at any level sees the state
of everything beneath without reading it.

### 4.8 Dependencies

`dependsOn` is a list of sibling node ids. A node is `BLOCKED` until every
dependency is `CLOSED` `COMPLETE`. A blocked node may be assigned in
advance; its commission is delivered, and its heartbeat starts, when it
unblocks. A dependency closing `ABANDONED` raises an exception on the
dependent node's principal rather than silently unblocking. Cycles are
rejected. Dependencies are the only cross-node ordering the framework
knows; anything richer is the assignee's plan.

## 5. Operations

Adapter name `project`, dispatch `project:<op>`, catalog `v/ops/project/<op>`.
Every operation takes a `node` reference (§4.1). "Principal" and
"assignee" below are the roles *on that node*; the same agent is typically
assignee of one node and principal of the next level down, and uses both
groups accordingly.

### 5.1 Structure — building and maintaining the WBS

| Operation | By | Input | Effect |
|-----------|----|-------|--------|
| `project:create` | Anyone, in their own namespace | `{pid, title, brief, products?, targets?, tolerances?, cadence?, team?, assignee?}` | Writes the root as `PENDING`. If `assignee` is given, continues as `assign`. Exclusive create: fails if the slot exists. |
| `project:add` | Principal of the parent's children, i.e. the parent's effective assignee (or root principal) | `{node: <parent>, id, title, brief?, products?, targets?, tolerances?, cadence?, dependsOn?, order?, assignee?}` | Adds a child `PENDING` with `meta.principal` = caller's canonical identity, checks the 100 % rule, logs `decomposed` on the parent. If `assignee` is given, continues as `assign`. May be called with `nodes: [...]` to add several siblings atomically — the usual shape of a decomposition. |
| `project:update` | Principal of the node | `{node, brief?, products?, targets?, tolerances?, cadence?, dependsOn?, team?}` | Change control. Replaces the named principal-domain fields, re-checks the 100 % rule against parent *and* children, appends `changed` with the differing paths, and sends the assignee a `change` message. Cannot touch assignee or framework fields. |
| `project:move` | Principal of both old and new parent | `{node, parent?, order?}` | Re-parents or re-orders a subtree. `meta.principal` of the moved node is re-derived; the 100 % rule is re-checked at the destination; the whole subtree is re-keyed. Refused if the node is `ACTIVE` with a live session, unless `force` is set, in which case the session is kept and told. |
| `project:remove` | Principal of the node | `{node}` | Deletes a `PENDING` node with no children. Anything started is `close`d, not removed; the log is the audit. |
| `project:tree` | Anyone with `crud/read` on the node | `{node, depth?, status?}` | The subtree as `{path, wbs, id, title, status, assignee, deadline, spend, rollup, products: {total, accepted}, children: [...]}` — the WBS view. Default depth unlimited; `status` filters leaves. `readOnly: true`. |
| `project:info` | Anyone with `crud/read` | `{node, log?: n}` | One node in full, `log` truncated to the last `n`. `readOnly: true`. |
| `project:list` | Anyone | `{role?: principal\|assignee, status?}` | Nodes anywhere the caller is principal or assignee of, as `tree` rows. An agent's "my work" query. `readOnly: true`. |

### 5.2 Execution — doing the work

| Operation | By | Input | Effect |
|-----------|----|-------|--------|
| `project:assign` | Principal of the node | `{node, assignee, caps?, message?}` | Binds the assignee (§6.1): opens a session on the agent, delivers the commission, arms the heartbeat, moves `PENDING` → `ACTIVE` (or leaves it `BLOCKED`). Reassigning an `ACTIVE` node pauses the old session, logs `reassigned`, and commissions the new assignee with the full log. `assignee: null` unassigns; the node reverts to being executed by the effective assignee above. |
| `project:plan` | Effective assignee | `{node, plan?, registers?}` | Replaces `plan` and/or `registers`. No deep merge: the assignee owns these documents whole and re-writes them from its own context. |
| `project:report` | Effective assignee | `{node, kind, summary, detail?, spend?, forecast?}` | Appends a `log` entry. `kind` is `checkpoint` (routine), `highlight` (periodic summary upward), or `exception`. Reported `spend.cost` and `spend.jobs` overwrite the framework's figures for the node's own spend (the assignee is the only source of cost); `spend.tokens` is framework-owned and ignored. An `exception` raises to the principal (§6.4) and moves the node to `EXCEPTION`. |
| `project:deliver` | Effective assignee | `{node, product, location?, note?}` | Marks a product `DELIVERED` at `location` (default: its declared one), runs every `op`/`test` criterion and records the results on the log entry, then applies the product's `acceptance` rule (§6.5). Fails if an `op` criterion fails; the product stays `IN_PROGRESS` with the failures logged so the assignee can fix and retry. |
| `project:wake` | Assignee | `{node, after}` | Asks to be woken sooner than the next checkpoint (one-shot scheduler event, replaces any earlier ask). The project-scoped stand-in for the deferred `sleep` harness tool (SCHEDULER.md §7). |

### 5.3 Governance — acting as a node's principal

| Operation | By | Input | Effect |
|-----------|----|-------|--------|
| `project:accept` / `project:reject` | Principal of the node (or the product's named approver) | `{node, product, comment?}` | Records acceptance or rejection of a `DELIVERED` product. Rejection reopens the product and messages the assignee with the comment. These are what a HITL approval resolves to for a human principal; an agent principal calls them directly. |
| `project:decide` | Principal of the node | `{node, exception, decision, note?, extend?}` | Answers an open exception (§6.4): `proceed`, `extend` (deadline and/or budget, bounded by the principal's own tolerance — anything larger must be re-raised), `pause`, `abandon`, or free text. Logs `decision` on the node and `child-decision` on the principal's node; returns the node to `ACTIVE`. What a HITL answer resolves to for a human principal. |
| `project:pause` / `project:resume` | Principal of the node | `{node, reason?}` | Stops or restarts heartbeats and assignee writes for the subtree. Resume of a cap-tripped node requires the cap to have been raised by `update` (or `decide extend`) first. |
| `project:close` | Principal of the node | `{node, outcome?, comment?}` | Terminal. `outcome` defaults to `COMPLETE` when every product is `ACCEPTED` and every child is closed `COMPLETE`, otherwise `ABANDONED` (cascading). Cancels heartbeats, withdraws open exceptions, and sends each affected assignee a final `closed` message; logs `child-closed` on the parent. |

### 5.4 Internal

| Operation | Effect |
|-----------|--------|
| `project:tick` | The heartbeat, one scheduled event per **root**, fired by the grid scheduler under the root principal's captured authority (GRID_SCHEDULER.md §5) at the smallest `cadence.checkpoint` in the tree. `internal: true`, `track: false` — machinery, not user work, like the scheduled `agent:trigger`. See §6.2. |

## 6. Mechanics

### 6.1 Assignment and authority

`project:assign` crosses from the tree into an agent's namespace. In order:

1. **Standing.** The caller is the node's principal (identity), or holds
   `crud/write` on the root record (the root principal). The agent must be
   reachable: the caller is its owner, or is admitted by its
   `config.accepts`, or presents an `agent/message` delegation — the
   ordinary `Engine.crossUserAllows` gate.
2. **Authority the assignee needs.** `crud/read` on the root record (to see
   its node and everything above it), and `crud/write` on each of its
   products' `location`s — conventionally `w/projects/<pid>/out/<node path>/`.
   Where that comes from:
   - **Root principal's own uncapped agent** — has it already.
   - **Root principal's capped agent** — the root principal's `assign`
     appends the scopes to the agent's `config.caps` (`agent/write` on their
     own agent).
   - **`team` member** — the root principal listed the DID in `team` at
     `create`/`update`, and the adapter minted a project-wide token then
     (`ucan:issue` rules: resource in the caller's own namespace, custodial
     principal ⇒ venue-signed; self-sovereign ⇒ the caller supplies the
     signed token in `caps`). The token covers read on the record and write
     on `w/projects/<pid>/out/`; any node may be assigned to a team member
     without further grants. **This is the path an agent principal uses**,
     because an agent cannot mint (COG-17): it decomposes and commissions
     from the roster.
   - **Anyone else** — only a human caller can mint. An agent principal's
     `assign` of a non-team agent fails with `assignee lacks authority`, and
     the skill tells it to raise an exception whose options carry the grant
     (HITL `grants` on choice), so the human's one approval both authorises
     and, if the option says so, assigns.
3. **Session.** A dedicated session is opened on the assignee with the
   commission as its first pending message and a standing observed load of
   the node — `{op: "v/ops/project/info", input: {node}}`, the read-only
   load form re-read before each inference and appended only when changed
   (AGENT_CONTEXT.md). Every cycle therefore sees the node's brief, the
   inherited constraints of its ancestors (the load renders the ancestor
   chain's briefs and tolerances compactly above the node's own), the
   remaining time and budget, its children's rollup, open exceptions and
   the log tail, without a tool call. The session id is stored in the
   node's `session.sessionId` and the node path in the session's
   `meta.projectRefs` (§1.1).
4. **Heartbeat.** If the root has no tick scheduled, one is scheduled now
   under the root principal's authority; its handle is stored on the root's
   `session.wake`. Child nodes store nothing; the root tick serves them.
5. **Record.** `meta.assignee`, `meta.status: ACTIVE` (or stays `PENDING`
   while blocked), log `assigned` on the node and `child-assigned` on the
   parent.

The commission message is structured, not prose:

```json
{ "node": "did:key:zAlice.../w/projects/q3-report/nodes/data", "event": "assigned",
  "principal": "did:key:zAlice...:g:pm", "token": "<ucan, non-team cross-owner only>",
  "message": "<principal's note, optional>" }
```

The skill (§7) tells the agent what to do with it. Sending the brief in the
message would duplicate the load; the message is the trigger, the record
is the content.

### 6.2 The heartbeat

Each root `project:tick` walks the tree once, bottom-up:

1. **Rollup.** For every node, refresh own `spend` from its session's
   `meta.tokens` and its descendant Jobs, then recompute `rollup` (§4.7).
2. **Tolerances.** Evaluate §6.3 on every `ACTIVE` / `EXCEPTION` node
   against its rollup; take the actions; propagate `atRisk` upward.
3. **Dependencies.** Unblock nodes whose dependencies closed; deliver
   pending commissions to their assignees.
4. **Liveness.** A node whose assignee is `SUSPENDED` gets an exception to
   its principal; `TERMINATED` pauses the node with reason `agent`.
5. **Checkpoints.** For every assigned `ACTIVE` / `EXCEPTION` node whose
   own `cadence.checkpoint` (inherited if absent) has elapsed since its
   last checkpoint delivery, deliver
   `{node, event: "checkpoint", remaining: {time, tokens}, due: [...], children: {...}, highlightDue?}`
   to its session and wake the assignee. `due` lists milestones, products
   and *children* past their deadlines; `children` is the one-line rollup
   so a principal-agent sees its subtree without reading it. Ticks
   collapse: an undelivered checkpoint is replaced, never queued.
6. **Re-arm.** Schedule the next tick at the earliest next-due checkpoint
   across the tree, or cancel if no node is assigned and active.

The heartbeat is the only periodic wake. Everything else is event-driven:
a message from the principal, a HITL answer, a child's exception, delivery
or close, a rejection, a change to the brief, or the assignee's own
`project:wake`.

### 6.3 Tolerances

Three tolerances are mechanical; the framework evaluates them per node
against its **rollup** on every tick and every write:

| Tolerance | Evaluated as | Forecast breach | Breached | Hard cap |
|-----------|--------------|-----------------|----------|----------|
| **Time** | `now`, and `plan.forecast.completion`, against `targets.deadline` (+ `tolerances.time`) | forecast > deadline: log `forecast-breach`, raise exception once | `now > deadline`: log `overdue`, raise exception once, mark `atRisk` up the tree | `now > deadline + time`: raise again; keep working (time cannot be un-spent; the principal decides) |
| **Tokens** | `rollup.spend.tokens`, and `plan.forecast.tokens`, against `targets.budget.tokens` (+ `cost.percent`) | forecast > budget: log `forecast-breach`, raise exception once | `spend > budget`: log `over-budget`, raise exception once | `spend > budget × (1 + percent)`: `PAUSED` reason `budget`, subtree included |
| **Assignee liveness** | Agent status | — | `SUSPENDED`: exception | `TERMINATED`: `PAUSED` reason `agent` |

Because evaluation is against the rollup, a child's overspend is the
parent's overspend the moment it lands; the child's exception goes to its
principal (the parent's assignee) and, if that leaves the parent outside
its own tolerance, the parent's exception goes up in the same tick. The
human at the top hears about it only if the chain reaches them — and
hears it once, from the node whose tolerance it actually breached.

Cost, scope, quality and risk tolerances are text the assignee is told to
honour and to raise exceptions against; the framework cannot judge them.
The `exception` report is the instrument for every tolerance, mechanical
or not, and the skill says when: *whenever the plan's forecast leaves
tolerance, before the breach, not after*. "Raise once" means one open
exception per cause per node.

### 6.4 The escalation chain

An exception on node N — from `project:report kind: exception` or a
mechanical breach — goes to **principal(N)**, and only there:

- **Human principal** (a user DID): a `hitl:request` to their `h/` inbox,
  raised by the adapter with the venue as requester (the adapter acts
  after checking, as HITL itself does when writing to a target inbox). The
  answer resolves to `project:decide` and flows back as a verified message
  to N's session.

  ```json
  { "title": "Exception: q3-report / data — FR week 38 missing",
    "description": "<summary and detail; remaining time/budget at this node and its parent; the assignee's recommended options>",
    "asks": [ { "id": "decision", "type": "choice", "prompt": "How should this node proceed?",
                "options": [ { "id": "proceed", "label": "Proceed as recommended" },
                             { "id": "extend",  "label": "Extend deadline by tolerance" },
                             { "id": "pause",   "label": "Pause this node" },
                             { "id": "abandon", "label": "Abandon this node" } ] },
              { "id": "note", "type": "text", "prompt": "Instructions", "required": false } ] }
  ```

  The assignee proposes the options (`detail.options`); the adapter always
  appends `pause` and `abandon`. A `grants` entry on an option widens the
  assignee's authority in the same gesture (HITL's grant-on-choice), which
  is how "you may use the paid source after all" or "yes, commission that
  outside agent" is done without a separate `ucan:issue`.

- **Agent principal** (`<owner>:g:<id>`, the parent's assignee): a
  `child-exception` message to that agent's session for the *parent* node,
  carrying the same content, and a `child-exception` log entry on the
  parent. The agent answers with `project:decide` — within its own
  tolerance: it may `extend` a child only by what its own remaining
  tolerance allows, and the framework refuses more — or, if the child's
  problem is its problem, files its own `project:report kind: exception`,
  which goes one further level up. The child's exception stays open until
  decided; the parent's node shows `EXCEPTION` only if it raised one
  itself.

Decisions propagate down as events: `decided` to the child's session,
`child-decision` on the parent's log. `abandon` closes the child
`ABANDONED`; the parent's assignee then re-plans (its load shows the
closed child and the rejected work).

### 6.5 Acceptance

`project:deliver` on node N applies the product's `acceptance`:

| Approver | Mechanism |
|----------|-----------|
| `op` | Accepted iff every `op`/`test` criterion passed. |
| `principal`, human | HITL approval to the human, with the product description, location, check results and the assignee's note. Approval → `project:accept`; refusal → `project:reject` with the comment; expiry (`acceptance.timeout`) leaves the product `DELIVERED` and logs `acceptance-expired`. Nothing is accepted by silence. |
| `principal`, agent | A `child-delivered` message to the parent's assignee session with the same content. That agent inspects the product (it can read the location) and calls `project:accept` / `project:reject`. A team manager's output is accepted by the project manager, not by the board. |
| a DID | HITL approval to that reviewer, who holds a `hitl/request` delegation from the root principal. |

Acceptance of a child's products is how a parent's own products come to
exist: the parent's assignee assembles accepted child outputs into its
deliverable, then delivers upward. Nothing forces that ordering, but the
load shows the parent's `composition` naming child nodes and the rollup
showing which are accepted, and the skill makes the order explicit.

### 6.6 Capability enforcement

No new abilities. Every operation is `invoke`-gated as usual, then:

| Operation | Requires |
|-----------|----------|
| `create` | The caller's own namespace (bare `pid`). |
| `add`, `update`, `move`, `remove`, `assign`, `accept`, `reject`, `decide`, `pause`, `resume`, `close` | Caller identity is `meta.principal` of the node (the parent's effective assignee, canonical `<owner>:g:<id>` form) **or** the caller holds `crud/write` on the root record (the root principal, or their delegate). Plus `crud/read` on the node. |
| `plan`, `report`, `deliver`, `wake` | Caller identity is the node's *effective* assignee, or a principal above it. Plus `crud/read` on the node. |
| `tree`, `info`, `list` | `crud/read` on the node (`list` needs none: it returns only nodes the caller is party to). |
| Product content at `location` | Whatever `covia:write` / DLFS require there — the grants `assign` or `team` provision. |
| `tick` | Runs under the root principal's captured authority from the schedule; internal, not invocable by users. |

The adapter writes the tree under its own authority after these checks;
an assignee never needs `crud/write` on the record, so a broad workspace
grant does not let it edit its own brief, and a team member's project-wide
read does not let it write a sibling's plan. The identity check is the
load-bearing one: an agent's *scope* may be `null` (unrestricted), so a
scope-based guard would be absent on exactly the agent that most needs
bounding (UCAN.md §5.4). Pinning writes to the node's recorded roles is
what stops a worker from reporting on a node it was not assigned, or a
sibling's assignee from accepting a product it did not commission.

### 6.7 Recovery and shutdown

The tree holds everything: the root's `session.wake` is the scheduler
handle, the schedule persists (GRID_SCHEDULER.md §2), and each node's
session lives on its assignee's agent record. On boot the adapter walks
`w/projects/*` for every user with an open root, heals any stale handle,
and re-runs one tick per root — exactly as `rebuildSchedulerFromLattice`
does for agent wakes. Overdue ticks fire immediately. In-flight agent
cycles are the agent runtime's concern; a cycle lost to a crash is
re-driven by the next checkpoint. There is no project-side in-memory
state to lose.

## 7. Shipped skills and template

Following ADAPTERS.md, the adapter ships what an agent needs to play
either role — and it must play both, since the same agent is assignee of
one node and principal of the next. The skills are **shipped** (the
adapter's first increment); the template is not yet.

- **`v/skills/projects/`** — the skill family, one entry point plus one
  sub-skill per kind of project activity, so an agent loads the discipline
  it needs for the moment rather than one long procedure:

  | Skill | Role | Covers |
  |-------|------|--------|
  | `projects` (also `root/projects`) | both | The tree, the node record, roles, statuses, authorship domains, finding your nodes; opens the family |
  | `project-briefing` | principal | Brief, product descriptions, targets, tolerances, team, cadence, the 100 % rule, change control |
  | `project-planning` | assignee | Plan first, milestones and forecast, decomposition into children, registers, re-planning |
  | `project-delegation` | assignee as principal of children | Assigning from the team, the commission message, ad hoc sub-tasks, accepting child deliveries, deciding child exceptions |
  | `project-reporting` | assignee | Checkpoints, highlights, exceptions before the breach, the one-level-up escalation chain, honesty rules |
  | `project-delivery` | both | Writing products to their location, running quality checks, requesting acceptance, rejection, closing in order |
  | `project-monitoring` | principal | Reading rollups and at-risk nodes, silence detection, self-scheduled cadence, pause and resume, spend |

  Until the `project:*` operations exist, the skills declare only the
  lattice, agent, HITL, scheduler and schema operations that exist today,
  and teach the invariants the operations will later enforce (authorship
  domains, the 100 % rule, the status cascade, append-only logs). When the
  operations land, each skill's tool list gains them and its body tells the
  agent to prefer them; the procedures do not change.
- **`v/agents/templates/project-manager`** (planned) — a goal-tree agent
  (`v/ops/goaltree/chat`) with the `project:*` structure, execution and
  governance tools, the manager template's delegation tools,
  `covia:read/write`, and the skill above. `config.accepts: "owner"` by
  default; a principal who wants to commission it from another account
  adds their DID, and a project that wants it on the roster lists it in
  `team`.

A principal who wants a specialist (a coding agent with `claude-code:run`,
a research agent with `http:get`) forks or layers over this template; the
skill carries the project discipline and composes with any tool set. A
leaf worker that will never decompose can be given only the execution
tools.

## 8. Patterns

### 8.1 One agent, flat

```
project:create {pid: "q3-report", title, brief, products, targets, tolerances, assignee: "analyst"}
```

One node, one session. The analyst may still `add` unassigned children to
track its own parts separately; nothing requires it to. The user hears
from the project through HITL (exceptions, acceptance) and `project:tree`
when they choose to look.

### 8.2 A project manager agent that decomposes

```
project:create {pid: "q3-report", ..., team: ["g/analyst", "g/writer", "did:web:agents.example.com/g/reviewer"],
                assignee: "pm"}
```

The PM receives the commission, plans, and calls `project:add` with three
children, assigning `data` to the analyst, `draft` to the writer, and
`review` to the external reviewer with `dependsOn: ["draft"]`. Each has a
deadline and token budget inside the PM's. Each assignee gets its own
session and checkpoints; their exceptions reach the PM, not the user;
their deliveries are accepted by the PM; their spend rolls up into the
PM's node, against which the user's tolerance is evaluated. The analyst,
in turn, adds `de`/`fr`/`uk` as unassigned children of `data` to track
them. The user sees one tree.

### 8.3 Foreign agents and federation

A `team` member on another venue is commissioned like any other: the
project-wide token minted at `create` is delivered in the commission, the
agent reads its node and writes its product through DID-URL paths, and
both venues enforce the grant. The tree never leaves the root principal's
venue, however many venues its assignees are on.

### 8.4 Deterministic leaves

A leaf whose assignee is an orchestration-backed agent, or whose products
all have `acceptance: op`, runs as a scheduled pipeline stage with a real
deadline, budget and audit trail. The tree is useful even where the
"agent" is a rule; a PM agent can mix such leaves with LLM-driven ones.

### 8.5 Human work packages (future)

A node whose assignee is a user DID would be a work package for a person:
the commission and checkpoints become HITL requests, delivery is a HITL
answer with a location. Not in v1, but nothing in the record prevents it.

## 9. Decisions

1. **Workspace record, not a new namespace.** `w/projects/<pid>` costs
   nothing: existing `crud/*` caps, `covia:read`, replication, DID-URL
   addressing and federation apply, and a subtree is a path. The cost is
   that the root principal can `covia:write` the tree directly and bypass
   the shape; that is their own data and their own foot. If integrity ever
   needs to bind the root principal too, promotion to a typed `/p/`
   namespace is the upgrade path; the operations are its API either way.
2. **One tree, nested under `nodes/`.** A flat index keyed by WBS path
   would make subtree scans a range query but puts `/` inside keys, which
   fights path navigation. Nesting keeps every node a real lattice path,
   and structural sharing keeps deep trees cheap. Rollups are stored, not
   computed on read, so `tree` is one read.
3. **The parent's assignee is the child's principal.** Deriving
   `meta.principal` rather than storing it independently makes the chain
   unforgeable and makes "who do I escalate to" a lookup, not a policy.
4. **Escalation stops at the first principal.** An exception never skips a
   level. The root principal can still see everything (`tree`, `atRisk`)
   and reach in, but is not interrupted by problems their PM is expected
   to handle. This is the point of hiring a PM.
5. **Session per assigned node, not per agent and not per task.**
   Commissioning with `agent:request` and letting the task Job stand for
   the engagement was rejected: a standing task is perpetual `hasWork`, so
   the loop would run continuously and trip the stuck-task guard, and
   there is no `sleep` tool to park it (SCHEDULER.md §7). One session per
   assigned node gives each engagement its own context and its own
   checkpoint cadence, and lets one agent hold several nodes. When threads
   unification and `sleep` land, a task-backed commission becomes
   attractive because the principal gets a Job to await; the tree does not
   change.
6. **One heartbeat per root.** A scheduler entry per node would multiply
   handles and make re-arming a tree-walk anyway. One tick per root walks
   the tree, rolls up once, and delivers checkpoints to whichever nodes are
   due. Cadence remains per node.
7. **Identity-pinned writes.** Node operations check the caller against the
   node's recorded roles and write under the adapter's authority. No
   assignee holds `crud/write` on the tree; no new ability vocabulary.
8. **Team roster instead of agent minting.** An agent cannot mint grants
   (COG-17), so an agent principal could never commission anyone unless
   authority pre-existed. `team` lets the human authorise a roster once;
   everything an agent commissions from it needs nothing more, and
   anything beyond it is one HITL approval with a grant attached.
9. **Tokens are the enforced budget unit.** They are the only unit the
   venue measures. Money is declared, reported and rolled up, not enforced,
   until model assets carry prices (MODELS.md is the place for that).
10. **No deep merge on `plan`.** The assignee re-writes its plan from
    context each time. Partial updates invite drift between what the model
    believes the plan is and what is stored. Decomposition is the one
    structural plan the framework maintains, and it has its own operations.

## 10. Open questions

1. **Cost accounting.** Once model assets declare a price per token, should
   the tick compute `spend.cost` from the session's per-model token counts
   and demote the assignee's figure to an override? Probably yes; the
   inference record already keeps `model` per reply.
2. **Ad hoc delegation spend.** Work an assignee farms out with a plain
   `agent:request` (no node) is counted by Job through the `parent` chain
   but its tokens are not. The skill steers real delegation to child
   nodes, where it is counted; whether to chase `agent:request` tokens
   through the worker's session is open.
3. **Message-created sessions with loads.** `agent:request` accepts `loads`
   when it creates a session; `agent:message` does not. §6.1 step 3 needs
   that, or a small `agent:openSession` primitive. Framework change, scoped
   separately.
4. **Product provenance.** Should `deliver` pin the product's content hash
   (`asset:pin`, `a/<hash>`) on the log entry so acceptance is of an exact
   value rather than a mutable path? Cheap and probably right; deferred
   until LatticeContent is wired into storage.
5. **Stage gates.** PRINCE2 stage boundaries (the board approves the next
   stage plan before it starts) are expressible as a first-level node per
   stage with `dependsOn` chaining and `acceptance` on a "stage plan"
   product. Whether that deserves first-class support (`kind: stage`,
   an automatic HITL at the boundary) depends on whether principals ask for
   it.
6. **Concurrency on one root.** All nodes of a tree live in one user record
   and are written by CAS. Many assignees reporting at once will retry on
   conflict; retries are cheap and the writes are small, but a very wide
   tree with chatty leaves may want per-node write batching in the tick.
7. **Archival.** Same audit-versus-storage trade-off as sessions. A `CLOSED`
   subtree keeps its full logs; compaction into a summary plus a pinned
   asset is a later concern.

## 11. Phasing

| Phase | Delivers | Depends on |
|-------|----------|-----------|
| **P0 — Adapter and skills** (shipped) | `ProjectAdapter` registered with no operations; the `v/skills/projects/` family (§7) teaching the tree, roles, briefing, planning, delegation, reporting, delivery and monitoring over today's lattice, agent, HITL, scheduler and schema operations. Tests: registration, skill materialisation and root mirror, no unpublished tool refs, loud dispatch failure. | — |
| **P1 — Tree and lifecycle** | `create`, `add` (incl. batch), `update`, `move`, `remove`, `tree`, `info`, `list`, `pause`, `resume`, `close`; record schema; derived principal; 100 % rule; status machine with cascade rules; stored rollup; log. Tests: exact paths, exclusive create, authorship-domain and 100 %-rule rejection, cascade close/pause, rollup after every write, `move` re-keying. | — |
| **P2 — Assignment and heartbeat** | `assign` (own agent, Model A caps), per-node session with commission and load, root `tick` walking the tree, boot re-arm, `plan`, `report` (checkpoint/highlight), `wake`, `dependsOn` blocking. Tests: checkpoint delivery per node and collapse, unblock on close, re-arm after restart, identity pin on assignee ops, effective-assignee resolution. | Open question 3 |
| **P3 — Deliverables and acceptance** | `deliver` with `op`/`test` criteria, `accept`/`reject`, human HITL and agent-principal acceptance paths, auto-close on full acceptance and closed children. Tests: check failure keeps product open; human and agent round-trips; expiry; parent closes when last child closes. | HITL |
| **P4 — Tolerances and escalation** | Mechanical time/token/liveness on rollups, `decide`, human HITL and agent-principal exception paths, bounded `extend`, re-raise, hard-cap pause with cascade, raise-once. Tests: each row of §6.3; a child breach that does and does not breach the parent; an agent principal's over-tolerance `extend` refused. | P2, P3 |
| **P5 — Template and team** | `v/agents/templates/project-manager`, the `project:*` tools added to the `v/skills/projects/` family (shipped in P0 without them), `team` minting at `create`/`update`, cross-owner and federated assignment. Tests: the template runs a two-level, three-child project end to end against the test adapter; a foreign team member reads and delivers through a DID URL. | UCAN issue rules |
| **P6 — Refinements** | Worker-spend accounting (open question 2), product hash pinning (4), stage-gate support if wanted (5), write batching (6). | P4 |

---

## Related

- [AGENT_SESSIONS.md](./AGENT_SESSIONS.md) — sessions, `pending`, session metadata
- [AGENT_LOOP.md](./AGENT_LOOP.md) — run loop, wake, timeline
- [SCHEDULER.md](./SCHEDULER.md), [GRID_SCHEDULER.md](./GRID_SCHEDULER.md) — wakes and scheduled events
- [AGENT_TEMPLATES.md](./AGENT_TEMPLATES.md) — templates, delegation, `outputPath` handoff
- [UCAN.md](./UCAN.md) — capabilities, issuing, agent identity models
- [ADAPTERS.md](./ADAPTERS.md) — adapter contract, catalog, private state
- [JOBS.md](./JOBS.md) — Job records and `parent` links
