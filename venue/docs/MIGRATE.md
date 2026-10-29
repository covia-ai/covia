# Venue Migration

Venue migration brings a venue's stored state to the schema this build
supports. This document sets out who owns each part of that schema, the older
shapes this build still reads, what remains to build (#624), and how
migration is tested. Version numbers and how to add a venue step are in
[PERSISTENCE.md](PERSISTENCE.md#venue-versions-and-migrations), adapter state
versions in [ADAPTERS.md](ADAPTERS.md#versioned-state-and-migration), and the
calls an embedder makes in [EMBEDDING.md](EMBEDDING.md).

## Principles

- **Each part of the schema has one owner.** Covia owns the venue value and
  every user's jobs, agents, sessions and secrets. Each adapter owns its own
  state and any legacy paths it once wrote, and migrates them with the adapter
  inactive: no bots, no network. An embedder owns its own data and migrates it
  itself, after Covia's migration returns. Covia never runs an embedder's
  steps, and leaves the embedder's values as it found them.
- **A build upgrades any older supported version.** Starting a venue migrates
  it in one transaction, Covia's steps then those of the built-in and
  configured-module adapters, before any other write: all of it commits or
  none of it does. An adapter registered later migrates as it registers.
  Migration is also a call, so an embedder can migrate a store before launch,
  or a running venue at any time.
- **A newer version is refused.** Stored state newer than the build, Covia's or
  an adapter's, fails with `UnsupportedVersionException`, and nothing is
  written.
- **Migrations are pure functions.** A migration takes one part of the venue
  state, such as a user's secrets, a job, a session or an adapter's state, to a
  newer shape. It does no I/O and reads no clock; anything it needs, such as
  the venue's keys, is passed in. A part already current comes back as the
  same cell, so migrating again writes nothing. A shape it does not recognise
  is refused with a reason. Start-up, conversion on use and `user:import` call
  the same function.
- **Some shapes convert lazily.** A version may still read an older shape and
  convert it when the venue next uses it, which keeps start-up cheap. Readers
  normalise the shape, so code beyond the reader sees only the current one. A
  release that drops support for an older shape converts every part still in
  it first.
- **Migration only goes forward.** An older build cannot read upgraded values,
  so a caller that may need to go back keeps the state from before the
  upgrade (#354).

## Older shapes this build reads

The venue is at version 1. Each adapter records its own state version; only
the test adapter versions its state so far, with synthetic steps that show
which steps ran ([ADAPTERS.md](ADAPTERS.md#versioned-state-and-migration)).

| Older shape | Owner | Converted | Where |
|-------------|-------|-----------|-------|
| `w/user-deletions` → `:user-deletions` (venue version 0 → 1) | Covia | At start-up | `VenueState.migrateInPlace` |
| `w/adapters/<name>` → `:adapters/<name>` | Covia, for each adapter | At start-up for a versioned adapter, otherwise on first use | `VenueState.migrateLegacyState` |
| Job `prev` chain (#611) | Covia | Ignored on read, dropped on the next write | `Job.currentState` |
| Agent frames with `conversation`/`items` → `history` | Covia | When a session cycle begins | `GoalTreeContext.migrateFrames` |
| Session `loads` → the root frame's | Covia | When a session cycle begins | `SessionState.migrateLegacySessionLoads` |
| Public agent `a2a.caps` → `accepts: "anyone"` | Covia | Read as the newer shape; the stored config keeps it | `Admission.policy` |
| Created chat bot without `principal` → comes from its user | Messaging adapters | Read as is; one an agent created comes from its owner until created again | `AMessagingAdapter.startRegistry` |
| Secrets at key version 1 (#535) | Covia | On that secret's next write | `SecretStore` |
| Connected accounts as user secrets `oauth/<provider>` → `:adapters/oauth` connections | OAuth adapter | On that user's next use of their connections | `OAuthAdapter.moveLegacyGrants` |
| Telegram session mappings, `w/telegram/sessions/<bot>/<chat>` | Telegram adapter | On first read | `ConversationSessions` |
| Telegram bot registry, `w/telegram/bots` | Telegram adapter | Read at start, filling missing bindings | `TelegramAdapter.rearmLegacyBots` |

`user:import` (#615) applies Covia's migrations to an incoming user, and
refuses an export newer than the build.

## Still to build (#624)

A store can keep the shapes converted on use indefinitely, so the code that
reads them can never be removed, and nothing tells a caller whether any remain.

- **Convert on request.** Each shape above that converts on use becomes a
  migration function that the migration call also applies, so one call leaves
  no older shape, Covia's or a loaded adapter's. An adapter whose module is not
  loaded cannot convert its own parts; the call reports that, rather than
  treating the venue as current.
- **Check.** The read-only check reports any older shapes left, as well as the
  stored versions `VenueState.peekVersions` reads today.

Open questions:

- Whether the check finds older shapes by scanning, or from a record kept as
  they change, and so whether versions are also recorded for each user.
- How long an older shape stays supported once it can be converted on request.

## Testing

- **Built states.** Migration tests build their older states in code. A
  migration function has tests of its own: a current part comes back as the
  same cell, an older part converts and converting it again changes nothing,
  and an unknown shape is refused. A module adapter tests its own shapes in its
  own module, as `TelegramMigrationTest` does.
- **End to end.** `VenueMigrationEndToEndTest` builds one venue at version 0
  holding every shape in the table except Telegram's, with each versioned
  bundled adapter's oldest state, and takes it to the current version in
  process. It checks the start-up conversions after the start-up transaction,
  each later conversion after the venue next uses the shape, and that
  migrating again changes nothing. A new venue version or older shape is
  seeded there, and the test fails until a new version's shapes are.
- **Release-line fixtures.** Each release line (0.9, 0.10, …) leaves one store,
  written by a published jar of that line and never regenerated:
  `covia-0.9.11.zip` and `covia-0.10.0.zip` (see their
  [README](../src/test/resources/migrations/README.md)). Fixtures are big, so
  only the failsafe integration tests `VenueUpgradeIT` and
  `VenueStoreMigrationIT` open them.

## Out of scope

- The Etch store format, which does not change.
- DLFS node shapes. They are Convex's schema, and Convex 0.8.18 reads both
  forms.
- Backups: #354.
