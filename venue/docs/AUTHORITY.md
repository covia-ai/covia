# Authority (design)

Every request names three principals by DID: whose authority applies, who
called into it, and who started it. The model is Convex's `*address*`,
`*caller*` and `*origin*` (CAD005). A record of work, such as a job or a
scheduled event, names the principal that called it. This is a design; none of
it is implemented yet.

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
- An access token is a credential, not a principal: a request through it is
  its issuer's, narrowed to the token's caps
  ([ACCESS_TOKENS.md](ACCESS_TOKENS.md)). Grants a request presents add to
  that request's authority alone ([VENUE_AUTHORITY.md](VENUE_AUTHORITY.md)).
  Both are carried beside these fields. Work that continues the request keeps
  both, and what the request creates keeps the token but never the grants, so
  it holds no more than the request did.

## Where names resolve

Names resolve in the namespace of the `authority`: a user's own, or the one an
agent or callable belongs to, read from its record and never from the form of
its DID. Every namespace is an account's, and every principal is an account or
acts for one, directly or through the principals it acts for.

## Derived principals

An agent and a callable are each a principal that acts with another
principal's authority, narrowed by its own caps. The caps are read at each
check, and it holds nothing once its record is gone. Its record names the
principal it acts for, which may itself be derived: an agent made by an agent
acts for that agent, so a chain needs no field of its own.

Whatever a request creates acts for the request's `authority`, narrowed by the
token the request came through, so it never holds more than its creator: an
agent another agent makes, and that agent's harness, stay within its creator's
caps, and what a request through a token creates stays within the token and
loses what the token gave once it is gone. The caps it is given must lie within
what the request may do, presented grants aside. That is checked when they are
set and whenever they change, as `ucan:issue` checks each capability against a
grant it holds, and caps the creator could not grant are refused rather than
stored.

An `accepts` list or a callers gate that names a principal admits whatever
acts for it, directly or through others, within that one's own caps. Being
admitted is part of the named principal's authority, which a derived principal
holds narrowed by its caps, so it uses an admission only where its caps cover
the act. A request through a token is admitted where its issuer is, within the
token.

A derived principal's DID is refused at user admission and holds nothing by
its form alone, as an agent's does ([UCAN.md](UCAN.md) §5.4).

A callable is invoked as an operation, by its path
`<ownerDID>/x/callable/<name>`, and its call enters its authority
([CALLABLES.md](CALLABLES.md)). Calls nest: each call's `caller` is the
authority that made it.

## Records of work

A job records the call that made it: its `caller` is the principal that
called it, which is the `authority` of the frame that made the call. A
scheduled event records the request that scheduled it the same way. So in each
of the cases below, a job the request makes names the `authority` as its
caller. A record names no `origin`; a job made within another job names that
job as its `parent` ([JOBS.md](JOBS.md)).

A record of work is kept in its caller's account, a job in that account's
`j/`: a user's own, and for an agent or callable the account it acts in. It
names no owner beside its caller: the account is the caller's, and the
caller's DID names it, as an agent's `<ownerDID>:g:<id>` does, so a reader can
find the record. It counts against that account's quota. This is COG-8's
`caller`, and COG-8's `owner` goes.

## A job's state

A job runs in a context: its frame, whose `authority` is the job's caller, the
token and grants its request presented, and its execution scopes (agent,
session, job and task). That context is the job's state, which the venue keeps
beside the job while it is unfinished, with what its operation keeps to carry
it to its end ([JOBS.md](JOBS.md#the-venues-job-entries)). No route reads it.
It is one state, whether the job runs on from its request or resumes after a
pause or a restart: the context the job runs in is the one kept. A scheduled
event keeps the context of the request that scheduled it in the same form, and
each fire runs in it.

The state holds principals, the token and grants, never the authority worked
out from them. Each time the job acts, its authority is worked out from them as
they stand then: it holds nothing once its caller is gone, loses what a token
or grant gave once that token is gone or the grant expires, and never holds
more than its request did. Entering a frame is checked once, at the call, so
work inside it carries on as the frame's `authority` stands, whatever becomes
of the right that let it in.

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

Its controller is the `authority` of the request that created it, narrowed by
the token that request came through, so an agent made by another agent, or
through a token, runs within that agent's authority or that token, never its
owner's whole authority.

## Cases

| Case | `authority` | `caller` | `origin` |
|------|-------------|----------|----------|
| Alice's own request | Alice | none | Alice |
| Through Alice's token | Alice, narrowed by the token | none | Alice |
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
- The caller context a job keeps only when its adapter asks, in two shapes of
  kept authority, and a scheduled event's own shape of the same. A job's state
  is the context it runs in.
- A record of work whose `caller` names whoever authenticated (a sudo
  delegate, or an agent for its harness) beside an `owner` naming the account,
  from which the venue works out what kind of principal acted.
- An `accepts` list that matches DIDs exactly, and a callers gate that admits
  a namespace it lists.
- `admin/sudo` as a switch of namespace bounded by the grants the delegate
  presents ([ADMIN.md](ADMIN.md), [UCAN.md](UCAN.md)), and the check of the
  delegate's grant again when work it left runs.

## Open decisions

1. **A DID for a callable**, such as `<ownerDID>:x:callable:<name>`, formed
   from its record path as an agent's `<ownerDID>:g:<id>` is from `g/<id>`.
   Whatever the form, it names the account, since a record of work names only
   its caller.
2. **A name reused after deletion.** Either the DID carries the record's id, or
   deleting a principal ends the work it left running.
3. **One name for the principal a derived principal acts for:** `controller`
   (agents, released) or `issuer` (callables, unreleased).
4. **State a namespace keeps with no capability of its own**, such as Python
   instances: reachable only with the owner's whole authority, which capped
   agents reach today and would lose.
5. **A short form** for principals the venue names under its own DID, such as
   `u:bob` for `did:web:<host>:u:bob`, and which form records store.
6. **One depth limit on nested dispatch**, as Convex has (256).
7. **Which acts need the user in person** under `admin/sudo`: answering
   approval requests, issuing an identity token, extracting a secret.
8. **Asking another principal's agent:** a message, whose run is the agent's
   own top-level request with no `caller`, or a call with the asker as
   `caller`.
9. **Reaching a scheduled event:** today the principal that scheduled it
   reaches it as its creator, where a job needs a capability on its record.
