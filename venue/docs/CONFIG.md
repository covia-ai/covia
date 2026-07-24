# Venue Configuration Reference

The complete operator configuration reference for a Covia venue. Values live
in the JSON config passed to `covia.jar` (see `local-dev.json` for the
ephemeral dev shape and the venue-setup skill for persistent personal configs).

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
emit `Vary: Origin`; a denied browser origin receives HTTP 400 without an
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

- **Request rate** (`rps`, `burst`) — a per-caller token bucket on `/api/*`,
  `/mcp`, `/a2a*`. A denied request short-circuits with **429 + `Retry-After`**
  before any handler runs. Deliberately coarse/high — it's a flood backstop, not
  a normal-traffic gate; the job cap is the precise control.
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

## User registration (`users`)

Authentication proves control of a DID; it does not create a venue account.
By default, a previously unknown authenticated DID receives HTTP 403 with an
actionable registration message, before a job or user state is persisted.

```json
{
  "users": { "autoCreate": false }
}
```

- `autoCreate` defaults to `false`. Keep this setting for private and
  production venues, and provision accounts explicitly with
  `v/ops/user/create`.
- Set `autoCreate` to `true` only when authenticated first-use registration is
  intended, such as a public test venue. The checked-in `local-dev.json` opts
  in explicitly.

A runtime user ID is always a DID and may use any DID method. `user:create`
accepts a full DID directly (for example a self-sovereign `did:key`) or a
venue-managed `username`. A username requires a public `hostname` and derives
`did:web:<hostname>:u:<username>` — for example
`did:web:venue-1.covia.ai:u:alice`. `user:create` and `user:list` are
venue-administrative operations: invoke directly as the venue, or present a
venue-issued UCAN covering `<venueDID>/users` with `user/create` or
`user/read`. OAuth callbacks are trusted venue provisioners and create the
same did:web-managed account explicitly.

Registering a full external DID admits that identity to use the venue; it does
not transfer control of the DID to the venue. A self-sovereign user signs their
own UCAN roots. Only username-created `did:web:<hostname>:u:<username>` users
are custodial identities for which the venue may sign roots.

## Adapter configuration

```json
{
  "adapters": {
    "agent": { "sessionDelete": false }
  }
}
```

Per-adapter settings, keyed by adapter name (`Config.getAdapterConfig(name)`).
Currently defined:

- `agent.sessionDelete` — whether `agent:deleteSession` is available
  (default `true`). Set `false` to disable user-initiated session deletion
  venue-wide; the op then fails with "disabled on this venue".

## Private jobs

```json
{
  "enablePrivateJobs": true
}
```

Off by default. When enabled, an invoke with `private: true` (body field)
creates a **memory-only job** (#192): never persisted — no record in the
caller's job index, no lattice write, no recovery, gone on venue restart.
Use `wait` to collect the result; a completed private job is immediately
forgotten. A private request against a venue without this flag is an error —
never a silent downgrade to a persisted job. A private conversation is agent
intake (`agent:chat` / `agent:request`) invoked private; the session record
remains the (deletable, `agent:deleteSession`) conversation store.

**Operator telemetry is unaffected**: private controls the durable lattice
record, not operational visibility. The venue still logs job events (ID,
operation, status transitions, timings) per its logging config, live
job-update listeners (SSE, MCP notifications) still fire to authorized
subscribers, and stats counters still count. Note that log lines are
ID-and-status shaped as a rule, but failure messages can quote content
fragments — operators wanting content-clean logs address that via logging
policy (levels, appender redaction), not the job system.

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
    "modules/covia-sql-0.6.0-module.jar",
    { "path": "modules/other.jar", "sha256": "9f2a..." }
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
for everything else (dependency isolation). Loading is an OPERATOR act —
no runtime module-load op exists, deliberately. `sha256` pins content;
boot fails fast on any load error; no hot-unload (restart to remove).

First module: **covia-sql** (#227) — `v/ops/sql/query` / `v/ops/sql/execute`
over venue-local convex-db databases (per-user, lattice-backed, created on
first use; ONE instance = one store, per-user isolation via the `database=`
param) and operator-registered JDBC connections
(`adapters.sql.databases.<name>`, passwords as `s/` secret refs). Callers name a `db`, never a URL. Caps:
`sql/<db>` × `sql/query`|`sql/execute`. The module ships its own `sql`
agent skill from its jar (materialises at `v/skills/sql` exactly when the
module is loaded — the module-shipped-skill pattern, see `docs/SKILLS.md`).

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

- `defaultChatOp` — the operation invoked on a fresh `message/send` (no
  `taskId`). Its Job becomes the A2A Task; its output becomes the Task's
  artifact. `v/test/ops/echo` needs no LLM secret and is handy for smoke tests;
  point it at an `llmagent`/`agent` chat op for a real agent.
- `agentInfo` — surfaced in the agent card (`name`/`description`, plus
  `organization`/`providerUrl` for the card's `provider`). All optional.

**Auth note:** `message/send` invokes `defaultChatOp` as the *calling*
identity. Under the default read-only public scope an unauthenticated caller
cannot invoke, so the Task comes back `TASK_STATE_FAILED`. To exercise
`message/send` from an unauthenticated client, either authenticate the caller
or widen `auth.public.caps` to permit the op — do the latter only on a
loopback-bound (`bindAddress: 127.0.0.1`) throwaway venue, never a
LAN-reachable one. The agent-card GET is public and works regardless.

**Per-agent endpoints (COG-14):** beyond the front door, every agent is
addressable at `POST /a2a/<ownerDID>/g/<agentId>` (JSON-RPC `SendMessage` →
`agent:request` task Job = A2A Task; `GetTask`, `CancelTask`,
`GetExtendedAgentCard`), with its card at the A2A well-known path below that
base. Private by default: the owner interacts as themselves; anonymous
non-owners get an existence-hiding 404, authenticated non-owners 403.
Publishing is per-agent config: `a2a: {public: true}` makes the card
discoverable; adding an explicit `a2a.caps` scope accepts stranger
messages, dispatched as the OWNER narrowed by that scope — it must include
`agent/request` plus whatever the agent's own work needs. `"unrestricted"`
grants full owner authority (logged loudly). Wire method names are SDK-style
(`SendMessage`, not `message/send`). Per-agent task continuation (incoming
`taskId`) is not yet implemented (#234). Outbound `v/ops/a2a/*` ops pass the
http adapter's SSRF checks and operator allow/block lists.

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

**Never commit production secrets here.** Intended for personal dev configs in gitignored locations (e.g. `dev/local.json`).
