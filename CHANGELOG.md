# Changelog

All notable changes to Covia are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Covia is pre-1.0, so minor versions may include breaking changes.

## [Unreleased]

Work on `develop`, targeting `0.0.2`. (Not exhaustive — see the git history for detail.)

### Added
- True cross-venue federation: `TwoVenueTestServer` and end-to-end cross-venue tests; `VenueHTTP` client contract tests against a real venue.
- Grid scheduler for deferred operation invocation; agent wake routed through it.
- Goal-tree agent improvements, including progressive ancestor compaction.
- Per-user secret bootstrap from venue config.
- Persistence resilience: periodic `fsync` sweep with `PersistenceHandler.flush()` bounding unclean-shutdown data loss; hard-kill / soft-kill resilience tests.
- Developer-experience scaffolding: developer-facing `README.md`, [`DX_PLAN.md`](DX_PLAN.md) public roadmap, `CONTRIBUTING.md`, `SECURITY.md`, this changelog, and a CI build-and-test gate.
- Issue and pull-request templates, Dependabot (Maven + GitHub Actions), and CodeQL scanning.
- A dedicated `publish-docker.yml` workflow — the single source of `ghcr.io/covia-ai/covia` image tags (`:latest` + `:<sha>`).

### Changed
- Renamed `CLAUDE.md` to `AGENTS.md` (a `CLAUDE.md` import pointer remains).
- `GET /assets` response made consistent; no-limit paging fixed; `asset:list` scoped to the caller's own pinned assets.
- Documented a strong-consistency contract for `CoviaAdapter` CRUD, with regression tests.
- Logging moved off `printStackTrace`/`System.err` onto SLF4J.
- Hardened deployment JVM options and container health checks to prevent GC death-spirals.
- Refactored shared LLM-agent infrastructure into `AbstractLLMAdapter`.
- The Azure/EC2 deploy workflows now consume the published Docker image instead of each building and pushing their own.
- Documentation drift fixed: `BUILD.md` covers `covia-core` and the Convex prerequisite; `deploy/README.md` has a working Caddy install and release-based JAR download.

### Fixed
- LangChain adapter now fails fast on a missing API key (#91).
- `covia_read` resolves Blob-keyed index entries by hex key.
- `agent:trigger`'s blocking wait is now cancellable; removed an unbounded status poll.
- Parallel test-suite races made deterministic.

## [0.0.1] - 2026-01-23

Initial public release: venue server with the adapter framework, lattice-backed
content-addressed assets, the async job model with SSE, multi-protocol surface
(REST / MCP / A2A / DID), and strategy-based authentication.

[Unreleased]: https://github.com/covia-ai/covia/compare/0.0.1...develop
[0.0.1]: https://github.com/covia-ai/covia/releases/tag/0.0.1
