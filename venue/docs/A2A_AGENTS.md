# A2A Agent Exposure

How a venue presents its individual **agents** to external clients over the A2A
(Agent-to-Agent) protocol. This is the implementation-facing companion to the
normative spec, **COG-14: A2A Agent Interoperability**; where they overlap, COG-14
is authoritative. For the agent model itself see [AGENT_SESSIONS.md](./AGENT_SESSIONS.md)
and [AGENT_LOOP.md](./AGENT_LOOP.md); for the capability model see the venue
access-control design and COG-10 / COG-13.

## Baseline and target

The venue's simplest A2A model treats the **whole venue as one agent**: a single
front-door Agent Card and a single configured chat operation. In that model the
venue's real agents — the `AgentAdapter` agents, each with its own config,
sessions, tasks and tool palette — are not individually reachable.

The target is additive: each agent is exposed as its own A2A agent, and the
single front-door agent is kept as a special case (the operator's public agent).

## The model

A2A is a **protocol facade over the existing agent operations and the capability
layer** — it introduces no new authority. An inbound A2A interaction resolves a
target agent and then flows through the same agent ops (`agent:chat` /
`agent:request`) under the same `RequestContext` capability check as a native
call. Nothing about arriving over A2A grants access that a native caller
wouldn't have.

The correspondence:

| A2A concept | Covia entity |
| --- | --- |
| Agent | an `AgentAdapter` agent at `<ownerDID>/g/<agentId>` |
| Agent Card | the agent's config (name/description) + skills derived from its offered operations |
| Agent endpoint | a per-agent route encoding the agent's address |
| Context | the agent **session** |
| Task | a **task** (unit of work) within a session |
| Registry / catalogue | the caller's own agents, from the agent-list read surface |
| Provider | the venue (its DID) |

The A2A context/task split — a conversation containing units of work — is the
same shape as the Covia session/task split, so the mapping is direct rather than
an impedance layer.

## Addressing and endpoints

An agent's canonical address is its grid address, `<ownerDID>/g/<agentId>` (the
same address used everywhere else in the lattice). A per-agent A2A endpoint puts
that address **verbatim** below the venue's A2A root:

```
/a2a/<ownerDID>/g/<agentId>
```

alongside the venue-level `/a2a` front door. The path below `/a2a/` *is* the grid
address — `g` namespace and all — one addressing vocabulary shared with the rest
of the lattice, not a parallel `/agents/` one. The DID, the `g` namespace, and
the agent id are each a single path segment; standard `did:key` / `did:web` DIDs
carry no path-reserved slashes (a `did:web` port is already `%3A`-encoded, which
is *not* a slash), so they need no escaping.

**Encoded slashes** are handled defensively at both layers: the venue's Jetty
rejects a raw `%2F` in the path by default (covia#153), and the codec rejects a
`%2F` anywhere in the address regardless of the transport's decode behaviour — so
a slash can never be smuggled into a segment. Agent ids are therefore
single-segment identifiers.

The **card** is served at the A2A well-known path *relative to the agent's base
endpoint* — the location a standard `A2ACardResolver` fetches given the base URL
(it treats the base as a tenant path and appends `/.well-known/agent-card.json`):

```
card:      GET  /a2a/<ownerDID>/g/<agentId>/.well-known/agent-card.json
endpoint:  POST /a2a/<ownerDID>/g/<agentId>          (what the card's interface advertises)
```

So a client resolves `A2ACardResolver.baseUrl("<venue>/a2a/<ownerDID>/g/<agentId>")`,
reads the JSON-RPC endpoint from the returned card, and POSTs there. A bare `GET`
on the base endpoint is not a card location. The endpoint ↔ address codec is
`A2ACodec.agentEndpointUrl` / `parseAgentEndpoint`.

Addressing is **universal and independent of access**: a well-formed endpoint
exists for any agent. Whether a caller may act on it is decided at resolution
time (§ resolution and authorisation), not by hiding the address space.

## Resolution and authorisation

An inbound interaction (1) resolves the target agent from the endpoint, then (2)
dispatches to `agent:chat` / `agent:request` under the caller's
`RequestContext`. The capability check is the existing one (COG-10 / COG-13):
owner, a presented capability over `<ownerDID>/g/<agentId>`, or an agent the
owner/operator made public. There is no A2A-specific branch in the trust
decision.

Agents are **private by default**. Denials are shaped so as not to leak an
agent's existence to callers with no standing: an **anonymous** caller gets a
*not-found*, an **authenticated** caller without access gets a *forbidden*. (This
mirrors how the current `GetTask` already hides foreign tasks.)

Two independent levers open a private agent, and both already exist as
primitives: a **UCAN capability** delegated on the agent's address (fine-grained,
revocable), and an **explicit public flag** in the agent's config (optionally
bounded by an attenuated ceiling for anonymous callers). The operator's
`defaultChatOp` front-door is the venue-level version of the second.

## Discovery

Three surfaces, each scoped by who is asking:

- **Well-known front door** — the operator's designated public agent, if any. A
  venue with none serves the "A2A not configured" response rather than a bare
  404 (already the behaviour when no `a2a` block is configured).
- **Authenticated catalogue** — the caller's own agents by default. This is A2A's
  authenticated / extended-card discovery path; the venue currently answers
  `GetAuthenticatedExtendedCard` with `UnsupportedOperationError`, so this is net-new.
  It leans on a job-free agent list/info read surface (see the agent-list read gap).
- **Direct addressing** — a caller that already holds an agent's base URL fetches
  its card from the well-known path (`<base>/.well-known/agent-card.json`) and
  `POST`s to the base to interact — subject to the same authorisation.

## Interaction and identifiers

Covia identifiers are reused verbatim — no parallel A2A id space. The A2A
`contextId` **is** the session id; the A2A `taskId` **is** the task id (both the
venue's Blob-hex identifiers). `AGENT_SESSIONS.md` already fixes `contextId =
session id`. The A2A status mapping (`A2ACodec`) already translates Covia job /
task status to A2A task-lifecycle states; extending it from Job-backed tasks to
session/task-backed interactions is a mapping change, not a new state machine.

Concretely, a per-agent `message/send` (fresh) is submitted to the agent as an
`agent:request` **task**, which mints the session and returns immediately with a
non-terminal Task the client polls — `agent:request`'s own capability check is
the ownership enforcement (facade over the capability layer), so no A2A-specific
trust branch exists. The precise `contextId = session` surfacing, `GetTask` /
`CancelTask`, and task continuations (an incoming `taskId`) are follow-ups.

## Relationship to the venue-as-single-agent model

The current single-agent behaviour stays as the **front-door** case: an operator
who configures one public agent keeps exactly today's well-known card. Per-agent
exposure is additive — new endpoints and the authenticated catalogue — and does
not change the front door.

## Build-out threads

Logical pieces this design implies (not an implementation plan):

- Per-agent endpoint routing and card rendering from agent config + skills from
  the agent's offered operations.
- The authenticated catalogue / `GetAuthenticatedExtendedCard`, over a job-free
  agent list/info read surface.
- The interaction mapping shifting from `Task = Job` to `context = session,
  task = task`.
- The per-agent public flag and its attenuated anonymous ceiling, alongside the
  existing UCAN delegation path.
