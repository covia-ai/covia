# Developer Experience Plan

This is our shared, public roadmap for making Covia a joy to **adopt, build on, self-host, and contribute to**. It's deliberately open: if something here resonates, pick it up; if you think we've got a priority wrong, [open a discussion](https://github.com/orgs/covia-ai/discussions) or say so on [Discord](https://discord.gg/fywdrKd8QT). Nothing in this document is set in stone — it's a conversation.

> **TL;DR for the impatient:** the Covia *engine* is in good shape — clean adapter architecture, a real lattice foundation, multi-protocol surface (REST / MCP / A2A / DID), ~1,100 passing tests, and a published TypeScript SDK. What we're focused on now is the *experience around* that engine: getting a newcomer from `git clone` to "I ran my first federated operation" in minutes, making the build reproducible, putting a quality gate in front of every PR, and adding the community scaffolding that an open project deserves.

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

## Where we are today

An honest snapshot, so newcomers know what to expect and contributors know where to aim.

| Area | State | Notes |
|------|-------|-------|
| Core engine & adapters | 💪 Solid | Clean `AAdapter` abstraction, ~20 adapters, lattice-backed state |
| Test suite (engine) | 💪 Solid | ~1,100 tests, fast in-JVM parallel run |
| REST API reference docs | 💪 Solid | Comprehensive, hand-written, examples throughout |
| TypeScript SDK | 💪 Solid | Published to npm, typed, tested |
| Python SDK | 🔨 Good (alpha) | Published to PyPI, async mirror, well documented |
| README / first impression | 🌱 Needs work | Great vision copy, but no quickstart or runnable code for developers |
| Onboarding / quickstart | 🌱 Needs work | No single zero-to-running walkthrough yet |
| Build reproducibility | 🌱 Needs work | Depends on an unreleased Convex snapshot; versions skew across artifacts |
| CI quality gate | 🌱 Needs work | Workflows publish & deploy, but no build/test gate on PRs |
| Client/auth test coverage | 🌱 Needs work | `VenueHTTP` and the auth strategies are untested |
| Community scaffolding | 🌱 Needs work | No `CONTRIBUTING`, `SECURITY`, `CHANGELOG`, or templates yet |
| Operability (metrics, health, rate limits) | 🌱 Planned | Tracked below and in `AGENTS.md` |

**Legend:** 💪 solid · 🔨 in progress · 🌱 good area to contribute · ✅ done

---

## The roadmap

Three milestones, roughly in order of leverage. Each item has a checkbox so we can track it, and a difficulty hint. Items marked 🌱 are good entry points for new contributors. This isn't a contract — it's where we think the highest-value work is.

### Milestone 1 — The Front Door

_Goal: a developer who has never seen Covia can understand it, run it, and invoke their first operation in under ten minutes._

- [ ] 🌱 **Rewrite the repository `README.md` for developers.** Keep a crisp value proposition, then lead with a **Quickstart** (copy-paste: run a venue, open Swagger, invoke an operation via an SDK), badges (CI, latest release, license, Discord), an architecture diagram, and links into the docs. The aspirational marketing copy lives best on the website; the repo's front page should get someone *running*.
- [ ] 🌱 **Provide a true five-minute quickstart in the docs.** One language, one path, zero to first operation. Today's "Quick Start" mostly links elsewhere.
- [ ] 🌱 **Pick one frictionless install and document it end-to-end.** At minimum a documented `docker run` one-liner against a published image. (A thin `covia` CLI or a `curl | sh` installer is a stretch goal — see _Open questions_.)
- [ ] 🌱 **Fill in or hide the documentation stubs.** A few core-concept pages (the Venues and Grid overviews, the A2A adapter page) currently read as "coming soon". Stubs on central concepts undermine confidence — let's finish them or remove them from the nav until they're ready.
- [ ] **Align the README's promises with reality.** For example, we reference a CLI that doesn't exist yet — either build a minimal one or adjust the copy. Small honesty wins matter for trust.
- [ ] 🌱 **Add a `troubleshooting` / debugging guide.** "My job failed — how do I inspect it?", "How do I read a venue's logs?", common setup pitfalls.

### Milestone 2 — Trust the Build

_Goal: every clone builds reproducibly, every PR is validated automatically, and the version story is coherent._

- [ ] **Make the build reproducible.** We currently build against an unreleased Convex snapshot, so a clean clone can't build without first building Convex from source. Pin to a released Convex version (or, if we genuinely need to track Convex `develop`, document that coupling explicitly and automate it). See [Convex ↔ Covia dependency](#a-note-on-the-convex-dependency).
- [ ] **Add a CI quality gate.** A workflow that runs `mvn clean install` (with tests) on every pull request and on `develop`, gating merges. This is the single biggest credibility win available to us right now.
- [ ] 🌱 **Add a `CHANGELOG.md`** and keep it current per release. (Our release notes already link to one — let's make the link real.)
- [ ] **Coherent versioning across the product.** The platform, the TypeScript SDK, and the Python SDK currently sit at very different version numbers, which makes "what's stable?" hard to answer. Agree a versioning story and cut a real `0.1.0` of the platform.
- [ ] 🌱 **Reconcile documentation drift.** Build guide, `AGENTS.md`, and the POMs disagree on dependency versions and JAR names; the JDK baseline is stated inconsistently; `DEPLOY.md` has a truncated command and a leaked local path. A sweep to make the docs match the build.
- [ ] **Test the client and auth layers.** `VenueHTTP` and the auth strategies (`NoAuth`, `BearerAuth`, `KeyPairAuth`, `LocalAuth`) are the layer every SDK and integration depends on, and they're currently untested. Cover them against a real `VenueServer`.
- [ ] 🌱 **Add `Dependabot` and dependency/code scanning (e.g. CodeQL).** Standard hygiene for a public repo; low effort, high signal.
- [ ] **Consolidate the SDK story.** We have more than one Python client in the workspace; let's make the supported SDKs obvious and deprecate or redirect the rest. Give the Java client library (`covia-core`) a README and a published artifact so a third party can actually depend on it.

### Milestone 3 — Confident Self-Hosting & Ecosystem

_Goal: an operator can run a venue in production, and the surrounding ecosystem (examples, templates) helps developers go further._

- [ ] **Health & readiness endpoints** (`/health`, `/ready`) so orchestrators and load balancers can probe a venue meaningfully.
- [ ] **Structured (JSON) logging and request-ID propagation** for production observability. (Tracked in `AGENTS.md` P2.)
- [ ] **Rate limiting** — per-user and per-operation. (Tracked in `AGENTS.md` P1.)
- [ ] **Metrics export** — Prometheus-compatible counters for operations, jobs, adapters, and storage. (Tracked in `AGENTS.md` P2.)
- [ ] 🌱 **A runnable `examples/` collection.** Hello-world per SDK, plus the AP-invoice demo as real, clonable code rather than only a tooling skill. Working examples are some of the best documentation we can offer.
- [ ] **A hardening checklist for operators** — UCAN capabilities, secret management, SSRF protection, CORS — consolidated into one practical page.

---

## Community & governance scaffolding

A public open project needs the files that tell people how to participate. None of these are large; together they signal that contributions are welcome and taken seriously.

- [ ] 🌱 **`CONTRIBUTING.md`** — how to build, test, and submit a change; coding conventions; the PR flow.
- [ ] 🌱 **`CODE_OF_CONDUCT.md`** — a standard, welcoming baseline (e.g. Contributor Covenant).
- [ ] **`SECURITY.md`** — how to report a vulnerability privately, our trust model, and what's in/out of scope.
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
