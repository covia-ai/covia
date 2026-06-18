# Changelog

All notable changes to Covia are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Covia is pre-1.0, so minor versions may include breaking changes.

## [Unreleased]

### Changed
- **covia CRUD operations return meaningful values, not tautological flags.** `covia:write` / `copy` / `delete` / `append` previously returned a constant `{written|copied|deleted|appended: true}` that only restated job success. They now carry actual variables: `write` / `copy` return `{pathCreated: true}` *only* when the write had to build a missing parent path (intermediate hierarchy), omitted otherwise — and independent of whether a value already sat at the leaf; `append` returns `{newSize}` (the vector's element count after the append) plus `pathCreated` when it built hierarchy; `delete` returns an empty map — it removes only the addressed value and never prunes parent hierarchy, so there is nothing structural to report. Read-family field names are now consistent: `covia:read` reports `valueBytes` (always present; was `size`, only on truncation), `truncated` omitted when false; `covia:slice` / `list` report `totalSize` (was `count`) and always echo `offset`. Operation success remains the job's terminal status. Inputs are unchanged. (#132)

### Fixed
- **A deep write into a scalar no longer silently destroys it.** A `covia:write`/`covia:append` whose path navigates *through* an existing non-map, non-vector value — e.g. `w/a/b` where `w/a` holds a string — previously replaced the scalar with a map (silent data loss). It now throws a clear shape conflict naming the node (*"Cannot set key '…' on the scalar value at '…'"*), mirroring the list case fixed for GetMine-ai/demo#146. A genuinely absent intermediate is still auto-vivified; only a real value is protected. (`deepSet`/`deepAppend`)
- **A presented token's attenuation is now enforced on the direct invoke path (#131).** `CoviaAPI`/`MCP` attached a caller's UCAN proofs but never set a capability ceiling, so an *attenuated* bearer/UCAN presented to `/invoke` ran with the identity's full authority. Both transports now derive a self-attenuation ceiling and apply it via the existing `enforceCaps`. Done on the principled owner-scoped model: capability resources are absolute (owner-named), so enforcement canonicalises every resource — and each capability's `with` — to `<ownerDID>/…` before matching (`Capability.covers`), with DID-URL and `file://`/`dlfs://` resources already absolute. This unifies the self and cross-user enforcement conventions and is backward-compatible with bare agent-config caps (both sides canonicalise identically). The ceiling is **owner-authored** (the owner is the authority over its own namespace; the venue only enforces) and can only *narrow* a caller's own authority, never widen it. New convex-core primitive `UCANValidator.capabilitiesFor` is the canonical home for the selection; covia uses an interim `CapabilityChecker.selfCapabilities` (deprecated — to be removed when covia's Convex dependency includes `capabilitiesFor`).
- **An agent's run loop now executes under the agent owner's identity, not the waking caller's.** `wakeAgent` previously captured the triggering caller's `RequestContext`, so under concurrent mixed-identity wakes the loop — and every identity-scoped access during it (secret `/s/`, workspace `w/`, job ownership) — could resolve in the wrong namespace. The run loop is now a pure mechanism keyed on the agent's address (`ownerDID` + `agentId`) and runs under a fresh owner-scoped context carrying none of the waker's proofs or caps; the in-memory run-loop registries key on the full address so two users' same-named agents no longer share a slot. `Engine.resolveSecret` now distinguishes a genuinely absent secret (null) from a decrypt/key error (logged at WARN + thrown) instead of masking both as absence. (#91)

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

[Unreleased]: https://github.com/covia-ai/covia/compare/0.1.0...develop
[0.1.0]: https://github.com/covia-ai/covia/compare/0.0.1...0.1.0
[0.0.1]: https://github.com/covia-ai/covia/releases/tag/0.0.1
