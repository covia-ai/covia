# Authorization System Proposal

**Status:** Proposal — for discussion, not yet approved for implementation.

## Problem

AuthMiddleware extracts caller DID from JWT tokens, and `Engine.invokeOperation()` stores it in the job record, but **no access checks are ever performed**. Any authenticated (or anonymous) user can invoke any operation, see any job, and manage any resource.

## Design Goals

1. **Public servers must work** — no-auth venues on the grid must be a first-class configuration, not a special case
2. **Flexible and general** — access control expressed in Convex data structures, not hard-coded logic
3. **Set intersection as primitive** — roles and capabilities modelled as `ASet<Keyword>` with set operations for access decisions
4. **Federated** — venue controlled by one org, other venues/users may be different orgs; DID-based identity
5. **Proper access controls** — separate rights for different actions, groups with visibility over sets of jobs

## Core Model: Sets of Keywords

Everything is expressed as Convex `ASet<Keyword>` with set operations for access decisions.

```
Role definitions (Keyword → ASet<Keyword>):
  :user    → #{:invoke :read :write}
  :viewer  → #{:read}
  :admin   → #{:*}                       (:* = all capabilities)

Principal grants (DID string → ASet<Keyword>):
  "did:key:z6Mk..."   → #{:user}
  "did:web:venue2..."  → #{:user :team-alpha}

Default roles (ASet<Keyword> for anonymous/unauthenticated access):
  #{:viewer}                              (public read-only venue)
  #{:user}                                (fully public venue)
  #{}                                     (private venue — auth required for everything)
```

### Why Sets of Keywords?

- **Canonical** — Keywords are interned, so identity comparison is instant
- **Composable** — set union for combining roles, intersection for matching
- **Lattice-native** — ASet is a standard Convex data type with CRDT-compatible merge
- **Human-readable** — `:admin`, `:team-alpha`, `:invoke` are self-documenting
- **General** — same structure works for capabilities, roles, groups, and visibility tags

## Two-Layer Access Check

### Layer 1: System Capabilities (what ACTIONS can you perform?)

Standard capabilities guard API actions:

| Capability | Guards |
|-----------|--------|
| `:invoke` | Invoking operations |
| `:read` | Reading assets, job status |
| `:write` | Registering assets, uploading content |
| `:manage-jobs` | Cancel/pause/resume/delete ANY job (not just own) |
| `:manage-users` | Manage user records and role grants |
| `:admin` | Venue configuration |
| `:*` | Wildcard — implies all capabilities |

Resolution:
```
principalRoles = grants[callerDID] ∪ defaultRoles
effectiveCaps  = ∪( roleDefs[r] for r in principalRoles )
allowed        = requiredCap ∈ effectiveCaps
```

If any of the principal's roles maps to a capability set containing `:*`, all capability checks pass.

### Layer 2: Operation Access (which OPERATIONS can you use?)

Operations optionally declare required roles in metadata:

```json
{
  "operation": {
    "adapter": "langchain:openai",
    "requires": ["admin"],
    "input": { ... }
  }
}
```

Access check uses **set intersection**:
```
opRequires ∩ principalRoles ≠ ∅     (any matching role grants access)
```

If `requires` is absent or empty → anyone with `:invoke` capability can access the operation.

This enables natural scoping:
- `"requires": ["admin"]` → only admins
- `"requires": ["user"]` → any authenticated user
- `"requires": ["team-alpha", "team-beta"]` → members of either team
- *(absent)* → open to all with `:invoke`

### Combined Check for Invoke

When invoking an operation, both layers apply:
1. Does the principal have `:invoke` capability? (Layer 1 — role → capability resolution)
2. Does the principal satisfy the operation's `requires`? (Layer 2 — set intersection)

Both must pass.

## Job Visibility via Role Intersection

Jobs are tagged with the creator's roles at submission time:
```
job.roles = principalRoles    (snapshot at creation)
```

A principal can see/access a job if:
```
canSee = (caller == job.caller)                          // own job
       ∨ effectiveCaps.contains(:manage-jobs)            // admin
       ∨ (principalRoles ∩ job.roles).count() > 0        // shared role
```

This uses **set intersection** for group-based visibility. If you share any role with the job creator (e.g. `:team-alpha`), you can see their jobs. This naturally supports:

- **Personal isolation** — users only see their own jobs by default
- **Team visibility** — team members share a role and see each other's jobs
- **Admin oversight** — `:manage-jobs` grants visibility over everything
- **No configuration needed** — visibility emerges from role assignments

## Lattice Storage

New `:auth` section in VenueLattice:

```
:auth → AMap<Keyword, ACell>
  :roles    → AMap<Keyword, ASet<Keyword>>     role name → capability set
  :grants   → Index<AString, ASet<Keyword>>    principal DID → role set
  :defaults → ASet<Keyword>                    roles for anonymous access
```

All standard Convex types. Merge semantics: timestamp-based (newer wins) for the `:auth` section as a whole. Auth state is venue-authoritative — each venue controls its own access policy.

The auth state persists through Etch store (same as jobs, assets, etc.) and can be replicated if the venue chooses to share it.

## Public / No-Auth Configuration

Controlled entirely by `:defaults` — no special boolean flag needed:

| Configuration | defaults | Effect |
|--------------|----------|--------|
| Fully open (current default) | `#{:user}` | Anonymous gets invoke + read + write |
| Read-only public | `#{:viewer}` | Anonymous can browse; must authenticate to invoke |
| Private | `#{}` | All actions require authentication |
| Custom | `#{:custom-role}` | Custom capabilities for anonymous access |

## Default Seed

When a venue starts with no auth config, seed with:

```
:roles = {
  :viewer → #{:read}
  :user   → #{:invoke :read :write}
  :admin  → #{:*}
}
:grants = {}              (empty — no per-principal grants yet)
:defaults = #{:user}      (backwards-compatible: anonymous can do everything)
```

This preserves current behaviour while enabling operators to tighten access at any time.

## API Endpoints for Auth Management

New endpoints guarded by `:manage-users` capability:

```
GET  /api/v1/auth/roles                 — list all role definitions
PUT  /api/v1/auth/roles/{name}          — define/update a role (body: capability set)
GET  /api/v1/auth/grants/{did}          — get principal's granted roles
PUT  /api/v1/auth/grants/{did}          — set principal's granted roles (body: role set)
GET  /api/v1/auth/defaults              — get default roles for anonymous access
PUT  /api/v1/auth/defaults              — set default roles (body: role set)
```

## HTTP Status Codes

| Code | Meaning |
|------|---------|
| 401 | No authentication provided (and venue is not public) |
| 403 | Authenticated but insufficient permissions |
| 409 | Conflict (existing — invalid state transitions) |

## Examples

### Scenario: Private Venue with Team Access

```
roles:
  :analyst → #{:invoke :read}
  :engineer → #{:invoke :read :write}
  :ops → #{:invoke :read :write :manage-jobs}
  :admin → #{:*}

grants:
  "did:key:alice..." → #{:engineer :team-alpha}
  "did:key:bob..."   → #{:analyst :team-alpha}
  "did:key:carol..." → #{:ops :team-beta}
  "did:key:dave..."  → #{:admin}

defaults: #{}   (private — no anonymous access)
```

- Alice can invoke + read + write. She can see her own jobs and Bob's jobs (shared `:team-alpha`).
- Bob can invoke + read. He can see his own jobs and Alice's (shared `:team-alpha`).
- Carol can invoke + read + write + manage any job. She can see her own jobs. She can cancel/delete any job.
- Dave can do everything (`:*` wildcard).
- No anonymous access.

### Scenario: Public AI Marketplace

```
roles:
  :visitor → #{:read}
  :user → #{:invoke :read}
  :premium → #{:invoke :read :write}
  :admin → #{:*}

defaults: #{:visitor}   (anyone can browse operations)
```

- Anonymous users can browse the operation catalog.
- Authenticated users with `:user` role can invoke operations.
- Premium users can also upload content.

### Scenario: Operation-Level Restriction

An expensive LLM operation restricted to premium users:
```json
{
  "name": "GPT-4 Analysis",
  "operation": {
    "adapter": "langchain:openai",
    "requires": ["premium", "admin"],
    "input": { ... }
  }
}
```

A user with `:user` role has `:invoke` capability but fails the Layer 2 check (`#{:user} ∩ #{:premium :admin} = ∅`). Only `:premium` or `:admin` users can use this operation.

## Open Questions

1. **Role hierarchy** — Should `:admin` automatically include all other roles, or is `:*` wildcard sufficient? Current design uses `:*` in the capability set, not role inheritance.

2. **Negative permissions / deny rules** — Current model is purely additive (grant only). Should there be a way to explicitly deny? (Adds complexity; probably not needed for v1.)

3. **Per-asset access** — Should individual assets (not just operations) have `requires` fields? Would enable restricting who can view specific datasets.

4. **Expiring grants** — Should grants have TTL? Useful for temporary access but adds complexity.

5. **Delegation** — Should a principal be able to grant their own roles to others? Requires a delegation model (transitive trust).

6. **Audit logging** — Should authorization decisions be logged to the lattice? Would enable compliance reporting.
