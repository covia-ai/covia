# Venue Authentication — Design (covia#297)

Configurable authentication methods and assurance policy for named venue
users. This is the design companion to covia#297 and its child issues
(#296 self-sovereign keys, #298 OIDC hardening, #299 email, #300
WebAuthn, #301 passwords). Registration/admission is separate (#269);
authorisation (capabilities, roles) is downstream and out of scope —
see `UCAN.md`.

Status: design agreed 2026-08-10, revised after review 2026-10-21.
This document specifies the target design; §2 and the Phase 1 note below
record the implemented subset. The revision separates the method-independent core from
protocol payloads and tightens proof/freshness, sessions, migration and delivery.
Phase 1 (§10) remains a bounded refactor; later phases must satisfy their
acceptance criteria before activation.

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
6. **Upstream first.** Inspect the APIs at the dependency version in use
   before implementing generic verification, cryptography, transactions or
   lifecycle machinery. Reuse supported upstream facilities; request missing
   reusable capabilities upstream. Covia owns identity mapping, admission,
   policy and its public API, rather than another implementation of those
   generic mechanisms.
7. **Protocol-independent core.** Identity, verified evidence, optional sessions
   and policy do not depend on OAuth, OIDC, JWT or a browser. Protocol modules
   own their claims, ceremonies, delivery and error formats. Use standard field
   names at each protocol boundary without imposing that protocol on other
   methods. Session continuity and client authorisation grants are separate.

## 2. Baseline (what exists on develop)

The implementation is further along than #297/#296 suggest:

| Piece | Where | State |
|-------|-------|-------|
| Single verification seam | `VenueAuthenticator.verify` | Four credential classes: UCAN bearer, self-issued EdDSA, venue-signed session JWT, external provider RS256 |
| Common result (Phase 1) | `AuthResult`, `VenueAuthenticator.verify` / `bindResult` | Neutral identity, credential and event records with typed provenance; existing acceptance unchanged, unknown evidence absent |
| Named-user key registry (#296) | `Auth.addAuthenticationKey(s)` / `revokeAuthenticationKey` | Active/revoked tombstones, one-subject-per-key, no silent reactivation, last-active-key guard (`allowLast` for venue recovery) |
| Named-user self-issued login (#296) | `VenueAuthenticator` self-issued verification | `sub` = local `did:web:…:u:<id>`, requires `iss == sub`, record DID match, **active** registered key, audience + temporal bounds |
| DID document publication (#296) | `UserAPI.getUserDIDDocument` | Active user-held keys published under `verificationMethod` + `authentication`, controller = user DID |
| Key enrolment surface (#296) | `UserAdapter` (`user:create` keys, add/revoke ops), `Engine` creation path | Venue-authorised, audited (`addedBy`/`revokedBy`) |
| Session minting | `LoginProviders` OAuth callback | Inline `JWT.signPublic` — no session id, no revocation, no amr/auth_time |
| OAuth authorization server | `OAuthProvider` | Existing code/PKCE, token, refresh and revocation endpoints; scopes recorded for audit, not yet enforced as capability restrictions |
| Audience policy | `Auth` config + `requireAudience` | `verify`/`require`, `acceptedAudiences`, did:web alias |
| Public capability scope | `auth.public.*` (COG-10) | Unchanged by this design |

**Gaps this design closes:** per-method enable/disable and policy
config; a common method result (identity, evidence and typed method details);
an optional central session service with revocation; provider
identity keyed by `(issuer, subject)` instead of email; account-linking
rules; route-class assurance enforcement.

## 3. The method contract

Every authentication method produces one `AuthResult`. This is an internal
contract, not an OAuth token response or a bag of JWT claims. The following
logical shape guides implementation; reuse existing identity and value types
rather than introducing a general claims framework:

| Field | Meaning |
|-------|---------|
| `userDID` | Stable venue user DID — the identity that acts |
| `authenticatedIdentity` | Identity directly proven by the credential (key `did:key`, provider `(iss,sub)`, session subject) |
| `credential` | Presented credential description: `type`, optional validated `issuedAt` / `expiresAt`; no secret bytes |
| `authentication` | Verified event: originating `method`, optional `binding`, optional `authenticatedAt`, verified `properties`, optional typed `details` |
| `proof` | Optional request-bound possession evidence: `verifiedAt` and verified properties; never inherited as fresh from a session |
| `sessionId` | Optional non-secret reference to managed continuity state (§4); absent for direct/stateless authentication |

`authentication.binding` identifies the venue-owned authenticator/provider
binding for revocation and configuration checks. `authenticatedIdentity` is
typed so a key identity or provider identity need not masquerade as the other.
`properties` contains only established facts (for example `mfa`,
`phishingResistant`, `hardwareProtected`, `userPresent`, `userVerified`), not
client assertions. Absence means unproven. Policy derives local assurance
from those facts and trusted provenance under current configuration (§6);
there is no independent caller-supplied assurance label to trust.

**Method-specific payloads.** The registered method selects the schema of
`authentication.details`; it is not an unrestricted claim bag:

- OIDC details retain a bounded, validated selection of provider claims:
  `iss`, `sub`, optional `auth_time`, `amr`, `acr`. Provider configuration and
  the binding identify the verifier/trust policy. Map accepted evidence into
  common properties; never interpret an arbitrary provider `acr` as a local
  assurance level. Keep the provider subject distinct from `userDID`.
- WebAuthn details retain relevant verified credential/ceremony metadata,
  including UP/UV and backup/attestation evidence. Shared policy consumes the
  resulting presence, verification and protection properties without parsing
  WebAuthn structures. Hardware protection requires independent evidence.
- Key and UCAN verifiers retain the relevant verified key/proof references;
  UCAN authority continues through the existing capability path, not these
  authentication details. Password/email methods retain only bounded method
  metadata, never passwords, OTPs, bearer links or password verifiers.

Only trusted verifiers construct successful results. Retain enough provenance
to re-evaluate changed trust policy, or reject dependent sessions when it
changes; do not persist raw provider tokens or unconstrained claim sets.
Do not combine properties from unrelated events to manufacture MFA or a fresh
authentication time. A composed ceremony must establish the same principal,
transaction binding and factor independence before asserting combined evidence.

Core and store names follow Covia's camelCase conventions. Authentication,
proof and credential timestamps are distinct; serialized values here use Unix
seconds with explicit units. Unknown times are absent. Protocol serializers
own standard names and exact formats (§4.2); no universal `iss`/`sub`/`aud`,
`amr`/`acr` or OAuth client is required in the core.

Two event shapes share the contract:

- **Interactive login** (OIDC, email, WebAuthn, password) — produces an
  `AuthResult`; a caller may request managed continuity through a **session**
  (§4). The method neither mints the credential nor requires OAuth to do so.
- **Stateless credential** (self-issued key JWT, UCAN bearer) — verified
  on each request without creating a session. A signature authenticates the
  issuer's statement; presenting a reusable bearer does not establish fresh
  possession of the signing key. `iat` describes issuance, not necessarily
  human authentication. Never substitute request time for unknown
  `authentication.authenticatedAt`.

Fresh key possession requires an independently verified challenge or
request-bound proof with replay protection, using a supported upstream
protocol. That evidence may establish `proof.verifiedAt`; it does not establish
human presence or authentication time unless the protocol proves those too.
Machine/agent credentials remain valid for ordinary authorised operations,
but do not acquire evidence of a human authentication event by delegation.

**Phase 1 implementation (2026-10-20).** `AuthResult` replaces the private
`VerifiedPrincipal`. `verify(token)` returns it; existing `authenticate`
overloads keep returning the venue user DID. HTTP authentication also binds the
result for `authenticationResult(context)`. Trusted embedders may `bindResult`
after their own verification. Both identity-only binding APIs discard any
earlier evidence, even when rebinding the same identity.

The compatibility identity fields remain `AString`. Method-owned immutable
`AMap<AString, ACell>` payloads carry provenance; the core declares no built-in
method ids, protocol fields or Java payload subclasses. Provider
subject/email mapping remains the existing behaviour until #298. Provider
`iss` is absent when issuer validation was skipped; a signed subject is not
misrepresented as an established issuer/subject account binding.

Existing verifiers do not validate `iat` as issuance evidence or establish a
human authentication event, MFA or a replay-protected request proof. Therefore
`issuedAt`, `authenticatedAt`, `proof` and `sessionId` remain absent, properties
empty, and untrusted `amr`/`acr`/`auth_time` are not copied. Integer expiry is
projected after the existing temporal checks. Fractional provider NumericDate
expiry remains accepted by upstream but is omitted from this integer metadata
projection rather than rounded. Legacy venue JWTs have unknown originating
method, including tokens minted by the OAuth server.

This phase introduces no registry, trust mapping, policy enforcement, session
storage or new credential wire format. The result is available to direct callers and HTTP
embedders; propagation into action-policy contexts belongs to the enforcement
phase, not implicit evidence inheritance through jobs or agent delegation.

**Upstream audit.** Reviewed the installed Convex **0.8.17 sources** (the
dependency selected by the POM, not the neighbouring 0.8.18-SNAPSHOT checkout).
`JWT.verifyPublic` returns signature-verified claims; `JWT.validateClaims`
checks configured issuer/audience and optional NumericDate expiry, not
authentication time or MFA. `DIDVerifier` supplies signature verification;
`UCAN` exposes issuer/audience/expiry and verified payload access through
Covia's existing compatibility validator. These primitives remain in use.
None owns Covia's local-user mapping or host authentication-result contract,
so no competing verifier or upstream change is needed for this phase.

### 3.1 Grid-native data and operations

**Implemented data boundary.** `AuthResult.toData()` projects the common
record into an immutable `AMap<AString, ACell>`. Nested records are maps,
identifiers are `AString`, times are integer Unix seconds, properties are
`ASet<AString>`, and optional unknown fields are omitted. Details are already
Convex values, preserved without core interpretation. Existing CAD3 encoding,
content hashes, lattice cursors and operation inputs/outputs work directly;
no Java serialisation, class-name discriminator or per-method codec is needed.
JSON boundaries render sets as arrays; they must validate property collections
when reading them, rather than treating JSON parsing as evidence verification.
The result's map is data, never a bearer credential or a proof of authenticity.

The method owns its detail schema and validation. The current key verifier
retains `signingIdentity`, UCAN retains `iss`/`aud`, and the provider verifier
retains `provider` and checked `iss`/signed `sub` when available. A new method
may supply a different bounded map without editing the core. A method whose
stored schema evolves owns its versioning and migration inside that payload.
Core policy reads verified common properties, not arbitrary details. Convex
values guarantee immutability, not confidentiality or evidentiary validity;
trusted verifiers remain responsible for the allowlisted projection.

**Method registration and operation integration (Phase 2 contract).** Reuse
the adapter/module lifecycle and operation metadata in [ADAPTERS.md](ADAPTERS.md)
and [OPERATIONS.md](OPERATIONS.md):

- Trusted adapter code contributes method ids, verifiers, evidence declarations
  and any method-owned ceremony operations through an authentication extension
  interface. Effective configuration lives under `adapters.<name>` and uses
  the existing configuration and lifecycle hooks. One adapter may contribute
  several methods; method ids are not adapter names. A method need not be HTTP-based,
  interactive, JWT-based or session-producing. Do not impose a universal
  begin/finish or OAuth grant interface on stateless and interactive methods.
- Ceremony operations use normal content-addressed operation assets, schemas
  and `ACell` inputs/outputs. Publish only safe method metadata and operation
  references through the existing adapter catalog. Browsers and protocol
  callbacks are transport bindings to the same method implementation.
- Registration is independent of discovery: publishing an asset or receiving
  a map from a job, remote venue or user workspace cannot register a verifier
  or authenticate a caller. Only the trusted verification boundary may bind
  an `AuthResult`. Arbitrary adapter/agent output is not evidence.
- Capability checks still apply to enrolled-user administration and session
  revocation. Unauthenticated ceremony entry points need a deliberately
  narrow admission path with throttling and method-specific replay checks;
  they must not grant general anonymous operation execution.
- Challenge state and authenticator bindings are private, venue-owned lattice
  records, using the existing auth store or a method adapter's
  `AdapterWorkspace`, never caller-writable evidence. Reuse existing key
  bindings instead of creating parallel registries. Secret material follows
  `SecretStore` rules; job input/output, public catalog metadata and event
  details must not contain passwords, OTPs, private keys or bearer tokens.
  Sensitive exchange steps therefore need the established direct, unrecorded
  path rather than normal durable jobs.
- Authentication events are coherent whole records. Never merge properties
  or proofs across unrelated events by set union. Use the existing venue
  single-writer lattice and upstream atomic update mechanisms for consume,
  rotate and revoke; the fact that values replicate does not make a stale
  replica authoritative for authentication or immediate revocation (§4).
- Adapter disable/unload must withdraw its verifier as well as discovery;
  dependent credentials/sessions must fail closed under current policy.
  A job retaining an old adapter instance cannot keep authentication enabled.
- Core maintains a derived lookup of contributions from active adapters, not
  a separately configured or persisted method registry. Duplicate method ids
  fail validation rather than overriding another adapter. Reconfiguration
  validates the complete proposed contribution before publication; rejection
  leaves the previous configuration intact. Disabling a method takes effect
  at the authentication boundary even for an in-flight ceremony completion.
  Policy declarations must be validated against available methods before
  serving requests; unknown methods and unattainable requirements fail closed.

The data projection is implemented and tested through existing CAD3 operations
and private lattice storage. Adapter contributions, ceremony dispatch and lifecycle
policy above remain Phase 2 work; no new login operation is exposed yet.

**Embedders plug in at the same contract.** An embedder may install an adapter
contributing additional methods through the same interface. Each produces an
`AuthResult`, owns its configuration under that adapter and is subject to the
same shared policy, session issuance and step-up rules as the built-ins.
There is no parallel `auth.methods` configuration tree or registration API
with an independent enable/disable lifecycle. Installing adapter code is a
trusted host action, not something an operation asset can request. Each method
declares the evidence it can verify; central policy caps the assurance it may
assert. Provider-supplied `amr`/`acr` require an explicit trusted-provider
mapping, not acceptance of arbitrary claims or a method's name.
The existing `bindIdentity` attribution seam remains for fully embedder-owned
authentication. Identity-only attribution carries no implicit freshness or
elevated assurance; an embedder must supply verified evidence through the
same trusted contract to satisfy such policies.

## 4. Central session service

One issuer mints every venue session; methods never mint credentials
themselves (`LoginProviders` currently signs JWTs inline — it re-routes
here).

**A session records authentication continuity; its credential is an opaque
venue-issued token, not a JWT** (decided). The session is optional: direct
credentials and trusted embedded authentication do not need a synthetic session.
The venue's own state is the source of truth, so a signed,
claims-bearing token would add parsing surface and a readable payload
for no benefit — signature verification is redundant once verification
requires a record. The server-side session pattern does not require an OAuth
grant or an HTTP introspection endpoint. Protocol modules may use opaque
credentials too, but their token purpose and authorisation must remain explicit.

- **Token**: `covia_<base64url(128-bit random)>`. The prefix makes
  leaked tokens secret-scannable; the body is a random credential secret. Use
  unpadded base64url and an exact canonical encoding. The
  token carries no information at all — no user, no claims, no
  structure beyond the prefix.
- **Lookup**: a venue-owned lattice `Index` keyed by the SHA-256 digest
  of the complete, canonically encoded token →
  `{userId, sessionId, issuedAt, expiresAt}`. Token expiry cannot exceed session
  expiry. Keep token issuance time distinct from session creation. Use the
  existing Convex hashing primitive. Persist the digest only; the bearer is
  delivered once and excluded from logs and audit records. The random token's
  entropy permits a fast digest rather than password hashing. A read-only
  store leak then reveals no directly usable session bearer; write access to
  authentication state remains a compromise of the authority.
- **Session records are per-user** (decided): a `sessions` map on the
  venue-owned auth user record,
  `sessionId → {status, createdAt, expiresAt, authentication, revokedAt?, revokedBy?}`,
  tombstone-style like the key registry. `authentication` is the verified event
  from §3, including its typed method payload and binding. `sessionId` is a
  random display/audit identifier unique within the venue —
  never the token — so session listings and logs never contain
  credentials. Managed under the venue's update policy; never writable
  through the user's own namespace.
- **Verification**: validate token encoding → digest → Index lookup →
  the user's `sessions[sessionId]` and credential must be active and unexpired.
  Read index and record from one authoritative snapshot. A dangling index entry, disabled
  originating method/provider, revoked authenticator binding or rejected
  account admission fails closed. Session records retain the originating
  binding reference needed for those checks.
- **Authentication evidence lives in the record** — originating method,
  binding, authentication time, properties and typed details feed the policy
  seam (§9). Assurance is evaluated under current trust policy. Request proof
  is verified anew, never inherited as fresh from a session record. Clients
  get permitted session metadata through the relevant interface, not by
  decoding the token; provider payloads are not automatically public.
- **Revocation**: flip the record — one session, or all of a user's by
  iterating their own map. Account-level kill remains the admission
  layer (a disabled user fails admission on every request, whatever
  they hold). Sessions no longer depend on the venue key, so key
  rotation does not collaterally log everyone out. Session issuance, rotation
  and revocation use one authoritative writer and the transaction/durability
  rules below.
- **Venue boundary**: an opaque token is meaningful only to the authority
  holding its index. Replicas of that index are part of the same trust boundary;
  cloning a store does not automatically create a new session audience.
  An independent venue clone must invalidate sessions before serving requests.
  The JWT `aud` policy continues to apply to signed credential classes.
- **Restart / GC / migration**: index and records are venue lattice
  state, surviving ordinary restart. GC removes expired index/record pairs
  under the authoritative write policy; it must not permit stale state to
  reactivate a session. Restoring an older backup is a separate recovery
  operation: invalidate restored sessions before accepting traffic.
  Legacy venue-signed session JWTs retain their existing verification rules
  until expiry or an operator-configured migration cutoff, whichever comes
  first. They cannot gain immediate per-session revocation retroactively;
  retain no inferred elevated assurance or authentication time for them.
  A legacy token lacking origin metadata cannot satisfy an explicit
  originating-method/provider restriction. Its acceptance is limited to the
  default compatibility policy, not an inferred OIDC or key login.
  Stop minting them at cutover and document the remaining compatibility window.

**Atomicity, durability and recovery.** Session index and record changes commit
as one root transaction using existing Convex facilities. Persist issuance
before returning a token, and persist rotation/revocation before acknowledging
success. A rotation retires the old lookup and establishes the replacement in
that same transaction; concurrent logout or revocation cannot be undone by a
late reauthentication callback. An issued token that never reaches its client
may expire normally, but a token must never refer to a partially committed pair.
Initially one session has one live session credential. Rotation preserves its
non-secret session id while replacing the credential; a new independent login
creates a new session. Do not give multiple clients a shared session bearer.

The initial deployment has one authoritative session writer/verifier. An
eventually consistent replica must not independently serve authentication
decisions claiming immediate revocation. Recheck session validity at protected
actions; revocation cannot undo actions already accepted. Do not cache positive
verification across requests without coherent invalidation. Multi-node serving
requires an explicit upstream-supported consistency/failover contract first.

Restore and failover procedures must establish the authoritative state before
serving traffic and fence stale writers/merges. If the latest acknowledged
revocations cannot be established, invalidate sessions. A backup alone cannot
prove that no later revocation occurred. The restore procedure must also close
the legacy-session-JWT compatibility window; clearing opaque sessions alone
does not invalidate those JWTs. Test crash recovery and restore separately.

### 4.1 Delivery and protocol ownership

One internal session service does **not** imply one universal HTTP login or
token endpoint. Methods own their challenge/response ceremonies; transports
invoke the common verifier, optional session service and policy seam. Direct
key/UCAN requests and embedded callers work with the OAuth server disabled.

- The earlier catch-all `POST /api/v1/auth/login → {token, did, exp}` proposal
  is withdrawn. Native login/session-management routes use an explicit Covia
  contract when implemented; they do not pretend to be OAuth grants. Reuse
  method routes and upstream ceremonies instead of adding a new handoff protocol.
- OAuth clients use the existing `/oauth/authorize`, `/oauth/token` and
  `/oauth/revoke` interfaces, with standard discovery, parameters and errors.
  Interactive methods can satisfy authentication within that flow; password,
  OTP and WebAuthn inputs do not become custom OAuth grant types. Inspect and
  harden the existing implementation before reusing it for browser delivery.
- Browser redirects carry only transaction-bound, single-use codes, never
  session/access/refresh tokens. Bind the frontend exchange to the initiating
  client, exact registered redirect URI, browser transaction and S256 PKCE.
  The identity provider's PKCE leg does not replace frontend-to-venue binding.
  Consume codes atomically; discard outstanding codes on restart. Short expiry
  alone does not prevent an interceptor redeeming first. Keep codes/verifiers
  out of logs/referrers. #299 must specify email cross-device binding separately.
- Deliver bearer credentials in a protected response body with no-store
  caching. An embedder/BFF may instead hold the credential server-side and
  establish its own Secure/HttpOnly cookie with SameSite and CSRF protections.
  Cookie ownership is a transport decision, not a dependency of core auth.
- The proposed `POST /api/v1/auth/logout` revokes the presented managed session.
  Session list/revoke-all operations expose only safe identifiers and metadata
  under existing ownership/authorisation gates. OAuth token revocation is a
  separate operation; local logout does not imply logout at an external IdP.

### 4.2 Standards at protocol boundaries

| Boundary | Contract |
|----------|----------|
| OAuth token response | `access_token`, `token_type: "Bearer"`, `expires_in` (seconds); optional `scope` (space-separated) and `refresh_token` only when supported |
| OAuth requests/errors | Standard form-encoded token/revocation requests and JSON `error` / `error_description`; resource HTTP challenges use RFC 6750 and, for supported step-up requirements, RFC 9470 |
| OIDC provider evidence | Validated `iss`, `sub`, `auth_time`, `amr`, `acr` remain in OIDC method details; trusted mappings produce core evidence |
| JWT/OIDC serializers | `iat`/`exp`/`auth_time` use NumericDate seconds; `amr` is a string array; `acr` is a policy identifier, not a portable numerical rank |
| Session identifiers | Core `sessionId` is non-secret; expose OIDC `sid` only under an explicit issuer-scoped session/logout mapping, never as the bearer or token `jti` |
| General discovery (#367) | Enumerate enabled methods and their protocol-specific entry points; reference OAuth metadata rather than copying it or requiring OAuth for every method |

AMR mappings use registered values where applicable (`pwd`, `otp`, `mfa`,
`hwk`, `swk`, etc.) only with evidence. Registered `user` means presence,
not user verification. Email magic links do not automatically become `otp`.
Local assurance profiles are not provider `acr` values; any outward mapping
must define its semantics explicitly. OAuth/OIDC payloads keep their standard
spellings; no wholesale rename of private Java/config fields is needed.

Consuming OIDC does not make the venue an OpenID Provider. Adding that role
would require its own conformant HTTPS issuer, metadata and ID-token contract;
the existing DID-valued JWT issuer cannot simply be relabelled as an OIDC
issuer. ID tokens and API access tokens remain distinct credentials.

### 4.3 OAuth grants are separate from authentication sessions

An OAuth grant records a client's authorised access to a resource. Its
protocol-owned payload holds client, scope, audience, access/refresh-token
lifecycle and relevant consent, with references to the subject and, when
applicable, an authentication session/event. These are not universal fields
on `AuthResult` or the session record. Reuse existing records and add only
needed fields; a generic grant framework is not a prerequisite for #297.

The grant owner must enforce granted authority on use and renewal, and define
token revocation versus grant revocation versus session logout. If a grant
depends on a live session, check that dependency on access and refresh; if it
can survive logout, require explicit authorisation and independent revocation.
Never upgrade an already issued client credential merely because a linked
session subsequently steps up; bind its evidence to the issuance event.
Shared storage utilities do not permit accepting a grant token as an
unrestricted login-session credential.

`OAuthProvider` currently puts `scope` on its token for audit; resource-side
restriction is pending in #595, separately from #297's method-neutral refactor.
Likewise, outbound connected-account grants (§8.1)
remain separate from both login and the venue's authorization-server grants.

## 5. Methods

| `method` id | Credential | User mapping | Verified evidence | Session? | Issue |
|-------------|-----------|--------------|-----|----------|-------|
| `key` | Self-issued EdDSA JWT | `did:key` direct; named user via active registered key | Only what the verified protocol establishes; bearer presentation alone is not `pop` | No — stateless | #296 |
| `ucan` | UCAN bearer (aud = venue) | Issuer DID | No fresh possession or human authentication inferred | No — stateless | — |
| originating method, carried by `session` credential type | Opaque venue token (`covia_…`) | Index → per-user record | retained event, evaluated under current policy | Is the session | — |
| `oidc` | Authorization Code + PKCE | `(issuer, subject)` binding (§7) | provider-dependent, under explicit trust mapping | Optional continuity | #298 |
| `email` | Magic link / OTP | Verified address on the user record | Address control; no MFA or phishing resistance inferred | Optional continuity | #299 |
| `webauthn` | Passkey assertion | Registered credential id | Derived from verified flags and attestation; hardware never presumed | Optional continuity | #300 |
| `password` | Password (+ recovery) | Stored verifier | Password verification; no MFA inferred | Optional continuity | #301 |

Named-user and `did:key` self-issued login share the `key` method
deliberately: same credential class, same signature verification, same
verification path — a named user with a registered key is exactly as
self-sovereign as a bare `did:key` (§1.5). Policy that must distinguish
"venue-managed subject" from "raw did:key" keys off the *subject form*,
not the method.

### 5.1 Finishing #296 (self-sovereign named users)

The core is implemented and the acceptance-test sweep is in place
(`NamedUserAuthTest`, `UserAPITest`, `AudienceBoundAuthTest`): multiple
keys, rotation with immediate revocation, tombstone audit, last-key
guard, wrong key/subject/issuer/audience and foreign-`did:web`
rejections, temporal negatives, DID-document publication. #296 is
closed.

Two notes:

1. **Mismatched `iss` on `did:key` tokens is accepted by design**
   (covia#323, closed): identity binds to the *signing key* — a signed
   `sub` claim conveys no authority by itself, and a distinct named
   subject requires an explicitly registered authentication key.
   Enforcing `iss == sub` would conflate JWT claim semantics with
   Covia's separate subject-admission policy. (COG-3 §5's
   "MUST equal `sub` when present" wording is stricter than enforced
   behaviour — a docs follow-up may reconcile them.)
2. **Enrolment authorisation** (decided): add/revoke ops require the
   venue operator, or the user themselves meeting `policy.enrolment`
   via step-up (§9) — a live session alone is never enough to change
   the key set, since an enrolled authenticator outlives any stolen
   credential. Lands with the policy-enforcement phase (§10); until then the
   existing gate (the named user themselves, or a venue-rooted
   delegation) applies. `user:create` continues to install keys at
   creation under the creating authority.

## 6. Configuration

Method settings belong to their implementing adapter under `adapters.<name>`:
provider configuration, ceremony options, evidence mappings and any selection
among multiple methods that adapter contributes. The framework owns the
adapter's reserved `enabled` flag; a multi-method adapter owns its internal
method settings and validation. Reuse `configure`, enable/disable and module
load/unload rather than adding a second management surface.

The central `auth` block retains shared policy and session settings. Existing
`public`, `tokenExpiry`, `audience` and `acceptedAudiences` keep their current
meaning during migration. Policy may refer to method ids without owning their
implementation configuration.

Illustrative target configuration (these new method adapters and policy settings
are not yet implemented; the example assumes existing key/UCAN contributions):

```json
{
  "adapters": {
    "email":    { "enabled": false, "modes": ["magic-link", "otp"] },
    "webauthn": { "enabled": false, "rpId": "venue.example" },
    "password": { "enabled": false }
  },
  "auth": {
    "sessions": { "enabled": true },
    "policy": {
      "default": { "allowed": ["key", "ucan"] }
    }
  }
}
```

This minimal example enables existing stateless access only. Sensitive policies
are enabled with the enforcement phase, after an installed method can prove
their requirements. For example, `policy.enrolment` may require `strong` plus
`maxAuthenticationAgeSeconds:900`; a machine-oriented surface may instead
require `requiredProperties:["freshKeyPossession"]` with `maxProofAgeSeconds`.
These are separate requirements. `requiredProperties` names verified facts
from `authentication.properties`, such as `mfa`, `phishingResistant`,
`hardwareProtected` or `userVerified`. `freshKeyPossession` specifically requires
request-bound `proof` and its age check; it is never a persisted session fact.
Unknown properties fail validation.
The operator must have an attainable recovery/enrolment path
before enabling them; a plain key JWT cannot silently satisfy either freshness
requirement. The `allowed` list names originating methods, never `session`.

Rules:

- **Defaults** (no new configuration): current credential acceptance — `key`
  and `ucan` enabled; session acceptance enabled; `oidc` enabled iff providers
  are configured
  (legacy external-login entries under `auth.oauth` remain aliases for their
  matching owning-adapter configuration for one release). Do not alias the
  entire block: `auth.oauth.provider` configures the venue's OAuth authorization
  server and remains independent. Inventory legacy OAuth-only providers too;
  do not silently classify a non-OIDC provider as OIDC. Reject conflicting old
  and new settings. `email`/`webauthn`/`password` default off.
- **Disabled = absent**: routes not registered, credentials of that method
  rejected at the seam. Sessions retain the originating method, provider and
  binding: disabling any of those blocks their sessions too, even when the
  generic session verifier is enabled. Explicit revocation is permanent;
  disabling configuration suspends acceptance while disabled, so re-enabling
  may restore an otherwise active, unexpired session. Operators wanting a
  permanent logout revoke those sessions as well. `auth.sessions.enabled:false`
  blocks session issuance and acceptance, including legacy session JWTs during
  migration. The session service is not an originating login method or a way
  to bypass a method's `allowed` policy.
- **Fail closed at startup**: unknown method names, malformed provider
  blocks, non-HTTPS/unsafe redirect origins, and impossible policy
  (a policy whose `allowed` methods cannot reach its
  `minimumAssurance`) are configuration errors, not warnings.
  The same validation applies to proposed adapter reconfiguration. Runtime
  disable/unload still withdraws the method immediately: a policy referring to
  it cannot fall back to weaker evidence. Re-enabling validates configuration
  before restoring contributions. No second copy of enabled-method state is
  persisted under `auth` or in the lattice.
- **Secrets stay external**: provider secrets by `s/<name>` reference
  (SecretStore), never inline in tracked config.
- The **last viable strong authenticator** of a venue administrator
  cannot be removed without the explicit venue recovery path
  (`allowLast` — already enforced for keys; generalise per method).
  The host identifies accounts subject to this recovery safeguard; the
  authentication layer does not introduce a role system. Check viability
  against actual method evidence and enabled policy, not just key counts.
- **Policy names label surface classes, not user roles** (§9): `admin`
  means "surfaces the operator designates admin-grade", never a
  role held by users.

**Assurance profiles.** Retain three local names for policy convenience, with
no claimed equivalence to NIST AALs or OpenID `phr`/`phrh`. A method name or
signature algorithm alone does not establish a level above `standard`.

| Level | Minimum verified evidence |
|-------|---------------------------|
| `standard` | An accepted credential; no implicit human presence, MFA or phishing resistance |
| `strong` | Verified MFA, or a phishing-resistant protocol with verified user participation, under trusted method/provider policy |
| `hard` | Phishing resistance, hardware protection and user verification all established by the protocol and accepted attestation policy |

MFA and phishing resistance are distinct properties. A surface needing one
specifically must require that property in addition to a level; `strong` alone
does not imply both. A session retains the authentication event's evidence
for policy evaluation, but its bearer remains replayable if stolen. Fresh proof
is a separate request requirement. Plain `key` and `ucan` bearers have no
automatic elevation.

Method declarations specify attainable evidence, while each request must
actually present it. An OIDC provider's claims are accepted only under its
configured validation and assurance mapping. Hardware claims require evidence;
software/synced passkeys do not become hardware-bound by using WebAuthn.
Unsupported levels or required properties fail configuration validation. Do
not enable policies requiring proof mechanisms that are not implemented.

## 7. OIDC provider identity binding (#298 prerequisite)

OIDC logins bind by immutable `(issuer, subject)`, held in a
venue-owned index: `(iss, sub) → userId`, one user per pair. Email and
display name are mutable attributes on the user record, refreshed at
login (given `email_verified`), **never lookup keys**. Records already
holding `provider`/`providerSub` fields bind directly.

**Legacy email-only records**: `legacyEmailBinding:"confirm"` is the
default. Same-provider plus `email_verified` does not establish continuity:
addresses can be recycled or reassigned. A matching email is only a candidate
for a migration review; it must not authenticate the old account, disclose
its contents or silently create a conflicting binding. Confirmation requires
an independently authenticated existing owner or an authorised operator's
recovery procedure, not another proof of that same email address.

Automatic migration is limited to reliable, unambiguous existing
provider-subject bindings whose provider resolves to the validated issuer.
Ambiguous provider names/tenants and conflicting bindings require confirmation.
Commit a new `(issuer, subject)` binding and its account association atomically;
concurrent first logins cannot claim the same provider identity for two users.
Cross-provider email matches never link accounts; §8 requires proof of both sides.

**Embedder policy seam**: a trusted `BindingPolicy` evaluates
`(issuer, subject, email, emailVerified, candidate record)` and returns
`BIND | CONFIRM | REJECT`. Embedders may consult an authoritative migration
source, such as an HR directory, but must supply independent continuity
evidence to bind a legacy account. There is no built-in email-only auto-bind
mode. This is an OIDC binding extension, not a mandatory interface for every
authentication method. Other methods retain their own stable identity and
binding rules. Configuration selects the policy by name; unknown names fail closed.
Binding/confirmation decisions are audited without logging credentials.

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

### 8.1 Connected accounts are not linking

Linking adds a way to *log in* as the user. A **connected account** — the
`oauth` adapter, CONFIG.md "Connected accounts" — is the opposite direction:
a grant, obtained under the user's venue identity, to act on their data at a
provider. It never binds a provider identity to the venue user, is stored in
the user's secret store, and is consumed only by `http:*`
(`bearerSecret: "oauth/<provider>"`). The two share OAuth plumbing and
nothing else.

## 9. Assurance policy enforcement

One Engine-owned seam: `engine.requireAssurance(ctx, policyName)` —
resolves the request's originating method, assurance, authentication time,
required properties and any fresh possession proof against the named policy.
Protected venue surfaces (key enrolment, user admin,
config-affecting operations) name their policy at the point of action,
exactly like `requireCapability`. Embedders reuse the same call for
application routes.

**Step-up** (decided) — the standard sudo-mode pattern:

- The core checker returns `null` on success or the owning constant's
  human-readable reason on refusal, retaining the existing checks-return-reasons
  convention. The selected policy supplies the required evidence/freshness;
  the core constructs no HTTP headers or OAuth claims. Transports serialize
  the refusal in their own contract. An OAuth resource boundary uses RFC 9470
  `insufficient_user_authentication` with `acr_values` / `max_age` only where
  explicit mappings express the required policy. A method-specific proof
  requirement must not be disguised as an unrelated OAuth assurance class.
- Re-authentication is a re-run of the method with stricter parameters
  (OIDC: `max_age=0` / `prompt=login`, optionally `acr_values`;
  password/WebAuthn: complete that method's ceremony). Verify the returned
  subject matches the current principal (and session user, when present),
  the transaction is bound to the requesting context,
  and the actual evidence meets the requested policy. Sending `prompt=login`
  or requesting an `acr` is not itself proof that the provider complied.
- For managed sessions, successful reauthentication **rotates the session
  token**, atomically retiring the old lookup and persisting the new verified `authentication`
  event, including its properties and typed details, with the replacement.
  Deliver the new token through the selected safe transport; the client retries
  with it. Stateless callers instead supply new verified evidence without
  creating a session. Never upgrade a stolen old
  bearer along with the legitimate client's session. Preserve an auditable
  session lineage without exposing either token. A revoked session cannot
  be upgraded by a late callback; a new login is a separate transaction.
- `maxAuthenticationAgeSeconds` uses verified `authentication.authenticatedAt`;
  missing, future beyond allowed skew or stale evidence fails the freshness
  check. Token renewal must not reset it. `maxProofAgeSeconds` checks
  `proof.verifiedAt` for a verified, replay-protected possession proof; it
  implies no human authentication.
  Plain `key`/`ucan` bearer presentation satisfies neither automatically.
  Return a reason when a credential cannot meet a policy. Request fresh proof
  through an existing supported protocol; do not invent a local workaround
  or force machine clients through an interactive human login.

**Roles are out of scope** (decided): policies name *surface classes*,
never user roles — authentication proves an authenticator and grants
nothing (§1). Anything role-shaped is downstream authorisation, owned
by the application or embedder, which performs its own role check and
then calls this same seam with its own policy name. The venue enforces the
assurance a surface demands; who counts as an admin is never
venue-auth state.

## 10. Phasing

0. **Upstream/API audit and immediate protocol fixes.** Inventory the existing
   Convex APIs, `VenueAuthenticator`, `OAuthProvider` code/PKCE exchange and
   connected-account OAuth plumbing. Document what can be reused and request
   missing generic capabilities upstream. Assess #298's existing weaknesses
   for independently shippable fixes; do not defer a contained fix merely
   because the whole method registry is not ready. Reuse mechanisms while
   keeping login, connected-account grants and token issuance policies distinct.
   Keep OAuth grant enforcement as its own authorisation work item (§4.3).
1. **Phase 1 — common result, unchanged credential behaviour.** Extend
   `VerifiedPrincipal` into the common result and route existing verifiers
   through it using neutral identity/evidence fields and typed method payloads.
   Keep protocol claims and transport errors outside the core. Preserve
   acceptance rules, token formats, endpoints and current
   enrolment gates. Leave unknown evidence unknown. Add contract tests for all
   existing credential classes and embedders. Remove superseded verification
   glue; no new session issuer or enforcement switch ships in this phase.
2. **Phase 2 — adapter method contributions and policy evaluation.** Add the
   trusted adapter extension and derived verifier lookup, using existing
   adapter configuration/lifecycle for method settings and evidence mappings.
   Keep shared policy and sessions under `auth`; do not add `auth.methods`.
   Follow the grid-native ownership and operation contract in §3.1,
   including disable/unload and the unrecorded sensitive exchange boundary.
   Define originating-method versus credential-type handling.
   Preserve existing defaults when no new settings are supplied; explicit
   restrictions are intentional behaviour changes. Exercise the policy checker
   before enabling new sensitive-action requirements.
3. **Phase 3 — complete session lifecycle and safe delivery.** Ship opaque
   tokens with hashed lookup, atomic persistence, expiry, logout, per-session
   and per-user revocation, token rotation, restore invalidation and migration
   cutoff together. The service must work without OAuth. Any browser flow
   migrated at cutover must integrate its protocol's client-bound exchange
   and safe delivery; do not send the new token through the old redirect-query
   flow. Rehearse legacy JWT compatibility and rollback.
   If an upstream primitive needed for atomicity is unavailable, this phase
   waits for that capability rather than adding local replay machinery.
4. **Phase 4 — finish #298 and enable sensitive-action policy.** Complete
   provider state/PKCE/nonce validation, stable identity binding and conservative
   legacy migration. Enable enrolment/admin assurance gates only with tested
   attainable evidence, step-up and operator recovery. Separate OAuth fixes
   already shipped in earlier phases remain recorded under #298.
5. **Phase 5 — additional methods.** #299 email, #300 WebAuthn and #301
   passwords ship independently; passwords remain off by default. A proof
   mechanism required by an earlier phase may be brought forward with its
   own review. Do not enable an impossible policy while waiting for a method.

### Acceptance criteria for the changed contracts

- A custom adapter can contribute multiple methods without core edits or a
  parallel registration/configuration path. Duplicate ids, rejected config,
  disable/enable and unload are tested, including in-flight completions and
  dependent sessions; rejected config cannot partially replace live verifiers.
- Core contract tests exercise a trusted non-OAuth method, direct key/UCAN
  requests, and session issuance/verification with OAuth disabled. Core results
  require no OAuth client or protocol claims; direct paths create no session.
- Under the same method allowlist and trust policy, equivalent verified
  properties produce equivalent evidence-policy decisions across methods.
  Provider claims stay in typed details; missing/untrusted evidence
  remains unproven. No payload bypasses verifier, admission or capability gates.
- Replaying an accepted key/UCAN bearer never refreshes
  `authentication.authenticatedAt` or yields fresh possession. Missing/future
  timestamps fail policies that require them.
  An untrusted `amr`/`acr` claim or plugin method name cannot elevate assurance.
- Policies inspect the originating method of sessions. Disabled methods,
  providers and revoked bindings reject dependent sessions; re-enable versus
  permanent revocation follows §6. Existing admission/capability gates still run.
- Each protocol's code exchange is single-use under concurrency. Wrong client,
  redirect, browser transaction, PKCE verifier, expired code and replay fail.
  Cover the frontend-to-venue exchange separately from the provider leg.
- Protocol contract tests cover standard field names, types, time units,
  errors and discovery without imposing them on internal records. Config
  migration preserves the independent OAuth server and non-OIDC providers.
- OAuth grant tests separately cover enforced resource/scope restrictions,
  refresh narrowing, revocation and declared session dependency. A session
  step-up cannot silently elevate a previously issued client token.
- After successful step-up the old token fails, and a token stolen before
  step-up gains no assurance. A different provider subject cannot upgrade the
  session. Logout/rotation races and delayed callbacks cannot revive it.
- Crash/restart tests cover issuance, rotation and revocation acknowledgements.
  Index/record pairs remain atomic; persisted records contain no usable token.
  A restored pre-revocation backup, stale writer and independent store clone
  cannot serve restored sessions. Legacy JWT cutoffs and rollback are explicit.
- Provider subject continuity survives email changes. Recycled addresses and
  cross-provider email matches do not claim existing accounts; concurrent
  binding attempts preserve uniqueness and require independent confirmation.
- The first phase retains existing external behaviour. Subsequent phases
  document their API/config/store changes and test denied as well as accepted
  paths, including embedder-owned routes and recovery from invalid policy.

## 11. Security considerations

- **Existence privacy**: authentication errors never disclose whether a
  named account exists. A refusal does say *why* (covia#548) — which
  credential shape the verifier read, and the first check it failed:
  algorithm, `kid`, signature, `exp`/`nbf`, audience, key status — but
  the wording is chosen so that an unknown named user and an unregistered
  key read the same. Reasons describe the presented token only; anything
  that depends on venue state is checked after the signature, and on the
  named-user path the signature authenticates a statement issued by the
  `kid` key, not fresh possession by the presenter. Anyone may generate a key,
  so nothing venue-side is worded per user. A presenter of a validly signed
  token from a revoked key may be told that the key was revoked.
- **Method downgrade**: policy `allowed` lists are enforced at the
  seam, so an attacker cannot authenticate a high-value user via a
  weaker enabled method than policy permits for the surface.
- **Stateless credentials and revocation**: reusable `key`/`ucan` bearers
  have no general per-token revocation here. Named-user key revocation and
  account admission remain effective gates. Do not assume a short lifetime:
  it depends on validated expiry and configured limits. Session revocation
  applies to managed sessions, not arbitrary signed credentials or delegation
  proofs. The legacy session-JWT migration window has the same limitation.
- **Bounded lifetimes** (covia#534): a bearer credential must expire —
  `auth.requireExp`, on by default, refuses a self-issued JWT without `exp`
  or a UCAN bearer with `exp: null` — and `auth.maxTokenLifetime`
  optionally caps how far ahead one may expire (unset by default; a
  production venue sets it). Transport grants keep their own expiry rules;
  non-expiring delegations are the subject of #197.
- **Audit**: authentication records (key registry, provider bindings,
  session records) are venue-owned state; users cannot edit them via
  their own namespaces.

## Related

- `UCAN.md` — capabilities and the proof channel (authorisation layer)
- `CONFIG.md` — operator configuration reference (gains §6 on landing)
- COG-3 / COG-10 — token classes, self-issued rules, public capability scope
- covia#297 (this design), #296, #298, #299, #300, #301, #323, #269
- covia#367 (method/protocol discovery), #595 (OAuth grant enforcement)
- [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html) — client/transaction
  binding, PKCE and replay protection; a signed bearer alone is not a
  sender-constrained token
- [RFC 9470](https://www.rfc-editor.org/rfc/rfc9470.html) — step-up challenges
  and authentication time, which is not reset by token renewal
- [OpenID Connect Core](https://openid.net/specs/openid-connect-core-1_0.html#ClaimStability)
  — provider claim semantics and stable issuer/subject binding
- [RFC 6749](https://www.rfc-editor.org/rfc/rfc6749.html) — OAuth grants and
  token response formats belong to the OAuth boundary
- [RFC 6750](https://www.rfc-editor.org/rfc/rfc6750.html) and
  [RFC 7009](https://www.rfc-editor.org/rfc/rfc7009.html) — bearer challenges
  and token revocation, distinct from a universal session API
- [IANA AMR registry](https://www.iana.org/assignments/authentication-method-reference-values)
  — registered method references for protocol mappings, not a core method enum
- [NIST SP 800-63B](https://pages.nist.gov/800-63-4/sp800-63b.html#phishres)
  — phishing resistance requires protocol evidence; local assurance profiles
  in this design make no AAL-equivalence claim
