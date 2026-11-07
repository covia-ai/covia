# Covia

Covia's runtime: venues host operations, invoking one creates a job run by an
adapter, and agents, assets and user state live in Convex lattices. The
normative specs are the COGs in covia-docs (`docs/protocol/cogs/`);
`venue/docs/` holds the implementation design of each subsystem.

## Layout

| Path | Contents |
|------|----------|
| `covia-core/` | Grid client library and shared types (`Asset`, `Job`, `Operation`, `Venue`, `Status`), constants in `covia.api` (`Fields`, `Abilities`), auth strategies, `VenueHTTP` |
| `venue/` | The venue runtime, built as `venue/target/covia.jar` (main class `covia.venue.MainVenue`); guide in `venue/CLAUDE.md`, design docs in `venue/docs/` |
| `covia-sql`, `covia-telegram`, `covia-discord`, `covia-whatsapp`, `covia-slack`, `covia-sonnylabs`, `covia-documents`, `covia-speech`, `covia-claude-code`, `covia-python-adapter` | Optional venue modules: shaded `-module.jar`s loaded through config, not in `covia.jar` |
| `covia-python/` | Java FFM bridge to embedded CPython |
| `workbench/` | Shared Swing components (`covia.gui.markdown`, `covia.gui.components`) and a REPL demo |
| `skills/` | Claude Code and Codex skills (see Skills) |
| `deploy/` | Operator guide, Caddyfile, Docker and cloud deployment |
| `dev/` | Gitignored personal configs and scratch files |

Dependency versions are in the root `pom.xml` (`convex.version` is 0.8.18).

## Build and run

- JDK 21+ (25+ recommended) and Maven 3.7+. Never test on JDK 25 before
  25.0.3: JDK-8370887 fires virtual-thread timeouts tens of seconds late, so
  jobs stall and tests fail. The build warns on one; `-Dcovia.jdk.check=ERROR`
  (CI and release) fails it.
- `mvn clean install` builds and tests everything (`-DskipTests` skips tests);
  `mvn test -pl venue` tests one module. `covia.jar` is assembled in
  `install`, not `package`.
- Heavy tests are opt-in: `-Dcovia.tests.durability=true` (forks JVMs) and
  `-Dcovia.tests.integration=true` (external services); see `BUILD.md`.
- Never kill a running `mvn test`: it orphans venue JVMs. Let it finish, and
  stop a stray venue JVM only by its `jps` PID.
- In a worktree, build with `-pl <module> -am`: sibling SNAPSHOTs may be
  missing from `~/.m2`, and installing them overwrites what other checkouts
  build against. Rebuild `covia.jar` there with
  `mvn -pl venue -am install -DskipTests -Dmaven.install.skip=true`.
- A test that fails locally but passes on CI may be running on unreleased
  Convex: a local Convex `mvn install` overwrites the released jars in
  `~/.m2`. Compare their sha1 with Maven Central; `-Dmaven.repo.local=<dir>`
  gives a CI-equivalent run.
- Run a venue with `java -jar venue/target/covia.jar [config]`.
  `local-dev.json` is two ephemeral venues (8080, 8081); a personal
  persistent config goes under `dev/` (venue-setup skill).

## Branches and deploys

`develop` is the working branch and `master` takes releases (`BUILD.md`). CI
deploys from both: a push to `develop` redeploys the dev venues (venue-3 on
EC2, venue-4 on Azure), and a push to `master` the stable GCP venues
(venue-test, venue-1, venue-2).

## Conventions

- **Upstream first:** before adding a fix or abstraction, check the
  dependency's API at the version in use and prefer it. When a capability is
  missing, ask upstream rather than building a competing workaround here.
- **Packages:** `covia.<module>.<feature>`.
- **Constants:** fixed strings (field names, abilities, statuses) are shared
  constants in `covia.api.Fields`, `covia.api.Abilities` and
  `covia.grid.Status`, interned in their declaration. Never inline
  `Strings.intern` or `Strings.create` for a fixed string in logic;
  `Strings.create` is for dynamic strings.
- **Data:** persistent values use the Convex `ACell` types (`AMap`,
  `AVector`, `Index`).
- **Jobs:** every job operation goes through `engine.jobs()`.
- **API contracts are defined in docs:** every externally used API contract
  is defined in the relevant design doc or COG, in terms its callers can see,
  and the code implements it. Internal types and components never appear in a
  contract or decide its answers.
- **Message wording lives in constants:** a refusal or error message is built
  from a constant on the class that owns it (`VenueAuthenticator.Reason`,
  `Auth.KEY_ALREADY_BOUND`), and tests assert against the constant, never the
  wording, so a rewording breaks no test.
- **Expected failures are typed:** a failure a caller can cause or act on is a
  `CoviaException` subtype whose message says what failed and why, raised
  where that is known; library exceptions are wrapped where they are called.
- **Faults are reported by their scope:** anything else is a fault of the
  scope it escapes (an adapter running an operation, a tool call, the venue
  handling a request). That scope logs it once at ERROR and reports it as its
  own failure, naming whose code it was and the exception's type, never its
  text (`FaultException.within`); outer scopes pass the report on. An
  `IllegalArgumentException` escaping an adapter is that adapter's fault. See
  `venue/docs/ERRORS.md`.
- **Checks return reasons:** a check returns `null` when it passes and a
  human-readable reason when it does not (`CapabilityChecker.allows`,
  `UcanJwtValidator.Validation`, `VenueAuthenticator.Verdict`), so the reason
  reaches the caller unchanged.
- **Tests:** JUnit 6; fixtures and rules are in `venue/CLAUDE.md` (Testing).
  A passing build prints nothing but Maven's `[INFO]`, `[WARNING]` and
  `[ERROR]` lines: a test that provokes an ERROR log wraps it in
  `TestLogs.quiet` in an `@Isolated` class.
- **Lattice reads are consistent:** once a CAS commits, every thread reads the
  write. A flaky agent test is a timing assumption, a same-tick LWW tie or a
  lifecycle race; never add a read-after-write retry.
- **CHANGELOG:** one short headline per change, optionally with `(#issue)`.
  List what operators and API users see (operations, abilities, config keys,
  REST, MCP and A2A behaviour, modules), never Java API changes.

### Adding a New Adapter

`venue/docs/ADAPTERS.md` is the canonical contract: `AAdapter`, configuration,
catalog publication, `AdapterState`, capabilities, lifecycle, module packaging
and required tests. Operation metadata is covered there and in
`venue/docs/OPERATIONS.md`; the adapter tables are in `venue/CLAUDE.md`.

## Where things are documented

- `venue/CLAUDE.md`: venue architecture, adapter and API tables, testing.
- `venue/docs/`: one design doc per subsystem, among them `ADAPTERS.md`,
  `JOBS.md`, `UCAN.md`, `AUTH.md`, `PERSISTENCE.md`, `SKILLS.md`,
  `AGENT_LOOP.md`, `EMBEDDING.md`, `GRID_LATTICE_DESIGN.md` (lattice layout,
  defined in code by `covia.lattice.Covia`) and `CONFIG.md` (operator
  reference).
- `BUILD.md`: build details and the release flow. `DX_PLAN.md`: the public
  developer-experience roadmap. `deploy/README.md`: the operator guide.

## Skills

Skills live in `skills/` (tracked). Each `SKILL.md` describes itself, so no
list is kept here. Claude Code reads them through `.claude/skills` and Codex
through `.agents/skills`, gitignored junctions made once per checkout:

```bash
# Windows (from the covia root)
cmd /c "mklink /J .claude\skills skills"
cmd /c "mklink /J .agents\skills skills"

# macOS / Linux
ln -s ../skills .claude/skills
ln -s ../skills .agents/skills
```

Invoke one as `/<name>` in Claude Code or `$<name>` in Codex. `.mcp.json`
and `.claude/settings.json` are tracked; local overrides go in
`.claude/settings.local.json`.
