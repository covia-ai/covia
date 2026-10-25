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
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.lattice.Covia;
import covia.lattice.WorkspaceRoot;

class VenueMigrationTest {
	private static final AString DID = Strings.create("did:test:migrations");
	private static final ACell[] OLD_QUEUE = WorkspaceRoot.parseStringPath("w/user-deletions");
	private static final ACell[] OLD_ADAPTER = WorkspaceRoot.parseStringPath("w/adapters/unloaded");
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
		fresh.initialise(DID);
		assertEquals(VenueState.CURRENT_VERSION, fresh.version());
		assertEquals(CVMLong.create(VenueState.CURRENT_VERSION), RT.getIn(fresh.get(), Covia.VERSION));

		VenueState venue = legacy();
		assertEquals(0, venue.version());
		venue.initialise(Strings.create("did:test:ignored"));
		assertEquals(DID, RT.getIn(venue.get(), Covia.DID));
		assertEquals(VenueState.CURRENT_VERSION, venue.version());
		assertEquals(PENDING, RT.getIn(venue.get(), Covia.USER_DELETIONS));
		assertNull(venue.users().get(DID).readInternalPath(OLD_QUEUE));
		assertEquals(Vectors.of("opaque", 42L), venue.users().get(DID).readInternalPath(OLD_ADAPTER));
		assertNull(RT.getIn(venue.get(), Covia.ADAPTERS), "migration must not scan adapter records");
		ACell before = venue.get();
		venue.migrate();
		venue.initialise(DID);
		assertSame(before, venue.get(), "current state is neither rewritten nor re-stamped");
		assertEquals(Vectors.of("opaque", 42L), venue.adapterState("unloaded").get());
	}

	@Test void explicitZeroUsesTheLegacyMigration() {
		VenueState venue = legacy();
		venue.cursor().path(Covia.VERSION).set(CVMLong.ZERO);
		venue.migrate();
		assertEquals(VenueState.CURRENT_VERSION, venue.version());
		assertEquals(PENDING, RT.getIn(venue.get(), Covia.USER_DELETIONS));
	}

	@Test void migrationConflictPreservesVersionAndBothCopies() {
		VenueState venue = legacy();
		venue.cursor().path(Covia.USER_DELETIONS).set(Maps.of("conflicting", true));
		ACell before = venue.get();
		var error = assertThrows(IllegalStateException.class, () -> venue.initialise(DID));
		assertEquals(VenueState.LEGACY_STATE_CONFLICT + "w/user-deletions", error.getMessage());
		assertSame(before, venue.get());
		assertEquals(0, venue.version());
	}

	@Test void migrationRollsBackWithItsContainingTransaction() {
		VenueState venue = legacy();
		ACell before = venue.get();
		assertThrows(IllegalArgumentException.class, () -> venue.transact(draft -> {
			draft.migrate();
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
			var migration = CompletableFuture.runAsync(() -> venue.transact(draft -> {
				draft.migrate();
				if (attempts.incrementAndGet() == 1) {
					staged.countDown();
					try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
					catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
				}
			}));
			assertTrue(staged.await(5, TimeUnit.SECONDS));
			assertEquals(0, venue.version(), "staged version is not yet visible");
			venue.users().get(DID).writeInternalPath(OLD_QUEUE, latest);
			release.countDown();
			migration.get(5, TimeUnit.SECONDS);
			assertTrue(attempts.get() >= 2);
			assertEquals(VenueState.CURRENT_VERSION, venue.version());
			assertEquals(latest, RT.getIn(venue.get(), Covia.USER_DELETIONS));
			assertNull(venue.users().get(DID).readInternalPath(OLD_QUEUE));
		} finally { release.countDown(); }
	}

	@Test void startupRejectsMalformedOrNewerVersionsBeforeAnyWrites() throws Exception {
		for (ACell version : new ACell[] {null, CVMLong.create(-1), Strings.create("1"),
				CVMDouble.create(1), CVMLong.create(VenueState.CURRENT_VERSION + 1)}) {
			ACell record = Index.<Keyword, ACell>none().assoc(Covia.VERSION, version);
			String reason = version instanceof CVMLong number && number.longValue() > VenueState.CURRENT_VERSION
				? VenueState.UNSUPPORTED_VERSION.formatted(number.longValue(), VenueState.CURRENT_VERSION)
				: VenueState.INVALID_VERSION;
			assertStartupRejected(record, reason);
		}
	}

	@Test void malformedRawStateCannotBeSilentlyReinitialised() {
		for (ACell value : new ACell[] {Vectors.of("bad root"), Maps.of("version", 1)}) {
			VenueState venue = VenueState.create(AKeyPair.generate())
				.withCursor(convex.lattice.cursor.Cursors.root(value));
			assertEquals(VenueState.INVALID_STATE,
				assertThrows(IllegalStateException.class, () -> venue.initialise(DID)).getMessage());
			assertSame(value, venue.get());
		}
	}

	private static void assertStartupRejected(ACell record, String reason) throws Exception {
		AKeyPair key = AKeyPair.generate();
		CoviaApplication application = CoviaApplication.create(key);
		try (var store = application.hostStore()) {
			application.venue(key.getAccountKey()).cursor().set(record);
			application.host().sync();
			ACell before = application.cursor().get();
			Engine engine = new Engine(Maps.empty(), application, key);
			try {
				var error = assertThrows(IllegalStateException.class, engine::prepare);
				assertEquals(reason, error.getMessage());
				assertNull(engine.getVenueState());
			} finally { engine.close(); }
			assertEquals(before, application.cursor().get(), "failed startup must not stamp, initialise or bootstrap");
			assertEquals(before, store.getRootData(), "failed startup must leave persisted state untouched");
		}
	}

	@Test void eachVenueOwnsItsVersionAndUnknownFieldsSurviveMigration() {
		AKeyPair key = AKeyPair.generate();
		CoviaApplication application = CoviaApplication.create(key);
		VenueState local = application.venue(key.getAccountKey());
		AKeyPair peerKey = AKeyPair.generate();
		application.setContext(application.getContext().withSigningKey(peerKey));
		VenueState peer = application.venue(peerKey.getAccountKey());
		peer.cursor().set(Index.<Keyword, ACell>none().assoc(Covia.VERSION, CVMLong.create(99)));
		ACell peerBefore = peer.get();
		Keyword extension = Keyword.intern("extension");
		local.cursor().set(Index.<Keyword, ACell>none().assoc(extension, Vectors.of("keep")));
		local.initialise(DID);
		assertEquals(Vectors.of("keep"), RT.getIn(local.get(), extension));
		assertEquals(VenueState.CURRENT_VERSION, local.version());
		assertEquals(peerBefore, peer.get());
		assertThrows(IllegalStateException.class, () -> peer.adapterState("unloaded"));
		assertEquals(peerBefore, peer.get());
	}
}
