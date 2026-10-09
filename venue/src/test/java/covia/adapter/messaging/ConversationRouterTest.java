package covia.adapter.messaging;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.venue.Engine;

class ConversationRouterTest {
	private record Spec(String name, String userRef, String agent, String operation, ACell reply)
			implements MessagingBotSpec { }

	@Test void ordersTurnsAndResetsDespiteFailureOrObserverCancellation() throws Exception {
		Engine engine = Engine.createTemp(Maps.empty());
		CountDownLatch release = new CountDownLatch(1);
		try {
			var sessions = new ConversationSessions(engine.adapterWorkspace("messaging-test"), "config/bot/sessions");
			var router = new ConversationRouter(engine, new Spec("bot", "did:test:owner", "agent", null, null), sessions);
			List<String> calls = Collections.synchronizedList(new ArrayList<>());
			CountDownLatch entered = new CountDownLatch(1);
			CompletableFuture<Void> first = router.enqueue("chat", () -> {
				entered.countDown();
				await(release);
				sessions.put("chat", "old-session");
				calls.add("first");
				throw new IllegalStateException("test turn failure");
			});
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			first.cancel(false); // Cancel observing the turn; it must not release ordering.
			CompletableFuture<Void> reset = router.enqueue("chat", () -> { router.reset("chat"); calls.add("reset"); });
			CompletableFuture<Void> last = router.enqueue("chat", () -> { assertNull(sessions.get("chat")); calls.add("last"); });
			router.enqueue("other-chat", () -> calls.add("other")).get(5, TimeUnit.SECONDS);
			assertEquals(List.of("other"), calls);
			assertFalse(reset.isDone());
			assertFalse(router.isIdle("chat"));
			release.countDown();
			last.get(5, TimeUnit.SECONDS);
			assertEquals(List.of("other", "first", "reset", "last"), calls);
			assertTrue(router.isIdle("chat"));
		} finally { release.countDown(); engine.close(); }
	}

	@Test void persistsOpaqueKeysWithoutCrossingInstallationsThreadsOrOwners() {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			var state = engine.adapterWorkspace("slack-test");
			String root = state.userPath(Strings.create("did:test:owner"), "sessions/bot");
			var sessions = new ConversationSessions(state, root);
			String thread = ConversationRouter.key("T1", "C1", "1700000000.123456");
			String otherThread = ConversationRouter.key("T1", "C1", "1700000000.123457");
			sessions.put(thread, "session-a");
			sessions.put(otherThread, "session-b");
			var restored = new ConversationSessions(state, root);
			assertEquals("session-a", restored.get(thread));
			assertEquals("session-b", restored.get(otherThread));
			assertNull(restored.get(ConversationRouter.key("T2", "C1", "1700000000.123456")));
			assertNull(new ConversationSessions(state, "config/bot/sessions").get(thread));
			assertNull(new ConversationSessions(state, state.userPath(Strings.create("did:test:other"), "sessions/bot")).get(thread));
			assertNotEquals(ConversationRouter.key("a/b", "c"), ConversationRouter.key("a", "b/c"));
			sessions.put("../../escape", "safe");
			assertEquals("safe", restored.get("../../escape"));
			assertNull(state.read("escape"));
			sessions.put("-123", "telegram");
			assertEquals(Strings.create("telegram"), state.read(root + "/-123"));
		} finally { engine.close(); }
	}

	@Test void migratesLegacySessionsWithCanonicalPrecedenceAndDeletesBoth() {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			var state = engine.adapterWorkspace("messaging-test");
			var legacy = new java.util.HashMap<String, String>();
			legacy.put("1", "legacy-session");
			var sessions = new ConversationSessions(state, "sessions", legacy::get, legacy::remove);
			assertEquals("legacy-session", sessions.get("1"));
			assertEquals(Strings.create("legacy-session"), state.read("sessions/1"));
			sessions.put("1", "current-session");
			assertEquals("current-session", sessions.get("1"));
			sessions.remove("1");
			assertNull(sessions.get("1"));
			assertTrue(legacy.isEmpty());
		} finally { engine.close(); }
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}
}
