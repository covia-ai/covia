# Venue Migration

Requirements for venue migration: bringing a venue's stored state to the
schema this build supports (#621). These are requirements, not an agreed
design: the questions at the end are open. Related: #620 (knowing before a
start, typed refusal), #615 (`user:import`), #354 (store upgrade contract),
#582 (0.10.0).

MUST and MAY are used as in RFC 2119.

## Need

1. It MUST be possible to upgrade a Covia venue's version.
2. An embedder MUST be able to use venue migration as part of its own
   migration.
3. Venue migration MUST be structured as pure functions of venue or vault
   state, so that they can be composed as each caller needs.
4. Venue migration MAY migrate some values lazily. The version upgrade then
   happens, and sub-components still in a supported older shape are migrated
   on demand.
5. Adapters MUST be able to define their own migration rules.

The embedder drives. It calls Covia's check and Covia's run from its own
migration path, then runs its own steps on top of them, in its own code. Covia
never runs an embedder's migration steps. An embedder such as GetMine migrates
its own data this way; how it does so is its own business and out of scope
here. Venue migration only has to make it possible.

The version contract in
[PERSISTENCE.md](PERSISTENCE.md#venue-versions-and-migrations), under which a
venue version may permit transitional records, is lazy migration under need 4,
and it stays.

## Terms

- **Venue state**: a venue's lattice value, wherever it is held: in a running
  venue, in a store no venue has open, or in an embedder's vault.
- **Responsible code**: the code responsible for a part's schema and its
  migration. Covia's venue code is responsible for the venue value and for
  every user's jobs, agents, sessions and secrets. An adapter is responsible
  for its own state and any legacy paths it once wrote. An embedder is
  responsible for its own paths in user workspaces and under the venue's
  identity, and migrates them itself.
- **Part**: the unit a migration function converts. A part is likely to be a
  specific lattice component or sub-component, such as a user's `SecretStore`,
  `JobState` or `AgentState`, one session's `SessionState`, or one adapter's
  `AdapterState`. It may be the whole venue (`VenueState`). A migration that
  moves a value has a part spanning the source and the destination.
- **Schema version**: an integer for Covia and for each adapter, recorded in
  the venue state: for the venue as a whole and, where the responsible code
  keeps per-user state, for each user. An embedder keeps its own versions in
  its own data.
- **Migration function**: a pure function from a part's value to that part in a
  newer shape.
- **Step**: the migration functions that take one responsible code from version
  N to N + 1. Steps are the stages of a migration: the unit an embedder calls
  and a test checks.
- **Migration rules**: the ordered steps that a responsible code defines.
- **Lazy part**: a part that a step leaves in an older shape the new version
  still supports, to be migrated on demand.
- **Run**: the ordered steps, Covia's and its adapters', that bring venue state
  to the schema this build supports, and one application of them.

## Migrations on `develop`

The venue is at version 1, and most of its migrations are lazy.

| Form | Responsible code | Eager or lazy | Where | How |
|------|------------------|---------------|-------|-----|
| Venue `:version` 0 → 1: `w/user-deletions` → `:user-deletions` | Covia | Eager, at start-up | `VenueState.migrateInPlace` | Transaction on a draft of the venue value |
| `w/adapters/<name>` → `:adapters/<name>` | Covia, for each adapter | Lazy, on first access | `VenueState.migrateLegacyState` | Transaction moving one value between two paths |
| Job `prev` chain (#611) | Covia | Lazy: ignored on read, dropped on the next write | `Job.currentState` | Static function over the record |
| Legacy agent frames (`conversation`/`items`) → `history` (`historyVersion: 1`) | Covia | Lazy, when a session cycle begins | `GoalTreeContext.migrateFrames`, from `SessionState.beginCycle` | Static function over the frames |
| Session `loads` folded into the root frame | Covia | Lazy, same place | `SessionState.migrateLegacySessionLoads` | Static function over the session |
| Secrets at key version 1 (#535) | Covia | Lazy, on that secret's next write | `SecretStore` | No function; every write encrypts under the current key version |
| Telegram session mappings, `w/telegram/sessions/<bot>/<chat>` | Telegram adapter | Lazy, on first read | `ConversationSessions` legacy callbacks | Callbacks that copy from the old path, which keeps its entry until the mapping is removed |
| Telegram bot registry, `w/telegram/bots` | Telegram adapter | Read at start; legacy records only fill missing bindings | `TelegramAdapter.rearmLegacyBots` | Reads through the engine |

Only the static functions are already close to the migration function
contract (R3). The transactions, the callbacks and the engine reads need to
become migration functions over a declared part.

Adapters can version their own state and migrate it step by step, enabled or
not (`AAdapter.stateVersion`, `AAdapter.migrateState`; see
[ADAPTERS.md](ADAPTERS.md#versioned-state-and-migration)). At launch the venue's
steps and those of the built-in and configured-module adapters run in one
transaction, before any bootstrap write; an adapter registered later migrates
in its own transaction. Only the test adapter versions its state so far
(version 1, with version 0's shape), so every venue and the 0.9.11 fixture run
adapter migration at start-up. `VenueState.peekVersions` reads the stored versions
before a start, and a newer version is refused with an
`UnsupportedVersionException`.

## Requirements

### R1. Upgrading a venue's version

- Venue state records a schema version for Covia and for each adapter. Covia's
  is the venue's `:version`.
- A build upgrades venue state at any older supported version to its own by
  applying the run.
- Starting a venue on older state applies the run in one venue transaction,
  before any bootstrap write: all of it commits, or none of it does. An
  embedder that wants control applies the run itself first (R2).
- A read-only check reports, for Covia and each adapter, the stored version
  against the version this build supports, and any lazy parts still in an
  older shape. It writes nothing, and can be called on a store before
  `VenueServer.launch` (#620), so a caller knows whether a start will migrate
  before it copies anything.
- A stored version newer than the build's, Covia's or an adapter's, is refused
  with a typed exception naming the responsible code, the stored version and
  the supported version (#620). Nothing is written.
- Applying the run again changes nothing.

### R2. Use by an embedder

- The run is an ordered list of steps. A caller can list the steps venue state
  needs and apply them one at a time, or all at once. Applying the whole run is
  a convenience over applying each step.
- The Java API is the main route, for embedders in the same process. A
  venue-owned operation is a second route, for callers outside it.
- Steps apply offline, to venue state that no running venue holds, and live, to
  a running venue. Live, writes made while steps apply are kept.
- The embedder drives. The check and the run are calls it makes from its own
  migration path. Neither takes embedder steps, and neither calls embedder
  code.
- The check and the run can both be reached before Covia's own start-up step
  changes anything, so the embedder chooses when Covia's values move.
- The run applies Covia's steps first, then each adapter's (R5). An adapter's
  functions see Covia's parts in their current shape. The embedder's own steps
  run after the run returns, and see every part Covia and the adapters are
  responsible for in its current shape.
- The run leaves every value that neither Covia nor an adapter is responsible
  for exactly as it found it: the embedder's own paths in user workspaces and
  under the venue's identity, and the names of its secrets. Covia still owns
  how secrets are encrypted and re-keyed.
- Each step returns a report: the responsible code, the versions it went from
  and to, the parts it converted, the parts it left lazy, and the parts it
  refused, each with its reason. A refused part leaves that responsible code's
  version where it was.
- The caller gets the venue state before and after each step. A state it keeps
  is a restore point that costs no copy of files. It stays readable, across
  store GC and restarts, until the caller releases it. When to keep one, and
  when to restore from it, is the embedder's decision (#354).

A path an embedder might build from these, where nothing serves requests until
the end:

1. Check the store: Covia's values with Covia's check, its own data with its
   own. If nothing is older, start as usual.
2. Keep a restore point, or take a copy.
3. Apply Covia's run, one step at a time or all together, checking the result
   between steps and doing any work of its own that is not a migration
   function. Migrate lazy parts on demand if it wants no older shapes left.
4. Run its own steps, on top of Covia's.
5. Verify the result by its own measures.
6. Release the restore point, or go back to it.
7. Serve.

### R3. Pure, composable functions

Every migration function is partial, idempotent and pure.

- **Partial.** A migration function covers one part. It sees only that part's
  value and the inputs it declares. It declares the paths it reads and writes,
  and reaches nothing outside them. Only one responsible code migrates a given
  part.
- **Idempotent.** Applying it twice gives the same result as applying it once.
  Given a part already in the current shape, it returns the same cell, so a
  current part causes no write and no re-stamp.
- **Pure.** No I/O, no lattice reads beyond its input, no clock. Anything else
  it needs, such as the venue's `SecretStore.Keys` or a timestamp, is passed in.
  Its only effect is its return value. Secret re-encryption is the one case
  that needs randomness: `AESGCM` draws a fresh nonce, so the bytes differ each
  time. There the result must decrypt to the same plaintext under
  `SecretStore.CURRENT_KEY_VERSION`, and a record already at that version is
  returned unchanged.
- **Refuses unknown shapes.** A shape the function does not recognise is
  refused with a reason, never passed through or reinterpreted.
- **One definition for every caller.** An eager step, a lazy migration on
  demand and `user:import` call the same function. No migration exists in two
  copies.

They compose:

- Lifted to venue state, every migration function, every step and every run is
  a pure function from venue state to venue state. A step is its functions
  together, a run is its steps in order, and an embedder composes Covia's run
  with its own steps.
- **Batching belongs to the caller.** A function never decides how much is
  committed together. At start-up the run is one transaction, all or nothing.
  A live run, such as a walk over every record of a large user, is many part
  migrations, committed in batches of whatever size the caller chooses. A
  batch holds parts of at most one user, so no commit spans users. Run on a
  fork of the venue state, the batches are all-or-nothing again when the
  caller adopts the fork, provided no running venue writes in the meantime.
- **Resume is a rerun.** An interrupted run starts again, and parts already
  current are no-ops. A progress cursor may make that faster, but correctness
  never depends on one.
- **Concurrent writes are kept.** Live, each batch commits as a transaction
  against the latest value. On a conflict the functions are applied again to
  the new value, which purity makes safe.
- **Functions are tested alone**, on fixture parts, without a venue.

Work that needs a model, the network, files or a drive is not a migration
function. An embedder does such work itself, between steps or after them.

### R4. Lazy migration

- A step declares each of its parts eager or lazy.
- Applying a step converts its eager parts. The responsible code's version
  advances once they are all converted.
- A lazy part may stay in an older shape that the new version still supports.
  It is migrated on demand: when the venue next uses it, or when a caller asks,
  for one part or for all of them.
- Readers normalise a lazy part through its migration function, so reading
  code handles the current shape only.
- A step that drops support for an older shape migrates every part still in
  that shape: any lazy part in it becomes eager in that step.

### R5. Adapter migration rules

- An adapter defines migration rules for the parts it is responsible for: its
  `AdapterState` and any legacy paths it once wrote. It has its own schema
  version, in line with [ADAPTERS.md](ADAPTERS.md), which makes each adapter
  responsible for its own schema.
- Module adapters (shaded module jars) define rules the same way as built-in
  adapters.
- An adapter's migration functions can be used without activating the adapter:
  no bots, no network, no polling.
- An adapter's steps apply after Covia's, within the run.
- Adapters are Covia's extension point, so an adapter an embedder or operator
  adds defines its rules the same way as a built-in. Those rules cover the
  adapter's own state only, never the embedder's other data.
- An adapter whose module is not loaded cannot migrate its own parts. The run
  reports it as incomplete rather than done, as `user:delete` does with
  `Engine.USER_DELETION_PENDING`.

### R6. `user:import`

- `user:import` (#615) applies Covia's steps to the incoming user before writing
  it. Adapter state is not part of an export. An export newer than the build
  is refused with the typed exception from R1.
- An embedder migrates its own paths in an imported user itself, after the
  import returns, as it does after the run.

### R7. Step-by-step testing

- **Fixtures by release.** Each release that changes a persisted format leaves
  a fixture, written by that release's published jar and never regenerated, as
  `covia-0.9.11.zip` is (see its
  [README](../src/test/resources/migrations/README.md)). The next is the 0.10.0
  store, at tag time. Between them, the fixtures hold every legacy form in the
  table above. Today none holds Telegram's registry or session mappings, or
  embedder data.
- **The embedders' API.** A harness walks each fixture through the run one step
  at a time, through the API embedders call. It has no stepping path of its
  own.
- **Checks after every step:**
  - the responsible code's version has advanced;
  - its eager parts are current in storage, not only on read;
  - its lazy parts are current or in a supported older shape, and migrating
    them on demand gives the current shape;
  - every other responsible code's parts are the same cells as before the
    step;
  - applying the step again returns the same venue state;
  - the fixture's data reads the same through the HTTP APIs, as
    `VenueStoreFixture.verifyUser` checks today.
- **Interruption.** A run stopped after any batch, then run again, ends equal to
  an uninterrupted run (for secrets, equal in meaning, as under R3).
- **Offline and live.** Both give equal results. Live, writes made during the
  run are kept.
- **For embedders.** The harness is published, so an embedder can walk its own
  fixtures through Covia's run, then apply its own steps in its own code. A
  failure in the run names the step and its responsible code, so it says
  whose migration broke.
- **Other values untouched.** After every step, values that neither Covia nor
  an adapter is responsible for are the same cells as before the run.
- **Kept runs.** On request a walk keeps each step's venue state and report,
  as `-Dcovia.migration.output` keeps an upgraded store today.

## Out of scope

- How an embedder migrates its own data, beyond what venue migration must
  allow it.
- The Etch store format, which does not change.
- DLFS node shapes. They are Convex's schema; Convex 0.8.18 reads both forms,
  and embedders read drives through the file API.
- Backup orchestration: #354 stands.

## Acceptance

- Every migration function has its own tests: a current part comes back as the
  same cell; a legacy part converts, and converting the result changes
  nothing; an unknown shape is refused with a reason.
- On the 0.9.11 fixture (`VenueStoreMigrationTest`,
  `migrations/covia-0.9.11.zip`), the run reaches the current version. With
  every lazy part then migrated on demand, no legacy form is left: no `prev`,
  no legacy frames or session `loads`, no `w/adapters`, and every secret at the
  current key version.
- The R7 harness walks the 0.9.11 fixture step by step, offline and live, with
  every check passing, and runs in CI's durability gate.
- An embedder can call Covia's check and run from its own migration path,
  before Covia's own start-up step changes anything, and run the harness on its
  own fixtures. Covia runs no embedder steps, and leaves the embedder's values
  as it found them.
- An adapter's migration rules apply with the adapter inactive.
- A newer stored version is refused with the typed exception, for Covia and
  for an adapter.
- A kept restore point is still readable after a store GC and a restart.
- PERSISTENCE.md describes schema versions for each responsible code, eager
  and lazy parts, and venue migration.

## Open questions

- Where schema versions are recorded for each responsible code, and for each
  user.
- Whether the check finds lazy parts in older shapes by scanning, or from a
  record kept as they change.
- How long an older shape stays supported for lazy parts.
- How an embedder reaches the run before Covia's own start-up step: by running
  it on the store before calling `launch`, or by `launch` leaving that step to
  the embedder's `beforeStart` hook.
- Who may invoke the venue-owned operation.
- How the harness is published: as a test jar of `venue`, or as a module of its
  own.
