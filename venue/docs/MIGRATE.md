# Schema Migration Requirements

Requirements for a complete schema migration that an embedder triggers, with
the embedder's own migrations layered after Covia's (#621). These are
requirements, not an agreed design: the questions at the end are open.
Related: #620 (knowing before a start, typed refusal), #615 (`user:import`),
#354 (store upgrade contract), #582 (0.10.0).

## Need

An embedder must be able to bring every stored value to the current schema,
and from then on read only that schema. It does this from inside its own
migration path: in one call, or step by step with its own checks and work
between steps. Its own migrations must run the same way, after Covia's.

Most schema changes today are lazy: a value takes its new shape when it is next
written, or when its component next reaches it. Until every value has been
reached, every reader, Covia's and the embedder's alike, must understand both
shapes. GetMine's rule from #354 is one format, one direct path, no permanent
dual-read. Lazy migration alone cannot meet it.

This changes the contract in
[PERSISTENCE.md](PERSISTENCE.md#venue-versions-and-migrations), under which the
venue version names the formats readers must understand, not the completion of
a bulk rewrite, and no venue-wide scan runs. The lazy paths stay; the complete
migrate is added alongside them.

These are changes to lattice values. The Etch store format does not change.

## Terms

- **Owner**: the code that defines a value's schema. Covia owns the venue value
  and every user's jobs, agents, sessions and secrets. An adapter owns its
  `:adapters/<name>` state and any legacy paths it once wrote. An embedder owns
  its paths in user workspaces and under the venue's own identity.
- **Schema version**: one integer per owner, held for the venue identity and
  for each user.
- **Part**: the smallest unit a conversion must see whole, such as one job
  record, one agent session, one secret record, one adapter's value, or the top
  level of the venue value. A conversion that moves a value has a part that
  spans the source and the destination.
- **Conversion**: a function from one part's value to that part in the current
  shape.
- **Step**: the conversions an owner needs to go from version N to N + 1. Steps
  are the stages of a migration: the unit an embedder calls and a test checks.
- **Run**: the ordered list of steps that brings a store to the current schema,
  and one application of them.

## Conversions

Every conversion is a partial, idempotent, pure function. The other
requirements follow from this.

- **Partial.** A conversion covers one part. It sees only that part's value and
  the inputs it declares. It declares the paths it reads and writes, and
  reaches nothing outside them. Two owners never convert the same part.
- **Idempotent.** Converting twice gives the same result as converting once.
  Given a part already in the current shape, a conversion returns the same
  cell, so a current part causes no write and no re-stamp.
- **Pure.** No I/O, no lattice reads beyond its input, no clock. Anything else
  it needs, such as the venue's `SecretStore.Keys` or a timestamp, is passed in.
  Its only effect is its return value. Secret re-encryption is the one case
  that needs randomness: `AESGCM` draws a fresh nonce, so the bytes differ each
  time. There the requirement is that the result decrypts to the same plaintext
  under `SecretStore.CURRENT_KEY_VERSION`, and that a record already at that
  version is returned unchanged.
- **Refuses unknown shapes.** A shape the conversion does not recognise is
  refused with a reason, never passed through or reinterpreted.
- **One definition for every caller.** The lazy path, the complete migrate and
  `user:import` call the same conversion. No conversion exists in two copies. A
  reader can then normalise through the conversion and handle only the current
  shape, before the migrate has reached that part.

Consequences:

- **Batching belongs to the runner.** A conversion never decides how much is
  committed together. A walk over every record of a user (thousands of
  `w/health` records, in GetMine's case) is thousands of part conversions,
  committed in batches of whatever size the runner chooses.
- **Resume is a rerun.** An interrupted run starts again, and parts already
  current are no-ops. A progress cursor may make that faster, but correctness
  never depends on one.
- **Concurrent writes are kept.** Each batch commits as a transaction against
  the latest value. On a conflict the conversions are applied again to the new
  value, which purity makes safe.
- **Conversions are tested alone**, on fixture parts, without a venue.

Anything that needs a model, the network, files or a drive is not a
conversion. For GetMine, that covers re-extraction (which replaces the old
`suggestions` line with `advice`), email ledger reclassification on a new
`filterVersion`, and housekeeping that touches files or the drive. These stay
in the embedder's own start-up, outside the migrate.

## Lazy conversions on `develop`

| Form | Owner | When it converts | How |
|------|-------|------------------|-----|
| Venue `:version` 0 → 1: `w/user-deletions` → `:user-deletions` | Covia | At start-up, `VenueState.migrateInPlace` | Transaction on a draft of the venue value |
| `w/adapters/<name>` → `:adapters/<name>` | Covia, for each adapter | On first access, `VenueState.migrateLegacyState` | Transaction moving one value between two paths |
| Job `prev` chain (#611) | Covia | Ignored on read by `Job.currentState`, dropped on the next write | Static function over the record |
| Legacy agent frames (`conversation`/`items`) → `history` (`historyVersion: 1`) | Covia | When a session cycle begins, `GoalTreeContext.migrateFrames` from `SessionState.beginCycle` | Static function over the frames |
| Session `loads` folded into the root frame | Covia | Same place, `SessionState.migrateLegacySessionLoads` | Static function over the session |
| Secrets at key version 1 (#535) | Covia | On that secret's next write | No conversion; every write encrypts under the current key version |
| Telegram session mappings, `w/telegram/sessions/<bot>/<chat>` | Telegram adapter | On first read, through `ConversationSessions`' legacy callbacks | Callbacks that copy from the old path, which keeps its entry until the mapping is removed |
| Telegram bot registry, `w/telegram/bots` | Telegram adapter | At start, `TelegramAdapter.rearmLegacyBots`; legacy records only fill missing bindings | Reads through the engine |

Only the static functions are already close to the conversion contract. The
transactions, the callbacks and the engine reads become conversions over a
declared part.

## What GetMine brings

GetMine's values live per user: records under `w/health/<category>/<id>`, the
ingestion ledger under `w/sources`, settings and housekeeping under `w/system`,
machinery under `w/mina`, and secrets in each user's store. Under the venue's
own identity it keeps `w/users/list/<id>`, `w/dev-mode/*`,
`w/install/keep-running` and the venue's seed secrets.

It already keeps two per-user stamps, applied when a user is first opened:

- `w/system/version` = `{categories: 2}` refuses to load a vault with a higher
  stamp.
- `w/system/status-words` = `{migrated: 1}` records a one-off rewrite of
  `userStatus` across every `w/health` record.

Further migrations run when the app loads (lifting nested `self-reported`,
`snaps` and Made by Mina records to flat records), and its readers tolerate
older roots and shapes. These become GetMine's first registered steps, under
one GetMine version per user. Its readers can then drop their tolerances.

## An embedder's migration path

Migration orchestration is the embedder's (#354): copy forward, verify, then
retire. Covia supplies the pieces of that path that only Covia can. A path built
from them looks like this:

1. Check the store, with the embedder's own steps included, before any venue
   opens it (R3). If nothing is older, start as usual.
2. Keep a restore point, or take a copy, as the embedder chooses.
3. Apply the run's steps in order, one at a time or all together: Covia's, then
   the adapters', then the embedder's. Between steps, check the result and do
   any of the embedder's own work that is not a conversion.
4. Verify the result by the embedder's own measures, such as a manifest of
   counts and hashes.
5. Release the restore point, or go back to it.
6. Start the venue. Readers see current shapes only.

Every requirement below must allow this path with no venue serving requests.

## Requirements

### R1. Owners and versions

- Each owner has one integer schema version, held for the venue identity and
  for each user. Steps exist for both.
- Within an owner, steps apply in version order.
- Covia's steps run first, then each adapter's, then the embedder's. Each
  owner's conversions see the values of the owners before it in their current
  shape.
- An embedder supplies its owner name, its current version and its ordered
  steps as code. The check, the run and a running venue all take them, and
  supplying them never needs a running venue. Steps are plain Java with the
  embedder's code to hand (for example GetMine's category registry), not
  lattice data.
- An adapter's conversions can be used without activating the adapter: no
  bots, no network, no polling.

### R2. The complete migrate

- A run is an ordered list of steps. The caller can list the steps a store
  needs and apply them one at a time, or all at once. Applying the whole run is
  a convenience over applying each step.
- The Java API is the main route, for embedders in the same process. A
  venue-owned operation is a second route, for callers outside it.
- A run works offline, on a store no venue has open or on a root value, and
  live, on a running venue. Offline serves the embedder's own path. Live, writes
  made during the run are kept.
- It converts every part that has a registered conversion: the venue value,
  every user's jobs, agents, sessions and secrets, each adapter's state, and
  each embedder's paths.
- A batch holds parts of at most one user, so no commit spans users.
- Each step returns a report: the owner, the versions it went from and to, the
  parts it converted, and the parts it refused, each with its reason. A refused
  part leaves that owner's version where it was.
- For each owner, for the venue identity and for each user, it records the
  version fully reached. That version advances only when no part of the owner
  remains older.
- It reports every owner it could not complete. An adapter whose module is not
  loaded cannot convert its own state, so that owner stays incomplete and the
  run says so rather than reporting done, as `user:delete` does with
  `Engine.USER_DELETION_PENDING`.
- Running it again changes nothing.
- The caller gets the root before and after each step. A root it keeps is a
  restore point that costs no copy of files. It stays readable, across store GC
  and restarts, until the caller releases it. When to keep one, and when to
  restore from it, is the embedder's decision (#354).
- The lazy paths remain for parts a run has not reached, through the same
  conversions. Once the check (R3) reports an owner complete, readers of that
  owner's values may assume the current shape.

### R3. Read-only check

- It reports, for each owner, the stored version against the version this
  build supports, and whether any part is older.
- It writes nothing, and can be called on a store before `VenueServer.launch`
  (#620), so an embedder knows whether a start will migrate before it copies
  anything.
- It includes the embedder's owners.

### R4. Typed refusal

- A stored version newer than the build's, for any owner, is refused with a
  typed exception that names the owner, the stored version and the supported
  version (#620). Nothing is written. Today the venue check throws a plain
  `IllegalStateException` with `VenueState.UNSUPPORTED_VERSION`.

### R5. `user:import`

- `user:import` (#615) runs the same chain on the incoming user before writing
  it: Covia's steps, then the adapters', then the embedder's. An export newer
  than the build is refused as in R4.

### R6. Step-by-step testing

- **Fixtures by release.** Each release that changes a persisted format leaves
  a fixture, written by that release's published jar and never regenerated, as
  `covia-0.9.11.zip` is (see its
  [README](../src/test/resources/migrations/README.md)). The
  next is the 0.10.0 store, at tag time. Between them, the fixtures hold every
  legacy form in the table above. Today none holds Telegram's registry or
  session mappings, or embedder data.
- **The embedders' API.** A harness walks each fixture through the run one step
  at a time, through the API embedders call. It has no stepping path of its
  own.
- **Checks after every step:**
  - the owner's version has advanced;
  - that owner's parts are current in storage, not only on read;
  - every other owner's parts are the same cells as before the step;
  - applying the step again returns the same root;
  - the fixture's data reads the same through the HTTP APIs, as
    `VenueStoreFixture.verifyUser` checks today.
- **Interruption.** A run stopped after any batch, then run again, ends equal to
  an uninterrupted run (for secrets, equal in meaning, as under
  [Conversions](#conversions)).
- **Offline and live.** Both give equal results. Live, writes made during the
  run are kept.
- **For embedders.** The harness is published, so an embedder can run the same
  walk with its own fixtures and steps: Covia's steps, then its own. GetMine's
  per-release user exports (#615) are such fixtures. A failure names the step
  and its owner, so it says whose migration broke.
- **Kept runs.** On request a walk keeps each step's root and report, as
  `-Dcovia.migration.output` keeps an upgraded store today.

## Out of scope

- The Etch store format, which does not change.
- DLFS node shapes. They are Convex's schema; Convex 0.8.18 reads both forms,
  and embedders read drives through the file API.
- Backup orchestration: #354 stands.
- Work that needs I/O, as listed under [Conversions](#conversions).

## Acceptance

- Every conversion has its own tests: a current part comes back as the same
  cell; a legacy part converts, and converting the result changes nothing; an
  unknown shape is refused with a reason.
- On the 0.9.11 fixture (`VenueStoreMigrationTest`,
  `migrations/covia-0.9.11.zip`), a run leaves no legacy form: no `prev`, no
  legacy frames or session `loads`, no `w/adapters`, and every secret at the
  current key version.
- The R6 harness walks the 0.9.11 fixture step by step, offline and live, with
  every check passing, and runs in CI's durability gate.
- An embedder can run the same harness with its own fixtures and steps.
- An embedder's steps run after Covia's, both in a run and in `user:import`.
- A kept restore point is still readable after a store GC and a restart.
- A newer stored version is refused with the typed error, for Covia and for an
  embedder.
- PERSISTENCE.md describes schema versions per owner and the complete migrate.

## Open questions

- Where completion is recorded: by advancing `:version` only when nothing older
  remains, or in a separate marker per owner.
- The shape of the registration API for adapters and embedders.
- Who may invoke the venue-owned operation.
- How the harness is published: as a test jar of `venue`, or as a module of its
  own.
