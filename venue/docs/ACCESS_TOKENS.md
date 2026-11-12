# Access tokens

An access token is a scoped bearer that acts with its issuer's authority
within caps fixed on it. Any user, or an agent acting for one, can create one
and hand it to a client, another agent included, which presents
`Authorization: Bearer <token>` and needs no venue login, DID or UCAN.

A token is accepted only at the access points it carries an entry for. An
access point is an adapter that accepts tokens (`TokenAccessPoint`); its
entry in the token, `access.<name>`, is read by that adapter alone and may
narrow what the token's core allows, never widen it. The venue's MCP endpoint
is the first access point: an MCP token is an access token accepted at MCP
alone, whose entry lists the tools it offers.

The tokens are kept by the `token` adapter (`covia.adapter.token`), which
other adapters use as their infrastructure. The `test` adapter is an access
point too: it accepts any object as its entry and no route accepts tokens
there, so it serves only to exercise the rules with a second access point.

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
- **Created work keeps the token.** What a request through a token creates
  acts later with the same authority, the token read at the time of action:
  - jobs it starts and events it schedules (the kept caller context,
    [JOBS.md](JOBS.md));
  - agents it creates or forks, whose controller's authority is narrowed by
    the token (`controllerToken`, shown by `agent:info` and `agent:list`);
  - tokens it creates, whose `source` is that token, so each token on the
    chain narrows them.
- **Revocation.** Setting a token's caps to `[]`, emptying what an access
  point offers through it, deleting it or letting it expire takes effect at
  the next check, on work it already started too. A token that no longer
  stands narrows everything it created to nothing. A new token of the same
  name is another token.

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
(`admin/sudo`), never counts as the venue, never reaches state a namespace keeps
with no capability of its own (such as Python instances and bot lists), and
cannot create a messaging bot, which acts with its creator's whole authority.
Its reach into another identity's resources, through admissions or presented
grants, must also be covered by its tokens' caps.

**Changing a token** needs its source authority: its issuer's whole authority
and the chain of tokens that created it. So a request through a token can
change the tokens it created but not itself, nor a token its issuer created
directly. Deleting a token only narrows, so it needs no source authority.

### What revocation cannot take back

A credential minted while a token was in force outlives it, as it outlives an
issuer's change of mind: a UCAN issued through `ucan:issue` (bounded by the
token's caps, which need `grant/<can>`), an identity bearer from
`auth:identity-token` (which only an unrestricted token can mint), and a grant
minted by answering a HITL ask.

## Capabilities

Every token is one resource, `x/token/<name>` in its namespace, whichever
operation reaches it.

| Ability on `x/token/<name>` | Permits |
|------------------------------|---------|
| `admin/token/<access point>` | Working on tokens that carry that access point directly, through the `token:` operations, including fetching a kept bearer |
| `admin/token` | The parent of every `admin/token/<access point>` leaf, so every access point |
| `mcp/token` | Creating, changing and deleting MCP tokens through the MCP token operations |
| `crud/read` on `x/token` | Reading the user's view of its tokens, and listing them |

- **Direct work** on a token needs `admin/token/<p>` for every access point
  `p` it carries before and after the change: a bearer works at each of them.
  A user holds these in its own namespace; its agents need them in their caps.
  The operations work only in the caller's own namespace.
- **An access point's own operations** reach only tokens accepted at that
  access point alone, so they never touch a token they could not have made.
  A token with any other entry is refused there.
- **Fetching a kept bearer** also needs the token's source authority.

## Operations

| Operation | Input | Result | Needs |
|-----------|-------|--------|-------|
| `v/ops/token/create` | `name`, `access`, `caps?`, `expires?` | the token, with `token` the bearer | `admin/token/<p>` for each access point named |
| `v/ops/token/update` | `name`, `access?`, `caps?`, `expires?`, `rotate?` | the token; `token` only on rotate | `admin/token/<p>` for each access point before and after, and the token's source authority |
| `v/ops/token/delete` | `name` | `{name, deleted}` | `admin/token/<p>` for each access point it carries |
| `v/ops/token/list` | none | `{tokens, total}`, never a bearer | `crud/read` on `x/token` |
| `v/ops/token/get` | `name` | `{name, token}` | `admin/token/<p>` for each access point and the token's source authority; only while bearers are kept |

- **Name:** `[a-z0-9-]{1,64}`, unique within a namespace, which a user shares
  with its agents.
- **Access:** an object naming at least one access point this venue runs,
  each with the entry that access point accepts. An update's `access`
  replaces all of a token's entries.
- **Caps:** `"unrestricted"` or a list of capabilities. Left out, the token has
  none. Each entry has string `with` and `can` fields and optional `nb` caveats.
  `can` names one ability; granting read and write uses two entries, not an
  array of abilities in one entry.
- **Expiry:** milliseconds since the epoch, still to come. Left out, never.
- **The bearer** is `cvtok_` followed by 32 random bytes in base58. It is
  returned once, to a caller waiting for the result, and the job record holds
  it hidden (`secretFields`). Rotating gives a new bearer and retires the old.
- **Who may create tokens:** any user or agent holding the abilities above,
  never the public principal or the venue itself.

## The user's view

`x/token` is the token adapter's view for a user, read like any path
(`covia:read`, the read API): each of its tokens by name, with its issuer,
access entries, caps, creation time and expiry. It never shows a bearer, a
bearer's hash or a token's id, and it is read-only. Reading it needs
`crud/read` on `x/token`, or on `x/token/<name>` for one token; another
user's is read at `<did>/x/token` under the usual cross-user delegation
rules.

## Storage

The token adapter's state at `:adapters/token`, which no path reaches:

```
tokens:  { <ownerDID>: { <name>: record } }
bearers: { <SHA3-256 of the bearer, hex>: [<ownerDID>, <name>] }

record: id, issuer, source?, caps?, expires?, created, hash, sealed?,
        access: { <access point>: entry }
```

- `id` is random and fixed at creation. A reference to a token is
  `{owner, name, id}`, and resolves only while that record stands, so a name
  reused after deletion never revives old references.
- `caps` missing means unrestricted and `[]` none (`covia.lattice.Caps`).
- A record and its `bearers` entry change in one transaction. A change made
  for an access point's operations applies only while the record still
  carries the access points it was checked against.
- `sealed` is the bearer, sealed under the venue's secret store keys
  (`SecretStore.seal`), kept only while `adapters.token.keepRawTokens` is
  on ([CONFIG.md](CONFIG.md#access-tokens-adapterstoken)). The kept bearers
  are dropped when the adapter starts or is reconfigured with it off; a token
  created or rotated meanwhile has none until it is rotated.
- Deleting a user deletes their tokens, and deleting an agent the tokens it
  issued.
- Work a request through a token creates keeps the token's reference in its
  stored authority under the key `mcpToken`.

## Presenting a token

A route that is an access point recognises a `cvtok_` bearer in the token
store, never as a venue credential. The token must stand, carry an entry for
that access point, and that access point's adapter must be running; otherwise
the request is refused with 401 and counts against the authentication throttle
when rate limiting is on. The request's caller is the token's issuer and its
venue user the token's namespace, admitted as for any request.

## The MCP access point

An MCP token's entry is `access.mcp: {tools: [...]}`.

- **Tools:** entries as in an agent's `config.tools`: an operation reference,
  or `{operation, name?, description?}`. A tool's MCP name is its `name`, else
  the operation's `toolName`, else its adapter string with `:` and `/` as `_`,
  and must match `[A-Za-z0-9_.-]{1,128}`. Two tools of one name are refused.
  The issuer must be able to read each operation's definition, and a tool the
  issuer can no longer read is left out of the list.
- **Bound tools.** The token's tools are its palette, as an agent's configured
  tools are: calling one needs no `invoke` grant, but what it does must fit
  the token's authority.

### MCP token operations

These keep MCP's own arguments and reach only tokens accepted at MCP alone.

| Operation | Input | Result | Needs |
|-----------|-------|--------|-------|
| `v/ops/mcp/create-token` | `name`, `tools`, `caps?`, `expires?` | the token, with `token` the bearer | `mcp/token` on `x/token/<name>` |
| `v/ops/mcp/update-token` | `name`, `tools?`, `caps?`, `expires?`, `rotate?` | the token; `token` only on rotate | `mcp/token` and the token's source authority |
| `v/ops/mcp/delete-token` | `name` | `{name, deleted}` | `mcp/token` on `x/token/<name>` |
| `v/ops/mcp/list-tokens` | none | `{tokens, total}` of MCP tokens, never a bearer | `crud/read` on `x/token` |
| `v/ops/mcp/get-token` | `name` | `{name, token}` | `admin/token/mcp` on `x/token/<name>` and the token's source authority; only while bearers are kept |

### `/mcp` with a token

- **Recognition** is as above, at the MCP routes. The token's owner is also
  checked against `mcp.auth.allowedDids`, and a token satisfies
  `mcp.auth.required`. Venue authentication on `/mcp` works as before, but a
  scoped token is the recommended way for an external agent to connect.
- **`tools/list`** shows only the token's tools.
- **`tools/call`** reaches only the token's tools; another name is answered as
  an unknown tool. A `ucans` argument is refused: the token's caps are all its
  request acts with.
- **Per token:** the limit on calls in progress, cancellation and MCP session
  ownership are each token's own, so two tokens of one issuer never share them.
- **Discovery:** the venue's protected-resource metadata lists `mcp-token`
  among its `bearer_token_profiles`.

A venue agent can consume a peer's token through `v/ops/mcp/tools-list` and
`v/ops/mcp/tools-call`. Each takes the bearer as `token`, or as `bearerSecret`
naming a secret that holds it, never both. Discovery takes `server` and the
bearer; a call requires `server`, `toolName` (the exact discovered name,
including any custom alias) and an `arguments` object matching that tool's
schema, plus the same bearer. An empty arguments object is required even when
the remote tool takes no arguments.
