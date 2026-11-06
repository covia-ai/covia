# Venue Authority (design)

How the venue lends its authority to code in its own process, and how it
carries out COG-17's rules for the grants it holds, verifies and revokes.
This is a design; none of it is implemented yet.

Tracked by covia#660.

## Principle

The venue is the authority for everything it hosts. It holds the grants over
what it hosts instead of signing them, holds the revocations for its
resources, and lends its own authority to code in its process. It signs a
grant only for a verifier elsewhere.

A signature carries authority across a trust boundary, to a verifier who
cannot see the issuer's state. Inside the process there is no boundary: code
holding the `Engine` already holds the venue's key and its full authority
(`Engine.venueContext()`). Nor is there one between the venue and a grant it
will check itself: grants over a resource are checked by the venue hosting it,
so a grant the venue issues over what it hosts is its own state, and the
holder presents the grant's identifier. Under COG-17 the grantor stays in
control: any grant can be revoked by a principal in its line of authority, and
its expiry is the grantor's choice within the venue's policy.

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

### Held grants

`ucan:issue` holds every grant over a resource this venue hosts, and signs
nothing: it returns `{id}`, and HITL approvals return the same identifier as
`grantId`. The rules for what a caller may grant are unchanged
([UCAN.md](./UCAN.md) §4.1). A grant to be verified elsewhere is still a
signed token: the venue passing on another venue's grant (`prf`), and, once
custodial attestation exists, a grant over this venue's users' resources at
another venue.

A held grant's record holds:

- its identifier: 32 bytes from `SecureRandom`, hex-encoded;
- the grantee (`aud`), the grants (`att`, absolutised) and the expiry;
- the principal that asked, and when;
- its parent, if any: the held grant it narrows, or the granting right it was
  minted under, with the identifiers of that right and of every link above it.

**Passing one on.** A grantee passes on part of a held grant through
`ucan:issue`, naming the held grant's identifier in `prf`. The venue checks
that the caller is its grantee and that each new grant narrows it, and holds
the result with the held grant as its parent. A token in `prf` remains the
venue's own passing on of another venue's grant (UCAN.md §4.1).

### Presentation

A grantee presents a held grant's identifier in the proof channel, beside
tokens (`ucans`, `X-Covia-Ucans`); an identifier has no `.`, so it cannot be
mistaken for a JWT. On arrival (`AuthMiddleware.withTransportGrants`) the
venue looks each identifier up. A live grant whose grantee is the
authenticated caller enters the request as a parsed proof
(`RequestContext.withProofs`), in the form a verified token takes, with its
parent nested as that token's proof, so `crossUserAllows` and every check
work unchanged. Convex's validator already walks parsed token maps after
ingress, so it needs no change for held grants.

A grant is live while it has not expired, it has not been revoked, and
neither has its parent nor any link the record names above it. Any other
identifier is discarded, and nothing says why (COG-17).

- **Recovery.** A recorded job keeps the identifiers it was given, not the
  resolved grants, and recovery looks them up again
  (`JobManager.callerContext`), so a grant revoked or expired meanwhile does
  not come back with the job.
- **Relays.** A grid hop (`VenueConnection`) forwards the identifiers it does
  not hold, which the target may, and drops those it holds, which mean
  nothing elsewhere.

### Grants the venue signed

On arrival, a token bearing the venue's own signature with a non-empty `att`
is discarded (`UcanJwtValidator.OWN_SIGNED_GRANT`), as COG-17 requires: the
venue holds every grant it will verify. A copy of the venue key then mints
nothing the venue honours. Identity tokens, which grant nothing, are not
affected.

### Listing

`ucan:issued {aud?}` lists live held grants. The venue sees all of them. Any
other caller sees the grants it asked for, those held for it, and those
granting a resource it owns: an owner holds the root authority over its
resources, and COG-17 audits delegation rather than preventing it. Every route
that reads a held grant applies this rule.

### Revocation

`ucan:revoke {id}` revokes a held grant, and `ucan:revoke {token}` a token
presented here. The caller must be in the grant's line of authority: whoever
asked for a held grant, the grantor of any link above it, and for a grant
minted under a granting right, an issuer in that right's chain, read from the
record or from the token's chain. Anyone else, and any identifier the venue
holds no grant under, gets the same refusal (`UCANAdapter.REVOKE_REFUSED`).

- **Held grants.** Revoking a held grant adds a revocation under its
  identifier. A grant passed on from it or minted under it stops being live,
  because liveness checks its parent and the links its record names; nothing
  else needs writing.
- **Tokens.** A token is revoked under its token identifier, the SHA3-256
  hash of its signing input (the JWT up to its second `.`), so respelling its
  base64url signature, which Java's decoder accepts in several forms, does not
  escape the revocation. A chain through a revoked token is refused at the
  link it passes through. Convex's validator works on capability paths and
  leaves revocation to its callers by design; it gains a hook that lets the
  caller reject a token at any link, and Covia supplies the test.
- **Running work.** Every point of action checks afresh, so a revocation
  reaches running work at its next check, `outbound/any` included.
- **Retention.** A revocation is kept until its grant's expiry; one for a
  grant with no expiry is kept.

### Storage

Held grants and revocations are core state in the venue's auth state, beside
sign-in keys; the operations are on the `ucan` adapter, the granting surface,
and nothing else writes them. Both stores only grow until their entries
expire. An entry never changes once written, and revoking a held grant adds a
revocation instead of removing the grant, so merging two copies is their union
and a merge cannot revive a revoked grant. An identifier is drawn once, so
two copies never disagree about an entry.

### Grant lifetime

`auth.maxGrantLifetime` (seconds) bounds every grant the venue issues, as
`auth.maxTokenLifetime` bounds bearers. It is absent by default, so issuing
works as it does today. Set, a later or null `exp` is refused with a reason
naming the limit (`UCANAdapter.EXP_EXCEEDS_LIMIT`), never shortened. An
omitted `exp` still means no expiry. HITL issues through `ucan:issue`, so the
limit covers it; `adapters.hitl.maxGrantLifetimeSecs` stays as a tighter bound
for HITL alone.

### The venue's own resources

Grants over them are held, bounded and revoked like any other. GetMine's
administrators, the documented provisioning path and standing admin
identities keep working by presenting identifiers, and the operator can list
and revoke what they hold.

## Security

- **Guessing an identifier.** It gains nothing: an identifier is honoured only
  for its grantee, who must authenticate. Identifiers are random, and every
  identifier that confers nothing gets the same answer, so no one can even
  confirm that a grant exists. An identifier derived from the record would be
  computable by anyone who can guess what the grant says and roughly when it
  was issued.
- **Identifiers are not secrets.** They appear in job records, logs, listings
  and relays. No path may treat holding one as authority: every path that
  takes one checks its grantee.
- **Answers that probe.** Presentation, listing and revocation give the same
  answer for a missing grant, an ended one and another principal's, so a
  caller learns nothing about grants it cannot see.
- **Authentication carries the weight.** As with tokens, a grant is good only
  for its authenticated grantee, so a stolen sign-in key carries that
  principal's grants. Sign-in keys and grants can each be revoked.
- **No ambient authority.** A grant applies only to the requests that present
  it, so an agent or service acting for several principals cannot be steered
  into using one principal's grant for another.
- **Writing held grants.** Only the granting surfaces write them. Whoever can
  write the venue's store could grant anything it hosts, the same exposure as
  the store holding the data itself.
- **A copied venue key.** It mints nothing here. Venues that accept this
  venue's attestation are bounded by their own trust policy, and the venue
  signs only for them.
- **Restarts.** Recovery looks kept identifiers up again, so revocation holds
  across a restart.

## Upgrade

Refusing tokens the venue signed takes effect at one release. Every
venue-signed grant issued before it stops working then, HITL approvals
included: non-expiring service grants must be issued again as held grants, and
GetMine must present identifiers rather than tokens (its grants are issued
hourly, so none needs issuing again). Brightside moves to lending first. The
CHANGELOG and upgrade notes say so.

## Staging

1. Lending, and Brightside's move to it.
2. Held grants: `ucan:issue` holding grants over hosted resources,
   presentation and recovery by identifier, `ucan:issued`,
   `ucan:revoke {id}` and the lifetime limit; HITL returns `grantId`.
3. GetMine presents identifiers (GetMine's repository).
4. Refusing tokens the venue signed, at a release.
5. Revoking tokens: token identifiers, `ucan:revoke {token}`, and the Convex
   hook for rejecting a token at a link.
6. Docs: [UCAN.md](./UCAN.md) §4, CONFIG.md, EMBEDDING.md (lending), the
   `ucan` and `hitl` skills and the user guide.

## Considerations

- **Revocation is local.** A token honoured at another venue under its trust
  policy stays good there until it expires, and that venue cannot see this
  one's revocations. Grants meant for other venues should be short-lived.
  Having verifiers ask the issuer instead would end COG-17's rule that a grant
  is checked without calling its grantor.
- **Grants with no expiry.** COG-17 lets a venue refuse presented grants that
  have none. Nothing here does. Held grants with no expiry accumulate until
  revoked; `auth.maxGrantLifetime` bounds them.
- **Self-sovereign owners.** `ucan:issue` refuses to issue over a
  self-sovereign caller's own namespace, which they sign client-side. Holding
  such grants for them is possible under COG-17 but not part of this design.
- **Agents as grantees.** What an agent reads can steer it. A long-lived
  grant to an agent is standing authority for whatever steers it; a lend for
  one call is the narrower tool.
- **The venue's `did:web` alias.** Resources and principals under the alias
  are the venue's, as `Engine.isVenuePrincipal` treats them.

## Rejected alternatives

- **Refusing tokens over the venue's own resources with no replacement**, as
  covia#660 first proposed: it breaks GetMine's administrators and the
  documented provisioning path.
- **Signed tokens with issuance records**, the venue signing a token for each
  grant and refusing any it has no record of: the record already is the
  grant, so the token adds a signature the venue checks against itself, and
  revoking by the hash of a JWT the holder can respell needs strict decoding
  everywhere.
- **Standing grants**, every live grant applying to its grantee's requests
  without being presented: authority becomes ambient (see Security). They may
  still suit the users the venue issued (#449, #450).
- **Identifiers derived from the record**, such as its Convex value hash:
  anyone who can guess the record can compute them (see Security).
- **Covia dropping revoked tokens itself** before calling the validator: the
  links above a presented token sit inside signed parents, so Covia could only
  drop a whole parent, losing its other valid paths.
- **Records held by a new adapter**: disabling it would quietly make revoked
  grants good again, and the core check would read adapter state.
- **The capability scope as the lend's carrier**: see Lending.
