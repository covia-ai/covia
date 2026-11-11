# MCP tokens

An MCP token is an API key for the venue's MCP endpoint (`/mcp`). It offers a
fixed set of operations as MCP tools and acts within caps set on it. Any user,
or an agent acting for one, can create tokens and hand them to an MCP client,
another agent included, which connects with `Authorization: Bearer <token>`
and needs no venue login, DID or UCAN. Venue authentication on `/mcp` works as
before, but a scoped token is the recommended way for an external agent to
connect.

The tokens are kept by the `mcp` adapter (`covia.adapter.McpTokens`).

## Identity and authority

- **Identity.** A token is not a principal. A request through it is its
  issuer's: a user, or an agent acting in its owner's namespace. Its namespace
  is the issuer's, and jobs and records attribute its work to the issuer.
- **Authority.** A request through a token may do what the token's caps allow
  within the issuer's authority, both read at each point of action
  (`Engine.tokenContext`). The caps are fixed on the token, while the issuer's
  authority may change underneath it: narrowing an agent issuer narrows its
  tokens, and deleting it deletes them. A token with `"unrestricted"` caps has
  all of its issuer's authority, and one with no caps has none.
- **Bound tools.** The token's tools are its palette, as an agent's configured
  tools are: calling one needs no `invoke` grant, but what it does must fit
  the token's authority.
- **Created work keeps the token.** What a request through a token creates
  acts later with the same authority, the token read at the time of action:
  - jobs it starts and events it schedules (the kept caller context,
    [JOBS.md](JOBS.md));
  - agents it creates or forks, whose controller's authority is narrowed by
    the token (`controllerToken`, shown by `agent:info` and `agent:list`);
  - tokens it creates, whose `source` is that token, so each token on the
    chain narrows them.
- **Revocation.** Emptying a token's tools, setting its caps to `[]`,
  deleting it or letting it expire takes effect at the next check, on work it
  already started too. A token that no longer stands narrows everything it
  created to nothing. A new token of the same name is another token.

### Acting with a principal's whole authority

Some checks ask whether a request acts with all of a principal's authority
(`Engine.holdsAuthorityOf`, [UCAN.md](UCAN.md) §5.4). A request through a
token holds its issuer's whole authority only when every token on its chain
is unrestricted. A restricted token's request therefore cannot:

- name a controller for an agent, or change an agent's config or controller;
- read or manage its user's account (`user:info` of itself, authentication
  keys, export and import);
- answer HITL asks (`hitl:respond`);
- make an agent act (`llmagent:chat` or `goaltree:chat` invoked directly).

Whatever its caps, a request through a token never acts for another user
(`user/sudo`), never counts as the venue, never reaches state a namespace keeps
with no capability of its own (such as Python instances and bot lists), and
cannot create a messaging bot, which acts with its creator's whole authority.
Its reach into another identity's resources, through admissions or presented
grants, must also be covered by its tokens' caps.

**Changing a token** needs its source authority: its issuer's whole authority
and the chain of tokens that created it. So a request through a token can
change the tokens it created but not itself, nor a token its issuer created
directly. Deleting a token only narrows, so `mcp/token` alone allows it.

### What revocation cannot take back

A credential minted while a token was in force outlives it, as it outlives an
issuer's change of mind: a UCAN issued through `ucan:issue` (bounded by the
token's caps, which need `grant/<can>`), an identity bearer from
`auth:identity-token` (which only an unrestricted token can mint), and a grant
minted by answering a HITL ask.

## Operations

| Operation | Input | Result | Needs |
|-----------|-------|--------|-------|
| `v/ops/mcp/create-token` | `name`, `tools`, `caps?`, `expires?` | the token, with `token` the bearer | `mcp/token` on `x/mcp/tokens/<name>` |
| `v/ops/mcp/update-token` | `name`, `tools?`, `caps?`, `expires?`, `rotate?` | the token; `token` only on rotate | `mcp/token` and the token's source authority |
| `v/ops/mcp/delete-token` | `name` | `{name, deleted}` | `mcp/token` on `x/mcp/tokens/<name>` |
| `v/ops/mcp/list-tokens` | none | `{tokens, total}`, never a bearer | `crud/read` on `x/mcp/tokens` |
| `v/ops/mcp/get-token` | `name` | `{name, token}` | `admin/token` on `x/mcp/tokens/<name>` and the token's source authority; only while bearers are kept |

- **Name:** `[a-z0-9-]{1,64}`, unique within a namespace, which a user shares
  with its agents.
- **Tools:** entries as in an agent's `config.tools`: an operation reference,
  or `{operation, name?, description?}`. A tool's MCP name is its `name`, else
  the operation's `toolName`, else its adapter string with `:` and `/` as `_`,
  and must match `[A-Za-z0-9_.-]{1,128}`. Two tools of one name are refused.
  The issuer must be able to read each operation's definition, and a tool the
  issuer can no longer read is left out of the list.
- **Caps:** `"unrestricted"` or a list of capabilities. Left out, the token has
  none.
- **Expiry:** milliseconds since the epoch, still to come. Left out, never.
- **The bearer** is `cvmcp_` followed by 32 random bytes in base58. It is
  returned once, to a caller waiting for the result, and the job record holds
  it hidden (`secretFields`). Rotating gives a new bearer and retires the old.
- **Who may create tokens:** any user or agent with `mcp/token`, never the
  public principal or the venue itself. A user holds it in its own namespace;
  a capped agent needs it in its caps.

## Storage

The adapter's state at `:adapters/mcp`:

```
tokens:  { <ownerDID>: { <name>: record } }
bearers: { <SHA3-256 of the bearer, hex>: [<ownerDID>, <name>] }

record: id, issuer, source?, tools, caps?, expires?, created, hash, raw?
```

- `id` is random and fixed at creation. A reference to a token is
  `{owner, name, id}`, and resolves only while that record stands, so a name
  reused after deletion never revives old references.
- `caps` missing means unrestricted and `[]` none (`covia.lattice.Caps`).
- A record and its `bearers` entry change in one transaction.
- `raw` is the bearer itself, kept only while `adapters.mcp.keepRawTokens` is
  on ([CONFIG.md](CONFIG.md#mcp-tokens-adaptersmcp)). The kept bearers are
  dropped when the adapter starts or is reconfigured with it off; a token
  created or rotated meanwhile has none until it is rotated.
- Deleting a user deletes their tokens, and deleting an agent the tokens it
  issued.

## `/mcp` with a token

- **Recognition:** a `cvmcp_` bearer is looked up in the token store, on the
  MCP routes only, and never as a venue credential. An unknown, deleted or
  expired token is refused with 401, and counts against the authentication
  throttle when rate limiting is on. The token's owner is admitted as for any
  request and checked against `mcp.auth.allowedDids`, and a token satisfies
  `mcp.auth.required`.
- **`tools/list`** shows only the token's tools.
- **`tools/call`** reaches only the token's tools; another name is answered as
  an unknown tool. A `ucans` argument is refused: the token's caps are all its
  request acts with.
- **Per token:** the limit on calls in progress, cancellation and MCP session
  ownership are each token's own, so two tokens of one issuer never share them.
- **Discovery:** the venue's protected-resource metadata lists `mcp-token`
  among its `bearer_token_profiles`.
