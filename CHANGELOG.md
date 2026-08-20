# Changelog

All notable changes to Covia are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Covia is pre-1.0, so minor versions may include breaking changes.

## [Unreleased]

### Added

- Hierarchical agent skills: skills and skillsets are separate declared kinds; a loaded skill contributes further sources — discovered, never auto-loaded
- Venue skill library grouped into skillsets under `v/skills/<set>/`, with `v/skills/root` as the entry index (24 always-on lines down to 8)
- SKILL.md frontmatter accepts `tools`, `skills` and `skillsets` lists
- Boot warnings for a malformed venue skill library, and an `agent:create` warning for a skillset pointing at a directory of skillsets
- `adapters.skills.defaultSkillsets` / `defaultSkills` configure the `skills` op's entry point, published at `v/info/adapters/skills`

- Optional `covia-discord` venue module: operator-declared or user-created
  Discord bots route DMs and mentioned guild messages to agents (durable
  per-channel sessions) or operations. Includes capability-gated
  `discord:send`, `discord:call`, `discord:create`, `discord:delete`, and
  `discord:bots`, fail-closed Discord account allow-lists, Gateway reconnect
  parking, REST rate-limit retry, a module-shipped agent skill/template, and
  a shaded JDA 6.5.0 runtime isolated from the venue classpath.

### Changed

- **Breaking:** `config.skills` now means individual skills only; directories move to `config.skillsets`. Standard templates are migrated

- Adapter-global durable state now has a shared venue-private convention at
  `<venue-did>/w/adapters/<adapter>/`. Telegram and Discord use matching
  `config/<bot>/sessions` and `users/<did>/{bots,sessions}` schemas while
  leaving user-managed storage paths user-selectable and credentials in `s/`.
  Telegram discovers and copies its legacy `w/telegram` runtime records on
  upgrade; deletion cleans both locations.

### Fixed

## [0.9.3] - 2026-08-19

### Added

- General asset-reference resolution across HTTP and the Java client. The
  canonical `assets/<ref>` metadata and `content/<ref>` byte endpoints accept
  hashes, `a/`, `v/`, `w/`, `o/`, leading-slash forms, and DID URLs. Venue asset listings return
  owner-qualified references that round-trip without changing namespace.

### Changed

- Updated Javalin to 7.2.3, LangChain4j to 1.19.0, MCP Java SDK to 2.0.1,
  and Logback to 1.6.3. The MCP update bounds transport reads, while the
  Logback update includes the `MDCBasedDiscriminator` path-sanitisation fix.

### Fixed

- Fixed #387: long-lived DLFS views now use the venue's current write clock per
  mutation instead of freezing their timestamp at connect time. Per-user DLFS
  signer overrides now retain the host's owner-verification and future-skew
  policies, and hosted drives use Convex 0.8.14's store-aware connection so
  streamed blobs are persisted incrementally instead of remaining heap-backed.
  Convex 0.8.14 also preserves the current side of equal-timestamp live/tombstone
  merges, preventing stale sync callbacks from erasing a recreated path.

- Venue-authorised adapter and module loads are last-write-wins: reloading a module replaces existing adapter names and catalog declarations instead of failing on occupied paths. Disable/unload removes live dispatch and introspection while leaving durable catalog metadata available for later replacement or explicit operator deletion (#386).

## [0.9.2] - 2026-08-17

### Added

- LangChain providers `mistral` (`MISTRAL_API_KEY`, `mistral-medium-latest` default) and `openrouter` (`OPENROUTER_API_KEY`, `openrouter/auto` default, any vendor-prefixed model id) — OpenAI-compatible endpoints; listed by `langchain:models`
- Agent skills `covia` (what Covia is) and `venue` (what a venue is, its identity/URL, how clients connect, what it offers, namespaces); `discovery`/`files` point at adapter-published facts (`v/info/adapters/dlfs/webdav`)
- Adapters own their skills: each built-in adapter installs its own `v/skills/<name>` via `installSkill`, so a skill is published exactly when its adapter is active (retracted on disable/unload); `SkillsAdapter.LIBRARY` keeps only the platform skills (`covia`, `venue`, `discovery`, `provenance`, `skills`, `skill-authoring`)
- `v/adapters/<name>/` — the adapter-owned subtree: `info`, `config` (only what `AAdapter.publicConfig()` explicitly allow-lists — nothing by default; e.g. `orchestrator` maxItems/maxConcurrency, `vault` drive, `agent` sessionDelete, `hitl` maxGrantLifetimeSecs, `telegram` apiUrl), and its `ops/`, `skills/`, `templates/` mirrored from the canonical catalog (same values, equally invocable), published/retracted with the adapter and refreshed on reconfigure
- Job-free read surfaces for page-load clients: `GET /api/v1/schedules` — the caller's pending scheduled events (#369); `GET /api/v1/assets?scope=own` — the caller's own `a/` assets, populated by `asset:store`/`asset:pin` (#382). Both are synchronous, capability-checked, and persist no Job (the `scheduler:list` / `asset:list` operations remain)
- Asset content retrieval by any asset reference: `GET /api/v1/assets/content/<ref>` (#368)
- Optional `responseSchema` on `agent:request` with requester-controlled `strict` enforcement at task completion (#376)
- Runtime adapter lifecycle: `v/ops/venue/adapter/{enable,disable,configure}` and `v/ops/venue/module/{load,unload}` (venue-owned, `adapter/manage`); `adapters.<name>.enabled` boot switch; `dynamicModules` policy; `v/info/modules`
- `AAdapter.configure(config, strict)` hook and `Engine.adapterConfig(name)` effective-config overlay
- `/adapters` skill — discover, invoke and manage adapters, including runtime lifecycle and module load/unload
- `v/info/url` (venue base URL), `AAdapter.info()` — adapter-published facts merged into `v/info/adapters/<name>` and refreshed on reconfigure; `dlfs` publishes `webdav: {enabled, url?, path?, windows?}` there (Windows UNC form included), and `dlfs-webdav` joins `v/info/protocols` when mounted
- covia-telegram module: `telegram` adapter — operator-declared Telegram bots with a configurable inbound handler (agent conversation with persistent per-chat sessions, or any operation fed the Telegram `Update` verbatim — every inbound update a Job in the bot user's job index), `reply` control; `telegram:send` (sendMessage params as-is), `telegram:call` (any Bot API method), `telegram:create` / `telegram:delete` (user-created bots, persisted in the workspace and re-armed at boot), `telegram:bots`; agents receive `{text, via: {from, chat, bot, access}}` so they know the Telegram sender; module-shipped `telegram` agent skill and `v/agents/templates/telegram` assistant template
- covia-claude-code module: `claudecode` adapter — drives the Claude Code CLI (subscription or API login of the venue's OS user) in venue-authorised project directories. `claudecode:run` (one turn as a Job, `session` to continue, live `progress` of tool calls/text, error turns fail with a resumable session id, `jsonSchema` → `structured`) and `claudecode:session` (a long-lived conversation Job: each message a turn, `{end:true}` finishes) over a bounded warm-process pool (`maxSessions`, `idleSeconds` reaper; the session lives in Claude Code's own transcript so it survives reaps and restarts). `claudecode:sessions` / `claudecode:stop`, and a venue-authorised project registry (`claudecode:projects` / `claudecode:create` / `claudecode:delete`, persisted and re-armed at boot). Projects gate on `<owner>/claudecode/<project>` × `claudecode/run`; per-call options (model, permissionMode, allowedTools, maxTurns…) layer over adapter defaults and project settings, with process env/dirs/MCP operator-only; module-shipped `claudecode` agent skill

### Changed

- Convex dependency bumped to `0.8.13`. Adapts to its removal of the no-arg `DLFSDriveManager` constructor: the WebDAV manager now passes an empty `DLFSDrives.create()` registry to `super(…)` (every drive access is already overridden to delegate to the `dlfs` adapter, so the base store is unused). `Config.WEBDAV_PATH` now tracks the canonical `DLFSWebDAV.MOUNT_PATH` exposed for embedders (Convex-Dev/convex#699), so the advertised WebDAV URL cannot drift from where DLFS mounts
- Skills sources are maybe-style: unresolved sources no longer produce create-response warnings or agent-context noise; source diagnostics render only on the `skills:list` inspection surface

### Fixed

- MCP `covia_list` `fields` projection accepts the documented array form when a client serialises it as a JSON string (as some MCP clients do): the string is JSON-parsed rather than comma-split into mangled keys that all read `exists:false` (#379)
- `agent:request` delegation turns record the calling Job id (`jobId`), like chat turns already do, so a task delivered to another agent is traceable back to the `agent:request` job — the task is keyed by that job id (#378)
- `java -jar covia.jar <relative-config-path>` works: MainVenue resolves the config argument against the working directory (`~` expanded) instead of Convex `FileUtils.getPath`, which treats bare relative names as root-relative (Convex-Dev/convex#701)
- Metadata reads for asset refs whose final segment is `content` no longer misroute (#368)
- An empty `complete_task` now delivers the turn's message text as the task result instead of looping to rejection; built-in agent tool errors state the expected call shape

## [0.9.1] - 2026-08-13

### Changed

- Generalised the `vault` adapter: it now targets the neutral `vault` DLFS
  drive by default, supports `adapters.vault.drive` for application-specific
  bindings, and no longer exposes health-specific descriptions or examples.
  Startup warns when the adapter is active without an encrypted Etch policy.
- Named-user key-pair authentication now emits the verification-method DID URL
  published by the user's DID document as the JWT `kid`; ordinary `did:key`
  authentication retains its bare-Multikey header. Covia now uses the released
  Convex `0.8.12` artifacts for the explicit-key-ID signing API (#352).
- Restored compatibility with correctly signed legacy UCAN JWTs that omit the
  `ucv` profile marker and empty `prf` claim. Venues still emit the explicit
  UCAN 0.10.0 profile, reject explicit unsupported versions, advertise the
  emitted profile in `/api/v1/status`, and return claim-specific verification
  diagnostics (#353).
- Reconciled the engineering and DX roadmaps with the 0.9.0 codebase: current
  artifact versions, shipped VenueHTTP/SSRF/CORS test coverage, narrowed auth
  and focused-test gaps, resolved Java baseline, and per-caller rate limiting.
- Made deployment and optional-module examples version-neutral, refreshed the
  module maps and contribution link, and removed obsolete A2A limitations that
  shipped in 0.9.0.
- Centralized the hosted quickstart on a configurable stable-venue URL and
  replaced deployment-specific hostnames in generic examples and test fixtures
  with RFC-reserved domains. The venue inventory now flags venue-3's expired
  TLS certificate and avoids duplicating dev-host coordinates in usage examples.
- Strengthened DLFS regression coverage for long sibling names and concurrent
  staged-file promotion, directory listing, lattice sync, and encrypted Etch v3
  restart. The reported corruption does not reproduce locally on either Convex
  0.8.11 or 0.8.12, so no dependency-level fix is claimed (#342).

### Fixed

- Scoped caller-facing asset hashes to the current caller's `/a` namespace,
  including metadata and content reads and writes. Venue catalog access is now
  explicit for federation and no longer acts as an authorization-bypassing
  fallback for user requests (#368).

## [0.9.0] - 2026-08-10

Breaking: UCAN JWTs now use the versioned Convex profile (Convex 0.8.11) —
tokens minted by pre-0.9 venues or older SDKs no longer verify. Re-issue
outstanding grants and update to covia-sdk / covia-sdk-py releases that emit
the profile (`ucv` claim, always-present `prf`).

### Added
- Operator-declared venue identity: the `did` config key is validated
  fail-closed (did:web must match the public hostname; did:key pins the venue
  key pair); DID-method resolvers now separate identity, signature verification,
  and transport routing, and did:web federation is covered end to end (#343)
- `file:move` / `file:copy` with provider-native dispatch — fully native on
  DLFS-backed roots with Convex 0.8.11 (#321)
- Outbound A2A agents modelled as assets (#340)
- Result-oriented run execution (#316)
- Agent session titles
- DLFS roots scoped to subpaths (#326)
- Venue admission documented and exposed (#318)
- Venue authentication design agreed (`venue/docs/AUTH.md`, #297)
- Etch store policy pass-through (`etch` config block): venues can run on
  encrypted Etch v3 stores, with fail-closed key sourcing (env/file/hex),
  auto-stamped and verified key-identity hints, and an embedder-supplied
  key function for caller-opened vault stores

### Changed
- Updated Convex to 0.8.11: `ucan:issue` mints genuinely non-expiring tokens
  (explicit `exp: null`) in the Convex UCAN JWT profile, replacing the 99-year
  workaround (#322)
- HITL grant expiry policy configurable (#314)
- Agent creation exclusive (#329)
- Structured admission errors (#327)
- Updated JUnit to 6.1.3 and the A2A Java SDK to 1.2.0.Final; A2A streaming
  responses use the SDK's declared union serializer and omitted cancel metadata
  is normalised to the SDK's empty-map default

### Fixed
- Standard bearer auth for outbound A2A (#339)
- A2A long-turn reattachment (#338)
- Typed and nested collection tool results preserved in agent loops (#334)
- Unavailable configured agent tools surfaced (#317)
- Forbidden returned for denied CORS origins (#320)
- MCP connection failures made actionable
- Orchestrator strict schemas resolved at the target venue
- Agent session title updates hardened
- Asset content semantics and bound agent shutdown (#331, #333)
- Agent execution durability boundary clarified (#332)

## [0.8.0] - 2026-07-31

### Added
- Dependency-light `covia-python` module with Java FFM access to embedded
  CPython, owned/reference-counted Python values, and Convex collection
  conversion; operator-configured operations ship as a separate optional
  `covia-python-adapter` venue module rather than entering `covia.jar`. Operators
  can expose fixed script operations or bounded, per-user stateful instances
  with template and function allowlists
- Per-route venue policy for embedded HTTP endpoints: contributed routes are
  raw by default and can independently opt into verified identity, user
  admission, rate limiting, and lattice sync; authenticated credential identity
  is retained separately from its mapped venue user, including for
  extender-owned authentication (#309)

### Changed
- **Embedded venue migration:** contributed routes no longer inherit Covia
  middleware from an `/api/*` path. Embedders must opt each protected route into
  the required `VenueRouteFeature` roles; see the
  [migration checklist](venue/docs/CONFIG.md#upgrading-an-embedded-venue-from-07-or-earlier)
- Snapshot and stable GitHub releases publish checksummed Python and SQL venue
  module jars alongside the dependency-free standard venue executable

### Fixed
- Embedded-route HTTP errors retain Javalin's standard representations,
  structured details, and protocol headers, with safe HTML rendering;
  unexpected errors remain diagnostic and extension-specific exception mappers
  take precedence
- Python instance management rejects the shared synthetic public principal,
  preventing anonymous callers from sharing state even if an operator grants
  public invocation of the management operations

## [0.7.0] - 2026-07-30

### Added
- HITL requests (COG-16) — typed asks, echo-consent grants over the per-user `h/` inbox; `hitl` skill
- `Hitl` builders (covia-core)
- HITL `token` ask — transports a user-signed self-sovereign token for cross-venue access (COG-19, #292)
- Agents are sub-principals with their own DID `<ownerDID>:g:<agentId>` — identity split from namespace (#280)
- Job records name the acting agent in `actor` (#280)
- `Principals` (covia-core) — agent DID minting/parsing and the SELF/OWNER/SAME_USER/FOREIGN relation
- `ucan:issue` granting surface — mint under a presented `grant/<ability>` right (COG-17)
- Federated job observation carries caller proofs (`X-Covia-Ucans` header)
- Authenticated callers get public-user access (#254)
- Archive adapter (zip/jar); `file` reads see into archives via `x.zip!/entry`
- Orchestration `["array", …]` binding — an array whose elements reference prior steps (#281)
- Bounded orchestration `foreach` steps
- Structural agent output handoffs via `go.outputPath`, with direct output when
  no path is configured
- Job-free reads of execution-scoped task/session state
- Named venue-user authentication keys with stable user identity, rotation,
  revocation, and short-lived client credential minting
- MCP discovery metadata describing whether authentication is required and
  which Covia authentication mechanism clients should use
- Configurable venue root page content, redirect, or static file for
  operator-branded public entry points
- Config validation with warnings for unknown fields by default and opt-in
  strict rejection; malformed known fields always fail startup

### Changed
- Dependency bumps — Convex 0.8.10, LangChain4j 1.18.1, Logback 1.6.1,
  JUnit 6.1.2, A2A 1.1.0.Final
- `ucan:issue` and `hitl:respond` refuse agent contexts — agents hold no granting authority (COG-17)
- Private Network Access defaults on for loopback-bound venues (#286)
- MCP tool schemas are type-less, not union arrays — strict client SDKs connect (#275)
- Scheduled events are owned by the user, fire as the agent that queued them
- Job lifecycle hardening — atomic updates, post-commit persistence, shutdown gate
- Job polling — caller-side timeouts no longer fail jobs
- Job pause/resume is adapter opt-in
- Agent LLM calls are time-bounded (`llmTimeoutMs`)
- Default agent tool pack trimmed to read-only (#60)
- Bare UCAN grant resources mean the issuer's own namespace
- Agent context assembly is prompt-cache-friendly
- Task scratch state uses the owning job record rather than a parallel task
  store
- Persistent venue stores can be opened by the operator and handed into venue
  startup, with transactional ownership and failure cleanup
- MCP tool declarations expose operation safety annotations

### Removed
- Wire self-attenuation on `/invoke` — presented proofs are additive-only (#131)
- MCP `notifications/jobUpdate` broadcast — off-spec, flooded strict clients (#274)

### Fixed
- The GHCR venue image is anonymously pullable, repository-linked, and continuously checked for public access (#212)
- Failed agent tool calls are recorded once in session conversations, while retaining structured timeline diagnostics (#290)
- Cross-user lattice writes work with a `crud/write` proof — `covia:write`/`append`/`delete` route through the same proof gate reads use (#295)
- REST `GET /assets/{id}/content` serves inline/record/dlfs content, not just blobs (#289)
- Unanswered agent tool call repaired on load — sessions no longer poisoned by a mid-call abort (#271)
- Orchestration failure containment — a failed step no longer runs its dependents (#281)
- Orchestration specs validated at construction, not mid-run (#281)
- `a/<hash>` references resolve without a leading slash
- Store unlocked when engine construction fails
- `agent:completeTask`/`failTask` tolerate cross-thread lattice read lag (#214)
- MCP sessions are bound to the authenticated creator and cannot be attached
  to or terminated by another allowed principal
- MCP-supplied UCAN proofs are transport-only and are never persisted in job
  input
- Revoked named-user keys immediately lose access, including to existing MCP
  session identifiers; rotated keys for the same user retain the stable subject
- MCP configuration validates supported fields and DID allowlist entries
- The public Docker image is invoked after publishing to verify anonymous pull
  and a real operation round trip

## [0.6.0] - 2026-07-17

### Added
- Agent skills — discoverable instruction+tool bundles agents load on demand with `skill_load`; skills are ordinary assets (`venue/docs/SKILLS.md`)
- Venue skill library — 20 skills covering every covia mechanism, from workspace and agents to grid, a2a, discovery and provenance
- `v/ops/skills` — list and read skills over workspace, venue and asset sources; exposed in the default MCP tool palette
- Skills-first agent templates — `skilled` (recommended default), caps-pinned `reader`, discovery-only `minimal`
- Venue modules ship their own skills from their own jars (covia-sql's `sql` skill)
- `langchain:models` — provider and model discovery with caller-relative readiness (#221)
- Per-agent A2A publishing — `a2a.public` for a discoverable card, `a2a.caps` ceiling to accept stranger messages
- Agents report back into a conversation — `agent:request` with `sessionId` runs the task in that session
- Token usage on job records, agent timelines, sessions and `agent:context` (#217)
- Capability gates — a grant applies only when its named gating op approves the invocation (#216)
- MCP tool bridging — external MCP tools become ordinary catalog operations, curated singly or mirrored per server (#80)
- `operation.default` — declarative argument defaults on any operation (`venue/docs/OPERATIONS.md` §5)
- Venue modules — external adapter jars loaded at boot with classloader isolation (#226)
- SQL venue module (covia-sql) — per-user lattice-backed databases and operator-registered JDBC connections (#227)
- Typed outputs on Anthropic — provider-aware forced tool calling, transparent to agents (#81)
- `temperature` and `topP` on all langchain ops (#218); venue-level Ollama base URL (#224)
- `VenueAuth.mintToken()` — self-issued JWTs for stored credentials (#219)
- `agent:create` warns on raw credentials in `config.apiKey`
- System tray icon on desktop launches (`COVIA_NO_TRAY=1` to disable)
- MCP spec-conformance tests — tools/list schemas and call-result shapes

### Changed
- Convex 0.8.9 — live lattice-context inheritance, Etch online GC, convex-db fixes (#221)
- MCP scalar tool results are text content, not `{result: …}` — the upstream rendering, shim removed
- MCP tools/list schemas pass through as declared; `outputSchema` advertised only for object-typed outputs
- DLFS WebDAV advertises `DAV: 1` only — unenforced class-2 locking is no longer claimed
- `GET /api/v1/agents` returns the same enriched entries as `agent:list`; `?status=false` for bare ids (#233)
- `GET /api/v1/jobs` returns the paged assets-style `{items, total, offset, limit}` envelope; `stats.jobs` and `stats.userJobs` on `/status` (#229)
- Relaunching an existing persistent store without a configured venue identity is a startup error, never a silent fresh DID (#232)
- covia-sql supports single-column tables (convex#646 fixed)
- Agent lifecycle ops invoked as agent tools delegate to real, owner-attributed Jobs

### Fixed
- Loopback-bound venues answer on both 127.0.0.1 and ::1 (#231)
- Outbound A2A URLs pass the http adapter's SSRF checks and operator allow/block lists (#234)
- Outbound `a2a:send` no longer corrupts per-agent endpoint URLs (#234)
- Anonymous A2A senders can poll and cancel tasks they created on public agents (#234)
- `a2a:send` works as an agent tool (#234)
- Task input rendered to models as plain text/JSON, never EDN literals (#215)
- Text-form control tool calls (`complete_task {...}`) recognised and honoured (#215)
- A task that exhausts the loop budget fails structurally instead of pinning STARTED (#215)
- Job SSE streams broadcast every status change and close on terminal frames (#225)
- Job SSE defaults to `text/event-stream` on missing/wildcard Accept; non-SSE Accept gets 406 with remedy (#222)
- Bridged MCP tool errors fail the job with the remote error text; text-only results preserved; transport failures name tool, server and cause (#80)

## [0.5.0] - 2026-07-15

### Added
- Lattice-resident goal-tree frames — durable, observable mid-run, crash resume (`venue/docs/GOAL_TREE.md`)
- Venue identity from a PKCS12 keystore (Convex CLI format) (#208)
- Startup guard against booting a store with the wrong venue key (#208)
- Resource-scoped `invoke` capability grants (#211)
- `agent:create` warnings for non-tool-capable models and unresolvable tools (#205)
- Configurable tool-loop iteration limit (venue default + per-agent)
- User activity stamps (`meta.created` / `meta.updated`)
- Java client parity: private jobs, `ucan:verify`, job-free reads

### Changed
- Crash recovery stabilises jobs, never re-executes (`venue/docs/JOBS.md` §Recovery) (#214)
- Capability denials name the missing capability; public callers get the auth remedy (#206, #209, #211)
- Agent tool failures recorded on timeline/session, visible via `agent:context` (#211)
- Write clock refreshed per dispatch/sync

### Fixed
- Job SSE stream route unreachable (#200)
- Suspended/deleted agents hanging callers (#201, #202)
- MCP tool calls losing scalar results
- Crash-resume could re-dispatch a pre-crash tool call (#214)

## [0.4.0] - 2026-07-10

### Added
- Cross-venue trust C3a: self-sovereign grants, identity tokens, `venue/relay` delegations (`venue/docs/UCAN.md` §5.6) (#100)
- `ucan:verify` — diagnosable token verification against venue trust policy
- Client-side UCAN minting (`covia.grid.auth.UcanTokens`)
- Rate limiting and backpressure (per-caller request limits + concurrent-job cap; client 429 retry)
- Cross-user DLFS reads/writes/deletes under UCAN proof (#98)
- Reference-addressed content: CAS assets, lattice values, and DLFS drive paths via one resolver
- LangChain vision (image blocks, reference-resolved) + `maxTokens` on Anthropic (#198)
- Audience-bound client auth (`VenueAuth.keyPair`, `VenueDID.discover`) (#199)
- Job-free agent reads: `GET /api/v1/agents` (#180)
- A2A authenticated agent catalogue (#187)
- Values API field projection (#191)
- Private (memory-only) jobs via `private: true` + `enablePrivateJobs` (#192)
- Context scope chain: agent → session → frame tiers (#142)
- Per-turn caller attribution in session history (#84)
- `covia:slice maxSize` (#78)
- `agent:deleteSession` (`venue/docs/AGENT_SESSIONS.md` §7.2)
- Maven Central publishing under `ai.covia` (#33)

### Changed
- Convex 0.8.7 → 0.8.8; UCAN verification on the convex-core authority layer (#196)
- DLFS capability resources use DID-scoped paths (#196)
- Job reads delegable like `j/<id>` lattice reads; mutations stay owner-only (#102)
- Job deletion is permanent (removes the durable record)
- Agent config collapsed to a single canonical `config` slot (#144)
- Harness tool misuse fails at point of use (#143)
- `asset:store` rejects `metadata.content` shape deviations (#173)
- Agent templates instruct reference-passing handoff (#71)

### Fixed
- Confused deputy in cross-user DLFS writes
- `dlfs:write` from a plain content asset
- `/openapi` shadowed by the Convex chain API document
- OpenAPI route parameters, build version, ReDoc
- Jetty `UriCompliance` narrowed (#153)
- Central snapshots endpoint

## [0.3.0] - 2026-07-06

### Added
- Job-free lattice reads: `GET /api/v1/values/*` (`venue/docs/READ_API.md`) (#177)
- Secret deletion via `covia:delete path=s/<name>` (#166)
- `did:web` alias publication for public hostnames (#167)
- `POST /invoke` millisecond `wait` window (#140)
- Optional output-schema validation (#51)
- New LLM providers: Gemini, xAI, DeepSeek (#158)
- Configurable HTTP `bindAddress` (#129)
- Venue-wide agent defaults (LLM + transition op)
- Build version in `GET /status` (#139)

### Changed
- Venue lattice is a single whole-value-LWW node — durable deletions (requires Convex 0.8.7)
- Tool-call arguments parsed once at the wire boundary (#89, #58)
- `schema:*` ops never reparse JSON-looking strings
- Adapter asset installation fails loudly (`strictAssets`)
- Convex 0.8.6, Javalin 7, Jetty 12 (#152); MCP SDK 2.0.0 (#156); A2A 1.0.0.Final (#155)
- Agents strict-allowlist by default; `defaultTools: true` opts in (#92, #134)
- `covia:write` requires a `value`
- Assets retrievable by lattice address (#150)
- Default Anthropic model `claude-sonnet-4-6`
- Private Network Access header gated behind `allowPrivateNetwork` (#130)
- CRUD operations return meaningful outcomes (#147, #132)

### Security
- Capability enforcement active: read-only public ceiling, `operationAbility` map, `auth.public.caps` (#148)
- JWT audience validated; UCANs classified by `att` (#149)
- UCAN bearer signature bound to claimed issuer
- Failed bearer verification is a hard 401
- Malformed JSON bodies return 400

### Fixed
- Path navigation surfaces abnormal errors; typed `WrongScopeException` (#175)
- Remote-fetch failures are errors, not "not found" (#174)
- `v/`-namespace startup writes after restart (#159)
- Monotonic job IDs — ordered job listings under concurrency
- Tool-loop exhaustion fails the task (#138)
- Deep-write shape conflicts surfaced, not silently replaced
- Hang audit: indefinite blocking paths bounded
- Token attenuation enforced on direct `/invoke` (#131)
- Agent run loop executes as the owner, not the waking caller (#91)

## [0.2.0] - 2026-06-15

### Added
- User memory: single `memory` tool (recall/remember/update/forget) over a durable per-user list (`venue/docs/AGENT_CONTEXT.md`)
- `venue/docs/AGENT_CONTEXT.md` — agent-context design

### Changed
- Cross-venue references denote definitions, never execution sites: `did:web` refs fetch hash-verified and run locally; explicit execution via `grid:run`/`grid:invoke` (`venue/docs/OPERATIONS.md` §4)
- `did:web` port encoding + localhost http per spec
- Named catalog references resolve name→hash at the publisher, then fetch
- `asset:pin` adopts remote assets (definition + declared content, verified)
- Fetched definitions cached by content hash
- Agent context resolution fails loudly, not silently

### Fixed
- `covia:delete` is durable: `w/`/`o/`/`h/` namespaces replaced whole-value under LWW (deletes no longer resurrect on merge-back)

## [0.1.0] - 2026-06-12

First release under the agreed versioning story (independent SemVer per
artifact). Companion releases: TypeScript SDK 1.5.0 (npm), Python SDK 0.2.0 (PyPI).

### Added
- Cross-venue federation tests (`TwoVenueTestServer`); `VenueHTTP` contract tests
- Grid scheduler for deferred invocation; agent wake routed through it
- Goal-tree progressive ancestor compaction
- Per-user secret bootstrap from venue config
- Persistence resilience: periodic fsync sweep, hard-kill tests
- DX scaffolding: README, `DX_PLAN.md`, CONTRIBUTING, SECURITY, changelog, CI gate
- Issue/PR templates, Dependabot, CodeQL
- `publish-docker.yml` — single source of `ghcr.io/covia-ai/covia` tags
- Stable venue tier (venue-1/-2) on the `:stable` channel

### Changed
- Depends on released Convex 0.8.5 from Maven Central
- `CLAUDE.md` → `AGENTS.md`
- `GET /assets` consistency; `asset:list` scoped to caller
- Strong-consistency contract for CoviaAdapter CRUD
- Logging on SLF4J
- Hardened deploy JVM options and health checks
- Shared LLM-agent infrastructure in `AbstractLLMAdapter`
- Deploy workflows consume the published Docker image
- Licensing: platform EPL-2.0, SDKs Apache-2.0

### Fixed
- LangChain fails fast on missing API key (#91)
- `covia_read` Blob-keyed index entries
- `agent:trigger` wait cancellable
- Parallel test-suite races

## [0.0.1] - 2026-01-23

Initial public release: venue server with the adapter framework, lattice-backed
content-addressed assets, the async job model with SSE, multi-protocol surface
(REST / MCP / A2A / DID), and strategy-based authentication.

[Unreleased]: https://github.com/covia-ai/covia/compare/0.9.3...HEAD
[0.9.3]: https://github.com/covia-ai/covia/compare/0.9.2...0.9.3
[0.9.2]: https://github.com/covia-ai/covia/compare/0.9.1...0.9.2
[0.9.1]: https://github.com/covia-ai/covia/compare/0.9.0...0.9.1
[0.9.0]: https://github.com/covia-ai/covia/compare/0.8.0...0.9.0
[0.8.0]: https://github.com/covia-ai/covia/compare/0.7.0...0.8.0
[0.7.0]: https://github.com/covia-ai/covia/compare/0.6.0...0.7.0
[0.6.0]: https://github.com/covia-ai/covia/compare/0.5.0...0.6.0
[0.5.0]: https://github.com/covia-ai/covia/compare/0.4.0...0.5.0
[0.4.0]: https://github.com/covia-ai/covia/compare/0.3.0...0.4.0
[0.3.0]: https://github.com/covia-ai/covia/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/covia-ai/covia/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/covia-ai/covia/compare/0.0.1...0.1.0
[0.0.1]: https://github.com/covia-ai/covia/releases/tag/0.0.1
