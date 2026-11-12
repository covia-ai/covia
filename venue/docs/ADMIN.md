# Privileged operations

Status: the admin ability organisation is implemented. Grants using the former
user, adapter and venue administration names must be migrated separately;
those names are not accepted as aliases.

`admin/…` identifies privileged controls outside normal workflows: venue
administration, directly exercising an agent's authority, and retrieving
retained bearer credentials. Venue administration uses `admin/user`,
`admin/adapter`, `admin/venue` and `admin/sudo`; user-resource privileges use
`admin/agent` and `admin/token/mcp`. Their resource scopes and additional
authority rules are preserved. Explicit activation is outside this change.

Managing something does not by itself make an operation high-level admin.
MCP management, for example, must remain independently delegable without
granting venue administration. Sensitive user operations also need their own
precise permissions. Every contract must name its resource, ability and any
additional restrictions, regardless of how the action is reached.

## Document responsibilities

| Document | Owns |
|----------|------|
| **ADMIN.md (this document)** | Which operations are privileged; their ability names, target resources, additional restrictions and exceptions; separation from ordinary domain management; migration of those privileges |
| [VENUE_AUTHORITY.md](VENUE_AUTHORITY.md) | The general authority-elevation model: base and additions, allowances, issuance, activation, expiry/revocation, deferred work, the venue's host power and the trusted-code boundary |
| [UCAN.md](UCAN.md) | Capability matching and enforcement, ownership, proof validation, granting and delegation, and federation |
| [AUTH.md](AUTH.md) | Authentication, identity evidence, sessions and assurance policy |

ADMIN.md is the design's single inventory of privileged operations.
VENUE_AUTHORITY.md refers here for that inventory rather than defining its
own operation list or admin ability taxonomy. Conversely, this document
refers there for elevation mechanics; classifying an operation as admin does
not implement or require the proposed activation model. An allowance can
cover ordinary domain capabilities too: elevation and admin classification
are separate concerns.

Subsystem documents retain their operation behavior and detailed contracts;
this inventory links to them and records the privilege classification.
Cross-cutting documents should reference the owning contract rather than
maintain competing definitions. The normative capability and granting
contracts remain COG-13 and COG-17 in covia-docs.

## Scope and naming

Use the ability `can: "admin/<area>/<action>"` for venue administration,
or `can: "admin/sudo"` for the existing explicit namespace switch. Keep
`with` as the resource being administered; there is no global admin role.
Managing hosted accounts is a venue resource even though the operation is
named `user:…`. Sudo retains its target-user resource and separate action
checks; its classification does not broaden what it permits.

| Family | Meaning |
|--------|---------|
| `admin/user` | Administer the venue's hosted accounts: registration, deletion, directory reads, authentication management and state transfer |
| `admin/adapter` | Administer the venue's installed adapters and modules |
| `admin/venue` | Maintain the venue process and store |
| `admin/sudo` | Explicitly enter another user's working namespace, with separately granted invocation and action authority |
| `admin/agent` | Direct tool execution under an agent's effective authority |
| `admin/token` | Administration across token kinds; `admin/token/mcp` retrieves a retained MCP bearer |

Ordinary workflow grants should name their domain abilities and resources.
They should not need an `admin` grant. An operator deliberately running an
administrative workflow can delegate the specific admin abilities it needs.

This is an ability hierarchy, not a new REST route, adapter, operation path
or lattice namespace. Existing operation names can remain. Moving a tool
into an `admin` skill is useful for discovery but does not authorize it.
Generic invocation still needs `invoke` for its target as well as each
action's authority; a directly bound tool still needs its action authority.

Convex 0.8.19's `Capability.abilityCovers` already supplies exact matching,
slash-boundary prefix matching and the top ability `*`. No new matcher is
needed. In examples, `admin/…` denotes the family; the parent grant is
`can: "admin"`, not the literal string `"admin/*"`.

- `admin` covers all administrative abilities on the resource it names,
  including future descendants. Prefer specific leaves for delegation.
- `admin/user` covers account administration, but only on its named resource.
- `agent`, `mcp`, `user` and other ordinary families do not cover abilities
  moved below `admin`.
- `*` still covers `admin/…`. Owners and unrestricted contexts still have
  their existing implicit authority. A rename alone does not require
  activation, a fresh login, human approval or a separate grant.
- `admin` does not imply `grant/admin`. Minting and chain delegation retain
  COG-17's distinct rules; ordinary UCAN use grants can still be passed on
  through a valid, narrower proof chain.

## Current venue-level admin functions

Here `V` means the hosting venue DID and `U` a hosted user's DID. These are
authorization resources, not necessarily paths callers can read as data.
The following are the enforced abilities. Operation paths remain unchanged.
Resource scopes are preserved; possible refinements are listed separately.

| Surface | Required ability and resource |
|---------|-------------------------------|
| `user:create` | `admin/user/create` on `V/users` |
| `user:delete` | `admin/user/delete` on `V/users` |
| `user:list`; operator view of `user:info`; another user's `user:authentication-list` | `admin/user/read` on `V/users` |
| `user:authentication-add`, `user:authentication-revoke` for another account | `admin/user/authentication-manage` on `V/users` |
| `user:export` for another account | `admin/user/export` on `V/users` |
| `user:import` | `admin/user/import` on `V/users` |
| `user:clone` | Source export permission plus `admin/user/import` on `V/users` |
| `venue:adapters`, `venue:adapter-enable`, `venue:adapter-disable`, `venue:adapter-configure`, `venue:module-load`, `venue:module-unload` | `admin/adapter/manage` on `V/adapters` |
| `venue:restart`, including executable jar handoff | `admin/venue/restart` on `V/process` |
| `venue:gc`, including status, cancel and backup options | `admin/venue/gc` on `V/store`; also `admin/venue/restart` on `V/process` when restarting |
| `user:sudo` into another namespace | `admin/sudo` on the target `U`, plus independent invocation and action authority |

Venue administration currently admits the venue itself, its agents within
their effective authority, or callers presenting a suitable venue-rooted
grant. An unrestricted venue agent therefore holds these privileges.
The venue does not acquire ambient access to every user's data from this
gate. It can root grants over hosted users, valid locally, as UCAN.md defines.

The account REST routes (`GET /api/v1/users`, `GET /api/v1/users/{did}`,
`GET /api/v1/users/{did}/authentications`, `DELETE /api/v1/users/{did}`) accept
delegations through `X-Covia-Ucans` and enforce the same checks as their
operations. Authentication remains separate from the presented grants.

Account operations have important exceptions:

- A direct user with its whole authority can inspect its own account and
  authenticators, manage its named account's keys, and export its own state.
  This is not venue-wide `user/*` authority. An agent or a restricted MCP
  token does not automatically qualify for those self-service exceptions.
- The operator account view includes venue bookkeeping that the self view
  omits. Listing all accounts is an administrative read.
- Export additionally needs a direct, unrecorded run using the published
  definition, with no agent/session/task context and caller equal to the
  acting user. Its result may contain secrets. A capability does not remove
  these restrictions. Clone uses its own source/target checks and does not
  return the export blob.
- Import requires venue authority even for the caller's own account and
  fills an empty account. Clone requires both sides' permission.
- Only the direct venue can override the final-active-authentication-key
  protection. An authentication-management delegation does not suffice.
- `venue:show-config` reads the published effective view with `crud/read` on
  `v/config`; it is not a privileged read of private configuration.

## Separately managed privileges

These controls are part of the inventory, not additions to the initial
admin families. A venue-owned resource does not automatically require an
`admin` ability. In particular, keep MCP management separate so a caller can
manage MCP without becoming a venue administrator. Also avoid making a broad
`admin` grant acquire MCP management merely by moving its ability.

| Surface | Existing ability and resource | Initial treatment |
|---------|-------------------------------|-------------------|
| Venue-scoped MCP server/catalog management | `mcp/manage` on `V/v/mcp` | Keep separate |
| `p2p:peers` / `p2p:connect` | `p2p/read` / `p2p/manage` on `V/p2p` | Retain; classify separately if a later change needs it |
| Bypass of outbound target checks across adapters | `outbound/any` on `V/outbound` | Retain explicit bypass permission; outside the initial rename |
| Optional Claude Code module's project `create` / `delete` | `claudecode/manage` on `V/claudecode/projects` | Retain separate operator permission; outside the initial rename |

Two user-resource actions are explicitly privileged: `admin/agent` permits
real tool execution under the agent's authority without a model deciding the
calls, and `admin/token/mcp` permits retrieval of a reusable MCP credential.
Both belong in `admin`; ordinary agent and MCP management remain separate.

`admin/token` is the parent for token administration across token kinds.
`admin/token/mcp` is the MCP-specific leaf, on `U/x/mcp/tokens/<name>`.
The ordinary prefix rule means existing `admin/token` grants still cover MCP
retrieval on matching resources and will cover future descendants. Prefer a
specific leaf when delegating access to only one kind. No other token-kind
leaf is currently defined, and every retrieval retains its source-authority
and storage restrictions.

| Surface | Current check | Treatment |
|---------|---------------|--------------------|
| `agent:step` | `admin/agent` on `U/g/<id>`; executes caller-supplied tool calls under the target agent's authority | Privileged execution under the agent's effective capabilities, unrestricted included |
| `mcp:get-token` | `admin/token/mcp` on `U/x/mcp/tokens/<name>`, token source authority, and retained bearer availability | Privileged credential retrieval specific to MCP tokens |
| `secret:extract` | `secret/extract` on own `s/<name>` and direct-owner, non-recorded-call restrictions | Retain its separate sensitive-operation permission |
| Self-service account key changes and whole-state export | Whole-owner identity exception described above | Preserve self-service; it grants no venue-level account administration |
| `mcp:create-token`, `mcp:update-token`, `mcp:delete-token` | `mcp/token` on `U/x/mcp/tokens/<name>`; creation checks issuer authority, update checks source authority; deletion only narrows | Retain independently managed token permissions |
| Agent configuration/controller changes | `agent/write` plus controller-authority checks where required | Retain separate agent management and authority checks |
| `auth:identity-token` | Caller identity, scope/sub-principal and signing-key custody checks | Identity issuance remains identity-bound; a generic admin grant must not permit impersonation |
| `hitl:respond` | Responder must hold its own whole authority; grant answers also follow granting rules | Approval remains attributable to the responder; admin authority must not manufacture someone else's consent |

`user:sudo` changes the working namespace, not the authenticated actor. It
does not grant all the target user's powers. Renaming it must preserve that
property; `admin/sudo` alone must never become an impersonation grant.
Requests through MCP tokens remain unable to switch to another user.

Sensitive domain actions need an explicit place in the review without all
becoming administration. Ordinary secret use, connected-account use,
financial transactions and scoped file writes remain domain abilities.
Held-key signing, especially `key/sign/raw` which can produce arbitrary
signatures, must remain visible as credential risk even if its name stays
outside `admin`. `key/sign/grant` and `grant/<ability>` retain their granting
semantics. Their authorization and custody contracts are in
[HELD_KEYS.md](HELD_KEYS.md) and UCAN.md.

Likewise, `skill/load` and `tool/load` explicitly admit instructions/tools
outside an agent's advertised surface. Keep them visible as trust controls;
changing their names or allowing an admin parent to cover them needs an
explicit decision. A controlled tool list does not replace resource checks.

## Direct-identity restrictions

Some privileged paths deliberately have no delegable administrative ability:

- `hitl:expire` is a direct-venue lifecycle operation.
- Grid `authenticateAs: "venue"` requires the direct venue; a grant cannot
  make an agent or administrator authenticate as it.
- `ucan:issue` with a non-empty `prf` passes on grants held by the venue and
  is restricted to the direct venue.
- Revoking the final active named-user key has the direct-venue exception
  described above.

List these as privileged restrictions rather than silently converting them
to capability checks. The admin ability names do not make them delegable.
The trusted-code boundary is defined in
[VENUE_AUTHORITY.md](VENUE_AUTHORITY.md#trusted-code). Consequently, loading
modules or handing off to a jar is a code execution privilege, not merely a
settings change.

## Where finer control is needed

The existing checks are explicit but often broad. The following are separate
policy changes to agree before implementation:

1. **Per-account administration.** Today most checks target `V/users`, not
   the specific account. Define a canonical child resource for one hosted
   account if delegation must be limited to it. Collection listing remains
   a collection privilege; clone checks its source and destination. Keep the
   venue's directory record distinct from the user's own data and the
   user-rooted `admin/sudo` resource. The exact child encoding is still open.
2. **Separate observation from mutation.** `venue:adapters` requires the same
   ability as module loading; GC status requires the same ability as
   collection. Add read leaves if operators need observation-only access.
3. **Separate maintenance from executable code changes.** Consider distinct
   rights for module load/unload and jar replacement instead of treating
   them as ordinary adapter management and restart.
4. **Explicit activation (deferred).** See the separate
   [elevation design](VENUE_AUTHORITY.md#design); this organisation of
   privileges preserves current owner and unrestricted-agent behavior.
5. **Audit coverage.** Audit logging is opt-in (`logging.audit`). User
   mutations, selected credential actions and venue administration have
   events, but this is not a uniform trail for every privileged check.
   For example, the venue adapter's authority rejection precedes its audit
   wrapper. Define success and denial coverage, actor, target resource,
   ability, request/job reference and authority source. Never record bearer
   credentials, secret values or arbitrary operation input. Decide retention
   and whether a privileged call must fail when mandatory recording fails.
6. **Authentication assurance.** A valid capability answers permission;
   fresh or stronger authentication is a separate requirement. Use AUTH.md's
   policy design rather than inferring assurance from an `admin` name.

## Migration and acceptance

The implemented scope organises venue-administration abilities under
`admin/user`, `admin/adapter`, `admin/venue` and `admin/sudo`. MCP and other
domain management remain separate from privileged agent execution
(`admin/agent`) and credential retrieval (`admin/token/mcp`). Activation and
finer resource scopes remain separate changes.

This is a direct cutover, with no compatibility aliases or automatic grant
migration. Replace grants, configured caps and grant-issuing clients separately:

| Former ability | Required ability |
|----------------|------------------|
| `user/create`, `user/delete`, `user/read`, `user/export`, `user/import`, `user/authentication-manage` | The same suffix under `admin/user/…` |
| `adapter/manage` | `admin/adapter/manage` |
| `venue/restart`, `venue/gc` | The same suffix under `admin/venue/…` |
| `user/sudo` | `admin/sudo` |

Existing signed grants cannot be rewritten. Broad `user`, `venue` and
`adapter` grants no longer cover the moved actions. Existing `admin` grants
cover the new descendants only on matching resources. Unrestricted and `*`
authority retain their existing behavior. Operation names, resource paths,
audit event names and stored user data are unchanged.

Acceptance must cover:

- Exact admin leaf, parent and unrelated-family grants; resource boundaries
  between users and between a user and the venue; `*` and owner behavior.
- Direct user, capped/unrestricted agent, ordinary delegated caller and
  restricted/unrestricted MCP token, including tokens created by tokens.
- REST job-free reads, `/run`, `/invoke`, bound MCP tools, agent tools and
  direct adapter calls: the same action has the same privilege requirement.
- Sudo's separate namespace, invocation and action checks; clone's two
  targets; GC with restart; every direct-identity and source-authority rule.
- Grant issuance and chain delegation, including `grant/admin/…`, expiry,
  unknown caveats and rejection of the former ability names.
- Scheduled and resumed work, narrowing/revocation where supported, and
  secret-safe success/denial audit records.

## Implementation references

- [Abilities](../../covia-core/src/main/java/covia/api/Abilities.java): shared ability vocabulary.
- [Engine](../src/main/java/covia/venue/Engine.java): resource authority, venue administration, sudo, direct-venue checks and outbound bypass.
- [UserAdapter](../src/main/java/covia/adapter/UserAdapter.java) and [CoviaAPI](../src/main/java/covia/venue/api/CoviaAPI.java): account administration and shared REST checks.
- [VenueAdapter](../src/main/java/covia/adapter/VenueAdapter.java), [P2PAdapter](../src/main/java/covia/adapter/P2PAdapter.java) and [MCPAdapter](../src/main/java/covia/adapter/MCPAdapter.java): shared facilities and tokens.
- [ClaudeCodeAdapter](../../covia-claude-code/src/main/java/covia/adapter/claudecode/ClaudeCodeAdapter.java): optional module's venue project registry.
- [AgentAdapter](../src/main/java/covia/adapter/AgentAdapter.java), [SecretAdapter](../src/main/java/covia/adapter/SecretAdapter.java), [AuthAdapter](../src/main/java/covia/adapter/AuthAdapter.java), [HITLAdapter](../src/main/java/covia/adapter/HITLAdapter.java) and [UCANAdapter](../src/main/java/covia/adapter/UCANAdapter.java): user privileges and identity restrictions.
- [Audit](../src/main/java/covia/venue/Audit.java): current audit event vocabulary and logging contract.
