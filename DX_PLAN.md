# Developer Experience Plan

This is our shared, public roadmap for making Covia a joy to **adopt, build on, self-host, and contribute to**. It's deliberately open: if something here resonates, pick it up; if you think we've got a priority wrong, [open a discussion](https://github.com/orgs/covia-ai/discussions) or say so on [Discord](https://discord.gg/fywdrKd8QT). Nothing in this document is set in stone — it's a conversation.

> **TL;DR for the impatient:** the Covia *engine* is in good shape — clean adapter architecture, a real lattice foundation, multi-protocol surface (REST / MCP / A2A / DID), ~1,300 passing tests gating every PR, and published TypeScript and Python SDKs. What we're focused on now is the *experience around* that engine: getting a newcomer from `git clone` to "I ran my first federated operation" in minutes, making the build reproducible, and shipping current, coherently-versioned artifacts.

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
| Onboarding / quickstart (docs) | 🌱 Needs work | README quickstart now exists; the *docs* "Quick Start" still mostly links elsewhere |
| Published artifacts up to date | 🌱 Needs work | The GitHub `latest` release is `0.0.1` (Jan 2026); `develop` is `0.0.2-SNAPSHOT`. A newcomer who downloads `latest` gets a months-old build that predates much of what the docs describe |
| Build reproducibility | 🌱 Needs work | Depends on an unreleased Convex snapshot; versions skew across artifacts |
| CI quality gate | ✅ Done | `test.yml` runs the full reactor (with tests) on every PR and push to `develop`/`master`; its first run caught three latent flaky tests |
| Client/auth test coverage | 🌱 Needs work | `VenueHTTP` and the auth strategies are untested |
| Community scaffolding | 🔨 In progress | `CONTRIBUTING`, `SECURITY`, and `CHANGELOG` in place; issue/PR templates and a governance note remain |
| Operability (metrics, health, rate limits) | 🌱 Planned | Tracked below and in `AGENTS.md` |

**Legend:** 💪 solid · 🔨 in progress · 🌱 good area to contribute · ✅ done

---

## The roadmap

Three milestones, roughly in order of leverage. Each item has a checkbox so we can track it, and a difficulty hint. Items marked 🌱 are good entry points for new contributors. This isn't a contract — it's where we think the highest-value work is.

### Milestone 1 — The Front Door

_Goal: a developer who has never seen Covia can understand it, run it, and invoke their first operation in under ten minutes._

- [x] **Rewrite the repository `README.md` for developers.** The front page now leads with a copy-paste Quickstart (call a live venue, invoke an operation, run your own via Docker/JAR), badges, an architecture diagram, and links into the docs.
- [ ] 🌱 **Provide a true five-minute quickstart in the docs.** One language, one path, zero to first operation. The README quickstart now does this; the docs' own "Quick Start" still mostly links elsewhere and should mirror it.
- [ ] 🌱 **Pick one frictionless install and document it end-to-end.** The README now documents a `docker run` one-liner against `ghcr.io/covia-ai/covia:latest`. Two supply-side gaps remain, both tracked in Milestone 2: the image needs its own publish workflow, and the JAR download points at a stale release. (A thin `covia` CLI or a `curl | sh` installer is a stretch goal — see _Open questions_.)
- [ ] 🌱 **Fill in or hide the documentation stubs.** A few core-concept pages (the Venues and Grid overviews, the A2A adapter page) currently read as "coming soon". Stubs on central concepts undermine confidence — let's finish them or remove them from the nav until they're ready.
- [ ] 🌱 **Add a `troubleshooting` / debugging guide.** "My job failed — how do I inspect it?", "How do I read a venue's logs?", common setup pitfalls.

### Milestone 2 — Trust the Build

_Goal: every clone builds reproducibly, every PR is validated automatically, and the version story is coherent._

- [x] **Add a CI quality gate.** `.github/workflows/test.yml` runs `mvn clean install` (full reactor, with tests) on every pull request and on pushes to `develop`/`master`, building the Convex dependency from source first. Running and green; its first run surfaced three latent flaky tests (now fixed) — exactly the job it's there to do.
- [ ] 🌱 **Make the gate a required check and fix the build badge.** Branch protection should require the `Test` workflow for merges to `develop`/`master`, and the README "build" badge should point at it — it currently points at `snapshot-release.yml`, which skips tests, so green only means "it compiled".
- [ ] **Make the build reproducible.** We currently build against an unreleased Convex snapshot, so a clean clone can't build without first building Convex from source. The end state is to pin a released Convex (or, if we genuinely need to track Convex `develop`, document that coupling explicitly and automate it). A cheaper near-term step: publish `convex 0.8.5-SNAPSHOT` to a snapshot repository (Sonatype OSS snapshots or GitHub Packages) so a clean clone *resolves* the dependency without building Convex from source — unblocking newcomers before a full Convex release lands. See [Convex ↔ Covia dependency](#a-note-on-the-convex-dependency).
- [x] **Add a `CHANGELOG.md`** — in Keep a Changelog format. Keep it current per release, and make the release-notes link point at it for real.
- [ ] **Coherent versioning across the product — and ship a current artifact.** The platform, the TypeScript SDK, and the Python SDK sit at very different version numbers, which makes "what's stable?" hard to answer. Concretely, the symptom is live today: the GitHub `latest` release is `0.0.1` (23 Jan 2026) while `develop` is `0.0.2-SNAPSHOT`, so the README's "download the latest release" path hands newcomers a months-old build that predates operations the quickstart invokes. Either point the JAR download at `latest-snapshot` (rebuilt on every `develop` push) or — better — agree a versioning story and cut a real, current `0.1.0` of the platform.
- [ ] **Decouple the public Docker image from deployment.** `ghcr.io/covia-ai/covia:latest` (the README's `docker run` one-liner) is currently built and pushed as a side-effect of *both* `deploy-azure.yml` and `deploy-ec2.yml` on every `develop` push — a double build racing to the same tag, and one that silently stops updating if the cloud deploys are ever disabled. Give the image its own publish workflow with a clear tagging scheme.
- [ ] 🌱 **Reconcile documentation drift.** Build guide, `AGENTS.md`, and the POMs disagree on dependency versions and JAR names; the JDK baseline is stated inconsistently; `deploy/README.md` has a truncated command and a leaked local path. A sweep to make the docs match the build.
- [ ] **Test the client and auth layers.** `VenueHTTP` and the auth strategies (`NoAuth`, `BearerAuth`, `KeyPairAuth`, `LocalAuth`) are the layer every SDK and integration depends on, and they're currently untested. Cover them against a real `VenueServer`.
- [ ] 🌱 **Add `Dependabot` and dependency/code scanning (e.g. CodeQL).** Standard hygiene for a public repo; low effort, high signal.
- [ ] **Consolidate the SDK story.** We have more than one Python client in the workspace; let's make the supported SDKs obvious and deprecate or redirect the rest. Give the Java client library (`covia-core`) a README and a published artifact so a third party can actually depend on it.

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
- [ ] **Wire up private vulnerability reporting.** `SECURITY.md` promises two channels that need switching on: enable *Private vulnerability reporting* in the repo's Security settings, and confirm `security@covia.ai` is a monitored inbox.
- [x] **`CONTRIBUTING.md`** — how to build (including the Convex-from-source step), test, branch, and submit a change; conventions defer to `AGENTS.md`. Includes a short expectation of professional, good-faith behaviour in project spaces — a deliberate decision *not* to adopt a formal `CODE_OF_CONDUCT.md`, which tends to invite unproductive argument; we'd rather build than legislate behaviour.
- [ ] 🌱 **Issue & PR templates** (`.github/`) — light structure that helps reporters give us what we need.
- [ ] **A short `ROADMAP` / governance note** — who maintains what, and how decisions get made.

## The open-core boundary

"Open core" means a deliberate line between what's open and freely self-hostable and what (if anything) is offered commercially. We owe contributors and adopters a clear, public statement of where that line sits — so that nobody is surprised, and so contributions land on the right side of it. Until we've written that down, treat everything in this repository as the open core.

Related: the project is currently licensed under the **Eclipse Public License 2.0** (inherited from our Convex lineage). That's a deliberate choice worth confirming for an open-core posture — see _Open questions_ below.

## A note on the Convex dependency

Covia is built on the [Convex](https://github.com/Convex-Dev/convex) lattice platform and tends to track its latest capabilities. That's a strength, but it currently means we depend on an unreleased Convex build, which hurts reproducibility for newcomers. The plan is to depend on released Convex artifacts wherever possible, and to make any unavoidable coupling to Convex `develop` explicit and automated rather than implicit.

---

## How to get involved

- 🌱 **Looking for a first contribution?** Anything marked 🌱 above is a good place to start. Comment on (or open) an issue so we can help you scope it.
- 💬 **Questions or ideas?** [GitHub Discussions](https://github.com/orgs/covia-ai/discussions) and [Discord](https://discord.gg/fywdrKd8QT).
- 📚 **Docs:** [docs.covia.ai](https://docs.covia.ai)

We review this plan as the project evolves. If you tackle an item, tick it off in your PR and add a line to the changelog — and thank you. We're building this in the open because we think the result is better when it's shared.

## Open questions we'd love input on

These are genuine forks in the road where community input would help:

- **Distribution:** is a dedicated `covia` CLI worth building, or do we lead with Docker and the SDKs?
- **License:** confirm EPL-2.0, or move the core to a more conventional permissive licence (e.g. Apache-2.0) for an open-core model?
- **Versioning:** independent SemVer per artifact, or a unified version line across platform and SDKs?
- **Java baseline:** settle on a single supported JDK for building and running.
