# Venue Authority (design)

How the venue lends its authority to code in its own process, and how it
carries out COG-17's rules for the grants it signs, verifies and revokes.
This is a design; none of it is implemented yet.

Tracked by covia#660.

## Principle

The venue is the authority for everything it hosts. It records every grant it
signs, holds the revocations for its resources, and lends its own authority to
code in its process without signing anything.

A signature carries authority across a trust boundary, to a verifier who
cannot see the issuer's state. Inside the process there is no boundary: code
holding the `Engine` already holds the venue's key and its full authority
(`Engine.venueContext()`). Outside it, a grant is a token, and under COG-17 its
grantor stays in control: any grant can be revoked by a principal in its line
of authority, and its expiry is the grantor's choice within the venue's
policy. Grants over a resource are checked by the venue hosting it, so that
venue holds the revocations.

The venue's own resources (everything under `<venueDID>/`: users, adapters,
process, store, outbound reach, the resources of venue-owned adapters and the
venue's own namespaces) need no rule of their own. The venue is a grantor like
any other.

## How it works today

- `Engine.hasVenueAuthority` admits the venue acting as itself
  (`isDirectVenue`, which excludes the venue's agents), or a presented grant
  covering `<venueDID>/<resource>` (`crossUserAllows`). A null capability scope
  is deliberately not enough, and the scope is not read.
- `ucan:issue` is the only place Covia signs a grant: HITL approvals issue
  through it too. It signs grants over the venue's own resources when the
  venue asks, or a caller holding a granting right over them, to any audience,
  with any expiry or none ([UCAN.md](./UCAN.md) §4.1). It logs each issue as
  an audit event naming who asked, the audience and the expiry.
- A grant is bound to its audience: whoever presents it must also
  authenticate as its `aud`, so a copied token confers nothing. Nothing can
  revoke a grant; only sign-in keys can be revoked (`AuthState`).
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

## Design

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
  (`Scheduler.LENT_AUTHORITY`): a lend covers one call, and a scheduled event
  outlives it.
- **Not the capability scope.** The scope limits an agent within its owner's
  namespace, and an agent's `config.caps` is lattice data its owner writes.
  Reading it for venue authority would make any venue agent with a wide scope
  an administrator.

Brightside's `VenueGrants` becomes a call to this method.

### Token identifiers

COG-17 identifies a token by the SHA3-256 hash of its JWT. The links above a
token in its chain travel as JWTs in its `prf`, so their identifiers can be
computed whenever it is checked. The presented token's own JWT is gone once
parsed, and a job's kept grants are parsed maps, so the venue computes that
identifier on arrival and keeps it with the parsed token.

### Issuance records

`ucan:issue` records each grant it signs, and so covers HITL approvals: the
token's identifier, the principal that asked, the audience, the capabilities,
the expiry, the granting right it was minted under and that right's issuers,
and when. It returns the identifier with the token, so whoever asked can
revoke it later without holding it.

`ucan:issued {aud?}` lists live records. The venue sees all of them. Any other
caller sees the records it asked for, and those granting a resource it owns:
an owner holds the root authority over its resources, and COG-17 audits
delegation rather than preventing it. A record goes once its token has
expired.

### Grants the venue never recorded

`crossUserAllows` refuses a chain containing a grant the venue signed but has
no issuance record for (`Engine.UNRECORDED_VENUE_GRANT`), as COG-17 requires:
authority is minted only at granting surfaces. It is checked at each link, by
the same hook as revocation. A copy of the venue key then
cannot mint authority the venue honours. Identity tokens, which grant
nothing, are not affected.

### Revocation

`ucan:revoke {token}` or `ucan:revoke {id}` revokes a token. The caller must be
in its line of authority (`UCANAdapter.REVOKE_NOT_ENTITLED`): an issuer of the
token or of a token above it in its chain; for a token this venue signed,
whoever asked for it; and for a token minted under a granting right, an issuer
in that right's chain, read from the issuance record. An `id` must name a
token this venue signed (`UCANAdapter.REVOKE_UNKNOWN_ID`). Revoking a granting
right also revokes the tokens minted under it.

- **Checking.** A chain through a revoked token is refused at the link it
  passes through. Convex's validator works on capability paths and leaves
  revocation to its callers by design; it gains a hook that lets the caller
  reject a token at any link, and Covia supplies the test. Every point of
  action checks afresh, so a revocation reaches running work at its next
  check, `outbound/any` included.
- **Retention.** A revocation is kept until its token's expiry, read from the
  token or its issuance record; one for a token with no expiry is kept.

### Storage

Revocations and issuance records are core state in the venue's auth state,
beside sign-in keys; the operations are on the `ucan` adapter, the granting
surface. Both stores only grow until their entries expire, and an entry never
changes once written, so merging two copies is their union and dropping an
expired entry is always safe.

### Grant lifetime

`auth.maxGrantLifetime` (seconds) bounds every grant the venue signs, as
`auth.maxTokenLifetime` bounds bearers. It is absent by default, so issuing
works as it does today. Set, a later or null `exp` is refused with a reason
naming the limit (`UCANAdapter.EXP_EXCEEDS_LIMIT`), never shortened. An
omitted `exp` still means no expiry. HITL issues through `ucan:issue`, so the
limit covers it; `adapters.hitl.maxGrantLifetimeSecs` stays as a tighter bound
for HITL alone.

### The venue's own resources

Grants over them are recorded, bounded and revoked like any other. GetMine's
administrators, the documented provisioning path and standing admin
identities keep working, and the operator can list and revoke what they hold.

## Upgrade

Refusing unrecorded grants takes effect at one release. Every venue-signed
grant issued before it has no record and stops working then, HITL approvals
included: non-expiring service grants must be issued again, while GetMine's
are issued hourly and need nothing. Brightside moves to lending first. The
CHANGELOG and upgrade notes say so.

## Staging

1. Lending, and Brightside's move to it.
2. The Convex hook for rejecting a token at a link.
3. Token identifiers, issuance records, revocation and the lifetime limit.
4. Refusing unrecorded grants, at a release.
5. Docs: [UCAN.md](./UCAN.md) §4.1, §4.4 and §4.6, CONFIG.md, EMBEDDING.md
   (lending) and the `ucan` skill.

## Considerations

- **Revocation is local.** A token signed here and honoured at another venue
  under its trust policy stays good there until it expires, and that venue
  cannot see this one's records. Grants meant for other venues should be
  short-lived. Having verifiers ask the issuer instead would end COG-17's rule
  that a grant is checked without calling its grantor.
- **Grants with no expiry.** COG-17 lets a venue refuse presented grants that
  have none. Nothing here does.
- **Agents as grantees.** What an agent reads can steer it. A long-lived
  grant to an agent is standing authority for whatever steers it; a lend for
  one call is the narrower tool.
- **The venue's `did:web` alias.** Resources and principals under the alias
  are the venue's, as `Engine.isVenuePrincipal` treats them.

## Rejected alternatives

- **Refusing tokens over the venue's own resources with no replacement**, as
  covia#660 first proposed: it breaks GetMine's administrators and the
  documented provisioning path, and once tokens can be revoked and bounded it
  gains nothing.
- **Standing grants**, records the venue checks in place of tokens over its
  own resources: once tokens can be revoked, a recorded grant and a token held
  by a compromised principal carry the same risk, so a second mechanism adds
  operations and a check but no control. They may still suit the users the
  venue issued (#449, #450).
- **Covia dropping revoked tokens itself** before calling the validator: the
  links above a presented token sit inside signed parents, so Covia could only
  drop a whole parent, losing its other valid paths.
- **Records held by a new adapter**: disabling it would quietly make revoked
  tokens good again, and the core check would read adapter state.
- **The capability scope as the lend's carrier**: see Lending.
