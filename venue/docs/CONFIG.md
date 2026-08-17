# Venue Configuration Reference

The complete operator configuration reference for a Covia venue. Values live
in the JSON config passed to `covia.jar` (see `local-dev.json` for the
ephemeral dev shape and the venue-setup skill for persistent personal configs).

## Validation

Core venue configuration is validated completely before storage or the HTTP
listener is opened. A known field with the wrong type, an out-of-range value,
or an unsupported enum value is always a fatal startup error; Covia never
silently substitutes a default for malformed operator intent.

Unknown core fields warn by default and are ignored. This keeps a configuration
usable across mixed Covia versions while still making typos visible in operator
logs. Production and CI deployments can reject them:

```json
{
  "strictConfig": true
}
```

At the standalone document root, `strictConfig` validates the server document
and is inherited by every entry in `venues` unless that venue explicitly
overrides it. It may also be set on one venue only. Strictness applies to known
nested core blocks such as `auth`, `rateLimit`, `mcp`, and `rootPage`.
Adapter-specific configuration, file-root names, user bootstrap entries, and
secret names remain extensible rather than being mistaken for unknown Covia
fields.

## Public root page

The built-in Covia server summary is the default `/` page. A venue operator can
replace that public face without changing the API, discovery, or documentation
routes.

Redirect `/` to an operator application:

```json
{
  "rootPage": {
    "redirect": "https://app.example.com/"
  }
}
```

Or serve an operator-owned HTML file:

```json
{
  "rootPage": {
    "file": "/srv/venue-public/index.html"
  }
}
```

Exactly one of `redirect` or `file` is required. Redirects may be absolute
HTTP(S) URLs or same-origin paths beginning with `/`. A file must exist and be
readable at startup; relative paths resolve against the venue process working
directory. It is read for each request so an operator can update the page
without restarting the venue. The built-in diagnostics page remains available
at `/index.html`.

## Persistence

Venue state (lattice, agents, secrets, DLFS) is persisted via Etch store:

```json
{
  "store": "/data/venue.etch",
  "seed": "hex-ed25519-seed"
}
```

- `store`: `"temp"` (default, deleted on exit), `"memory"`, or file path
- `seed`: Ed25519 hex seed for stable venue identity. If omitted with a persistent store, auto-generated and saved to `venue.key` alongside the store file. On POSIX filesystems this raw seed is created with owner-only permissions (`0600`), and existing key-file permissions are repaired on each launch. On non-POSIX filesystems it inherits the platform ACL policy.

### Etch store policy (`etch`)

An optional `etch` block (Convex 0.8.11+) sets the Etch creation policy for
the venue's store — including **encrypted Etch v3**:

```json
{
  "store": "/data/venue.etch",
  "etch": {
    "version": 3,
    "cipher": "aes-256-ctr",
    "encryptIndex": true,
    "key": { "env": "COVIA_ETCH_KEY" }
  }
}
```

- `version` / `mapping` / `buildChains` / `publicKeyHint` / `cipher`
  (`none`, `aes-256-ctr`, `chacha20`) / `encryptIndex` pass through to
  Convex's `EtchConfig` unchanged.
- `key` is Covia-side: the 32-byte store encryption key as hex, sourced from
  `{"env": "VAR"}`, `{"file": "path"}` (operator-secured file), or an inline
  hex string (dev/test only — never commit key material).
- **Key identity** (consistent with venue key management): the master key is
  treated as an Ed25519 seed and identified by its derived public key — the
  same identifier scheme as venue identity keys and keystore aliases. New
  files stamp that identity as the Etch v3 `publicKeyHint` automatically
  (set `publicKeyHint` explicitly to pin your own label), and opening
  verifies the file's hint against the configured key, so a wrong key fails
  with a precise identification error, never a decrypt failure.
- The store key is **independent of the venue identity `seed`** — rotate and
  guard them separately. For an encrypted store, configure the identity via
  `seed`/`keystore` rather than relying on the auto-generated plaintext
  `venue.key` beside the store (the venue warns about that combination:
  encrypted data with the identity readable off the same disk).
- For an encrypted **vault**, keep content in the store: with
  `storage.content: file`, asset content bytes are written outside the
  encrypted store as plaintext files (the venue warns). The lattice default
  keeps everything — workspace, DLFS drives, secrets, content — inside the
  encrypted Etch file.

**Embedders** hold vault key material in their own code (KMS, passphrase
derivation, HSM) rather than config: compile the policy with a key
*function* and adopt a caller-opened store —

```java
EtchConfig policy = config.getEtchConfig(hint -> myKms.vaultKey());
VenueServer server = VenueServer.launch(venueConfig,
    EtchStore.create(vaultFile, policy));
```

No key material touches config, environment, or disk on this path; hint
management is the embedder's concern. The key function and the config `key`
field are mutually exclusive. A keyless encrypted policy constructs (so
embedder configs validate) but fails closed on an operator launch.
- The policy applies to file stores and `"temp"` stores; `"memory"` is not an
  Etch store and rejects an `etch` block.
- Fail-closed: an invalid field, unresolvable key, wrong-sized key, an
  encrypted cipher without a key source, or the wrong key for an existing
  encrypted file are all startup errors — never a silently-unencrypted or
  empty store. The store encryption key is independent of the venue identity
  `seed`; rotate and guard them separately.

## Venue identity

Identity resolution order: `seed` → `keystore` → `venue.key` next to a
persistent store → freshly generated. A venue on an ephemeral store
(`temp`/`memory`) without `seed`/`keystore` gets a **new DID every start —
by design** (#208): a stable identity is something the operator pins
explicitly, not something the venue persists behind their back.

For managed keys, point the venue at a PKCS12 keystore in the Convex format
(so keys are created/listed with the Convex CLI — `convex key generate`):

```json
{
  "keystore": {
    "path": "~/.convex/keystore.pfx",
    "alias": "<hex-public-key>",
    "storepass": "...",
    "keypass": "..."
  }
}
```

- `path`: defaults to `~/.convex/keystore.pfx` (env `CONVEX_KEYSTORE` fills absence)
- `alias`: required — the key entry to use (Convex convention: hex public key)
- `storepass` / `keypass`: env `CONVEX_KEYSTORE_PASSWORD` / `CONVEX_KEY_PASSWORD`
  fill absence; missing both config and env is a fatal startup error

Any keystore failure (missing file, bad password, unknown alias) is **fatal** —
the venue never silently falls back to a generated key. Likewise, booting an
existing store with a key that owns none of its venue state fails at startup
naming the store's real owner: venues are keyed by AccountKey, so a wrong key
would otherwise silently create a fresh empty venue and orphan the existing
data. Never commit keystore passwords; use env vars or gitignored dev configs.

## Network binding

```json
{
  "port": 8080,
  "bindAddress": "127.0.0.1"
}
```

- `port`: HTTP listen port (default `8080`).
- `bindAddress`: network interface the HTTP connector binds to. When omitted, the venue binds **all interfaces** (`0.0.0.0`) — reachable from the LAN. Set to `"127.0.0.1"` to restrict the venue to loopback (recommended when embedding the venue as a local subprocess). This is the socket bind address and is distinct from `hostname`, which is the venue's *advertised* public host used to derive `baseUrl`/DID.

## Browser origins (CORS)

`corsOrigins` controls which browser origins may read venue responses. The
legacy string form remains supported, while an array can name every legitimate
frontend explicitly:

```json
{
  "corsOrigins": [
    "https://app.example.com",
    "https://admin.example.com",
    "loopback"
  ],
  "allowPrivateNetwork": false
}
```

Supported forms:

- Omitted or `"*"` — allow every valid HTTP(S) origin. This remains the
  compatibility default, but is permissive for venues holding private data.
- One origin string — allow exactly that browser origin.
- An array of origin strings — allow any listed origin.
- `"loopback"` — allow literal `localhost`, `127.0.0.1`, or `::1` on any
  port, over HTTP or HTTPS. Matching never resolves DNS, so names such as
  `localhost.example` do not qualify.
- `false`, `"none"`, or `[]` — disable CORS entirely: requests still work,
  but no `Access-Control-Allow-Origin` header is emitted.

Specific-origin and loopback responses echo the accepted request origin and
emit `Vary: Origin`; a denied browser origin receives HTTP 403 without an
allow-origin header. Entries should be origins only (`scheme://host[:port]`),
not URLs with paths. For compatibility with the previous Javalin setting, a
bare configured host defaults to HTTPS. Invalid or ambiguous configuration
fails at startup rather than silently widening access.

`allowPrivateNetwork` emits the browser Private Network Access response header
(`Access-Control-Allow-Private-Network: true`) after the origin passes the CORS
policy, so a hosted https page can reach this venue on a loopback/private
address (Chrome/Edge/Firefox otherwise fail such a fetch with `TypeError`).

Its **default follows the bind**: a loopback-bound venue (`bindAddress` of
`127.0.0.1`/`localhost`/`::1`) answers PNA preflights automatically — that is
the "hosted demo → the venue you just started locally" first-touch flow, and a
loopback venue is reachable only from its own machine. A public- or LAN-bound
venue (including the default all-interfaces bind) keeps it **off**: PNA exists
to stop public origins reaching private-network services, and a public-internet
venue never receives a PNA preflight anyway. Set `allowPrivateNetwork` explicitly
to override in either direction — `true` to answer preflights on a non-loopback
dev venue (accepting that a public origin may then reach it across the private
network), or `false` to refuse them even on loopback. The checked-in
`local-dev.json` sets it `true` so the demo flow works on its all-interfaces bind.

> **Safari caveat:** Safari has no PNA opt-in and blocks localhost-from-https
> regardless of this header. The universal answer for a hosted page reaching a
> local venue is an https venue (or a tunnel); the header unblocks the
> Chrome/Edge/Firefox majority only.

## System tray

When `MainVenue` runs on a desktop (not headless), each venue gets a system
tray icon: hover shows the venue name, port and DID; the menu offers **Open
Venue** (status page in the browser — double-click does the same), **Close
Venue** (that venue; the process exits when the last one closes) and **Exit**
(all venues). Close/Exit run the full shutdown flush, same as SIGTERM.

Strictly best-effort — headless JVMs (Docker, CI, servers) and unsupported
desktops run without an icon, and a tray failure never takes a venue down.
Set `COVIA_NO_TRAY=1` to suppress it explicitly. See `covia.venue.Tray`.

## Embedded route policy

`VenueServer.launch(config, routeContributors)` lets a Java application mount
its own Javalin routes on the venue server. Covia 0.8 makes ownership explicit:
**a contributed route is raw Javalin by default, regardless of its path**.
Putting an application route under `/api/*` does not implicitly add Covia
authentication, admission, rate limiting, or lattice persistence.

This is a deliberate security boundary. An embedder controls its complete HTTP
surface and opts into only the venue services that route needs:

| `VenueRouteFeature` | Effect |
|---|---|
| `AUTHENTICATED_IDENTITY` | Require a venue-accepted bearer credential; expose both the authenticated credential identity and its mapped venue user; do not create/admit a venue user |
| `ADMITTED_USER` | Require a venue-accepted credential and admit its mapped venue user; unknown users follow `users.autoCreate` |
| `RATE_LIMITED` | Apply the configured per-venue-user HTTP token bucket (or per-IP when the route has no identity) |
| `LATTICE_SYNC` | Sync venue lattice state after the matched handler completes |

The features are independent and can coexist with application-owned
`RouteRole` values. Covia ignores roles it does not recognise:

```java
import static covia.venue.server.VenueRouteFeature.*;

VenueServer server = VenueServer.launch(config, List.of(routes -> {
    // Deliberately public; no Covia policy is inferred from the path.
    routes.get("/healthz", ctx -> ctx.result("ok"));

    // Authenticated product identity, without venue-user admission.
    routes.get("/api/product/me", product::me,
        AUTHENTICATED_IDENTITY, RATE_LIMITED);

    // Authenticated product mutation that writes lattice-backed state.
    routes.post("/api/product/consent", product::recordConsent,
        AUTHENTICATED_IDENTITY, RATE_LIMITED, LATTICE_SYNC);

    // A native venue user performing an admitted operation.
    routes.post("/api/operator/action", operator::act,
        ADMITTED_USER, RATE_LIMITED, LATTICE_SYNC);
}));
```

After Covia-managed authentication, extension handlers can read both sides of
the mapping:

```java
AString credentialIdentity = AuthMiddleware.getAuthenticatedIdentity(ctx);
AString venueUser = AuthMiddleware.getVenueUserDID(ctx);
```

They are equal for a self-sovereign `did:key` user. For a registered named-user
key they differ: the authenticated identity is the key DID, while the venue
user is the stable named DID used for admission, capabilities, job ownership,
MCP allowlists, and rate limiting. `getCallerDID(ctx)` remains a compatibility
alias for the venue user.

An extender may instead own authentication end-to-end. Its own Javalin `before`
handler verifies the credential and mapping, then publishes the result with
`AuthMiddleware.setRequestIdentity(ctx, authenticatedIdentity, venueUserDID)`.
That method only attributes the request: it does not verify credentials, admit
a user, or persist anything.

Native Covia REST, MCP, and A2A routes carry internal policy roles and retain
their normal admission, rate-limit, and sync behavior. Application routes do
not need—and generally should not use—the internal `COVIA_*` roles.

Framework-level exception mapping is also policy-neutral. Standard Javalin
HTTP exceptions retain Javalin's response formats, structured details, and
protocol headers (such as `Allow` on a 405). Unexpected exceptions are logged
with their stack trace server-side and returned through the same mapper with a
bounded class-and-message diagnostic. Covia safely renders the selected HTML
representation because Javalin's default mapper does not escape it. An extender
can register a more-specific Javalin exception mapper in its route registrar;
that mapper takes precedence over Covia's generic fallback.

### Python operations

The optional Python adapter exposes only operator-configured scripts. There is
no caller-supplied `eval` operation: allowing arbitrary operation metadata to
select source or a host path would turn ordinary venue invocation into host
code execution.

```json
{
  "modules": [{
    "path": "modules/covia-python-adapter-<version>-module.jar",
    "sha256": "<optional 64-hex module digest>",
    "config": {
      "library": "/usr/lib/libpython3.13.so",
      "operations": {
        "health/score": {
          "script": "/opt/getmine/python/health_score.py",
          "function": "main",
          "name": "Calculate health score",
          "description": "Calculates a score from a health record",
          "input": { "type": "object" },
          "output": { "type": "object" }
        }
      },
      "instances": {
        "maxPerUser": 8,
        "maxTotal": 128,
        "templates": {
          "health-session": {
            "script": "/opt/getmine/python/health_session.py",
            "functions": ["add_reading", "summary", "reset"]
          }
        }
      }
    }
  }]
}
```

This installs `v/ops/python/health/score`. Each function receives one Python
value converted from the Covia input and its return value is converted back to
a Convex value. Script globals persist for the venue lifetime and are released
on shutdown. Script paths may be absolute or relative to the venue process;
they are resolved at startup, loaded once, and a missing or invalid configured
script fails startup.

The optional `instances` object separately opts the operator into stateful,
caller-managed Python namespaces. It installs `create`, `list`, `call`, and
`close` operations under `v/ops/python/instances/`. `create` accepts a configured
template name; `call` accepts an instance `id`, an allowlisted `function`, and an
optional `args` vector of positional Convex values. The create operation's JSON
Schema advertises the configured template names, so MCP clients can discover
them without a separate host-filesystem API. Create and list results include the
instance's allowed `functions`, making the subsequent call surface discoverable.

Instance ownership follows the effective venue user (`RequestContext.getUserDID`),
while `createdBy` records the authenticated caller DID. This means an agent
sub-principal and its owner share the owner's instance namespace without losing
attribution. Other users receive the same not-found result whether an ID is
absent or belongs to someone else. The venue's synthetic public principal is a
shared authorization namespace, not an authenticated identity, and is always
rejected for instance management even if public invocation is configured.
Instances are process-local, disappear on restart, and are closed automatically
during venue shutdown. `maxPerUser` (default 8) and `maxTotal` (default 128)
bound retained native state.

Callers never provide source, script paths, or unrestricted global names. The
operator configures each template and must explicitly list its callable
functions. All four management operations also pass through ordinary,
path-scoped `invoke` capability checks. Omit `instances` entirely to expose none
of this management surface.

Python is a separate loadable module and is not present in `covia.jar` or the
standard Docker image. Listing the module enables it by default; `enabled:
false` leaves it inactive. It is also inactive with a warning when the stable
FFM API (Java 22+), native access, or a compatible CPython 3.10–3.14 shared
library is unavailable. Venue startup continues, and native tests skip when
these prerequisites are absent. Use `--enable-native-access=ALL-UNNAMED` in
production. `library` is optional when normal discovery succeeds; set it
explicitly for reproducible deployments.

Python runs in the venue process and is not a sandbox. Configured scripts and
native extensions have the venue process's filesystem, network, and memory
authority; only the operator should control them. Use process or container
isolation when code is not fully trusted.

### Upgrading an embedded venue from 0.7 or earlier

This route policy is a breaking security change for embedders that relied on
an `/api/*` prefix to inherit venue middleware. Before changing the dependency:

1. Inventory every contributed route; classify it as deliberately public,
   Covia-authenticated, or authenticated entirely by the application.
2. Add `AUTHENTICATED_IDENTITY` or `ADMITTED_USER` to every route using Covia
   credentials. Do not use URL placement as policy.
3. Add `RATE_LIMITED` where the venue limiter is part of the route's abuse or
   cost protection. Enabling `rateLimit` in config alone does not select a raw
   contributed route.
4. Add `LATTICE_SYNC` to handlers that directly mutate lattice-backed state.
5. Test missing, invalid, expired, and wrong-audience credentials; verify public
   routes remain public and authenticated product identities are not silently
   provisioned as venue users.
6. Re-test native REST and MCP admission separately from product authorization.

Treat a dependency-only upgrade with no route audit as unsafe: it can turn a
previously protected contributed route into a raw route without changing its
URL or handler.

## Rate limiting

```json
{
  "rateLimit": {
    "enabled": true,
    "rps": 100,
    "burst": 300,
    "maxConcurrentJobsPerUser": 100,
    "blockMs": 3000
  }
}
```

Two independent backpressure controls, keyed per caller identity (all anonymous
callers share the venue `:public` DID → one bucket).

- **Request rate** (`rps`, `burst`) — a per-caller token bucket on native Covia
  REST/MCP/A2A routes and contributed routes carrying `RATE_LIMITED`. A denied
  request short-circuits with **429 + `Retry-After`** before any handler runs.
  Deliberately coarse/high — it's a flood backstop, not a normal-traffic gate;
  the job cap is the precise control.
- **Concurrent jobs** (`maxConcurrentJobsPerUser`) — admission control on
  top-level invokes: a caller at the cap **blocks** up to `blockMs` for a slot
  to free (a job completing releases it), then sheds with **429 + `Retry-After`**.
  Sub-jobs (orchestrator / agent fan-out, which carry a parent job id) are
  **exempt**, so internal fan-out is never throttled. Set `blockMs` under typical
  client read timeouts so a saturated caller gets a clean 429, not a socket
  timeout. Set the cap to `0` to disable it.

`enabled` defaults **on** for a LAN/public bind and **off** for a loopback bind
(the embedded-venue case, where the only caller is a trusted local process); an
explicit `enabled` always wins.

## Public access (`auth.public`)

```json
{
  "auth": { "public": { "enabled": true, "caps": "unrestricted" } }
}
```

Anonymous callers are attributed to `<venueDID>:public` under the public
capability scope: unset `caps` → the secure read-only default; an explicit
`{with, can}` array → that scope; `"unrestricted"` → no scope (loopback
dev only). Authenticated callers hold the public-user grants ambiently
(covia#254) — the scope governs both. See `docs/UCAN.md` §4.7.

## MCP authentication

MCP discovery is always public. `GET /.well-known/mcp` advertises the
streamable HTTP endpoint and its effective authentication requirement, while
`GET /.well-known/oauth-protected-resource/mcp` publishes RFC 9728-shaped
protected-resource metadata. Neither document exposes the configured DID
allowlist.

```json
{
  "mcp": {
    "auth": {
      "required": true,
      "allowedDids": [
        "did:key:z..."
      ]
    }
  }
}
```

- `required` defaults to the inverse of `auth.public.enabled`. Setting it to
  `false` cannot override a venue-wide `auth.public.enabled: false` policy.
- A non-empty `allowedDids` list implies `required: true`. Bearer
  authentication runs first, including temporal and audience validation, then
  the authenticated caller DID is checked against the list.
- Missing or invalid MCP credentials receive HTTP 401 with a
  `WWW-Authenticate: Bearer` challenge pointing to the protected-resource
  metadata. An authenticated DID outside the allowlist receives HTTP 403.

Covia currently accepts its DID/UCAN bearer profiles rather than acting as an
OAuth 2.1 authorization server. The protected-resource document therefore
uses standard RFC 9728 fields where truthful and carries the Covia bearer
expectation in the namespaced `_meta["ai.covia/authentication"]` extension.
It deliberately does not advertise a fictional `authorization_servers`
entry. A future OAuth bridge can add that standard field without changing the
discovery URLs.

## User registration (`users`)

Authentication proves control of a DID; it does not create a venue account.
By default, a previously unknown authenticated DID receives HTTP 403 with an
actionable registration message, before a job or user state is persisted.

```json
{
  "users": {
    "autoCreate": false,
    "bootstrap": {
      "alice": {
        "authenticationKeys": ["did:key:z6Mk..."]
      }
    }
  }
}
```

- `autoCreate` defaults to `false`. Keep this setting for private and
  production venues, and provision accounts explicitly with
  `v/ops/user/create`.
- Set `autoCreate` to `true` only when authenticated first-use registration is
  intended, such as a public test venue. The checked-in `local-dev.json` opts
  in explicitly.
- `bootstrap` provisions named venue users and their public `did:key`
  authenticators before HTTP starts. It is first-use only: once a user has any
  authenticator history, later startup never adds, revokes or reactivates keys.
  Private keys never belong in venue configuration or lattice state.

A runtime user ID is always a DID and may use any DID method. `user:create`
accepts a full DID directly (for example a self-sovereign `did:key`) or a
venue-managed `username`. A username requires a public `hostname` and derives
`did:web:<hostname>:u:<username>` — for example
`did:web:venue.example.com:u:alice`. `user:create` and `user:list` are
venue-administrative operations: invoke directly as the venue, or present a
venue-issued UCAN covering `<venueDID>/users` with `user/create` or
`user/read`. Operator code can use `engine.venueContext()` to invoke the
built-in adapter; the [deployment guide](../../deploy/README.md#admit-users-at-runtime)
shows the recorded-job path. An operator-installed adapter may use the same
mechanism. OAuth callbacks are trusted venue provisioners and create the same
did:web-managed account explicitly.

A venue-managed named user may authenticate with any active public key bound
to its authentication-directory record. The self-issued JWT uses the stable
named DID for both `iss` and `sub`, the target venue DID as `aud`, and the
registered multikey in `kid`. Active methods are published in
`/u/<username>/did.json`. `user:authentication-add`,
`user:authentication-revoke`, and `user:authentication-list` manage the
bindings; self-management is allowed, while cross-user changes require
`user/authentication-manage` on `<venueDID>/users`. Revocation retains an
audit tombstone and takes effect on the next request.

Java clients use the same key without hand-building JWTs:

```java
VenueAuth auth = VenueAuth.namedKeyPair(
    keyPair,
    "did:web:venue.example:u:alice",
    venueDID);
Venue venue = Grid.connect("https://venue.example", auth);
```

The strategy signs a fresh short-lived, audience-bound token for each request;
`mintToken()` is available for transports that need the raw bearer credential.

Registering a full external DID admits that identity to use the venue; it does
not transfer control of the DID to the venue. A self-sovereign user signs their
own UCAN roots. For username-created
`did:web:<hostname>:u:<username>` users the venue remains the did:web
controller and may issue venue sessions, while registered user-held keys
provide direct self-sovereign authentication as the stable named DID.

## File roots

The `file` adapter exposes operator-configured logical roots. A root may be a
host directory, an ephemeral temporary directory, or a caller-owned DLFS
drive. DLFS roots may be clamped to a provider-relative subtree:

```json
{
  "file": {
    "roots": {
      "workspace": "/srv/agent-workspace",
      "reference": {
        "path": "/srv/reference",
        "readOnly": true
      },
      "agent-documents": {
        "dlfs": "vault",
        "subpath": "agent-output",
        "readOnly": false,
        "description": "Files created by document-processing agents"
      }
    }
  }
}
```

`subpath` is valid only with `dlfs`. It must be a non-empty relative path and
cannot contain `..`; invalid roots are skipped rather than broadened to the
whole drive. The subtree is created lazily on first file access. It is an
implementation boundary, not part of the client path: callers use
`{ "root": "agent-documents", "path": "report.pdf" }`, while the provider
stores the file at `vault/agent-output/report.pdf`. Because this is DLFS data,
capabilities name the canonical DID-scoped resource
`dlfs/vault/agent-output/report.pdf`, not the configured `file://` alias.

File operations may also address an authorised DLFS path directly without a
configured root: use `dlfs/<drive>/<path>` for the caller's own drive, or
`<ownerDID>/dlfs/<drive>/<path>` with an appropriate cross-user proof. The
same forms are accepted by `file:move` and `file:copy` endpoints.

## Adapter configuration

```json
{
  "adapters": {
    "agent": { "sessionDelete": false },
    "vault": { "drive": "vault" },
    "orchestrator": {
      "maxItems": 50,
      "maxConcurrency": 8
    }
  }
}
```

Per-adapter settings, keyed by adapter name. Adapters read their *effective*
configuration through `Engine.adapterConfig(name)` — this static block
overlaid by any runtime reconfiguration (see
[Runtime adapter lifecycle](#runtime-adapter-lifecycle)).

The reserved key `enabled: false` parks a non-kernel adapter as disabled at
boot: it is not installed, publishes nothing to `v/ops/` or `v/info/adapters/`,
and does not dispatch. `{"adapters": {"test": {"enabled": false}}}` hides the
test operations; `{"adapters": {"convex": {"enabled": false}}}` removes the
Convex ops. A disabled adapter can be switched on later without a restart
with `v/ops/venue/adapter/enable`. Kernel adapters (`covia`, `agent`, `dlfs`,
`hitl`, `http`, `file`, `grid`, `venue`) cannot be disabled — the venue does
not function without them — and `enabled: false` on one is a boot error.

Currently defined:

- `vault.drive` — DLFS drive targeted by the drive-free `vault:*` convenience
  operations (default `vault`). It must be a single non-empty drive name without
  `/`, `\\`, or `:`. Because vault content is held in the venue lattice, startup
  warns if the adapter is active without an encrypted Etch policy; configure
  `etch.cipher` and key management before storing sensitive data.
- `agent.sessionDelete` — whether `agent:deleteSession` is available
  (default `true`). Set `false` to disable user-initiated session deletion
  venue-wide; the op then fails with "disabled on this venue".
- `hitl.maxGrantLifetimeSecs` — optional positive lifetime ceiling for grants
  minted after HITL approval. It is absent by default, so the venue imposes no
  maximum and permits an explicit `exp: null`. With a finite ceiling, null and
  later expiries are rejected before the request reaches the approver. A grant
  that omits `exp` uses the shorter of the seven-day default and this ceiling.
- `orchestrator.maxItems` — maximum number of elements accepted by one
  `foreach` step (default `50`). The complete input is rejected before any
  iteration starts when this limit is exceeded. Set explicitly to `null` for no
  orchestrator-level item cap (normal Convex/JVM representation limits still
  apply).
- `orchestrator.maxConcurrency` — maximum child jobs in process for one
  `foreach` step (default `8`). A step may request a lower value through
  `foreach.maxConcurrency`, but cannot exceed this venue ceiling. The
  orchestrator resolves inputs and issues child invocations serially; only
  waiting for the issued jobs is concurrent.

## Legacy private invoke setting

```json
{
  "enablePrivateJobs": true
}
```

Deprecated compatibility setting; it no longer enables `private: true` on
`/invoke`. Invoke now always creates a durable Job. Use `/api/v1/run` (or the
SDK's `run`) when only the result is required. Whether run's internal Job is
transient is controlled by operation metadata and
`recordReadOnlyOperations`, not by a caller-selected privacy flag.

## Result-oriented operation runs

`POST /api/v1/run` waits for an operation and returns its output directly. It
is distinct from `POST /api/v1/invoke`: invoke always creates a durable Job and
returns that Job, even when `wait` is used. Run still executes through a Job
internally, but does not expose the Job handle to the caller.

Operations explicitly declaring `operation.readOnly: true` use a transient,
non-persisted Job for `run` and `invokeInternal` by default. Mutating or
unclassified operations invoked through `run` remain durable. An operation can
declare `operation.internal: false` when its lifecycle itself must be recorded
(for example, a human-in-the-loop request); this forces a durable Job on both
result-oriented paths.

Operators can force read-only runs and internal calls to be recorded:

```json
{
  "recordReadOnlyOperations": true
}
```

This option is off by default. It does not change `/invoke`, which is always
recorded.

## DLFS WebDAV

```json
{
  "webdav": { "enabled": true }
}
```

Mounts WebDAV at `/dlfs/` for file access to DLFS drives. Off by default.

## MCP tool bridging

```json
{
  "mcp": {
    "servers": {
      "github": { "url": "https://mcp.github.example", "auth": "s/GITHUB_MCP_TOKEN" }
    }
  }
}
```

Bridges external MCP tools into the catalog (#80): they materialise as
ordinary operations — capability grants, gates, job records and schema
validation all apply because they are ordinary ops. **The tool is the
entity**; the server is just where it lives. Two management styles:

- **Curated** (`v/ops/mcp/add-tool {server, tool, path, auth?, name?,
  description?, default?}`): one tool at a caller-chosen catalog path.
  Groups are just paths — `o/research/search_papers` and
  `o/research/github_search` can point at different servers with different
  auth. Registry-free (the asset is self-contained); remove with
  `covia:delete` on the path — nothing resurrects it. `name`/`description`
  overrides are yours and survive refresh. `default` purpose-shapes the
  tool with argument defaults (any value types; defaulted keys leave
  `required`; generic `operation.default` mechanism — `docs/OPERATIONS.md`
  §5): a generic five-field `create_issue` becomes a two-field
  `report_bug`.
- **Mirrored** (`v/ops/mcp/add-server {name, url, auth?, scope?}` /
  `remove-server`): ALL of a server's tools under `o/mcp/<server>/` (or
  `v/ops/mcp/<server>/` at venue scope), registry entry for bookkeeping.
  Config-declared servers (above) mirror at venue scope on boot —
  best-effort per server; one that is down logs a warning and the last-known
  catalog persists.

`v/ops/mcp/refresh` follows the same split: `{name}` reconciles a mirror
fully (vanished tools deleted); `{path}` refreshes curated tools in place —
schemas/annotations update, name/description untouched, vanished tools
**reported in `missing`, never deleted**. Exactly one of `name`/`path`.

Destination paths under the caller's own `o/` need nothing extra; venue-side
targets (`v/ops/...` or `scope: "venue"`) require the `mcp/manage` ability.
Server URLs pass the same SSRF validation (and operator allow/block lists) as
the http adapter. `auth` should be a secret reference (`s/<name>`, stored via
`v/ops/secret/set`; bare refs are stored DID-qualified to the registrar) —
resolved at call time, never persisted raw; raw tokens warn.

Bridged assets are self-contained — a hand-authored asset with
`operation: {adapter: "mcp:tools:call", remoteToolName, server, auth?}` works
without any registry entry. The bridged op's declared input IS the tool's own
schema, so the invocation input is passed directly as the tool arguments.
Failures are LLM-diagnosable at the point of use: a remote tool-level error
(`isError` per the MCP spec) fails the job with the remote error text;
transport failures name the tool, server and root cause with a remedy;
text-only tool results are preserved (structured content wins when present).

## LLM providers (langchain)

`v/ops/langchain/*` inputs carry `model` / `url` / `apiKey` / `maxTokens` /
`temperature` / `topP` / `tools` / `responseFormat`. `temperature` and `topP`
pass through to every provider (#218 — accepts integer or double, so
`temperature: 0` works for deterministic extraction); `maxTokens` is
honoured by the anthropic provider (its API requires it).

`defaultLlmOperation` selects the provider used when an agent config does not
name one; the built-in fallback is `v/ops/langchain/anthropic`. Standard agent
templates are provider-neutral, so a later config layer can choose any provider
without copying the template. `v/ops/langchain/models` reports caller-relative
provider readiness, each release-time model list, its balanced default, and
workload recommendations (for example `economical`, `quality`, or `coding`).
The built-in balanced defaults are Sonnet 5, GPT-5.6 Terra, Gemini 3.6 Flash,
DeepSeek V4 Flash, and Grok 4.3. Operators may replace an advertised hosted list
with `adapters.langchain.models.<provider>`; this is discovery metadata, not an
allowlist, so other provider-supported model IDs remain usable.

Ollama base URL resolution (#224): explicit input `url`, then venue config
`adapters.langchain.ollamaUrl`, then the `OLLAMA_BASE_URL` environment
variable, then `http://localhost:11434`. Keep agents topology-agnostic —
only the venue deployment knows where Ollama lives (a Dockerised venue
typically needs `http://host.docker.internal:11434`, with Ollama started as
`OLLAMA_HOST=0.0.0.0 ollama serve`). A connect failure names the resolved
URL and this knob instead of a bare ConnectException.

Agent-side bounds: each level-3 LLM call is bounded by the agent's
`llmTimeoutMs` (default 120s) and each tool call by `toolCallTimeoutMs`
(default 300s); cancellation interrupts the in-flight provider call.

## Venue modules

```json
{
  "modules": [
    "modules/covia-sql-<version>-module.jar",
    { "path": "modules/other.jar", "sha256": "9f2a...", "config": { } }
  ]
}
```

External adapter jars loaded at boot (#226) — heavyweight or optional
adapters stay out of covia.jar. A module is a self-contained shaded jar
compiled against `venue` (provided scope) declaring its adapters via
`META-INF/services/covia.adapter.AAdapter`; its adapters are ordinary
adapters (catalog, `/v/info/adapters`, caps/gates/defaults all apply). Each
module gets a split-delegation classloader: parent-first for
`covia.*`/`convex.*`/JDK/SLF4J (shared cell types + logging), child-first
for everything else (dependency isolation). `sha256` pins content; boot
fails fast on any load error. Loaded modules are listed at
`v/info/modules/<name>` (`{name, path, sha256?, adapters}`), and each module
adapter's `v/info/adapters/<name>` summary names its owning `module`.
The optional `config` object is passed unchanged to every adapter discovered
in that module before registration. An adapter may reject malformed settings
or remain inactive when an optional runtime is unavailable. Module-specific
unknown-field handling receives the venue's `strictConfig` mode.

Modules can also be loaded and unloaded on a running venue — see
[Runtime adapter lifecycle](#runtime-adapter-lifecycle). Off by default;
`modules` remains the boot-time, restart-surviving declaration.

First module: **covia-sql** (#227) — `v/ops/sql/query` / `v/ops/sql/execute`
over venue-local convex-db databases (per-user, lattice-backed, created on
first use; ONE instance = one store, per-user isolation via the `database=`
param) and operator-registered JDBC connections
(`adapters.sql.databases.<name>`, passwords as `s/` secret refs). Callers name a `db`, never a URL. Caps:
`sql/<db>` × `sql/query`|`sql/execute`. The module ships its own `sql`
agent skill from its jar (materialises at `v/skills/sql` exactly when the
module is loaded — the module-shipped-skill pattern, see `docs/SKILLS.md`).

### Telegram bots (covia-telegram)

The **covia-telegram** module (`telegram` adapter) runs operator-declared
Telegram bots that connect chats to the venue, and gives agents a way to
message people on Telegram. Bots live in the adapter's *effective*
configuration, so they follow the runtime adapter lifecycle: a
`v/ops/venue/adapter/configure` call adds, removes or changes bots on a
running venue (only changed bots restart), and the venue config is
authoritative again after a restart.

```json
{
  "modules": ["modules/covia-telegram-<version>-module.jar"],
  "adapters": {
    "telegram": {
      "bots": {
        "assistant": {
          "token": "s/TELEGRAM_BOT_TOKEN",
          "user": "did:key:z6Mk...",
          "agent": "Assistant",
          "allow": [123456789, "@mike"],
          "parseMode": "Markdown",
          "greeting": "Hi — I'm the venue assistant."
        },
        "orders": {
          "token": "s/ORDERS_BOT_TOKEN",
          "user": "did:key:z6Mk...",
          "operation": "o/telegram-to-orders-db",
          "reply": "Recorded, thanks.",
          "allow": ["@warehouse_lead"]
        }
      },
      "apiUrl": "https://api.telegram.org/bot"
    }
  }
}
```

Per bot:

- `token` — the token from @BotFather, as an `s/NAME` secret reference
  (resolved in the bot user's store, then the venue's; a literal is accepted
  but never logged or listed). A bot whose secret is absent parks as
  `PENDING` and retries (once after 2 s, then every 30 s), so provisioning the secret later brings it
  up without a restart.
- `user` — the DID the bot acts as; every invocation runs with that user's
  full authority, so it should be the owner of the agent it routes to.
  `"public"` names the venue's public principal (the same identity as an
  anonymous MCP client on a public venue), which is what a local dev venue's
  agents are owned by.
- `agent` **or** `operation` — the **inbound handler**. With `agent`, each
  Telegram chat is one `agent:chat` session, persisted at
  `w/telegram/sessions/<bot>/<chatId>` in the user's workspace so
  conversations survive restarts; `/new` starts a fresh one. The agent
  receives each turn as `{text, via: {channel: "telegram", bot, access:
  "allow" | "open", from, chat, message_id}}` — Telegram's authenticated
  `from`/`chat` verbatim — so it knows who is on the other end without
  trusting anything typed; the venue's own "[Authenticated sender: …]" line
  still names the bot's Covia identity. With
  `operation`, every update invokes that reference with the **Telegram
  `Update` exactly as sent** — snake_case, `message` / `edited_message` /
  `callback_query` / … nested as Telegram nests them (a photo arrives as
  `message.photo[]` with `file_id`s and `message.caption`; a button tap as
  `callback_query.data`) — plus `bot`. The module never reshapes messages:
  for a target whose input is not an Update — a deterministic SQL write, a
  webhook, a log to some location — point `operation` at a small **mapping
  operation you own** (an orchestration, a pinned op…) that takes the Update
  and does the work. Agent bots receive only text messages (captions count);
  operation bots receive every update type Telegram delivers. Either way each inbound message runs
  as a **Job in the bot user's job index**, which is the canonical record of
  the interaction; the module keeps no log of its own.
- `reply` — for an `operation` handler: `true` (default: the result
  rendered as text — a string as-is, a map's
  `text`/`response`/`content`/`message`/`result`, else pretty JSON),
  `false` (never reply), or a fixed acknowledgement string. Agent
  conversations always reply. Handler failures are always reported to the
  sender.
- `allow` — Telegram user ids and/or `@usernames` permitted to talk to the
  bot. **Fail-closed**: with no `allow` and no `open: true`, everyone is
  refused (in private chats they are told their user id, so an operator can
  add it). `open: true` admits anyone — appropriate only for a bot whose
  target is safe for the public.
- `parseMode` — `Markdown`, `MarkdownV2` or `HTML` for replies; omit for
  plain text. Markup Telegram rejects is resent as plain text.
- `greeting` — the `/start` reply (default names the agent and venue).

`apiUrl` overrides the Bot API base (a proxy, or a test double); it defaults
to Telegram's. Built-in commands: `/start`, `/help`, `/new`, `/id`. Messages
are answered in order per chat, with a typing indicator while the agent
works; long replies are split at Telegram's 4096-character limit. Polling is
long-poll (`getUpdates`); no inbound port or webhook is required. Disabling
the adapter (`adapters.telegram.enabled: false` or `adapter/disable`) takes
its bots offline without confirming updates, so Telegram redelivers the
backlog when it is enabled again.

Bots can also be **created at runtime by their user**: `v/ops/telegram/create
{name, token: "s/…", agent | operation, reply?, allow?, open?, parseMode?,
greeting?}` makes a bot that acts as the caller (no `user` — a user cannot
create a bot acting as someone else; the token must be a secret reference),
records it at `w/telegram/bots/<name>` in the caller's workspace, starts it,
and re-arms it on every venue start or module load; `v/ops/telegram/delete
{name}` stops and removes it (record and sessions). Both are gated on
`<caller>/telegram/<name>` × `telegram/manage`. Config-declared bots stay
the operator's (delete refuses them); `telegram:bots` reports `managed:
config | runtime`. Bot names are per user for created bots; `send`/`call`
resolve `bot` against the caller's own bots first, then config bots.

Operations — all in Telegram's own field names, so the Bot API reference is
the reference: `v/ops/telegram/send {bot?, chat_id, text, parse_mode?,
reply_parameters?, reply_markup?, …}` (the `sendMessage` parameters as-is;
returns the sent `Message`; text over 4096 characters is split and rejected
markup is resent plain) — gated on `<bot user>/telegram/<bot>` ×
`telegram/send`, so the bot's user and their agents (within scope) may send
and anyone else needs a delegation from that user; `v/ops/telegram/call
{bot?, method, params}` — any other Bot API method with its documented
parameters (`sendPhoto`/`sendDocument` by `file_id` or URL, `editMessageText`,
`deleteMessage`, `answerCallbackQuery`, `getChat`, …; `getUpdates`,
`setWebhook`, `deleteWebhook`, `logOut`, `close` are refused as the venue's
own update loop owns them) — gated on `telegram/call`; and
`v/ops/telegram/bots` — the caller's bots
with state (`STARTING`, `PENDING`, `RUNNING`, `STOPPED`), Telegram username,
last error and counters, tokens never included. The module ships a
`telegram` agent skill (`v/skills/telegram`).

## Runtime adapter lifecycle

```json
{
  "dynamicModules": {
    "enabled": true,
    "dir": "modules",
    "anyPath": false
  }
}
```

The `venue` adapter exposes venue-owned operations that change the adapter
set of a *running* venue without a restart:

| Operation | Effect |
|-----------|--------|
| `v/ops/venue/adapters` | Registry view: every registered adapter (active **and** disabled) with `enabled`, `kernel`, owning `module`, effective `config` and `operations`, plus loaded `modules` |
| `v/ops/venue/adapter/disable {name}` | Retract a non-kernel adapter's catalog paths and `v/info/adapters/<name>`, stop dispatch. Instance retained; in-flight jobs finish |
| `v/ops/venue/adapter/enable {name}` | Restore it (installing on first enable if it was boot-disabled) |
| `v/ops/venue/adapter/configure {name, config, merge?}` | Apply a new effective configuration (`adapters.<name>` shape) — the adapter's `configure` hook may reject it, in which case nothing changes |
| `v/ops/venue/module/load {module, sha256?, config?}` | Load a module jar and publish its adapters |
| `v/ops/venue/module/unload {name}` | Remove a module's adapters (catalog + introspection retracted, resources closed) and close its classloader |

**Authority.** All of these are venue administration: a null capability
scope is deliberately not enough. They run only as the venue identity itself
or with a venue-issued delegation covering `<venue DID>/adapters` ×
`adapter/manage` — the same model as `user:create`. Ordinary authenticated
users get `AuthException`.

**Module policy.** `module/load` and `module/unload` require
`dynamicModules.enabled` (default **off**). By default `module` must be a jar
*name* inside the staging directory `dynamicModules.dir` (default `modules`,
relative to the working directory): no absolute paths, no `..` segments, and
the resolved real path must stay inside the directory. An operator who wants
to install any adapter from anywhere sets `dynamicModules.anyPath: true`,
after which `module` may be any filesystem path (a relative one still
resolves against `dir`). `sha256` pins content exactly as for boot modules.
Loading in-process code is total compromise of the venue; this policy exists
so that the decision stays with the operator.

**Semantics.** Enable, disable, load and unload each publish as one lattice
transaction, so the catalog never shows a half-applied adapter. Kernel
adapters cannot be disabled or unloaded. In-flight jobs keep their adapter
reference and finish; anything that re-resolves the adapter by name
(multi-turn messages, restart recovery) fails at that point of use — the
same rule as `mcp:remove-server`. Unloading closes `AutoCloseable` adapters
and the module classloader, but JVM class unloading is best-effort (JDBC
`DriverManager`, JNI and lingering references can pin a loader); "unload"
means deregistered and released, not guaranteed collected. Runtime changes
are **not persisted**: after a restart the venue config (`adapters.*`,
`modules`) is authoritative again. A persisted live configuration is a
later step.

## A2A protocol

```json
{
  "a2a": {
    "defaultChatOp": "v/test/ops/echo",
    "agentInfo": {
      "name": "My Venue Agent",
      "description": "What this agent does",
      "organization": "Acme",
      "providerUrl": "https://acme.example"
    }
  }
}
```

Enables the A2A (Agent-to-Agent) protocol. Off by default — the endpoints are
registered **only** when an `a2a` block is present. Without it, `POST /a2a` and
`GET /.well-known/agent-card.json` return `501` with a hint pointing back here
(rather than an indistinguishable 404).

- `defaultChatOp` — the operation invoked on a fresh `SendMessage` (no
  `taskId`). Its Job becomes the A2A Task; its output becomes the Task's
  artifact. `v/test/ops/echo` needs no LLM secret and is handy for smoke tests;
  point it at an `llmagent`/`agent` chat op for a real agent.
- `agentInfo` — surfaced in the agent card (`name`/`description`, plus
  `organization`/`providerUrl` for the card's `provider`). All optional.

**Auth note:** `SendMessage` invokes `defaultChatOp` as the *calling*
identity. Under the default read-only public scope an unauthenticated caller
cannot invoke, so the Task comes back `TASK_STATE_FAILED`. To exercise
`SendMessage` from an unauthenticated client, either authenticate the caller
or widen `auth.public.caps` to permit the op — do the latter only on a
loopback-bound (`bindAddress: 127.0.0.1`) throwaway venue, never a
LAN-reachable one. The agent-card GET is public and works regardless.

**Per-agent endpoints (COG-14):** beyond the front door, every hosted agent is
addressable at `POST /a2a/<ownerDID>/g/<agentId>` (JSON-RPC `SendMessage` →
`agent:request` task Job = A2A Task; `GetTask`, `CancelTask`,
`SubscribeToTask`, `GetExtendedAgentCard`), with its card at the A2A well-known
path below that base. Private by default: the owner interacts as themselves; anonymous
non-owners get an existence-hiding 404, authenticated non-owners 403.
Publishing is per-agent config: `a2a: {public: true}` makes the card
discoverable; adding an explicit `a2a.caps` scope accepts stranger
messages, dispatched as the OWNER narrowed by that scope — it must include
`agent/request` plus whatever the agent's own work needs. `"unrestricted"`
grants full owner authority (logged loudly). Wire method names are SDK-style
(`SendMessage`, not `message/send`). The full addressing, discovery and
publication model is in [A2A_AGENTS.md](./A2A_AGENTS.md), the implementation
companion to COG-14; non-owner run authority is detailed in
[A2A_INTERACTION_AUTHORITY.md](./A2A_INTERACTION_AUTHORITY.md).

Minimal authenticated per-agent request:

```bash
# The base URL is also the URL given to a standard A2A card resolver.
AGENT_URL="$VENUE/a2a/$OWNER_DID/g/$AGENT_ID"
curl -sS "$AGENT_URL/.well-known/agent-card.json" \
  -H "Authorization: Bearer $TOKEN"

curl -sS "$AGENT_URL" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{
    "jsonrpc": "2.0",
    "id": "send-1",
    "method": "SendMessage",
    "params": {
      "message": {
        "role": "ROLE_USER",
        "parts": [{"text": "Hello"}],
        "messageId": "message-1"
      }
    }
  }'
```

The result is an A2A Task. Poll its `id` at the same `AGENT_URL` with
`{"jsonrpc":"2.0","id":"get-1","method":"GetTask","params":{"id":"<task-id>"}}`.
The `Authorization` header may be omitted only when the agent is explicitly
published with an `a2a.caps` scope that permits the interaction.

`SendMessage` never waits for the agent turn to finish. It returns the current
Task snapshot as soon as the durable Job has been submitted: Task `id` is the
Job id and `contextId` is the session id. A turn may therefore run for minutes,
days, or longer without crossing a protocol timeout or changing identity.
Reconnect with `GetTask`, or open `SubscribeToTask` at the same endpoint for
SSE updates. Both read the same Job record and converge on the same terminal
state; closing an HTTP request or SSE connection does not fail or cancel it.

Outbound calls use imported A2A agent Assets. Import with
`v/ops/a2a/import-agent`, then invoke `agent-card`, `send`, `get-task`, or
`cancel` with `agent: "w/a2a/agents/<name>"`. The Asset may describe a standard
A2A URL or a Covia agent at a local/remote venue; the latter still uses the
standard per-agent A2A endpoint above. Authentication bindings retain only a
caller-owned `s/NAME` SecretStore reference and support card-declared API-key
and HTTP-Bearer schemes. UCAN authority is not relayed over A2A; use native Grid
operations for Covia-to-Covia authority and identity. All outbound URLs pass
the HTTP adapter's SSRF checks and operator allow/block lists.

## Secrets bootstrap

Per-venue config can pre-populate the encrypted per-user secret stores at startup:

```json
{
  "secrets": {
    "venue":  { "OPENAI_API_KEY": "sk-..." },
    "public": { "OPENAI_API_KEY": "sk-...", "ANTHROPIC_API_KEY": "sk-ant-..." },
    "did:key:z6MkAlice...": { "FOO": "bar" }
  }
}
```

Top-level keys resolve as follows:
- `"venue"` → the venue's own DID (used by venue-internal operations and self-issued requests)
- `"public"` → `<venueDID>:public`, the default identity for unauthenticated callers
- Anything else → used verbatim; expected to be a literal DID string

Each named secret overwrites any existing value under that name for that user — config is the source of truth at launch. Names not listed are left untouched. Per-secret failures log a warning but do not fail startup. Values are never logged.

Secret resolution at invocation time checks the caller's own store first,
then falls back to the public store (covia#254, use-only — `secret:extract`
stays closed).

For LangChain hosted providers, if the operation's named secret is absent from
both stores, the venue process environment is the final fallback using that
same conventional name (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, etc.). Store
values take precedence. This supports process/container secret injection
without persisting a credential in Covia config or agent state; the environment
credential is venue-wide, so use a per-user SecretStore when tenant-specific
provider credentials are required.

**Never commit production secrets here.** Intended for personal dev configs in gitignored locations (e.g. `dev/local.json`).
