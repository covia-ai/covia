package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.adapter.AAdapter;
import covia.exception.AuthException;

class AdapterUserDeletionTest {
	private static final AString OWNER = Strings.create("did:test:deleted-owner");
	private static final AString OTHER = Strings.create("did:test:other-owner");
	private static final AString VALUE = Strings.create("private-value");
	private static final String NAME = "cleanup-test";

	static class Probe extends AAdapter {
		Consumer<AdapterWorkspace> duringState = state -> { };
		int stopped;
		boolean failStop;
		boolean started;
		boolean sawPreviousUser;
		@Override public String getName() { return NAME; }
		@Override public String getDescription() { return "Deletion hook test"; }
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			return CompletableFuture.completedFuture(input);
		}
		@Override public void deleteUserState(AString did, AdapterWorkspace state) {
			state.delete("custom/" + did);
			duringState.accept(state);
		}
		@Override public void onUserDeleted(AString did, AMap<AString, ACell> previousUser) {
			if (engine != null) {
				assertTrue(engine.getVenueState().users().isDeleted(did));
				assertNull(engine.adapterWorkspace(NAME).read("custom/" + did));
			}
			sawPreviousUser |= previousUser != null;
			stopped++;
			if (failStop) throw new IllegalStateException("test stop failure");
		}
		@Override public void start() {
			assertTrue(engine.getVenueState().pendingUserDeletions(NAME).isEmpty());
			started = true;
		}
	}

	private static AdapterWorkspace seed(Engine engine) {
		engine.getVenueState().users().create(OWNER);
		engine.getVenueState().users().create(OTHER);
		AdapterWorkspace state = engine.adapterWorkspace(NAME);
		state.write(state.userPath(OWNER, "bots/old"), VALUE);
		state.write(state.userPath(OTHER, "bots/keep"), VALUE);
		state.write("custom/" + OWNER, VALUE);
		return state;
	}

	@Test void disabledAdaptersParticipateAndRetainedWorkersCannotWriteAfterRecreation() {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			Probe adapter = new Probe();
			engine.registerAdapter(adapter);
			AdapterWorkspace state = seed(engine);
			AdapterWorkspace retained = state.forUser(OWNER);
			engine.disableAdapter(NAME);
			ACell tombstone = engine.deleteUser(engine.venueContext(), OWNER);
			assertTrue(adapter.sawPreviousUser);
			assertEquals(1, adapter.stopped);
			assertNull(state.read(state.userPath(OWNER, "")));
			assertEquals(VALUE, state.read(state.userPath(OTHER, "bots/keep")));
			assertThrows(AuthException.class, () -> state.write(state.userPath(OWNER, "late"), VALUE));
			assertEquals(tombstone, engine.deleteUser(engine.venueContext(), OWNER));
			assertTrue(engine.getVenueState().users().create(OWNER));
			state.forUser(OWNER).write("config/fresh/value", VALUE);
			assertThrows(AuthException.class, () -> retained.write("config/fresh/value", VALUE));
			assertThrows(AuthException.class, () -> retained.delete("config/fresh/value"));
			assertThrows(AuthException.class, () -> retained.read("config/fresh/value"));
			assertEquals(VALUE, state.read("config/fresh/value"));
		} finally { engine.close(); }
	}

	@Test void failedRuntimeCleanupRevokesImmediatelyAndCanBeRetried() {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			Probe adapter = new Probe();
			engine.registerAdapter(adapter);
			AdapterWorkspace state = seed(engine);
			adapter.failStop = true;
			assertThrows(IllegalStateException.class, () -> engine.deleteUser(engine.venueContext(), OWNER));
			assertNull(state.read(state.userPath(OWNER, "")));
			assertTrue(engine.getVenueState().pendingUserDeletions(NAME).containsKey(OWNER));
			assertThrows(AuthException.class, () -> engine.getVenueState().users().create(OWNER));
			adapter.failStop = false;
			engine.deleteUser(engine.venueContext(), OWNER);
			assertEquals(2, adapter.stopped);
			assertTrue(engine.getVenueState().pendingUserDeletions(NAME).isEmpty());
			assertTrue(engine.getVenueState().users().create(OWNER));
		} finally { engine.close(); }
	}

	@Test void bootDisabledAdaptersDoNotNeedInstallationToDeleteState() {
		Engine engine = Engine.createTemp(Maps.of("adapters", Maps.of(
			NAME, Maps.of("enabled", false), "agent", Maps.of("enabled", false))));
		try {
			Probe adapter = new Probe();
			engine.registerAdapter(adapter);
			assertNull(adapter.engine);
			AdapterWorkspace state = seed(engine);
			engine.getVenueState().users().get(OWNER).ensureAgent("old", Maps.empty(), VALUE);
			engine.deleteUser(engine.venueContext(), OWNER);
			assertEquals(1, adapter.stopped);
			assertNull(state.read("custom/" + OWNER));
			assertTrue(engine.getVenueState().users().create(OWNER));
		} finally { engine.close(); }
	}

	@Test void unloadedAdapterStateIsErasedAndCustomCleanupRunsBeforeReactivation() {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			engine.registerAdapter(new Probe());
			AdapterWorkspace state = seed(engine);
			engine.removeAdapter(NAME);
			IllegalStateException pending = assertThrows(IllegalStateException.class,
				() -> engine.deleteUser(engine.venueContext(), OWNER));
			assertEquals(Engine.USER_DELETION_PENDING, pending.getMessage());
			assertNull(state.read(state.userPath(OWNER, "")));
			assertEquals(VALUE, state.read("custom/" + OWNER));
			assertThrows(AuthException.class, () -> engine.getVenueState().users().create(OWNER));
			Probe replacement = new Probe();
			engine.registerAdapter(replacement);
			assertTrue(replacement.started);
			assertEquals(1, replacement.stopped);
			assertFalse(replacement.sawPreviousUser, "personal data is not retained for retries");
			assertNull(state.read("custom/" + OWNER));
			assertTrue(engine.getVenueState().users().create(OWNER));
		} finally { engine.close(); }
	}

	@Test void stateHookFailureRollsBackTheEntireDeletion() {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			Probe adapter = new Probe();
			engine.registerAdapter(adapter);
			AdapterWorkspace state = seed(engine);
			adapter.duringState = draft -> { throw new IllegalStateException("test rollback"); };
			assertThrows(IllegalStateException.class, () -> engine.deleteUser(engine.venueContext(), OWNER));
			assertNotNull(engine.getVenueState().users().get(OWNER));
			assertEquals(VALUE, state.read(state.userPath(OWNER, "bots/old")));
			assertEquals(VALUE, state.read("custom/" + OWNER));
			assertTrue(engine.getVenueState().pendingUserDeletions(NAME).isEmpty());
			assertEquals(0, adapter.stopped);
		} finally { engine.close(); }
	}

	@Test void concurrentWriteRetriesTheWholeDeletionAndCannotLeaveAnOrphan() throws Exception {
		Engine engine = Engine.createTemp(Maps.empty());
		CountDownLatch staged = new CountDownLatch(1), release = new CountDownLatch(1);
		try {
			Probe adapter = new Probe();
			engine.registerAdapter(adapter);
			AdapterWorkspace state = seed(engine);
			AtomicInteger attempts = new AtomicInteger();
			adapter.duringState = draft -> {
				if (attempts.incrementAndGet() == 1) {
					staged.countDown();
					try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
					catch (InterruptedException e) { throw new AssertionError(e); }
				}
			};
			CompletableFuture<ACell> deletion = CompletableFuture.supplyAsync(() -> engine.deleteUser(engine.venueContext(), OWNER));
			assertTrue(staged.await(5, TimeUnit.SECONDS));
			assertFalse(engine.getVenueState().users().isDeleted(OWNER));
			assertEquals(VALUE, state.read("custom/" + OWNER), "draft cleanup must not leak");
			state.forUser(OWNER).write(state.userPath(OWNER, "bots/racing"), VALUE);
			release.countDown();
			deletion.get(5, TimeUnit.SECONDS);
			assertTrue(attempts.get() >= 2);
			assertNull(state.read(state.userPath(OWNER, "")));
			assertEquals(1, adapter.stopped, "runtime effects must not repeat with CAS retries");
		} finally { release.countDown(); engine.close(); }
	}

	@Test void repeatingAnOlderDeletionCleansLegacyAdapterRecords() {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			Probe adapter = new Probe();
			engine.registerAdapter(adapter);
			AdapterWorkspace state = seed(engine);
			engine.getVenueState().deleteUser(OWNER, engine.getDIDString());
			engine.getVenueState().users().finishDeletion(OWNER); // pre-hook tombstone
			assertEquals(VALUE, state.read(state.userPath(OWNER, "bots/old")));
			engine.deleteUser(engine.venueContext(), OWNER);
			assertNull(state.read(state.userPath(OWNER, "")));
			assertTrue(engine.getVenueState().users().create(OWNER));
		} finally { engine.close(); }
	}
}
