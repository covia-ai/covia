# Authority (design)

Every request names up to three principals by DID: whose authority applies,
who called into it, and who it authenticated as where it entered the venue.
The model is Convex's `*address*`, `*caller*` (CAD005) and `*origin*`. A
record of work, such as a job or a scheduled event, names the principal that
called it.

## The fields

| Field | Meaning | Changes |
|-------|---------|---------|
| `authority` | The principal whose authority applies. Only this decides access. | When a call enters another principal's authority: a callable's call, or `admin/sudo` |
| `caller` | The `authority` of the frame that called into this one; absent at the top level | On that call: the previous `authority` becomes `caller` |
| `origin` | The principal the request authenticated as where it entered the venue; absent from work the venue starts itself | Never: every context made for the request keeps it, a call included, and nothing a job or event keeps carries it, though a job's route still names where its request started |

- Ordinary composition keeps all three: orchestration steps, sub-operations
  and tool calls run in the frame that started them.
- Every authority check (`Engine.authorityCovers`, `holdsAuthorityOf`) reads
  `authority` alone. `caller` grants and refuses nothing, and `origin` decides
  only whether a user is present in person
  ([VENUE_AUTHORITY.md](VENUE_AUTHORITY.md#how-it-works-today), "In person").
- A configured Python function whose settings ask is told its frame's
  `authority` and `caller`, so one offered through a callable can keep each
  caller's state apart, as a Convex actor keys state by `*caller*`
  ([CONFIG.md](CONFIG.md#python-operations)).
- `origin` is the principal an HTTP, MCP or A2A door authenticated, or the
  issuer of the access token the request presented, since the venue never
  knows who holds a token; or the principal in-process code made the request
  for (`RequestContext.of`, `Engine.requestContext`). A context the venue
  makes for a principal itself (`Engine.context`) has none: the venue's own
  work, an agent's cycle, a message a bot brings, whose channel record is
  attribution, not evidence ([AUTH.md](AUTH.md)), and work a job or a
  scheduled event carries on later from what it kept. Neither does a gate's
  evaluation. Unlike Convex's `*origin*`, it can be absent. How a session
  signed in is its originating method ([AUTH.md](AUTH.md)).
- A token or a callable narrows the `authority` a request runs under
  ([Tokens and callables](#tokens-and-callables)), and grants a request
  presents add to it ([VENUE_AUTHORITY.md](VENUE_AUTHORITY.md)). Both are
  carried beside these fields. Work that continues the request keeps both;
  what the request creates is bounded by the narrowing when it is made, never
  by the grants ([What a request creates](#what-a-request-creates)).

## Where names resolve

Names resolve in the namespace of the `authority`: a user's own, or the one an
agent belongs to, read from its record and never from the form of its DID.
Every namespace is an account's, and every principal is an account or an agent
acting for one, directly or through the agents it acts for.

A user the venue names, `<webDID>:u:<name>` under the `did:web` DID of its
public hostname ([CONFIG.md](CONFIG.md#user-registration-users)), may be
given in short form wherever a principal or a path arrives as input: `u:bob`
for `<webDID>:u:bob`, `u:bob:g:helper` for his agent, and `u:bob/w/notes` for
his `w/notes`. That covers principals (an `accepts` list, a callers gate's
`callers`, an agent's `controller`, a grant's audience, the user, keyring
holder or job owner an operation or REST route names, a binding's or Claude
Code project's configured `user`, a key of the `secrets` bootstrap) and paths (data, agent, callable, operation, asset, secret and DLFS
references, and a capability's `with` in an agent's config, a token, a
callable, an approval request or a grant). It is expanded once, where it
arrives (`Engine.expandPrincipal`, `Engine.expandPath`), and the venue's
records hold and compare only the full DID, as does an operation reference or
job owner sent on to another venue. Data a user writes, such as a
callers gate's list in an operation's defaults, is kept as written and
expanded when it is read. A venue with no public hostname names no such users
and refuses the form. A name still in short form where nothing expands it is
refused, never read as a path in the request's namespace, and a grant someone
presents or stored that names one is left as written and covers nothing.

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

Asking an agent sends it a message. The request that asks is the asker's own,
and each of the agent's cycles is the agent's own top-level request, with no
`caller` and no `origin`, whoever asked. Who asked is recorded on each
message, so one cycle can serve several askers ([A2A_INTERACTION_AUTHORITY.md](A2A_INTERACTION_AUTHORITY.md)).

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
`caller` is the authority that made it. Nesting has no depth limit: work nested
deeper than the venue can run fails its job, and the work done counts against
quotas as any work does.

## What a request creates

Whatever a request creates, an agent, a token or a callable, acts for the
request's acting principal, narrowed by its own caps, and records only that
principal and those caps, never the token or callable the request came
through. So it never holds more than its creator:

- **A token or a callable** is given caps within what the request may do
  itself: its principal's authority as its token's, callable's or agent's caps
  narrow it, never widened by grants it presents. A request nothing narrows
  may give any caps. Each check reads them within the issuer's authority as it
  stands then. What a request through a token or a callable creates keeps its
  caps once that token or callable is gone.
- **An agent** acts with its controller's authority narrowed by its caps,
  read at each check, so an agent another agent makes stays within its
  creator, including whatever the creator is given later. Its harness acts
  with all of its controller's authority, which no caps bound, so a request a
  token or a callable narrows creates an agent only when their caps are
  unrestricted.

Changing a token or a callable needs a request acting with its issuer's
authority that covers its caps as they are and as they become.

## Admission

An `accepts` list or a callers gate that names a principal admits whatever
acts with that principal's authority, directly or through others: the
principal itself, the agents acting for it up their chain of controllers, and
requests through its tokens or calls of its callables (`Engine.actsFor`).
`"venue"` names the venue's own DID in the same way. Being admitted is part of
the named principal's authority, which each of these holds only as narrowed,
so a request uses an admission only where its narrowing covers the act: the
caps of every agent on its chain of controllers, whichever principal is
named, and the caps of its tokens and callable (`Engine.admissionCovers`). The
same bound holds for `"anyone"` and for every gate, checked before the gate
runs, since what a gate admits on is its own. The venue's public principal has
no caps, so nothing narrows what `"anyone"` admits it to.

## State within one interaction

State that lives only within one interaction, such as a live dictation, belongs
to the principal that opened it: only a request whose `authority` is that
principal reaches it. No capability names it, since there is nothing to
delegate, and opening it is an ordinary call of its operation.

## Records of work

A job records the call that made it: its `caller` is the principal that
called it, which is the `authority` of the frame that made the call. A
scheduled event records the request that scheduled it the same way. So in each
of the cases below, a job the request makes names the `authority` as its
caller: one made through a token, or within a callable's call, names the
issuer. A job made within another job names that job as its `parent`
([JOBS.md](JOBS.md)).

A record of work is kept in its caller's account, a job in that account's
`j/`: a user's own, and for an agent the account it acts in. It names no owner
beside its caller: the account is the caller's, and the caller's DID names it,
as an agent's `<ownerDID>:g:<id>` does, so a reader can find the record. It
counts against that account's quota. This is COG-8's `caller`; a record names
no `owner`, and none names the request's `origin`: the audit log does, where
it is not the `authority`, and so does the route an agent's turn or an
approval request keeps, whose first record is where the request started
([AGENT_CONTEXT.md](AGENT_CONTEXT.md) §1.2).

A job is reached by capability on its record ([JOBS.md](JOBS.md#job-capabilities)).
A scheduled event is reached by capability on `<account>/x/schedule/<id>`:
`crud/read` inspects and lists it, and `schedule/manage` cancels or triggers
it ([GRID_SCHEDULER.md](GRID_SCHEDULER.md)). Having scheduled an event grants
nothing, as having started a job does not.

A record of a call the venue keeps in an account, a job, its mirror or an
approval request, leaves out a principal that is the account it is kept in,
since where it is kept says so. A missing principal
means that account wherever the record is read against the structure that
holds it: a lattice read, a copy, an export or a clone carries it as stored, so
a clone's records are the clone's. A response that hands a client one record
on its own, such as a job's status, names the principal in full, since the
client has no account to read it against. A scheduled event, kept
venue-wide, names its caller. An agent's own records, its task rows, session
turns and timeline entries, always name their caller: there a missing caller
means a turn recorded before callers were kept, and `agent:fork` copies an
agent's history into another account, where a caller left out would read as
the new owner. Data a user writes is stored as given: the venue neither
leaves anything out of it nor fills anything in, since such data is more
likely to be copied between places.

## A job's state

A job runs in a context: its frame, whose `authority` is the job's caller, the
token or callable narrowing it, the grants its request presented, its request's
route, and its execution scopes (agent, session, job and task). That context is the job's
state, which the venue keeps beside the job while it is unfinished, with what
its operation keeps to carry it to its end
([JOBS.md](JOBS.md#the-venues-job-entries)). No route reads it. It is one
state, whether the job runs on from its request or resumes after a pause or a
restart: the context the job runs in is the one kept. A scheduled event keeps
the context of the request that scheduled it in the same form, but for the
route, and each fire runs in it: a fire is a request of its own, whose route
starts at the scheduler ([AGENT_CONTEXT.md](AGENT_CONTEXT.md) §1.2).

The state holds principals, what narrows them and grants, never the authority
worked out from them. Each time the job acts, its authority is worked out from
them as they stand then: it holds nothing once its caller is gone, loses what a
token, callable or grant gave once it is gone or expires, and never holds more
than its request did. Entering a frame is checked once, at the call, so work
inside it carries on as the frame's `authority` stands, whatever becomes of the
right that let it in.

## Acting for another user

`admin/sudo` on a user is the power to act as that user, as `sudo` is on
Unix. It is a call into that user: `authority` is the user, whole, `caller`
is the delegate, and `origin` is unchanged. The grants the delegate presents do not bound it. A narrower delegation is a plain grant, under which the delegate acts as
itself and what it creates is its own.

So the user controls what a request creates under `admin/sudo`, and work it
leaves runs as the user while the user stands, whatever becomes of the
delegate's grant, as a cron entry made under `sudo -u` runs as its owner.
Authority does not tell a request under `admin/sudo` from the user's own; the
request's route does, so an agent the request reaches is told who acted as the
user ([AGENT_CONTEXT.md](AGENT_CONTEXT.md) §1.2). An
action that needs the user in person, such as answering the user's approval
requests, decides that itself from the request's `origin`
([VENUE_AUTHORITY.md](VENUE_AUTHORITY.md#how-it-works-today), "In person"), as
`passwd` under `sudo -u` still asks for the user's own password.

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

Its controller is the acting principal of the request that created it, so an
agent made by another agent runs within that agent, never its owner's whole
authority, and only a request holding its controller's whole authority makes
one through a token or within a callable's call.

## Cases

| Case | `authority` | `caller` | `origin` |
|------|-------------|----------|----------|
| Alice's own request | Alice | none | Alice |
| Through Alice's token | Alice, narrowed by the token | none | Alice |
| Alice's agent calling a tool | the agent | none | none |
| That agent's harness, as configured | Alice, its controller | none | none |
| Bob acting for Alice (`admin/sudo`) | Alice | Bob | Bob |
| Bob with a grant from Alice | Bob, with the grant added | none | Bob |
| Bob calls Alice's guestbook | Alice, narrowed by the guestbook | Bob | Bob |
| The guestbook calls Carol's callable | Carol, narrowed by that callable | Alice | Bob |

Alice's and Bob's requests here are ones they signed in to make; the agent's
rows are its cycle, which the venue starts.
