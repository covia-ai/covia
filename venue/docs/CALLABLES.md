# Callables (design)

A callable is a function a principal publishes for others to call. It names
an operation of any kind (an orchestration, a configured Python function, an
HTTP call, an agent request) and runs it with its issuer's authority narrowed
by caps fixed on it, whoever calls it. A gate operation the provider chooses
decides who may call it. It is Covia's form of a Convex callable function
guarded by a trust monitor.

This is a design; none of it is implemented yet.

## Decisions

1. **Kept in the `callable` adapter's state.** The binding of a callable to
   its issuer's authority is adapter state at `:adapters/callable`, as access
   tokens are the `token` adapter's ([ACCESS_TOKENS.md](ACCESS_TOKENS.md)). It
   is not an operation in the owner's `o/`, which the owner's agents may be
   able to write.
2. **The caller is the invoker.** The call is the invoker's job and its work
   is attributed to the invoker, as Convex's `*caller*`.
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
6. **The gate is a capability gate.** It has the input, outcome and limits of
   `nb.gate` ([UCAN.md](UCAN.md#capability-gates-nbgate--covia216)), so one
   gate serves grants and callables alike. It runs with the callable's
   authority, as a Convex trust monitor runs as its actor, so it can consult
   the provider's private allow lists, block lists and usage records. A
   capability gate instead runs with a constrained form of the caller's
   authority (`RequestContext.forGateEvaluation`), and its evaluator
   (`JobManager.evaluateGate`) takes `owner` from that context; a callable's
   gate needs the invoker's namespace there and the callable's own authority.
7. **The target runs inside the call.** A call is one job; the target is bound
   to it as an agent's configured tool is, so running it needs no `invoke`
   grant.
8. **Work left running keeps the callable by reference.** Narrowing its caps
   narrows that work, and deleting it narrows the work to nothing, as for
   access tokens.

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
can serve several. `owner` keeps its meaning there, the invoker's namespace;
the callable's owner is in `operation`. The call is admitted only if the gate
succeeds. A gate that fails, cannot be resolved or runs past a capability
gate's time limit refuses the call, and its reason is returned.

### Standard gates

| Gate | Admits |
|------|--------|
| `v/ops/callable/gates/anyone` | Every caller, the public principal included |
| `v/ops/callable/gates/callers` | A caller listed in its `callers`, or acting in a listed namespace |

A gate takes parameters from an operation default. A provider publishes an
operation that adds them and names it as the gate:

```
o/team-gate: {
  name: "Team gate",
  operation: {
    adapter: "callable:gates/callers",
    default: { callers: ["did:key:z6Mk…", "did:web:example.com:u:bob"] }
  }
}
```

The evaluator builds the gate's input from the call and never sets
`callers`, so a caller cannot change the list. The test adapter's
`allowgate`, `denygate` and `amountgate` work as callable gates too.

### Kept authority

Work a call leaves running, such as a job the target starts or an event it
schedules, keeps the callable's authority with it
([JOBS.md](JOBS.md)). `Engine.keptAuthority` gains one key:

```
authority: {
  caller?, owner?, proofs?,   // the issuer, as for any separate authority
  token?:    {owner, name, id},   // the callable's source
  callable?: {owner, name, id}    // the callable whose caps narrow it
}
```

Rebuilt, it is the issuer's authority narrowed by the token and by the
callable's caps as they stand, and nothing once the callable is gone. Such
work belongs to the owner's namespace (see below), so it resumes there with
the invoker as its caller.

## What a call acts in

The call itself is the invoker's: a job in the invoker's `j/`, counted against
the invoker's job quota, which the invoker can watch and cancel. Inside it,
names resolve in the owner's namespace, so what the target reaches by name is
the owner's, within the callable's caps:

- jobs it records and events it schedules are the owner's, attributed to the
  invoker as caller, and count against the owner's quota;
- a human it asks (`hitl:request`) is the owner's, through the owner's `h/`;
- a secret it uses is the owner's, as `secret/use` in its caps allows.

## What a call never holds

As for a request through an access token
([ACCESS_TOKENS.md](ACCESS_TOKENS.md#acting-with-a-principals-whole-authority)),
the authority a call acts with is narrowed, so:

- **It never acts with its namespace's own authority.** State a namespace
  keeps with no capability of its own, such as Python instances, dictations
  and bot lists, is out of its reach
  (`RequestContext.actsWithNamespaceAuthority`), whatever its caps.
- **It holds its issuer's whole authority** (`Engine.holdsAuthorityOf`) only
  when its caps are unrestricted and every token on its source chain is.
- **It never counts as the venue**, even when the venue issued it. It reaches
  the venue's resources only as far as its caps cover them, as the first
  example below does.

## How a call runs

1. **Resolve** `callable` to its record. A callable that does not exist, and
   one with no gate whose `callable/call` the caller does not hold, are refused
   alike, so a private callable is not revealed.
2. **Admit** the caller: its authority covers `callable/call` on the
   callable's resource, or the gate, run with the callable's authority,
   succeeds. A gate's refusal is returned with its reason.
3. **Act** with the callable's authority: the invoker is the caller, the
   owner's namespace is where names resolve, and the issuer's authority
   narrowed by `source` and `caps` decides every check.
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
  gate: "o/friends"
}

callable:call { callable: "<aliceDID>/x/callable/guestbook",
                input: {path: "w/guestbook", value: "Hello from Bob"} }
```

The path resolves in Alice's namespace, and the caps refuse any other.

## Precedents

| Part | Follows |
|------|---------|
| Store, record, reference, `issuer` and `source`, the `x/` view, deletion with the issuer | Access tokens ([ACCESS_TOKENS.md](ACCESS_TOKENS.md)) |
| Authority narrowed by caps, read at each point of action; the bound target | Agents and their controllers ([UCAN.md](UCAN.md) §5.4) |
| The gate's contract and evaluation | Capability gates, `nb.gate` ([UCAN.md](UCAN.md#capability-gates-nbgate--covia216)) |
| The reference forms of `callable` | Agent references in `agent:request` |
| Caller and authority as `*caller*` and `*address*`; the gate as a trust monitor | Convex callable functions and trust monitors |

## Open questions

- **Live or pinned references.** `operation` and `gate` are references
  resolved at each call, so whoever can write what they name, such as an
  `o/` entry the owner's agents may write, changes what the callable runs or
  whom it admits, within its caps. Either a callable pins them when it is
  created or updated, keeping each definition's content hash (as
  [OPERATIONS.md](OPERATIONS.md) freezes a version), or they follow their
  binding and writing what they name is as sensitive as changing the
  callable. Pinning also fixes a `callers` list given as an operation default,
  so a list that changes often would live in the provider's data, which its
  gate reads.
- **Whose quota.** Jobs the target records count against the owner's quota,
  as work in the owner's namespace, not the invoker's.
- **Calls within calls.** A callable whose target calls another callable:
  which caller the inner gate sees, the invoker or the outer callable, and how
  deep a chain may go (access tokens stop at a fixed depth).
- **The `callers` gate and agents.** Listing a user admits that user's agents,
  whatever their caps. A provider who wants exact principals writes its own
  gate.
- **Calls across venues.** Calling a callable another venue hosts, through
  `grid:run`.
- **A provider's record of calls.** Whether calls are recorded where the
  provider can see who called, beyond what a gate records for itself.
