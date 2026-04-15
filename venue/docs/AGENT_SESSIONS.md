# Agent Sessions — Design Proposal

**Status:** Draft — April 2026. Future epic, on hold pending other work.

This doc proposes **sessions** as a first-class agent primitive: **scoped interactions between parties, with their own history and context, jointly owned by the participants**. A session is a communication primitive — a channel. Whatever the parties build together (documents, deliverables, long-lived work state) lives elsewhere (see [PROJECTS.md](./PROJECTS.md) for that layer).

The current agent model assumes 1 transition = 1 timeline entry = 1 result snapshot. This is fundamentally a request handler model. It cannot express:

- Long-running conversations between an agent and a counterparty
- Multiple concurrent scoped channels (agent has conversation A with user X about topic P while holding conversation B with agent Y about topic Q)
- Persistent working memory beyond the next transition's input
- Coherent "what channel is this message on" routing

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

A **session** is a scoped interaction between parties with its own history and context, jointly owned by the participants.

- **Identifier** — opaque, venue-minted, MCP/A2A-compatible (matches `Mcp-Session-Id` semantics and A2A `contextId`)
- **Parties** — the DIDs participating. Typically one agent + one counterparty; multi-party is a future question (§9.3)
- **Metadata** — parties, title, status, timestamps, optional counterparty-facing subject
- **Own history / context** — turns, tool calls, tool results, pinned context entries
- **Jointly owned** — every listed party holds read/append capability; neither is subordinate

Sessions live at `g/<agent>/sessions/<sid>/` in the agent's lattice namespace. The hosting venue mints the id; counterparties echo it on subsequent requests, exactly as in MCP.

A session is *just data* — the framework doesn't enforce a particular shape for state or history beyond the standard fields. Different adapter configurations can use sessions differently.

### 2.1 What a session is NOT

- Not a job (jobs are per-request execution units; sessions span many jobs)
- Not a task (tasks are discrete actionable items, often within a session)
- Not a transition (transitions are ephemeral compute steps within a session)
- Not a project (projects are domain state — documents, plans, deliverables — that sessions may reference but don't own; see [PROJECTS.md](./PROJECTS.md))
- Not a memory store (cross-session facts/decisions belong in a separate memory layer — §9.2)

---

## 3. Patterns Supported

All variations of the same primitive:

| Pattern | Session shape | Example |
|---------|---------------|---------|
| **Chatbot** | One session per (agent, user); long-lived dialogue | Personal assistant |
| **Customer support** | Session per ticket; counterparty = customer DID | Helpdesk agent |
| **Investigation** | Session per topic; agent accumulates findings in-channel | Audit agent |
| **Pipeline worker** | Session per pipeline run; ephemeral, completes | AP demo (Bob, Carol) |
| **Sessionless** | Pure request/response, no persistence | Pure extraction (Alice) |

The same agent can run many sessions concurrently — a counterparty may hold several concurrent sessions with the same agent, distinguished by id and title.

Long-lived work (projects, plans, deliverables) that persists beyond any one conversation is not modelled as a session — see [PROJECTS.md](./PROJECTS.md).

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
  "id": "01HXYZABC123...",
  "parties": ["did:key:z6MkAgent...", "did:key:z6MkUser..."],
  "title": "AP demo improvements",
  "status": "active",
  "started": 1776079053991,
  "lastActivity": 1776082534100,
  "turnCount": 47
}
```

The id is opaque and venue-minted (UUID-ish, MCP/A2A-compatible). `parties` is the authoritative access-control list — capability checks read it directly. Title is free-form, human-facing. Status tracks lifecycle (§7).

---

## 5. API Surface

### 5.1 Existing operations gain optional `sessionId`

```
agent_request   agentId=X  sessionId=Y  input=...
agent_message   agentId=X  sessionId=Y  content=...
agent_trigger   agentId=X  sessionId=Y
```

Session ids are always venue-minted — callers never supply their own. Behaviour:
- `sessionId` supplied + session exists → continue that session (caller echoes the id, MCP-style)
- `sessionId` supplied + session unknown → error (unknown session; caller can request a new one)
- `sessionId` omitted → see §5.4 default rule
- `session={title: ..., parties: [...]}` → create new session; the response includes the minted id

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
| **A: Default-per-caller** | Agent maintains one "default" session per caller DID (via index `g/<agent>/defaultSessions/<callerDID>` → `sid`); auto-resumes on unsessioned requests | Zero config for chatbots | Implicit; same caller can't have multiple parallel sessions without explicit ID |
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

### 6.4 Adapter unification (design amendment)

`llmagent:chat` is structurally a **depth-1 goaltree with persistent transcript**: a single frame, no subgoals, whose history carries across transitions. `goaltree:chat` is the general case (N frames, stateless per transition). The overlap is large enough that the two adapters should collapse into one.

**Proposed shape:** a single transition adapter (working name `agent:chat`) parameterised by:

| Flag | Effect |
|------|--------|
| `framed: false` (default) | Flat single-frame mode. `subgoal` disallowed. Equivalent to today's `llmagent:chat`. |
| `framed: true` | Multi-frame mode. `subgoal` allowed, child frames summarise on return. Equivalent to today's `goaltree:chat`. |
| `persistent: true` (default for flat) | Persist transcript / frame stack across transitions. |
| `persistent: false` (default for framed) | Fresh root on every transition. |
| `sessionAware: true` | Transcript / frame stack keyed by session id (see §6.2). Combines with either mode. |

**What this buys:**

- **Harness tools become uniform.** `more_tools`, `context_load`, `context_unload`, typed `complete`/`fail` are available in both modes. This directly fixes the gap where a flat-mode agent (today's `llmagent`) has no way to self-expand its tools mid-run.
- **Sessions are orthogonal.** A session-aware flat agent is a chatbot. A session-aware framed agent is a resumable goal tree. No new transition ops needed — §6.3's `chatagent:reply` and `goaltree:session` both fall out of the flag matrix.
- **One migration story.** Existing `llmagent:chat` agents map to `{framed: false, persistent: true, sessionAware: false}`; existing `goaltree:chat` agents map to `{framed: true, persistent: false, sessionAware: false}`. Transition-op names remain as aliases.
- **Smaller conceptual surface.** Agent authors pick flags rather than learning which of N adapters matches their pattern.

**What this costs:**

- A one-time refactor to merge `LLMAgentAdapter` and `GoalTreeAdapter`. Most of the code in each already does the same work (tool-call loop, context assembly, harness resolution) with subtle divergence.
- Harness-tool semantics that today depend on the frame model (`subgoal`, `compact`) need explicit guards when `framed: false`.

If pursued, this replaces the "one new adapter per session variant" approach in §6.3 and collapses the decision in §10 OQ #12.

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
    "support-01HXYZ": { "meta": {...}, "state": {...}, "history": [...] }
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
- "Resume the conversation titled Alpha" — find session by title

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
4. **Sub-sessions** — can a session spawn sub-sessions (e.g. a support session branching to a specialist)? Or is the relationship flat with `meta.parent` references?
5. **Multi-party** — one session, multiple counterparties? See §9.3.
6. **GoalTree integration** — does each session get its own frame stack persisted? This unlocks resumable goal trees but adds storage. Ties directly to §6.4: under adapter unification, a session simply owns whatever `{framed, persistent}` state its agent's config declares — the question becomes "is `persistent: true` the default for framed sessions?" rather than a separate integration project.
7. **Storage limits** — per-session size cap? Per-agent total cap? Auto-archive on overflow?
8. **Session merge/fork** — should sessions support merge (combine two contexts) or fork (branch from a point)? Probably not v1.
9. **Visibility** — can other users/agents read sessions (with caps)? Default: only the agent and its owner.
10. **Scheduling** — can a session declare "wake me at time T" without an external request? See #65 (scheduled wake).
11. **Migration** — existing agents have flat timelines; do they get a default session retroactively or stay flat?
12. **Session contract for transitions** — is it one new operation per session-aware adapter (`chatagent:reply`, `goaltree:session`), or a config flag on existing ones? See §6.4: if the adapter-unification amendment is adopted, this collapses to "flags on a single unified adapter" and the question is resolved.

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
| **5** | Adapter unification (§6.4) | Merge `llmagent:chat` and `goaltree:chat` into one flag-driven adapter; uniform harness tools (`more_tools` etc.) across both modes; persistable frame stack per session |
| **6** | Memory layer | Separate epic; episodic/semantic/procedural memory across sessions |
| **7** | Multi-party / scheduling | Advanced features |

Each phase is independently shippable. Phase 1 alone unlocks chatbots without touching pipeline agents.

### 11.3 Existing agents

Do nothing. They stay sessionless. If an owner wants to add session support, they update the agent config to use a session-aware transition op.

---

## 12. Why This is Right (and Why Not)

### 12.1 Why it's right

- **General primitive** — covers chatbots, support, investigations, pipelines without baking any one pattern into the framework
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

- [AGENT_LOOP.md](./AGENT_LOOP.md) — current agent transition model that this generalises
- [GOAL_TREE.md](./GOAL_TREE.md) — frame stack abstraction that could plug in as session-scoped
- [LATTICE_CONTEXT.md](./LATTICE_CONTEXT.md) — context loading that sessions can leverage per-context
- [PROJECTS.md](./PROJECTS.md) — long-lived work state that sessions reference but don't own
- Issues: #67, #68, #69, #57, #71 — symptoms this addresses
- Future: AGENT_MEMORY.md (not yet written) — separate cross-session memory layer
