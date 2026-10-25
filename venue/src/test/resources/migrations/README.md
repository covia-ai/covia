# Release-written venue fixtures

`covia-0.9.11.zip` contains a complete, cleanly closed Etch store and its
`manifest.json`. It was written and reopened by the **published 0.9.11 jar**,
not by current code with old-looking records inserted afterwards.

- Source: <https://github.com/covia-ai/covia/releases/tag/0.9.11>
- Published jar SHA-256: `c5975145ff860c2d6d893ecfa832cdb9a7e532d71929f06a4d9b335d47269530`
- Original store SHA-256: `834373255d9a54d2c66e2706219d940997e87578848b0cd7bdd9d1589fd564b6`
- Generator: `venue/src/test/java/covia/venue/VenueStoreFixture.java`
- Test: `VenueStoreMigrationTest` (the existing durability gate includes it in CI).

The identities, seeds and secrets are public test data. Never use this venue
for real accounts. The generator and launch config bind to loopback and disable
anonymous access.

The corpus has Alice and Bob, each with a workspace containing nested values,
Unicode, a stored null and empty containers; a callable user operation;
encrypted secrets; a drive with text and binary files; a content-addressed
asset; a completed job with the old `prev` chain; and a mock-LLM agent with a
completed, titled session. Both users deliberately have the same agent name.
The venue user also owns a synthetic test-adapter scratch value at the old
`w/adapters/test` route, exercising lazy adapter-state migration without bots.

## Run

From the repository root:

```sh
mvn test -pl venue -am '-Dtest=VenueStoreMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcovia.tests.durability=true'
```

The test extracts a fresh copy, verifies its checksum, upgrades it, exercises
authenticated HTTP APIs, and reopens it twice. No download or older runtime is
needed to run the test. It also checks current-only job reads, account isolation,
secret decryption/rewrite, conversation continuation, extension CRUD, and user
export/import/clone/delete. Provider services are replaced by the built-in test
LLM. Existing crash tests cover abrupt termination separately; this test covers
release-to-release migration and graceful restart.

To retain a run, add `-Dcovia.migration.output=/absolute/path/to/NEW-directory`.
Its parent must exist and the directory itself must not: the harness refuses
to overwrite a previous run. It retains the upgraded `venue.etch`, original
manifest, a `result.json` report, and `venue.json` for a loopback venue on port
18080. The manifest's store hash describes the original baseline; `result.json`
records the upgraded store's hash. After building the current jar:

```sh
java -jar venue/target/covia.jar /absolute/path/to/run/venue.json
```

Stop the running venue before opening its store with another process. The public
test identities use 32-byte seeds repeating `11` (Alice), `22` (Bob), `33`
(imported user), and `42` (venue operator), in hexadecimal. The cloned user (`44`)
is deliberately deleted by the rehearsal. Use ordinary audience-bound
`VenueAuth.keyPair` credentials for API calls.

For checking a **copy** of a retained run against another packaged build:

```sh
java -cp /path/to/candidate/covia.jar venue/src/test/java/covia/venue/VenueStoreFixture.java verify-updated /absolute/path/to/copied-run
```

`verify` checks an unmodified baseline copy; `verify-updated` checks the values
after the rehearsal. Opening a store can migrate it, so never aim a candidate
build at the original archive or a live/user-owned store. The full JUnit test is
the mutation/authority/transfer check; the standalone command checks fixture
readability through HTTP on the selected jar.

## Provenance and regeneration

Keep old fixture archives unchanged. Do not make a failing migration pass by
regenerating the baseline with a newer build. Add new versioned fixtures when
later persisted formats need coverage.

For auditing the 0.9.11 generator, download `covia.jar` and `covia.jar.sha256`
from the release above, verify the checksum, then run:

```sh
java -cp /path/to/0.9.11/covia.jar venue/src/test/java/covia/venue/VenueStoreFixture.java seed /absolute/path/to/NEW-baseline
```

The generator itself checks the jar's published checksum and refuses an existing
directory. It uses source-file mode so all venue classes come from that release.
It verifies both users before and after an old-runtime restart. Timestamps and
job/session IDs vary between generations; the manifest records those identifiers
and the resulting store hash. A newly generated store is not byte-identical to
the committed baseline and must not silently replace it.
