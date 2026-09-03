# A2A Interaction Authority (design)

The crux of per-agent A2A exposure: when a **non-owner** interacts with an
agent, **under whose authority** does the agent run, and **bounded by what**?
This is the authority model that [A2A_AGENTS.md](./A2A_AGENTS.md) §"Resolution and
authorisation" defers to. It is a proposal under review — the decisions it must
settle are listed at the end.

For the agent model see [AGENT_SESSIONS.md](./AGENT_SESSIONS.md); for the
capability primitives see the venue access-control design and COG-13.

## Principle: an agent always runs as its owner

An agent is the owner's. Its config, its workspace memory, its secrets, and any
credits or downstream authority it spends are the **owner's**. It follows that an
agent **executes under its owner's identity — always, for every caller**. A
caller's identity never becomes the execution identity.

This settles the shape of the whole problem. The question is never "run the agent
*as Bob*" (Bob has no standing in the owner's namespace, and an agent cut off from
its owner's memory and secrets cannot function). The question is only:

> May this caller cause a run, and **how much of the owner's authority** may the
> resulting run wield?

## Two separable questions

The current prose conflates two orthogonal things:

1. **Admission** — *who* may cause the agent to run at all.
2. **Execution authority** — once running, *how much* of the owner's authority
   the run may exercise.

Admission is about the knock at the door; execution authority is about how much
power the resulting run holds. Keeping them separate is what makes the model
tractable.

## Three layers of authority

Any run — owner-initiated or not — is bounded by the intersection of:

- **Layer 0 — the agent's intrinsic capability.** The agent's own config `caps`
  (the `CapabilityChecker` gate on its tool calls) bounds what the agent may
  *ever* do, independent of any caller — including the owner's own interactions.
  This is the owner's standing statement: "this is what my agent is allowed to
  do." It is the **absolute bound** on every run.

- **Layer 1 — admission.** Owner (always); anonymous/public (via the agent's
  public flag); a named delegate (via a UCAN grant). Denials are leak-shaped —
  an anonymous caller gets *not-found*, an authenticated caller without standing
  gets *forbidden* — so the address space is not a disclosure oracle.

- **Layer 2 — the interaction scope.** A *further* narrowing applied to the
  owner-identity run when the initiator is **not** the owner, so that an outside
  party can never drive the agent at the owner's unrestricted authority even
  within Layer 0. This is the new concept this document pins down.

Effective authority of a run = Layer 0 ∩ Layer 2, executed under the owner's
identity, admitted by Layer 1.

## Identity for authority vs identity for provenance

For a non-owner interaction these **diverge**, and both must be recorded:

- **Authority identity** = the **owner** (what the run may touch).
- **Initiator identity** = the **caller** — the delegate's DID, or the venue
  public DID for an anonymous caller (who *caused* the run).

The audit trail must carry both. A task/session created by a non-owner records
the owner as the authority under which it ran *and* the initiator on whose behalf
it was admitted. Collapsing the two (recording only the owner as "caller") loses
the accountability the venue exists to provide — "who caused this" is exactly the
question a system of record must answer. This is a required field, not a nicety.

## The interaction scope, per admission class

Layer 2 is the open design point. Its value depends only on **how the caller was
admitted** (Layer 1), never on the caller's own ambient authority:

- **Owner-initiated** — no Layer 2 narrowing. The owner runs their own agent at
  Layer 0.

- **Public / anonymous** — bounded by the agent's configured **`a2a.caps`**,
  the *public interaction scope*. This is a required, owner-declared bound: a
  public agent with no `a2a.caps` is discoverable (its card) but **not
  interactable** — the owner must deliberately bound what an anonymous run may
  do. `a2a.caps: "unrestricted"` is honoured (with a warning) as the owner's
  explicit choice. *(This ratifies the behaviour already built for the public
  lever.)*

- **Named delegate** — admitted by a UCAN grant the owner issued naming that
  exact principal and agent. **Recommended:** such a run is bounded only by
  Layer 0 (the agent's own configured capability) — i.e. a named delegate runs
  the agent at its full configured authority, the same as the owner, differing
  only in admission and provenance. The rationale mirrors the public
  "unrestricted is honoured because the owner chose it" rule, one step stronger:
  issuing a capability that names a specific principal *and* a specific agent is
  a deliberate, higher-bar trust decision than flipping a public flag, so it
  warrants at least the authority the public scope could grant. The owner may
  optionally attach a **narrower** scope to a specific grant to restrict that
  delegate further (narrowing-only — a grant can never widen beyond Layer 0).

The invariant this produces:

> `a2a.caps` is the **public** interaction scope — not "all non-owner". Layer 0
> (the agent's own config caps) is the absolute bound for **every** run. No
> interaction, by anyone other than the owner, can ever cause the agent to exceed
> Layer 0.

The trade-off to weigh: a uniform "`a2a.caps` bounds *every* non-owner run,
delegates included" is simpler to reason about, but it makes a named grant unable
to express more trust than the anonymous public gets — which defeats the point of
naming a delegate. The recommendation above keeps the two admission classes with
distinct scopes; the alternative collapses them. This is the decision to ratify.

## Admission mechanics (logical)

- **Public flag** — `a2a.public` on the agent config admits anonymous callers to
  discovery; `a2a.caps` additionally admits them to interaction (per above).

- **Named grant** — a self-sovereign owner signs a UCAN with their own key; a
  venue-managed custodial owner may use `ucan:issue`. The grant's audience is the
  delegate; its ability is **`agent/request`** — a dedicated ability, chosen so
  that "may interact with this agent" does not leak the broader `invoke`
  authority, and so the ability namespace has room to grow (`agent/message`,
  `agent/chat`) hierarchically. The delegate presents the token; it is verified
  as any cross-user proof is (the root is the self-sovereign owner or its
  controlling venue, audience is the caller, temporal bounds hold, and the grant **covers** the agent
  resource with boundary-aware matching so a grant on `…/g/agent` cannot cover a
  sibling `…/g/agentX`).

- **Native admission policy** — `config.accepts` on the agent record
  (covia#447): the owner's standing statement of who may talk to the agent —
  the venue operator (`"venue"`: the venue principal and its agents, never
  every user hosted here) or exact principal DIDs. The native agent ops
  consult it inside the single cross-user gate, before proofs; it covers
  `agent/request` and `agent/message` only. A2A does not consult it yet — its
  non-owner path remains the public lever above.

All three levers are pure **admission**; none changes the execution identity (still
the owner) — they only decide whether a run happens and, for the public lever,
which scope applies.

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
- A delegate polling the resulting task via `GetTask` (the task is owned by the
  agent owner) — a follow-up once the initiator-provenance field exists to
  scope it.
- Task continuations (an inbound `taskId`).
- Cross-user addressing for the REST/MCP agent ops (A2A carries the owner in its
  path; the native ops take a bare agent id).

## Decisions to ratify

1. **Named-delegate scope** — Layer-0-only by default (recommended), vs a
   uniform `a2a.caps` for all non-owner runs, vs a separate owner-configured
   "delegated scope". This is the central call.
2. **Per-grant narrowing** — support an optional narrower scope attached to a
   specific grant now, or defer to C2.
3. **Initiator provenance** — the field/shape recording the initiator alongside
   the owner authority on a non-owner task/session (name, and whether it also
   captures the admitting grant id for audit).
