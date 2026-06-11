# Contributing to Covia

Thanks for your interest in Covia — open-source infrastructure for federated AI orchestration. Contributions of all kinds are welcome: code, tests, docs, examples, bug reports, and ideas. This guide gets you from a clean clone to a merged pull request.

> New here? The [`DX_PLAN.md`](DX_PLAN.md) roadmap marks good first contributions with a 🌱. Pick one, comment on (or open) an issue so we can help you scope it, and dive in.

## Conduct

We don't have a formal code of conduct. We expect **professional, good-faith behaviour in all project-related spaces** — keep discussion focused on the work and treat other contributors with basic respect. Maintainers may remove plainly disruptive content (spam, abuse) to keep the project usable, but we'd rather spend our time building than legislating behaviour.

## Ways to contribute

- **Report a bug** — open an issue with steps to reproduce, what you expected, and what happened.
- **Suggest a feature or improvement** — start a [discussion](https://github.com/orgs/covia-ai/discussions) or open an issue.
- **Pick up a 🌱 item** from `DX_PLAN.md` — these are scoped to be approachable.
- **Improve docs or examples** — often the highest-leverage contribution of all.
- **Report a security issue** — *privately*, please: see [`SECURITY.md`](SECURITY.md). Do not open a public issue for vulnerabilities.

## Prerequisites

- **Java 21+** (JDK)
- **Maven 3.7+** (enforced by `maven-enforcer-plugin`)
- **Git**
- For the documentation sites: **Node.js 18+** and **pnpm**

## Building

Covia currently builds against an **unreleased Convex snapshot** (`0.8.5-SNAPSHOT`), so a clean clone needs Convex built into your local Maven repo first. (Removing this step is on the roadmap — see *"Make the build reproducible"* in `DX_PLAN.md`.)

```bash
# 1. Build the Convex dependency from source (once, and after Convex changes)
git clone --depth 1 --branch develop https://github.com/Convex-Dev/convex.git
cd convex && mvn install -DskipTests && cd ..

# 2. Build Covia (all modules) and run the tests
cd covia
mvn clean install

# Run the venue server
java -jar venue/target/covia.jar
```

Useful variants:

```bash
mvn clean install -DskipTests   # build without running tests
mvn test -pl venue              # test a single module
mvn test -pl covia-core
```

The module layout, run configurations, and release flow are documented in [`BUILD.md`](BUILD.md) and [`AGENTS.md`](AGENTS.md).

## Running the tests

```bash
mvn test            # whole reactor
mvn test -pl venue  # one module
```

The venue suite is JUnit 6, runs in-JVM in parallel, and should stay fast and **deterministic** — no test should depend on timing, iteration counts, or wall-clock races. Every pull request runs `mvn clean install` (with tests) in CI; please make sure it's green locally first.

## Branch strategy

- **`develop`** — active development. Target your pull requests here.
- **`master`** — releases only.

See `BUILD.md` for the full release flow.

## Submitting a pull request

1. **Fork** the repository and create a topic branch off `develop`:
   `git checkout -b my-change develop`
2. **Make your change.** Keep it focused — one logical change per PR is easier to review than a grab-bag.
3. **Add or update tests.** Bug fixes should come with a regression test; new behaviour should be covered. Don't skip tests "for momentum".
4. **Run `mvn clean install`** and confirm it's green.
5. **Open the PR against `develop`.** Describe what changed and why; link any related issue. If you ticked off a `DX_PLAN.md` item, say so and add a `CHANGELOG.md` entry under *Unreleased*.
6. **CI must pass** and a maintainer will review. Address feedback by pushing follow-up commits.

By contributing, you agree that your contributions are your own work and are licensed under the project's [Eclipse Public License 2.0](LICENSE).

## Coding conventions

`AGENTS.md` is the source of truth for in-codebase conventions; the essentials:

- **British English** throughout (e.g. *decentralised*, *behaviour*, *colour*).
- **Package naming:** `covia.<module>.<feature>` (e.g. `covia.venue.api`, `covia.adapter`, `covia.grid.auth`).
- **Constants:** use `Strings.intern()` for field names and status strings and reuse existing constants (see `Fields.java`, `Status.java`) — avoid `Strings.create` for fixed strings.
- **Immutability:** use the Convex `ACell` hierarchy (`AMap`, `AVector`, `Index`) for persistent data.
- **Async:** return `CompletableFuture` from adapters; use virtual threads for IO-bound work.
- **Prefer editing** existing files over adding new ones; fix or extend an existing operation before introducing a new class or abstraction.
- **Commit messages:** describe what changed in plain terms — no internal step/phase labels in the subject line.

Adding an adapter or working in the venue module? See the *"Adding a New Adapter"* section of `AGENTS.md` and `venue/CLAUDE.md`.

## Working with a coding assistant

Covia aims to be **LLM-friendly** — a contributor using Claude Code, Codex, or similar should be able to harness the full project. Two things help:

- **`AGENTS.md`** at the repo root is written for coding assistants and humans alike — point your tool at it.
- **Skills** in `skills/` (`/venue-setup`, `/grid-test`, `/agent`, `/ap-demo`, …) automate common workflows. They're exposed to Claude Code via a `.claude/skills` junction you create once per checkout — see the *Skills* section of `AGENTS.md`.

## Getting help

- 💬 [GitHub Discussions](https://github.com/orgs/covia-ai/discussions)
- 🗨️ [Discord](https://discord.gg/fywdrKd8QT)
- 📚 [docs.covia.ai](https://docs.covia.ai)

Thank you for helping build Covia in the open.
