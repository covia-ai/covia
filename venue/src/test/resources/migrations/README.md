# Release-written venue fixtures

Each archive here contains a complete, cleanly closed Etch store and its
`manifest.json`, one per release line. Each was written and reopened by a
**published release jar**, not by current code with old-looking records
inserted afterwards.

- `covia-0.9.11.zip`, from <https://github.com/covia-ai/covia/releases/tag/0.9.11>
  - Published jar SHA-256: `c5975145ff860c2d6d893ecfa832cdb9a7e532d71929f06a4d9b335d47269530`
  - Original store SHA-256: `834373255d9a54d2c66e2706219d940997e87578848b0cd7bdd9d1589fd564b6`
- `covia-0.10.0.zip`, from <https://github.com/covia-ai/covia/releases/tag/0.10.0>
  - Published jar SHA-256: `f0f4a4f369ac22482e414d1c0e5d64bd345e58eaeaa064c0f0b86118321eefc1`
  - Original store SHA-256: `e9d9c6fe5e427af1f43c3dea85c30f7973fba83234cbfb8af22a5b4628a7dee4`
- Generator: `venue/src/test/java/covia/venue/VenueStoreFixture.java`
- Tests: `VenueUpgradeIT` opens every archive in process, and
  `VenueStoreMigrationIT` adds HTTP use and restarts. Fixtures are big, so
  both are failsafe integration tests: `mvn verify` runs them, and so does CI.

The identities, seeds and secrets are public test data. Never use these venues
for real accounts. The generator and launch config bind to loopback and disable
anonymous access.

The corpus has Alice and Bob, each with a workspace containing nested values,
Unicode, a stored null and empty containers; a callable user operation;
encrypted secrets; a drive with text and binary files; a content-addressed
asset; a completed job, which 0.9.11 wrote with the old `prev` chain; and a
mock-LLM agent with a completed, titled session. Both users deliberately have
the same agent name. Alice also has a synthetic test-adapter scratch value,
with no bot started: 0.9.11's is at the old `w/adapters/test` route, written by
the venue user, exercising adapter-state migration; 0.10.0's is in the test
adapter's own state, at version 1. Both then run the test adapter's later
steps.

## Run

From the repository root:

```sh
mvn verify -pl venue -am '-Dit.test=VenueStoreMigrationIT' '-Dtest=none' '-Dsurefire.failIfNoSpecifiedTests=false'
```

For each archive, the test extracts a fresh copy, verifies its checksum, upgrades it, exercises
authenticated HTTP APIs, and reopens it twice. No download or older runtime is
needed to run the test. It also checks current-only job reads, account isolation,
secret decryption/rewrite, conversation continuation, extension CRUD, and user
export/import/clone/delete. Provider services are replaced by the built-in test
LLM. Existing crash tests cover abrupt termination separately; this test covers
release-to-release migration and graceful restart.

To retain a run, add `-Dcovia.migration.output=/absolute/path/to/directory`.
Each fixture's run goes in a new sub-directory named for its release, and the
harness refuses to overwrite a previous run. It retains the upgraded `venue.etch`, original
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
regenerating the baseline with a newer build. Each release line (0.9, 0.10, …)
gets one fixture; every other migration step is tested on states built in
tests ([MIGRATE.md](../../../../docs/MIGRATE.md#testing)).

To make a new release line's fixture, or to audit an existing fixture's
generator, download `covia.jar` and `covia.jar.sha256` from the release, check
the jar against the checksum, then run:

```sh
java -cp /path/to/<release>/covia.jar venue/src/test/java/covia/venue/VenueStoreFixture.java seed /absolute/path/to/NEW-baseline
```

The generator itself checks that the jar is one of the published releases in
`VenueStoreFixture.RELEASES` (a new line's release is added there first), and
refuses an existing directory. It uses source-file mode so all venue classes
come from that release, and verifies both users before and after an
old-runtime restart. It writes the store, its `manifest.json` and the fixture
archive, `covia-<release>.zip`; a new line's archive is copied here and listed
above. Timestamps and
job/session IDs vary between generations; the manifest records those identifiers
and the resulting store hash. A newly generated store is not byte-identical to
the committed baseline and must not silently replace it.
