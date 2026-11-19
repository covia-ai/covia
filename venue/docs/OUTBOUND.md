# Outbound HTTP authority

Every HTTP request made by a venue adapter requires ordinary outbound authority,
in addition to its operation, credential and domain permissions. This includes
bound tools, model providers, MCP, A2A, native venue federation, speech APIs and
messaging APIs. Supplying an inline credential or calling an API with no
credential does not remove this requirement.

## Resource and abilities

The resource is `<ownerDID>/outbound`; `outbound` is its relative spelling in
that owner's caps. The abilities form the ordinary slash hierarchy:

| Ability | Permits |
|---|---|
| `outbound` | Implemented outbound transports; currently HTTP |
| `outbound/http` | All supported HTTP methods |
| `outbound/http/get`, `/post`, `/put`, `/patch`, `/delete`, `/head`, `/options` | The corresponding actual HTTP method |

The leaves in the last row all start with `outbound/http/`. Other methods are
refused. There are no placeholder abilities for TCP, WebSocket or other future
transports. Existing Convex peer permissions and host checks remain separate.

For example, an agent may POST to one model endpoint with this cap:

```json
{
  "with": "outbound",
  "can": "outbound/http/post",
  "nb": {"url": "https://api.anthropic.com/v1/messages"}
}
```

A delegated UCAN uses the fully qualified resource, such as
`did:key:zAlice/outbound`. `nb.url` matches scheme, host, effective port and a
path boundary; `/v1` covers `/v1/messages`, but not `/v10`. Query parameters and
fragments are not restrictions. An origin-only caveat covers all paths there,
including protocol-generated identifiers with encoded separators. Path-bound
caveats refuse ambiguous path encodings. `nb.gate` can additionally inspect
the invocation's input. These are conditions on a grant, not global deny rules:
an independent unconditional covering grant can authorize the same use.

## Whose authority is used

| Work | Authority |
|---|---|
| An agent's configured model call | The harness's controller authority, as it stands now |
| An agent calling HTTP or a model as a tool | The agent's current bounded authority |
| A direct user or access-token call | That request's authority, including token/callable restrictions |
| MCP or native venue connection reused by another caller | The current invocation, never the caller that opened the connection |
| A remote Job's poll, continuation or cancellation | The originating request; recovery reconstructs its saved authority |
| Explicit messaging send/download tools | The tool caller |
| A messaging binding's polling, gateway setup, automatic replies or inbound media fetch | The binding's current principal, including its creator agent's caps when applicable |
| Login/OAuth provider ceremonies, DID verification, pinned local speech installation | Venue infrastructure authority; DID documents still always pass the host guard |

Selecting a model for an agent does **not** give that agent permission to call
the model. Agents created without caps have no outbound permission. A bounded
agent can therefore run its harness while being unable to make a direct model
call, even with an inline API key. An explicitly granted agent can make those
calls; an explicitly unrestricted agent retains its controller's ordinary
authority. No automatic model or outbound grant is added to agent templates.

The harness boundary does not confer the venue's administration authority on
an ordinary user's agent. Its model call uses the controller, including that
controller's credential permissions and applicable host restrictions.

## Enforcement and address policy

At the send boundary the runtime checks the actual method and transmitted URL.
Library-generated endpoint paths and retries are checked too. The generic HTTP
adapter follows redirects explicitly: each new destination and its resulting
method require authority. For example, a POST followed by a 303 requires GET
permission for the next hop. Model, MCP, A2A and native venue clients do not
silently follow redirects. Pinned installation downloads explicitly check each
redirect before sending it.

Transport connection pooling does not cache permission. Outbound scope, proof expiry,
token restrictions and gate conditions are checked at each send. Long-lived
streams do not get disconnected retroactively; a new HTTP request is checked
again. A failed check sends nothing for that attempt. Refusals retain the missing
capability and do not echo a resolved credential-bearing destination.

Ordinary egress does not override `http.allowedHosts`, `http.blockedHosts` or
private-address protections. Operators must allow local provider hosts when
ordinary users' harnesses or bindings need them. Credential uses still need
`secret/use` or `oauth/use`; messaging and other domain checks remain required.

`admin/outbound/bypass` on `<venueDID>/outbound` exempts a caller from the address
guard. It does **not** grant ordinary HTTP authority, credential use or domain
authority. Conversely, `outbound` never grants this administrative bypass.

This is the venue's HTTP authorization boundary, not a process network sandbox.
Trusted native adapters must call it. Administrative code execution, external
programs and non-HTTP drivers need their own execution/transport controls.

## Migration

- Bounded callers that previously relied on `invoke`, a secret grant or a
  messaging/domain grant alone now also need an appropriate `outbound/http`
  grant. Prefer method and URL restrictions over an unscoped parent grant.
- Replace `outbound/any` with `admin/outbound/bypass` in venue-issued grants and
  relevant caps. The old ability is inert; it is deliberately not an alias
  beneath the new ordinary `outbound` parent. Reissue signed grants as needed.
- Saved remote connections no longer reuse a historical bypass flag. Recovery
  uses saved caller authority and checks its current rights.

For adapter authors, `Engine.requireOutboundHttp(ctx, method, url)` is the
mandatory per-send check. Normalize caller-selected targets with
`Engine.outboundUrl` first, and send the same normalized spelling. The per-send
check uses the exact generated URL for capability matching and its origin for
host policy, so native protocol identifiers are not rewritten. Attach checks
through the library's request extension point, carry context explicitly across
asynchronous dispatch, and keep failures typed as authorization refusals.

See [UCAN.md](UCAN.md) for matching and grants, [MODELS.md](MODELS.md) for model
selection, and [CONFIG.md](CONFIG.md) for address-policy configuration.
