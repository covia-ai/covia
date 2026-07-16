# Changelog

All notable changes to Covia are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Covia is pre-1.0, so minor versions may include breaking changes.

## [Unreleased]

### Added
- System tray icon on desktop launches — open, close venue, exit (`COVIA_NO_TRAY=1` to disable)

### Fixed
- Task input rendered to models as plain text/JSON, never EDN map literals (#215)
- Control tools emitted as plain text (`complete_task {...}`) now recognised and honoured (#215)
- A task that burns the whole loop budget fails with a structured error instead of pinning STARTED (#215)

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
