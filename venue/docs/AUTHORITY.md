# Authority (design)

Every request, and every record of work (a job, a scheduled event), names four
principals by DID: whose authority applies, who called into it, who started
it, and where names resolve. The model is Convex's `*address*`, `*caller*` and
`*origin*` (CAD005). This is a design; none of it is implemented yet.

## The four fields

| Field | Meaning | Changes |
|-------|---------|---------|
| `authority` | The principal whose authority applies. Only this decides access. | When a call enters something that acts with its own authority, such as a callable |
| `caller` | The `authority` of the frame that called into this one; absent at the top level | On that call: the previous `authority` becomes `caller` |
| `origin` | The principal that started the top-level request | Never, down the chain |
| `owner` | The namespace names resolve in, recorded, never parsed from a DID | With `authority`, or by an explicit switch (`admin/sudo`) |

- Ordinary composition keeps all four: orchestration steps, sub-operations
  and tool calls run in the frame that started them.
- Every authority check (`Engine.authorityCovers`, `holdsAuthorityOf`) reads
  `authority` alone. `caller` and `origin` grant and refuse nothing.
- Grants a request presents are additions to its authority
  ([VENUE_AUTHORITY.md](VENUE_AUTHORITY.md)), carried beside these fields.

## Derived principals

An agent, an access token and a callable are each a principal that acts with
another principal's authority, narrowed by its own caps. The caps are read at
each check, and it holds nothing once its record is gone. Its record names the
principal it acts for, which may itself be derived: a token made through a
token acts for that token, so a chain needs no field of its own. Whatever a
request creates acts for the request's `authority`.

A derived principal's DID is refused at user admission and holds nothing by
its form alone, as an agent's does ([UCAN.md](UCAN.md) §5.4).

A callable is invoked as an operation, by its path
`<ownerDID>/x/callable/<name>`, and its call enters its authority
([CALLABLES.md](CALLABLES.md)). Calls nest: each call's `caller` is the
authority that made it.

## Cases

| Case | `authority` | `caller` | `origin` | `owner` |
|------|-------------|----------|----------|---------|
| Alice's own request | Alice | none | Alice | Alice |
| Through Alice's token | the token | none | the token | Alice |
| Alice's agent acting | the agent | none | the agent | Alice |
| That agent's harness | Alice, its controller | none | the agent | Alice |
| Bob acting for Alice (`admin/sudo`) | Bob, with Alice's grants | none | Bob | Alice |
| Bob calls Alice's guestbook | the guestbook | Bob | Bob | Alice |
| The guestbook calls Carol's callable | Carol's callable | the guestbook | Bob | Carol |

## What it replaces

- The request context's caller DID, which today names the authority in most
  cases, but the origin for an agent's harness or a sudo delegate.
- A separate authority on a context, the token chain on a context, and the
  two shapes of kept authority. Work kept for later records the four fields,
  and is rebuilt from them as the principals stand.
- The `source` of tokens and callables, and an agent's `controllerToken`.

## Open decisions

1. **Admission of derived principals.** An `accepts` list and a callers gate
   match DIDs exactly, so a token acting as itself loses what its issuer is
   admitted to; today it keeps that within its caps. A derived principal is
   either admitted where the principal it acts for is, within its caps, which
   agents would then gain too, or it is not.
2. **The recorded `caller`.** COG-8 defines `caller` as today's origin. Either
   it is renamed `origin` (a COG-8 change, a venue migration and an API change),
   or the new field takes another name.
3. **DIDs for tokens and callables**, such as `<ownerDID>:x:token:<name>` and
   `<ownerDID>:x:callable:<name>`, formed from their record paths as an agent's
   `<ownerDID>:g:<id>` is from `g/<id>`.
4. **A name reused after deletion.** Either the DID carries the record's id, or
   deleting a principal ends the work it left running.
5. **One name for the principal a derived principal acts for:** `controller`
   (agents, released) or `issuer` (tokens and callables, unreleased).
6. **State a namespace keeps with no capability of its own**, such as Python
   instances: reachable only with the owner's whole authority, which capped
   agents reach today and would lose.
7. **A short form** for principals the venue names under its own DID, such as
   `u:bob` for `did:web:<host>:u:bob`, and which form records store.
8. **Accounting by `origin`**, so a call spends the quota of whoever started it.
9. **One depth limit on nested dispatch**, as Convex has (256).
