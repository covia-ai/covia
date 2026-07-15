# Changelog

All notable changes to Covia are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Covia is pre-1.0, so minor versions may include breaking changes.

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
- Cross-venue trust (Phase C3a): **self-sovereign grants** — a resource owner signs delegations with their own `did:key` and they verify on any venue holding the data (no venue involved in issuance); delegation chains with per-hop attenuation; third-party roots refused. Grid hops forward the caller's authority through the `ucans` proof channel: **identity tokens** (empty-`att` UCANs audienced to the target venue) carry the caller's identity across relays, and a **`venue/relay`** delegation has the venue hop as itself. Relays forward only provably-admissible tokens. See COG-15 and `venue/docs/UCAN.md` §5.6. (#100)
- `ucan:verify` — verifies a token against the venue's trust policy and explains the verdict: validity with a diagnosable reason, delegation chain depth and root issuer, per-capability root-authority verdict (owner / venue / refused), and an optional would-it-authorise check. The diagnostic counterpart to "Access denied".
- Client-side UCAN minting (`covia.grid.auth.UcanTokens`): `identityToken`, `grant`, `relayDelegation` — the tokens self-sovereign callers sign with their own keys.
- **Rate limiting and backpressure** — per-caller token-bucket request limits (429 + `Retry-After`) and a concurrent-job cap (admission control: block briefly, then shed) — on by default for non-loopback binds, configurable via the `rateLimit` block. The Java client (`VenueHTTP`) retries 429s automatically (Retry-After floor, full-jitter backoff, bounded) and throws a typed `RateLimitException` on exhaustion. See `venue/CLAUDE.md` §Rate limiting.
- Cross-user DLFS via UCAN: reads, writes and deletes on another user's drive when the presented proof authorises them (`<owner>/dlfs/<drive>/<path>` resources, delegation chains supported); mutations land under the owner's key with the caller recorded. (#98)
- Reference-addressed content: `Engine.resolveContent`/`putContent` resolve any content reference — CAS assets, lattice values, and **DLFS drive paths** (a `ContentProvider` seam; DLFS is an alternative content storage mechanism, not a special case). `asset:content` serves drive paths; `dlfs:write asset:` refs accept any resolvable source. Assets may declare DLFS-resident content in metadata: `content: {dlfs, sha256}` is pinned (drift fails loudly), `content: {dlfs}` is a live binding.
- LangChain vision: user message content accepts image blocks — inline base64 or, preferred, a reference (`a/<hash>`, `w/…`, `dlfs/<drive>/…`, DID URL) resolved at call time under the caller's authority so job records keep bytes out. `maxTokens` honoured on `langchain:anthropic`. (#198)
- Audience-bound client auth: `VenueAuth.keyPair(kp, venueDID[, lifetime])` binds each self-issued JWT to its target venue (capture-replay containment); `VenueDID.discover(baseUrl)` resolves the DID to bind to from `/.well-known/did.json`. (#199)
- Job-free agent reads: `GET /api/v1/agents` and `/agents/{id}` share the `agent:list`/`agent:info` accessors with no job persisted. (#180)
- A2A: authenticated agent catalogue via `GetExtendedAgentCard`. (#187)
- Values API: field projection (sparse fieldsets) on `values/list`. (#191)
- Private jobs: `private: true` on invoke creates a memory-only job — never persisted, gone on restart; venue opt-in via `enablePrivateJobs`. Operator telemetry unaffected. (#192)
- Context scope chain: agent → session → frame context tiers with lexical semantics and tombstone masking. (#142)
- Session history: opt-in per-turn caller attribution. (#84)
- `covia:slice` accepts `maxSize` (CVM storage size, distinct from render budget); exact reads fail on overflow rather than summarising. (#78)
- `agent:deleteSession` — delete a session on an agent, removing the session record (history, pending, meta) so a user can hold a private conversation and delete it afterwards. Job records are not touched — callers hold their own Job IDs and delete those separately. An in-flight chat on the session is failed cleanly. Enabled by default; operators disable via `{"adapters": {"agent": {"sessionDelete": false}}}`. See `venue/docs/AGENT_SESSIONS.md` §7.2.
- Library modules publish to Maven Central under `ai.covia` (`covia-core`, `venue`, `workbench` + parent); snapshots on the Central snapshots repository. (#33)

### Changed
- Convex dependency **0.8.7 → 0.8.8**; UCAN verification migrated onto the convex-core authority layer (`UCANValidator.isAuthorised` with root-authority policy — the enabler for self-sovereign cross-venue trust). `Venue.getAssetDID` now returns `DIDURL` (`DID.withPath` removed upstream). (#196)
- DLFS capability resources use the DID-scoped path form `dlfs/<drive>/<path>` (owner-prefixed for cross-user); the legacy `dlfs://<drive>/…` scheme form remains an own-drive ceiling shorthand. Own-drive DLFS *reads* are covered by the standard read-only ceiling like `/w/` reads; writes remain denied. (#196)
- Reading a job is the same delegable right as reading its `j/<id>` lattice path — one shared check; job mutations remain owner-only. (#102)
- Job deletion is now permanent: `JobManager.deleteJob` (and `PUT /api/v1/jobs/{id}/delete`) removes the durable record from the owner's job index instead of only evicting the in-memory cache. Previously "deleted" jobs remained readable via the lattice fallback.
- Agent config collapsed to a single canonical `config` slot (was split across `config` / `state.config`). (#144)
- Agent harness tool calls outside their valid runtime fail at the point of use with a diagnosable error (not venue logs). (#143)
- `asset:store` rejects `metadata.content` shape deviations instead of silently rewriting (the asset ID is the metadata hash). (#173)
- Manager/worker agent templates instruct reference-passing handoff (paths / job refs, not payloads) between pipeline stages, with the capability requirements documented. (#71)

### Fixed
- Confused deputy in cross-user DLFS writes: a caller-supplied `asset:` reference now resolves under the *caller's* authority, never the drive owner's.
- `dlfs:write` from a plain content asset (metadata without an `operation` field) failed "Asset not found".
- `covia.jar` served the Convex chain API document at `/openapi`, shadowing covia's own.
- OpenAPI: route parameters documented, build version tracked, ReDoc restored.
- Jetty `UriCompliance` narrowed to `AMBIGUOUS_PATH_SEPARATOR` only. (#153)
- Central snapshots endpoint corrected (`central.sonatype.com`, not `.org`).

## [0.3.0] - 2026-07-06

### Added
- Job-free lattice reads: `GET /api/v1/values/{read,list,slice,inspect,aggregate,count}` — synchronous, capability-checked, no job persisted (fixes read-driven job/etch bloat). New `covia:aggregate`/`count` op. See `venue/docs/READ_API.md`. (#177)
- Secrets can be deleted: `covia:delete path=s/<name>` (whole records, idempotent, capability-gated). (#166)
- Venues publish a spec-compliant `did:web:<hostname>` alias when a public hostname is set — did:web for discovery, did:key stays canonical. (#167)
- `POST /invoke` accepts a millisecond `wait` window (`false` async / `true` up to the 120s cap / integer ms); malformed `wait` → 400. (#140)
- Optional operator-set output-schema validation (`outputValidation: off|warn|strict`, default off). (#51)
- New first-class LLM providers: Google Gemini, xAI, and DeepSeek. (#158)
- Configurable HTTP `bindAddress` (defaults to all interfaces). (#129)
- Configurable venue-wide agent defaults (default LLM and transition op).
- Build version reported in `GET /status`. (#139)

### Changed
- **The venue lattice is a single whole-value-LWW node — deletions are durable venue-wide (requires Convex 0.8.7).** Existing venue data loads unchanged. See `venue/docs/GRID_LATTICE_DESIGN.md` §A.2.
- LLM tool-call arguments are parsed once at the wire boundary; internal dispatch never coerces. (#89, #58)
- `schema:*` operations treat the value exactly as given — no silent reparse of JSON-looking strings.
- Adapter asset installation fails loudly on a missing/unreadable resource (`strictAssets: false` downgrades to warnings).
- Upgraded to Convex 0.8.6, Javalin 7, and Jetty 12. (#152)
- Upgraded the MCP SDK to 2.0.0 (#156) and the A2A SDK to 1.0.0.Final (#155).
- Agents are strict-allowlist by default; opt into the default tool pack with `defaultTools: true`. (#92, #134)
- `covia:write` requires a `value`; path-only writes are rejected.
- Assets can be retrieved by lattice address, not just hex hash. (#150)
- The default Anthropic chat operation is now `claude-sonnet-4-6`.
- Dependency maintenance (logback, FlatLaf, assembly plugin, several GitHub Actions).
- Private Network Access response header is off by default, gated behind `allowPrivateNetwork`. (#130)
- covia CRUD operations return meaningful outcomes (`{existed}`, `{deleted}`, `{existed, index, newSize}`) instead of tautological flags; read-family field names unified. (#147, #132)

### Security
- Capability enforcement is now active — a read-only ceiling for unauthenticated callers, `operationAbility` mapping every venue mutation, operator-overridable via `auth.public.caps`. (#148)
- JWT audience is validated at the auth boundary; UCANs are classified by their `att` array. (#149)
- A UCAN bearer's signature is bound to its claimed issuer, closing an identity-spoofing gap.
- A bearer token that fails verification is a hard 401 — never a silent downgrade to the public identity.
- Malformed JSON request bodies return 400 with the parse cause, not a generic 500.

### Fixed
- Path navigation surfaces abnormal errors instead of masking them as phantom absence; scope-misuse gets a typed `WrongScopeException`. (#175)
- Remote-fetch failures surface as errors (`RemoteFetchException` / HTTP 502), not "not found". (#174)
- `v/`-namespace startup writes no longer silently no-op after a restart. (#159)
- Job IDs are monotonic, so the per-user job index and `GET /jobs` listing are fully ordered under concurrency.
- An agent that exhausts its tool-call iteration limit now fails its task. (#138)
- A missing intermediate value during path resolution is surfaced, not nulled; a named-key write into a list gives a clear shape-conflict error.
- A deep write through or into an existing scalar throws a shape conflict instead of silently replacing it.
- Indefinite-blocking paths found in a hang audit are now bounded.
- A presented token's attenuation is enforced on the direct `/invoke` path. (#131)
- An agent's run loop executes under the owner's identity, not the waking caller's. (#91)

## [0.2.0] - 2026-06-15

### Added
- **User memory.** A single `memory` tool (`v/ops/memory`, dispatched by a `command`: `recall` / `remember` / `update` / `forget`) maintains a per-user numbered list of durable facts in the user's workspace — one tool definition rather than four ops, to keep agent tool context small. `recall` doubles as a `config.context` assemble-op (injected as system context every turn) and renders either a flat list or, given a `displayField`, the active/surfaceable values of a slug-keyed map collection — skipping entries whose `status` is not `active`, that are `surfacing: hold`, or that carry a `mergedInto` — so a curated store (e.g. a problem list) can be surfaced with no separate copy. Mutations rewrite the whole list value under LWW, so removals are durable. See `venue/docs/AGENT_CONTEXT.md`.
- `venue/docs/AGENT_CONTEXT.md` (renamed from `CONTEXT.md`): the agent-context design — entry forms, the specify→return→render data-shape contract, and the failure model.

### Fixed
- `covia:delete` is now durable. The user-writable namespaces (`w/`, `o/`, `h/`) previously merged per-entry (a union), so a deleted key was re-introduced whenever the live cursor merged with a pre-delete snapshot — which the persistence propagator does on every announce round-trip (deletes "came back" within ~30s and after restart). These namespaces are now whole `{updated, data}` values replaced as a unit under LWW (`LWWWrapperLattice`), the same trade the `:schedule` slot made. Write stamps are strictly increasing (`max(now, current+1)`), so fast sequential writes in the same millisecond each dominate the value they replace in either merge order. The wrapper is storage shape only — paths, reads, and lists are unchanged; pre-existing unwrapped workspace data remains readable and is migrated in place by the first write.

### Changed
- **Cross-venue reference semantics.** Invoking a `did:web:<venue>/a/<hash>` operation reference now **fetches** the content-addressed definition from the publishing venue (hash-verified) and executes it locally, as an ordinary local job — references denote definitions, never execution sites. The previous reference-inferred remote delegation (which blocked a thread and left no job record on the accepting venue) is removed; cross-venue *execution* is explicit via `grid:run` / `grid:invoke` with a `venue` argument, which records a job on each venue. Semantics pinned by `RemoteAssetFetchTest` / `RemoteOperationTest`; see `venue/docs/OPERATIONS.md` §4.
- `Grid.connect` resolves `did:web` DIDs with percent-encoded ports (`did:web:host%3A8080`) and uses http for localhost, per the did:web spec note.
- Named catalog references (`did:web:<venue>/v/ops/<name>`) resolve as fetches too: the name is resolved to an asset id at the publishing venue (names are mutable bindings, trusted at fetch time), then the definition travels over the same hash-verified path. The job record carries the resolved hash — name→hash provenance at invoke time. Fetches remain transient; pin is the explicit adoption act.
- `asset:pin` can now actually adopt remote assets: pinning a `did:web:…` reference (hash or named form) fetches the definition hash-verified — plus declared content, verified against its sha256 — and stores it durably in the caller's namespace.
- Fetched definitions are cached in memory by content hash (immutable, so never stale): repeat invokes of a remote reference no longer re-fetch, and a cached definition resolves even if the reference's venue hint is unreachable. The cache is transient plumbing, not adoption.
- **Agent context resolution fails loudly, not silently.** A `config.context` / `state.context` that is present but not an array now throws (a malformed value was previously dropped, leaving the agent with no context and no signal). A context entry that *errors* while resolving — an assemble op that throws or times out, a read that genuinely fails — now injects a visible `[Context: <label> — unavailable: <reason>]` element instead of vanishing, so the model can adapt; an absent/empty source is still skipped, and a `required` failure still throws. See `venue/docs/AGENT_CONTEXT.md`.

## [0.1.0] - 2026-06-12

The first release under the agreed versioning story (independent SemVer per
artifact; the platform version names the product generation). Companion
artifact releases: TypeScript SDK 1.5.0 (npm), Python SDK 0.2.0 (PyPI).
(Not exhaustive — see the git history for detail.)

### Added
- True cross-venue federation: `TwoVenueTestServer` and end-to-end cross-venue tests; `VenueHTTP` client contract tests against a real venue.
- Grid scheduler for deferred operation invocation; agent wake routed through it.
- Goal-tree agent improvements, including progressive ancestor compaction.
- Per-user secret bootstrap from venue config.
- Persistence resilience: periodic `fsync` sweep with `PersistenceHandler.flush()` bounding unclean-shutdown data loss; hard-kill / soft-kill resilience tests.
- Developer-experience scaffolding: developer-facing `README.md`, [`DX_PLAN.md`](DX_PLAN.md) public roadmap, `CONTRIBUTING.md`, `SECURITY.md`, this changelog, and a CI build-and-test gate.
- Issue and pull-request templates, Dependabot (Maven + GitHub Actions), and CodeQL scanning.
- A dedicated `publish-docker.yml` workflow — the single source of `ghcr.io/covia-ai/covia` image tags: `:latest` (develop), `:stable` (master), `:<x.y.z>` (release builds), `:<sha>` (every build).
- A stable venue tier (venue-1, venue-2) running the `:stable` image channel, deployed automatically from `master`.

### Changed
- Covia now depends on released **Convex 0.8.5** from Maven Central — a clean clone builds with `mvn clean install`, with no Convex source build. CI workflows no longer build Convex from source.
- Renamed `CLAUDE.md` to `AGENTS.md` (a `CLAUDE.md` import pointer remains).
- `GET /assets` response made consistent; no-limit paging fixed; `asset:list` scoped to the caller's own pinned assets.
- Documented a strong-consistency contract for `CoviaAdapter` CRUD, with regression tests.
- Logging moved off `printStackTrace`/`System.err` onto SLF4J.
- Hardened deployment JVM options and container health checks to prevent GC death-spirals.
- Refactored shared LLM-agent infrastructure into `AbstractLLMAdapter`.
- The Azure/EC2 deploy workflows now consume the published Docker image instead of each building and pushing their own.
- Documentation drift fixed: `BUILD.md` covers `covia-core` and the Convex prerequisite; `deploy/README.md` has a working Caddy install and release-based JAR download.
- Licensing clarified: the platform stays **EPL-2.0**; the SDK libraries (TypeScript, Python) are **Apache-2.0**.
- README build badge now points at the `Test` workflow (build + full test suite) instead of the snapshot build; the JAR download points at `latest-snapshot` until `0.1.0` ships.

### Fixed
- LangChain adapter now fails fast on a missing API key (#91).
- `covia_read` resolves Blob-keyed index entries by hex key.
- `agent:trigger`'s blocking wait is now cancellable; removed an unbounded status poll.
- Parallel test-suite races made deterministic.

## [0.0.1] - 2026-01-23

Initial public release: venue server with the adapter framework, lattice-backed
content-addressed assets, the async job model with SSE, multi-protocol surface
(REST / MCP / A2A / DID), and strategy-based authentication.

[Unreleased]: https://github.com/covia-ai/covia/compare/0.3.0...develop
[0.3.0]: https://github.com/covia-ai/covia/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/covia-ai/covia/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/covia-ai/covia/compare/0.0.1...0.1.0
[0.0.1]: https://github.com/covia-ai/covia/releases/tag/0.0.1
