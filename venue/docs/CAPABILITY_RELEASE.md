# Capability release verification — 2026-11-19

This is the release check for the outbound and asset-creation changes.
The maintained contracts are [UCAN.md](UCAN.md), [OUTBOUND.md](OUTBOUND.md)
and [AUTHORITY.md](AUTHORITY.md).

## Shipped configurations

- Standard agent templates remain bounded by default: omitted caps are empty,
  `full` and `manager` explicitly use empty caps, and `reader` grants reads.
  `admin` deliberately opts into unrestricted controller authority. No ordinary
  template receives an automatic outbound or model grant.
- Telegram, Discord and Slack templates advertise tools without conferring
  authority. Their owner must compose the required domain, storage and outbound
  grants. The Telegram/Slack setup examples explicitly choose unrestricted
  caps; their bounded-caller guidance explains the additional grants.
- The AP demo's Alice, Bob, Carol and Dave perform local work under bounded
  grants. Their configured model calls use the harness; Dave's local grid
  orchestration does not need network permission.
- `local-dev.json` explicitly allows `localhost` for local providers and
  federation. This changes address policy, not capability scope.
- `docker-local.json` deliberately uses unrestricted public access for local
  development. Deployment and general example configurations contain no old
  `outbound/any` grants to migrate. A read-only public principal remains unable
  to send HTTP or create assets unless explicitly granted those rights.

## Local regression coverage

| Boundary | Tests |
|----------|-------|
| Asset creation: relative and qualified collection grants, legacy broad grants, wrong owner, hash-only and neighbouring path refusals | `AssetAdapterTest`, `PublicScopeTest` |
| Source read + asset output and A2A binding + egress requirements | `ArchiveAdapterTest`, `A2AAdapterTest` |
| Harness model succeeds while agent direct model call is refused | `OutboundAuthorityTest` with a local model API |
| Methods, destinations, redirects, bypass separation and live rights on pooled MCP/native federation clients | `OutboundAuthorityTest` |
| Domain permissions + outbound rights; downloads and background binding requests | Telegram, Discord, WhatsApp and Slack integration tests using local platform API fixtures |
| Presented granting rights cannot escape token or callable caps | `McpTokenAuthorityTest`, `CallableAdapterTest` |

These checks use local fixtures. They send no messages to real recipients and
make no paid model requests. On 2026-11-19, the full default Maven suite passed
4,414 tests with 276 skipped; the selected local integration run passed 159
with two skipped. The integration selection overlaps default coverage and is
not an additional count of unique tests. The covia-docs production build
passed its internal link/anchor checks; 36 relevant JSON configurations,
templates and skill/demo assets parsed successfully. This does not certify
every external provider or deployment.

## Deployment acceptance

Before promoting a specific deployment:

1. Inventory its bounded agents, access tokens, callables, public caps and
   messaging binding principals. Add method/destination grants only where
   their intended work sends HTTP. Reissue signed legacy bypass grants;
   editing a configuration cannot alter an already signed token.
2. Check the actual provider/API endpoints, including discovery, polling,
   file downloads and redirects. A private or local endpoint the operator
   configured passes host policy; one a caller names needs host policy
   approval, which grant scope does not override.
3. Exercise a configured model through a bounded agent and confirm that a
   direct model tool call by that agent is refused without an explicit grant.
4. Exercise MCP and federation with that deployment's bounded callers, then
   remove the outbound grant and verify that a new request is refused.
5. Exercise messaging and media with approved test accounts/recipients. Confirm
   that an unauthorised explicit send is refused and that background binding
   work uses its binding principal's current rights.

No remote deployment was changed by this review. Production-specific grants,
provider credentials and message delivery must be checked in the deployment's
authorised promotion run. Signed UCAN revocation is not implemented; use finite
expiries or revocable access tokens when withdrawal is required. Live agent,
token and callable caps remain a separate, immediately checked mechanism.
