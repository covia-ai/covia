# Authority (design)

Every request names three principals by DID: whose authority applies, who
called into it, and who started it. The model is Convex's `*address*`,
`*caller*` and `*origin*` (CAD005). A record of work, such as a job or a
scheduled event, names the principal that called it. This is a design; none of
it is implemented yet.

## The three fields

| Field | Meaning | Changes |
|-------|---------|---------|
| `authority` | The principal whose authority applies. Only this decides access. | When a call enters another principal's authority: a callable's call, or `admin/sudo` |
| `caller` | The `authority` of the frame that called into this one; absent at the top level | On that call: the previous `authority` becomes `caller` |
| `origin` | The principal that started the top-level request | Never, down the chain |

- Ordinary composition keeps all three: orchestration steps, sub-operations
  and tool calls run in the frame that started them.
- Every authority check (`Engine.authorityCovers`, `holdsAuthorityOf`) reads
  `authority` alone. `caller` and `origin` grant and refuse nothing.
- A token or a callable narrows the `authority` a request runs under
  ([Tokens and callables](#tokens-and-callables)), and grants a request
  presents add to it ([VENUE_AUTHORITY.md](VENUE_AUTHORITY.md)). Both are
  carried beside these fields. Work that continues the request keeps both, and
  what the request creates keeps the narrowing but never the grants, so it
  holds no more than the request did.

## Where names resolve

Names resolve in the namespace of the `authority`: a user's own, or the one an
agent belongs to, read from its record and never from the form of its DID.
Every namespace is an account's, and every principal is an account or an agent
acting for one, directly or through the agents it acts for.

## Agents

An agent is a principal that acts with its controller's authority, narrowed by
its own caps. The caps are read at each check, and it holds nothing once its
record is gone. Its controller may itself be an agent, so a chain needs no
field of its own. An agent's DID is refused at user admission and holds
nothing by its form alone ([UCAN.md](UCAN.md) §5.4).

An agent's identity is its id in its owner's namespace. An agent deleted and
created again with the same id is the same principal, as one reconfigured is:
whatever names it, such as a grant, an `accepts` entry or work it left, applies
to the agent that stands under that id when it is used.

## Tokens and callables

An access token and a callable are not principals. Each is a standing
permission from the principal that issued it to act with that principal's
authority, narrowed by its caps: a token for whoever presents it
([ACCESS_TOKENS.md](ACCESS_TOKENS.md)), and a callable for whoever may call it,
to run its one operation ([CALLABLES.md](CALLABLES.md)). The issuer is the
principal that created it: a user, or one of the user's agents.

A request through a token is its issuer's. A callable is invoked as an
operation, by its path `<ownerDID>/x/callable/<name>`, and its call enters its
issuer's authority: `authority` is the issuer and `caller` is whoever called.
Either way the token or callable is carried beside the fields and narrows each
check as it stands, allowing nothing once it is gone. Calls nest: each call's
`caller` is the authority that made it.

## What a request creates

Whatever a request creates, an agent, a token or a callable, acts for the
request's `authority`, narrowed as the request is by a token or a callable, so
it never holds more than its creator: an agent another agent makes, and that
agent's harness, stay within its creator's caps, and what a request through a
token creates stays within the token and loses what the token gave once it is
gone. The caps it is given must lie within what the request may do, presented
grants aside. That is checked when they are set and whenever they change, as
`ucan:issue` checks each capability against a grant it holds, and caps the
creator could not grant are refused rather than stored.

## Admission

An `accepts` list or a callers gate that names a principal admits whatever
acts with that principal's authority, directly or through others: its agents,
and requests through its tokens or calls of its callables, each within its own
caps. Being admitted is part of the named principal's authority, which each of
these holds only as narrowed, so it uses an admission only where its caps cover
the act.

## Records of work

A job records the call that made it: its `caller` is the principal that
called it, which is the `authority` of the frame that made the call. A
scheduled event records the request that scheduled it the same way. So in each
of the cases below, a job the request makes names the `authority` as its
caller: one made through a token, or within a callable's call, names the
issuer. A record names no `origin`; a job made within another job names that
job as its `parent` ([JOBS.md](JOBS.md)).

A record of work is kept in its caller's account, a job in that account's
`j/`: a user's own, and for an agent the account it acts in. It names no owner
beside its caller: the account is the caller's, and the caller's DID names it,
as an agent's `<ownerDID>:g:<id>` does, so a reader can find the record. It
counts against that account's quota. This is COG-8's `caller`, and COG-8's
`owner` goes.

## A job's state

A job runs in a context: its frame, whose `authority` is the job's caller, the
token or callable narrowing it, the grants its request presented, and its
execution scopes (agent, session, job and task). That context is the job's
state, which the venue keeps beside the job while it is unfinished, with what
its operation keeps to carry it to its end
([JOBS.md](JOBS.md#the-venues-job-entries)). No route reads it. It is one
state, whether the job runs on from its request or resumes after a pause or a
restart: the context the job runs in is the one kept. A scheduled event keeps
the context of the request that scheduled it in the same form, and each fire
runs in it.

The state holds principals, what narrows them and grants, never the authority
worked out from them. Each time the job acts, its authority is worked out from
them as they stand then: it holds nothing once its caller is gone, loses what a
token, callable or grant gave once it is gone or expires, and never holds more
than its request did. Entering a frame is checked once, at the call, so work
inside it carries on as the frame's `authority` stands, whatever becomes of the
right that let it in.

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

Its controller is the `authority` of the request that created it, narrowed as
that request was, so an agent made by another agent, through a token or within
a callable's call runs within what made it, never its owner's whole authority.

## Cases

| Case | `authority` | `caller` | `origin` |
|------|-------------|----------|----------|
| Alice's own request | Alice | none | Alice |
| Through Alice's token | Alice, narrowed by the token | none | Alice |
| Alice's agent calling a tool | the agent | none | the agent |
| That agent's harness, as configured | Alice, its controller | none | the agent |
| Bob acting for Alice (`admin/sudo`) | Alice | Bob | Bob |
| Bob with a grant from Alice | Bob, with the grant added | none | Bob |
| Bob calls Alice's guestbook | Alice, narrowed by the guestbook | Bob | Bob |
| The guestbook calls Carol's callable | Carol, narrowed by that callable | Alice | Bob |

## What it replaces

- The request context's caller DID, which today names the authority in most
  cases, but the origin for an agent's harness or a sudo delegate, and the
  invoker within a callable's call.
- The request context's namespace as a value apart from its authority, and a
  separate authority on a context.
- The caller context a job keeps only when its adapter asks, in two shapes of
  kept authority, and a scheduled event's own shape of the same. A job's state
  is the context it runs in.
- A record of work whose `caller` names whoever authenticated (a sudo
  delegate, an agent for its harness, a callable's invoker) beside an `owner`
  naming the account, from which the venue works out what kind of principal
  acted.
- An `accepts` list that matches DIDs exactly, and a callers gate that admits
  a namespace it lists.
- `admin/sudo` as a switch of namespace bounded by the grants the delegate
  presents ([ADMIN.md](ADMIN.md), [UCAN.md](UCAN.md)), and the check of the
  delegate's grant again when work it left runs.

## Open decisions

1. **State a namespace keeps with no capability of its own**, such as Python
   instances: reachable only with the owner's whole authority, which capped
   agents reach today and would lose.
2. **A short form** for principals the venue names under its own DID, such as
   `u:bob` for `did:web:<host>:u:bob`, and which form records store.
3. **One depth limit on nested dispatch**, as Convex has (256).
4. **Which acts need the user in person** under `admin/sudo`: answering
   approval requests, issuing an identity token, extracting a secret.
5. **Asking another principal's agent:** a message, whose run is the agent's
   own top-level request with no `caller`, or a call with the asker as
   `caller`.
6. **Reaching a scheduled event:** today the principal that scheduled it
   reaches it as its creator, where a job needs a capability on its record.
