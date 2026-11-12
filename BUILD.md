# Build Guide

Build, test, promote and release Covia. See [AGENTS.md](AGENTS.md) for the
module layout and coding conventions, and [deploy/](deploy/README.md) for
host setup and store backups.

## Prerequisites

- JDK 21+; CI and releases use JDK 25, and source targets Java 21.
  **Never test on JDK 25 before 25.0.3:** JDK-8370887 delays timeouts.
  CI and release builds reject affected versions with `-Dcovia.jdk.check=ERROR`.
- Maven 3.7+ and Git. Maven Central publication requires Maven 3.9.
- Dependencies come from Maven Central; versions are pinned in the root POM.

The optional CPython bridge needs Java 22+ for its FFM implementation. Java 21
builds retain the facade, report Python unavailable and skip native tests.
Release builds include the Python adapter's FFM backend; the standard venue
depends on neither Python module.

## Building the Project

Run from the repository root:

```bash
mvn clean install                         # build all modules and run default tests
mvn clean install -DskipTests              # build without tests
mvn clean install -pl venue -am            # venue and its prerequisites
mvn clean install -pl workbench -am        # workbench and its prerequisites
mvn compile                               # compile only
java -jar venue/target/covia.jar [config]   # optional JSON config
```

`install` assembles `covia.jar`; `package` and `verify` do not. The entry point
is `covia.venue.MainVenue`. Omit the config for defaults, or pass `local-dev.json`
for ephemeral venues on ports 8080 and 8081. Personal configs belong in `dev/`.

In worktrees, include `-am` and avoid overwriting sibling SNAPSHOTs in `~/.m2`:

```bash
mvn -pl venue -am install -DskipTests -Dmaven.install.skip=true
```

### Build Artifacts

`<version>` is the root POM version.

| Output | Purpose |
|--------|---------|
| `venue/target/covia.jar` | Executable venue with dependencies |
| `venue/target/venue-<version>.jar` | Venue library |
| `venue/target/venue-<version>-tests.jar` | Shared test fixtures for modules |
| `workbench/target/workbench-<version>.jar` | Shared Swing components and REPL demo |
| `<module>/target/<module>-<version>-module.jar` | Optional loadable venue module |

Workbench's venue and FlatLaf dependencies are optional: applications using only
its shared components do not inherit them; the REPL demo needs them at runtime.
See [ADAPTERS.md](venue/docs/ADAPTERS.md) for the module packaging contract.

## Tests

```bash
mvn test                                  # default suite
mvn test -pl venue                         # one module, after prerequisite build
mvn verify                                # also run failsafe *IT tests
mvn test -Dcovia.tests.durability=true      # crash/restart survival, forks JVMs
mvn test -Dcovia.tests.integration=true     # component and external-service tests
```

The two heavy groups are off by default, including when selecting a single test.
Integration tests cover components working together, background activity and
external services; small synchronous helper tests stay in the default suite.
Unreachable services cause a skip (`covia.test.Reachable`); failures after a
connection opens count normally. See [venue testing rules](venue/CLAUDE.md#testing).

Real speech-model tests have a separate opt-in. Normal tests, integration tests,
QA and release builds do not download speech models. To run the model checks
deliberately, after building prerequisites:

```bash
mvn test -pl covia-speech -Dtest=LocalSpeechRealModelTest -Dcovia.tests.speech.models=true
```

This downloads hundreds of MB of models and native libraries into
`covia-speech/target/speech-cache`, retained until cleaned. The `speech-models`
tests run serially against that shared cache; deterministic speech tests use
local fixtures and fake engines.

Never kill a running Maven test: it can orphan venue JVMs. Let it finish and
stop a stray venue only by its `jps` PID.

### Troubleshooting

- Check `java -version` and `mvn -version` against the prerequisites.
- Retry dependency resolution with `mvn clean install -U`.
- For build memory pressure, increase `MAVEN_OPTS` (for example, `-Xmx2g`).
- A local Convex install can replace released jars in `~/.m2`. If local results
  differ from CI, compare checksums with Maven Central or build with a fresh
  `-Dmaven.repo.local=<directory>`.

### Upstream checks

In a Convex checkout at the tag named by `convex.version`, run the cursor,
component and DLFS regressions (`mvnw.cmd` on Windows):

```sh
./mvnw test -pl convex-core,convex-dlfs -am '-Dtest=*CursorTest,*ComponentTest,*ApplicationTest,*HostTest,DLFS*Test' '-Dsurefire.failIfNoSpecifiedTests=false'
```

## Store Compatibility

### Upgrading from Convex 0.8.17

The disposable-store upgrade from Convex 0.8.17 passes. **Upgrading is one-way:**
the first DLFS write adds an optional modification-time slot (`POS_MTIME = 5`),
producing six-element nodes that 0.8.17 rejects (that reader accepts only
four/five-element nodes). Convex 0.8.18 makes this its contract (CHANGELOG and
DLFS_DESIGN "Upgrading Is One-Way", [Convex #688](https://github.com/Convex-Dev/convex/issues/688)):
back up a store before upgrading, and roll back only by restoring that backup.
It is a DLFS format change, not an Etch encoding change or a Covia
secret-record migration. Test with disposable stores or copies.

### Store compatibility rehearsal

Keep a baseline `covia.jar` built with Convex 0.8.17. Compile
`venue/src/test/java/covia/venue/SnapshotCompatibilityProbe.java` against that jar
once, then run the resulting class with the baseline, experimental, and baseline
jars in that order. Pass the same **disposable** Etch path and marker pairs
`- before`, `before snapshot`, and `snapshot rollback`. A fourth run of the
experimental jar with `snapshot reopened` checks the return path after the
expected rollback failure (which does not write the rollback marker). Each run
checks workspace data, encrypted secrets, DLFS content, agent records and
persisted jobs, writes a fresh marker, and flushes before closing. The probe uses
a fixed public test seed and must never open a real venue store. No schema rewrite
is intended by this migration; the rehearsal verifies actual cross-version reads.

The repeatable API regression is `VenueStoreMigrationIT`. It starts from each
checked-in store written by a published release jar, one per release line,
upgrades a copy, exercises authenticated operations and reopens it twice. It is
a failsafe integration test, so `mvn verify` and CI run it, and it needs no old
jar or network download:

```sh
mvn verify -pl venue -am '-Dit.test=VenueStoreMigrationIT' '-Dtest=none' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Add `-Dcovia.migration.output=/absolute/path/to/directory` to retain each
upgraded store, report and a loopback launch config for manual API testing.
See the [fixture guide](venue/src/test/resources/migrations/README.md) for
contents, provenance, candidate-jar checks and generation instructions.

## Releases

### Development and QA

| Branch/event | Validation | Publication and deployment |
|--------------|------------|----------------------------|
| `develop` pushes and PRs | Default tests, Java 21 fallback | None; direct pushes allowed |
| `qa` pushes | Default, integration and durability tests, Java 21 fallback | `latest-snapshot`, Docker `:latest`, dev venues 3 and 4 |
| PRs into `master` | Same as QA | None |
| `master` pushes | Same as QA | Docker `:stable` (plus version tags for non-SNAPSHOT builds), stable venues test/1/2 |
| Release tags | Default, integration and durability tests | Versioned GitHub release and `latest` |

QA holds selected development commits for testing, prereleases and release
candidates. Fixes go through develop; QA has no independent commits. Promote
without force, publishing develop first:

```bash
git push origin develop
git push origin develop:qa
```

QA checks that its commit belongs to published develop. Accept a candidate after
tests, both dev-venue deploys and relevant manual/API checks pass. Review skipped
external-service tests and record the accepted SHA and publishing run. The release
operator verifies acceptance; it is not an automated master merge prerequisite.

`workflow_run` reads definitions from default branch develop. Land workflow
changes there before promoting QA. If QA already names the candidate, rerun its
push-triggered Test workflow. Manual Test dispatches validate only; publishers
accept successful push runs and reject superseded candidates.

### Deployment identity and redeploys

Publishers reuse the Test run's jars; the snapshot tag names the tested SHA.
Each Docker publishing attempt saves `deployment-<attempt>` for 90 days, recording
the source `sha`, `branch`, image digest reference and `test_run_id`. Deploys use
that digest; EC2/Azure also use the host script from the recorded SHA.

For a manual deploy, run the host's Actions workflow from `develop` with
`publish_run_id` set to a successful *Publish Docker image* run: QA for EC2/Azure,
master for GCP. Its latest attempt must have succeeded and its record must still
exist. An earlier run selects an older image; restoring incompatible stores also
requires their matching backup and keys (see [deployment guide](deploy/README.md)).

### Repository setup

Configure GitHub separately from the workflows:

- Keep develop open to direct iteration.
- Disallow QA force pushes and deletion; allow fast-forward promotion.
- Require `build-and-test` and `java21-fallback` for master release merges.

Remaining rollout from the 2026-11-12 audit:

- [ ] Apply the QA safeguards and add the Java 21 check on master. QA was
  unprotected; master required only `build-and-test`, with admin bypass.
- [ ] Publish the workflow changes to develop and verify a full QA promotion:
  Test → snapshot/Docker publication → both dev-venue deployments.

### Stable Releases

Start from an accepted QA candidate, validate a release PR into `master`,
then tag the merge commit:

1. **Branch from the accepted QA candidate** with everything the release should contain:
   ```bash
   git fetch origin
   git checkout -b release/1.0.0 origin/qa
   ```
   Confirm `origin/qa` is still the commit accepted above before branching.

2. **Update the Maven version** in all pom.xml files and check no `-SNAPSHOT`
   remains (the Release workflow refuses one, including a snapshot Convex):
   ```bash
   mvn versions:set -DnewVersion=1.0.0
   mvn versions:commit
   grep -R -n --include='pom.xml' -- '-SNAPSHOT' .
   ```

3. **Complete the release notes**: rename `## [Unreleased]` in `CHANGELOG.md`
   to `## [1.0.0] - <date>` and add a fresh empty `[Unreleased]` above it.

4. **Commit and open a pull request** into `master`:
   ```bash
   git add -A
   git commit -m "Release 1.0.0"
   git push -u origin release/1.0.0
   gh pr create --base master --title "Release 1.0.0"
   ```
   Merge once `build-and-test` and `java21-fallback` are green. The PR into
   master reruns both heavy groups against the proposed merge.

5. **Tag the merge commit** (the tag must match the Maven version and be
   reachable from `origin/master`):
   ```bash
   git fetch origin
   git tag 1.0.0 origin/master
   git push origin 1.0.0
   ```

6. **GitHub Actions will automatically**:
   - Build the project
   - Create a versioned release (e.g., `1.0.0`)
   - Update the `latest` release to point to this version

7. **Prepare for next development cycle** (on develop). Develop takes the
   next minor version's snapshot; a fix to a released line is a patch release
   from `master`:
   ```bash
   git checkout develop
   git merge origin/master
   mvn versions:set -DnewVersion=1.1.0-SNAPSHOT
   mvn versions:commit
   git add -A
   git commit -m "Prepare for next development iteration"
   git push origin develop
   ```

8. **Add the release line's migration fixture**, a store written by the
   published `covia.jar`, as the
   [fixture guide](venue/src/test/resources/migrations/README.md#provenance-and-regeneration)
   describes.

### Publishing to Maven Central

The adapter/module implementation contract is documented in
[`venue/docs/ADAPTERS.md`](venue/docs/ADAPTERS.md); this section covers release
packaging and publication.

The reactor modules are published to Maven Central under the `ai.covia`
groupId. `ai.covia:covia-core`, `ai.covia:covia-python`, `ai.covia:venue`, and
`ai.covia:workbench` are ordinary library artifacts (along with the
`ai.covia:covia` parent POM). The venue also publishes its test jar
(`venue-<version>-tests.jar`), which module tests take as a test-scoped
dependency for shared test support such as `TestTemp`. The operator-facing
`covia-python-adapter`, `covia-sql`, `covia-telegram`, `covia-discord`, `covia-whatsapp`, `covia-slack`,
`covia-sonnylabs`, `covia-documents`, `covia-speech` and
`covia-claude-code` artifacts are loadable venue modules rather than dependencies of
the standard venue. Their slim jars are published so an embedding host can
depend on a module through Maven; the shaded `-module.jar`s are GitHub Releases
artifacts only and are never deployed to Central (the shade plugin writes them
without attaching them, and leaves the slim jar's POM intact so the published
POM carries the module's real dependencies). GitHub Releases remain the
canonical operator download, pairing each module jar with a `sha256sum -c`
compatible checksum file. The executable `covia.jar` is an unattached
assembly and is **not** published to Central.

Publishing uses the [Sonatype Central Publishing plugin](https://central.sonatype.org/publish/publish-portal-maven/)
and mirrors the Convex setup: pom config lives in the root `pom.xml`
(`distributionManagement`, `central-publishing-maven-plugin`, and a `release`
profile that attaches sources and javadoc jars and GPG-signs every artifact;
other builds make neither jar). The deploy itself is
a **local command**, run only after the GitHub Release is confirmed live —
Maven Central publishes are irreversible.

**One-time setup** (per machine / per operator):

1. **Namespace verification** — verify ownership of the `ai.covia` namespace in
   the [Central Portal](https://central.sonatype.com/) (a DNS TXT record on
   `covia.ai`, or GitHub-org verification). Done once for the whole org.
2. **GPG signing key** — releases are signed with the Covia release key:
   `Covia Releases <mike@covia.ai>`, ed25519, fingerprint
   `50211D99AC2D33BBCE2E2E967E88537C3387A49C` (published on
   keyserver.ubuntu.com and keys.openpgp.org). The fingerprint is pinned in
   the root pom's `release` profile, so no other key on the operator's ring
   can be picked up by accident. The gpg *executable* is machine-specific:
   point Maven at the GnuPG that holds the key via a settings.xml profile,
   e.g.
   ```xml
   <profile>
     <id>gpg</id>
     <activation><activeByDefault>true</activeByDefault></activation>
     <properties>
       <gpg.executable>C:\Program Files (x86)\GnuPG\bin\gpg.exe</gpg.executable>
     </properties>
   </profile>
   ```
   mark it `activeByDefault` (as above) so `mvn deploy -Prelease` finds it, or name it on the command line: `mvn deploy -P release,gpg`.
3. **Central token** — generate a user token in the Central Portal and add it to
   `~/.m2/settings.xml`:
   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>TOKEN_USERNAME</username>
         <password>TOKEN_PASSWORD</password>
       </server>
     </servers>
   </settings>
   ```

**Validate the pipeline first** with a snapshot (freely republishable):
```bash
mvn clean deploy    # -SNAPSHOT version → routes to the Central snapshot repo
```

**Publish a release** — only after the GitHub Release for the tag is live:
```bash
mvn clean deploy -Prelease
```
Run it with Maven 3.9 and without `-B`. Under Maven 3.10 the Central plugin
bundles Maven's `.locks/` and `maven-metadata-local.xml` files, which Central
rejects. In batch mode the gpg plugin passes `--pinentry-mode error`, so gpg
cannot ask for the key's passphrase.

`-Prelease` GPG-signs every artifact; the Central plugin bundles all reactor
modules (main + sources + javadoc + pom + signatures) and, with
`autoPublish=true`, publishes them to Maven Central. Then verify at
`https://central.sonatype.com/artifact/ai.covia/covia-core`.

### Release Artifacts

Snapshot and stable GitHub releases include `covia.jar`, each optional module's
`<module>-<version>-module.jar`, and a matching `.sha256` file for every JAR.
Verify beside the downloaded file with `sha256sum -c <jar>.sha256`.
The [module list](AGENTS.md#layout) identifies the optional adapters.

- [Latest stable](https://github.com/covia-ai/covia/releases/tag/latest)
- [Latest snapshot](https://github.com/covia-ai/covia/releases/tag/latest-snapshot)
- Specific version: `https://github.com/covia-ai/covia/releases/tag/<version>`
