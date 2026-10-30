# Venue Migration

Migration brings a venue's stored state to the schema of the running build.
The version fields are defined in
[PERSISTENCE.md](PERSISTENCE.md#venue-versions-and-migrations), adapter state
versions in [ADAPTERS.md](ADAPTERS.md#versioned-state-and-migration), and the
calls an embedder makes in [EMBEDDING.md](EMBEDDING.md).

## Requirements

- A build reads a store written by any older release and brings it to its own
  schema.
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
  embedder's, go through `AAdapter.stateVersion` and `migrateState`.
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
- **Start-up is one transaction.** `VenueState.initialise(did, adapters)` runs
  the venue's steps, then those of the built-in and configured module
  adapters, before any other write. A refusal or a failing step discards the
  whole transaction. An adapter registered later migrates in its own
  transaction as it registers (`VenueState.migrateAdapter`).
- **Migration is also a call.** `Engine.migrate()` migrates the venue and
  every installed adapter. `VenueState.migrate(adapters)` does the same on a
  store before launch. `VenueState.peekVersions` reads the stored versions
  without writing.
- **Some shapes convert on use.** A venue version may read an older shape and
  convert it when the part is next used, instead of at start-up. The reader
  normalises it, so code beyond the reader sees only the current shape. A
  build that stops reading such a shape first converts every part still in it.
- **Import migrates.** `user:import` brings jobs to their current shape and
  re-encrypts secrets under the current key; other shapes convert on use. It
  refuses an export newer than `UserExports.VERSION`.

Open questions for #624:

- Whether the check finds older shapes by scanning, or from a record kept as
  they change, and so whether versions are also recorded for each user.
- How long an older shape stays readable once it can be converted on request.

## Migrations in this build

The venue is at version 1.

### Venue steps

| Step | Change | Code |
|------|--------|------|
| 0 → 1 | `w/user-deletions` in the venue user's workspace moves to the `:user-deletions` region | `VenueState.migrateInPlace`, `case 0` |

### Adapter steps

| Adapter | Step | Change | Code |
|---------|------|--------|------|
| `test` | 0 → 1 | None: version 1 has version 0's shape | `TestAdapter.migrateState`, `case 0` |
| `test` | 1 → 2, 2 → 3 | Synthetic, for tests: each appends the version it reaches to `migrations` | `TestAdapter.migrateState`, `case 1`, `case 2` |

### Shapes converted on use

| Older shape | Owner | Converted | Code |
|-------------|-------|-----------|------|
| Adapter state at `w/adapters/<name>` → `:adapters/<name>` | Covia | Before a versioned adapter's steps; otherwise when the adapter's state is first read | `VenueState.migrateLegacyState` |
| Job with a `prev` chain | Covia | Read without the chain; dropped on the job's next write | `Job.currentState` |
| Agent frame with `conversation` or `items` → `history` | Covia | When a session cycle begins | `GoalTreeContext.migrateFrames` |
| Session `loads` → the root frame's `loads` | Covia | When a session cycle begins | `SessionState.migrateLegacySessionLoads` |
| Secret at key version 1 | Covia | Read with the version 1 key; re-encrypted under the current key on its next write | `SecretStore` |
| Public agent with `a2a.caps` | Covia | Read as `accepts: "anyone"`; the stored config is unchanged | `Admission.policy` |
| Created chat bot with no `principal` | Messaging adapters | Read as created by its user; the stored record is unchanged | `AMessagingAdapter.principalOf` |
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
  leaves out the two read as they are and the messaging adapters' shapes. Each
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
