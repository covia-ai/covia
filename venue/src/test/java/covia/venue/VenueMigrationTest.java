package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Path;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Principals;
import covia.grid.Status;
import covia.lattice.Covia;
import covia.lattice.CoviaPath;
import covia.lattice.Namespace;

class VenueMigrationTest {
	/** The venue's secret store keys: these venues keep no secret a migration step carries over. */
	private static final SecretStore.Keys NO_SECRETS = SecretStore.keys(AKeyPair.generate());
	private static final AString DID = Strings.create("did:test:migrations");
	private static final Path OLD_QUEUE = CoviaPath.parse("w/user-deletions");
	private static final Path OLD_ADAPTER =CoviaPath.parse("w/adapters/unloaded");
	private static final ACell PENDING = Maps.of("unloaded", Maps.of("did:test:deleted", CVMLong.ONE));

	private static VenueState legacy() {
		VenueState venue = VenueState.create(AKeyPair.generate());
		venue.cursor().set(Index.<Keyword, ACell>none().assoc(Covia.DID, DID));
		venue.users().ensure(DID).writeInternalPath(OLD_QUEUE, PENDING);
		venue.users().ensure(DID).writeInternalPath(OLD_ADAPTER, Vectors.of("opaque", 42L));
		return venue;
	}

	@Test void freshAndExistingVenuesUseTheSameMigrationEntryPoint() {
		VenueState fresh = VenueState.create(AKeyPair.generate());
		assertEquals(0, fresh.version());
		fresh.initialise(DID, NO_SECRETS);
		assertEquals(VenueState.CURRENT_VERSION, fresh.version());
		assertEquals(CVMLong.create(VenueState.CURRENT_VERSION), RT.getIn(fresh.get(), Covia.VERSION));

		VenueState venue = legacy();
		assertEquals(0, venue.version());
		venue.initialise(Strings.create("did:test:ignored"), NO_SECRETS);
		assertEquals(DID, RT.getIn(venue.get(), Covia.DID));
		assertEquals(VenueState.CURRENT_VERSION, venue.version());
		assertEquals(PENDING, RT.getIn(venue.get(), Covia.USER_DELETIONS));
		assertNull(venue.users().user(DID).readInternalPath(OLD_QUEUE));
		assertEquals(Vectors.of("opaque", 42L), venue.users().user(DID).readInternalPath(OLD_ADAPTER));
		assertNull(RT.getIn(venue.get(), Covia.ADAPTERS), "migration must not scan adapter records");
		ACell before = venue.get();
		venue.migrate(NO_SECRETS);
		venue.initialise(DID, NO_SECRETS);
		assertSame(before, venue.get(), "current state is neither rewritten nor re-stamped");
		assertEquals(Vectors.of("opaque", 42L), venue.adapterState("unloaded").get());
	}

	@Test void explicitZeroUsesTheLegacyMigration() {
		VenueState venue = legacy();
		VenueState.level(venue.cursor(), Covia.VERSION).set(CVMLong.ZERO);
		venue.migrate(NO_SECRETS);
		assertEquals(VenueState.CURRENT_VERSION, venue.version());
		assertEquals(PENDING, RT.getIn(venue.get(), Covia.USER_DELETIONS));
	}

	/** A store a snapshot took to version 3, before step 3 → 4's parts were in step 2 → 3, still receives them. */
	@Test void aStoreAtVersionThreeTakesStepFourAndACurrentOneComesThroughAsItWas() {
		AString alice = Strings.create("did:test:alice");
		AString worker = Principals.agentDID(alice, Strings.create("worker"));
		Blob own = Blob.fromHex("01".repeat(16)), agents = Blob.fromHex("02".repeat(16));
		VenueState venue = VenueState.create(AKeyPair.generate());
		venue.cursor().set(Index.<Keyword, ACell>none().assoc(Covia.DID, DID).assoc(Covia.VERSION, CVMLong.create(3))
			.assoc(Covia.USER_DATA, Maps.of(alice, Maps.of(Namespace.J, Index.<Blob, ACell>none()
				.assoc(own, Maps.of(Fields.STATUS, Status.STARTED, Fields.CALLER, alice))
				.assoc(agents, Maps.of(Fields.STATUS, Status.STARTED, Fields.CALLER, worker, Fields.OWNER, alice)))))
			.assoc(Covia.JOBS, Index.<Blob, ACell>none()
				.assoc(own, Maps.of(JobIndex.OWNER, alice, JobIndex.CONTEXT, Maps.of(Fields.JOB_ID, own)))));

		venue.migrate(NO_SECRETS);
		assertEquals(VenueState.CURRENT_VERSION, venue.version());
		assertEquals(Maps.of(Fields.STATUS, Status.STARTED), RT.getIn(venue.get(), Covia.USER_DATA, alice, Namespace.J, own),
			"a record names one caller, left out where it is the account");
		assertEquals(Maps.of(Fields.STATUS, Status.STARTED, Fields.CALLER, worker),
			RT.getIn(venue.get(), Covia.USER_DATA, alice, Namespace.J, agents));
		assertEquals(Maps.of(Fields.FRAME, Maps.of(Fields.AUTHORITY, alice), Fields.JOB_ID, own),
			RT.getIn(venue.get(), Covia.JOBS, own, JobIndex.CONTEXT), "unfinished work keeps its state");

		ACell current = venue.get();
		VenueState.level(venue.cursor(), Covia.VERSION).set(CVMLong.create(3));
		venue.migrate(NO_SECRETS);
		assertEquals(current, venue.get(), "a store already in the current shape comes through as it was");
	}

	@Test void migrationConflictPreservesVersionAndBothCopies() {
		VenueState venue = legacy();
		VenueState.level(venue.cursor(), Covia.USER_DELETIONS).set(Maps.of("conflicting", true));
		ACell before = venue.get();
		var error = assertThrows(IllegalStateException.class, () -> venue.initialise(DID, NO_SECRETS));
		assertEquals(covia.venue.migration.UserDeletionsRegion.CONFLICT, error.getMessage());
		assertSame(before, venue.get());
		assertEquals(0, venue.version());
	}

	@Test void migrationRollsBackWithItsContainingTransaction() {
		VenueState venue = legacy();
		ACell before = venue.get();
		assertThrows(IllegalArgumentException.class, () -> venue.execute(draft -> {
			draft.migrate(NO_SECRETS);
			assertEquals(VenueState.CURRENT_VERSION, draft.version());
			throw new IllegalArgumentException("test rollback");
		}));
		assertSame(before, venue.get());
	}

	@Test void concurrentLegacyChangesRetryTheMigrationAndVersionTogether() throws Exception {
		VenueState venue = legacy();
		CountDownLatch staged = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicInteger attempts = new AtomicInteger();
		ACell latest = Maps.of("unloaded", Maps.of("did:test:deleted", CVMLong.create(2)));
		try {
			var migration = CompletableFuture.runAsync(() -> venue.execute(draft -> {
				draft.migrate(NO_SECRETS);
				if (attempts.incrementAndGet() == 1) {
					staged.countDown();
					try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
					catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
				}
			}));
			assertTrue(staged.await(5, TimeUnit.SECONDS));
			assertEquals(0, venue.version(), "staged version is not yet visible");
			venue.users().user(DID).writeInternalPath(OLD_QUEUE, latest);
			release.countDown();
			migration.get(5, TimeUnit.SECONDS);
			assertTrue(attempts.get() >= 2);
			assertEquals(VenueState.CURRENT_VERSION, venue.version());
			assertEquals(latest, RT.getIn(venue.get(), Covia.USER_DELETIONS));
			assertNull(venue.users().user(DID).readInternalPath(OLD_QUEUE));
		} finally { release.countDown(); }
	}

	@Test void startupRejectsMalformedOrNewerVersionsBeforeAnyWrites() throws Exception {
		for (ACell version : new ACell[] {null, CVMLong.create(-1), Strings.create("1"),
				CVMDouble.create(1), CVMLong.create(VenueState.CURRENT_VERSION + 1)}) {
			ACell record = Index.<Keyword, ACell>none().assoc(Covia.VERSION, version);
			boolean newer = version instanceof CVMLong number && number.longValue() > VenueState.CURRENT_VERSION;
			String reason = newer
				? VenueState.UNSUPPORTED_VERSION.formatted(VenueState.CURRENT_VERSION + 1, VenueState.CURRENT_VERSION)
				: VenueState.INVALID_VERSION;
			IllegalStateException error = assertStartupRejected(record, reason);
			assertEquals(newer, error instanceof UnsupportedVersionException);
			if (error instanceof UnsupportedVersionException unsupported) {
				assertNull(unsupported.adapter(), "the venue's own version names no adapter");
				assertEquals(VenueState.CURRENT_VERSION + 1, unsupported.stored());
				assertEquals(VenueState.CURRENT_VERSION, unsupported.supported());
			}
		}
	}

	@Test void storedVersionsAreReadWithoutWriting() throws Exception {
		AKeyPair key = AKeyPair.generate();
		CoviaApplication application = TestEngine.application(key);
		try (var store = application.hostStore()) {
			assertTrue(VenueState.peekVersions(store, key.getAccountKey()).isEmpty(), "a fresh store holds no venue");
			ACell record = Index.<Keyword, ACell>none()
				.assoc(Covia.VERSION, CVMLong.create(VenueState.CURRENT_VERSION + 1))
				.assoc(Covia.ADAPTER_VERSIONS, Maps.of("telegram", 2L, "example", 7L));
			application.venue(key.getAccountKey()).cursor().set(record);
			application.sync();
			application.host().sync();
			ACell before = store.getRootData();

			var versions = VenueState.peekVersions(store, key.getAccountKey()).orElseThrow();
			assertEquals(VenueState.CURRENT_VERSION + 1, versions.venue(), "a newer version is reported, not refused");
			assertEquals(java.util.Map.of("example", 7L, "telegram", 2L), versions.adapters());
			assertTrue(VenueState.peekVersions(store, AKeyPair.generate().getAccountKey()).isEmpty());
			assertEquals(before, store.getRootData(), "peeking must leave the store untouched");
		}
	}

	@Test void malformedRawStateCannotBeSilentlyReinitialised() {
		for (ACell value : new ACell[] {Vectors.of("bad root"), Maps.of("version", 1)}) {
			VenueState venue = VenueState.create(AKeyPair.generate())
				.rebind(convex.lattice.cursor.Cursors.root(value));
			assertEquals(VenueState.INVALID_STATE,
				assertThrows(IllegalStateException.class, () -> venue.initialise(DID, NO_SECRETS)).getMessage());
			assertSame(value, venue.get());
		}
	}

	private static IllegalStateException assertStartupRejected(ACell record, String reason) throws Exception {
		AKeyPair key = AKeyPair.generate();
		CoviaApplication application = TestEngine.application();
		try (var store = application.hostStore()) {
			Engine engine = application.mount(key, Maps.empty());
			application.venue(key.getAccountKey()).cursor().set(record);
			application.sync();
			application.host().sync();
			ACell before = application.cursor().get();
			IllegalStateException error;
			try {
				error = assertThrows(IllegalStateException.class, engine::prepare);
				assertEquals(reason, error.getMessage());
				assertNull(engine.getVenueState());
			} finally { engine.close(); }
			assertEquals(before, application.cursor().get(), "failed startup must not stamp, initialise or bootstrap");
			assertEquals(before, store.getRootData(), "failed startup must leave persisted state untouched");
			return error;
		}
	}

	@Test void eachVenueOwnsItsVersionAndUnknownFieldsSurviveMigration() {
		AKeyPair key = AKeyPair.generate();
		CoviaApplication application = TestEngine.application(key);
		VenueState local = application.venue(key.getAccountKey());
		AKeyPair peerKey = AKeyPair.generate();
		VenueState peer = VenueState.fromRoot(application.cursor(), peerKey.getAccountKey(),
			peerKey::signData, application.venueContext());
		peer.cursor().set(Index.<Keyword, ACell>none().assoc(Covia.VERSION, CVMLong.create(99)));
		peer.sync();
		ACell peerBefore = peer.get();
		Keyword extension = Keyword.intern("extension");
		local.cursor().set(Index.<Keyword, ACell>none().assoc(extension, Vectors.of("keep")));
		local.initialise(DID, NO_SECRETS);
		application.sync();
		assertEquals(Vectors.of("keep"), RT.getIn(local.get(), extension));
		assertEquals(VenueState.CURRENT_VERSION, local.version());
		VenueState published = VenueState.fromRoot(application.cursor(), peerKey.getAccountKey(),
			peerKey::signData, application.venueContext());
		assertEquals(peerBefore, published.get());
		assertThrows(IllegalStateException.class, () -> published.adapterState("unloaded"));
		assertEquals(peerBefore, published.get());
	}
}
