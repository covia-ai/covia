# Schema Migration Requirements

Requirements for a complete schema migration that an embedder triggers, with
the embedder's own migrations layered after Covia's (#621). These are
requirements, not an agreed design: the questions at the end are open.
Related: #620 (knowing before a start, typed refusal), #615 (`user:import`),
#354 (store upgrade contract), #582 (0.10.0).

## Need

An embedder must be able to bring every stored value to the current schema in
one operation it triggers, and from then on read only that schema. Its own
migrations must run the same way, after Covia's.

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
- **Step**: the conversions an owner needs to go from version N to N + 1.
- **Run**: one application of the complete migrate to a venue.

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

## Requirements

### R1. Owners and versions

- Each owner has one integer schema version, held for the venue identity and
  for each user. Steps exist for both.
- Within an owner, steps apply in version order.
- Covia's steps run first, then each adapter's, then the embedder's. Each
  owner's conversions see the values of the owners before it in their current
  shape.
- An embedder registers, at start-up, its owner name, its current version and
  its ordered steps. Steps are plain Java with the embedder's code to hand (for
  example GetMine's category registry), not lattice data.

### R2. The complete migrate

- One run, triggered by the embedder, available as a Java API and as a
  venue-owned operation.
- It converts every part that has a registered conversion: the venue value,
  every user's jobs, agents, sessions and secrets, each adapter's state, and
  each embedder's paths.
- A batch holds parts of at most one user, so no commit spans users.
- For each owner, for the venue identity and for each user, it records the
  version fully reached. That version advances only when no part of the owner
  remains older.
- It reports every owner it could not complete. An adapter whose module is not
  loaded cannot convert its own state, so that owner stays incomplete and the
  run says so rather than reporting done, as `user:delete` does with
  `Engine.USER_DELETION_PENDING`.
- Running it again changes nothing.
- It keeps a reference to the root as it stood before the run: a restore point
  that costs no copy of files. When to keep it, and when to restore from it,
  is the embedder's decision (#354).
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
- A second run changes nothing. An interrupted run completes when run again.
  Writes made during a run are kept.
- An embedder's registered steps run after Covia's, both in a run and in
  `user:import`.
- A newer stored version is refused with the typed error, for Covia and for an
  embedder.
- PERSISTENCE.md describes schema versions per owner and the complete migrate.

## Open questions

- Where completion is recorded: by advancing `:version` only when nothing older
  remains, or in a separate marker per owner.
- The shape of the registration API for adapters and embedders.
- Who may invoke the venue-owned operation.
- Whether a run happens while the venue serves requests, or before it starts
  serving.
