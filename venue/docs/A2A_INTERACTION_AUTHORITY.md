# A2A Interaction Authority

The crux of per-agent A2A exposure: when a **non-owner** interacts with an
agent, **under whose authority** does the agent run, and **bounded by what**?
This is the authority model that [A2A_AGENTS.md](./A2A_AGENTS.md) §"Resolution and
authorisation" defers to.

For the agent model see [AGENT_SESSIONS.md](./AGENT_SESSIONS.md); for the
capability primitives see the venue access-control design and COG-13.

## Principle: an agent always runs as itself, in its owner's namespace

An agent is the owner's. Its config, its workspace memory, its secrets, and any
credits or downstream authority it spends are the **owner's**. It follows that an
agent **executes in its owner's namespace, as the owner's agent sub-principal —
always, for every caller** (`RequestContext.ofAgent(owner, agentId)`, bounded by
its own `config.caps`). A caller's identity never becomes the execution identity.

This settles the shape of the whole problem. The question is never "run the agent
*as Bob*" (Bob has no standing in the owner's namespace, and an agent cut off from
its owner's memory and secrets cannot function). The question is only:

> May this caller cause a run?

## Two separable questions

Two orthogonal things must stay apart:

1. **Admission** — *who* may cause the agent to run at all.
2. **Execution authority** — once running, *how much* of the owner's authority
   the run may exercise.

Admission is about the knock at the door; execution authority is about how much
power the resulting run holds. Keeping them separate is what makes the model
tractable.

## One authority per agent

Every run — owner-initiated or not — is bounded by **the agent's own
capability**: its config `caps` (the `CapabilityChecker` gate on its tool calls)
bounds what the agent may *ever* do, independent of any caller, the owner's own
interactions included. This is the owner's standing statement: "this is what my
agent is allowed to do." It is the bound on every run, and the only one.

There is deliberately no second, caller-dependent bound that narrows a run for an
outside party. An agent is one brain with one memory: a run bounded in what it
may *do* for a stranger still knows everything the agent knows, so a per-caller
scope would limit actions without isolating knowledge, and it would have to be
threaded through a run loop whose cycle serves one task and every pending
message. The honest unit of isolation is the agent. **An owner who wants a
narrower public face publishes a dedicated agent whose `config.caps` is that
face** — with its own state, workspace and skills, so a stranger reaches neither
the private agent's authority nor its memory.

**Admission** — owner (always); anonymous/public (via the agent's public flag);
a named delegate (via a UCAN grant). Denials are leak-shaped — an anonymous
caller gets *not-found*, an authenticated caller without standing gets
*forbidden* — so the address space is not a disclosure oracle.

Effective authority of a run = the agent's `config.caps`, executed as the agent
in the owner's namespace, admitted by whichever lever the caller came through.

## Identity for authority vs identity for provenance

For a non-owner interaction these **diverge**, and both are recorded:

- **Authority identity** = the **owner** (whose namespace, and whose agent's
  `config.caps`, the run uses).
- **Initiator identity** = the **caller** — the delegate's DID, or the venue
  public principal (`<venueDID>:public`) for an anonymous caller (who *caused*
  the run).

The audit trail carries both. A request admitted through the public lever is
dispatched as the caller acting as a sub-principal in the owner's namespace
(`Authority.of(caller).onBehalfOf(owner)` under the admitting scope), so the
task Job records the owner as `caller` (ownership, quota, persistence) and the
initiator as `actor`, and the agent's task row names the initiator as its
`caller`. The model sees the same provenance: `ContextAssembler.attributionNote`
presents the public principal as anonymous, never as the owner. Collapsing the
two would lose the accountability the venue exists to provide — "who caused
this" is exactly the question a system of record must answer.

## Admission, per class

- **Owner-initiated** — the owner addresses their agent as themselves.

- **Public / anonymous** — admitted by the agent's `a2a.public` flag together
  with an explicit **`a2a.caps`**: the grant scope the non-owner's *request* is
  dispatched under. It must cover `agent/request` on the agent (and
  `agent/message` for a `taskId` continuation) or the send is refused. It
  governs the request, not the run — the run is the agent's, under
  `config.caps`, like every run — so a grant in `a2a.caps` beyond the talk
  abilities has no effect. `a2a.caps: "unrestricted"` admits the request with
  the owner's full authority and is honoured with a warning as the owner's
  explicit choice. A public agent with no `a2a.caps` is discoverable (its card)
  but **not interactable** — the owner must deliberately admit strangers.

- **Named delegate** — admitted by a UCAN grant the owner issued naming that
  exact principal and agent (ability `agent/request`), or by the agent's native
  `config.accepts` policy. Such a run is the agent's run under `config.caps`,
  the same as for the owner and for the public, differing only in admission and
  provenance. A delegate who should see a narrower agent is given a narrower
  agent; a grant does not carry a run scope.

### Admission mechanics

- **Public flag** — `a2a.public` on the agent config admits anonymous callers to
  discovery; `a2a.caps` additionally admits them to interaction (per above).

- **Named grant** — a self-sovereign owner signs a UCAN with their own key; a
  venue-managed custodial owner may use `ucan:issue`. The grant's audience is the
  delegate; its ability is **`agent/request`** — a dedicated ability, chosen so
  that "may interact with this agent" does not leak the broader `invoke`
  authority, and so the ability namespace has room to grow (`agent/message`,
  `agent/chat`) hierarchically. The delegate presents the token; it is verified
  as any cross-user proof is (the root is the self-sovereign owner or its
  controlling venue, audience is the caller, temporal bounds hold, and the grant
  **covers** the agent resource with boundary-aware matching so a grant on
  `…/g/agent` cannot cover a sibling `…/g/agentX`).

- **Native admission policy** — `config.accepts` on the agent record
  (covia#447): the owner's standing statement of who may talk to the agent —
  the venue operator (`"venue"`: the venue principal and its agents, never
  every user hosted here) or exact principal DIDs. The native agent ops
  consult it inside the single cross-user gate, before proofs; it covers
  `agent/request` and `agent/message` only. A2A does not consult it yet — its
  non-owner path remains the public lever above.

All three levers are pure **admission**; none changes the execution identity
(the owner's agent, under its `config.caps`) — they only decide whether a run
happens, and record who asked.

## Addressing a task afterwards

The task a non-owner creates through a per-agent endpoint is the owner's job.
The per-agent by-id methods (`GetTask`, `CancelTask`, `SubscribeToTask`, a
`SendMessage` with a `taskId`) let that caller — and only that caller, by the
recorded `actor` — read, cancel, follow and continue it. The owner's other
jobs, and other callers' tasks, are "not found" there in one wording; the owner
addresses every job of theirs on the front door.

## Lifecycle and boundaries

- **Revocation / expiry** — grants are UCAN tokens bounded by expiry. Admission
  is decided at interaction time; a run already admitted is not retroactively
  killed if the grant later expires (the task was validly created). Venue-side
  revocation of live grants is a later capability (C2).

- **Within-venue only** — because the C1 authority model requires the grant's
  issuer to be this venue, delegation is within-venue. Owner-signed
  (self-sovereign) and cross-venue grants are C3. The audience may be any DID, so
  the *delegate* can live elsewhere; the *grant* is minted here.

- **No sub-delegation** — a delegate re-granting to a third party (chain walking,
  attenuation) is C2 and explicitly out of scope here.

## Out of scope for this iteration

- Sub-delegation / delegation chains (C2).
- Cross-user addressing for the REST/MCP agent ops (A2A carries the owner in its
  path; the native ops take a bare agent id).
