# Venue Authority (design)

How authority over the venue's own resources is held: lent to code inside the
process for one call, or granted to a principal outside it as a record the
venue keeps. This is a proposal; the decisions it must settle are at the end.

Tracked by covia#660.

The venue's own resources are everything under `<venueDID>/`: its users
(`users`), adapters (`adapters`), process (`process`), store (`store`) and
outbound reach (`outbound`), the resources of venue-owned adapters (a
treasury, the venue's shared MCP targets at `v/mcp`) and the venue's own namespaces (`w`, `g`, `j`,
`o`, `h`). Resources of the users the venue issued (`<venueDID>:u:<name>`)
are out of scope: #449 and #450 design those, and the same shape should serve
them.

## Principle

A signature carries authority across a trust boundary, to a verifier who
cannot see the issuer's state. For the venue's own resources the venue is both
the issuer and the only verifier: nobody else ever checks a grant over
`<venueDID>/users`. So:

- **Inside the process there is no boundary.** Code holding the `Engine`
  already holds the venue's key and its full authority
  (`Engine.venueContext()`). It lends part of that authority directly, without
  signing anything.
- **Outside the process the venue can still see its own state.** It records
  who may do what to its resources and checks that record. A token adds
  nothing it needs.

COG-17 points the same way: issued tokens are not revocable, and long-lived
authority "SHOULD be modelled as a standing relationship at the granting
surface". The venue already consults standing policy without tokens: an
agent's `config.accepts` (#447) and the public scope (`auth.public.caps`).

## How it works today

- `Engine.hasVenueAuthority` admits the venue acting as itself
  (`isDirectVenue`, which excludes the venue's agents), or a presented grant
  covering `<venueDID>/<resource>` (`crossUserAllows`). A null capability scope
  is deliberately not enough, and the scope is not read.
- `ucan:issue` signs grants over the venue's own resources when the venue
  asks, or a caller holding a granting right over them, to any audience, with
  any expiry or none ([UCAN.md](./UCAN.md) §4.1).
- A grant is bound to its audience: whoever presents it must also
  authenticate as its `aud`, so a copied token confers nothing. But a grant
  issued without `exp` never ends, and no grant can be withdrawn: Covia has no
  revocation of grants, only of sign-in keys (`AuthState`).
- Grants reach a request as parsed maps (`RequestContext.withProofs`), trusted
  because the transport verified them on arrival. Code in the process is
  trusted, so a grant it attaches is not checked again, and signing one in the
  process proves nothing that attaching it directly would not.
- A recorded job keeps the grants it was given in its entry in the venue's
  job index (`JobManager.keepCallerContext`), and recovery presents them again
  (`JobManager.callerContext`). That relies on only the venue writing those
  entries; no route reads or writes them.

| Holder | Where | How |
|--------|-------|-----|
| Embedder code, operator modules | In the process | `engine.venueContext()`: full authority |
| Brightside's gods (bridges, treasury) | In the process, as venue agents | A 300-second grant per call, signed and verified in the process (`VenueGrants`) |
| GetMine administrators | Outside | A one-hour grant over users, sign-in keys, adapters, restart and store collection, plus reads of the venue's namespaces but `s` on named non-production hosts, issued on demand (`VenueGrantApi`) and presented over MCP or `X-Covia-Ucans` |
| Provisioning services, standing admin identities | Outside | A grant from `ucan:issue`, as deploy/README.md and CONFIG.md describe |

Nothing in Covia itself signs a grant over the venue's own resources except
`ucan:issue`.

## Proposal

### Lending, inside the process

`Engine.lendVenueAuthority(RequestContext ctx, AVector<ACell> att)` returns
`ctx` carrying `att`: capabilities over the venue's own resources, written as
a grant's are. A lend is unsigned. Only Java code holding the `Engine` can
make one; nothing in a request or the lattice constructs it.

- **Checking.** `crossUserAllows` honours a lend for a resource under the
  venue's DID, so `hasVenueAuthority` and every local access check see it.
  The actor is unchanged, and the job record says who acted.
- **Refusals.** A capability outside the venue's own resources is refused
  (`Engine.LEND_NOT_VENUE_RESOURCE`): authority over a user's resources is
  that user's to grant. A lend to the public principal is refused
  (`Engine.LEND_TO_PUBLIC`).
- **Reach.** A lend travels where presented grants travel now: contexts
  derived from the request and the operations it invokes. It does not reach
  an agent's own steps, which run as the agent (`Engine.stepContext`).
- **Lifetime.** A recorded job keeps the lend in its entry beside its grants,
  and the entry goes when the job ends, so the lend survives a restart for as
  long as its job does. Scheduling work under a lend is refused
  (`Scheduler.LENT_AUTHORITY`): the lender is not there when the event fires.
- **Not the capability scope.** The scope limits an agent within its owner's
  namespace, and an agent's `config.caps` is lattice data its owner writes.
  Reading it for venue authority would make any venue agent with a wide scope
  an administrator.

Brightside's `VenueGrants` becomes a call to this method.

### Standing grants, outside the process

The venue keeps one record per grantee: `{did, att, exp, by, at}`, naming the
principal, its capabilities over the venue's own resources, an optional
expiry, who granted it and when.

- **Checking.** `crossUserAllows` honours a live grant naming the exact
  caller DID, for a resource under the venue's DID. It is read as it stands
  at each check, as an agent's caps are, so revoking or expiring a grant
  reaches running work at its next check, `outbound/any` included. Nothing is
  kept with jobs.
- **Operations** on the `venue` adapter, each requiring the venue acting as
  itself:
  - `v/ops/venue/grant {did, att, exp}` adds or replaces a principal's grant;
  - `v/ops/venue/revoke {did}` removes it;
  - `v/ops/venue/grants {did?}` lists live grants.
- **Refusals.** The public principal and the venue itself cannot be named
  (`VenueAdapter.GRANT_PRINCIPAL_REFUSED`). A capability outside the venue's
  own resources is refused (`VenueAdapter.GRANT_NOT_VENUE_RESOURCE`).
- **Lifecycle.** Erasing an account removes its grant, as it removes the
  account's scheduled events.
- **Audit.** Each grant and revocation is an audit event naming the
  principal, the change and who made it.

A grantee signs in as itself, as audience binding already requires, and
presents nothing. GetMine's `VenueGrantApi` records a one-hour grant for the
administrator instead of issuing a token. A provisioning service is granted
`user/create` once.

### Tokens over the venue's own resources

Once lending and standing grants exist and their callers have moved, a grant
over the venue's own resources is never honoured as a proof, whatever the
channel (`Engine.VENUE_PROOF_REFUSED`), and `ucan:issue` refuses to issue one
(`UCANAdapter.VENUE_RESOURCE_NOT_ISSUABLE`). Grants the venue issues over the
users it issued, and grants it passes on with `prf`, are unaffected. If
federation ever needs one venue to hold authority over another's own
resources, this rule needs an exception; no such case is known.

### Grant lifetime

`adapters.ucan.maxGrantLifetimeSecs` bounds what `ucan:issue` signs, as
`adapters.hitl.maxGrantLifetimeSecs` bounds grants made on HITL approval. A
later or null `exp` is refused with a reason naming the limit
(`UCANAdapter.EXP_EXCEEDS_LIMIT`), never shortened. The operator chooses the
limit; decision 2 settles what happens without one.

## Staging

1. Add lending, standing grants and the lifetime limit. Nothing that exists
   changes.
2. Move the callers: Brightside to lending; GetMine's `VenueGrantApi` to
   standing grants; and the docs that describe venue-issued grants
   (deploy/README.md, CONFIG.md, AUTH.md, skills/adapters) to standing grants.
3. Refuse tokens over the venue's own resources. Outstanding ones stop working
   at that release. GetMine's last an hour; a long-lived grant made for a
   service must be replaced by a standing grant before upgrading, and the
   CHANGELOG says so.

## Considerations

- **Re-delegation.** A grantee cannot pass any of a standing grant on: the
  operator grants each principal directly. COG-17 audits re-delegation rather
  than preventing it, but for the venue's own authority the restriction is the
  point.
- **Agents as grantees.** What an agent reads can steer it. A standing grant
  to an agent is standing authority for whatever steers it; a lend for one
  call is the narrower tool.
- **The venue's `did:web` alias.** Resources and principals under the alias
  are the venue's, as `Engine.isVenuePrincipal` treats them.
- **Verdicts.** COG-17 requires a chain to give the same verdict on any venue.
  Standing grants are not chains, and tokens over the venue's own resources
  stop being issued, so nothing conflicts.
- **The venue key.** Once no grant over the venue's own resources is honoured,
  the venue key cannot mint authority over them. It still roots grants over
  the users the venue issued, keys their secret stores and is the venue's
  identity to peers.

## Rejected alternatives

- **Banning outside holders with no replacement**, as covia#660 first
  proposed: it breaks GetMine's administrators and the documented provisioning
  path.
- **A register of issued tokens**, each grant carrying the id of a record the
  venue checks at use: it gives revocation and listing, but makes a token's
  verdict depend on one venue's state, against COG-17's rule that a chain
  gives the same verdict on any venue, and adds a revocation mechanism COG-17
  leaves out. A standing grant does the same with nothing to carry.
- **A revocation list**: the same objection, and it can neither list
  outstanding grants nor withdraw one issued before the list existed.
- **The capability scope as the lend's carrier**: see Lending.

## Decisions

In order; each shapes the next.

1. **The model.** Lending and standing grants replace tokens over the venue's
   own resources (proposed). The alternative keeps tokens with an enforced
   lifetime, issued on demand as COG-17 describes: nothing breaks and GetMine
   already works that way, but no grant can be withdrawn before it expires,
   nobody can see who holds venue authority, and signing in the process
   stays.
2. **Lifetime and COG-17.** COG-17 requires `exp` on every issued grant and a
   policy cap, and forbids indefinite grants. `ucan:issue` and
   `adapters.hitl.maxGrantLifetimeSecs` both allow no cap and `exp: null`
   today. Either the venue follows the COG, with a default cap the operator
   may change, or COG-17 allows the operator to set none. Separately: whether
   the same limit bounds standing grants.
3. **Placement.** (a) Records in the venue's auth state beside sign-in keys,
   with the operations on the `venue` adapter, which already administers the
   venue (proposed). (b) A new adapter owning both. Under (b), disabling the
   adapter silently removes every outside administrator's authority, and the
   core check would read adapter state, which no core check does today.
   #617 bears on (b).
4. **A grantee's agents.** Whether a grant to a principal covers that
   principal's agents. Proposed: no. A grant names one caller, just as the
   venue's own agents do not inherit the venue's authority.
5. **Who manages grants.** The venue acting as itself (proposed), or holders
   of a granting right over `<venueDID>/grants`, which is re-delegation by
   another route.
6. **Scheduling under a lend.** Refused (proposed), or the event fires without
   the lend and fails at its first check.
7. **When tokens are refused.** At a release with an upgrade note (proposed),
   or behind an operator setting first.
