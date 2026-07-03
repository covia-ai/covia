# Read API — job-free lattice reads (design)

> Status: **implemented** (#177). `/api/v1/values/{read,list,slice,inspect,
> aggregate,count}` GET routes over one shared synchronous accessor, no jobs. Data
> routes (`read`/`list`/`slice`/`aggregate`/`count`) return `{exists, …}` with
> `count` as the single cardinality word, camelCase fields, and no request echo;
> `inspect` is the render route (`{result}`). `aggregate` ships as **count
> (optionally grouped)**; `covia:aggregate` is also an op. Absence is
> `200 {exists:false}`. `read` supports ETag/`304` conditional reads (value hash).
> **Still TODO:** numeric reductions (`sum`/`min`/`max`, pending a consumer).

## Problem

Every lattice read today goes through `POST /api/v1/invoke` → `JobManager`, which
creates **and persists a job record** to the caller's lattice
(`:user-data/<DID>/:j`) for *every* call — reads included. There is no TTL or
compaction. A read-heavy consumer therefore writes an unbounded job history into
etch: one live vault reached ~170k job records / ~2 GB etch, at which point GC
thrash pushed read latency from ~0.03 s to 6–7 s and froze a demo.

Reads are pure. They should not mint durable audit records. This API gives reads a
**job-free path**: authenticated, capability-checked, synchronous, zero
persistence.

## Principles

1. **Reads never create jobs.** No `Job`, no `persistJobRecord`, no entry in the
   caller's `:j` index. Writes stay on the job path unchanged.
2. **No new async plumbing.** Reads are synchronous. They do **not** go through
   `invokeInternal` or any `CompletableFuture` dispatch — the read cores already
   return a plain `ACell`; the future is only ever wrapped on at the op-dispatch
   seam.
3. **One accessor, two callers.** The existing `covia:read/list/slice/inspect`
   handlers and the new GET routes call the **same** synchronous accessor methods.
   No logic is duplicated and no read semantics can drift between the op form and
   the GET form.
4. **Governance is orthogonal to job creation.** The same `crud/read` capability
   check runs on the GET path as on the invoke path. Dropping the job does not drop
   the auth.
5. **Local-only.** GET reads resolve against local lattice state only
   (`Engine.resolvePath` is local — see OPERATIONS.md §"absence vs failure"). They
   never speculatively fetch from a remote venue, so there is no remote-error
   surface on this path.

## Architecture

The read cores are lifted from `private ACell handleX(...)` on `CoviaAdapter` to
callable synchronous accessors (same bodies, same `requireCap(ctx, input,
CRUD_READ)`, zero side effects):

```
CoviaAdapter
  read(ctx, input)     -> ACell     // covia:read  core
  list(ctx, input)     -> ACell     // covia:list  core
  slice(ctx, input)    -> ACell     // covia:slice core
  inspect(ctx, input)  -> ACell     // covia:inspect core
  aggregate(ctx, input)-> ACell     // NEW — count (optionally grouped)
```

- **Op path (unchanged):** `invokeFuture` switch cases call the accessor and wrap
  the result in `CompletableFuture.completedFuture(...)` at the dispatch seam. This
  is the *only* place a future touches a read.
- **GET path (new):** `CoviaAPI` resolves the adapter (`engine.getAdapter("covia")`),
  builds the caller `RequestContext` exactly like the existing job-free GET reads —

  ```java
  RequestContext rctx = AuthMiddleware.callerContext(ctx);
  AString bearer = ctx.attribute(AuthMiddleware.UCAN_BEARER_ATTR);
  rctx = AuthMiddleware.withTransportAuth(rctx, bearer, null);
  ```

  — calls the accessor, and serialises via `buildResult(ctx, 200, value)`. No job,
  no future, no adapter dispatch. The caller's ceiling and UCAN proofs ride along
  inside the accessor's `requireCapability`.

`aggregate` is one new pure function that walks the value the read already
materialises (`handleList` already does `cursor.get()` → full `AMap`), counting the
visited entries in a single pass — so it adds **no** extra lattice I/O and **no**
storage-model change. **Count is not a separate operation** — it is `aggregate`
with no `groupBy`.

## Routes

All GET, all reads, grouped under **`/api/v1/values/`** — a self-contained noun
namespace (values *are* what these read out of the lattice) that keeps generic
verbs out of the top level and leaves room for `PUT/POST /api/v1/values/…` write
versions later. `path` is a **query parameter** (not a path segment) because
lattice paths contain `/` (`w/health/appointments`).

| Route | Mirrors | Purpose |
|-------|---------|---------|
| `GET /api/v1/values/read?path=…` | `covia:read` | Literal value at a path |
| `GET /api/v1/values/list?path=…` | `covia:list` | Shallow view — keys/count of one node |
| `GET /api/v1/values/slice?path=…` | `covia:slice` | Paginated elements of a sequence (vector, set) or `{key,value}` entries of a map/Index |
| `GET /api/v1/values/inspect?path=…` | `covia:inspect` | Budget-controlled JSON5 render (single path) |
| `GET /api/v1/values/aggregate?path=…` | `covia:aggregate` (new op) | Count entries at a depth, optionally grouped |
| `GET /api/v1/values/count?path=…` | — | Fast path for the ungrouped count — see below |

Every route takes **`path`** as its single primary parameter — one uniform entry
point across the whole read surface.

`aggregate` is the single tally primitive. **`count` is kept as its own route** — it
is the by-far most common ask ("how many"), and a dedicated fast path means the
caller never reasons about `groupBy` at all. It takes only `path` + `depth` (no
`groupBy` — use `aggregate` for grouped counts). Underneath it is `aggregate` with no
`groupBy`; one accessor serves both.

### Common query parameters

| Param | Routes | Meaning |
|-------|--------|---------|
| `path` | **all** | Lattice path — relative (`w/health/appointments`) or DID-qualified (`did:key:…/w/health/appointments`) |
| `maxSize` | read | Byte ceiling; over it → `{truncated:true}` (default 1 MB) |
| `limit`, `offset` | list, slice | Pagination over keys / elements (default `limit` 1000, `offset` 0) |
| `budget`, `compact` | inspect | JSON5 byte budget / compact rendering |
| `depth` | count, aggregate | How many `get`-steps below `path` to visit (default 1) — the only recursion control |
| `groupBy` | aggregate | Field whose value forms the group key; may be a relative path (`foo/bar`) |

## Response shapes

Every **data** route (`read`, `list`, `slice`, `aggregate`, `count`) returns an
`{exists, …}` envelope; `count` is the one word for cardinality; fields are
camelCase; the request is **not** echoed (the client knows what it asked). `inspect`
is deliberately the odd one out — a *render* route that returns `{result}`
(annotated JSON5 **text**, not structured data), so it carries no `exists` and
encodes absence inside the rendered string. Serialised with the existing UTF-8
`buildResult`.

**read** — `GET /api/v1/values/read?path=w/agent/state`
```json
{ "exists": true, "value": { … }, "valueBytes": 512 }
```
`valueBytes` (the value's encoded size) is always included. Absent path →
`{ "exists": false, "value": null, "valueBytes": 0 }`.
Over `maxSize` → `{ "exists": true, "type": "Map", "value": null, "truncated": true, "valueBytes": 1234567 }`
— `type` is included on truncation (new) so the caller knows whether to fall back to
`list` (map) or `slice` (sequence) instead of guessing.

**list** — `GET /api/v1/values/list?path=w/health/appointments`
```json
{ "exists": true, "type": "Map", "count": 42,
  "keys": ["2026-06-01", "2026-06-02", …], "offset": 0 }
```
**Keyed** collections (maps and Indexes) → paginated `keys` + `count`. **Positional**
collections (vectors, sets) → `type` + `count` only; read their elements with
`slice`. Scalars → `type` only. So any keyed collection is enumerable by keys via
`list`; any sequence by elements via `slice`.

**slice** — `GET /api/v1/values/slice?path=w/events&offset=0&limit=20`
```json
{ "exists": true, "type": "Vector", "count": 190,
  "values": [ … ], "offset": 0 }
```
Works on any collection. **Positional** (vector, set) → `values` is the paginated
elements (sets iterate in canonical order, so `offset` is stable). **Keyed** (map,
Index) → `values` is the paginated `{ "key": …, "value": … }` entries. So a keyed
collection can be enumerated either by keys (`list`) or by entries (`slice`);
`read` fetches one value by key.

**inspect** — `GET /api/v1/values/inspect?path=w/health&budget=500`
```json
{ "result": "…JSON5…" }
```
Single path only. (`inspect` renders a value as annotated JSON5 for a human/LLM
reader — it returns *rendered text*, not data, so it stands slightly apart from the
data routes.)

**aggregate, count** — `GET /api/v1/values/aggregate?path=w/health/appointments&depth=2`
```json
{ "exists": true, "count": 190 }
```

**aggregate, grouped** — `GET /api/v1/values/aggregate?path=w/orders&depth=2&groupBy=source`
```json
{ "exists": true, "count": 644,
  "groups": {
    "nhs":     { "count": 596 },
    "letters": { "count": 48 }
  } }
```
Each group's value is a **metric object**, not a bare number — so when the
`sum`/`min`/`max` reductions land they add keys *inside* each group object and the
grouped shape never breaks.

**count** (fast path) — `GET /api/v1/values/count?path=w/health/appointments&depth=2`
```json
{ "exists": true, "count": 190 }
```

`exists` on `aggregate`/`count` means *a countable collection is present at `path`*:

- path resolves to nothing, or to a **scalar** (nothing to descend into) →
  `{exists:false}`, no `count`. (Note this differs from `read`, where that scalar is
  `{exists:true, value:…}` — here `exists` is about a *countable*, not a value.)
- path resolves to an **empty** collection → `{exists:true, count:0}`.
- path resolves to a collection but nothing sits at `depth` (ragged / too deep) →
  `{exists:true, count:0}`.

## aggregate — count, optionally grouped (`depth` only, no shape inference)

`aggregate` visits the entries at a chosen depth below `path` and counts them,
optionally partitioned by a caller-named field. `count` is always returned. It is a
single cheap walk over the already-materialised subtree.

Storage nests records under path segments, e.g.
`w/health/<category>/<date>/<record>`. `list` on `…/appointments` returns the
**date buckets** (direct children); a caller who wants "how many appointments" or
"how many per source" needs to count entries *below* the buckets, not the buckets.

**`depth` and the "entry" model.** A **step** is one `get` into a container —
uniform across maps (by key), vectors (by index), and Indexes (by key), matching
the semantics of `covia:read` path navigation. `depth=N` visits every value
reachable by exactly N successive `get`s; default `depth=1` is the direct
children/elements/entries. A branch shorter than N contributes nothing at depth N —
strict "exactly N steps", deterministic. Want several depths → several calls.

**No shape inference.** `depth` is the only recursion control. We do **not** guess a
record shape (no "deepest map = a record", no scalar-leaf detection); the caller
states how far down to visit, and naming `groupBy` is an explicit contract, never
inferred.

- **count** — `aggregate?path=P&depth=N` → `{exists:true, count:K}`, K = entries at
  depth N. Absent path → `{exists:false}`.
- **groupBy** — `aggregate?path=P&depth=N&groupBy=G` → adds `groups`, an object
  keyed by each distinct value of `G`, whose value is a **metric object**
  (`{count: …}` today; reductions add keys additively). Σ(group `count`) == top
  `count`. `G` may be a **relative path** (`foo/bar`), resolved by `get` into each
  visited entry. An entry lacking `G` groups under a **`null`** key (visible,
  lossless) rather than being dropped.
  - *Key representation:* `groups` is object-keyed, so group keys are the field's
    values as object keys — string-valued `G` (the common case: `source`, `status`,
    `category`) maps directly; a non-string `G` (number, boolean, the missing-field
    `null`) is coerced to a string key. If a use case ever needs type-preserving
    group keys, `groups` becomes an array of `{key, …metrics}` pairs — a localised
    change, since callers already read the per-group object.

### TODO — numeric reductions (`sum` / `min` / `max`)

Deferred: no named consumer yet (#177 review). The walk that computes `count` can
compute numeric reductions at the same cost, so this is a cheap add when something
asks for it. The intended grammar, recorded so it lands consistently:

- **`field=F`** — numeric field to reduce; accepts a relative path like `groupBy`.
- **`metrics`** — selects which reductions to add; **`count` is always present**,
  `metrics` only adds `sum`/`min`/`max` (never removes count). Requesting a numeric
  reduction without `field` is a **400** (explicit, no silent no-op).
- **`avg`** intentionally omitted — derivable as `sum/count`.
- **Coverage diagnostics** — an entry whose `field` is **absent** vs **present but
  non-numeric** is surfaced as two separate tallies, `fieldMissing` and
  `fieldInvalid`, **omitted when zero** (their mere presence flags partial
  coverage). Invariant: `numeric_contributors = count − fieldMissing − fieldInvalid`.
  "Numeric" is a CVM integer/double type check, not a shape guess.
- Under `groupBy`, reductions and diagnostics are added as further keys **inside
  each group's metric object** (alongside its `count`), and the top level carries
  the overall roll-up. Because group values are already objects, this is additive —
  the grouped shape does not change when reductions arrive.

## ETag / conditional reads — `read` only

The lattice is content-addressed, so a value at a specific `path` *is* a single
CAD3-hashed cell — a free, exact cache validator. Conditional reads are therefore
supported on **`read` only**:

- A `read` returns `ETag: "0x<value-hash>"` — the value's own CAD3 hash, nothing
  computed. A genuine **null / absent** value is a cacheable state too: its
  canonical hash is `Hash.NULL_HASH`, so polling an empty path can also 304.
- `If-None-Match: "0x<hash>"` matching → **304 Not Modified**, no body re-sent (the
  capability check still runs, since the value is resolved either way).
- **Truncated** (`maxSize`) reads are the one exception — the body is the truncation
  marker, not the value, so they carry no ETag (which also avoids a wrong-304 when a
  client varies `maxSize`).

**Not** applied to `list`/`slice`/`aggregate`/`count`/`inspect`: their bodies depend
on query params (`limit`/`offset`/`depth`/`groupBy`/`budget`), and the source cell's
hash alone would validate the *data* but not the *rendering* — a paginated poll
could get a wrong 304. We deliberately do **not** hash params or computed results, so
those routes are simply not ETagged (honest, rather than a subtly-broken validator).

## Capability & auth

Unchanged from the invoke path; only the job is dropped.

- `AuthMiddleware` already runs `before("/api/*")`, so caller identity, the
  public-read ceiling, and any presented UCAN are attached before the handler.
- The accessor calls `ctx.requireCapability(path, "crud/read")`. Unauthenticated
  callers carry the read-only public ceiling (`crud/read` + `asset/read`), so they
  can read public / their-own data and are denied cross-user paths unless a UCAN
  proof covers them — **exactly** as `covia:read` via invoke behaves today.
- Cap denied → **403**. Auth required (no token, public access disabled) → **401**.

## Namespace scope

`path` accepts two equivalent forms, both resolved by the same shared accessor
(`Engine.resolvePath`, OPERATIONS.md §4):

- **Own-namespace (relative):** `w/…`, `o/…`, `g/…`, `s/…`, `j/…`, `a/…` — resolved
  against the **caller's** user data.
- **DID-qualified (absolute):** the same paths prefixed with a full DID, e.g.
  `did:key:z6Mk…/w/health/appointments` or `did:web:example.com/g/my-agent/state`.
  The form for **cross-user reads** and for callers that want to be unambiguous
  about whose namespace they mean. A DID-qualified path at the caller's *own* DID is
  equivalent to the relative form.

DID-qualified reads are **first-class on every route** — the accessor is shared, so
a fully-qualified path works anywhere a relative one does.

> `did:web` note: a `did:web:<host>` prefix is an alias for the local venue's own
> identity (the `did:web` ⇄ `did:key` mapping from #167). It does not mean "fetch
> from `<host>`" — resolution stays local.

**Capability enforcement is what differs, not the plumbing.** The accessor calls
`requireCapability` with the full DID-URL resource, so:

- own DID (relative or qualified) → allowed under the normal ceiling;
- another user's DID → **denied (403)** unless the caller presents a UCAN proof
  whose resource covers that DID-URL path — identical to `covia:read` today.

**Local resolution only.** A DID-qualified path resolves against *this venue's*
state for that DID. If the DID is not hosted here the result is ordinary absence
(`200 {exists:false}`) — the read path never speculatively fetches from a remote
venue (Principle 5). Cross-**venue** reads remain a federation concern (grid ops),
not part of this surface.

The execution-scoped virtual namespaces are **rejected** — they are only meaningful
inside a running job/agent and have no caller context on a plain GET:

- `t/` — job-scoped scratch (needs a `jobId`)
- `n/`, `c/` — agent-run / session scoped (need `agentId` / `sessionId`)

Rejected namespace → **400** with a message naming the namespace.

**Secrets (`s/`) — encrypted values only; extract stays gated.** A `s/<name>` read
returns the **encrypted** stored value, gated by `crud/read`. That is safe to
expose: it is ciphertext, and the read path never persists it into a job record
(strictly safer than the invoke path). The dangerous operation is **decryption**,
which is *not* on this surface — plaintext only ever comes out via the
capability-gated `secret:extract` op, unchanged. A fuller secret-read policy (should
`s/` list at all, per-name caps, whether ciphertext exposure is itself worth
restricting) is deferred to a **separate later review**.

## Status codes

| Code | When |
|------|------|
| 200 | Success, **including** absence (`{exists:false}`) and truncation — parity with the ops |
| 304 | `read` only: `If-None-Match` matches the value's CAD3 hash (conditional read) |
| 400 | Malformed `path`/params, or an execution-scoped namespace (`t/`, `n/`, `c/`) |
| 401 | Authentication required (no token, public access disabled) |
| 403 | Capability denied for the requested path |

**Absence is `200 {exists:false}`**, matching `covia:read` — GET and invoke agree,
callers have one absence check, and a missing path is a normal read result, not an
error. (No 5xx on this path: reads are local-only, so there is no remote-fetch
failure to surface — see Principle 5.)

## Compatibility — field renames (breaking, 0.3.0 window)

Because op and GET share one accessor, aligning the vocabulary changes the existing
op outputs too. Intentional, folded into the 0.3.0 breaking release:

- `covia:list` / `covia:slice`: **`totalSize` → `count`** (the one cardinality word).
- `covia:read` (truncation): `type` now included (additive) so a truncated read is
  actionable. (`valueBytes` was already camelCase — no change.)
- `covia:list` on a **set** no longer dumps all values — a set gets `type`+`count`
  from `list`, and its elements page through `slice` like a vector.

`Index` needs no change: it is an `AMap` subtype, so `list`/`slice` already treat it
like a map (keys / `{key,value}` entries). The only Index-specific behaviour is
blob-key navigation (hex ⇄ Blob) in path resolution — the "5%" that differs from a
plain map.

Downstream consumers (frontend, SDK) that read these fields update in the same
release.

## Non-goals / out of scope

- **Writes.** `write/delete/append` stay on the job path (they *should* leave an
  audit record). Only reads move. The `/api/v1/values/` prefix reserves room for
  write verbs later.
- **Numeric reductions now.** `sum`/`min`/`max` are a documented TODO above, not v1.
- **Job-log TTL / compaction.** This removes reads as the dominant source of etch
  bloat, but the *existing* accumulated job history and future write-jobs still need
  bounding. Separate issue; this one is independent of it.
- **`aggregate` is also exposed as an op** (`covia:aggregate`), not GET-only, so MCP
  tools and in-agent transitions can compute tallies too. Same accessor; invoking
  the op goes through the normal job path (it *is* an invocation, reasonable to
  record). The **GET** form remains the job-free path for plain read clients.

## SDK migration (covia-sdk, separate repo)

`WorkspaceManager.read/list` today call `operations.run('v/ops/covia/read', …)` →
`POST /invoke` → a persisted job. Repoint them at `GET /api/v1/values/read|list`;
add `count`/`aggregate`. Keep the invoke-based methods for one release, deprecated,
for back-compat with older venues that lack the `/values/*` routes.

## Verification

- **Job-free proof:** a GET read leaves the caller's `:j` index unchanged (assert
  count before == after) — the core regression this issue exists to prevent.
- **Semantic parity:** GET `read/list/slice/inspect` return the same shape as the
  corresponding op over the same path (round-trip test against a live venue, per the
  "real venues over stubs" rule).
- **Envelope consistency:** data routes (`read`/`list`/`slice`/`aggregate`/`count`)
  return `exists`; `inspect` returns `{result}`; `count` is the only cardinality
  field; no `path`/`depth` echo; fields camelCase.
- **Auth parity:** anonymous caller reads public data (200), is denied a cross-user
  path (403), and is denied entirely when public access is off (401) — same verdicts
  as the invoke path.
- **enumeration by type:** `list` on a map and on an Index both return `keys`;
  `slice` on a vector and a set both paginate elements (`offset` stable across sets);
  a truncated `read` carries `type`.
- **aggregate:** `count` at a depth matches a hand-computed fixture; grouped counts
  satisfy Σ(group `count`) == top `count`; a missing `groupBy` field lands under the
  `null` key; absent **or scalar** path → `{exists:false}` (no `count`); empty or
  too-deep → `{exists:true, count:0}`; execution-scoped namespaces 400.
- **ETag (`read`):** a value read carries an `ETag`; unchanged value + `If-None-Match`
  → 304; changed value → 200 with a new `ETag`; `list`/`slice`/`aggregate` carry no
  `ETag`.
