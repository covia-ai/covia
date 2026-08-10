# Venue Authentication — Design (covia#297)

Configurable authentication methods and assurance policy for named venue
users. This is the design companion to covia#297 and its child issues
(#296 self-sovereign keys, #298 OIDC hardening, #299 email, #300
WebAuthn, #301 passwords). Registration/admission is separate (#269);
authorisation (capabilities, roles) is downstream and out of scope —
see `UCAN.md`.

Status: design agreed (2026-08-10) — all open decisions resolved inline
(marked "decided"). Phase 1 (§10) is unblocked.

## 1. Principles

1. **Identity invariant.** Every successful method authenticates the
   same stable venue user DID (e.g. `did:web:venue.example:u:sabine`).
   A login method is an *authenticator bound to that subject*, never the
   subject itself. Email, password, passkey and provider changes must
   not change the user DID.
2. **Authentication proves an authenticator; it grants nothing.**
   Roles, capabilities and admission remain separate decisions.
3. **Secure defaults.** No method is implicitly enabled by the presence
   of unrelated credentials; configuration validation fails closed.
4. **One verification seam.** All credential classes verify through
   `VenueAuthenticator` — embedders and transports never reproduce
   signature/audience/temporal rules. Policy checks are single
   Engine-owned methods at the seam, not scattered call-sites.
5. **Self-sovereign parity.** A named venue user with a registered key
   is as self-sovereign as a `did:key` user: the venue holds only
   public keys and can neither sign for the user nor lose their key.

## 2. Baseline (what exists on develop)

The implementation is further along than #297/#296 suggest:

| Piece | Where | State |
|-------|-------|-------|
| Single verification seam | `VenueAuthenticator.verify` | Four credential classes: UCAN bearer, self-issued EdDSA, venue-signed session JWT, external provider RS256 |
| Named-user key registry (#296) | `Auth.addAuthenticationKey(s)` / `revokeAuthenticationKey` | Active/revoked tombstones, one-subject-per-key, no silent reactivation, last-active-key guard (`allowLast` for venue recovery) |
| Named-user self-issued login (#296) | `VenueAuthenticator.tryVerifySelfIssued` | `sub` = local `did:web:…:u:<id>`, requires `iss == sub`, record DID match, **active** registered key, audience + temporal bounds |
| DID document publication (#296) | `UserAPI.getUserDIDDocument` | Active user-held keys published under `verificationMethod` + `authentication`, controller = user DID |
| Key enrolment surface (#296) | `UserAdapter` (`user:create` keys, add/revoke ops), `Engine` creation path | Venue-authorised, audited (`addedBy`/`revokedBy`) |
| Session minting | `LoginProviders` OAuth callback | Inline `JWT.signPublic` — no session id, no revocation, no amr/auth_time |
| Audience policy | `Auth` config + `requireAudience` | `verify`/`require`, `acceptedAudiences`, did:web alias |
| Public ceiling | `auth.public.*` (COG-10) | Unchanged by this design |

**Gaps this design closes:** per-method enable/disable and policy
config; a common method result (method reference, `amr`, assurance,
`auth_time`); a central session issuer with revocation; provider
identity keyed by `(issuer, subject)` instead of email; account-linking
rules; route-class assurance enforcement.

## 3. The method contract

Every authentication method produces one `AuthResult`:

| Field | Meaning |
|-------|---------|
| `userDID` | Stable venue user DID — the identity that acts |
| `authenticatedIdentity` | Identity directly proven by the credential (key `did:key`, provider `(iss,sub)`, session subject) |
| `method` | Stable method id (see §5) |
| `amr` | RFC 8176 authentication-method references (`pop`, `pwd`, `otp`, `hwk`, `mfa`, …) |
| `assurance` | Venue assurance level derived from `amr` + method (§6) |
| `authTime` | When the human/key actually authenticated (Unix s) |
| `provider` | Optional issuer/subject metadata — audit only, never authority |

Two event shapes share the contract:

- **Interactive login** (OIDC, email, WebAuthn, password) — produces an
  `AuthResult` once, then a **session** carries it (§4).
- **Per-request credential** (self-issued key JWT, UCAN bearer) —
  stateless; every request re-proves possession. `authTime` = token
  `iat`; no session exists or is needed.

`VenueAuthenticator.VerifiedPrincipal` grows into this record; the
existing two-identity model (`authenticatedIdentity` vs `venueUserDID`)
is already the right split and is retained.

**Embedders plug in at the same contract.** The method set is a
registry, not a closed enum: an embedder may register additional
methods implementing the same interface — each produces an
`AuthResult`, appears under its own id in `auth.methods` (registered
before config validation, so fail-closed checks accept it), and is
subject to the same policy machinery, session issuance, and step-up
rules as the built-ins. The existing `bindIdentity` attribution seam
remains for fully embedder-owned authentication that maps to a venue
user outside the registry.

## 4. Central session service

One issuer mints every venue session; methods never mint credentials
themselves (`LoginProviders` currently signs JWTs inline — it re-routes
here).

**Sessions are opaque venue-issued credentials, not JWTs** (decided).
The venue's own state is the source of truth, so a signed,
claims-bearing token would add parsing surface and a readable payload
for no benefit — signature verification is redundant once verification
requires a record. Opaque server-side session ids are the standard
pattern (RFC 6750 does not require JWT bearers; this is the
reference-token model of RFC 7662).

- **Token**: `covia_<base64url(128-bit random)>`. The prefix makes
  leaked tokens secret-scannable; the body is the raw session key. The
  token carries no information at all — no user, no claims, no
  structure beyond the prefix.
- **Lookup**: a venue-owned lattice `Index` keyed by the 128-bit Blob →
  `{userId, sid}`. Convex `Index` handles Blob keys natively — no
  hashing layer. (If leaked-backup hardening is ever wanted, keying the
  index by a hash of the token is a server-side-only change; the token
  format does not move.)
- **Session records are per-user** (decided): a `sessions` map on the
  venue-owned auth user record, `sid → {status, iat, exp, auth_time,
  method, amr, acr, revokedAt?, revokedBy?}`, tombstone-style like the
  key registry. `sid` is a short random display/audit identifier —
  never the token — so session listings and logs never contain
  credentials. Managed under the venue's update policy; never writable
  through the user's own namespace.
- **Verification**: decode token → Index lookup → the user's
  `sessions[sid]` must be `active` and unexpired. A dangling index
  entry (record gone or revoked) fails closed.
- **Assurance data lives in the record** — `amr`, `acr`, `auth_time`,
  `method` are read by the policy seam (§9). Clients get their login
  metadata from the login response, not by decoding the token.
- **Revocation**: flip the record — one session, or all of a user's by
  iterating their own map. Account-level kill remains the admission
  layer (a disabled user fails admission on every request, whatever
  they hold). Sessions no longer depend on the venue key, so key
  rotation does not collaterally log everyone out; clearing session
  state is the global lever, and a targeted one.
- **Audience**: not applicable — an opaque token is meaningless outside
  this venue's index, so sessions are venue-bound by construction. The
  JWT `aud` policy applies only to the signed credential classes.
- **Restart / GC / migration**: index and records are venue lattice
  state — sessions survive restarts and revocations are never
  forgotten; expired entries drop lazily on the next write; legacy
  venue-signed JWTs are honoured until expiry (bounded by
  `tokenExpiry`, default unchanged at 24 h), then that class retires
  from the session role.

**Delivery** (decided): one token endpoint, response-body delivery
everywhere; session tokens never travel in URLs (#298).

- `POST /api/v1/auth/login` — the single token endpoint. Accepts a
  method credential (password, OTP, WebAuthn assertion) *or* a
  single-use code (OIDC callback, email magic link) and returns
  `{token, did, exp}` in the response body.
- Redirect-based methods adapt to the endpoint via **single-use
  codes**: the venue's OIDC callback redirects to the frontend's
  registered `redirect_uri` with `?code=<single-use, seconds-lived>`,
  and the frontend exchanges it at the login endpoint. A code in a URL
  is safe the same way an OAuth authorization code is — single-use,
  near-instant expiry, useless without the exchange. Email magic links
  carry the same kind of code, never a session.
- `POST /api/v1/auth/logout` — revokes the presented session's record.
- Stateless credentials (`key`, `ucan`) never touch the login surface:
  per-request `Authorization` headers, no session minted.
- Cookies are embedder/BFF territory: an embedder owning the frontend
  origin may exchange the code server-side and set its own `HttpOnly`
  cookie. The venue primitive stays origin-agnostic.

## 5. Methods

| `method` id | Credential | User mapping | amr | Session? | Issue |
|-------------|-----------|--------------|-----|----------|-------|
| `key` | Self-issued EdDSA JWT | `did:key` direct; named user via active registered key | `pop` | No — stateless | #296 |
| `ucan` | UCAN bearer (aud = venue) | Issuer DID | `pop` | No — stateless | — |
| `session` | Opaque venue token (`covia_…`) | Index → per-user record | inherited | Is the session | — |
| `oidc` | Authorization Code + PKCE | `(issuer, subject)` binding (§7) | provider-dependent (`mfa` iff proven) | Yes | #298 |
| `email` | Magic link / OTP | Verified address on the user record | `otp` | Yes | #299 |
| `webauthn` | Passkey assertion | Registered credential id | `hwk`,`user` (UV) | Yes | #300 |
| `password` | Password (+ recovery) | Stored verifier | `pwd` | Yes | #301 |

Named-user and `did:key` self-issued login share the `key` method
deliberately: same credential class, same proof (`pop`), same
verification path — a named user with a registered key is exactly as
self-sovereign as a bare `did:key` (§1.5). Policy that must distinguish
"venue-managed subject" from "raw did:key" keys off the *subject form*,
not the method.

### 5.1 Finishing #296 (self-sovereign named users)

The core is implemented (§2). The remaining delta:

1. **covia#323** — the `did:key` branch of `tryVerifySelfIssued` never
   reads `iss`, so a present-but-mismatched `iss` verifies (RFC 8725
   §3.8 non-conformant). Reject `iss` present-and-≠-`sub` in both
   branches; keep `iss` optional for `did:key` subjects.
2. **Acceptance-test sweep** per the #296 criteria: multiple keys,
   rotation, revoked-key rejection (stale key after removal), foreign
   `did:web` rejection, `kid` belonging to another user, audience and
   temporal negatives.
3. **Enrolment authorisation** (decided): add/revoke ops require the
   venue operator, or the user themselves meeting `policy.enrolment`
   via step-up (§9) — a live session alone is never enough to change
   the key set, since an enrolled authenticator outlives any stolen
   credential. `user:create` continues to install keys at creation
   under the creating authority.

With those, #296 closes. Nothing in this design blocks it; the method
registry (§6) treats it as the already-working `key` method.

## 6. Configuration

Extends the existing `auth` block (`public`, `tokenExpiry`, `audience`,
`acceptedAudiences` unchanged). New `methods` and `policy` sections:

```json
"auth": {
  "methods": {
    "key":      { "enabled": true },
    "ucan":     { "enabled": true },
    "oidc":     { "enabled": false,
                  "providers": { "google": { "clientId": "…", "clientSecret": "s/google-oauth" } } },
    "email":    { "enabled": false, "modes": ["magic-link", "otp"] },
    "webauthn": { "enabled": false, "rpId": "venue.example" },
    "password": { "enabled": false }
  },
  "policy": {
    "default":   { "allowed": ["key", "ucan", "session", "oidc"] },
    "enrolment": { "minimumAssurance": "strong", "maxAuthenticationAgeSeconds": 900 },
    "admin":     { "allowed": ["key", "webauthn"], "minimumAssurance": "strong",
                   "maxAuthenticationAgeSeconds": 900 }
  }
}
```

Rules:

- **Defaults** (no `methods` block): current behaviour — `key`, `ucan`
  and `session` enabled; `oidc` enabled iff providers are configured
  (`auth.oauth` remains a deprecated alias for `methods.oidc.providers`
  for one release). `email`/`webauthn`/`password` default off.
- **Disabled = absent**: routes not registered, credentials of that
  class rejected at the seam, regardless of stored state (a disabled
  `password` verifier or OIDC binding stays on record for re-enable,
  but authenticates nothing).
- **Fail closed at startup**: unknown method names, malformed provider
  blocks, non-HTTPS/unsafe redirect origins, and impossible policy
  (a policy whose `allowed` methods cannot reach its
  `minimumAssurance`) are configuration errors, not warnings.
- **Secrets stay external**: provider secrets by `s/<name>` reference
  (SecretStore), never inline in tracked config.
- The **last viable strong authenticator** of a venue administrator
  cannot be removed without the explicit venue recovery path
  (`allowLast` — already enforced for keys; generalise per method).
- **Policy names label surface classes, not user roles** (§9): `admin`
  above means "surfaces the operator designates admin-grade", never a
  role held by users.

**Assurance ladder** (decided): three named levels, used directly as
profile-defined `acr` values, mapping approximately to NIST 800-63B:

| Level | ≈ NIST | Meaning | Methods |
|-------|--------|---------|---------|
| `standard` | AAL1 | Any single accepted factor | `password`, `email`, plain `oidc` |
| `strong` | AAL2 | MFA, or phishing-resistant possession proof | `oidc` with IdP-asserted `mfa`, `key`, software passkeys |
| `hard` | AAL3 | Hardware-backed, phishing-resistant, user-verified | `webauthn` with attestation requiring hardware + UV |

- **Documented deviation from strict NIST**: a self-sovereign `key` is
  formally single-factor, but the venue cannot observe how the key is
  held (HSM or plaintext file — inherent to self-sovereignty), while
  the property that distinguishes the upper tiers — phishing /
  verifier-impersonation resistance — is exactly what a signature
  credential has. `key` therefore rates `strong`; a strict-NIST venue
  can gate its sensitive surfaces on `hard` instead.
- **`hard` is initially unreachable** — no method attains it until
  WebAuthn (#300) lands with attestation-required configuration;
  policy naming it before then fails closed at startup (the
  impossible-policy rule).
- **Interop note**: the OpenID `phr`/`phrh` acr values
  (phishing-resistant / phishing-resistant hardware) correspond to
  `strong`/`hard` — the ladder stratifies on phishing resistance, not
  factor count, matching ecosystem practice. Raw `amr` is stored in
  the session record regardless, so finer-grained policy remains
  possible later without migration.

## 7. Provider identity binding (#298 prerequisite)

Provider logins bind by immutable `(issuer, subject)`, held in a
venue-owned index: `(iss, sub) → userId`, one user per pair. Email and
display name are mutable attributes on the user record, refreshed at
login (given `email_verified`), **never lookup keys**. Records already
holding `provider`/`providerSub` fields bind directly.

**Legacy email-only records** (decided): conditional auto-bind by
default — a login binds a legacy record only when it comes from the
**same provider** the record names **and** the IdP asserts
`email_verified`; the `(issuer, subject)` pair is then stamped
permanently. A different issuer or an unverified email requires
operator confirmation. A cross-provider email match **never** binds —
that is account linking (§8), requiring proof of both sides. The
residual risk is a recycled address at the same IdP during the
pre-stamp window; `legacyEmailBinding: "confirm"` routes everything
through confirmation for venues on recycling-prone domains (both modes
share the confirmation path). Note this is a strict tightening: today
email match binds every login, cross-provider included.

**Embedder policy seam**: the binding decision is pluggable. The two
built-in modes (`auto`, `confirm`) implement a `BindingPolicy`
interface — `(issuer, subject, email, emailVerified, candidate
record) → BIND | CONFIRM | REJECT` — and an embedder may register its
own (e.g. consult an HR directory before binding). Configuration
selects the policy by name; unknown names fail closed at startup.

## 8. Account linking

Linking a second method to a user is its own operation, never a side
effect:

- Requires an **active session (or stateless proof) meeting
  `policy.enrolment`** — fresh, strong — *plus* an immediate proof of
  the method being linked (complete the OIDC flow / verify the key /
  assert the passkey within the linking transaction).
- Matching email alone never links (#297/#298 invariant).
- Every linked authenticator is auditable (`addedBy`, `addedAt`,
  method metadata) and individually revocable, mirroring the key
  registry's tombstone model.

## 9. Assurance policy enforcement

One Engine-owned seam: `engine.requireAssurance(ctx, policyName)` —
resolves the request's method/`acr`/`auth_time` (from the session
record, or inherently for stateless credential classes) against the
named policy. Protected venue surfaces (key enrolment, user admin,
config-affecting operations) name their policy at the point of action,
exactly like `requireCapability`. Embedders reuse the same call for
application routes.

**Step-up** (decided) — the standard sudo-mode pattern:

- A failed check rejects with the RFC 9470 challenge —
  `WWW-Authenticate: Bearer error="insufficient_user_authentication"`,
  naming the required `acr_values` / `max_age` — so clients know
  exactly what quality of re-authentication to perform.
- Re-authentication is a re-run of login with stricter parameters
  (OIDC: `max_age=0` / `prompt=login`, optionally `acr_values`;
  password/WebAuthn: re-present at the login endpoint). A successful
  re-auth **updates the existing session record's**
  `auth_time`/`amr`/`acr`; the client then retries the original call.
- Stateless `key`/`ucan` credentials satisfy freshness inherently —
  every request carries a signature minted at call time, so
  `auth_time` is effectively *now*; a key user never sees a step-up
  prompt.

**Roles are out of scope** (decided): policies name *surface classes*,
never user roles — authentication proves an authenticator and grants
nothing (§1). Anything role-shaped is downstream authorisation, owned
by the application or embedder, which performs its own role check and
then calls this same seam with its own policy name. #297's per-role
acceptance criterion is met exactly that way: the venue enforces the
assurance a surface demands; who counts as an admin is never
venue-auth state.

## 10. Phasing

1. **Phase 1 — contract, config, no behaviour change.** `AuthResult` +
   method registry + fail-closed config validation; re-plumb the four
   existing verifiers and the OAuth callback through the contract and
   the central session issuer (sessions become opaque `covia_…` tokens
   backed by per-user records; outstanding venue-signed JWTs honoured
   until expiry). Fix #323. Land the #296 test sweep and close #296.
2. **Phase 2 — #298 OIDC hardening** on the new plumbing: PKCE,
   server-bound single-use `state`, OIDC `nonce`, exact redirect
   allowlist, back-channel token delivery, `(iss, sub)` binding (§7),
   negative-path tests.
3. **Phase 3 — sessions and policy**: revocation records + central
   revocation surface; `requireAssurance` enforcement on the enrolment
   and admin surfaces.
4. **Phase 4 — new methods** as independent reviews: #299 email, #300
   WebAuthn, #301 passwords (in that order of value; passwords last and
   default-off).

## 11. Security considerations

- **Existence privacy**: authentication errors never disclose whether a
  named account exists (uniform failure at the seam — already the
  pattern in `verify`).
- **Method downgrade**: policy `allowed` lists are enforced at the
  seam, so an attacker cannot authenticate a high-value user via a
  weaker enabled method than policy permits for the surface.
- **Stateless credentials and revocation**: `key`/`ucan` proofs cannot
  be centrally revoked mid-lifetime — they are short-lived by
  construction (minutes), and named-user keys are revocable at the
  registry. Session revocation covers the long-lived class.
- **Audit**: authentication records (key registry, provider bindings,
  session records) are venue-owned state; users cannot edit them via
  their own namespaces.

## Related

- `UCAN.md` — capabilities and the proof channel (authorisation layer)
- `CONFIG.md` — operator configuration reference (gains §6 on landing)
- COG-3 / COG-10 — token classes, self-issued rules, public ceiling
- covia#297 (this design), #296, #298, #299, #300, #301, #323, #269
