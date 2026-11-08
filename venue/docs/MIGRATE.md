# Venue Migration

Migration brings a venue's stored state to the schema of the running build.
The version fields are defined in
[PERSISTENCE.md](PERSISTENCE.md#venue-versions-and-migrations), adapter state
versions in [ADAPTERS.md](ADAPTERS.md#versioned-state-and-migration), and the
calls an embedder makes in [EMBEDDING.md](EMBEDDING.md).

## Requirements

- A build reads a store written by any older release and brings it to its own
  schema.
- Migration supports stores written by a release, never a snapshot's. The
  dev venues, which CI deploys from `develop`
  ([AGENTS.md](../../AGENTS.md#branches-and-deploys)), are wiped and
  refreshed instead (#670, not built), and no other store runs a snapshot.
- A build refuses stored state newer than it supports, Covia's or an
  adapter's, and writes nothing.
- A failed migration leaves the store as it was.
- Migrating state that is already current writes nothing.
- Migration only goes forward. An older build cannot read upgraded state, so
  going back needs the state from before the upgrade (#354).
- Migration can run at any time: at start-up, on a store before launch, or on
  a running venue. The stored versions can be read without writing.
- One call converts every older shape, and a read-only check reports any that
  remain (#624, not built). Until then, a shape that converts on use can stay
  in a store indefinitely, and the code that reads it cannot be removed.

## Ownership

Each part of the schema has one owner, which defines its migrations.

- **Covia** owns the venue value and each user's jobs, agents, sessions and
  secrets.
- **Each adapter** owns its state at `:adapters/<name>` and any older paths it
  wrote. Its versioned steps, whether it is built in, a module or an
  embedder's, are its `AAdapter.stateSteps()`.
- **An embedder** migrates any other data of its own after Covia's migration
  returns. Covia does not change it.

## Design

- **Versions.** The venue value's `:version`, against
  `VenueState.CURRENT_VERSION`, covers Covia's schema. Each adapter's version
  is recorded in `:adapter-versions`, against its `stateVersion()`. A user
  export carries its own version, `UserExports.VERSION`.
- **Migrations are pure functions.** Each takes one part of the state to a
  newer shape, with no I/O and no clock; anything else it needs, such as the
  venue's keys, is passed in. A part already current comes back as the same
  cell. An unrecognised shape is refused with a reason.
- **One version step per release.** A step is a `MigrationStep`
  (`covia.venue.migration`). Covia's are listed in order in
  `VenueMigrations.steps(keys)` and an adapter's in its `stateSteps()`; step `i`
  takes version `i` to `i + 1`, so a version is the number of steps. A release
  moves a schema one version at most. Its step is made of that release's
  changes, each a part with its own tests, applied in order
  (`MigrationStep.of`). A change before the release adds a part to its step;
  a released step never changes, and the next release's change starts a new
  step at the end. `VenueState` only runs them.
- **A full scan is a versioned step.** Converting every stored record of a
  kind visits the whole store, so it happens once, in a venue or adapter step,
  never on a routine start-up. Readers keep no fallback for a shape a step has
  converted.
- **Start-up is one transaction.** `VenueState.initialise(did, keys, adapters)` runs
  the venue's steps, then those of the built-in and configured module
  adapters, before any other write. A refusal or a failing step discards the
  whole transaction. An adapter registered later migrates in its own
  transaction as it registers (`VenueState.migrateAdapter`).
- **Migration is also a call.** `Engine.migrate()` migrates the venue and
  every installed adapter. `VenueState.migrate(keys, adapters)` does the same on a
  store before launch. `VenueState.peekVersions` reads the stored versions
  without writing.
- **Some shapes convert on use.** A venue version may read an older shape and
  convert it when the part is next used, instead of at start-up. The reader
  normalises it, so code beyond the reader sees only the current shape. A
  build that stops reading such a shape first converts every part still in it.
- **Import migrates.** `user:import` applies the venue steps' conversions of a
  user's records to an export older than `UserExports.VERSION` (version 2
  names callers and owners as step 1 → 2 does) and re-encrypts secrets under
  the current key; other shapes convert on use. It leaves out the export's
  jobs, which the account it fills has not made, and refuses an export newer
  than `UserExports.VERSION`.

Open questions for #624:

- Whether the check finds older shapes by scanning, or from a record kept as
  they change, and so whether versions are also recorded for each user.
- How long an older shape stays readable once it can be converted on request.

## Migrations in this build

The venue is at version 2.

### Venue steps

A step's parts apply in the order listed.

| Step | Change | Code |
|------|--------|------|
| 0 → 1 | `w/user-deletions` in the venue user's workspace moves to the `:user-deletions` region | `UserDeletionsRegion` |
| 1 → 2 | A job record holds only its current state: the `prev` chain of earlier states that a venue before 0.10.0 wrote goes from every job of every account, without walking the chain | `CurrentJobRecords` |
| 1 → 2 | Records of calls name `caller` and `owner` (COG-8): a job's `actor` becomes its `caller` and its old `caller` its `owner`; a scheduled event's `actor` (else `owner`) becomes its `caller` and `by` goes; a HITL request's `from` and `requesterUser` become `caller` and `owner`; an agent output snapshot's `user` becomes `owner` and its `caps` copy goes. A HITL request or snapshot naming only its caller takes its owner's namespace when the caller is an existing agent of this venue, else its own | `CallerAndOwner` |
| 1 → 2 | Each unfinished job of a live account gets an entry in `:jobs` naming its owner. Proofs its record kept move to the entry's caller context. A remote job's connection, kept encrypted in the venue's `remote-job-<id>` secret that `delegation.authRef` named, moves to the entry's adapter data without any sign-in credential (only the mode of a caller-signed hop), and its pinned definition gives the entry's output rules; `authRef` and every `remote-job-*` secret go. An external agent's job moves `agentOwner`, `agentInteraction` and `agentBackendJob` to the entry's adapter data. A pending agent `outputPath` write's context moves from the worker's task (`outputContext`) to the request job's entry, and the task keeps neither `outputContext` nor `outputPath`. Any other job's entry has no output rules: a step cannot resolve an operation. Built with the venue's secret keys | `JobEntries` |
| 1 → 2 | A remote job's record keeps only what its caller can follow: every record's `delegation` keeps its `remoteId` and `remoteStatus`, and goes when it holds neither. An unfinished job's `protocol`, `target` and `ownsRemote` are copied into its entry's adapter data; anything else, observation health among it, is left behind | `DelegationEntries` |

### Adapter steps

| Adapter | Step | Change | Code |
|---------|------|--------|------|
| `test` | 0 → 1 | None: version 1 has version 0's shape | `TestAdapter.STATE_STEPS` |
| `test` | 1 → 2, 2 → 3 | Synthetic, for tests: each appends the version it reaches to `migrations` | `TestAdapter.LogStep` |
| `telegram`, `discord`, `whatsapp`, `slack` | 0 → 1 | A bot one of its user's agents created names that agent `caller`, not `principal` | `BotCaller` |

### Shapes converted on use

| Older shape | Owner | Converted | Code |
|-------------|-------|-----------|------|
| Adapter state at `w/adapters/<name>` → `:adapters/<name>` | Covia | Before a versioned adapter's steps; otherwise when the adapter's state is first read | `VenueState.moveLegacyAdapterState` |
| Agent frame with `conversation` or `items` → `history` | Covia | When a session cycle begins | `GoalTreeContext.migrateFrames` |
| Session `loads` → the root frame's `loads` | Covia | When a session cycle begins | `Session.migrateLegacySessionLoads` |
| Secret at key version 1 | Covia | Read with the version 1 key; re-encrypted under the current key on its next write | `SecretStore` |
| Public agent with `a2a.caps` | Covia | Read as `accepts: "anyone"`; the stored config is unchanged | `Admission.policy` |
| OAuth grant as a user secret `oauth/<provider>` → `:adapters/oauth` connection | OAuth adapter | On the user's next use of their connections | `OAuthAdapter.moveLegacyGrants` |
| Telegram session mapping at `w/telegram/sessions/<bot>/<chat>` | Telegram adapter | When the chat's mapping is first read | `ConversationSessions`, `BotRunner.legacySession` |
| Telegram bot registry at `w/telegram/bots` | Telegram adapter | At adapter start, for each bot with no current binding | `TelegramAdapter.rearmLegacyBots` |

## Testing

- **Migration functions.** Tests build the older state in code: a current part
  comes back as the same cell, an older part converts and converting it again
  changes nothing, and an unrecognised shape is refused. A module tests its own
  shapes, as `TelegramMigrationTest` does.
- **End to end.** `VenueMigrationEndToEndTest` builds a venue at version 0
  holding every shape above that Covia and the bundled adapters convert, which
  leaves out the one read as it is (a public agent's `a2a.caps`) and the
  messaging adapters' shapes. Each
  versioned bundled adapter starts at its oldest state. The test migrates the
  venue in process. A new venue step or older shape is seeded there; raising
  `CURRENT_VERSION` fails the test until the new step's shapes are.
- **Release-line fixtures.** Each release line (0.9, 0.10, …) has one store,
  written by a published jar of that line and never regenerated
  ([README](../src/test/resources/migrations/README.md)). The failsafe
  integration tests `VenueUpgradeIT` and `VenueStoreMigrationIT` open them.

## Out of scope

- The Etch store format, which does not change.
- DLFS node shapes, which are Convex's schema; Convex 0.8.18 reads both forms.
- Backups (#354).
