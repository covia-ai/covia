# UCAN Design — Lattice-Native Capabilities for Covia

Design for User Controlled Authorisation Networks (UCAN) in Covia, using lattice data structures instead of IPLD/DAG-CBOR.

**Status:** Draft — March 2026

---

## 1. Principles

1. **Consistent with UCAN spec.** Same conceptual model: issuer/audience, attenuated capabilities, delegation chains, cryptographic signatures. Where Covia diverges from the UCAN spec, it is because the encoding uses CAD3/lattice rather than IPLD/DAG-CBOR — not because the authorisation model differs.

2. **Lattice-native.** UCANs are first-class lattice values, stored as content-addressable data in `/a/`. No JWT, no base64, no separate token format. The CAD3 value hash of a UCAN is its canonical identifier. UCANs merge, replicate, and verify like any other lattice data.

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

**Attenuation rule:** A resource URI is a valid attenuation of a parent if it is equal to or a sub-path of the parent. `did:.../w/projects/foo` attenuates `did:.../w/`.

This aligns with `DIDURL` from convex-core — the DID is the authority, the path is the namespace scope.

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
| `agent/message` | — | Send message to agent session |
| `agent/fork` | — | Fork an agent |
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
in the operation input. Tokens travel with the job across venue boundaries:
```json
grid:invoke { operation: "...", input: {...}, ucans: [...] }
```

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
    5. If prf is empty:
       - ucan.iss must be the resource owner (DID in the with URI)
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
| `secret:extract` | `{ with: "/s/<name>", can: "secret/decrypt" }` |
| `agent:message` (cross-user) | `{ with: "/g/<id>", can: "agent/message" }` |
| Grid operation invoke | `{ with: "/o/<op>", can: "invoke" }` |
| Sub-delegation | `{ with: "<path>", can: "ucan/delegate" }` |

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

The enforcement point is the same in all cases — CoviaAdapter's
`verifyProofs()` method. The difference is what's in the RequestContext.

For Model A, the key change is that the agent's tool calls go through a
**restricted RequestContext** instead of inheriting the user's full access.
The venue becomes the enforcement mechanism — it issued the scoped token
and it verifies it on every tool call.

---

## 6. Implementation Phases

### Phase C1: Signed UCAN Tokens ✓

- `Capability.covers()` in convex-core for attenuation matching
- `ucan:issue` operation creates and signs tokens
- Tokens presented per-request in `RequestContext.proofs`
- Signature verification on every capability check
- Time bounds (`exp`, `nbf`) enforced
- Cross-user reads work when valid proof is presented

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

- Proof chains travel with job submissions across venue boundaries
- Each venue verifies independently using DID→key resolution
- Cross-venue capability negotiation
- Independent agents can operate across venues with portable identity

---

## 8. Differences from Standard UCAN

| Aspect | Standard UCAN | Covia UCAN |
|--------|--------------|------------|
| Canonical encoding | DAG-CBOR (IPLD) | CAD3 (Convex lattice encoding) |
| Content addressing | CID (multihash + multicodec) | CAD3 Value Hash (SHA3-256) |
| Data types | IPLD schema types | CVM types (AMap, AVector, AString, CVMLong, Blob) |
| Transport encoding | DAG-JSON or JWT | CVM JSON serialisation |
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
