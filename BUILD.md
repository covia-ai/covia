# Build Guide

This document describes how to build the Covia project using Maven.

## Prerequisites

- **Java 21**: The project requires Java 21 (JDK 21)
- **Maven 3.7+**: Minimum Maven version required (enforced by maven-enforcer-plugin)
- **Git**: For cloning the repository

## Project Structure

Covia is a multi-module Maven project with the following structure:

```
covia/
├── pom.xml                 # Parent POM (aggregator)
├── venue/                  # Main application module
│   ├── pom.xml            # Venue module POM
│   └── src/
│       ├── main/java/     # Main source code
│       ├── main/resources/ # Resources and assets
│       └── test/java/     # Test source code
└── workbench/             # GUI workbench module
    ├── pom.xml            # Workbench module POM
    └── src/
        ├── main/java/     # GUI source code
        └── main/resources/ # GUI resources
```

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

The venue module produces several artifacts:

- **Standard JAR**: `venue/target/venue-0.0.1-SNAPSHOT.jar`
- **Executable JAR**: `venue/target/covia.jar` (with dependencies)
- **Test JAR**: `venue/target/venue-0.0.1-SNAPSHOT-tests.jar`

The executable JAR (`covia.jar`) is created using the maven-assembly-plugin and includes all dependencies. It can be run directly with:

```bash
java -jar venue/target/covia.jar
```

### Workbench Module

The workbench module produces:

- **Standard JAR**: `workbench/target/workbench-0.0.1-SNAPSHOT.jar`


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

Snapshot builds are automatically created on every push to the `develop` branch. These are available at:

- [latest-snapshot](https://github.com/covia-ai/covia/releases/tag/latest-snapshot)

### Stable Releases

To create a stable release:

1. **Ensure you're on master** with all changes merged from develop:
   ```bash
   git checkout master
   git pull origin master
   ```

2. **Create and push a version tag** (must be semver format):
   ```bash
   git tag 1.0.0
   git push origin 1.0.0
   ```

3. **GitHub Actions will automatically**:
   - Verify the tag is on the master branch
   - Build the project
   - Create a versioned release (e.g., `1.0.0`)
   - Update the `latest` release to point to this version

### Release Artifacts

Both snapshot and stable releases include:

- `covia.jar` - The executable venue server JAR with all dependencies

### Download Links

- **Latest stable**: [latest](https://github.com/covia-ai/covia/releases/tag/latest)
- **Latest snapshot**: [latest-snapshot](https://github.com/covia-ai/covia/releases/tag/latest-snapshot)
- **Specific version**: `https://github.com/covia-ai/covia/releases/tag/<version>` 