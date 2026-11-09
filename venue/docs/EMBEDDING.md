# Embedding a Venue

For applications that run a Covia venue inside their own process, as GetMine
and Brightside do. This is the supported embedder surface;
`EmbedderContractTest` exercises it, so a change that breaks it fails Covia's
own build. Raw lattice paths and other internals can change between minor
versions; use these entry points instead. An embedder calls these venue
objects directly; the venue's operations are another route, suited to
out-of-process clients and agents, and never required of an embedder.

## Starting and stopping

**A standalone venue.** `VenueServer.launch(config)` opens the store named in
the configuration. `VenueServer.launch(config, store)` adopts a store you have
already opened: the venue closes it when it closes, or if launch fails.

The venue opens a `LatticeHost` over the store and, once the venue is running,
starts the host's sync and flush workers
([PERSISTENCE.md](PERSISTENCE.md#snapshot-implementation-contract)) and the
application's update timer (see Time). It closes in this order:
1. the application, which stops the timer and unmounts the venue: its engine
   stops intake, gives work in flight the shutdown grace window
   ([JOBS.md](JOBS.md#shutdown)) and makes its final flush;
2. the node;
3. the host, whose `close()` stops its workers and makes the final checkpoint;
4. the store.

`getHost()` returns that host. You may checkpoint it, for example before
acknowledging work that must be durable. Never close it yourself.

**A venue on your own host.** Open the application with
`CoviaApplication.open(store)`, or `connect(host)` over a host you already run.
It holds Covia's lattice regions and runs the venues mounted in it, each with
its own key pair. `application.mount(keyPair, config)` mounts a venue and
returns its engine, inert until you `launch()` or `start()` it; mounting a
venue that is already mounted is refused (`CoviaApplication.ALREADY_MOUNTED`).
`application.engine(venueKey)` returns a mounted venue's engine.

Whoever opens a host runs its workers and closes it. Once the venue is running,
start `host.autoSync(VenueServer.SYNC_INTERVAL, application::sync)` and
`host.autoFlush(VenueServer.FLUSH_INTERVAL)`, or intervals of your own; without
them the venue publishes and saves only when it flushes or closes. Whoever
opens the application likewise decides when scheduled events fire: start
`application.autoUpdate()`, or step the application yourself (see Time);
without either, no scheduled event, agent wake or HITL timeout fires.

`application.unmount(venueKey)` closes the venue's engine, which makes its
final flush, signs the venue's copy into its record and forgets it; closing
the engine does the same, and the venue can then be mounted again.
`application.close()` stops the update timer and unmounts every venue. Neither
closes the host or the store. So close the application, then `application.host().close()`, which
stops the workers, then the store.

**The venue's state.** The application holds one working copy of each mounted
venue: `application.venue(venueKey)` returns it, and the venue's engine works
on the same copy. Edits to it sign nothing until `application.sync()` signs the
venue's record; `application.host().sync()` then publishes the root. A running
engine does both. Code that edits the venue with no engine running calls them
itself. `open(store, keyPair)`, `connect(host, keyPair)` and
`create(keyPair)` mount that venue with the default configuration, for such
code; mount it yourself to give it a configuration.

**JVM shutdown.** Register your shutdown work with
`Shutdown.addHook(Shutdown.SERVICE, task)` (`convex.core.util.Shutdown`). It
runs before Convex closes its servers and Etch files. `MainVenue` registers
there.

**Before starting.** `VenueState.peekVenueKeys(store)` reads the venue keys
recorded in an open store, and `VenueState.peekVersions(store, venueKey)` its
schema versions, Covia's and each adapter's, without starting a venue.

**Migrating.** Migration is a call, not only a start-up event:
- `engine.migrate()` migrates the venue and every installed adapter's state,
  enabled or disabled, in one transaction, at any time.
- Before launch, open the application over the store and call
  `application.venue(venueKey).migrate(SecretStore.keys(venueKeyPair), adapters)`
  with your adapters' `stateMigration()`, then `application.sync()` and
  `application.host().sync()`. The venue's secret store keys let a step read
  what it carries over from the venue's secrets. Launch then finds the state
  current.
- Launch itself migrates the venue with the built-in and configured-module
  adapters, and an adapter you register later migrates as it registers.
- Stored state newer than the build fails with `UnsupportedVersionException`,
  with nothing written. From `VenueServer.launch` it is the cause of the
  exception thrown: walk `getCause()`.

Covia never runs your own migration steps: run them after Covia's, in your
own code. See [MIGRATE.md](MIGRATE.md).

## Time

The venue's clock belongs to the application's policy. Give one to
`CoviaApplication.create(policy)`, `open(store, policy)` or
`connect(host, policy)` as `VenueContext.create(clock)`, where `clock` supplies
the time in epoch milliseconds as a `CVMLong`. Without one the application uses
the system clock (`VenueContext.SYSTEM`). Every venue mounted in the
application shares it.

Every timestamp the venue and its adapters write comes from that clock: jobs,
agents, scheduled events, HITL requests, user records, DLFS file times and
message receipts. So does every check against such a timestamp, such as an
agent's wake time, a request's expiry or a connected account's token expiry.
`engine.currentTimestamp()` reads it, for your own code writing to the same
lattice. Elapsed time stays on the system timer: sync and flush intervals,
shutdown grace, timeouts, retries, rate limits and in-memory caches.

Tokens follow the same clock. The venue judges the times in every token and
proof presented to it by its clock, and issues its own tokens at its time, so
a venue on a driven clock accepts only tokens minted on that clock. A client
minting for it passes the clock to `VenueAuth.namedKeyPair(keyPair, did,
clock)`.

The clock may stand still. It may give several writes the same time, and a
test may hold it or step it by zero: a local write overwrites the venue's own
state, and a merge on an equal timestamp keeps the local value, so no write is
lost to a tie.

The clock may step back, as a host's clock can. New writes are stamped with
the earlier time, except that a stamp a later write has to beat in a merge
(`updated`, and the venue's and an agent's timestamp) stays at its latest
value, so no write is lost. Job IDs keep increasing, jobs already running
carry on, and a scheduled event waits until the clock reads its time.

Scheduled events fire when the application is updated.
`application.update()` reads the clock once and starts, in each mounted venue,
the events due by then: scheduled operations, agent wakes and HITL timeouts.
Its future completes when each has started (its operation's invocation has
returned) and gives their Jobs; how each resolves is its own, so wait on the
Job for that. A step that leaves the time unchanged still starts what is due,
such as an event added since with a past time.
`application.autoUpdate()` starts a timer that updates the application
whenever a venue has something due, and returns it: `failure()` reports its
first failure and `close()` stops it, as does closing the application. One
runs at a time (`CoviaApplication.AUTO_UPDATING`). It waits in real time for
the clock's distance to the next event, so on a clock you drive, step the
application yourself rather than starting it. How many events a venue starts
at once is `scheduler.maxConcurrency` ([CONFIG.md](CONFIG.md)).

`DrivenClock` is a clock you move yourself. `DrivenClock.at(instant)` creates
one, `policy()` gives the policy for the application, and `set(millis)` and
`advance(ms)` move it, in either direction.
The clock only holds the time: moving it starts nothing, so update the
application after each step. To mint tokens on the same time, pass
`clock::millis` as the clock above.

```java
DrivenClock clock = DrivenClock.at(Instant.parse("2026-01-01T00:00:00Z"));
CoviaApplication application = CoviaApplication.create(clock.policy());
Engine engine = application.mount(AKeyPair.generate(), config).launch();
// ... act, then check what the venue wrote at that time
clock.advance(60_000);              // one minute later
List<Job> started = application.update().get(); // start what is due by then
for (Job job : started) job.awaitResult();      // when the test needs them finished
```

`Engine.createTemp(clock, config)` does this setup in one call: an in-memory
venue on `clock` with no timer, updated with `engine.application().update()`.
`Engine.createTemp(config)` is the same on a clock held at the current time, so
nothing fires unless you update it. `Engine.createRealTime(config)` uses the
system clock and starts the timer, for integration and smoke tests.

## Routes

Routes you add with `VenueServer.launch(config, extraRoutes)` share the
venue's server; its javadoc says how they opt into the venue's policy.

A filter of yours that admits or refuses requests keys on the route a request
matched, never on `ctx.path()`. Routing ignores a trailing slash, so
`/app/admin/` reaches a route registered as `/app/admin`, while `ctx.path()` is
the path as sent. Register the filter with `beforeMatched` and read the matched
route's roles, `ctx.routeRoles()`, as the venue's own admission does, or the
path it was registered with, `ctx.endpoints().matchedHttpEndpoint().path`.

## Adapters

Register your own adapters with `engine.registerAdapter(adapter)` on a
prepared engine, in the launch hook or later. An adapter keeps its private
state in `engine.adapterState(name)`, and owns that state's schema
([ADAPTERS.md](ADAPTERS.md)): registering migrates it through the adapter's
`stateSteps()`.

**To switch an adapter off, disable it; don't remove it.**
- `engine.disableAdapter(name)` stops dispatch, but the adapter still runs its
  user-deletion hooks. Messaging bindings stop handling messages, and
  Telegram's bots make no calls out.
- A removed adapter cannot clean up after a deleted user. `user:delete` then
  revokes the user, fails with `Engine.USER_DELETION_PENDING`, and leaves the
  adapter's data until an adapter of that name is active again.

**The venue jar sets the JVM's name resolver.** It registers
`covia.adapter.GuardedResolver` as an `InetAddressResolverProvider`, the
outbound guard's connect-time half. It answers every lookup as the JDK's own
resolver does, except for hosts the guard has passed, which it holds to the
guard's address rule. If your application ships its own provider, or sets
`jdk.net.hosts.file`, the guard passes only allow-listed hosts and the `http`
adapter logs `HTTPAdapter.RESOLVER_INACTIVE` when installed. Keep the service
file when you repackage the jar.

## User data

Find a user with `engine.getVenueState().users().ensure(did)`, or with
`user(did)`, which is null for a user who is unknown or deleted. These components check no authority: they act for whatever code calls them,
so the application decides who reaches them.

- **Workspace.** `user.workspace()` is the user's workspace (`w`). Its
  `read`, `write`, `append`, `update` and `delete` take a path (a Convex
  `Path`) inside the workspace, such as `CoviaPath.parse("app/record")`. A change creates no
  missing level: `createPath` makes them, and a write below a missing level is
  refused (`NotFoundException`). When several changes must land together, wrap
  them in a transaction, which applies all or nothing and is retried on a
  conflict (`transact` in place of `execute` returns what the body returned in
  the run that committed):
  ```java
  user.execute(draft -> {
      Workspace workspace = draft.workspace();
      workspace.createPath(CoviaPath.parse("app"));
      workspace.write(CoviaPath.parse("app/record"), record);
      workspace.write(CoviaPath.parse("app/stamp"), stamp);
  });
  ```
  Keep I/O out of the body: it may run more than once.
- **Secrets.** Use `user.secrets().store(name, value, keys)` and
  `decrypt(name, keys)`, with `keys = SecretStore.keys(engine.getKeyPair())`.
- **Agent conversations.** Read a session frame through
  `GoalTreeContext.archivedConversation(frame)`, never a frame field directly.
  It returns the same nested shape for legacy and versioned frames
  ([AGENT_SESSIONS.md §5.2.1](AGENT_SESSIONS.md#521-embedded-callers-and-storage-compatibility)).

## Identity

A `did:key` installation can sign for identities nested below it, such as
`<did:key>:u:<name>`. To keep its private key inside the venue, store the seed
as one of the installation's secrets, run `crypto:add-key {"seed":"s/<name>"}`
as the installation, then delete the secret. The venue then signs identity
tokens for the installation and every identity nested below it. To get a
user's token, invoke `auth:identity-token` as that user
(`engine.context(userDID)`): the token names that user and is stored in their
secret store, where `secrets().decrypt` reads it (see Secrets above). An
application that keeps the key itself signs with `VenueAuth.namedKeyPair`. See
AUTH.md, "Nested installation identities below a did:key" and "Minting an
identity token through an operation", and HELD_KEYS.md.

## Upgrading from 0.10.0

| 0.10.0 | Current |
|--------|---------|
| `user.workspaceRoot()` with keys from `WorkspaceRoot.parseStringPath("w/...")` | `user.workspace()` with a path inside `w` from `CoviaPath.parse` (see User data) |
| `workspaceRoot().write` creating missing levels | `createPath` for them first, in the same transaction |
| `VenueState.migrate(adapters)`, `migrate()` | `migrate(SecretStore.keys(venueKeyPair), adapters)`, `migrate(keys)` |
| The engine publishing and flushing its host in the background | Whoever opens the host starts `host.autoSync` and `host.autoFlush` (see "A venue on your own host"); `VenueServer` does |
| `new Engine(config, application, keyPair)` | `application.mount(keyPair, config)` on an application from `open(store)`, `connect(host)` or `create()`; `Engine`'s constructors are no longer public |
| `CoviaApplication.create(context)` or `connect(host, context)` with a context from `withSigner(keyPair)` | Pass the context without a signer, then `mount(keyPair, config)`; a context with a signer is refused (`CoviaApplication.POLICY_SIGNS`) |
| `CoviaApplication.CANNOT_SIGN` | `CoviaApplication.NOT_MOUNTED`, for a venue that is not mounted |
| Several engines in turn over one application | Close (or `unmount`) the venue's engine, then mount it again |
| Closing the engine, then the host | Close the application (or the engine), then the host |
| `AuthMiddleware.withTransportGrants(ctx, ucans[, verifier])` | `engine.withTransportGrants(ctx, ucans)`, which verifies with the venue's DID verifier and clock |
| Scheduled events, agent wakes and HITL timeouts firing by themselves on an engine you mounted | Whoever opens the application starts `application.autoUpdate()` or calls `application.update()` (see Time); `VenueServer` and `Engine.createTemp` start the timer |

## Upgrading from 0.9.x

0.10.0 moves stored values to a new schema that 0.9.x cannot read. Keep the
state from before the upgrade if you may need to go back; see
[MIGRATE.md](MIGRATE.md). Recompile against 0.10.0. Some changes compile
unchanged but fail at runtime against jars built for 0.9.x.

| 0.9.x | 0.10.0 |
|-------|--------|
| `Engine` constructors taking a cursor | `new Engine(config, CoviaApplication, keyPair)`, with `CoviaApplication.open(store, keyPair)` or `connect(host, keyPair)` |
| `getRootCursor()` and `User.cursor()` returning `ALatticeCursor` | They return `AForkableCursor`. Source-compatible, but binary-incompatible: recompile |
| `VenueState.fromRoot(cursor, ownerKey)` | `application.venue(ownerKey)`, or `fromRoot(AForkableCursor, AccountKey, signer, VenueContext)` for a standalone copy that reaches the root when you `sync()` it |
| `CoviaAdapter.writePathToCursor`, `CoviaAdapter.parseStringPath` | `user.workspace()` inside `user.transact` (see User data) |
| `WrapperLattice`, `Covia.child`, `Engine.connectDLFSDrive` | Removed |
| A job record's `prev` chain, `Job.getPreviousState` | Gone: a job record is its current state |
| A frame's `conversation` field | `GoalTreeContext.archivedConversation(frame)` |
| `SecretStore.deriveKey`, the `byte[]` `store` and `storeIfAbsent` | `SecretStore.keys(keyPair)` |
| `NodeServer` saving on close | It saves nothing; follow the close order above |
| `engine.removeAdapter` to switch an adapter off | `engine.disableAdapter` |
| Convex 0.8.17, Javalin 7.2.3, MCP Java SDK 2.0.1, Logback 1.6.5 | Convex 0.8.18, Javalin 7.2.4, MCP Java SDK 2.1.0, Logback 1.6.6 |

Javalin 7.2.4's `filename()` for an uploaded file drops any folder part of the
name.
