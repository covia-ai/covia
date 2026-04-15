# Projects — Design Placeholder

**Status:** Sketch — April 2026. Placeholder for a future epic, deliberately separate from sessions.

This doc holds design thinking for **projects** — long-lived, shared work state that agents and counterparties build up over time. Projects are explicitly **not** sessions: [AGENT_SESSIONS.md](./AGENT_SESSIONS.md) narrows sessions to "scoped interactions between parties with their own history and context." A project outlives any single session and is the artefact, not the channel.

---

## 1. Why separate from sessions

| Concern | Session | Project |
|---------|---------|---------|
| Lifetime | Bounded dialogue | Weeks to years |
| Shape | Turns, tool calls, history | Documents, plans, deliverables, domain state |
| Owners | Parties of the conversation | Organisation, team, or party group |
| Access | Fixed participant list | Arbitrary ACL that evolves |
| Mutation | Append-only history | Structured edits to typed state |
| Transport | MCP / A2A session id | Lattice path or URL |

Mixing them into one primitive conflates **how we talked** with **what we built**. Keeping them separate lets each be simpler and lets many sessions reference one project (or one session touch many projects).

---

## 2. Rough shape

A project is a lattice-addressable bundle of:

- **Metadata** — id, title, description, status, owner(s), ACL, timestamps
- **State** — the actual work: documents, plans, data, typed records
- **History** — structured revision log (who changed what, when, and why)
- **Refs** — sessions that touched this project, external resources, parent/child projects

Path sketch: `w/projects/<pid>/...` in the owning entity's workspace namespace. Content-addressed sub-structures dedupe naturally via the lattice.

---

## 3. How sessions interact with projects

- A session's metadata MAY include `projectRefs: [pid, ...]` — purely informational.
- During a transition, the agent can read/write projects via ordinary workspace tools (`covia_read`, `covia_write`). The project's own ACL governs — a session capability doesn't grant project capability.
- Projects are reusable across sessions (several concurrent conversations about the same project) and sessions are reusable across projects (one long conversation that touches many projects).

This keeps sessions as a pure communication primitive and projects as pure work-state. Neither abstraction has to know much about the other.

---

## 4. Open questions (for the future epic)

1. **ACL model.** UCAN caps on `w/projects/<pid>/` with role primitives (owner/editor/viewer)? Or is this just workspace ACL that already exists?
2. **Schema enforcement.** Do projects declare a schema for their state, or is it free-form? Different domains (AP invoices, research notes, code reviews) want different shapes.
3. **Revision history.** Explicit append-only log, or rely on lattice cursor history?
4. **Multi-agent collaboration.** Multiple agents editing one project concurrently — merge semantics? Lattice CRDTs give us this for free if the data shape cooperates.
5. **Archival and deletion.** Same audit-vs-storage tradeoff as sessions.
6. **Cross-venue projects.** A project owned by an organisation but touched by agents on multiple venues — where does it live canonically?

---

## 5. Explicitly out of scope (for now)

- Any code — this is a placeholder to pin the concept and keep sessions clean
- Task management within projects (that's a project-state design choice, not a framework concern)
- Memory (cross-session/cross-project facts belong to the memory layer — see AGENT_SESSIONS.md §9.2)

---

## 6. Related

- [AGENT_SESSIONS.md](./AGENT_SESSIONS.md) — the communication primitive this complements
- [GRID_LATTICE_DESIGN.md](./GRID_LATTICE_DESIGN.md) — workspace namespace conventions
- [UCAN.md](./UCAN.md) — capability model that a project ACL would build on
