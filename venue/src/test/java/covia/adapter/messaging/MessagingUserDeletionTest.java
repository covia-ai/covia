package covia.adapter.messaging;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.exception.AuthException;
import covia.adapter.messaging.MessagingBot.Managed;
import covia.venue.AdapterWorkspace;
import covia.venue.Engine;
import covia.venue.RequestContext;

class MessagingUserDeletionTest {
	private static final AString OWNER = Strings.create("did:test:bot-owner");
	private static final AString OTHER = Strings.create("did:test:other-owner");
	private static final AString VALUE = Strings.create("session");
	private record Spec(String name, String userRef) implements MessagingBotSpec {
		@Override public String agent() { return null; }
		@Override public String operation() { return "v/test/ops/echo"; }
		@Override public ACell reply() { return null; }
	}
	private static class Bot implements MessagingBot<Spec> {
		final Spec spec;
		final Managed managed;
		final AdapterWorkspace state;
		boolean running;
		Bot(Adapter adapter, Spec spec, Managed managed) {
			this.spec = spec; this.managed = managed;
			state = adapter.state().forUser(spec.userDID(adapter.engine));
		}
		@Override public Spec spec() { return spec; }
		@Override public Managed managed() { return managed; }
		@Override public void start() { running = true; }
		@Override public void stop() { running = false; }
		@Override public AMap<AString, ACell> status() { return Maps.of("running", running); }
	}
	private static class Adapter extends AMessagingAdapter<Spec, Bot> {
		Runnable creating = () -> { };
		Runnable deleting = () -> { };
		@Override public String getName() { return "test-messaging"; }
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
		@Override public void onUserDeleted(AString did, AMap<AString, ACell> previousUser) {
			deleting.run();
			super.onUserDeleted(did, previousUser);
		}
		void configureOwner() { configureBots(Map.of("configured", new Spec("configured", OWNER.toString()))); }
		void create(AString owner) { handleCreate(RequestContext.of(owner), Maps.of("name", "runtime", "token", "s/token")); }
	}

	@Test void deletionStopsDisabledBotsErasesPrivateStateAndPreventsRestart() {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			engine.getVenueState().users().create(OWNER);
			engine.getVenueState().users().create(OTHER);
			Adapter adapter = new Adapter();
			adapter.configureOwner();
			engine.registerAdapter(adapter);
			adapter.create(OWNER);
			adapter.create(OTHER);
			Bot configured = adapter.runner("configured"), runtime = adapter.runner(OWNER, "runtime");
			for (String region : new String[] { "sessions", "inbox", "files", "resolved", "relay" }) {
				runtime.state.write(adapter.userStatePath(OWNER, region + "/runtime/entry"), VALUE);
				configured.state.write("config/configured/" + region + "/entry", VALUE);
			}
			engine.disableAdapter(adapter.getName());
			engine.deleteUser(engine.venueContext(), OWNER);
			assertFalse(runtime.running);
			assertFalse(configured.running);
			assertTrue(adapter.runner(OTHER, "runtime").running);
			assertNull(adapter.state().read(adapter.userStatePath(OWNER, "")));
			assertNull(adapter.state().read("config/configured"));
			engine.enableAdapter(adapter.getName());
			assertNull(adapter.runner(OWNER, "runtime"));
			assertNull(adapter.runner("configured"));
			engine.getVenueState().users().create(OWNER);
			assertThrows(AuthException.class, () -> runtime.state.write(adapter.userStatePath(OWNER, "sessions/late"), VALUE));
			assertThrows(AuthException.class, () -> configured.state.write("config/configured/sessions/late", VALUE));
			adapter.create(OWNER);
			assertTrue(adapter.runner(OWNER, "runtime").running);
		} finally { engine.close(); }
	}

	@Test void createRacingDeletionCannotPersistOrStartAnOrphan() throws Exception {
		Engine engine = Engine.createTemp(Maps.empty());
		CountDownLatch creating = new CountDownLatch(1), revoked = new CountDownLatch(1), release = new CountDownLatch(1);
		try {
			engine.getVenueState().users().create(OWNER);
			Adapter adapter = new Adapter();
			engine.registerAdapter(adapter);
			adapter.creating = () -> {
				creating.countDown();
				try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
				catch (InterruptedException e) { throw new AssertionError(e); }
			};
			adapter.deleting = revoked::countDown;
			CompletableFuture<Void> create = CompletableFuture.runAsync(() -> adapter.create(OWNER));
			assertTrue(creating.await(5, TimeUnit.SECONDS));
			CompletableFuture<ACell> delete = CompletableFuture.supplyAsync(() -> engine.deleteUser(engine.venueContext(), OWNER));
			assertTrue(revoked.await(5, TimeUnit.SECONDS));
			assertTrue(engine.getVenueState().users().isDeleted(OWNER));
			release.countDown();
			assertInstanceOf(AuthException.class,
				assertThrows(java.util.concurrent.ExecutionException.class, () -> create.get(5, TimeUnit.SECONDS)).getCause());
			delete.get(5, TimeUnit.SECONDS);
			assertNull(adapter.runner(OWNER, "runtime"));
			assertNull(adapter.state().read(adapter.userStatePath(OWNER, "")));
		} finally { release.countDown(); engine.close(); }
	}

	@Test void queuedCallbacksCannotActAsARecreatedAccount() throws Exception {
		Engine engine = Engine.createTemp(Maps.empty());
		CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1);
		try {
			engine.getVenueState().users().create(OWNER);
			Adapter adapter = new Adapter();
			engine.registerAdapter(adapter);
			var router = new ConversationRouter(engine, new Spec("runtime", OWNER.toString()),
				new ConversationSessions(adapter.state().forUser(OWNER), "users/" + OWNER + "/sessions/runtime"));
			var first = router.enqueue("chat", () -> {
				busy.countDown();
				try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
				catch (InterruptedException e) { throw new AssertionError(e); }
			});
			assertTrue(busy.await(5, TimeUnit.SECONDS));
			var queued = router.enqueue("chat", () -> fail("A callback from the deleted account ran"));
			engine.deleteUser(engine.venueContext(), OWNER);
			engine.getVenueState().users().create(OWNER);
			release.countDown();
			first.get(5, TimeUnit.SECONDS);
			assertInstanceOf(AuthException.class,
				assertThrows(java.util.concurrent.ExecutionException.class, () -> queued.get(5, TimeUnit.SECONDS)).getCause());
			assertThrows(AuthException.class, router::context);
		} finally { release.countDown(); engine.close(); }
	}
}
