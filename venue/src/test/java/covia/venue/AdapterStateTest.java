package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.exception.AuthException;
import covia.lattice.Covia;
import covia.lattice.WorkspaceRoot;

class AdapterStateTest {
	private static final AString HOST = Strings.create("did:test:venue");
	private static final AString OWNER = Strings.create("did:test:owner");

	private static VenueState venue() {
		VenueState venue = VenueState.create(AKeyPair.generate());
		venue.initialise(HOST);
		return venue;
	}

	private static void legacy(VenueState venue, String name, ACell value) {
		venue.users().ensure(HOST).writeInternalPath(WorkspaceRoot.parseStringPath("w/adapters/" + name), value);
	}
	private static ACell legacy(VenueState venue, String name) {
		User host = venue.users().get(HOST);
		return host == null ? null : host.readInternalPath(WorkspaceRoot.parseStringPath("w/adapters/" + name));
	}

	@Test void adapterValuesHaveNoWorkspaceEnvelopeOrUserActivitySideEffects() {
		VenueState venue = venue();
		User host = venue.users().ensure(HOST);
		ACell before = host.get();
		AdapterState state = venue.adapterState("test-store");
		String relative = state.userPath(OWNER, "preferences/theme");
		state.write(relative, Strings.create("dark"));
		assertEquals(":adapters/test-store", state.rootPath());
		assertEquals(Strings.create("dark"), state.read(relative));
		assertEquals(state.get(), RT.getIn(venue.get(), Covia.ADAPTERS, Strings.create("test-store")));
		assertEquals(before, host.get(), "adapter edits do not stamp the venue principal as active");
		assertNull(legacy(venue, "test-store"));
		assertFalse(state.delete("users/did:test:owner/missing"));
		assertTrue(state.delete(relative));
		assertNull(state.read(relative));
		assertThrows(IllegalArgumentException.class, () -> venue.adapterState("Bad/Name"));
		assertThrows(IllegalArgumentException.class, () -> state.write("../escape", CVMLong.ONE));
		assertThrows(IllegalArgumentException.class, () -> state.write("", CVMLong.ONE));
		assertThrows(NullPointerException.class, () -> state.write("x", null));
	}

	@Test void arbitraryValuesAndTypedCursorOperationsShareTheVenueTransaction() {
		VenueState venue = venue();
		AdapterState state = venue.adapterState("opaque");
		state.set(Vectors.of(1L, 2L));
		state.write("1", CVMLong.create(3));
		assertEquals(Vectors.of(1L, 3L), state.get());
		state.set(CVMLong.ZERO);
		state.transact(draft -> draft.update(value -> CVMLong.create(((CVMLong)value).longValue() + 1)));
		assertEquals(CVMLong.ONE, state.get());
		AtomicReference<AdapterState> retained = new AtomicReference<>();
		venue.transact(draft -> {
			AdapterState first = draft.adapterState("opaque");
			first.set(Maps.of("count", 2L));
			draft.adapterState("second").set(Vectors.of("committed"));
			draft.users().create(OWNER);
			retained.set(first);
		});
		assertEquals(Maps.of("count", 2L), state.get());
		assertEquals(Vectors.of("committed"), venue.adapterState("second").get());
		retained.get().set(Strings.create("detached"));
		assertEquals(Maps.of("count", 2L), state.get());
		ACell before = venue.get();
		assertThrows(IllegalStateException.class, () -> venue.transact(draft -> {
			draft.adapterState("opaque").set(CVMLong.ZERO);
			draft.adapterState("second").set(null);
			draft.users().create(Strings.create("did:test:rollback"));
			throw new IllegalStateException("test rollback");
		}));
		assertEquals(before, venue.get());
	}

	@Test void migrationMovesOneExactValueAndNeverCopiesDeletedDataBack() {
		VenueState venue = venue();
		ACell value = Maps.of("unknown", Vectors.of(1L, null, "future"), "empty", Maps.empty());
		legacy(venue, "one", value);
		legacy(venue, "unloaded", Maps.of("keep", true));
		AdapterState state = venue.adapterState("one");
		assertEquals(value, state.get());
		assertNull(legacy(venue, "one"));
		assertNotNull(legacy(venue, "unloaded"), "no eager sweep of unrelated adapters");
		state.set(null);
		assertNull(venue.adapterState("one").get());
		assertNull(legacy(venue, "one"));
	}

	@Test void conflictingMigrationPreservesBothCopiesAndIdenticalCopiesConverge() {
		VenueState venue = venue();
		AdapterState state = venue.adapterState("one");
		state.set(Maps.of("canonical", true));
		legacy(venue, "one", Maps.of("legacy", true));
		ACell before = venue.get();
		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> venue.adapterState("one"));
		assertEquals(VenueState.LEGACY_STATE_CONFLICT + "w/adapters/one", failure.getMessage());
		assertEquals(before, venue.get());
		legacy(venue, "one", state.get());
		assertEquals(state.get(), venue.adapterState("one").get());
		assertNull(legacy(venue, "one"));
		state.set(null);
		legacy(venue, "one", Maps.empty());
		assertThrows(IllegalStateException.class, () -> venue.adapterState("one"), "stored nil is still an authoritative value");
	}

	@Test void migrationRollsBackWithItsEnclosingTransaction() {
		VenueState venue = venue();
		legacy(venue, "one", Maps.of("old", true));
		ACell before = venue.get();
		assertThrows(IllegalStateException.class, () -> venue.transact(draft -> {
			draft.adapterState("one").write("new", CVMLong.ONE);
			throw new IllegalStateException("test rollback");
		}));
		assertEquals(before, venue.get());
	}

	@Test void concurrentLegacyWriteIsRetriedWithoutLosingTheNewValue() throws Exception {
		VenueState venue = venue();
		legacy(venue, "one", CVMLong.ONE);
		CountDownLatch staged = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicInteger attempts = new AtomicInteger();
		try {
			var migrate = CompletableFuture.runAsync(() -> venue.transact(draft -> {
				draft.adapterState("one");
				if (attempts.incrementAndGet() == 1) {
					staged.countDown();
					await(release);
				}
			}));
			assertTrue(staged.await(5, TimeUnit.SECONDS));
			legacy(venue, "one", CVMLong.create(2));
			release.countDown();
			migrate.get(5, TimeUnit.SECONDS);
			assertTrue(attempts.get() >= 2);
			assertEquals(CVMLong.create(2), venue.adapterState("one").get());
			assertNull(legacy(venue, "one"));
		} finally { release.countDown(); }
	}

	@Test void ownerGuardRejectsATransactionCommittingAfterAccountRecreation() throws Exception {
		VenueState venue = venue();
		venue.users().create(OWNER);
		AdapterState worker = venue.adapterState("worker", OWNER);
		worker.set(CVMLong.ONE);
		CountDownLatch staged = new CountDownLatch(1), release = new CountDownLatch(1);
		try {
			var write = CompletableFuture.runAsync(() -> worker.transact(draft -> {
				draft.set(CVMLong.create(2));
				staged.countDown();
				await(release);
			}));
			assertTrue(staged.await(5, TimeUnit.SECONDS));
			venue.deleteUser(OWNER, HOST);
			venue.users().finishDeletion(OWNER);
			venue.users().create(OWNER);
			release.countDown();
			assertInstanceOf(AuthException.class, assertThrows(java.util.concurrent.ExecutionException.class,
				() -> write.get(5, TimeUnit.SECONDS)).getCause());
			assertEquals(CVMLong.ONE, venue.adapterState("worker").get());
			assertThrows(AuthException.class, () -> worker.cursor().set(CVMLong.ZERO));
		} finally { release.countDown(); }
	}

	@Test void venueDraftRebindingRetainsTheOriginalAccountFence() throws Exception {
		VenueState venue = venue();
		venue.users().create(OWNER);
		AdapterState worker = venue.adapterState("worker", OWNER);
		worker.set(CVMLong.ONE);
		CountDownLatch staged = new CountDownLatch(1), release = new CountDownLatch(1);
		try {
			var write = CompletableFuture.runAsync(() -> venue.transact(draft -> {
				worker.inVenue(draft).set(CVMLong.create(2));
				draft.adapterState("other").set(CVMLong.ONE);
				staged.countDown();
				await(release);
			}));
			assertTrue(staged.await(5, TimeUnit.SECONDS));
			venue.deleteUser(OWNER, HOST);
			venue.users().finishDeletion(OWNER);
			venue.users().create(OWNER);
			release.countDown();
			assertInstanceOf(AuthException.class, assertThrows(java.util.concurrent.ExecutionException.class,
				() -> write.get(5, TimeUnit.SECONDS)).getCause());
			assertEquals(CVMLong.ONE, venue.adapterState("worker").get());
			assertNull(venue.adapterState("other").get(), "the whole delivery draft must roll back");
			assertThrows(AuthException.class, () -> venue.transact(draft -> worker.inVenue(draft).set(CVMLong.ZERO)));
			assertThrows(AuthException.class, () -> worker.cursor().set(CVMLong.ZERO));
		} finally { release.countDown(); }
	}

	@Test void snapshotRecoverySelectsAWholeVenueRatherThanCombiningForks() {
		VenueState venue = venue();
		venue.adapterState("left").set(CVMLong.ZERO);
		venue.adapterState("right").set(CVMLong.ZERO);
		AMap<ACell, ACell> backup = RT.ensureMap(venue.get());
		venue.adapterState("right").set(CVMLong.ONE);
		long newer = ((CVMLong)RT.getIn(venue.get(), Keywords.TIMESTAMP)).longValue() + 1;
		ACell recovered = backup.assoc(Covia.ADAPTERS, Maps.of("left", 2L, "right", 0L))
			.assoc(Keywords.TIMESTAMP, CVMLong.create(newer));
		venue.merge(recovered);
		assertEquals(CVMLong.create(2), venue.adapterState("left").get());
		assertEquals(CVMLong.ZERO, venue.adapterState("right").get(), "LWW selects one snapshot; it cannot reconcile divergent executions");
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}
}
