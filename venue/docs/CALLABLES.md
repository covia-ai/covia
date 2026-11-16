# Callables

A callable is a function a principal publishes for others to call. It names
an operation of any kind (an orchestration, a configured Python function, an
HTTP call, an agent request) and runs it with its issuer's authority narrowed
by caps fixed on it, whoever calls it. A gate operation the provider chooses
decides who may call it. It is Covia's form of a Convex callable function
guarded by a trust monitor.

The `callable` adapter (`covia.adapter.callable`) keeps callables and runs
their calls; `Engine.callContext` builds the authority a call acts with.

## Decisions

1. **Kept in the `callable` adapter's state.** The binding of a callable to
   its issuer's authority is adapter state at `:adapters/callable`, as access
   tokens are the `token` adapter's ([ACCESS_TOKENS.md](ACCESS_TOKENS.md)). It
   is not an operation in the owner's `o/`, which the owner's agents may be
   able to write.
2. **A call enters its issuer's frame.** The call itself is the invoker's
   job, but what it does is the issuer's: its authority is the issuer and its
   caller is the invoker, as Convex's `*address*` and `*caller*`
   ([AUTHORITY.md](AUTHORITY.md)).
3. **The authority is the callable's**: its issuer's authority, narrowed by
   the access token that created it if any, and by its caps, all read at each
   point of action. This is how an agent acts with its controller's authority
   narrowed by its `config.caps` ([UCAN.md](UCAN.md) §5.4). Names resolve in
   the callable's owner's namespace, as Convex's `*address*`.
4. **Caps bound what it does.** A callable fixes no part of its input: the
   caller supplies all of it, and the caps decide what the target may do with
   it. Operation defaults remain purpose-shaping
   ([OPERATIONS.md](OPERATIONS.md)), and the gate may inspect the input.
5. **Who may call:** a request whose authority covers `callable/call` on the
   callable, or any caller its gate admits. Without a gate, only the first.
   A gate is an admission, so it admits a request an access token narrows
   only as far as its tokens' caps cover `callable/call` on the callable, as
   for any admission into another identity's resources
   ([ACCESS_TOKENS.md](ACCESS_TOKENS.md#acting-with-a-principals-whole-authority)).
6. **The gate is a capability gate.** It has the input, outcome and limits of
   `nb.gate` ([UCAN.md](UCAN.md#capability-gates-nbgate--covia216)), so one
   gate serves grants and callables alike. It runs with the callable's
   authority, as a Convex trust monitor runs as its actor, so it can consult
   the provider's private allow lists, block lists and usage records. A
   capability gate instead runs with a constrained form of the caller's
   authority (`RequestContext.forGateEvaluation`). Both are evaluated by
   `JobManager`, a callable's by `evaluateCallableGate`, and within either,
   gated grants stay fail-closed.
7. **The target runs inside the call.** A call is one job; the target is bound
   to it as an agent's configured tool is, so running it needs no `invoke`
   grant.
8. **Work left running keeps the callable by reference.** Narrowing its caps
   narrows that work, and deleting it narrows the work to nothing, as for
   access tokens.
9. **References are the provider's choice.** The record keeps `operation` and
   `gate` as given, and each call resolves them. A content-addressed asset
   (`a/<hash>`) never changes, so it is the recommended way to name exactly
   one definition. A reference to `o/` or `v/` follows its binding, so whoever
   can write that entry changes what the callable runs or whom it admits,
   within its caps.

## Data shapes

### The record

The adapter's state at `:adapters/callable`, which no path reaches:

```
callables: { <ownerDID>: { <name>: record } }

record: {
  id:           <random string, fixed at creation>,
  issuer:       <DID of the principal that created it: the owner or one of its agents>,
  source?:      {owner, name, id},          // the access token narrowing the request that created it
  operation:    <operation reference>,
  caps?:        [ {with, can, nb?}, ... ],  // missing: unrestricted; []: none (covia.lattice.Caps)
  gate?:        <operation reference>,
  description?: <string>,
  created:      <milliseconds since the epoch>
}
```

- **The name** is `[a-z0-9-]{1,64}`, unique within the owner's namespace,
  which a user shares with its agents.
- **A reference** to a callable is `{owner, name, id}`, as an access token's
  is, and resolves only while that record stands, so a name reused after
  deletion never revives a reference to the old one.
- **`issuer` and `source`** have the meaning they have on an access token. An
  issuer is never changed: a callable that should act with another
  principal's authority is another callable.
- **Bare references bind to the owner's namespace**: `operation`, `gate` and
  each cap's `with`, as an agent's caps and a grant's gate bind to their owner.
  A venue resource is named with its venue's DID.

### Abilities

| Ability | On | Permits |
|---------|----|---------|
| `callable/call` | `<owner>/x/callable/<name>` | Calling it without passing its gate |
| `callable/manage` | `x/callable/<name>` | Creating, changing and deleting it |
| `crud/read` | `x/callable`, or `x/callable/<name>` for one | Listing and viewing callables |

The owner holds these in its own namespace; its agents and access tokens hold
what their caps allow. Another principal holds `callable/call` only as a grant
from the owner.

### Operations

| Operation | Input | Result | Needs |
|-----------|-------|--------|-------|
| `v/ops/callable/create` | `name`, `operation`, `caps?`, `gate?`, `description?` | the callable's view | `callable/manage` |
| `v/ops/callable/update` | `name`, `operation?`, `caps?`, `gate?`, `description?` | the callable's view | `callable/manage` and the callable's source authority |
| `v/ops/callable/delete` | `name` | `{name, deleted}` | `callable/manage` |
| `v/ops/callable/list` | none | `{callables, total}` | `crud/read` on `x/callable` |
| `v/ops/callable/call` | `callable`, `input?` | the target's output | `callable/call`, or the gate |

- **Caps** are `"unrestricted"` or a list of capabilities, as on an access
  token. Left out at creation, the callable has none.
- **Gate:** an update sets `gate` to `null` to remove it.
- **The issuer** is the principal of the creating request, and `source` the
  token narrowing that request. The public principal never creates one.
- **Changing a callable** needs its source authority, its issuer's whole
  authority and the chain of tokens that created it, as changing an access
  token does ([ACCESS_TOKENS.md](ACCESS_TOKENS.md)). Deleting one only narrows,
  so it needs none.
- **`callable`** in a call is a reference in the forms an agent reference
  takes in `agent:request`: a bare name for one of the caller's own,
  `x/callable/<name>`, or `<ownerDID>/x/callable/<name>`. **`input`** is the
  target's input.

### The view

`x/callable` is the adapter's read-only view for a user
([ADAPTERS.md](ADAPTERS.md#user-visible-extension-routes)): each callable by
name.

```
x/callable: { <name>: {issuer, operation, caps, gate?, description?, created} }
```

It omits `id` and `source`, as `x/token` omits a token's id. Another user's is
read at `<did>/x/callable` under the usual cross-user delegation rules.

### The gate's input

```
{
  operation: "<ownerDID>/x/callable/<name>",  // the callable being called
  input:     <the call's input>,
  caller:    <the invoker's DID>,
  owner?:    <the namespace the invoker acts in, when it differs from caller>
}
```

This is a capability gate's input. `operation` names the callable, so one gate
can serve several. For a call made within another call, `caller` is the outer
callable's issuer. `owner` keeps its meaning there, the invoker's namespace;
the callable's owner is in `operation`. The call is admitted only if the gate
succeeds. A gate that fails, cannot be resolved or runs past a capability
gate's time limit refuses the call, and its reason is returned.

### Standard gates

| Gate | Admits |
|------|--------|
| `v/ops/callable/gates/anyone` | Every caller, the public principal included |
| `v/ops/callable/gates/callers` | A caller listed in its `callers`, or acting in a listed namespace |

A gate takes parameters from an operation default. A provider stores an
operation that adds them (`asset:store`) and names it as the gate by its
content hash, so the list stays as stored until the callable names another:

```
a/<hash>: {
  name: "Team gate",
  operation: {
    adapter: "callable:gates/callers",
    default: { callers: ["did:key:z6Mk…", "did:web:example.com:u:bob"] }
  }
}
```

A provider who wants to edit the list in place publishes the same operation
at an `o/` path and names that. The evaluator builds the gate's input from the
call and never sets `callers`, so a caller cannot change the list. The test adapter's
`allowgate`, `denygate` and `amountgate` work as callable gates too.

### Kept authority

Work a call leaves running, such as a job the target starts or an event it
schedules, keeps the callable's authority with it
([JOBS.md](JOBS.md)). Its kept authority (`Engine.keptAuthority`) is the
callable, since the record names the issuer and the source, and the call's
caller:

```
authority: { callable: {owner, name, id}, caller: <the call's caller> }
```

Rebuilt, it is the call's frame as it stands now: the issuer's authority,
narrowed by the source chain and the callable's caps, and nothing once the
callable is gone, with the kept caller as its caller and nobody present.

## What a call acts in

The call itself is the invoker's: a job in the invoker's `j/`, counted against
the invoker's job quota, which the invoker can watch and cancel. Inside it,
the call is its issuer's work, and names resolve in the owner's namespace, so
what the target reaches by name is the owner's, within the callable's caps:

- jobs it records and events it schedules are the issuer's, recording the
  issuer as their caller, kept in the owner's account and counted against the
  owner's quota, so the invoker does not reach them; an event fires in the
  call's frame as it stands then, so the caps must cover `invoke` on the
  operation the event runs;
- a human it asks (`hitl:request`) is asked by the issuer, through the
  owner's `h/`;
- a secret it uses is the owner's, as `secret/use` in its caps allows;
- an agent it asks is asked by the issuer, and told who called through which
  callable: the call carries the invoker's request on, so its route is the
  invoker's with the callable's step added, and that step names the callable
  and the call's caller, the authority that called it: for an agent's run,
  the controller whose authority it acts with
  ([AGENT_CONTEXT.md](./AGENT_CONTEXT.md) §1.2). A call within a call adds a
  step of its own, whose caller is the outer callable's issuer. Work the call
  leaves running and rebuilds later is not the call, and has none.

## What a call never holds

As for a request through an access token
([ACCESS_TOKENS.md](ACCESS_TOKENS.md#acting-with-a-principals-whole-authority)),
the authority a call acts with is narrowed, so:

- **It holds its issuer's whole authority** (`Engine.holdsAuthorityOf`) only
  when its caps are unrestricted and every token on its source chain is.
- **It never counts as the venue**, even when the venue issued it or calls
  it. It reaches the venue's resources only as far as its caps cover them, as
  the first example below does.
- **It never acts for another user** (`admin/sudo`), and what it holds beyond
  its scope (an admission, a presented grant, another callable's gate)
  reaches only as far as its caps cover it.
- **It never holds its invoker's authority.** An agent of the owner calling
  acts with the callable's authority, not its own, and what the call creates
  is its issuer's (`Engine.actingPrincipal`).
- **It hands on only what it holds.** An agent it creates takes the issuer as
  controller, and a controller keeps no callable's caps, so creating one needs
  the issuer's whole authority. Tokens and callables it creates need that
  authority too, as their issuer.

## Calls within calls

A call's target, and work a call left running, may call a callable. Each call
enters its own issuer's frame, with the authority that made it as its caller,
and only its own callable narrows it. The outer call's caps decide only
whether it may make the inner call: calling another identity's callable
through its gate is an admission, so those caps must cover `callable/call` on
it (decision 5). Nothing yet bounds how deep calls nest
([#708](https://github.com/covia-ai/covia/issues/708)).

## How a call runs

1. **Resolve** `callable` to its record. A callable that does not exist, and
   one with no gate whose `callable/call` the caller does not hold, are refused
   alike, so a private callable is not revealed.
2. **Admit** the caller: its authority covers `callable/call` on the
   callable's resource, or the gate, run with the callable's authority,
   succeeds. A gate's refusal is returned with its reason.
3. **Enter** the issuer's frame: the issuer's authority narrowed by `source`
   and `caps` decides every check, the owner's namespace is where names
   resolve, and the invoker's authority is the frame's caller.
4. **Run** the target, resolved as the issuer would resolve it, with `input`.
   Its output is the call's output.

## Examples

**An operator offering a Python function.** Running Python needs
`admin/execute/python` ([CONFIG.md](CONFIG.md), Python operations). The venue
publishes a callable that holds it for one function, and lets anyone call it:

```
callable:create {
  name: "health-score",
  operation: "v/ops/python/health/score",
  caps: [{with: "<venueDID>/python/operations/health/score", can: "admin/execute/python"}],
  gate: "v/ops/callable/gates/anyone"
}

callable:call { callable: "<venueDID>/x/callable/health-score", input: {...} }
```

**A user's guestbook.** Alice lets her friends append to one list and nothing
else:

```
callable:create {
  name: "guestbook",
  operation: "v/ops/covia/append",
  caps: [{with: "w/guestbook", can: "crud/write"}],
  gate: "a/<hash>"   // a callers gate listing her friends
}

callable:call { callable: "<aliceDID>/x/callable/guestbook",
                input: {path: "w/guestbook", value: "Hello from Bob"} }
```

The path resolves in Alice's namespace, and the caps refuse any other.

**A callable as a tool.** A callable is not an operation reference, so an MCP
token or an agent offers `v/ops/callable/call` as the tool, and the client
names the callable. Bob offers Alice's guestbook to a client of his:

```
mcp:create-token {
  name: "guestbook",
  tools: [{operation: "v/ops/callable/call", name: "guestbook"}],
  caps: [{with: "<aliceDID>/x/callable/guestbook", can: "callable/call"}]
}

tools/call guestbook { callable: "<aliceDID>/x/callable/guestbook",
                       input: {path: "w/guestbook", value: "Hello from Bob"} }
```

The token's caps let it reach the guestbook's gate and no other callable's.

## Precedents

| Part | Follows |
|------|---------|
| Store, record, reference, `issuer` and `source`, the `x/` view, deletion with the issuer | Access tokens ([ACCESS_TOKENS.md](ACCESS_TOKENS.md)) |
| Authority narrowed by caps, read at each point of action; the bound target | Agents and their controllers ([UCAN.md](UCAN.md) §5.4) |
| The gate's contract and evaluation | Capability gates, `nb.gate` ([UCAN.md](UCAN.md#capability-gates-nbgate--covia216)) |
| The reference forms of `callable` | Agent references in `agent:request` |
| Caller and authority as `*caller*` and `*address*`; the gate as a trust monitor | Convex callable functions and trust monitors |

## Open questions

- **Whose quota.** Jobs the target records count against the owner's quota,
  as work in the owner's namespace, not the invoker's.
- **A callable as an operation reference.** Accepting
  `<ownerDID>/x/callable/<name>` wherever an operation reference goes, so a
  tool takes the target's input and schema directly.
- **The `callers` gate and agents.** Listing a user admits that user's agents,
  whatever their caps. A provider who wants exact principals writes its own
  gate.
- **Calls across venues.** Calling a callable another venue hosts, through
  `grid:run`.
- **A provider's record of calls.** Whether calls are recorded where the
  provider can see who called, beyond what a gate records for itself.
