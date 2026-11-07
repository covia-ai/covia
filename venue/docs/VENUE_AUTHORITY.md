# Venue Authority (design)

How a principal's authority is raised above what it starts with: standing
allowances that its holder activates for one request, and the venue's power
over the users it hosts. This is a design; none of it is implemented yet.

Tracked by covia#660.

## Terminology

| Term | Meaning |
|------|---------|
| Base | The authority a context starts with: a user's own authority; for an agent with no caps, its owner's; for an agent with caps, exactly those caps; for the venue, its own resources |
| Addition | Authority added to the base for a request: a grant it presents, or an allowance it activates |
| Allowance | A standing right, kept by the venue as a record separate from grants, that confers nothing until its holder activates it |
| Activation | An explicit request to add an allowance's privileges to a context for one request or job and everything it starts |
| Own right | What a principal may do from its base, plus the venue's dormant power over its users. A grant or allowance received from someone else is not own right |

## How it works today

- **Base and additions.** `Engine.authorityCovers` passes a context with no
  capability scope over its own namespaces, and otherwise passes when a
  presented grant or the scope covers the request. Another identity's resource
  that the venue hosts passes only on a presented grant, admission or the
  public scope, whatever the scope. Choosing the base replaces
  the owner's authority with the caps; nothing intersects. An agent is a
  sub-principal of its owner and works in the owner's namespace
  ([UCAN.md](./UCAN.md) §5.4).
- **Venue authority.** `Engine.hasVenueAuthority` admits the venue acting as
  itself (`isDirectVenue`, which excludes the venue's agents), or a presented
  grant covering `<venueDID>/<resource>`. The scope is not read.
- **The venue context.** `Engine.venueContext()` is the venue as caller. About
  two dozen call sites in this repository use it for the venue's own secrets,
  its own records and start-up work. It reaches a user's namespace only as any
  other caller does, through grants or an agent's admission policy
  (`Engine.crossUserAllows`). Acting as a user (`Engine.sudoContext`) needs
  `user/sudo` on that user, rooted by the user or by the venue, which roots
  grants over every identity it hosts, self-sovereign users included, valid
  only here (`Engine.rootAuthorityPolicy`, [UCAN.md](./UCAN.md) §5.6). Managing
  accounts, such as users' sign-in keys, is done on venue resources.
- **Issuing.** `ucan:issue` signs grants over the identities the venue hosts,
  for a caller that is root of the resource (its own, an identity's it signs
  in as, its agent's) and whose scope includes the right to grant it, or that
  presents a `grant/<can>` right; it accepts any expiry or none
  ([UCAN.md](./UCAN.md) §4.1). An agent's caps are conferred by the same
  check. A caller with a held key signs as that key instead.
- **Deferred work.** A recorded job (`JobManager.keepCallerContext`) and a
  scheduled event (`Scheduler.schedule`) keep the grants presented to them but
  not the scope. Agents are unaffected, because their scope is read again from
  their config.
- **Revocation.** Nothing revokes a grant; only sign-in keys can be revoked
  (`AuthState`).

Authority is raised above a base today by signing grants:

| Holder | Where | How |
|--------|-------|-----|
| Embedder code, operator modules | In the process | `engine.venueContext()`, over the venue's own resources |
| Brightside's gods (bridges, treasury) | In the process, as venue agents | A 300-second grant per call, signed and verified in the process (`VenueGrants`): for an operation a god runs, for a user asking a god, and for acting as a user |
| GetMine administrators | Outside | A one-hour grant over users, sign-in keys, adapters, restart and store collection, issued on demand (`VenueGrantApi`) and presented over MCP or `X-Covia-Ucans` |
| Provisioning services, standing admin identities | Outside | A grant from `ucan:issue`, as deploy/README.md and CONFIG.md describe |

## Design

### Base and additions

A context's authority is its base plus its additions. A check passes when the
base or any addition covers the request, and stops at the first that does.
Nothing computes an intersection. The base is as defined above; the venue's
base is its own resources (`<venueDID>/…`).

### Allowances

An allowance is a record the venue keeps, apart from grants. It holds:

- its holder: the exact principal that may activate it, which for an agent is
  the agent and not its owner;
- its ceiling: the most it can unlock, written as capabilities;
- its gate, if any: an operation that must pass when the allowance is
  activated;
- its expiry;
- its issuer.

Allowances are indexed by holder. They never enter a context unless
activated, and ordinary checks never read them, so a principal may hold many
without cost.

### Issuing an allowance

- The issuer must hold everything the ceiling covers in its own right. Grants
  it received and allowances it activated do not count, so an allowance can
  never be passed on and there are no chains of allowances.
- Issuing is an action the issuer's base must cover: an agent with caps can
  issue only if a cap allows it.
- An agent with no caps has its owner's authority as its base, so it can issue
  allowances over its owner's namespace. An agent with caps can issue only
  within them.
- The same base rule applies to issuing grants: `ucan:issue` must check the
  issuer's caps for its own namespace too.

### Activation

A request or job asks to activate either an allowance by its identifier, or a
privilege, in which case the venue searches that holder's allowances for one
whose ceiling covers it. Nothing is activated unless the request asks.

The venue activates only when all of these hold, and refuses otherwise:

1. The allowance has not expired or been revoked.
2. The requester is its holder.
3. The requester's base covers activating it. An agent whose caps do not allow
   activation can never raise its own authority; an agent with no caps can
   activate any allowance issued to it.
4. The privileges asked for lie within the ceiling.
5. Its gate, if any, passes. The gate runs once, at activation, never on later
   checks.

An activation adds the privileges to the context for that request and
everything it starts, and ends with it. The requester remains the actor, so a
job's `caller` is the principal that asked. The job records which allowance
supplied each privilege. Activated privileges can be used but never granted
onward: they are not own right, so they cannot be used to issue a grant or an
allowance.

### Deferred work

Work deferred to later, a scheduled event or a job resumed after a restart,
stores the activation request, not the activation. It activates again when it
runs, gate included, so a revocation takes effect then. Asked by identifier,
the revoked allowance stops the work; asked by privilege, the work continues if
another allowance its holder holds still covers it.

### The venue

- **Base.** The venue's own resources.
- **Power over its users.** The venue can do anything to what it hosts,
  because its operator ultimately controls it. That power covers every user it
  hosts, self-sovereign users included. It is the venue's own right but
  dormant: it needs no record, because nobody issues it, and it is used only
  through activation, which is recorded like any other.
- **Passing it on.** Once activated, the venue can issue grants and
  allowances over its users' resources, because the power is its own. For
  example, it can give a Brightside god an allowance over one folder of one
  user's workspace.
- **Signing.** The venue signs as an owner's custodian only for identities
  whose keys it holds, and never authenticates as a self-sovereign user. COG-17
  names its power over what it hosts as the host root, which holds only at
  this venue, so that power never reaches another venue.
- **Change from today.** Trusted code that signs grants with the venue's key
  reaches every user the venue hosts today, self-sovereign users included,
  with no record of each use. Under this design that power is used only
  through activation.

### Trusted code

Java code in the venue process can reach the `Engine` and is trusted: nothing
here contains it. What this design bounds is how far untrusted principals
(agents, orchestrations, operation assets, remote callers) can steer that code.
Elevation reaches them only as an allowance they hold, activated explicitly,
within its ceiling.

Under it, Brightside's gods hold allowances and Brightside activates the
privileges each call needs, instead of signing grants to itself. GetMine and
provisioning services hold allowances the venue issued.

## Precedents

- **Held but disabled.** Windows tokens carry privileges disabled until code
  enables them, and administrators run with an ordinary token until they
  elevate. Linux holds capabilities as permitted until they are raised to
  effective.
- **Eligible, then activated.** Microsoft Entra Privileged Identity Management
  makes roles eligible until their holder activates them, optionally with
  approval; Google Cloud's Privileged Access Manager calls these entitlements.
- **No escalation by granting.** Kubernetes refuses to create or bind a role
  granting more than its author holds, unless the author holds the separate
  right to escalate or bind.
- **One actor.** Unix keeps the effective uid apart from the real uid, and
  Postgres `current_user` apart from `session_user`. The one widely used design
  that let a service hold a requester's full authority beside its own,
  Kerberos unconstrained delegation, is a standard escalation path.

## Security

- **Dormant by default.** A holder's ordinary calls do not carry its
  allowances, nor do its agents' tool calls or any code acting under its
  identity. Only a request that asks gets them.
- **Bound to the holder.** An allowance's identifier is not a secret: only its
  holder can activate it, and the holder must authenticate.
- **Agents that cannot elevate.** An agent whose caps do not allow activation
  cannot raise its own authority, whatever allowances exist for it.
- **No laundering.** Activated privileges cannot be turned into grants that
  outlive the request.
- **Confused deputy.** Trusted code that works out which privileges a call
  needs from the call's input is still bounded by the allowance's ceiling, and
  must check anything the requester names, such as a user to act for, against
  the requester.
- **Gates run once.** An allowance's gate runs at activation, so a long list
  of allowances adds no cost to ordinary checks.

## Open questions

1. How a request asks for activation over the Java API, REST, MCP and A2A.
2. The names of the abilities to activate and to issue an allowance, and the
   resource each names.
3. What the gate receives, and whether its decision may depend on the
   operation and input the request will run.
4. Where allowances are stored, how they are listed, who may revoke one
   (issuer, holder, venue), and whether revoking ends activations in progress.
5. Whether an allowance may have no expiry, as a grant may (COG-17).
6. Deferred work keeps presented grants but not the base. Agents are safe
   because their caps are read again; any other context with a scope would
   lose it.
7. Moving Brightside, GetMine and provisioning services to allowances, and
   what becomes of the grants they hold or sign.
8. Whether to refuse grants the venue signs over its own resources once
   allowances replace them, as covia#660 first proposed. Refusing every token
   bearing the venue's signature would also refuse its custodial users'
   grants, which the venue signs for them.
9. Revoking tokens: a token's base64url signature can be spelt several ways,
   so revocation needs an identifier from its signed content, and Convex's
   validator needs a way for Covia to reject a token at any link of a chain.
10. A limit on the lifetime of grants the venue issues; `ucan:issue` accepts
    no expiry today.
11. Whether `*` covers granting rights (`grant/…`).

## Rejected alternatives

- **Lending the venue's authority into the requester's context**: it mixes
  two principals' authority in one context, so it needed rules about how far a
  lend may reach.
- **Grants the venue holds, presented by identifier**: they were ordinary
  grants, passed on along chains and presented like tokens. An allowance is a
  separate record that cannot be passed on, and activating one is an action
  its holder's base must allow.
- **Declaring elevations in venue config**: elevations are standing allowances
  that principals hold and ask for, not operator settings.
- **Dormancy as a caveat on ordinary grants**: a principal may hold many
  allowances; as grants they would travel in contexts, and their gates would
  run on every check instead of at activation.
- **Searching a holder's allowances whenever other checks fail**: allowances
  would always be on, which makes them ordinary grants with a lookup.
- **Acting as the venue during an elevation**: the actor and the principal
  that asked would differ. The requester acting keeps one actor per context.
- **Caps as an upper bound on activation**: it brings intersections into
  every check. Caps choose the base, and activating is an action they must
  allow.
- **The venue's custody of its users' keys as part of its base**: the venue's
  power over its users is dormant and used only through activation.
