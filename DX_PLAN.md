# Developer Experience Plan

Roadmap for developers adopting Covia, building applications on it, embedding
venues and running their own infrastructure. The goal is a clear path from
installation to a working operation, with dependable SDKs and useful diagnostics.

## First operation

- [x] **Quickstart and API guides.** The README and [documentation](https://docs.covia.ai)
  cover calling a venue, invoking operations through the SDKs and running a venue.
- [x] **Core concept documentation.** Venues, Grid and A2A guides explain the main
  surfaces without placeholder pages.
- [ ] 🌱 **One recommended installation path.** Choose the lead Docker or JAR
  path and document installation through first use, with stable and experimental
  downloads clearly labelled. A CLI remains an option if it simplifies that path.
- [ ] 🌱 **Troubleshooting guide.** Cover failed jobs, authentication, connectivity,
  setup errors and finding useful venue logs.
- [ ] 🌱 **Runnable examples.** Provide a small working application per SDK and a
  complete AP-invoice example, with commands and expected results.
- [ ] **Coding-assistant workflows.** Make it easy to discover and use Covia's
  skills to set up a venue, invoke operations and build an integration.

## SDKs and distribution

- [x] **Published clients and runtime downloads.** Java, TypeScript and Python
  clients are available, alongside venue JARs, optional modules and Docker images.
- [x] **Build from released dependencies.** A source build resolves the pinned
  Convex release from Maven Central; no separate Convex checkout is needed.
- [x] **Release notes and checksums.** Releases include a changelog and checksums
  for operator JAR downloads.
- [ ] **One clear SDK story.** Identify supported clients, retire obsolete
  guidance, add a focused Java client README and maintain a compatibility matrix.
  SDKs have independent versions; Python follows the published `covia-core` line,
  and the platform version identifies the product generation.
- [ ] **Clear licensing and product boundaries.** The platform is EPL-2.0;
  TypeScript and Python SDKs are Apache-2.0. Apply the agreed Apache-2.0 licence to
  the standalone Java SDK's publication metadata, and explain which features are
  freely self-hostable and which, if any, are commercial.

## Operating and embedding venues

- [x] **Structured diagnostics.** JSON logging, request IDs, audit logs and access
  logs help operators trace requests and failures.
- [ ] **Health and readiness endpoints.** Let hosting platforms distinguish a
  running process from a venue ready to serve work.
- [ ] **Metrics.** Expose Prometheus-compatible operation, job, adapter and
  storage metrics.
- [ ] **Per-operation limits.** Extend the existing per-caller request and
  concurrent-job limits to individual operations.
- [ ] **Complete the production profile.** Document and enforce appropriate key
  handling, security logging, outbound access, WebDAV ([#673](https://github.com/covia-ai/covia/issues/673))
  and agent limits ([#667](https://github.com/covia-ai/covia/issues/667)); see
  [venue configuration](venue/docs/CONFIG.md).

Suggestions: [GitHub Discussions](https://github.com/orgs/covia-ai/discussions)
or [Discord](https://discord.gg/fywdrKd8QT). Items marked 🌱 are approachable
contributions; see [CONTRIBUTING.md](CONTRIBUTING.md).
