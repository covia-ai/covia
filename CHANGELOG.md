# Changelog

All notable changes to Covia are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Covia is pre-1.0, so minor versions may include breaking changes.

## [Unreleased]

### Fixed
- `covia:delete` is now durable. The user-writable namespaces (`w/`, `o/`, `h/`) previously merged per-entry (a union), so a deleted key was re-introduced whenever the live cursor merged with a pre-delete snapshot — which the persistence propagator does on every announce round-trip (deletes "came back" within ~30s and after restart). These namespaces are now whole `{updated, data}` values replaced as a unit under LWW (`LWWWrapperLattice`), the same trade the `:schedule` slot made. Write stamps are strictly increasing (`max(now, current+1)`), so fast sequential writes in the same millisecond each dominate the value they replace in either merge order. The wrapper is storage shape only — paths, reads, and lists are unchanged; pre-existing unwrapped workspace data remains readable and is migrated in place by the first write.

### Changed
- **Cross-venue reference semantics.** Invoking a `did:web:<venue>/a/<hash>` operation reference now **fetches** the content-addressed definition from the publishing venue (hash-verified) and executes it locally, as an ordinary local job — references denote definitions, never execution sites. The previous reference-inferred remote delegation (which blocked a thread and left no job record on the accepting venue) is removed; cross-venue *execution* is explicit via `grid:run` / `grid:invoke` with a `venue` argument, which records a job on each venue. Semantics pinned by `RemoteAssetFetchTest` / `RemoteOperationTest`; see `venue/docs/OPERATIONS.md` §4.
- `Grid.connect` resolves `did:web` DIDs with percent-encoded ports (`did:web:host%3A8080`) and uses http for localhost, per the did:web spec note.
- Named catalog references (`did:web:<venue>/v/ops/<name>`) resolve as fetches too: the name is resolved to an asset id at the publishing venue (names are mutable bindings, trusted at fetch time), then the definition travels over the same hash-verified path. The job record carries the resolved hash — name→hash provenance at invoke time. Fetches remain transient; pin is the explicit adoption act.
- `asset:pin` can now actually adopt remote assets: pinning a `did:web:…` reference (hash or named form) fetches the definition hash-verified — plus declared content, verified against its sha256 — and stores it durably in the caller's namespace.
- Fetched definitions are cached in memory by content hash (immutable, so never stale): repeat invokes of a remote reference no longer re-fetch, and a cached definition resolves even if the reference's venue hint is unreachable. The cache is transient plumbing, not adoption.

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

[Unreleased]: https://github.com/covia-ai/covia/compare/0.1.0...develop
[0.1.0]: https://github.com/covia-ai/covia/compare/0.0.1...0.1.0
[0.0.1]: https://github.com/covia-ai/covia/releases/tag/0.0.1
