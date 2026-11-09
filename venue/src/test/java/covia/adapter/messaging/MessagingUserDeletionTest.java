package covia.adapter.messaging;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.exception.AuthException;
import covia.adapter.messaging.MessagingBot.Managed;
import covia.venue.AdapterState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

class MessagingUserDeletionTest {
	private static final AString VALUE = Strings.create("session");
	/** One venue for the class: each test registers an adapter of its own and disables it or deletes through it. */
	private static Engine engine;
	private AString owner;
	private AString other;
	private Adapter adapter;

	@BeforeAll static void start() { engine = Engine.createTemp(Maps.empty()); }
	@AfterAll static void stop() { engine.close(); }

	@BeforeEach void perTest(TestInfo info) {
		owner = TestEngine.uniqueDID(info);
		other = Strings.create(owner + "-other");
		adapter = new Adapter(info.getTestMethod().orElseThrow().getName().toLowerCase(), owner);
	}

	private record Spec(String name, String userRef) implements MessagingBotSpec {
		@Override public String agent() { return null; }
		@Override public String operation() { return "v/test/ops/echo"; }
		@Override public ACell reply() { return null; }
	}
	private static class Bot implements MessagingBot<Spec> {
		final Spec spec;
		final Managed managed;
		final AdapterState state;
		boolean running;
		Bot(Adapter adapter, Spec spec, Managed managed) {
			this.spec = spec; this.managed = managed;
			state = adapter.state(spec.userDID(adapter.engine));
		}
		@Override public Spec spec() { return spec; }
		@Override public Managed managed() { return managed; }
		@Override public void start() { running = true; }
		@Override public void stop() { running = false; }
		@Override public AMap<AString, ACell> status() { return Maps.of("running", running); }
	}
	private static class Adapter extends AMessagingAdapter<Spec, Bot> {
		private final String name;
		private final AString owner;
		Runnable creating = () -> { };
		Runnable deleting = () -> { };
		Adapter(String name, AString owner) { this.name = name; this.owner = owner; }
		@Override public String getName() { return name; }
		@Override public String getDescription() { return "In-memory messaging lifecycle test"; }
		@Override protected Spec parseBot(String name, ACell settings, boolean strict) {
			return new Spec(name, RT.getIn(settings, Strings.create("user")).toString());
		}
		@Override protected Bot newBot(Spec spec, Managed managed) {
			Bot bot = new Bot(this, spec, managed);
			creating.run();
			return bot;
		}
		@Override protected boolean configurationMatches(Bot runner) { return true; }
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			return CompletableFuture.completedFuture(handleCreate(ctx, input));
		}
		/** Every adapter on the venue hears of every deletion: another test's account is none of this one's business. */
		@Override public void onUserDeleted(AString did, AMap<AString, ACell> previousUser) {
			if (!did.equals(owner)) return;
			deleting.run();
			super.onUserDeleted(did, previousUser);
		}
		void configureOwner() { configureBots(Map.of("configured", new Spec("configured", owner.toString()))); }
		void create(AString user) { handleCreate(RequestContext.of(user), Maps.of("name", "runtime", "token", "s/token")); }
	}

	@Test void deletionStopsDisabledBotsErasesPrivateStateAndPreventsRestart() {
		engine.getVenueState().users().create(owner);
		engine.getVenueState().users().create(other);
		adapter.configureOwner();
		engine.registerAdapter(adapter);
		adapter.create(owner);
		adapter.create(other);
		Bot configured = adapter.runner("configured"), runtime = adapter.runner(owner, "runtime");
		for (String region : new String[] { "sessions", "inbox", "files", "resolved", "relay" }) {
			runtime.state.write(adapter.userStatePath(owner, region + "/runtime/entry"), VALUE);
			configured.state.write("config/configured/" + region + "/entry", VALUE);
		}
		engine.disableAdapter(adapter.getName());
		engine.deleteUser(engine.venueContext(), owner);
		assertFalse(runtime.running);
		assertFalse(configured.running);
		assertTrue(adapter.runner(other, "runtime").running);
		assertNull(adapter.state().read(adapter.userStatePath(owner, "")));
		assertNull(adapter.state().read("config/configured"));
		engine.enableAdapter(adapter.getName());
		assertNull(adapter.runner(owner, "runtime"));
		assertNull(adapter.runner("configured"));
		engine.getVenueState().users().create(owner);
		assertThrows(AuthException.class, () -> runtime.state.write(adapter.userStatePath(owner, "sessions/late"), VALUE));
		assertThrows(AuthException.class, () -> configured.state.write("config/configured/sessions/late", VALUE));
		adapter.create(owner);
		assertTrue(adapter.runner(owner, "runtime").running);
	}

	@Test void createRacingDeletionCannotPersistOrStartAnOrphan() throws Exception {
		CountDownLatch creating = new CountDownLatch(1), revoked = new CountDownLatch(1), release = new CountDownLatch(1);
		try {
			engine.getVenueState().users().create(owner);
			engine.registerAdapter(adapter);
			adapter.creating = () -> {
				creating.countDown();
				try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
				catch (InterruptedException e) { throw new AssertionError(e); }
			};
			adapter.deleting = revoked::countDown;
			CompletableFuture<Void> create = CompletableFuture.runAsync(() -> adapter.create(owner));
			assertTrue(creating.await(5, TimeUnit.SECONDS));
			CompletableFuture<ACell> delete = CompletableFuture.supplyAsync(() -> engine.deleteUser(engine.venueContext(), owner));
			assertTrue(revoked.await(5, TimeUnit.SECONDS));
			assertTrue(engine.getVenueState().users().isDeleted(owner));
			release.countDown();
			assertInstanceOf(AuthException.class,
				assertThrows(java.util.concurrent.ExecutionException.class, () -> create.get(5, TimeUnit.SECONDS)).getCause());
			delete.get(5, TimeUnit.SECONDS);
			assertNull(adapter.runner(owner, "runtime"));
			assertNull(adapter.state().read(adapter.userStatePath(owner, "")));
		} finally { release.countDown(); }
	}

	@Test void queuedCallbacksCannotActAsARecreatedAccount() throws Exception {
		CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1);
		try {
			engine.getVenueState().users().create(owner);
			engine.registerAdapter(adapter);
			var router = new ConversationRouter(engine, new Spec("runtime", owner.toString()),
				new ConversationSessions(adapter.state(owner), "users/" + owner + "/sessions/runtime"));
			var first = router.enqueue("chat", () -> {
				busy.countDown();
				try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
				catch (InterruptedException e) { throw new AssertionError(e); }
			});
			assertTrue(busy.await(5, TimeUnit.SECONDS));
			var queued = router.enqueue("chat", () -> fail("A callback from the deleted account ran"));
			engine.deleteUser(engine.venueContext(), owner);
			engine.getVenueState().users().create(owner);
			release.countDown();
			first.get(5, TimeUnit.SECONDS);
			assertInstanceOf(AuthException.class,
				assertThrows(java.util.concurrent.ExecutionException.class, () -> queued.get(5, TimeUnit.SECONDS)).getCause());
			assertThrows(AuthException.class, router::context);
		} finally { release.countDown(); }
	}
}
