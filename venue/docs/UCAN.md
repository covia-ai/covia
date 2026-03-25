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
| `agent/message` | — | Write to agent inbox |
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

## 4. Delegation Chains

### 4.1 Chain Structure

A delegation chain is a sequence of UCANs where each link's `aud` matches the next link's `iss`:

```
Root UCAN (self-issued by resource owner):
  iss: did:key:zAlice        ; Alice is the owner
  aud: did:key:zBob          ; grants to Bob
  att: [{ with: "did:key:zAlice.../w/", can: "crud" }]
  prf: []                    ; root — no parent proofs

Delegation UCAN (issued by Bob):
  iss: did:key:zBob           ; Bob delegates
  aud: did:key:zCarol         ; grants to Carol
  att: [{ with: "did:key:zAlice.../w/reports/", can: "crud/read" }]
  prf: [<hash-of-root-ucan>] ; proves authority via Alice's grant
```

Carol can read `did:key:zAlice.../w/reports/` because:
1. Alice granted Bob `/crud` on her entire `/w/` namespace
2. Bob attenuated to `/crud/read` on the `/w/reports/` subtree for Carol
3. The chain is cryptographically verifiable without contacting Alice or Bob

### 4.2 Verification Algorithm

```
verify(ucan, requiredCapability):
  1. Verify ucan.sig against CAD3 hash using iss public key
  2. Check exp >= now and (nbf == null or nbf <= now)
  3. Check nnc not previously seen (if present)
  4. Check ucan.att contains a capability that covers requiredCapability:
     - with is equal or parent of required.with
     - can is equal or parent of required.can
  5. If prf is empty:
     - ucan.iss must be the resource owner (DID in the with URI)
     - This is the root — chain terminates
  6. For each parent hash in prf:
     - Resolve parent UCAN from /a/ store
     - Verify parent.aud == ucan.iss (continuous delegation)
     - Verify parent covers ucan.att (attenuation is valid)
     - Recursively verify(parent, ucan.att)
```

### 4.3 Revocation

A revocation is a signed record referencing a UCAN's CAD3 hash:

```
{
  revoke: <cad3-hash-of-ucan>
  iss: "did:key:zAlice..."        ; must be the UCAN's issuer
  sig: 0x...                       ; Ed25519 signature
}
```

Revocations are stored in the lattice and checked during verification (step between 3 and 4). Revocation of a parent UCAN invalidates all downstream delegations.

---

## 5. Lattice Storage

### 5.1 UCAN Storage

UCANs are stored as content-addressable values in the `/a/` (assets) namespace. The CAD3 value hash of the complete UCAN (including signature) is its identifier.

```
/a/<cad3-hash>  →  { iss, aud, att, exp, nbf, nnc, fct, prf, sig }
```

This means UCANs are:
- **Immutable** — the hash changes if any field changes
- **Deduplicated** — identical UCANs share the same hash
- **Replicable** — content-addressed data syncs safely across venues
- **Referenceable** — proof chains use hashes, not inline copies

### 5.2 Capability Grants (`:caps`)

The venue state includes a `:caps` field (`MapLattice`) mapping DIDs to their active capability sets. This is the runtime-queryable index of effective capabilities:

```
:caps → MapLattice (DID → capability record)
  "did:key:zBob" → {
    grants: [<hash1>, <hash2>]     ; CAD3 hashes of UCANs granting to this DID
    updated: 1719500000
  }
```

Venue operators populate `:caps` to bootstrap initial grants. Agents populate it via `/ucan/delegate`. The enforcement layer resolves and validates the chain at invocation time.

### 5.3 Per-Agent Capabilities

The agent record's `caps` field (currently a placeholder) will hold the agent's effective capability set — the resolved and validated capabilities that the agent is authorised to exercise:

```
agent record:
  caps: {
    grants: [<hash1>, ...]          ; UCAN hashes
    effective: [                     ; pre-resolved capabilities (cache)
      { with: "did:.../w/", can: "crud/read" }
    ]
  }
```

The `effective` set is computed from the grant chain and cached. It is recomputed when grants change or expire.

---

## 6. Enforcement Points

### 6.1 Current State

| Point | Current Enforcement | UCAN Enforcement |
|-------|-------------------|-----------------|
| `covia:write` / `covia:delete` / `covia:append` | `validateWritablePath` — static check: namespace must be `w` or `o` | Check caller has `{with: "<did>/<path>", can: "crud/write"}` |
| `covia:read` / `covia:list` / `covia:slice` | Own namespace only; cross-user denied in `resolveTargetPath` | Check caller has `{with: "<target-did>/<path>", can: "crud/read"}` |
| `secret:extract` | TODO | Check `{with: "<did>/s/<name>", can: "secret/decrypt"}` |
| `agent:message` | Agent must exist and not be TERMINATED | Additionally check `{with: "<did>/g/<id>", can: "agent/message"}` |
| Grid operation invoke | No capability check | Check `{with: "<did>/o/<op>", can: "invoke"}` |
| Agent delegation | Not implemented | Check `{with: "<did>", can: "ucan/delegate"}` |

### 6.2 Enforcement Flow

For a cross-user read like `did:key:zAlice.../w/notes` from Bob:

```
1. resolveTargetPath extracts target DID (Alice) and path (/w/notes)
2. Caller is Bob — cross-user access detected
3. Look up Bob's capability grants (from :caps or presented UCAN)
4. Find a UCAN chain: Alice → Bob with { with: "did:.../w/", can: "crud/read" }
5. Verify chain (signatures, attenuation, expiry, revocation)
6. If valid: resolve Alice's cursor, navigate path, return value
7. If invalid: deny with uniform error (no information leak)
```

### 6.3 Own-Namespace Implicit Grant

A user always has full capabilities over their own namespace. No UCAN is needed for:
- Reading/writing/deleting own `/w/` and `/o/`
- Managing own agents (`/g/`)
- Accessing own secrets (`/s/`)

This is the "resource owner" root of every delegation chain.

---

## 7. Implementation Phases

### Phase C1: Simple Capability Grants (No Delegation)

- Add capability checking to `validateWritablePath` and `resolveTargetPath`
- Venue operator configures grants via `:caps` in venue state
- Capabilities are `{with, can}` pairs — no signatures, no chains
- Cross-user reads work when capability is granted
- Forward-compatible with full UCAN (same `with`/`can` semantics)

### Phase C2: Signed UCANs

- UCAN tokens with Ed25519 signatures over CAD3 content
- Stored in `/a/` as content-addressable values
- Signature verification on every capability check
- Time bounds (`exp`, `nbf`) enforced
- Nonce tracking for replay prevention

### Phase C3: Delegation Chains

- Proof chain walking (`prf` field)
- Attenuation validation (resource sub-path, command prefix)
- Agents can sub-delegate narrower capabilities
- Revocation support

### Phase C4: Federation

- UCANs travel with job submissions across venue boundaries
- Each venue verifies independently using DID→key resolution
- Cross-venue capability negotiation

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
