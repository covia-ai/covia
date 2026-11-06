# Venue Authority (design)

How the venue lends its authority to code in its own process, and how it
carries out COG-17's expiry and revocation rules for the grants it issues and
verifies. This is a proposal; the decisions it must settle are at the end.

Tracked by covia#660.

## Principle

A signature carries authority across a trust boundary, to a verifier who
cannot see the issuer's state. Two things follow:

- **Inside the process there is no boundary.** Code holding the `Engine`
  already holds the venue's key and its full authority
  (`Engine.venueContext()`). It lends part of that authority directly, without
  signing anything.
- **Outside the process a grant is a token, and its grantor stays in control
  of it.** Under COG-17 a grantor can revoke any grant it made, and its expiry
  is the grantor's choice within the venue's policy. Grants over a resource
  are checked by the venue hosting it, so that venue holds the revocations.

The venue's own resources (everything under `<venueDID>/`: users, adapters,
process, store, outbound reach, the resources of venue-owned adapters and the
venue's own namespaces) need no rule of their own. The venue is a grantor like
any other, and its grants can be revoked like any other's.

## How it works today

- `Engine.hasVenueAuthority` admits the venue acting as itself
  (`isDirectVenue`, which excludes the venue's agents), or a presented grant
  covering `<venueDID>/<resource>` (`crossUserAllows`). A null capability scope
  is deliberately not enough, and the scope is not read.
- `ucan:issue` signs grants over the venue's own resources when the venue
  asks, or a caller holding a granting right over them, to any audience, with
  any expiry or none ([UCAN.md](./UCAN.md) §4.1). It records each issue as an
  audit event: who asked, the audience and the expiry.
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

### Token identifiers

COG-17 identifies a token by the SHA3-256 hash of its JWT. The links above a
token in its chain travel as JWTs in its `prf`, so their identifiers can be
computed whenever it is checked. The presented token's own JWT is gone once
parsed, and a job's kept grants are parsed maps, so the venue computes that
identifier on arrival and keeps it with the parsed token.

### Issuance records

`ucan:issue` records each token it signs: its identifier, the principal that
asked, the audience, the capabilities, the expiry, the granting right it was
minted under (if any) and when. COG-17 requires the first three; the rest lets
a token be revoked by identifier, and revoked with the right it was minted
under.

- `ucan:issued {aud?}` lists live records. The venue sees every record; any
  other caller sees those it asked for.
- A record goes once its token has expired.

### Revocation

`ucan:revoke {token}` or `ucan:revoke {id}` revokes a token, with COG-17's
entitlement rule: the caller issued the token or one above it in its chain,
or, for a token this venue signed, asked for it or is the venue
(`UCANAdapter.REVOKE_NOT_ENTITLED`). An `id` must name a token this venue
issued (`UCANAdapter.REVOKE_UNKNOWN_ID`). Revoking a granting right also
revokes the tokens issuance records show were minted under it.

- **Checking.** Before a chain is checked, `crossUserAllows` drops each
  presented token that is revoked or has a revoked token in its chain. Every
  point of action checks afresh, so a revocation reaches running work at its
  next check, `outbound/any` included.
- **Retention.** A revocation is kept until its token's expiry, read from the
  token or its issuance record; one for a token with no expiry is kept.

### Grant lifetime

`adapters.ucan.maxGrantLifetimeSecs` bounds what `ucan:issue` signs, as
`adapters.hitl.maxGrantLifetimeSecs` bounds grants made on HITL approval. It
is absent by default, so issuing works as it does today. Set, a later or null
`exp` is refused with a reason naming the limit
(`UCANAdapter.EXP_EXCEEDS_LIMIT`), never shortened. An omitted `exp` still
means no expiry.

### The venue's own resources

Tokens over them are issued, bounded and revoked like any other. GetMine's
administrators, the documented provisioning path and standing admin
identities keep working, and the operator can now list and revoke what they
hold. covia#660's proposals to refuse such tokens from outside and to stop
issuing them are dropped.

## Staging

1. Lending, and Brightside's move to it.
2. Token identifiers, issuance records, revocation and the lifetime limit.
   Nothing that exists changes.
3. Docs: [UCAN.md](./UCAN.md) §4.1, §4.4 and §4.6, CONFIG.md, EMBEDDING.md
   (lending) and the `ucan` skill.

## Considerations

- **Revocation is local.** A token signed here and honoured at another venue
  under its trust policy stays good there until revoked there. This design
  keeps revocations at the venue that accepts them and sends none elsewhere.
- **Tokens the venue never saw.** Revocation reaches only tokens someone can
  name. One signed with the venue key outside `ucan:issue`, Brightside's
  today or one made with a copied key, has no issuance record (decision 3).
- **Minted tokens.** A token minted under a granting right can be revoked by
  the venue that signed it or the steward who asked for it. The resource's
  owner can end it only by revoking the right, which ends every token minted
  under it.
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
- **The capability scope as the lend's carrier**: see Lending.

## Decisions

In order; each shapes the next.

1. **Placement.** Revocations and issuance records in the venue's auth state
   beside sign-in keys, with the operations on the `ucan` adapter, the
   granting surface (proposed); or a new adapter owning both. Under a new
   adapter, disabling it would quietly make revoked tokens good again, and the
   core check would read adapter state, which no core check does today.
2. **Dropping revoked tokens.** Convex's validator works on capability paths
   and leaves revocation to its callers by design. Either Covia drops revoked
   tokens before calling it (proposed above), or Convex gains a hook that lets
   a caller reject a token in a chain.
3. **Unrecorded venue-signed tokens.** Whether the venue refuses a token it
   signed but has no issuance record for. That closes the gap above, but also
   refuses every venue-signed token issued before records existed.
4. **Who sees issuance records.** The venue all, and each principal those it
   asked for (proposed), or the venue only.
5. **Scheduling under a lend.** Refused (proposed), or the event fires without
   the lend and fails at its first check.
