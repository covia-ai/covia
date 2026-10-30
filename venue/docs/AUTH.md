# Venue Authentication — Design (covia#297)

Configurable authentication methods and assurance policy for named venue
users. This is the design companion to covia#297 and its child issues
(#296 self-sovereign keys, #298 OIDC hardening, #299 email, #300
WebAuthn, #301 passwords). Registration/admission is separate (#269);
authorisation (capabilities, roles) is downstream and out of scope —
see `UCAN.md`.

Status: design reopened for consolidation 2026-10-22; earlier decisions and
implementation reviewed below. Section 2.1 takes precedence over the proposed
integration and rollout sequence elsewhere in this document.
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
| Adapter method bindings (Phase 2 subset) | `AuthenticationMethods`, `AuthMethods`, `auth.methods` | Explicit resolution, adapter lifecycle checks, transient `ACell` verification; new ceremonies remain pending |
| Evidence evaluation (Phase 2 subset) | `EvidencePolicy.allows(result, now)` | Method-independent requirements on a trusted result; policy configuration, trust mappings and action enforcement remain pending (§9.1) |
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
account-linking/migration rules; route-class assurance enforcement. Basic
issuer/subject binding is now implemented (§2.3).

### 2.1 Consolidation review (2026-10-22)

**Next work is consolidation, not policy configuration or request-context
integration.** The evaluator tests establish its local behaviour; they do not
validate the surrounding security architecture. No production caller currently
uses `EvidencePolicy`. Existing verifiers establish identity and credential
metadata, but do not populate event time, assurance properties or request proof.
The examples in §9.1 therefore exercise a contract, not an available step-up flow.

Keep the method-independent identity/result boundary, method-owned details,
adapter lifecycle and explicit binding selection. Keep authentication separate
from admission and capabilities. Challenge the following before extending them:

| Finding in the current implementation | Consolidation direction / unresolved decision |
|---|---|
| Built-in dispatch is `VenueAuthenticator -> AuthMethods -> AuthAdapter -> another VenueAuthenticator`. The inner service constructs its own resolver even though built-in verification bypasses it; ordinary bearer dispatch also parses the credential twice. | Separate built-in credential verification from dispatch/request binding with the smallest change possible. Keep one verification implementation and existing acceptance semantics; do not add a second auth framework or new crypto. |
| `EvidencePolicy` checks credential expiry while leaving binding, verifier and session validity to its caller. An unrestricted policy also accepts identity-only attribution. | It is an evidence predicate, not a complete authentication decision. Decide the owner and time of validity checks before introducing a general `requireAssurance` API. Prefer credential validity in the verification boundary; assess removal of the evaluator's partial expiry check as part of that decision. |
| `Proof` contains only a timestamp and properties; the verifier takes only a method id and method-owned input. | Request binding/replay protection is currently a trusted-verifier obligation, not an enforced core mechanism. Before copying proof into execution contexts, demonstrate a concrete verifier binding it to its intended request/action. An age check alone does not prove that binding. Do not add guessed generic nonce/request fields. |
| Policies accept arbitrary exact property names, while the target design promises validation of attainable evidence and central assurance caps. `AuthenticationMethods` declares verifier functions only. | Those promises are not implemented. Agree the meaning and trusted issuer of the first properties using a real method. Do not grow a declaration registry or assurance hierarchy merely to support speculative configuration validation. |
| `RequestContext` already wraps authority and has explicit propagation into nested operations, jobs and agents. `CapabilityGate` already evaluates operation-based conditions on grants. | Trace the first protected action through these existing paths before adding another propagation/enforcement system. Capability caveats only constrain the grants carrying them, so they cannot automatically substitute for a venue-wide evidence requirement. Decide where the mandatory action condition belongs and which evidence survives each transition. |
| The adapter revision check rejects verification across any adapter change, but returns no durable verifier/configuration provenance in the result. | This provides conservative in-flight invalidation only. It neither revokes returned results nor establishes session continuity across replacement. Keep that limit explicit; do not add per-method epochs or session migration machinery before a concrete consumer requires them. |
| `toData()` gives results and requirements immutable Convex representations, including a projection of request proof. | Representation is not persistence policy or portable trust. Define authoritative ownership, allowed reads/writes, and what may be persisted for each actual record. Never restore request proof as fresh evidence from a stored projection. No new lattice region is needed for this review. |

The `standard`/`strong`/`hard` profiles, `auth.policy` configuration and
`engine.requireAssurance(ctx, policyName)` remain proposals. They are not prerequisites
for pluggable authentication, and should not be treated as settled APIs. In
particular, the current conjunction-only evaluator does not implement the proposed
`strong` profile's alternatives. Decide whether any profile abstraction is needed
from actual action requirements, rather than introducing a policy language now.

**Review sequence:**

1. Consolidate built-in verification ownership and compatibility dispatch, using
   the existing credential and lifecycle tests to preserve behaviour. Inspect the
   installed Convex APIs before moving or replacing any verification logic.
2. Use the existing `user:authentication-add` action as the design case. Trace
   direct caller, venue-delegated administrator and agent paths; identify the threat,
   existing authority gate, required evidence, checking instant and supported
   upstream ceremony. Include delayed execution and replay. This is a design
   exercise, not permission to turn on an unattainable requirement.
3. Reassess the result and evaluator against that case. Remove or narrow unused
   abstractions where appropriate. Choose policy ownership and proof lifetime
   before deciding whether configuration or context changes are needed.

Runtime behaviour is unchanged by this review. The existing evaluator remains
isolated pending that reassessment. Session implementation and broad enforcement
are deferred; the later sections retain design material for review, not an
instruction to implement each proposed layer in order.

### 2.2 Basic verification audit (2026-10-22)

Reviewed the current signature/identity paths, audience and temporal checks,
method selection, adapter lifecycle and request binding against their regression
coverage. Two new negative tests failed before correction: supplied malformed
`exp` was treated as absent under `requireExp:false`, and subtracting clock skew
from `Long.MIN_VALUE` `nbf` overflowed. The same review found overflow in
`exp + skew`. Existing local checks now reject malformed supplied `exp`/`nbf`
and compare bounds without overflowing claim arithmetic. Missing/null expiry
still follows `requireExp`; existing 60-second skew boundaries are preserved.
This changes neither the method architecture nor evidence propagation.

After correction, `mvn test -pl venue -am` reports 102 core and 3,027 venue tests,
zero failures/errors and 47 skips. Focused regressions cover key and venue-signed
credentials, malformed bounds, extreme timestamps, the lifetime cap and leeway.
These are regression results, not certification of every authentication flow.

Convex 0.8.17 and current upstream `JWT.validateClaims` were checked before the
fix. Reusable explicit-clock/skew `exp`/`nbf` validation is requested in
[Convex #766](https://github.com/Convex-Dev/convex/issues/766), to replace existing
local duplication rather than grow a new validator. External provider/browser
login was still incomplete at that audit; the subsequent hardening is recorded
in §2.3. Managed-session revocation and evidence-policy integration remain
unimplemented.

### 2.3 External login consolidation (#298, 2026-10-22)

The concrete login flow is hardened independently of managed-session or
assurance design. Nimbus OAuth/OIDC SDK **11.38.2** supplies ID-token validation,
JWKS key selection/cache/refresh/rate limiting, secure state/nonce generation and
PKCE. Its sources were checked: the OIDC verifier covers required issuer,
subject, audience, expiry, issuance time and requested nonce; the standard JWT
verifier adds optional `nbf`. Covia supplies the client-specific `azp` check,
explicit provider configuration and local account mapping. The former local
`JWKSClient` is removed. Convex **0.8.17** still owns venue JWTs and immutable
lattice data; its generic temporal-validator request remains upstream #766.

Browser callbacks and direct RS256 provider-token verification share the same
strict provider validator and issuer/subject lookup. OIDC validation failure
never falls back to user-info. Microsoft requires a configured tenant UUID;
Google's two documented issuer spellings map to one canonical issuer. GitHub
remains OAuth-only and uses its stable numeric ID, without trusting `/user`
email as verified metadata.

New bindings live in a method-owned `externalIdentity: {provider, iss, sub}`
map on the venue-owned account lattice record. The account key is derived from
the canonical Convex vector `[issuer subject]` hash; metadata changes do not
change its DID. No email-based linking or automatic legacy migration occurs.
Explicit linking, a migration operation and multiple bindings per user remain
design work. Conflicting bindings are rejected.

The provider transaction uses browser-bound random state, S256 PKCE and an OIDC
nonce. Frontend handoff has a separate PKCE challenge, exact destination and
client state; only a single-use code appears in the return URL. Exchange rechecks
the issuer/subject binding and account before issuing a credential. Credentials
are returned as no-store JSON. Protocol transactions/codes are bounded, process-local
and expire on restart; their atomic consumption has one node as authority.
Replicating a CRDT map would not establish global single-use semantics. Durable
identity bindings remain lattice data. This refines the earlier proposed blanket
requirement to persist challenges in the lattice; distributed ceremonies need
an explicit authority/consumption design before being added.

`issueCredential` isolates the existing venue-bearer issuance for later session
integration. There is no new session service, `auth.policy`, evidence propagation
or permissive assurance stub. Provider `iat` is checked for token validity but
is not converted into human authentication time, MFA or request proof. Browser
login remains the existing provisioning/issuance transport, not a new pluggable
ceremony SPI. Runtime provider lifecycle, generalized adapter ceremonies,
managed-session revocation, multi-tenant Microsoft admission and assurance
mapping remain open. See CONFIG.md for breaking config/client migration.

Validation: the full core/venue run passed (102 core, 3,039 venue; zero
failures/errors, 47 skips). Subsequent consolidation passed 95 focused tests,
including 14 browser-flow regressions, OAuth-only user-info and identity changes
during frontend handoff. These use local HTTP providers and signed tokens.
The frontend now exchanges codes with browser-generated S256 PKCE, removes legacy
persisted bearers and keeps bearer credentials in memory until sign-out/reload.
Live Google/Microsoft/GitHub validation is assigned to Anu in
[#600](https://github.com/covia-ai/covia/issues/600); it has not been performed.

Upstream references: [Nimbus ID-token validation](https://connect2id.com/blog/how-to-validate-an-openid-connect-id-token),
[GitHub PKCE support](https://github.blog/changelog/2025-07-14-pkce-support-for-oauth-and-github-app-authentication/).


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
method ids, protocol fields or Java payload subclasses. After §2.3, provider
`iss` and `sub` are verified and must match an existing issuer/subject account
binding; email is never a lookup key.

Existing verifiers do not establish a human authentication event, MFA or a
replay-protected request proof. OIDC now checks `iat` for token validity, but
common issuance metadata remains conservative pending integration. Therefore
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

- Trusted adapter code contributes named authentication implementations,
  verifiers, evidence declarations
  and any method-owned ceremony operations through an authentication extension
  interface. `auth.methods` binds venue method ids to these implementations.
  Protocol configuration lives under `adapters.<name>` and uses the existing
  configuration and lifecycle hooks. One adapter may contribute several
  implementations; venue method ids are not adapter names. A method need not be HTTP-based,
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
  `AdapterState`, never caller-writable evidence. Reuse existing key
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
- Core resolves `auth.methods` against contributions from active adapters;
  this binding table selects implementations, not a second adapter lifecycle.
  References must resolve unambiguously to a trusted authentication extension,
  never an arbitrary operation. Implementation names are local to an adapter;
  duplicate names within that adapter fail validation. Reconfiguration
  validates the complete proposed bindings and contributions before publication;
  rejection leaves the previous configuration intact. Withdrawing a method takes effect
  at the authentication boundary even for an in-flight ceremony completion.
  Policy declarations must be validated against available methods before
  serving requests; unknown methods and unattainable requirements fail closed.

**Implemented binding subset (2026-10-21).** `AuthenticationMethods` is the
trusted adapter extension. Its immutable map contains locally named verifier
functions; declarations stay fixed for an installed instance. Configuration
changes behaviour through the existing adapter hook, while declaration changes
use adapter replacement. These Java functions are executable host code, not
lattice values; their inputs and result projections remain Convex data.

`AuthMethods` resolves the binding table against active adapters. Config shape
is checked eagerly; `Engine.launch` resolves references after the host assembly
hook and before recovery/workers/listeners. A manually assembled engine's host
can call `VenueAuthenticator.validateMethods()` once installation is complete.
Live replacement validates references before publishing the new adapter.
Verification runs outside the engine monitor and rejects completion if the
existing adapter revision changed, including disable/re-enable and successful
reconfiguration. This conservative check also rejects a verification overlapping
an unrelated adapter change; retry against the current configuration.

The built-in `auth` adapter supplies `keyJwt`, `ucanBearer` and `providerToken`,
delegating to the existing verifiers and Convex primitives. It is installed
during Engine preparation so low-level embedders use the same lifecycle.
`verify(token)` retains the existing bearer classifier and routes to method ids
`key`, `ucan`, `oidc`. `verify(methodId, ACell)` supports explicitly selected
custom methods and aliases without imposing a bearer format or recording a job.
The returned method must match the selected id. Trusted embedder attribution
through `bindIdentity` / `bindResult` remains a separate host-owned seam.

When `auth.methods` is omitted, compatibility bindings preserve current bearer
acceptance. Explicit selection rejects legacy venue JWTs with unknown login
origin, including those minted by existing browser login flows; it cannot
prove they came from an allowed method. The compatibility verifier is tied
to the active `auth` adapter and is not a selectable originating method.
No new login operation, evidence-policy enforcement, provider-config migration
or managed-session lifecycle ships in this subset. Those remain later work.

**Embedders plug in at the same contract.** An embedder may install an adapter
contributing additional implementations through the same interface and select
them through `auth.methods`. Each produces an `AuthResult`, owns its protocol
configuration under that adapter and is subject to the same shared policy,
session issuance and step-up rules as the built-ins. The binding selects the
venue method identity; the verifier cannot substitute a different method to
escape its policy. There is no independent adapter lifecycle under `auth`.
Installing adapter code is a trusted host action, not something an operation
asset can request. Each method
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
| `key` | Self-issued EdDSA JWT | `did:key` direct or nested installation identity; local named user via active registered key | Only what the verified protocol establishes; bearer presentation alone is not `pop` | No — stateless | #296 |
| `ucan` | UCAN bearer (aud = venue) | Issuer DID | No fresh possession or human authentication inferred | No — stateless | — |
| originating method, carried by `session` credential type | Opaque venue token (`covia_…`) | Index → per-user record | retained event, evaluated under current policy | Is the session | — |
| `oidc` | Authorization Code + PKCE | `(issuer, subject)` binding (§7) | provider-dependent, under explicit trust mapping | Optional continuity | #298 |
| `email` | Magic link / OTP | Verified address on the user record | Address control; no MFA or phishing resistance inferred | Optional continuity | #299 |
| `webauthn` | Passkey assertion | Registered credential id | Derived from verified flags and attestation; hardware never presumed | Optional continuity | #300 |
| `password` | Password (+ recovery) | Stored verifier | Password verification; no MFA inferred | Optional continuity | #301 |
| adapter name (`telegram`, `slack`, `whatsapp`, `discord`) | Provider-delivered message (`signed-callback`, `provider-session`) | Binding identification (§5.3) | The provider's sender identity; no human authentication time | No — per message | — |

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
   `sub` claim conveys no authority by itself. A local named subject
   requires an explicitly registered authentication key; an installation
   identity must be nested below the signing key's exact DID (section 5.2).
   Enforcing `iss == sub` would conflate JWT claim semantics with
   Covia's separate subject-admission policy. (COG-3 §5's
   "MUST equal `sub` when present" wording is stricter than enforced
   behaviour — a docs follow-up may reconcile them.) One subject is
   refused whoever signs for it: the venue's public principal
   (`<venueDID>:public`) is a shared authorisation namespace, not an
   identity, so no self-issued or venue-signed credential authenticates
   as it.
2. **Enrolment authorisation** (decided): add/revoke ops require the
   venue operator, or the user themselves meeting `policy.enrolment`
   via step-up (§9) — a live session alone is never enough to change
   the key set, since an enrolled authenticator outlives any stolen
   credential. Lands with the policy-enforcement phase (§10); until then the
   existing gate (the named user themselves, or a venue-rooted
   delegation) applies. `user:create` continues to install keys at
   creation under the creating authority.

### 5.2 Nested installation identities below a did:key (#613)

An installation can sign a token for any identity nested below its exact
`did:key:<key>`, including `:u:alice`, `:u:alice:g:worker`, `:g:worker`,
and custom forms such as `:org:acme:service:build:job:42`. Nesting has no
fixed depth. Each colon-separated segment must be nonempty and match the
existing username character set `[A-Za-z0-9._-]+`. Empty segments, paths,
queries, fragments and percent-encoded suffixes are refused.

This is a Covia naming convention, not a standard resolvable `did:key`
namespace. It proves that the installation identifies the subject in its
scope, not that the subject independently holds a key. Identity establishes
who is acting; admission and capabilities separately authorize actions.

Issue the bearer with the existing client helper:

```java
VenueAuth.namedKeyPair(installKey, installDID + ":u:alice:g:worker", targetDID, 300).mintToken();
```

The signing key must match the exact base DID. Existing signature, audience,
expiry and not-before checks still apply. As for a bare `did:key`, the
`iss` claim does not confer authority. No subject register is needed for
authentication at the receiving venue. This does not extend UCAN issuer
resolution or grant an installation authority over descendant resources.

`VenueAuthenticator.authenticatedIdentity(context)` returns the installation's
`did:key`; `authenticatedUser(context)` and `AuthResult.userDID()` retain the
complete subject, including any agent segments. The latter accessor names
are compatibility APIs and do not imply admission as a local user. Relay
handlers likewise receive the complete subject for their own authorization.
Installation membership and grants do not transfer to descendants or siblings.

For agent-shaped subjects, `AuthMiddleware.callerContext` binds both actor
and namespace to the full subject and starts with an empty capability scope.
It does not infer local agent execution or owner access from the name, even
for ownership-only job checks. Verified transport grants can authorize the
exact agent audience for specific resources; a sibling cannot reuse them.
Trusted host code must separately authorize any owner-namespace execution.
Internal local-agent contexts still use their established owner mapping and
configured capabilities.

Identity-only routes can authenticate these agents. Native routes requiring
an admitted user still reject agent-shaped subjects; authentication does not
register agents as users or bypass that admission policy. Ordinary nested
users retain their independent admission and own-namespace behavior. Embedders
must not replace the subject with the signing installation or strip name
segments when constructing authority.

### Minting an identity token through an operation

Call `v/ops/auth/identity-token` on the signing venue:

```json
{"aud":"did:web:target.example","name":"RELAY_TOKEN","lifetime":300}
```

The operation derives `sub` and `iss` from the exact caller, including an
agent's full nested identity. It accepts no subject, claims, capabilities or
signing key from input. The caller must be the venue's signing `did:key` or a
valid identity nested below it. An unrelated external key identity, a
`did:web` identity, and anonymous/public callers are refused; an embedder
holding a different installation key uses `VenueAuth.namedKeyPair` instead.
The caller must also be acting under its own authority or as a sub-principal.
A restricted scope on a principal acting as itself bounds what the request may
do, and a bearer for that identity would carry it beyond the bound, so the
operation refuses that shape. A sub-principal (an agent, a delegate) mints only
its own identity, which the venue bounds again wherever it is used.

It requires `invoke` on `v/ops/auth/identity-token` and `secret/write` on
`s/RELAY_TOKEN`. Agents write into their owner's secret store under their
existing capability scope. The bearer is encrypted there; the result contains
only `{secret, sub, aud, exp}`, never the token or private key. An agent can
use `s/RELAY_TOKEN` as a model relay `apiKey`, subject to its separate
`secret/use` grant and any destination restriction. A direct owner who needs
the raw bearer can use `v/ops/secret/extract` under that operation's stricter
rules.

The audience is required. Lifetime defaults to 300 seconds and must be an
integer from 1 to 3600. Refresh by invoking again with `overwrite:true` before
expiry; there is no automatic refresh. The receiver still applies its own
audience, lifetime and admission policies. The token conveys identity only:
it contains no UCAN grants or proofs and adds no permissions.

### 5.3 Channel methods (messaging bindings)

A messaging binding — a Telegram or Discord bot, a Slack app installation, a
WhatsApp number — is the authentication method for the messages it receives
(COG-3 §7). The provider authenticates the sender; the binding verifies
delivery, identifies the principal and produces an `AuthResult` like any other
method. The request then runs as that principal, exactly as an HTTP request runs
as its credential's user.

**The result.**

| Field | Value |
|-------|-------|
| `userDID` | The identified principal: the binding's principal for a listed sender, the venue public principal for any other sender on an open binding |
| `authenticatedIdentity` | `<method>:<sender id>`, the id qualified as `<space>/<id>` where the provider scopes sender ids to a space (Slack: `slack:T0123/U0456`) |
| `credential.type` | `signed-callback` (WhatsApp, the Slack Events API) or `provider-session` (Telegram polling, the Discord gateway, Slack Socket Mode) |
| `authentication.method` | The adapter's name |
| `authentication.binding` | The binding's resource, `<ownerDID>/<adapter>/<bot>` |
| `authentication.authenticatedAt` | Absent: a message's time is when it was sent, not when its sender authenticated (§3) |
| `authentication.properties` | Empty |
| `authentication.details` | The channel (COG-3 §7): `sender {id, handle?, name?}`, `chat {id, kind, name?}` when sent in a chat, `thread?`, `space {id, name?}?`, `message?`, and provider extras under the adapter's name |
| `proof`, `sessionId` | Absent |

An open binding's other senders authenticate as the venue public principal. The
identity invariant (§1.1) holds for that principal, a stable DID, while
`authenticatedIdentity` keeps each sender distinct. Identification grants
nothing: capabilities and agent admission decide what the principal may do.

**Identification is shared.** `ChannelAuthentication.authenticate` in
`covia.adapter.messaging` builds the result for every messaging adapter from
the binding, its principal, whether it lists the sender and the channel, and
refuses a sender the binding neither lists nor is open to (`NOT_ADMITTED`);
`Engine.channelContext` refuses the public principal when the venue serves no
anonymous callers (`PUBLIC_ACCESS_OFF`). `ConversationRouter.context(channel)`
is the one call adapters make. An event with no sender, such as a Telegram poll
update, is anonymous with no record on an open binding and refused otherwise.
Each adapter does only what is provider-specific:

1. verify delivery — the callback signature and replay window, or its own
   authenticated connection — and that the message is for this binding's
   account;
2. build the `Channel` value (method, credential type and the standard details)
   from the verified payload, ids as strings, never from message text;
3. answer whether its binding lists the sender (`lists`), by the provider's
   stable id or, where the provider restricts and reassigns handles, by handle.

The binding supplies its principal (the owner, or the agent that created it)
and whether it is open. Restricting where a binding serves, such as Slack's
`allowChannels`, is the adapter's admission of a chat, not identification.

**The request context.** `Engine.channelContext(AuthResult)` is
`principalContext(userDID)` with the result's data projection attached by
`RequestContext.withAuthentication`. Only that seam sets it; an HTTP request's
result stays on its HTTP context. The projection is attribution, not evidence: assurance policy
never reads it from a context (§3.1), and contexts the venue starts for an
agent's own work (`ofAgent`, `principalContext`, the scheduler's replay) carry
none, so nothing is inherited through jobs or agent delegation. A bot's own work
for its agent — storing media, its media and reply operations — runs in such a
context.

**Recorded with the turn.** The agent's ingress (`agent:chat`, `agent:message`,
`agent:request`) writes the request's `caller` and its `authentication` — the
projection without `userDID`, which is the caller — onto the session envelope
and the task record, from the request context only. Message content is never
read for or rewritten with provenance. Both runtimes (the adapter's cycle merge
and `FrameStore.cycleInputTurns`) copy both fields onto each user turn, and the
task wire shape carries them, so a turn keeps its provenance for as long as
history keeps the turn (COG-20 §4).

**Rendered from the record.** `ContextAssembler` renders a `From` label element
(`Labels.Kind.FROM`, AGENT_CONTEXT.md §1.2) in the agent's dialect:

| Dialect | Owner over the API | Open-bot sender |
|---------|-------------------|-----------------|
| `bracket` | `[From: owner did:key:z6MkAlice]` | `[From: anonymous did:web:venue.example:public — telegram helper: sender 5551 @sam, direct chat 5551]` |
| `xml` | `<from principal="did:key:z6MkAlice" relationship="owner"/>` | `<from principal="…:public" relationship="anonymous" method="telegram" binding="helper" sender="5551" handle="sam" chat="5551" chat-kind="direct"/>` |

- **When.** Before a user turn whose key — caller, method, binding,
  authenticated identity, chat id, thread, space id — differs from the last key
  rendered, starting from the agent's own principal. Per-message fields
  (`message`, adapter extras) are never part of the key. A user turn without a
  recorded caller renders no element and leaves the key unchanged.
- **What.** The relationship is computed from DIDs alone: the agent's own DID,
  its owner's and the venue's. No venue state is consulted, so registering or
  deleting a user never changes an earlier element. The channel part shows the
  method, the binding's name, ids, the chat kind, a thread and a space id.
  `name` fields are never rendered (COG-3 §7). Ids and handles render only when
  they match a conservative identifier pattern; xml values are escaped.
- **Cache.** The element is a pure function of the stored turns and the three
  DIDs, and history is append-only within a frame, so each cycle's rendered
  conversation extends the previous cycle's unchanged. Changing the element's
  format rebuilds every session's prefix once.
- **Tasks.** The outstanding-task message renders its requester through the same
  element.

**Released wires.** A relay binding identifies no principal; it forwards
messages to the principals linked to it, and its wire projects the details onto
its released fields (`channel` = method, `from` = `sender.id`, `chat` =
`chat.id`, `thread`). A binding's media and reply hooks (`mediaOperation`,
`replyOperation`) receive the record as `authentication`. A webhook message
accepted before its binding recorded the channel with it is failed once rather
than run unauthenticated (`WebhookBot.UNVERIFIED`).

**Tests.** A forged record in message content stays content; listed, open,
closed and public-access-off senders identify as specified for every adapter;
envelope, task record and history turns keep `caller` and `authentication` in
both runtimes; rendering a session twice, with a turn added in between, leaves
the earlier output unchanged, also across a user's registration; `name` fields
and malformed handles never render; the relay wire is unchanged; an agent's own
contexts carry no `authentication`.

## 6. Configuration

`auth.methods` is a binding table from venue method ids to trusted adapter
implementations. Each entry has an `adapter` reference and, when the adapter
contributes more than one implementation, an `implementation` selector using
that adapter's local name. Omit the selector only when exactly one implementation
is contributed. These references do not name operation assets or load code.

Protocol settings belong to their implementing adapter under `adapters.<name>`:
provider configuration, ceremony options and evidence mappings. The framework
owns the adapter's reserved `enabled` flag; a multi-method adapter owns its internal
method settings and validation. Reuse `configure`, enable/disable and module
load/unload rather than adding a second management surface.

The central `auth` block owns method bindings, shared policy and session settings.
Existing `public`, `tokenExpiry`, `audience` and `acceptedAudiences` keep their current
meaning during migration. Policy may refer to method ids without owning their
implementation configuration.

Illustrative target configuration (adapter names and selectors below are examples,
not shipped implementations or configuration):

```json
{
  "adapters": {
    "identity": { "enabled": true },
    "passkeys": { "enabled": true, "rpId": "venue.example" }
  },
  "auth": {
    "methods": {
      "key": { "adapter": "identity", "implementation": "key-jwt" },
      "ucan": { "adapter": "identity", "implementation": "ucan-bearer" },
      "webauthn": { "adapter": "passkeys" }
    },
    "sessions": { "enabled": true },
    "policy": {
      "default": { "allowedMethods": ["key", "ucan", "webauthn"] }
    }
  }
}
```

This example selects two implementations from one adapter and the sole
implementation from another. A binding does not enable or load its adapter;
an active adapter does not expose all its implementations for authentication
merely because they are installed. Sensitive policies
are enabled with the enforcement phase, after an installed method can prove
their requirements. For example, `policy.enrolment` may require `strong` plus
`maxAuthenticationAgeSeconds:900`; a machine-oriented surface may instead
require `requiredProofProperties:["freshKeyPossession"]` with `maxProofAgeSeconds`.
These are separate requirements. `requiredProperties` names verified facts
from `authentication.properties`, such as `mfa`, `phishingResistant`,
`hardwareProtected` or `userVerified`. `freshKeyPossession` specifically requires
request-bound `proof` and its age check; it is never a persisted session fact.
The evaluator matches exact property names without a central method/property
registry. Future configuration validation must check declared attainable evidence;
at evaluation time, a property not established by the verifier always fails.
The operator must have an attainable recovery/enrolment path
before enabling them; a plain key JWT cannot silently satisfy either freshness
requirement. The `allowedMethods` list names originating methods, never `session`.

Rules:

- **Binding resolution**: a supplied `auth.methods` object is the complete
  selection, not an overlay on defaults; `{}` selects no methods. Omission
  preserves legacy behaviour through explicit compatibility bindings during
  migration. Reject unknown adapters/selectors, ambiguous omitted selectors,
  references to unavailable adapters and adapters with no authentication
  extension. A policy can reference only selected method ids. No `enabled`
  switch or protocol settings are duplicated inside a binding.
- **Binding changes**: removing a binding withdraws that method. Changing its
  target must not reinterpret old authentication evidence or adopt existing
  sessions merely because the method id is unchanged. Retain verifier/binding
  provenance and reject dependent state unless an explicit safe migration
  establishes continuity. In-flight completion checks the current binding.
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
  to bypass a method's `allowedMethods` policy.
- **Fail closed at startup**: unknown method names, malformed provider
  blocks, non-HTTPS/unsafe redirect origins, and impossible policy
  (a policy whose `allowedMethods` cannot reach its
  `minimumAssurance`) are configuration errors, not warnings.
  The same validation applies to proposed adapter reconfiguration. Runtime
  disable/unload still withdraws the method immediately: a policy referring to
  it cannot fall back to weaker evidence. Re-enabling validates configuration
  before restoring contributions. The configured binding remains a reference;
  it cannot keep an unloaded adapter live or restore it implicitly. The resolved
  dispatch lookup is derived, not independently persisted in the lattice.
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

**Assurance profiles (future convenience only).** Three proposed local names, with
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

Proposed enforcement seam (under review, §2.1): `engine.requireAssurance(ctx, policyName)` —
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
  missing, future or stale evidence fails the freshness
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

### 9.1 Implemented evidence evaluator

`EvidencePolicy` is a small immutable requirements record. Trusted host code calls
`policy.allows(result, now)` with a freshly verified `AuthResult` and the current
Unix time in seconds. It returns `null` or an owning constant's refusal reason.
It has five fields:

| Field | Requirement |
|---|---|
| `allowedMethods` | Originating method must be in the set. Null leaves origin unrestricted; an empty set permits none. Credential type and session id do not substitute for origin. |
| `requiredProperties` | All named facts must be in `authentication.properties`. |
| `requiredProofProperties` | All named facts must be in this request's `proof.properties`. |
| `maxAuthenticationAgeSeconds` | Optional maximum age of verified `authentication.authenticatedAt`. |
| `maxProofAgeSeconds` | Optional maximum age of this request's `proof.verifiedAt`. |

All supplied requirements must pass. The two property sets are required, possibly
empty, immutable Convex sets. Property names are exact non-blank strings; a custom
method or agreed property needs no evaluator changes. Common meaning is a contract
between the trusted verifier and the policy author. The checker does not inspect
`details`, interpret protocol claims, infer assurance levels, combine unrelated
events or copy facts between event and proof. The proposed profile names in §6
are not implemented as ranks or built-in shortcuts.

Age limits are non-negative; null means no age requirement. Zero requires the
same Unix second. The age boundary is inclusive; missing, negative or future times
fail a requested freshness check, with no implicit clock-skew allowance. Known
credential expiry is exclusive and always checked (`now >= expiresAt` fails).
Credential issuance/expiry never supplies an authentication or proof time. Direct
methods need no credential record. Both property checks and age checks are explicit:
proof properties alone do not impose an age limit, and proof age alone does not
require a particular property.

For example, a host can require MFA within 15 minutes and separate possession
proof within 30 seconds:

```java
var policy = new EvidencePolicy(null,
    Sets.of(Strings.create("mfa")),
    Sets.of(Strings.create("freshKeyPossession")), 900L, 30L);
String reason = policy.allows(verifiedResult, nowSeconds);
```

`toData()` projects the requirements directly to a Convex map with sets and integer
seconds. It preserves absent versus empty method selection. This is data for the
grid, not a new JSON policy language, job operation or evidence decoder.

This is a pure evidence check. The trusted caller still owns current verifier,
binding, session and revocation checks and must never reuse request proof on a
different request. Missing results fail; an unrestricted policy can accept an
identity-only result because it demands no additional evidence. Success neither
establishes credential validity nor grants access. Admission and capability checks
remain separate. `auth.policy` configuration, named profiles, Engine/context
integration, step-up and sensitive-action gates remain future work, enabled only
once their evidence and recovery paths are attainable.

## 10. Phasing

The consolidation review in §2.1 precedes this earlier rollout proposal. Phase
numbers do not imply that policy configuration or enforcement is the next step.

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
   Resolve the small `auth.methods` binding table against those contributions;
   keep protocol settings under adapters and shared policy/sessions under `auth`.
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

- A custom adapter can contribute multiple implementations without core edits;
  `auth.methods` can select them explicitly. Test unknown and ambiguous references,
  absent versus empty selection, duplicate local ids, rejected config,
  disable/enable and unload, including in-flight completions and
  dependent sessions; rejected config cannot partially replace live verifiers.
  Changing a binding target cannot transfer old evidence or sessions implicitly.
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
