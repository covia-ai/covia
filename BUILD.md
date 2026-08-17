# Build Guide

This document describes how to build the Covia project using Maven.

## Prerequisites

- **Java 21+**: The project compiles for Java 21 (`maven.compiler.release=21`); the published Docker image runs on a Java 25 JRE. This is deliberate policy: source targets Java 21 so client libraries stay broadly consumable, while container images ship the current Java LTS
- **Maven 3.7+**: Minimum Maven version required (enforced by maven-enforcer-plugin)
- **Git**: For cloning the repository

All dependencies, including Convex 0.8.12, resolve from Maven Central. A clean Covia checkout therefore builds directly with Maven; no sibling Convex checkout or source build is required.

## Project Structure

Covia is a multi-module Maven project with the following structure:

```
covia/
├── pom.xml                 # Parent POM (aggregator)
├── covia-core/             # Grid client library and shared abstractions
│   ├── pom.xml            # Core module POM
│   └── src/               # Client, auth strategies, core grid types
├── covia-python/           # CPython FFM bridge (only runtime dep: Convex)
│   ├── pom.xml            # Java 21 facade + Java 22+ FFM build profile
│   └── src/               # Runtime, references, conversion, native bindings
├── covia-python-adapter/   # Optional shaded venue module (not in covia.jar)
│   ├── pom.xml            # Venue SPI provided; covia-python bundled
│   └── src/               # Configured Python operation adapter
├── venue/                  # Main application module
│   ├── pom.xml            # Venue module POM
│   └── src/
│       ├── main/java/     # Main source code
│       ├── main/resources/ # Resources and assets
│       └── test/java/     # Test source code
├── workbench/             # GUI workbench module
│   ├── pom.xml            # Workbench module POM
│   └── src/
│       ├── main/java/     # GUI source code
│       └── main/resources/ # GUI resources
├── covia-sql/             # Optional loadable SQL adapter module
│   ├── pom.xml            # Venue SPI provided; shaded module classifier
│   └── src/               # SQL operation adapter and tests
└── covia-telegram/        # Optional loadable Telegram bot module
    ├── pom.xml            # Venue SPI provided; Telegram client shaded
    └── src/               # Telegram bot adapter and tests (fake Bot API)
```

The standard venue does not depend on either Python module. A Java 21 reactor
build deliberately omits the optional CPython FFM implementation because the
stable Foreign Function & Memory API starts in Java 22; the public facade stays
loadable, reports Python unavailable, and native tests skip. CI keeps a Java 21
fallback job. Release builds use Java 25 and attach the independently loadable
Python adapter module with its FFM backend.

## Building the Project

### Clean Build

To perform a clean build of the entire project:

```bash
mvn clean install
```

This command will:
1. Clean all previous build artifacts
2. Compile all modules
3. Run tests
4. Package the modules
5. Install artifacts to local Maven repository

### Build Individual Modules

To build only the venue module:

```bash
mvn clean install -pl venue
```

To build only the workbench module:

```bash
mvn clean install -pl workbench
```

### Skip Tests

To build without running tests:

```bash
mvn clean install -DskipTests
```

### Compile Only

To compile without packaging:

```bash
mvn clean compile
```

## Build Artifacts

### Venue Module

The venue module produces several artifacts (`<version>` is the current Maven version, e.g. `0.0.2-SNAPSHOT`):

- **Standard JAR**: `venue/target/venue-<version>.jar`
- **Executable JAR**: `venue/target/covia.jar` (with dependencies)
- **Test JAR**: `venue/target/venue-<version>-tests.jar`

The executable JAR (`covia.jar`) is created using the maven-assembly-plugin and includes all dependencies. It can be run directly with:

```bash
java -jar venue/target/covia.jar
```

### Workbench Module

The workbench module produces:

- **Standard JAR**: `workbench/target/workbench-<version>.jar`


## Build Configuration

### Java Version

The project is configured to use Java 21:

```xml
<maven.compiler.release>21</maven.compiler.release>
<maven.compiler.source>21</maven.compiler.source>
<maven.compiler.target>21</maven.compiler.target>
```

### Maven Enforcer Plugin

The project enforces a minimum Maven version of 3.7:

```xml
<requireMavenVersion>
    <version>3.7</version>
</requireMavenVersion>
```

### Assembly Plugin Configuration

The venue module uses the maven-assembly-plugin to create an executable JAR with all dependencies:

- **Main Class**: `covia.venue.MainVenue`
- **Output Name**: `covia.jar`
- **Phase**: `install` (not attached to deployment)

## Running the Application

### From Source

After building, you can run the venue application:

```bash
# Run from the venue directory
cd venue
java -jar target/covia.jar

# Or run from the project root
java -jar venue/target/covia.jar
```

### Development Mode

For development, you can run the application directly from the compiled classes:

```bash
mvn clean compile
cd venue
java -cp "target/classes:target/dependency/*" covia.venue.MainVenue
```

## Troubleshooting

### Common Issues

1. **Java Version Mismatch**: Ensure you're using Java 21
   ```bash
   java -version
   ```

2. **Maven Version Too Old**: Update to Maven 3.7 or later
   ```bash
   mvn -version
   ```

3. **Dependency Resolution Issues**: Try cleaning and rebuilding
   ```bash
   mvn clean install -U
   ```

4. **Memory Issues**: Increase Maven memory if needed
   ```bash
   export MAVEN_OPTS="-Xmx2g"
   mvn clean install
   ```

### Build Verification

To verify the build was successful:

```bash
# Check that all modules built successfully
mvn verify

# Run tests only
mvn test

# Check for any dependency conflicts
mvn dependency:analyze
```

## Continuous Integration

For CI/CD pipelines, use:

```bash
mvn clean verify
```

This ensures all tests pass and the build is ready for deployment.

## Releases

### Snapshot Releases

Snapshot builds are automatically created after the full test workflow passes
for a push to the `develop` branch. These are available at:

- [latest-snapshot](https://github.com/covia-ai/covia/releases/tag/latest-snapshot)

### Stable Releases

To create a stable release:

1. **Ensure you're on master** with all changes merged from develop:
   ```bash
   git checkout master
   git pull origin master
   ```

2. **Update the Maven version** in all pom.xml files:
   ```bash
   mvn versions:set -DnewVersion=1.0.0
   mvn versions:commit
   ```
   This updates the version in the parent pom.xml and all child modules.

3. **Commit the version change**:
   ```bash
   git add -A
   git commit -m "Release 1.0.0"
   ```

4. **Create and push a version tag** (must match the Maven version):
   ```bash
   git tag 1.0.0
   git push origin master --tags
   ```

5. **GitHub Actions will automatically**:
   - Build the project
   - Create a versioned release (e.g., `1.0.0`)
   - Update the `latest` release to point to this version

6. **Prepare for next development cycle** (on develop):
   ```bash
   git checkout develop
   git merge master
   mvn versions:set -DnewVersion=1.0.1-SNAPSHOT
   mvn versions:commit
   git add -A
   git commit -m "Prepare for next development iteration"
   git push origin develop
   ```

### Publishing to Maven Central

The reactor modules are published to Maven Central under the `ai.covia`
groupId. `ai.covia:covia-core`, `ai.covia:covia-python`, `ai.covia:venue`, and
`ai.covia:workbench` are ordinary library artifacts (along with the
`ai.covia:covia` parent POM). The operator-facing `covia-python-adapter`,
`covia-sql` and `covia-telegram` artifacts are loadable venue modules rather than dependencies of
the standard venue; their shaded `module` classifier jars are also published
and signed. GitHub Releases remain the canonical operator download, pairing
each module jar with its checksum. The executable `covia.jar` is an unattached
assembly and is **not** published to Central.

Publishing uses the [Sonatype Central Publishing plugin](https://central.sonatype.org/publish/publish-portal-maven/)
and mirrors the Convex setup: pom config lives in the root `pom.xml`
(`distributionManagement`, `central-publishing-maven-plugin`, source/javadoc
plugins, and a `release` profile that GPG-signs artifacts). The deploy itself is
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
     <properties>
       <gpg.executable>C:\Program Files (x86)\GnuPG\bin\gpg.exe</gpg.executable>
     </properties>
   </profile>
   ```
   and include it in release commands: `mvn deploy -P release,gpg`.
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
`-Prelease` GPG-signs every artifact; the Central plugin bundles all reactor
modules (main + sources + javadoc + pom + signatures, including attached
classifier artifacts) and, with `autoPublish=true`, publishes them to Maven
Central. `maven.deploy.skip` only controls the standard Maven deploy plugin; it
does not exclude a reactor module from the Central bundle. Then verify at
`https://central.sonatype.com/artifact/ai.covia/covia-core`.

### Release Artifacts

Both snapshot and stable releases include:

- `covia.jar` - The executable venue server JAR with all dependencies
- `covia.jar.sha256` - SHA-256 checksum for the executable JAR
- `covia-python-adapter-<version>-module.jar` - Optional Python venue module
- `covia-python-adapter-<version>-module.jar.sha256` - SHA-256 checksum
- `covia-sql-<version>-module.jar` - Optional SQL venue module
- `covia-sql-<version>-module.jar.sha256` - SHA-256 checksum
- `covia-telegram-<version>-module.jar` - Optional Telegram bot venue module
- `covia-telegram-<version>-module.jar.sha256` - SHA-256 checksum

### Download Links

- **Latest stable**: [latest](https://github.com/covia-ai/covia/releases/tag/latest)
- **Latest snapshot**: [latest-snapshot](https://github.com/covia-ai/covia/releases/tag/latest-snapshot)
- **Specific version**: `https://github.com/covia-ai/covia/releases/tag/<version>`
