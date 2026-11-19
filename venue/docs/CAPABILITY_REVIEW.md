# Capability review — 2026-11-18

Review of the runtime at `33a61e3e`, with the corrections accompanying this
document. The maintained capability catalog is [UCAN.md](UCAN.md), not this
dated report. That catalog now includes module extensions, resource/action
mappings and the distinction between model features and access authority.

## Assessment

The resource/action model is coherent: slash-boundary hierarchy, independent
domain leaves, distinct granting rights, and checks at the point of action.
The principal risks are treating a grant's condition as a global prohibition,
confusing model/tool configuration with execution authority, and documentation
that predates controller authority, access tokens or bound-tool dispatch.

The review found and fixed one concrete issuance bypass. Other differences
below are existing policy choices or contract drift requiring a separate
decision, not claims that the runtime has implemented a stronger policy.

## Corrected findings

### Presented granting rights escaped token and callable caps

`Engine.requireGrantable` checked a root issuer's scope, then accepted a
presented `grant/<ability>` proof without applying token/callable narrowing to
that alternative. An empty-scoped MCP token could therefore issue a UCAN
outside its caps when a granting proof was supplied for its issuer. The new
token regression failed before the fix because issuance succeeded.

The proof branch now checks the standing token and callable caps before using
the granting right. The tests cover token issuance over another owner's
resource and callable issuance over its issuer's own resource, each with a
refusal and an explicitly permitted control. Ordinary presented use grants,
root authority and held-key signing retain their separate contracts.

Evidence: `Engine.requireGrantable`,
`McpTokenAuthorityTest.presentedGrantingRightCannotEscapeTokenCaps`, and
`CallableAdapterTest.presentedGrantingRightCannotEscapeCallableCaps`.

### The catalog and agent instructions omitted important distinctions

The central catalog omitted the `admin/execute` parent and the SQL,
messaging, Claude Code and Python extension inventory. Its short guidance
incorrectly said agents cannot issue grants, and its caveat examples used
unimplemented `maxSize` and `rateLimit` keys. The enforcement table suggested
`admin/agent` alone allowed cross-user stepping and omitted the target-create
check on forks. These descriptions now match the implementation.

The auth/capability skills now explain token/callable narrowing, conditional
grants, explicit skill/tool admission, and configurable public access. The
scheduling skill now describes live authority at fire time instead of a
snapshot of agent capabilities. Model documentation now identifies which
call paths actually enforce an `invoke` gate.

## Outbound follow-up — 2026-11-19

The model/outbound decision below has now been implemented as ordinary
`outbound/http/<method>` authority on `<ownerDID>/outbound`, including provider
SDKs and bound calls. Harness model calls use controller authority; selecting a
model grants no outbound authority to its agent. The address-policy bypass is
now `admin/outbound/bypass`. See [OUTBOUND.md](OUTBOUND.md) for the contract,
coverage, migration and limits. The remaining findings are unchanged.

## Decisions and remaining reconciliation

| Priority | Finding | Consequence and recommendation |
|----------|---------|--------------------------------|
| Resolved by outbound follow-up | HTTP action checks previously differed between generic lookup, bound tools and configured models. | Ordinary `outbound/http/<method>` now gates actual HTTP sends. Harness calls use controller authority; agent tools need their own grants. This does not make model presets into spend ceilings. See [OUTBOUND.md](OUTBOUND.md). |
| Medium | Asset creation uses two resource scopes. `asset:store` and REST registration check an empty resource; pinning, archive asset output and A2A import check `a/`. | A scoped `asset/store` grant on `a/` succeeds through some paths and fails through others. Recommend one owner-scoped collection resource for asset creation, with explicit compatibility treatment for existing grants. This review documents the current scopes without changing authorization behavior. Evidence: `AssetAdapter.handleStore`, `handlePin`, `ArchiveAdapter`, `A2AAdapter`, `CoviaAPI`. |
| High documentation priority | COG-13 in the local covia-docs checkout disagrees with current runtime contracts. | Reconcile it with the agreed authority model before calling the documentation fully consistent. Its catalog still names `x/mcp/tokens`, describes sudo as conferring no action authority, lacks callable/schedule entries and the `admin/execute` family, and its resource-extraction prose treats reference-free execution as requiring unscoped `invoke`. Current contracts are in [ACCESS_TOKENS.md](ACCESS_TOKENS.md), [AUTHORITY.md](AUTHORITY.md), [ADMIN.md](ADMIN.md) and UCAN.md. The separate covia-docs repository is unchanged by this review. |

## Deliberate distinctions to preserve

- **Positive grants and bounds:** alternatives authorise additively, while
  access tokens and callable caps bound the authority used through them.
  An unconditional covering grant makes a conditional alternative irrelevant.
  Unknown caveats fail closed; only `gate` and `url` are implemented.
- **Families and leaves:** `job/manage` does not imply `job/read`,
  `secret/write` does not imply `secret/use`, and `grant/X` does not imply `X`.
  Parents include future descendants. `admin/*` is not wildcard syntax;
  `admin` is the parent. Convex 0.8.20 already supplies this matcher.
- **Naming and ownership:** a bare caps path resolves in its context's
  namespace; a bare signed-proof path binds to its issuer. An agent principal
  is `U:g:id`, while its agent record is `U/g/id`. Public/catalog resources,
  venue-owned administration and another hosted user's data are distinct.
  Host file roots use `file://` resources in local caps; `ucan:issue` does
  not mint scheme resources. A local caps entry is therefore not necessarily
  a resource that the venue's UCAN issuance surface can delegate.
- **Admission and execution:** `accepts` permits talking, not reading or
  administering the agent. A callable's admission gate and a capability's
  `nb.gate` have different execution-authority contracts. Neither transfers
  the caller's authority to an agent being asked.
- **Human presence:** extraction, HITL responses, self-service identity actions
  and whole-state export retain identity/transport restrictions. No wildcard
  capability removes those restrictions. Asking one's own user family a HITL
  question is intentionally permitted without `hitl/request`.
- **Credential power:** `secret/use` without a destination constraint permits
  use wherever the adapter allows sending it. Raw signing can produce other
  kinds of signature even though `key/sign/raw` and `key/sign/grant` are
  siblings; the narrower names do not cryptographically isolate raw signing.
- **Operator facilities:** Python execution is venue-owned. Claude Code
  project execution is separately delegated and runs under the configured
  CLI permissions. Registered JDBC connections are shared even though the
  capability check uses the caller's `sql/<db>` resource; an unrestricted user
  can use them. Registering one is a deliberate venue-wide facility choice,
  and database credentials/read-only configuration remain part of its policy.
- **Visibility:** loading a skill, advertising a tool, declaring model
  `toolCalling`, or installing an adapter does not grant resource rights.
  Conversely, a pure bound tool can run with empty caps because it acts on
  no protected resource. `skill/load` and `tool/load` require explicit consent
  when expanding the advertised surface, even in an otherwise unrestricted
  agent context.

## Review coverage and validation

Inspected shared abilities; Convex 0.8.20's packaged capability matcher;
Covia's caps/caveat evaluator; root, proof, controller, token and callable
authority; issuance; Job and agent access; public admission; secrets, keys,
OAuth, files and DLFS; adapter/module enforcement sites; model dispatch; and
the local COG-13 alongside the subsystem contracts and agent-facing skills.
Documents, speech and SonnyLabs add no distinct ability family; their calls
reuse invocation, resource and identity checks. Dynamic `grant/<ability>`
and `admin/token/<access point>` names make this an extensible vocabulary,
not a closed enum of valid strings.

The focused authority suite passed 195 tests after the fix. The regression
was first observed failing against the original issuance check. The default
core and venue suites passed with 4,253 successful tests and 88 skipped
(`mvn -pl venue -am test -Dcovia.jdk.check=ERROR`, JDK 27.0.1.0.3).
Every shared ability constant appears in UCAN.md; the updated skill JSON and
documentation file links were checked. These are local checks, not
external-provider, cross-deployment or formal security verification.

Future additions should follow the capability contract and test guidance in
[ADAPTERS.md](ADAPTERS.md), including the action resource, bound-call behavior,
additional identity restrictions and both allowed and refused cases.
