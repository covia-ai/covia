---
name: ucan
description: Issue and manage UCAN capability tokens — delegate access to workspace, agents, secrets, and operations. Use for demonstrating or configuring Covia's authorisation model.
argument-hint: [issue|explain]
---

# UCAN Capability Management

**Prerequisite:** The venue must be running and connected as an MCP server (`http://localhost:8080/mcp/sse`). If MCP tools are not available, tell the user to run `/venue-setup local` first.

Manage capability-based authorisation on a Covia venue using UCAN tokens.

## Concepts

**UCAN** (User Controlled Authorization Networks) — decentralised capability tokens. No central auth server; capabilities are cryptographically signed and verifiable by any party.

**Key fields:**
- **iss** (issuer) — who grants the capability (signs the token)
- **aud** (audience) — who receives the capability
- **att** (attenuations) — array of `{with, can}` pairs defining what's allowed
- **exp** (expiry) — Unix timestamp when the token expires

## Resources (the `with` field)

Resources are DID URL paths into a user's namespace:

| Resource Pattern | Scope |
|-----------------|-------|
| `did:key:z.../w/` | Entire workspace |
| `did:key:z.../w/projects/foo` | Specific workspace key |
| `did:key:z.../g/Alice` | Specific agent |
| `did:key:z.../s/API_KEY` | Specific secret |
| `did:key:z.../o/my-op` | Specific operation |

## Abilities (the `can` field)

| Ability | Scope |
|---------|-------|
| `*` | Full delegation |
| `crud/read` | Read data |
| `crud/write` | Write data |
| `crud/delete` | Delete data |
| `invoke` | Execute operations |
| `agent/message` | Send messages to an agent |
| `secret/decrypt` | Read a secret value |
| `ucan/delegate` | Sub-delegate capabilities |

## Commands

### `issue` — Issue a capability token

Ask the user for:
1. **Audience** — who should receive the capability (their DID)
2. **Resource** — what path to grant access to
3. **Ability** — what they can do (read, write, invoke, etc.)
4. **Expiry** — when the token should expire

Then:
```
ucan_issue
  aud: "<audience-DID>"
  att: [{ "with": "<resource-DID-URL>", "can": "<ability>" }]
  exp: <unix-timestamp>
```

Returns a signed UCAN token that the audience can present with future requests.

### `explain` — Explain the UCAN model

Walk through how Covia's authorisation works:

1. **No central auth server** — capabilities are cryptographically signed tokens
2. **Attenuated delegation** — Alice can grant Bob read access to her workspace; Bob can further delegate a subset to Carol, but never more than he has
3. **Verifiable by anyone** — tokens are self-contained; any venue can verify the chain
4. **Per-request** — tokens travel with each request in the `ucans` field
5. **Content-addressed** — tokens are stored as lattice values with CAD3 hashes, making them tamper-proof

## Example: Delegate Workspace Read Access

```
# Alice issues a token granting Bob read access to her shared folder
ucan_issue
  aud: "did:key:zBob..."
  att: [{ "with": "did:key:zAlice.../w/shared/", "can": "crud/read" }]
  exp: 1735689600

# Bob presents the token when reading Alice's data
# (via REST API ucans field or agent proof context)
```

## Solo Demo (No Second User)

If demoing alone without a second DID, you can still show the flow:

1. **Issue a self-referencing token** — use the venue's own DID as audience. Query the venue DID first:
   ```
   covia_read  path=venue/did  → returns the venue DID
   ucan_issue
     aud: "<venue-DID>"
     att: [{ "with": "<your-DID>/w/demo-data", "can": "crud/read" }]
     exp: <1 hour from now>
   ```

2. **Explain the model** — use the `explain` command to walk through the concepts. The token itself demonstrates the structure even without a real second party.

3. **Show the token contents** — UCANs are self-describing. Display the issued token and walk through the fields (issuer, audience, attenuations, expiry, signature).

## Current Status

- **Phase C1 (complete):** Venue-signed tokens, per-request verification, DID URL resources, adversarial tests
- **Phase C2 (planned):** Delegation chains, proof chain walking, revocation
- **Phase C3 (planned):** Cross-venue federation with UCAN proofs
