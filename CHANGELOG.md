# Changelog

All notable changes to Covia are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Covia is pre-1.0, so minor versions may include breaking changes.

## [Unreleased]

### Changed
- Job lifecycle hardening — atomic updates, post-commit persistence, shutdown admission gate
- Job polling improved — caller-side timeouts no longer fail jobs
- Job pause/resume is adapter opt-in; resume never re-invokes from stored input
- Agent LLM calls are time-bounded (`llmTimeoutMs`) and interrupt the provider on cancel
- Default agent tool pack trimmed to read-only — skills add tools on demand (#60)
- Bare UCAN grant resources mean the issuer's own namespace — canonicalised at issuance (custodial) and evaluation (self-sovereign)

### Fixed
- `a/<hash>` references resolve without a leading slash
- Store unlocked when engine construction fails

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

[Unreleased]: https://github.com/covia-ai/covia/compare/0.5.0...develop
[0.5.0]: https://github.com/covia-ai/covia/compare/0.4.0...0.5.0
[0.4.0]: https://github.com/covia-ai/covia/compare/0.3.0...0.4.0
[0.3.0]: https://github.com/covia-ai/covia/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/covia-ai/covia/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/covia-ai/covia/compare/0.0.1...0.1.0
[0.0.1]: https://github.com/covia-ai/covia/releases/tag/0.0.1
