# Embedding a Venue

For applications that run a Covia venue inside their own process, as GetMine
and Brightside do. This is the supported embedder surface;
`EmbedderContractTest` exercises it, so a change that breaks it fails Covia's
own build. Raw lattice paths and other internals can change between minor
versions; use these entry points instead.

## Starting and stopping

**A standalone venue.** `VenueServer.launch(config)` opens the store named in
the configuration. `VenueServer.launch(config, store)` adopts a store you have
already opened: the venue closes it when it closes, or if launch fails.

The venue opens a `LatticeHost` over the store, and closes in this order:
1. the engine, which stops intake and makes its final flush;
2. the node;
3. the host, whose `close()` makes the final checkpoint;
4. the store.

`getHost()` returns that host. You may checkpoint it, for example before
acknowledging work that must be durable. Never close it yourself.

**An engine on your own host.** Open the application with
`CoviaApplication.open(store, keyPair)`, then run
`new Engine(config, application, keyPair).start()`. `Engine.close()` makes its
final flush but never closes the host or the store: whoever opens a host
closes it. So close the engine, then `application.host().close()`, then the
store.

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
  `application.venue(venueKey).migrate(adapters)` with your adapters'
  `stateMigration()`. Launch then finds the state current.
- Launch itself migrates the venue with the built-in and configured-module
  adapters, and an adapter you register later migrates as it registers.
- Stored state newer than the build fails with `UnsupportedVersionException`,
  with nothing written. From `VenueServer.launch` it is the cause of the
  exception thrown: walk `getCause()`.

Covia never runs your own migration steps: run them after Covia's, in your
own code. See [MIGRATE.md](MIGRATE.md).

## Adapters

Register your own adapters with `engine.registerAdapter(adapter)` on a
prepared engine, in the launch hook or later. An adapter keeps its private
state in `engine.adapterState(name)`, and owns that state's schema
([ADAPTERS.md](ADAPTERS.md)): registering migrates it to the adapter's
`stateVersion()`.

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

Find a user with `engine.getVenueState().users().ensure(did)` or `get(did)`.

- **Workspace.** Use `user.workspaceRoot()`: `read`, `write` and `delete`,
  with keys from `WorkspaceRoot.parseStringPath("w/...")`. When several
  writes must land together, wrap them in a transaction, which applies all or
  nothing and is retried on a conflict:
  ```java
  user.transact(draft -> {
      draft.workspaceRoot().write(WorkspaceRoot.parseStringPath("w/app/record"), record);
      draft.workspaceRoot().write(WorkspaceRoot.parseStringPath("w/app/stamp"), stamp);
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
`<did:key>:u:<name>`. It issues them with `VenueAuth.namedKeyPair`, and a
caller can mint a token for itself with `auth:identity-token`. See AUTH.md,
"Nested installation identities below a did:key".

## Upgrading from 0.9.x

0.10.0 moves stored values to a new schema that 0.9.x cannot read. Keep the
state from before the upgrade if you may need to go back; see
[MIGRATE.md](MIGRATE.md). Recompile against 0.10.0. Some changes compile
unchanged but fail at runtime against jars built for 0.9.x.

| 0.9.x | 0.10.0 |
|-------|--------|
| `Engine` constructors taking a cursor | `new Engine(config, CoviaApplication, keyPair)`, with `CoviaApplication.open(store, keyPair)` or `connect(host)` |
| `getRootCursor()` and `User.cursor()` returning `ALatticeCursor` | They return `AForkableCursor`. Source-compatible, but binary-incompatible: recompile |
| `VenueState.fromRoot(cursor, ownerKey)` | `fromRoot(AForkableCursor, AccountKey, LatticeContext)` |
| `CoviaAdapter.writePathToCursor`, `CoviaAdapter.parseStringPath` | `workspaceRoot().write` inside `user.transact`; `WorkspaceRoot.parseStringPath` |
| `WrapperLattice`, `Covia.child`, `Engine.connectDLFSDrive` | Removed |
| A job record's `prev` chain, `Job.getPreviousState` | Gone: a job record is its current state |
| A frame's `conversation` field | `GoalTreeContext.archivedConversation(frame)` |
| `SecretStore.deriveKey`, the `byte[]` `store` and `storeIfAbsent` | `SecretStore.keys(keyPair)` |
| `NodeServer` saving on close | It saves nothing; follow the close order above |
| `engine.removeAdapter` to switch an adapter off | `engine.disableAdapter` |
| Convex 0.8.17, Javalin 7.2.3, MCP Java SDK 2.0.1, Logback 1.6.5 | Convex 0.8.18, Javalin 7.2.4, MCP Java SDK 2.1.0, Logback 1.6.6 |

Javalin 7.2.4's `filename()` for an uploaded file drops any folder part of the
name.
