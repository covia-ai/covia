# Agent Sessions — Design Proposal

**Status:** Draft — April 2026. Future epic, on hold pending other work.

This doc proposes **sessions** as a first-class agent primitive: persistent contexts where an agent maintains state and history. Sessions generalise across many patterns — chatbots, customer support, project work, research, pipeline runs — without baking any one pattern into the framework.

The current agent model assumes 1 transition = 1 timeline entry = 1 result snapshot. This is fundamentally a request handler model. It cannot express:

- Long-running conversations between an agent and a user
- Multiple concurrent relationships (agent talks to user A about project X while collaborating with agent B on project Y)
- Ongoing projects with milestones and evolving state
- Persistent working memory beyond the next transition's input
- Coherent "what is the agent currently working on" focus

Sessions fix this by introducing a layer between *agent* (long-lived identity) and *transition* (single compute step). Symptom issues #67, #68, #69 fall out naturally — they're all consequences of the missing session layer.

---

## 1. Problem

### 1.1 Current model

An agent record contains a flat `timeline` vector. Every transition appends one entry that snapshots the full state, config, result, and task results. There is no notion of:

- **Conversation continuity** — turn N+1 has no link to turn N other than re-reading the timeline
- **Logical work units** — a multi-step task is N independent timeline entries
- **Multiple parallel contexts** — all work is interleaved in one timeline
- **Memory beyond the next transition** — state is opaque to the framework, history is reconstructed from timeline

### 1.2 Symptoms (existing issues)

- **#67** — Config duplicated in every timeline entry (no notion of "config snapshot per session")
- **#68** — Result duplicated in `result.response` and `taskResults` (no notion of "session result")
- **#69** — Empty `messages`/`pending`/`inbox` per timeline entry (channels are agent-level, not session-level)
- **#71** — Manager agents lose context between pipeline steps
- **#57** — Multi-hop delegation timeout (no session = no resumable state)
- **General** — Cannot build a chatbot without faking conversation history

### 1.3 What's missing

A persistent context that an agent maintains state and history within. Independent of any single request. Discoverable, addressable, and pruneable.

---

## 2. Core Abstraction: Session

A **session** is a persistent context with its own state and history.

- **Identifier** — caller-supplied for continuity, framework-generated for new
- **Optional metadata** — type, counterparty (DID), subject, status, timestamps
- **Own state** — the agent's working memory for this context
- **Own history** — turns, events, tool calls, results within this context

Sessions live at `g/<agent>/sessions/<sid>/` in the agent's lattice namespace.

A session is *just data* — the framework doesn't enforce a particular shape for state or history beyond the standard fields. Different agent types can use sessions differently.

### 2.1 What a session is NOT

- Not a job (jobs are per-request execution units; sessions span many jobs)
- Not a task (tasks are discrete actionable items, often within a session)
- Not a transition (transitions are ephemeral compute steps within a session)
- Not a conversation (conversations are one *type* of session, not the only type)

---

## 3. Patterns Supported

All variations of the same primitive:

| Pattern | Session shape | Example |
|---------|---------------|---------|
| **Chatbot** | One session per (agent, user); long-lived dialogue | Personal assistant |
| **Customer support** | Session per ticket; counterparty = customer DID | Helpdesk agent |
| **Project agent** | Session per project; spans weeks; many transitions | Research collaborator |
| **Investigation** | Session per topic; agent accumulates findings | Audit agent |
| **Pipeline worker** | Session per pipeline run; ephemeral, completes | AP demo (Bob, Carol) |
| **Sessionless** | Pure request/response, no persistence | Pure extraction (Alice) |

The same agent can run multiple session patterns concurrently — a project agent might also handle ad-hoc questions in a separate ephemeral session.

---

## 4. Storage Layout

```
g/<agent>/
  config              — agent-level config (identity, default tools, caps, model)
  state               — agent-level state (rare; most state lives in sessions)
  status              — SLEEPING | RUNNING | SUSPENDED | TERMINATED
  sessions/           — Index of sessions, keyed by sid
    <sid>/
      meta            — type, counterparty, subject, status, started, last-activity
      state           — per-session working state (the agent's memory for this context)
      history         — vector of turns/events (full transcript)
      config?         — session-level config overrides (rare)
  cross-session/      — optional, future: memory shared across sessions
  inbox               — agent-level inbox (events not yet routed to a session)
  timeline            — agent-level audit log of session events (created, archived, ...)
```

### 4.1 What's per-session vs per-agent

| Per-session | Per-agent |
|-------------|-----------|
| Conversation history / turns | Identity (DID, keypair) |
| Working memory for this context | System prompt, model |
| Counterparty, subject | Default tool palette, caps |
| Task progress within this session | Cross-session memory (future) |
| Goal-tree frame stack (when persisted) | Long-term knowledge |
| Local config overrides | Default behaviour |

### 4.2 Session metadata shape (suggested)

```json
{
  "id": "conv-2026-04-13-mike",
  "type": "conversation",
  "counterparty": "did:key:z6Mk...",
  "subject": "AP demo improvements",
  "status": "active",
  "started": 1776079053991,
  "lastActivity": 1776082534100,
  "turnCount": 47
}
```

Type is a free-form string. The framework doesn't enforce a vocabulary — agents and tools can use whatever makes sense. Common values: `conversation`, `task`, `project`, `investigation`, `pipeline-run`.

---

## 5. API Surface

### 5.1 Existing operations gain optional `sessionId`

```
agent_request   agentId=X  sessionId=Y  input=...
agent_message   agentId=X  sessionId=Y  content=...
agent_trigger   agentId=X  sessionId=Y
```

Behaviour:
- `sessionId` supplied + session exists → continue that session
- `sessionId` supplied + session doesn't exist → create with that id
- `sessionId` omitted → see §5.4 default rule
- `session={type: ..., subject: ...}` → create new with framework-generated id, return it

### 5.2 New operations

| Op | Purpose |
|----|---------|
| `agent:sessionList` | List sessions for an agent (filter by type, counterparty, status) |
| `agent:sessionInfo` | Get metadata + summary for a session |
| `agent:sessionArchive` | Mark a session archived (no longer active) |
| `agent:sessionDelete` | Permanently remove (rare; audit-sensitive) |

Or — alternatively, no new operations: sessions are just lattice data, use `covia_list path=g/<agent>/sessions` to discover. This is more in keeping with the lattice-everything philosophy.

### 5.3 Discovery via standard lattice ops

```
covia_list  path=g/Assistant/sessions                       — all sessions
covia_read  path=g/Assistant/sessions/<sid>/meta            — one session's metadata
covia_slice path=g/Assistant/sessions/<sid>/history limit=20 — recent turns
```

### 5.4 Default session rule (open question)

Three options for "what session does a request belong to if `sessionId` omitted?":

| Option | Behaviour | Pros | Cons |
|--------|-----------|------|------|
| **A: Caller DID** | Auto-resolve to `g/<agent>/sessions/<callerDID-hash>` | Zero config for chatbots | Implicit; same caller can't have multiple parallel sessions without explicit ID |
| **B: Framework generates** | New session every request | Explicit; matches current model | Loses dialogue continuity by default |
| **C: Sessionless fallback** | Treat as current request/response model | Backward compat for pipeline agents | Two paths to maintain |

Recommended: **C as default, A or B opted in via agent config**. Pipeline agents stay sessionless; chatbot agents declare `defaultSession: "perCaller"` and get continuity.

---

## 6. Transition Contract

### 6.1 Sessionless (current, preserved)

```
input:  {agentId, state, messages, tasks, pending}
output: {state, result, taskResults?}
```

Pipeline agents (Bob, Carol) keep using this. No changes.

### 6.2 Session-aware (new)

```
input:  {agentId, session: {id, type, meta, state}, history, newInput}
output: {response, sessionState?, sessionMeta?}
```

The framework:
1. Loads session state and history before the transition
2. Provides them as transition input
3. Appends the new input + response to history after the transition
4. Updates session state if returned

### 6.3 Selection

The transition operation declares which contract it uses:
- `llmagent:chat` — sessionless (current)
- `goaltree:chat` — sessionless (current)
- `chatagent:reply` — session-aware (new)
- `goaltree:session` — session-aware variant (new, future)

Or via a config flag `state.config.sessionAware: true` that the dispatcher honours.

---

## 7. Lifecycle

```
Created → Active → Idle → Archived
                 ↓
              Suspended (on error)
```

- **Created** — new session, no turns yet
- **Active** — recent activity (configurable window, default 24h)
- **Idle** — no recent activity but not archived; can resume
- **Archived** — explicitly closed; read-only; counts toward audit but not active list
- **Suspended** — error state; needs manual resume (mirrors agent suspension)

Sessions are **never deleted** by the framework (audit). Archived sessions can be pruned by external tooling if storage demands it.

### 7.1 Session vs agent lifecycle

- Agent SLEEPING ↔ no active sessions running (but sessions persist)
- Agent RUNNING ↔ one or more sessions executing transitions
- Agent SUSPENDED ↔ all sessions paused
- Agent TERMINATED ↔ sessions still readable (audit) but no new transitions

---

## 8. Timeline as State Snapshots

### 8.1 Mental model

Timeline entries are **agent state snapshots at transition boundaries**, not events or conversation logs. Each entry captures "what the agent looked like at moment T" — not "what happened." This is already true today; the framing matters when sessions enter the picture.

### 8.2 With sessions

A snapshot is the **full agent state at the transition boundary**, including every active session in full:

```json
{
  "ts": 1776079053991,
  "transitionId": "...",
  "config": { ... full agent config ... },
  "state": { ... agent-level state ... },
  "sessions": {
    "conv-mike":  { "meta": {...}, "state": {...}, "history": [...] },
    "project-ap": { "meta": {...}, "state": {...}, "history": [...] }
  },
  "inbox": [...],
  "status": "RUNNING"
}
```

Lattice content addressing means unchanged sub-structures are deduplicated automatically — the inactive session's history isn't recopied, the unchanged config map points to the same cell as before. Each snapshot is "logically the whole agent at that moment" but physically only writes what changed.

### 8.3 What this gives

- **Complete reconstruction** — any past state of the agent is fully retrievable from one snapshot, no walking required
- **Multi-session captured naturally** — every active session is in the snapshot in full
- **Audit semantics straightforward** — "what was the agent at transition T?" is just reading the snapshot
- **Lattice dedup makes it free** — only changed cells take new storage; the snapshot's *logical* completeness is the correct mental model, the *physical* delta is the lattice's job

### 8.4 Cross-session view

To see all transitions across all sessions, the timeline is the right place — each snapshot already lists all active sessions. To see detail within one session, walk that session's history at `g/<agent>/sessions/<sid>/history`.

### 8.5 Could go further (out of scope for v1)

The lattice already tracks state changes via cursor history. Timeline could become a *view* over that history with transition-boundary annotations rather than a separately-appended structure. That's a deeper architectural change deferred for now.

---

## 9. Cross-Session Concerns

### 9.1 Lookups (rare but real)

- "Did this user mention X in any prior session?" — query across sessions filtered by counterparty
- "Across all my support tickets, what are common issues?" — analytics over sessions
- "Resume the conversation about Project Alpha" — find session by subject

Done via standard lattice ops (`covia_list`, filter, read). No special API.

### 9.2 Cross-session memory (future epic)

Sessions hold conversation history. **Memory** — facts, decisions, learnings — should be a separate layer:

- Episodic (what happened)
- Semantic (what's true)
- Procedural (how to do things)

Memory can be summarised from old sessions, accumulated across sessions, and queried independently. Out of scope for v1; design separately.

### 9.3 Multi-party sessions (open question)

A single session with multiple counterparties (e.g. a group chat). Not supported in v1. If needed, `meta.counterparty` becomes a vector and routing rules need defining.

---

## 10. Open Questions

1. **Session creation authority** — can callers, the framework, and the agent all create sessions? Who owns the id namespace?
2. **Default session rule** — A (caller DID), B (framework-generated), or C (sessionless)? See §5.4.
3. **Caps scope** — does each session have its own caps scope, or inherit from agent? Probably inherit by default, with optional per-session attenuation.
4. **Sub-sessions** — can a session spawn sub-sessions (project → tasks)? Or is the relationship flat with `meta.parent` references?
5. **Multi-party** — one session, multiple counterparties? See §9.3.
6. **GoalTree integration** — does each session get its own frame stack persisted? This unlocks resumable goal trees but adds storage.
7. **Storage limits** — per-session size cap? Per-agent total cap? Auto-archive on overflow?
8. **Session merge/fork** — should sessions support merge (combine two contexts) or fork (branch from a point)? Probably not v1.
9. **Visibility** — can other users/agents read sessions (with caps)? Default: only the agent and its owner.
10. **Scheduling** — can a session declare "wake me at time T" without an external request? See #65 (scheduled wake).
11. **Migration** — existing agents have flat timelines; do they get a default session retroactively or stay flat?
12. **Session contract for transitions** — is it one new operation per session-aware adapter (`chatagent:reply`, `goaltree:session`), or a config flag on existing ones?

---

## 11. Migration Path

### 11.1 Backward compatibility

Existing request/response model (sessionless) keeps working unchanged. Pipeline agents (Bob, Carol, Alice) continue with current contract.

### 11.2 Phasing

| Phase | Scope | Outcome |
|-------|-------|---------|
| **0 (now)** | Design doc + open questions | This file |
| **1** | Session storage + chatbot adapter | New `chatagent:reply` op; per-caller default; chatbot demo |
| **2** | Multi-session per agent | Explicit `sessionId` everywhere; session list/info ops |
| **3** | Timeline rework | Move heavy data to per-session history; agent timeline becomes audit log |
| **4** | Lifecycle automation | Auto-idle, auto-archive, suspended sessions |
| **5** | GoalTree integration | Persistable frame stack per session; resumable goal trees |
| **6** | Memory layer | Separate epic; episodic/semantic/procedural memory across sessions |
| **7** | Multi-party / scheduling | Advanced features |

Each phase is independently shippable. Phase 1 alone unlocks chatbots without touching pipeline agents.

### 11.3 Existing agents

Do nothing. They stay sessionless. If an owner wants to add session support, they update the agent config to use a session-aware transition op.

---

## 12. Why This is Right (and Why Not)

### 12.1 Why it's right

- **General primitive** — covers chatbots, projects, support, investigations, pipelines without baking any one pattern into the framework
- **Lattice-native** — sessions are just data at `g/<agent>/sessions/<sid>/`; standard tools work for discovery and inspection
- **Backward compatible** — sessionless mode preserved; pipeline agents unaffected
- **Solves real symptoms** — #67/#68/#69 fall out naturally; chatbots become possible
- **Phased** — chatbot in v1, advanced features later

### 12.2 Why it might be wrong

- **New primitive to learn** — agents authors now need to think about sessions in addition to state, config, tasks, inbox
- **Two transition contracts** — sessionless and session-aware; potential for confusion
- **Storage layout change** — moving heavy data to per-session history is a non-trivial migration if applied retroactively
- **Open questions are load-bearing** — default session rule (§5.4) and creation authority shape the whole API

---

## 13. Related

- AGENT_LOOP.md — current agent transition model that this generalises
- GOAL_TREE.md — frame stack abstraction that could plug in as session-scoped
- LATTICE_CONTEXT.md — context loading that sessions could leverage per-context
- Issues: #67, #68, #69, #57, #71 — symptoms this addresses
- Future: AGENT_MEMORY.md (not yet written) — separate memory layer
