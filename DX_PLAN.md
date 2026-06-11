# Developer Experience Plan

This is our shared, public roadmap for making Covia a joy to **adopt, build on, self-host, and contribute to**. It's deliberately open: if something here resonates, pick it up; if you think we've got a priority wrong, [open a discussion](https://github.com/orgs/covia-ai/discussions) or say so on [Discord](https://discord.gg/fywdrKd8QT). Nothing in this document is set in stone — it's a conversation.

> **TL;DR for the impatient:** the Covia *engine* is in good shape — clean adapter architecture, a real lattice foundation, multi-protocol surface (REST / MCP / A2A / DID), ~1,300 passing tests gating every PR, and published TypeScript and Python SDKs. What we're focused on now is the *experience around* that engine: getting a newcomer from `git clone` to "I ran my first federated operation" in minutes, and shipping current, coherently-versioned artifacts.

---

## Why this plan exists

Covia is becoming a public, open project. That changes who the repository is _for_: not just the people who already know how it works, but the developer who found us five minutes ago and is deciding whether it's worth their afternoon. This plan is about respecting that developer's time — and the time of the contributors who want to help us improve.

We'd rather be honest about the rough edges than pretend they aren't there. Every gap below is also an opportunity to contribute, and many are well-scoped enough to be a great first PR. Look for the 🌱 marker.

## What "good DX" means to us

These are the principles we want every change measured against:

1. **Time to first success in minutes, not hours.** A newcomer should reach a running venue and a successful operation invocation from a single, obvious path.
2. **Reproducible builds.** A clean clone builds with one command, on a released dependency set, every time — no "you also need to build X from source first".
3. **One obvious path.** When there are five ways to do something, the docs pick one and lead with it. Alternatives come later.
4. **Honest about maturity.** Clear, coherent versioning. Stable and experimental surfaces are labelled as such.
5. **Welcoming by default.** Contributing should be low-friction: clear guidelines, fast CI feedback, templates that help rather than gatekeep.
6. **Self-hostable with confidence.** Operators get health checks, sensible logs, and the observability they need to run a venue in production.
7. **LLM friendly.** A user with a coding assistant such as Codex or Claude Code should be able to harness the full power of Covia directly.

## Where we are today

An honest snapshot, so newcomers know what to expect and contributors know where to aim.

| Area | State | Notes |
|------|-------|-------|
| Core engine & adapters | 💪 Solid | Clean `AAdapter` abstraction, ~20 adapters, lattice-backed state |
| Test suite (engine) | 💪 Solid | ~1,300 tests, fast in-JVM parallel run |
| REST API reference docs | 💪 Solid | Comprehensive, hand-written, examples throughout |
| TypeScript SDK | 💪 Solid | Published to npm, typed, tested |
| Python SDK | 🔨 Good (alpha) | Published to PyPI, async mirror, well documented |
| README / first impression | ✅ Done | Rewritten for developers: quickstart, badges, architecture, SDK examples |
| Onboarding / quickstart (docs) | ✅ Done | README quickstart and the docs "Getting Started" page both go zero-to-first-operation |
| Published artifacts up to date | 🌱 Needs work | The GitHub `latest` release is `0.0.1` (Jan 2026); `develop` is `0.0.2-SNAPSHOT`. The README now routes JAR downloads to `latest-snapshot` as an interim fix; a real, current release is still owed |
| Build reproducibility | ✅ Done | Depends on released Convex 0.8.5 from Maven Central; a clean clone builds in one command |
| CI quality gate | ✅ Done | `test.yml` runs the full reactor (with tests) on every PR and push to `develop`/`master`; its first run caught three latent flaky tests |
| Client/auth test coverage | 🔨 In progress | `VenueHTTP` has contract tests against a real venue; dedicated auth-strategy tests remain |
| Community scaffolding | 🔨 In progress | `CONTRIBUTING`, `SECURITY`, `CHANGELOG`, and issue/PR templates in place; a governance note remains |
| Operability (metrics, health, rate limits) | 🌱 Planned | Tracked below and in `AGENTS.md` |

**Legend:** 💪 solid · 🔨 in progress · 🌱 good area to contribute · ✅ done

---

## The roadmap

Three milestones, roughly in order of leverage. Each item has a checkbox so we can track it, and a difficulty hint. Items marked 🌱 are good entry points for new contributors. This isn't a contract — it's where we think the highest-value work is.

### Milestone 1 — The Front Door

_Goal: a developer who has never seen Covia can understand it, run it, and invoke their first operation in under ten minutes._

- [x] **Rewrite the repository `README.md` for developers.** The front page now leads with a copy-paste Quickstart (call a live venue, invoke an operation, run your own via Docker/JAR), badges, an architecture diagram, and links into the docs.
- [x] **Provide a true five-minute quickstart in the docs.** The docs' "Getting Started" page now mirrors the README quickstart: curl a live venue, invoke an operation from TypeScript or Python, run your own venue — zero to first operation on one page.
- [ ] 🌱 **Pick one frictionless install and document it end-to-end.** The README now documents a `docker run` one-liner against `ghcr.io/covia-ai/covia:latest`, and the image has its own publish workflow; the JAR download points at `latest-snapshot`, so both paths hand newcomers a current build. What remains is choosing the lead path and documenting it end-to-end. (A thin `covia` CLI or a `curl | sh` installer is a stretch goal — see _Open questions_.)
- [x] **Fill in or hide the documentation stubs.** The Venues and Grid overviews and the A2A adapter page are now real content; no core-concept page reads as "coming soon" any more.
- [ ] 🌱 **Add a `troubleshooting` / debugging guide.** "My job failed — how do I inspect it?", "How do I read a venue's logs?", common setup pitfalls.

### Milestone 2 — Trust the Build

_Goal: every clone builds reproducibly, every PR is validated automatically, and the version story is coherent._

- [x] **Add a CI quality gate.** `.github/workflows/test.yml` runs `mvn clean install` (full reactor, with tests) on every pull request and on pushes to `develop`/`master`. Running and green; its first run surfaced three latent flaky tests (now fixed) — exactly the job it's there to do.
- [ ] 🌱 **Make the gate a required check and fix the build badge.** Branch protection should require the `Test` workflow for merges to `develop`/`master`, and the README "build" badge should point at it — it currently points at `snapshot-release.yml`, which skips tests, so green only means "it compiled".
- [x] **Make the build reproducible.** Covia now depends on released **Convex 0.8.5** from Maven Central — a clean clone builds with `mvn clean install`, no Convex source build. The Convex-from-source steps are gone from all CI workflows (saving ~2 minutes per run). Tracking unreleased Convex capabilities is now a deliberate, temporary act: build Convex locally and point `convex.version` at its snapshot, restoring the release pin before merging. See [Convex ↔ Covia dependency](#a-note-on-the-convex-dependency).
- [x] **Add a `CHANGELOG.md`** — in Keep a Changelog format. Keep it current per release, and make the release-notes link point at it for real.
- [ ] **Coherent versioning across the product — and ship a current artifact.** The versioning story is now agreed (see _Resolved_ under _Open questions_): independent SemVer per artifact, with the platform version naming the product generation. What remains is shipping it — cut **platform `0.1.0`** (the GitHub `latest` release is still `0.0.1` from 23 Jan 2026; the README's JAR download points at `latest-snapshot` as an interim fix), publish the Python SDK to PyPI for real, and add a compatibility matrix to the docs. When the release lands, also flip the quickstart's example venues from the dev tier to the stable tier — today only the dev venues carry the operations the docs invoke.
- [x] **Decouple the public Docker image from deployment.** `publish-docker.yml` is now the single source of `ghcr.io/covia-ai/covia` tags (`:latest` + `:<sha>` on every `develop` push); the Azure/EC2 deploy workflows just pull the published image after a successful publish.
- [x] **Reconcile documentation drift.** `BUILD.md` now lists `covia-core`, uses version-agnostic JAR names, and documents the released-Convex dependency (with the snapshot-override escape hatch); `deploy/README.md`'s truncated Caddy command and leaked local path are fixed (JARs now download from GitHub releases). JDK facts are stated consistently (build target 21; published image runs 25) — picking a single baseline remains an _Open question_.
- [ ] **Test the client-side auth strategies.** `VenueHTTP` now has contract tests against a real venue (`VenueHTTPTest`); the remaining gap is dedicated coverage for the auth strategies (`NoAuth`, `BearerAuth`, `KeyPairAuth`, `LocalAuth`) — signing round-trips and failure paths against a real `VenueServer`.
- [x] **Add `Dependabot` and dependency/code scanning.** Dependabot watches Maven and GitHub Actions weekly (Convex excluded — managed manually); CodeQL analyses `develop` pushes and runs weekly.
- [ ] **Consolidate the SDK story.** We have more than one Python client in the workspace; let's make the supported SDKs obvious and deprecate or redirect the rest. Give the Java client library (`covia-core`) a README and a published artifact so a third party can actually depend on it — and when it's published standalone, license it **Apache-2.0** like the other SDK libraries (see _Resolved_ under _Open questions_). Urgent sub-item: the Python SDK's `pip install covia` currently fails — the package exists only on TestPyPI; publish it to the real index.

### Milestone 3 — Confident Self-Hosting & Ecosystem

_Goal: an operator can run a venue in production, and the surrounding ecosystem (examples, templates) helps developers go further._

> The operability items below are surfaced here for the public roadmap, but `AGENTS.md` (P1/P2) is their engineering source of truth — track status there to avoid two checklists drifting.

- [ ] **Health & readiness endpoints** (`/health`, `/ready`) so orchestrators and load balancers can probe a venue meaningfully.
- [ ] **Structured (JSON) logging and request-ID propagation** for production observability. (Tracked in `AGENTS.md` P2.)
- [ ] **Rate limiting** — per-user and per-operation. (Tracked in `AGENTS.md` P1.)
- [ ] **Metrics export** — Prometheus-compatible counters for operations, jobs, adapters, and storage. (Tracked in `AGENTS.md` P2.)
- [ ] 🌱 **A runnable `examples/` collection.** Hello-world per SDK, plus the AP-invoice demo as real, clonable code rather than only a tooling skill. Working examples are some of the best documentation we can offer — and because they directly serve Milestone 1's "first success", consider pulling a single hello-world example forward rather than waiting for the full collection. The `ap-demo` skill already contains content to lift from.
- [ ] **A hardening checklist for operators** — UCAN capabilities, secret management, SSRF protection, CORS — consolidated into one practical page.

---

## Community & governance scaffolding

A public open project needs the files that tell people how to participate. None of these are large; together they signal that contributions are welcome and taken seriously.

- [x] **`SECURITY.md`** — private disclosure path, response expectations, supported versions, scope, and a note on the federation trust model.
- [ ] **Wire up private vulnerability reporting.** *Private vulnerability reporting* is enabled in the repo's Security settings; what remains is confirming `security@covia.ai` is a monitored inbox.
- [x] **`CONTRIBUTING.md`** — how to build, test, branch, and submit a change; conventions defer to `AGENTS.md`. Includes a short expectation of professional, good-faith behaviour in project spaces — a deliberate decision *not* to adopt a formal `CODE_OF_CONDUCT.md`, which tends to invite unproductive argument; we'd rather build than legislate behaviour.
- [x] **Issue & PR templates** (`.github/`) — bug-report and feature-request forms (with private-reporting and Discussions redirects) and a PR checklist matching `CONTRIBUTING.md`.
- [ ] **A short `ROADMAP` / governance note** — who maintains what, and how decisions get made.

## The open-core boundary

"Open core" means a deliberate line between what's open and freely self-hostable and what (if anything) is offered commercially. We owe contributors and adopters a clear, public statement of where that line sits — so that nobody is surprised, and so contributions land on the right side of it. Until we've written that down, treat everything in this repository as the open core.

Related: the platform is licensed under the **Eclipse Public License 2.0** (inherited from our Convex lineage), confirmed as the deliberate choice for the open core. The SDK libraries are **Apache-2.0** so that client applications can embed them without licence friction — see _Resolved_ under _Open questions_.

## A note on the Convex dependency

Covia is built on the [Convex](https://github.com/Convex-Dev/convex) lattice platform and tends to track its latest capabilities. Covia depends on **released Convex artifacts from Maven Central** (currently 0.8.5), so a clean clone always builds. When development genuinely needs an unreleased Convex capability, the coupling is deliberate and temporary: build Convex from source, point `convex.version` at its snapshot locally, and restore the release pin (bumping to the next Convex release) before the work merges.

---

## How to get involved

- 🌱 **Looking for a first contribution?** Anything marked 🌱 above is a good place to start. Comment on (or open) an issue so we can help you scope it.
- 💬 **Questions or ideas?** [GitHub Discussions](https://github.com/orgs/covia-ai/discussions) and [Discord](https://discord.gg/fywdrKd8QT).
- 📚 **Docs:** [docs.covia.ai](https://docs.covia.ai)

We review this plan as the project evolves. If you tackle an item, tick it off in your PR and add a line to the changelog — and thank you. We're building this in the open because we think the result is better when it's shared.

## Open questions we'd love input on

These are genuine forks in the road where community input would help:

- **Distribution:** is a dedicated `covia` CLI worth building, or do we lead with Docker and the SDKs?

### Resolved

- **License** _(resolved Jun 2026)_: the platform (this repository) stays **EPL-2.0**; the SDK libraries (TypeScript and Python) are **Apache-2.0**, so client code can embed them without licence friction. If `covia-core` is published as a standalone client artifact, it should follow the SDK side of that line — tracked under _Consolidate the SDK story_.
- **Versioning** _(resolved Jun 2026)_: independent SemVer per artifact; the **platform version names the product generation** (next: `0.1.0`), and the docs carry a compatibility matrix mapping SDK versions to the platform versions they support.
- **Java baseline** _(resolved Jun 2026)_: source targets **Java 21** (so client libraries stay broadly consumable); published container images run the **current Java LTS** (25 today).
