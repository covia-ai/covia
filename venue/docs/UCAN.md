# UCAN Design — Lattice-Native Capabilities for Covia

Design for User Controlled Authorisation Networks (UCAN) in Covia, using lattice data structures instead of IPLD/DAG-CBOR.

**Status:** Draft — March 2026

---

## 1. Principles

1. **Consistent with UCAN spec.** Same conceptual model: issuer/audience, attenuated capabilities, delegation chains, cryptographic signatures. Where Covia diverges from the UCAN spec, it is because the encoding uses CAD3/lattice rather than IPLD/DAG-CBOR — not because the authorisation model differs.

2. **Lattice-native.** UCANs are first-class lattice values, stored as content-addressable data in `/a/`. The CAD3 form is canonical — its value hash is the UCAN's identifier — and UCANs merge, replicate, and verify like any other lattice data. For interoperable *transport* (HTTP `ucans` arrays, bearer headers, cross-venue relay) the standard JWT encoding is used (§4.3); the two encodings carry the same token.

3. **Self-contained.** A UCAN plus its proof chain is sufficient for verification. No callbacks, no token servers, no online authority. This is critical for federated execution where the verifying venue may have no relationship with the issuer.

4. **Attenuation only.** Delegation can only narrow capabilities — never widen. Each link in the chain must be equal or more restrictive than its parent in both resource scope and command scope.

5. **DID-native.** Resource URIs are DID URLs. The DID identifies the authority (user), the path scopes into their lattice namespace. This aligns naturally with Covia's per-user namespace model.

---

## 2. UCAN Structure

A UCAN is a CVM map with the following fields:

```
{
  iss: "did:key:zAlice..."              ; Issuer DID — signs this token
  aud: "did:key:zBob.../g/helper"       ; Audience DID — receives the capability
  att: [                                ; Attenuations — array of capabilities
    { with: "did:key:zAlice.../w/", can: "crud/read" }
  ]
  exp: 1719500000                       ; Expiry — Unix timestamp
  nbf: 1719400000                       ; Not Before — Unix timestamp (optional)
  nnc: "a1b2c3d4e5"                     ; Nonce — replay prevention (optional)
  fct: [{ grid-version: "1.0" }]        ; Facts — signed assertions (optional)
  prf: [<hash1>, <hash2>]              ; Proof chain — CAD3 hashes of parent UCANs
  sig: 0xdeadbeef...                    ; Ed25519 signature over CAD3 hash of content
}
```

### 2.1 Field Semantics

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `iss` | AString (DID) | Yes | Issuer. The DID of the principal signing this UCAN. Resolves to an Ed25519 public key for signature verification. |
| `aud` | AString (DID/DID URL) | Yes | Audience. The DID (or DID URL for agent-scoped grants) of the intended recipient. |
| `att` | AVector of maps | Yes | Attenuations. Each entry is a `{with, can}` capability pair, optionally with `nb` constraints. |
| `exp` | CVMLong | Yes | Expiry. Unix timestamp in seconds. Token is invalid after this time. Use `0` for no expiry (permanent grants). |
| `nbf` | CVMLong | No | Not Before. Token is invalid before this time. Omit for immediate validity. |
| `nnc` | AString | No | Nonce. Unique value for replay prevention. |
| `fct` | AVector of maps | No | Facts. Additional signed metadata (grid version, venue context, etc.). |
| `prf` | AVector of Hash | Yes | Proof chain. CAD3 hashes referencing parent UCANs stored in `/a/`. Empty vector for root grants. |
| `sig` | Blob | Yes | Ed25519 signature over the CAD3 value hash of all fields except `sig`. |

### 2.2 Encoding: CAD3 Instead of DAG-CBOR

Standard UCAN uses DAG-CBOR (IPLD) for canonical encoding and DAG-JSON for transport. Covia replaces both with **CAD3** — the Convex canonical encoding format:

| UCAN Spec | Covia Equivalent |
|-----------|-----------------|
| DAG-CBOR canonical encoding | CAD3 canonical encoding |
| CID (content identifier) | CAD3 Value Hash (SHA3-256) |
| IPLD schema types | CVM data types (AMap, AVector, AString, CVMLong, Blob) |
| DAG-JSON transport | CVM JSON serialisation |

The mapping is direct because both DAG-CBOR and CAD3 are:
- Deterministic (same data = same encoding = same hash)
- Self-describing (type information in the encoding)
- Content-addressable (hash of encoding = identifier)

### 2.3 Signature

The signature covers the CAD3 value hash of the UCAN content — all fields except `sig` itself. This is computed as:

```
content = { iss, aud, att, exp, nbf, nnc, fct, prf }    ; UCAN without sig
hash    = SHA3-256(CAD3(content))                         ; canonical hash
sig     = Ed25519.sign(issuer_private_key, hash)          ; signature
```

Verification resolves the issuer's DID to an Ed25519 public key and verifies the signature against the same hash.

---

## 3. Capabilities

Each capability is a `{with, can}` pair, optionally with `nb` (constraints).

### 3.1 Resources (`with`)

Resources are **DID URLs** — the DID identifies the authority (user/owner), the path scopes into their lattice namespace:

| Resource URI | Scope |
|-------------|-------|
| `did:key:zAlice...` | Everything in Alice's namespace |
| `did:key:zAlice.../w/` | All workspace data |
| `did:key:zAlice.../w/projects/foo` | Specific workspace key |
| `did:key:zAlice.../o/` | All operations |
| `did:key:zAlice.../g/helper` | Specific agent |
| `did:key:zAlice.../s/api-key` | Specific secret |
| `did:key:zAlice.../dlfs/docs/` | All files on Alice's `docs` DLFS drive |
| `did:key:zAlice.../dlfs/docs/reports/q1.md` | Specific file on that drive |

**Attenuation rule:** A resource URI is a valid attenuation of a parent if it is equal to or a sub-path of the parent. `did:.../w/projects/foo` attenuates `did:.../w/`.

**DLFS is a DID-scoped namespace.** A DLFS drive is addressed as `<ownerDID>/dlfs/<drive>[/<path>]` — a plain DID-URL path where `/dlfs/` is a namespace segment alongside `/w/` and `/j/` (the CAD038 DID-scoped path profile; `RootAuthorityPolicy.ownerDID` derives the owner with no special cases). A caller reaches another user's drive by naming it as a DID-URL `drive` reference (`did:key:zAlice.../docs`); the venue authorises the op against this resource for the ability it needs (`crud/read` for reads, `crud/write` for writes, `crud/delete` for deletes) via the presented proofs (the same `proofsCover` gate as `/w/`). Reads, writes and deletes are all permitted when the proof authorises them; a mutation lands on the owner's drive under the owner's key (custodial), with the caller's identity recorded on the job. An agent's *own* drive is the bare `dlfs/<drive>/<path>` shorthand, canonicalised to `<callerDID>/dlfs/…` like any bare lattice path — so own-drive caps and cross-user grants never alias. The legacy scheme form `dlfs://<drive>/<path>` remains accepted as an own-drive shorthand (normalised to the path form at enforcement); cross-user grants use the path form only. A caller-supplied `asset` reference in a cross-user write resolves under the *caller's* authority, never the drive owner's.

This aligns with `DIDURL` from convex-core — the DID is the authority, the path is the namespace scope.

**Canonical vs. shorthand.** A `with` is always absolute (owner-named) as above. For convenience, Covia accepts a **bare** lattice path (`w/projects/foo`) as shorthand for the *caller's own* resource — this is how an agent's `caps` are written. Enforcement canonicalises it to the absolute form (`<callerDID>/w/projects/foo`) before matching, so a bare (own-namespace) grant and a DID-URL grant compare identically. The same applies to bare `dlfs/<drive>/…` paths. Only `file://…` resources are scheme-qualified (host-filesystem, not DID-scoped) and are left unchanged.

### 3.2 Abilities (`can`)

Abilities follow UCAN's slash-delimited convention with no leading slash. `*` is the top ability that proves everything. Shorter abilities prove longer ones (prefix hierarchy):

| Ability | Proves | Meaning |
|---------|--------|---------|
| `*` | everything | Full delegation |
| `crud` | `crud/read`, `crud/write`, `crud/delete` | All data operations |
| `crud/read` | — | Read data |
| `crud/write` | — | Write data |
| `crud/delete` | — | Delete data |
| `invoke` | `invoke/async` | Execute operations |
| `invoke/async` | — | Fire-and-forget execution |
| `agent` | every `agent/*` | All agent operations |
| `agent/create` | — | Create a new agent |
| `agent/request` | — | Submit a request task to an agent |
| `agent/message` | — | Send message to agent session |
| `agent/fork` | — | Fork an agent |
| `asset` | every `asset/*` | All asset operations |
| `asset/store` | — | Store a new content-addressed asset |
| `asset/read` | — | Get / list content-addressed assets |
| `secret/decrypt` | — | Decrypt a secret |
| `ucan/delegate` | — | Sub-delegate capabilities |
| `ucan/revoke` | — | Revoke a UCAN |

**Attenuation rule:** An ability is a valid attenuation of a parent if it is equal to or has the parent as a prefix. `crud/read` attenuates `crud`. `*` proves any ability.

### 3.3 Constraints (`nb`)

Optional per-capability constraints as a map:

```
{ with: "did:.../w/", can: "crud/read", nb: { maxSize: 1000000 } }
{ with: "did:.../o/langchain:openai", can: "invoke", nb: { rateLimit: 100 } }
```

Constraint semantics are application-defined. The UCAN infrastructure verifies attenuation (child constraints must be equal or stricter), but interpretation is delegated to the enforcing adapter.

### 3.4 Risk Hierarchy

| Capability | Risk |
|-----------|------|
| `{with: "did:.../o/", can: "crud/read"}` | Low — inspecting available operations |
| `{with: "did:.../w/key", can: "crud/read"}` | Medium — reading specific data |
| `{with: "did:.../o/op", can: "invoke"}` | Medium — consumes compute |
| `{with: "did:.../w/", can: "crud/write"}` | High — mutating workspace |
| `{with: "did:.../s/key", can: "secret/decrypt"}` | Highest — reveals plaintext credentials |
| `{with: "did:...", can: "*"}` | Maximum — full delegation |

---

## 4. Token Lifecycle

### 4.1 Issuing

The resource owner creates and signs a UCAN token using `ucan:issue`:

```json
ucan:issue {
  aud: "did:key:zBob...",
  att: [{ with: "/w/", can: "crud/read" }],
  exp: 1735689600
}
```

The venue signs the token with the caller's key (resolved from their DID)
and returns the complete signed token. The token is self-contained — it
includes everything needed for verification.

### 4.2 Delivery

The issuer delivers the token to the audience through any channel:
- `agent:message` — agent-to-agent delivery
- API response — returned to the caller
- Out-of-band — email, shared document, etc.

The token is a CVM value (a map). It can be serialised, transmitted,
and deserialised without loss.

### 4.3 Presentation

The audience presents UCAN tokens in the `RequestContext` on a
**per-request basis**. Each request carries its own proof set — there is
no server-side token store and no session-level capability state.

```
RequestContext:
  callerDID: "did:key:zBob..."
  proofs: [<ucan-token-1>, <ucan-token-2>, ...]   ; full signed tokens
```

A request may carry multiple proofs. For example, a cross-user read
of Alice's workspace might require:
- A root UCAN from Alice granting Bob `crud/read` on `/w/`
- (For delegation) Bob's own sub-delegation UCAN if acting on behalf
  of Carol

The proofs travel with the request — they are not stored at the venue.
This is the standard UCAN bearer token model.

#### Proof references

Proofs in the `prf` field can be either:
- **Inline** — the full signed token embedded directly (simple, self-contained)
- **By value ID** — a `/a/<hash>` path referencing a token stored in the
  venue's content-addressed asset store (bandwidth-efficient for repeated use)

Note: CIDs (IPLD content identifiers) are not used. Covia uses CAD3
value hashes as the native content-addressing scheme.

#### Transport

**REST API**: UCAN tokens may arrive through either (or both) of two
transport channels — they are merged through the same trust boundary:

1. **Request body `ucans` array** — the portable envelope form that survives
   cross-venue hops (e.g. `grid:invoke`) where HTTP headers are not
   preserved:
   ```json
   POST /api/v1/invoke
   {
     "operation": "covia:read",
     "input": { "path": "did:key:zAlice.../w/notes" },
     "ucans": [<signed-token>, ...]
   }
   ```

2. **`Authorization: Bearer <ucan-jwt>`** — matching the IETF UCAN-HTTP
   bearer convention. A single UCAN JWT in the standard HTTP bearer slot
   serves both as caller authentication (the UCAN's `iss` becomes the
   caller DID, since the signature proves the issuer holds the private
   key) and as a capability proof (the same token is added to the proof
   vector). Additional delegation proofs may accompany it in the body
   `ucans` array. Expired, tampered, or non-UCAN bearer tokens fall
   through to the existing JWT auth paths.

**MCP**: Same two channels. Tool call parameters may include `ucans`, and
the MCP endpoint honours `Authorization: Bearer <ucan-jwt>` on the
enclosing HTTP request:
```json
{ "path": "did:key:zAlice.../w/notes", "ucans": [<signed-token>, ...] }
```

**Grid operations** (`grid:run`, `grid:invoke`): Optional `ucans` field
in the operation input — the envelope channel for authority to travel with
the job across venue boundaries:
```json
grid:invoke { operation: "...", input: {...}, ucans: [...] }
```
This is the *transport*; on a cross-venue hop the grid wrapper relays the
caller's presented tokens into this field, filtered to the provably
admissible — see §5.6 "Forwarding authority across venues" for the full
forwarding model (identity tokens, `venue/relay` delegations, the relay
filter).

**Agent tool calls**: The agent framework (level 2) attaches the user's
proofs automatically when invoking tools on behalf of the user. Agents
inherit the capabilities of the user who triggered them.

#### Caching (future)

Per-request proof presentation means every request carries its full
proof set. A future optimisation: venues can cache validated tokens
(keyed by CAD3 hash) and accept hash references in place of full tokens
for subsequent requests within a time window. Not implemented in Phase C1.

### 4.4 Verification

The venue verifies the proof chain on every request. No server-side
state is consulted — the proofs in the request are sufficient.

```
verify(proofs, requiredCapability):
  For each ucan in proofs:
    1. Verify ucan.sig against CAD3 hash using iss public key
    2. Check exp >= now and (nbf == null or nbf <= now)
    3. Check ucan.aud matches the caller's DID
    4. Check ucan.att contains a capability that covers requiredCapability:
       - with is equal or parent of required.with (path attenuation)
       - can is equal or parent of required.can (ability attenuation)
       - * covers any ability
    5. If prf is empty (root):                                  ; see §5.6
       - O = resource owner (DID in the with URI)
       - A = O's controlling authority:
           - if O ends in ":u:<user>"  → A = O without that suffix (the venue)
           - else                       → A = O (self-sovereign)
       - ucan.iss must == A
       - Trust: if A == O → accept (owner's own authority, self-certifying);
                if A is a venue (custodial O) → accept iff A is trusted
                (Phase C3 policy; local A == this venue is always trusted)
       - Resolve A's key, verify the root signature
       - Root reached — chain valid
    6. For each parent in prf:
       - Verify parent.aud == ucan.iss (continuous delegation)
       - Verify parent covers ucan.att (attenuation only narrows)
       - Recursively verify parent
  If any proof provides a valid chain: allow
  Otherwise: deny (uniform error)
```

Attenuation matching uses `Capability.covers()` from convex-core.

### 4.5 Delegation Chains

An agent can sub-delegate a narrower capability by signing a new token
that references the parent token in `prf`:

```
Root (Alice → Bob):
  iss: did:key:zAlice, aud: did:key:zBob
  att: [{ with: "/w/", can: "crud" }]
  prf: []

Delegation (Bob → Carol):
  iss: did:key:zBob, aud: did:key:zCarol
  att: [{ with: "/w/reports/", can: "crud/read" }]
  prf: [<root-token>]
```

Carol presents the delegation token. The venue verifies:
1. Bob signed it, Carol is the audience
2. Bob's `att` is covered by Alice's grant (sub-path, sub-ability)
3. Alice signed the root, Alice owns the resource
4. Both signatures valid, neither expired

The full proof chain travels with Carol's request.

### 4.6 Revocation

A revocation is a signed record referencing a UCAN's CAD3 hash:

```
{
  revoke: <cad3-hash-of-ucan>
  iss: "did:key:zAlice..."        ; must be the UCAN's issuer
  sig: 0x...                       ; Ed25519 signature
}
```

Revocations are published to the lattice. Venues check revocation
lists during verification. Revoking a parent invalidates all
downstream delegations.

### 4.7 Authenticating — lifting the public read-only ceiling

An unauthenticated caller runs under the venue's public identity, whose
default ceiling is **read-only** (`crud/read` on its own namespace + `asset/read`;
no `invoke`, so `POST /api/v1/invoke` of a compute op returns a `FAILED` job with
`"Capability denied"`). Two ways to gain invoke/write authority:

1. **Authenticate as yourself** — present a self-issued UCAN bearer token:
   `Authorization: Bearer <ucan-jwt>`, with `aud` = the venue DID (from
   `GET /.well-known/did.json`). You then run as your own `did:key`, which is
   unrestricted within its own namespace (own-namespace implicit grant, §5.1).
   The SDKs mint and attach this for you (Ed25519 keypair auth); the MCP and
   REST endpoints both honour the header (§4.3).
2. **Widen the public ceiling (operator, trusted venues only)** — set
   `auth.public.caps` in the venue config. `"unrestricted"` removes the ceiling
   for anonymous callers entirely; an array of `{with, can}` grants widens it
   selectively. Only do this on a loopback-bound (`bindAddress: 127.0.0.1`)
   development venue, never a LAN/public-reachable one — it hands invoke
   authority to every anonymous caller.

---

## 5. Enforcement

### 5.1 Own-Namespace Implicit Grant

A user always has full capabilities over their own namespace. No UCAN
is needed for:
- Reading/writing/deleting own `/w/` and `/o/`
- Managing own agents (`/g/`)
- Accessing own secrets (`/s/`)

This is the "resource owner" root of every delegation chain. The venue
recognises the caller's DID as owning their namespace without requiring
a token.

**Narrowing the implicit grant (self-attenuation).** The full grant is the
default. A caller may *narrow* it for a session by presenting an
**owner-authored** attenuation — a UCAN the owner signed over its own
resources (`iss == aud == caller`). The venue takes the union of those caps
and applies it as a ceiling through the standard enforcement path, so the
session can do only what the attenuation allows. Because the owner is the
authority over its own namespace and a ceiling can only *narrow* the implicit
grant — never widen it — this is escalation-safe: the venue merely enforces
the restriction the owner chose. With no token presented, the full implicit
grant stands.

A self-attenuation is **owner-signed** — mint it by signing a UCAN with the
owner's own key (in the embedded/desktop case, the local owner key signs a
per-launch token that locks the app's session to a subset of the namespace).
This is distinct from `ucan:issue`, which mints *venue-signed* tokens for
**cross-user** grants (§5.2); a venue-signed token is not the owner's own
authority and forms no self-ceiling.

### 5.2 Cross-User Access

Cross-user access requires a valid proof chain in the `RequestContext`.
The enforcement point extracts the target DID and path from the request,
determines the required capability, and verifies the caller's proofs.

```
Bob requests: covia:read { path: "did:key:zAlice.../w/notes" }
  with proofs: [<ucan from Alice granting Bob crud/read on /w/>]

Venue:
  1. Extract target: did:key:zAlice, path: /w/notes
  2. Required capability: { with: "/w/notes", can: "crud/read" }
  3. Check proofs for a chain covering the requirement
  4. Verify signatures, attenuation, expiry
  5. Allow or deny
```

### 5.3 Enforcement Points

| Point | Required Capability |
|-------|-------------------|
| `covia:read` / `covia:list` / `covia:slice` (cross-user) | `{ with: "<path>", can: "crud/read" }` |
| `covia:write` / `covia:delete` / `covia:append` (cross-user) | `{ with: "<path>", can: "crud/write" }` |
| `file:read` / `file:list` / `file:stat` / `file:roots` | `{ with: "file://<root>/<path>", can: "crud/read" }` |
| `file:write` / `file:append` / `file:mkdir` | `{ with: "file://<root>/<path>", can: "crud/write" }` |
| `file:delete` | `{ with: "file://<root>/<path>", can: "crud/delete" }` |
| `dlfs:read` / `dlfs:list` / `dlfs:stat` / `dlfs:listDrives` | `{ with: "dlfs/<drive>/<path>", can: "crud/read" }` |
| `dlfs:write` / `dlfs:append` / `dlfs:mkdir` / `dlfs:createDrive` | `{ with: "dlfs/<drive>/<path>", can: "crud/write" }` |
| `dlfs:delete` / `dlfs:deleteDrive` | `{ with: "dlfs/<drive>/<path>", can: "crud/delete" }` |
| `secret:extract` | `{ with: "/s/<name>", can: "secret/decrypt" }` |
| `agent:create` | `{ with: "/g/<id>", can: "agent/create" }` |
| `agent:request` (cross-user) | `{ with: "/g/<id>", can: "agent/request" }` |
| `agent:message` (cross-user) | `{ with: "/g/<id>", can: "agent/message" }` |
| `asset:store` | `{ with: "<any>", can: "asset/store" }` |
| `asset:get` / `asset:list` | `{ with: "<any>", can: "asset/read" }` |
| Grid operation invoke | `{ with: "/o/<op>", can: "invoke" }` |
| Sub-delegation | `{ with: "<path>", can: "ucan/delegate" }` |

File resources use URI form so they parse as standard hierarchical
identifiers (per UCAN convention for `with`): the configured root is the URI
authority, the in-root path is the URI path. DLFS resources are DID-scoped
paths — `dlfs/<drive>/<path>` is a namespace segment under the owner, like
`w/` (the bare form canonicalises to `<callerDID>/dlfs/…`). Grants nest
naturally:

| Grant | Covers |
|-------|--------|
| `file://` | every configured file root |
| `file://scratch/` | every path inside the `scratch` root |
| `file://scratch/agent-output/` | one subtree of one root |
| `dlfs/` | every drive of the owner |
| `dlfs/health-vault/medications/` | one subtree of one drive |

Granting `crud` (without a verb suffix) covers read+write+delete uniformly;
trailing-slash on the resource is the conventional way to cover a subtree.

**Own-namespace enforcement (self-ceiling).** The points above are the
cross-user checks (a *grant* of access to someone else's resource). When a
caller presents a self-attenuation (§5.1), the same capability check is also
applied to the caller's **own** operations as a *ceiling*: the op's resource
and each presented capability's `with` are canonicalised to absolute
owner-scoped form (a bare `w/health/bp` → `<callerDID>/w/health/bp`; DID-URL
and `file://`/`dlfs://` left as-is), then matched with `Capability.covers`.
Own and cross-user resources thus match by one rule. With no token presented,
the implicit grant stands and no ceiling applies — so token-less callers are
unaffected.

### 5.4 Agent Identity Models

Agents need capabilities to act in the world. The critical question is
**identity** — does the agent act as the user, or as itself? Covia supports
both models, and they compose naturally via UCAN delegation.

#### Model A: User-Scoped Agent (Attenuated Delegation)

The agent has **no independent identity**. It acts under the user's DID with
attenuated capabilities. The user creates the agent and declares what it
can do — the venue issues a scoped UCAN at creation time.

```
User DID: did:key:zAlice...
Agent ID: Carol

Delegation at creation:
  iss: did:key:zAlice
  aud: did:key:zAlice.../g/Carol       ; agent-scoped audience (DID URL)
  att: [
    { with: "did:key:zAlice.../w/decisions/", can: "crud/write" },
    { with: "did:key:zAlice.../w/enrichments/", can: "crud/read" }
  ]
  prf: []                               ; root grant from resource owner
```

When Carol's tool calls execute, the framework wraps her RequestContext
with only this token. Carol can write to `w/decisions/` but not
`w/vendor-records/`. She can read enrichments but not secrets.

**Identity:** `did:key:zAlice.../g/Carol` (a DID URL under Alice's namespace)
**Keys:** None — the venue signs on Alice's behalf
**Best for:** Pipeline agents, task workers, scoped automations — anything that
operates within one user's namespace with restricted permissions.

**How it works at runtime:**

1. `agent:create` with `caps` field declares the agent's attenuations
2. Venue issues a UCAN: issuer = user DID, audience = agent DID URL
3. Token stored in agent record (alongside config, state, etc.)
4. On each run, level 2 creates a restricted RequestContext with only the agent's token
5. Tool calls go through CoviaAdapter which verifies the token against the requested path/ability
6. Writes outside the allowed scope are denied

**Reading an agent's live caps.** An agent's ceiling lives at `config.caps`,
so read it via the config: `agent:info` (returns the record, whose `config.caps`
holds the array) or `covia:read g/<agentId>` then `.value.config.caps`. There is
**no** `g/<agentId>/caps` projection — `covia:read g/<agentId>/caps` returns
`{exists:false}` (a natural first guess that misleads). Caps are plain lattice
data under the config, not a separate slot.

**Example — AP Demo enforcement:**

| Agent | Allowed | Denied |
|-------|---------|--------|
| Alice | (no tools needed — extraction only) | — |
| Bob | `crud/read` on `w/vendor-records/`, `w/purchase-orders/`, `w/invoices/`; `crud/write` on `w/enrichments/` | Write to `w/vendor-records/`, `w/decisions/` |
| Carol | `crud/read` on `w/enrichments/`; `crud/write` on `w/decisions/` | Write to `w/enrichments/`, read secrets |
| Dave | `crud/read` on `w/`; `invoke` on orchestration | Write to `w/` (read-only manager) |

#### Model B: Independent Agent (Own DID)

The agent has its own **cryptographic identity** — its own Ed25519 keypair and
DID. It can sign UCANs, receive delegations from multiple users, and act
autonomously across the grid.

```
Agent DID: did:key:zCarolBot...

Delegation from Alice:
  iss: did:key:zAlice
  aud: did:key:zCarolBot
  att: [{ with: "did:key:zAlice.../w/decisions/", can: "crud/write" }]
  prf: []

Delegation from Bob's org:
  iss: did:key:zBobOrg
  aud: did:key:zCarolBot
  att: [{ with: "did:key:zBobOrg.../w/invoices/", can: "crud/read" }]
  prf: []
```

Carol can now operate across multiple users' namespaces, presenting
different proof chains depending on whose data she's accessing.

**Identity:** `did:key:zCarolBot...` (independent DID)
**Keys:** Ed25519 keypair generated at agent creation, stored in the venue's key store
**Best for:** Autonomous agents, cross-organisation workflows, agents that serve
multiple users, agents that need to issue their own sub-delegations.

**How it works at runtime:**

1. `agent:create` with `identity: true` generates a keypair and DID for the agent
2. Agent's DID is recorded in the agent record and discoverable via `agent:query`
3. Users delegate capabilities to the agent's DID via `ucan:issue`
4. Agent stores received UCANs (in `state` or a dedicated token store)
5. On each run, level 2 creates a RequestContext with the agent's DID and attached proofs
6. When accessing cross-user data, the agent presents the relevant proof chain
7. The agent can sub-delegate to other agents by signing its own UCANs

#### Composing Both Models

The models compose. A user-scoped agent (Model A) can be upgraded to
independent (Model B) by generating a keypair. An independent agent
can be constrained by the delegations it receives — it can only do
what someone has explicitly granted.

```
                    Alice (user)
                   /           \
            [Model A]        [Model A]
           Carol (scoped)    Bob (scoped)
           w/decisions/*     w/enrichments/*
                |
          [Model B upgrade]
           Carol gets own DID
                |
           Carol can now receive
           delegations from other users
```

A practical pattern: start with Model A for simplicity and predictable
scoping. Upgrade to Model B when the agent needs cross-user access or
must act as a principal in federated workflows.

#### Agent-to-Agent Delegation

With Model B, agents can delegate to each other:

```
Alice delegates to Dave (Model B):
  iss: did:key:zAlice
  aud: did:key:zDaveBot
  att: [{ with: "did:key:zAlice.../w/", can: "crud" }]

Dave sub-delegates to Carol (Model B):
  iss: did:key:zDaveBot
  aud: did:key:zCarolBot
  att: [{ with: "did:key:zAlice.../w/decisions/", can: "crud/write" }]
  prf: [<alice-to-dave-token>]
```

Carol can write decisions in Alice's namespace, via a delegation chain
that flows from Alice through Dave. Dave's sub-delegation is attenuated —
Carol gets write on `w/decisions/` only, not the full `w/` that Dave has.

This is the standard UCAN delegation chain model applied to agents.

### 5.5 Enforcement in the Tool Call Loop

When level 2 dispatches a tool call during the agent's run, it constructs
a RequestContext for the tool invocation. The context determines enforcement:

| Agent Model | RequestContext | Enforcement |
|-------------|---------------|-------------|
| No caps (current default) | Caller's identity, no restrictions | Full access to own namespace |
| Model A (attenuated) | Caller's identity + agent's scoped UCAN | CoviaAdapter checks UCAN before writes |
| Model B (independent) | Agent's own DID + presented proof chain | Full UCAN verification on every operation |

Two complementary checks apply, both built on the same `Capability.covers`
matcher over canonical owner-scoped resources (§3.1):

- **`CapabilityChecker`** enforces a *ceiling* — the agent's config `caps`
  and any presented self-attenuation (§5.1) — on every operation (`enforceCaps`
  at job dispatch, and before each tool call). A ceiling can only narrow.
- **`CoviaAdapter.verifyProofs()`** authorises *cross-user* access against
  presented proofs (§5.2). A proof grants reach into another namespace.

The difference between agent models is which identity and caps the
RequestContext carries; the matcher is shared.

For Model A, the key change is that the agent's tool calls go through a
**restricted RequestContext** instead of inheriting the user's full access.
The venue becomes the enforcement mechanism — it issued the scoped token
and it verifies it on every tool call.

#### The `invoke` ability under a ceiling (#211)

Invoke-classed operations (compute, LLM, external I/O, federation — anything
that does not act on a specific named lattice resource) require the `invoke`
ability. Under a restricted `config.caps` ceiling:

- **Full tool access** is a one-liner: `{"can": "invoke"}` (or the equivalent
  `{"with": "", "can": "invoke"}`) — an empty/absent `with` is a match-any
  wildcard in the ceiling path. The standard restricted-but-tool-capable
  recipe is therefore:

  ```json
  "caps": [
    { "with": "w/kill-switch", "can": "crud" },
    { "can": "invoke" }
  ]
  ```

- **Scoped invoke** pins the grant to an op-path prefix, attenuating *which*
  operations the agent may invoke: `{"with": "v/ops/getmine", "can": "invoke"}`
  admits `v/ops/getmine/*` and denies every other invoke-classed op. The
  checked resource is the operation reference as the caller supplied it
  (`RequestContext.getOp()`), captured at dispatch — the same path form the
  agent's `config.tools` entries use, so tool-loop calls match naturally.

- **Caveat:** invoking by hex hash (or from resolved metadata directly)
  carries no path, so only the wildcard grant covers it — a path-scoped
  invoke ceiling denies hash-form invocation by design (fail-closed).

### 5.6 Trust Anchors and Federation

Every delegation chain terminates in a **root** — a token whose `prf` is
empty. The single question that makes verification federatable is: *who is
allowed to sign that root?* The answer is uniform and derives entirely from
the resource being accessed, never from the identity of the verifying venue.

**The trust anchor is the authority over the resource owner.** A resource URI
names its owner (§3.1: `did:…/w/notes` is owned by `did:…`). The root of any
chain granting access to it must be signed by that owner's controlling
authority — which is read directly off the owner's DID:

| Owner DID form | Identity class | Controlling authority | Root signer | Trusted because |
|----------------|----------------|-----------------------|-------------|-----------------|
| `did:key:z…` | **Self-sovereign** | the owner themselves | the owner (owner-signed) | `did:key` is self-certifying — the key *is* the DID |
| `did:web:host` | **Self-sovereign** | the owner themselves | the owner (owner-signed) | resolved from `https://host/.well-known/did.json` |
| `<venueDID>:u:<user>` | **Venue-custodial** | the controlling venue (`<venueDID>`) | that venue (venue-**attested**) | the verifier's trust policy accepts that venue |

The custodial authority is obtained by stripping the `:u:<user>` suffix that
the venue appends when it mints a user's DID (`LoginProviders`), yielding the
venue's own DID; a `did:web` custodial DID additionally resolves at
`/u/<user>/did.json`.

**Owner-rooted (self-sovereign).** A user who holds their own key signs their
own root. Nothing but their signature is consulted — no venue is asked, no
trust list, no config sync. This is fully federated by construction: any venue
can verify a `did:key` root offline. This is the model for embedded/desktop
owners (each app holds its keypair — see the [Embedded Venue](https://docs.covia.ai/docs/operator-guide/embedded-venue)
recipe) and the target for cross-venue delegation.

**Venue-attested (custodial).** An OAuth user has no key of their own; the
venue holds their identity. That venue signs on the user's behalf — the root
token's `iss` is the **controlling venue**, and the token is an *attestation*:
"I hold this user's identity and I vouch for this grant over their namespace."
This is not the venue claiming authority over arbitrary data — it is scoped to
the namespaces of the users it controls. A verifying venue accepts such a root
only if its **trust policy** accepts the attesting venue (Phase C3).

**The verifier's own identity is irrelevant.** A venue verifying a request
asks only: does the chain root in the resource owner's controlling authority,
and (for a custodial root) do I trust that authority? It never requires the
root to be its *own* signature. The current implementation's Phase C1 check —
root `iss` must equal the verifying venue — is the **degenerate case** where
the controlling authority happens to be the verifying venue itself (a local
custodial user). Generalising it from "iss == this venue" to "iss == the
owner's controlling authority, and trusted" is backward-compatible for local
users and is precisely what unblocks federation (#100).

#### Trust policy for attesting venues (Phase C3)

Self-sovereign roots need no policy — they are self-certifying. Only
**custodial** roots signed by a *remote* venue require a decision, and the
degenerate local case (attesting venue == verifying venue) is always trusted.
For genuinely remote attestations the verifier applies a configurable policy;
the design supports, in increasing flexibility:

1. **Trusted-issuers allowlist** — `auth.trustedIssuers: [did:web:a.example, …]`.
   Simple; explicit; needs manual sync.
2. **Organisation root** — venues in one org each hold a delegation from a
   shared org DID; a custodial attestation is trusted if it chains to that
   root. No per-venue enumeration.
3. **DID-document discovery** — resolve the attesting venue's DID document and
   apply a policy expressed there (e.g. a federation membership claim).

These are layered, not exclusive: an allowlist can ship first, an org root
added later, without changing the verification core — which only needs
"resolve authority A's key; is A trusted for this owner?" The policy is the
*only* federation-specific surface; the chain-walking, attenuation, temporal,
and revocation checks are identical to the single-venue path.

#### Forwarding authority across venues

The section above is the *verifying* side (venue B). The complement is the
*forwarding* side: when venue A relays a caller's request to B (e.g.
`grid:invoke`, `grid:jobStatus`), the caller's authority must travel with it —
**by default**, not opt-in. A cross-venue request that drops the caller's
authority is anonymous, which is a safe stopgap but not federation; the target
is that A forwards, and anonymous becomes the explicit choice for genuinely
public operations.

Two things travel, and they are treated differently:

- **Proofs (UCANs) — carried freely.** They are self-authenticating: B verifies
  each against the resource owner's authority (owner-rooted / attested, above).
  B never *trusts* a forwarded proof, it *verifies* it — so relaying them is
  safe. The `ucans` request-body array (§4.3) is the transport channel that
  survives the hop (headers do not).
- **Identity — carried only as a *proof*, never an assertion.** A must not put
  a bare "caller = Alice" claim in the request and have B believe it: A could
  claim any DID (the spoofing hole). A forwarded identity is only admissible if
  B can verify it:
  - **Self-sovereign caller** (`did:key`): the caller signs the request/UCAN
    with their own key; A relays the signature; B verifies it directly (no
    trust in A). The caller audiences their UCAN to **B** (or to the resource),
    not to A — a token audienced to A cannot be replayed at B.
  - **Custodial caller** (OAuth on A, `<A>:u:<user>`): the caller has no key, so
    **A attests** — A signs "my user `<A>:u:<user>` authenticated to me, and I
    forward this grant on their behalf," re-audienced to B. B accepts the
    attestation iff its trust policy accepts A (the venue-attested root, above).

**Sequencing (safety-critical).** Identity-forwarding must not ship before B
can verify what is forwarded — otherwise B cannot tell a real relayed identity
from a spoofed one, which is *worse* than anonymous. So it follows the
verification phases exactly: **proofs + self-sovereign signatures with C3a**
(shipped — see below); **custodial attestations with C3b** (the trust policy
exists).

**C3a implementation (shipped).** Authority travels **only in the `ucans` proof
channel** — never in operation input (input is data, persisted in job records;
a credential there would leak into durable history). Tokens are self-describing,
so there are no mode flags or auth fields anywhere:

- **Proofs are relayed, filtered to the provably admissible.** The caller's raw
  transport UCANs (`RequestContext.getRawUcans()`; the parsed maps cannot be
  re-signed) are relayed in the hop's `ucans` body array. The relay drops only
  what is *provably inert* at the target — expired/unparseable tokens, and
  tokens audienced to an identity that is not the hop's principal (e.g. the
  local caller's own grants when the venue relays as itself). Deeper filtering
  is not provable (the relay cannot know which resources an operation on the
  target touches, nor generally the target's DID before contact), so
  presentation remains the *caller's* disclosure decision: present per-request,
  not a wallet. The inbound *bearer* is never relayed — it is audienced to the
  relaying venue (§4.4).
- **Identity token** — the caller mints a UCAN with an **empty `att`**,
  audienced to the **target** venue, and presents it in their `ucans`. Pure
  proof of identity: it grants nothing, and being audience-bound it is unusable
  at any other venue. The relay just forwards it; the target's ingress accepts
  a verified identity token as the caller's identity **only on an anonymous
  transport** (an Authorization header always wins), verifying the caller's own
  signature — zero trust in the relay. The caller is then the principal at the
  target: jobs owned by them, their proofs audienced to them apply.
- **`venue/relay` delegation** — to have the venue hop *as itself*, the caller
  grants it a capability with `can: venue/relay` (token issued **by the
  caller**, audienced **to the relaying venue**; conventionally
  `with: <callerDID>`). The token is simultaneously the instruction and the
  authorisation — no flag. The issuer-must-be-the-caller rule makes the
  confused deputy impossible by construction: a relay delegation someone else
  minted for the venue is not an instruction from this caller. The same token
  can carry the substantive grants (e.g. `crud/read` over the owner's
  namespace) whose chain the target verifies to the owner root.
- **No identity token and no relay delegation** → anonymous hop (the explicit
  choice for public operations).
- **Local targets** carry the caller's verified proofs into the local context
  (`LocalVenue.setProofs`), so a local hop keeps authority exactly like a
  remote hop forwards it (closes covia#102 Finding 1).

---

## 6. Implementation Phases

### Phase C1: Signed UCAN Tokens ✓

- `Capability.covers()` in convex-core for attenuation matching
- `ucan:issue` operation creates and signs tokens
- `ucan:verify` operation verifies a token against the venue's trust policy and explains the verdict (validity, chain depth, root issuer, per-capability root-authority, optional would-it-authorise check)
- Tokens presented per-request in `RequestContext.proofs`
- Signature verification on every capability check
- Time bounds (`exp`, `nbf`) enforced
- Cross-user reads work when valid proof is presented

### Phase C1b: Self-attenuation on the direct invoke path ✓ (#131)

- A presented owner-authored UCAN narrows the caller's own implicit grant (§5.1)
- `enforceCaps` matches caps against **canonical owner-scoped resources** —
  bare own-paths resolve to `<callerDID>/…`, the same convention as cross-user
  (§3.1, §5.3); config caps and token caps interoperate
- Selection reuses `UCANValidator.capabilitiesFor` (convex-core);
  `CapabilityChecker.selfCapabilities` delegates to it and adds only the
  self-attenuation narrow-only guard (drop empty-`with`)
- Escalation-safe: a ceiling can only narrow, never widen
- Both invoke transports (REST, MCP) attach it via one seam,
  `AuthMiddleware.withTransportAuth`

### Phase C2: Delegation Chains

- Proof chain walking (`prf` field with embedded parent tokens)
- Attenuation validation at each chain link
- Revocation support (signed records referencing UCAN hash)

### Phase C2a: Agent Capability Scoping (Model A)

- `caps` field in agent create specifies attenuations
- Venue issues scoped UCAN at agent creation (issuer = user, audience = agent DID URL)
- Token stored in agent record
- Level 2 wraps tool call RequestContext with agent's token
- CoviaAdapter enforces write/read restrictions via token verification
- Own-namespace writes become capability-gated (not just namespace-prefixed)

### Phase C2b: Independent Agent Identity (Model B)

- `identity: true` on agent create generates Ed25519 keypair + DID
- Agent DID stored in record, discoverable via `agent:query`
- Agent can receive delegations from any user
- Level 2 uses agent's DID in RequestContext for tool calls
- Agent can sign its own UCANs for sub-delegation
- Agent-to-agent delegation chains

### Phase C3: Federation

Trust anchored at the resource owner, not the verifying venue (§5.6). Each
track has a *verifying* half (venue B) and a *forwarding* half (venue A); they
land together, in order of dependency:

**C3a — Self-sovereign** (current behaviour).
- *Verify:* root authority follows the §4.4 rule — the chain root must be signed
  by the resource owner's controlling authority (self-sovereign owners verify
  offline via `RootAuthorityPolicy.SELF_SOVEREIGN`), or by this venue (the C1
  arm, kept for venue-issued grants and custodial users). The generic primitive
  (chain walk + root-authority policy + pluggable DID verification) is
  convex-core `UCANValidator` (Convex-Dev/convex#635); covia's `proofsCover`
  composes the policy.
- *Forward:* the grid wrapper relays the caller's authority per §5.6 — proofs
  filtered to the provably admissible, identity as an audience-bound identity
  token, venue-as-delegate via a `venue/relay` capability.

**C3b — Venue-attested (custodial).**
- *Verify:* accept a root signed by a *remote* controlling venue for its
  custodial user (`<venueDID>:u:<user>`), gated by a trust policy — allowlist
  first (`auth.trustedIssuers`), org-root / DID-discovery later (§5.6).
- *Forward:* A attests on behalf of its custodial user (§5.6) and relays the
  attestation, re-audienced to B.

This is the only federation-specific surface; everything else (chain walk,
attenuation, temporal, revocation) is shared with the single-venue path.

---

## 7. Differences from Standard UCAN

| Aspect | Standard UCAN | Covia UCAN |
|--------|--------------|------------|
| Canonical encoding | DAG-CBOR (IPLD) | CAD3 (Convex lattice encoding) |
| Content addressing | CID (multihash + multicodec) | CAD3 Value Hash (SHA3-256) |
| Data types | IPLD schema types | CVM types (AMap, AVector, AString, CVMLong, Blob) |
| Transport encoding | DAG-JSON or JWT | JWT (interop transport); CVM JSON for lattice-native exchange |
| Storage | Application-specific | Lattice `/a/` namespace (content-addressable, replicated) |
| Key types | Ed25519, P-256, secp256k1 | Ed25519 (Convex native) |
| DID methods | Any | `did:key` (primary), `did:web`, `did:convex` |
| Revocation | Application-specific | Lattice-native signed records |
| Merge semantics | None (tokens are immutable) | CAS lattice merge (immutable, union) |

The conceptual model — issuer, audience, attenuated capabilities, delegation chains, cryptographic verification — is identical. Only the encoding layer differs.

---

## Related Documents

- **GRID_LATTICE_DESIGN.md §6** — Original capability model specification
- **AGENT_LOOP.md §3.5** — Agent tool palette and capability-gated tools
- **[UCAN Specification](https://github.com/ucan-wg/spec)** — Upstream UCAN spec
- **[W3C DID Core](https://www.w3.org/TR/did-core/)** — DID and DID URL syntax
