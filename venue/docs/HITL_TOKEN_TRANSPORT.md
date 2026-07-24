# HITL Self-Sovereign Token Transport (COG-19, design)

How a resident agent obtains a capability token it can use on **another
venue** — by asking its human, who signs the token with their own key. The
protocol-level specification is **COG-19** (covia-docs); this document is the
implementation-facing design. It builds on COG-16 (HITL, `venue/docs`/the
`hitl` skill) and COG-17 (granting surfaces, [UCAN.md](./UCAN.md) §4.1); it is a
proposal — the decisions it must settle are at the end.

Tracked by covia#292.

## The problem

An agent owned by user *U*, running on venue *A*, needs to act as *U* on venue
*B*, where *U* has resources. Today it cannot. A cross-venue grid call
([UCAN.md](./UCAN.md) §5.6) arrives at *B* as *B*'s `:public` identity unless it
carries authority in the proof channel, and the agent holds no authority to sign
as its owner — a Model-A sub-principal (`<owner>:g:<agentId>`, §5.4) has no key.
The natural resolution is human-in-the-loop: the agent asks *U* for a scoped,
time-boxed access token; *U* reviews the requested capabilities, signs a token
with their device key, and hands it back — or rejects.

## Principle: the venue transports, it does not mint

This is the whole design in one line, and it is what makes the feature both
possible and safe.

The existing HITL grant path **mints**. `hitl:respond` → `issueGrants` →
`v/ops/ucan/issue` produces a **venue-signed** UCAN (`iss` = this venue), rooted
in *this* venue's authority. That is correct for resources this venue controls
and useless anywhere else — venue *B* rejects it (`rootAuthorityPolicy`:
`SELF_SOVEREIGN.or(this venue attests only for its own managed users)`).

The token path **transports**. The human signs a UCAN client-side with their own
`did:key` (`iss` = *U*, `aud` = the requester, `att` = the caps), and the venue
only carries it back to the agent. A user-rooted token verifies on **any** venue
via the `SELF_SOVEREIGN` branch of the root-authority policy — no issuing venue
is involved at all. Transport-not-mint is the distinction that makes the token
usable off-venue.

## Trust model — a checked courier, not an issuer

The venue never holds *U*'s key, never signs, and cannot forge or alter the
token. It follows that **the token's integrity does not depend on trusting the
venue**: a fully-compromised venue *A* can at worst fail to deliver, or deliver
garbage — and garbage fails signature verification at *B*. This is a strictly
weaker trust assumption than the mint path, where the venue's signature *is* the
authority.

Two protections already hold, from COG-16/COG-17 and the sub-principal work:

- **Only the human answers.** `HITLAdapter.handleRespond` refuses a
  sub-principal context and reads only the caller's own inbox, so an agent
  cannot answer its own token request (the same guard that stops an agent
  laundering `ucan:issue` through HITL).
- **The responder is authoritative over their own key.** The venue must **not**
  police `att ⊆ requested caps`: the human reviewed — and may have edited — the
  capabilities in the UI, and it is their signature. The venue validates
  *provenance*, never *policy*.

## Mechanism: a `token` ask type

A new HITL ask type, alongside `text`/`approval`/`choice`/`checkboxes`.

### Request

```jsonc
{
  "id": "b-access",
  "type": "token",
  "prompt": "Grant this agent read access to your invoices on venue B?",
  "required": true,
  "token": {
    "caps":     [ { "with": "did:web:b.example/w/invoices/", "can": "crud/read" } ],
    "exp":      3600,                       // optional, seconds; UI may lower
    "audience": "<requester DID>",          // optional; default = the request's `from`
    "venue":    "did:web:b.example"         // optional, informational — UI shows/links it
  }
}
```

The `token` spec is a **first-class, validated field** — not an unknown field
riding through. (covia#292 reports custom fields being stripped; in fact
`HitlValidation.validateAsks` returns asks unchanged and `buildRecord` stores
them whole, so the loss is in the client request builder / a JSON round-trip.
Making `token`/`caps`/`audience`/`venue` validated fields fixes it definitively
rather than relying on passthrough.)

`audience` defaults to the request's `from` — the requesting agent — so the
token is bound to exactly the principal that asked (see the crux below). The
whole HITL spine is reused: record, `h/` inbox, job parking (`INPUT_REQUIRED`),
expiry, the `agent`/`from` provenance stamps.

### Response

The answer to a `token` ask is a **signed UCAN JWT string** — not a boolean, not
free text. The venue does **not** run `issueGrants` for these; it verifies and
transports.

**Validation (cheap, no trust, no mint):**

1. The answer is a well-formed UCAN JWT.
2. Its signature verifies against the **responder's `did:key`**, and `iss`
   equals `ctx.getUserDID()` — proof that *this human* signed it, not something
   an agent slipped in. (`DIDVerifier.CONVEX` already verifies `did:key` JWTs.)
3. `aud` equals the ask's `audience` (the requester) — **audience binding**,
   security-critical (below).
4. `exp` is present and not already past.

On success the token flows to the requester's job output under `token`, the same
delivery path the venue-signed grant token uses today — same channel, different
provenance.

**This is inherently a self-sovereign-user feature.** `iss` must be a `did:key`
the human can sign with. A venue-managed `did:web` custodial user has no client
key and uses the mint path (`grants`) instead.

## The crux: audience binding vs keyless agents

The token **must** be bound to `aud` = the requesting agent, so that leakage
from the persisted job record cannot be replayed by anyone else. But a Model-A
sub-principal has no key, so it cannot authenticate to venue *B* as that
audience — and *B*'s proof check requires the caller to have established the
audience identity (via an identity token signed by that key, §5.6). Making the
returned token *usable at B* therefore needs one of:

| Path | What `aud` is | Status |
|---|---|---|
| **Model-B agent identity** (§5.4) | the agent's own key | deferred |
| **C3b custodial attestation** (covia#100) | the agent sub-principal DID; venue *A* vouches to *B* | deferred |
| **Ephemeral session key** | a throwaway key the frontend mints; token + key returned to the agent | works today, self-sovereign, scoped |

**This document deliberately does not choose the presentation path.** How the
agent presents the token at *B* is a frontend/SDK concern; the venue's
responsibility is only to carry a token whose `audience` is set and verified.
`audience` is a first-class field precisely so the choice is the caller's and the
transport is future-proof: default it to the requesting agent (the clean Model-B
end state), and today a frontend may set it to an ephemeral session-key DID and
return that key alongside.

The consequence to state plainly: **the transport ships and is secure standalone,
but end-to-end *use* by a keyless agent additionally needs a presentable key.**
That second half rides on Model-B or C3b (both already on the roadmap) or the
ephemeral-key pattern — it is not blocked by, and does not block, this COG.

## The token is a secret

Unlike the venue-signed grant token, the transported token should be treated as
a bearer-ish credential:

- Deliver it to the requester's **job output**, but **omit it from the durable
  inbox `response` record** — the human does not need it echoed back, and the
  inbox is longer-lived than the credential should be.
- Lean on short `exp` + audience binding as the leak defence: even lifted from a
  job record, an audience-bound token is useless to any principal but the
  requester.

This is the one place the design goes beyond the grant path, which persists its
token.

## Implementation delta (minimal, ~3 files)

- **`Hitl` (covia-core)** — add the `token` ask type and the spec field
  constants (`CAPS`, `AUDIENCE`, `VENUE`; `EXP`/`WITH`/`CAN` exist).
- **`HitlValidation`** — accept `type:"token"`; validate its `token` spec
  (`caps:[{with,can}]`, optional `exp`/`audience`/`venue`); on the answer side a
  `token` ask takes a **string** answer and runs **no** grants / echo-consent
  path.
- **`HITLAdapter.resolveAnswer`** — branch on ask type: for a `token` ask,
  verify the JWT (signature / `iss` / `aud` / `exp`) and place it on the job
  output; **skip `issueGrants` entirely** — no `ucan:issue`, no responder-
  authority mint. Omit the token from the persisted response record.

No change to job parking, expiry, inbox delivery, or the respond-side identity
guard — all reused as-is.

## Security checklist

**The venue does:** verify the human's signature and `iss`; verify `aud` = the
requester; verify `exp`; transport; treat the token as a secret.

**The venue does not:** sign or mint; hold the user's key; enforce `att ⊆
request` (the human is authoritative); persist the token in the durable inbox
record; let an agent answer (already blocked).

## Decisions to settle

1. **Ask-type name.** `token` vs `access-token` vs `grant-request`. `token`
   reads cleanly against the existing types; the answer being a token, not a
   choice, is the differentiator.
2. **Audience default and enforcement.** Default `aud` = the request's `from`
   (recommended). Enforce `aud` == requester on the returned token, or accept a
   caller-chosen `aud` (e.g. an ephemeral key) with only a presence check?
   Recommendation: require `aud` to be *present and non-empty*, and if the
   request pinned an `audience`, require the token to match it — but allow the
   request to pin an ephemeral-key `audience` explicitly.
3. **`exp` policy.** A required maximum lifetime for token asks (e.g. cap at 24h)
   vs advisory. Recommendation: cap, since these are cross-venue bearer-ish
   credentials.
4. **Response persistence.** Confirm omitting the token from the durable inbox
   record is acceptable to the frontend's audit needs (the job output and the
   answered-status record remain).

## References

- covia#292 — the feature request and worked frontend example (frontend#171).
- [UCAN.md](./UCAN.md) §4.1 granting surfaces (COG-17), §5.4 agent identity
  models, §5.6 cross-venue forwarding and root-authority policy.
- COG-16 — HITL (`hitl` adapter, `h/` inbox, echo-consent grants).
- covia#100 — C3b custodial attestation (the deferred cross-venue trust that
  lets a token be audience-bound to a keyless agent).
