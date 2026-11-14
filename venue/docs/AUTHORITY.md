# Authority (design)

Every request, and every record of work (a job, a scheduled event), names
three principals by DID: whose authority applies, who called into it, and who
started it. The model is Convex's `*address*`, `*caller*` and `*origin*`
(CAD005). This is a design; none of it is implemented yet.

## The three fields

| Field | Meaning | Changes |
|-------|---------|---------|
| `authority` | The principal whose authority applies. Only this decides access. | When a call enters something that acts with its own authority, such as a callable |
| `caller` | The `authority` of the frame that called into this one; absent at the top level | On that call: the previous `authority` becomes `caller` |
| `origin` | The principal that started the top-level request | Never, down the chain |

- Ordinary composition keeps all three: orchestration steps, sub-operations
  and tool calls run in the frame that started them.
- Every authority check (`Engine.authorityCovers`, `holdsAuthorityOf`) reads
  `authority` alone. `caller` and `origin` grant and refuse nothing.
- Grants a request presents add to that request's authority alone
  ([VENUE_AUTHORITY.md](VENUE_AUTHORITY.md)). They are carried beside these
  fields and never handed on to what the request creates.

## Where names resolve

Names resolve in the namespace of the `authority`: a user's own, or the one an
agent, token or callable belongs to, read from its record and never from the
form of its DID. A record of work lives in that namespace, so where it is kept
says whose it is.

## Derived principals

An agent, an access token and a callable are each a principal that acts with
another principal's authority, narrowed by its own caps. The caps are read at
each check, and it holds nothing once its record is gone. Its record names the
principal it acts for, which may itself be derived: a token made through a
token acts for that token, so a chain needs no field of its own.

Whatever a request creates acts for the request's `authority`, so it never
holds more than its creator: an agent another agent makes, and that agent's
harness, stay within its creator's caps. The caps it is given must lie within
the authority of the request's `authority`, presented grants aside. That is
checked when they are set and whenever they change, as `ucan:issue` checks each
capability against a grant it holds, and caps the creator could not grant are
refused rather than stored.

A derived principal's DID is refused at user admission and holds nothing by
its form alone, as an agent's does ([UCAN.md](UCAN.md) §5.4).

A callable is invoked as an operation, by its path
`<ownerDID>/x/callable/<name>`, and its call enters its authority
([CALLABLES.md](CALLABLES.md)). Calls nest: each call's `caller` is the
authority that made it.

## Acting for another user

`admin/sudo` on a user is the power to act as that user, as `sudo` is on
Unix. It is a call into that user: `authority` is the user, whole, and `caller`
and `origin` are the delegate. The grants the delegate presents do not bound
it. A narrower delegation is a plain grant, under which the delegate acts as
itself and what it creates is its own.

So the user controls what a request creates under `admin/sudo`, and work it
leaves runs as the user while the user stands, whatever becomes of the
delegate's grant, as a cron entry made under `sudo -u` runs as its owner. Acts
that need the user in person, such as answering the user's approval requests,
are kept for a request signed in as that user. That is a rule of how the
request authenticated, as `passwd` under `sudo -u` still asks for the user's
own password, not one of authority.

## An agent's harness

The harness is the venue's trusted code, running an agent
([VENUE_AUTHORITY.md](VENUE_AUTHORITY.md#trusted-code)). It reaches the agent's
run records, its state, timeline and sessions, as venue code. What is held
under the agent's own DID, such as its keyring, is the agent's, reached only
by its tool calls. Two things steer it, each with the authority of whoever
chose it:

- **Its configuration**, which only its controller may change: the model it
  calls, the secrets and context it loads and the tools it offers act with the
  controller's authority, and names in it resolve in the controller's
  namespace. For an agent its owner controls, that is the owner's.
- **The model's choices**: each tool call runs as the agent, bounded by its
  caps.

Its controller is the `authority` of the request that created it, so an agent
made by a narrower principal, such as another agent or a token, runs within
that principal's authority and never its owner's whole authority.

## Cases

| Case | `authority` | `caller` | `origin` |
|------|-------------|----------|----------|
| Alice's own request | Alice | none | Alice |
| Through Alice's token | the token | none | the token |
| Alice's agent calling a tool | the agent | none | the agent |
| That agent's harness, as configured | Alice, its controller | none | the agent |
| Bob acting for Alice (`admin/sudo`) | Alice | Bob | Bob |
| Bob with a grant from Alice | Bob, with the grant added | none | Bob |
| Bob calls Alice's guestbook | the guestbook | Bob | Bob |
| The guestbook calls Carol's callable | Carol's callable | the guestbook | Bob |

## What it replaces

- The request context's caller DID, which today names the authority in most
  cases, but the origin for an agent's harness or a sudo delegate.
- The request context's namespace as a value apart from its authority, and a
  separate authority on a context.
- The token chain on a context, and the two shapes of kept authority: work
  kept for later records the three fields, and is rebuilt from them as the
  principals stand.
- The `source` of tokens and callables, and an agent's `controllerToken`.
- `admin/sudo` as a switch of namespace bounded by the grants the delegate
  presents ([ADMIN.md](ADMIN.md), [UCAN.md](UCAN.md)), and the check of the
  delegate's grant again when work it left runs.

## Open decisions

1. **Admission of derived principals.** An `accepts` list and a callers gate
   match DIDs exactly, so a token acting as itself loses what its issuer is
   admitted to; today it keeps that within its caps. A derived principal is
   either admitted where the principal it acts for is, within its caps, which
   agents would then gain too, or it is not.
2. **COG-8's `caller` and `owner`.** `caller` there means today's origin and
   `owner` the namespace. Either `caller` is renamed `origin` and `owner`
   stays as where a job lives (a COG-8 change, a venue migration and an API
   change), or the new fields take other names.
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
8. **Accounting by `origin`**, so a call spends the quota of whoever started
   it, or by `authority`, as Unix charges whoever a process runs as.
9. **One depth limit on nested dispatch**, as Convex has (256).
10. **Which acts need the user in person** under `admin/sudo`: answering
    approval requests, issuing an identity token, extracting a secret.
11. **Asking another principal's agent:** a message, whose run is the agent's
    own top-level request with no `caller`, or a call with the asker as
    `caller`.
