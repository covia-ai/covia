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
import convex.core.lang.RT;
import covia.venue.Engine;
import covia.adapter.AgentAdapter;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.grid.Status;

class ConversationRouterTest {
	@Test void recoveryUsesOnlyTheAgentCodeRegardlessOfWording() {
		Job missing = Job.create(Maps.of(Fields.STATUS, Status.STARTED));
		missing.fail(AgentAdapter.UNKNOWN_SESSION_CODE, "This conversation has expired");
		assertTrue(ConversationRouter.isUnknownSession(new java.util.concurrent.CompletionException(
			new JobFailedException(missing))));
		assertFalse(ConversationRouter.isUnknownSession(new JobFailedException("Unknown session")));
		Job other = Job.create(Maps.of(Fields.STATUS, Status.STARTED));
		other.fail("other:unknown-session", "Unknown session");
		assertFalse(ConversationRouter.isUnknownSession(new JobFailedException(other)));
	}

	@Test void staleSessionIsReplacedThroughTheRealAgentFailure() {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			Engine.addDemoAssets(engine);
			engine.getVenueState().users().ensure(Strings.create("did:test:owner"));
			var sessions = new ConversationSessions(engine.adapterState("messaging-test"), "config/bot/sessions");
			var router = new ConversationRouter(engine, new Spec("bot", "did:test:owner", "retry-agent", null, null), sessions);
			router.runOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "retry-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", "v/test/ops/llm")));
			String stale = "00000000000000000000000000000000";
			sessions.put("chat", stale);
			assertNotNull(router.chat("chat", Strings.create("hello")));
			assertNotNull(sessions.get("chat"));
			assertNotEquals(stale, sessions.get("chat"));
		} finally { engine.close(); }
	}

	@Test void agentsThatCannotTakeInputAreRefusedBeforeAnyJob() {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			Engine.addDemoAssets(engine);
			var owner = Strings.create("did:test:refusal");
			engine.getVenueState().users().ensure(owner);
			var state = engine.adapterState("messaging-test");
			var router = new ConversationRouter(engine, new Spec("bot", owner.toString(), "resting", null, null),
				new ConversationSessions(state, "refusal/sessions"));
			assertEquals(AgentAdapter.DELIVERY_AGENT_MISSING.formatted("resting"), router.deliveryRefusal());
			assertTrue(router.supportsSessionDelivery(), "a missing agent is refused by session delivery, not chatted to");
			router.runOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "resting",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", "v/test/ops/llm")));
			assertNull(router.deliveryRefusal());
			assertTrue(router.supportsSessionDelivery());
			router.runOperation("v/ops/agent/suspend", Maps.of(Fields.AGENT_ID, "resting"));
			long jobs = engine.jobs().getJobs(router.context()).count();
			assertEquals(AgentAdapter.DELIVERY_AGENT_SUSPENDED.formatted("resting"), router.deliveryRefusal());
			assertTrue(router.supportsSessionDelivery());
			assertEquals(jobs, engine.jobs().getJobs(router.context()).count(), "checking mints no Job");

			var strangerDID = Strings.create("did:test:stranger");
			engine.getVenueState().users().ensure(strangerDID);
			var stranger = new ConversationRouter(engine, new Spec("bot", strangerDID.toString(), owner + "/g/resting", null, null),
				new ConversationSessions(state, "refusal/stranger"));
			assertNotNull(stranger.deliveryRefusal(), "another user's agent that does not admit the caller");
		} finally { engine.close(); }
	}
	@Test void deliveryCommitsInputReceiptAndMappingTogetherAndRollsBackOnFailure() throws Exception {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			Engine.addDemoAssets(engine);
			engine.registerAdapter(new AgentAdapter() {
				@Override public CompletableFuture<ACell> wakeAgent(convex.core.data.AString owner,
						convex.core.data.AString agent, boolean force) { return CompletableFuture.completedFuture(null); }
			});
			var owner = Strings.create("did:test:atomic-intake");
			engine.getVenueState().users().ensure(owner);
			var state = engine.adapterState("messaging-test");
			var sessions = new ConversationSessions(state, "atomic/sessions");
			var inbox = new covia.adapter.webhook.WebhookInbox(engine, state, "atomic/inbox");
			var router = new ConversationRouter(engine, new Spec("bot", owner.toString(), "atomic", null, null), sessions);
			router.runOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "atomic",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat")));
			inbox.accept("1", Strings.create("input"));
			var agent = engine.getVenueState().users().get(owner).agent("atomic");
			assertThrows(JobFailedException.class, () -> router.deliver("chat", Strings.create("input"), (draft, sid, jid) -> {
				assertEquals(1, draft.users().get(owner).agent("atomic").getSessionPending(convex.core.data.Blob.fromHex(sid.toString())).count());
				assertTrue(agent.getSessions().isEmpty(), "the live input must remain invisible until commit");
				assertTrue(inbox.deliver(draft, "1", sid, jid));
				assertEquals(covia.adapter.webhook.WebhookInbox.PENDING, inbox.get("1").get(Fields.STATUS));
				throw new IllegalStateException("injected intake failure");
			}));
			assertTrue(agent.getSessions().isEmpty());
			assertNull(sessions.get("chat"));
			assertEquals(covia.adapter.webhook.WebhookInbox.PENDING, inbox.get("1").get(Fields.STATUS));

			// Race duplicates before a first session mapping exists. A losing draft
			// must discard its speculative session as well as its message append.
			var calls = new ArrayList<CompletableFuture<Job>>();
			for (int i = 0; i < 6; i++) calls.add(CompletableFuture.supplyAsync(() -> router.deliver(
				"chat", Strings.create("input"), (draft, sid, jid) -> inbox.deliver(draft, "1", sid, jid))));
			int winners = 0;
			for (var call : calls) if (call.get(10, TimeUnit.SECONDS) != null) winners++;
			assertEquals(1, winners);
			assertEquals(1, agent.getSessions().count());
			var sid = convex.core.data.Blob.fromHex(sessions.get("chat"));
			assertEquals(1, agent.getSessionPending(sid).count());
			assertEquals(covia.adapter.webhook.WebhookInbox.COMPLETE, inbox.get("1").get(Fields.STATUS));
			assertEquals(Strings.create(sessions.get("chat")), inbox.get("1").get(Fields.SESSION_ID));
			assertEquals(RT.getIn(agent.getSessionPending(sid).get(0), Fields.JOB_ID), inbox.get("1").get(Fields.JOB_ID));
		} finally { engine.close(); }
	}

	@Test void conflictingVenueEditRetriesIntakeButWakesAndRepliesOnce() throws Exception {
		Engine engine = Engine.createTemp(Maps.empty());
		var staged = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var wakes = new java.util.concurrent.atomic.AtomicInteger();
		try {
			Engine.addDemoAssets(engine);
			engine.registerAdapter(new AgentAdapter() {
				@Override public CompletableFuture<ACell> wakeAgent(convex.core.data.AString owner,
						convex.core.data.AString agent, boolean force) {
					wakes.incrementAndGet();
					return CompletableFuture.completedFuture(null);
				}
			});
			var owner = Strings.create("did:test:conflict-intake");
			engine.getVenueState().users().ensure(owner);
			var state = engine.adapterState("messaging-test", owner);
			var sessions = new ConversationSessions(state, "conflict/sessions");
			var inbox = new covia.adapter.webhook.WebhookInbox(engine, state, "conflict/inbox");
			var router = new ConversationRouter(engine, new Spec("bot", owner.toString(), "atomic", null, null), sessions);
			router.runOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "atomic",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat")));
			inbox.accept("1", Strings.create("input"));
			wakes.set(0);
			var attempts = new java.util.concurrent.atomic.AtomicInteger();
			var delivery = CompletableFuture.supplyAsync(() -> router.deliver("chat", Strings.create("input"), (draft, sid, jid) -> {
				boolean accepted = inbox.deliver(draft, "1", sid, jid);
				if (attempts.incrementAndGet() == 1) {
					staged.countDown();
					try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
					catch (InterruptedException e) { throw new AssertionError(e); }
				}
				return accepted;
			}));
			assertTrue(staged.await(5, TimeUnit.SECONDS));
			engine.adapterState("unrelated").set(Strings.create("concurrent write"));
			release.countDown();
			Job job = delivery.get(5, TimeUnit.SECONDS);
			assertTrue(attempts.get() >= 2);
			assertEquals(1, wakes.get());
			var agent = engine.getVenueState().users().get(owner).agent("atomic");
			assertEquals(1, agent.getSessions().count());
			assertEquals(1, agent.getSessionPending(convex.core.data.Blob.fromHex(sessions.get("chat"))).count());
			assertEquals(covia.adapter.webhook.WebhookInbox.COMPLETE, inbox.get("1").get(Fields.STATUS));
			var replies = new java.util.concurrent.LinkedBlockingQueue<ACell>();
			ConversationRouter.onResponse(job, replies::add, failure -> fail(failure));
			job.completeWith(Maps.of(Fields.RESPONSE, "reply"));
			assertEquals(Strings.create("reply"), replies.poll(5, TimeUnit.SECONDS));
			ConversationRouter.onResponse(router.deliver("chat", Strings.create("input"),
				(draft, sid, jid) -> inbox.deliver(draft, "1", sid, jid)), replies::add, failure -> fail(failure));
			assertNull(replies.poll(100, TimeUnit.MILLISECONDS));
		} finally { release.countDown(); engine.close(); }
	}

	@Test void deletingABindingWaitsForAdmissionAndCannotResurrectItsState() throws Exception {
		Engine engine = Engine.createTemp(Maps.empty());
		var release = new CountDownLatch(1);
		try {
			Engine.addDemoAssets(engine);
			engine.registerAdapter(new AgentAdapter() {
				@Override public CompletableFuture<ACell> wakeAgent(convex.core.data.AString owner,
						convex.core.data.AString agent, boolean force) { return CompletableFuture.completedFuture(null); }
			});
			var owner = Strings.create("did:test:binding-delete");
			engine.getVenueState().users().ensure(owner);
			var state = engine.adapterState("messaging-test");
			Object lifecycle = new Object();
			var running = new java.util.concurrent.atomic.AtomicBoolean(true);
			var sessions = new ConversationSessions(state, "deleting/sessions", lifecycle, running::get);
			var inbox = new covia.adapter.webhook.WebhookInbox(engine,state,"deleting/inbox");
			var router = new ConversationRouter(engine,new Spec("bot",owner.toString(),"deleting",null,null),sessions);
			router.runOperation("v/ops/agent/create",Maps.of(Fields.AGENT_ID,"deleting",Fields.CONFIG,Maps.of(Fields.OPERATION,"v/ops/llmagent/chat")));
			inbox.accept("1",Strings.create("input"));
			var entered = new CountDownLatch(1);
			var delivery = CompletableFuture.supplyAsync(() -> router.deliver("chat",Strings.create("input"),(draft,sid,jid) -> {
				entered.countDown();
				try { assertTrue(release.await(10,TimeUnit.SECONDS)); }
				catch (InterruptedException e) { throw new AssertionError(e); }
				return inbox.deliver(draft,"1",sid,jid);
			}));
			assertTrue(entered.await(10,TimeUnit.SECONDS));
			var stopping = new CountDownLatch(1);
			var deletion = CompletableFuture.runAsync(() -> {
				stopping.countDown();
				synchronized (engine) { synchronized (lifecycle) {
					running.set(false);
					state.delete("deleting");
				} }
			});
			assertTrue(stopping.await(10,TimeUnit.SECONDS));
			assertFalse(deletion.isDone());
			release.countDown();
			assertNotNull(delivery.get(10,TimeUnit.SECONDS));
			deletion.get(10,TimeUnit.SECONDS);
			assertNull(state.read("deleting"));
			assertThrows(IllegalStateException.class, () -> router.deliver("chat",Strings.create("late"),(draft,sid,jid) -> true));
			assertNull(state.read("deleting"));
		} finally { release.countDown(); engine.close(); }
	}

	@Test void deliveredInputSurvivesPersistentRestartBeforeAgentWake(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
		convex.core.data.AMap<convex.core.data.AString, ACell> config = Maps.of(covia.venue.Config.PORT, 0, covia.venue.Config.BIND_ADDRESS, "127.0.0.1",
			covia.venue.Config.STORE, temp.resolve("intake.etch").toString(),
			covia.venue.Config.SEED, convex.core.crypto.AKeyPair.createSeeded(74123).getSeed().toHexString());
		var owner = Strings.create("did:test:restart-intake");
		String sid;
		convex.core.data.Blob jobId;
		var first = covia.venue.server.VenueServer.launch(config);
		try {
			var engine = first.getEngine();
			Engine.addDemoAssets(engine);
			engine.getVenueState().users().ensure(owner);
			engine.registerAdapter(new AgentAdapter() {
				@Override public CompletableFuture<ACell> wakeAgent(convex.core.data.AString owner,
						convex.core.data.AString agent, boolean force) { return CompletableFuture.completedFuture(null); }
			});
			var state = engine.adapterState("messaging-test");
			var sessions = new ConversationSessions(state, "restart/sessions");
			var inbox = new covia.adapter.webhook.WebhookInbox(engine, state, "restart/inbox");
			var router = new ConversationRouter(engine, new Spec("bot", owner.toString(), "restart", null, null), sessions);
			router.runOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "restart", Fields.CONFIG,
				Maps.of(Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", "v/test/ops/llm")));
			inbox.accept("1", Strings.create("accepted before wake"));
			Job job = router.deliver("chat", Strings.create("accepted before wake"),
				(draft, session, id) -> inbox.deliver(draft, "1", session, id));
			assertFalse(job.isFinished());
			jobId = job.getID();
			sid = sessions.get("chat");
			assertEquals(covia.adapter.webhook.WebhookInbox.COMPLETE, inbox.get("1").get(Fields.STATUS));
		} finally { first.close(); }
		var second = covia.venue.server.VenueServer.launch(config);
		try {
			var engine = second.getEngine();
			var state = engine.adapterState("messaging-test");
			var sessions = new ConversationSessions(state, "restart/sessions");
			var inbox = new covia.adapter.webhook.WebhookInbox(engine, state, "restart/inbox");
			assertEquals(sid, sessions.get("chat"));
			assertEquals(covia.adapter.webhook.WebhookInbox.COMPLETE, inbox.get("1").get(Fields.STATUS));
			Job job = engine.jobs().getJob(jobId, covia.venue.RequestContext.of(owner));
			assertNotNull(job);
			assertTrue(job.awaitResult(10_000).toString().contains("accepted before wake"));
			assertEquals(Status.COMPLETE, job.getStatus());
			var router = new ConversationRouter(engine, new Spec("bot", owner.toString(), "restart", null, null), sessions);
			assertNull(router.deliver("chat", Strings.create("accepted before wake"),
				(draft, session, id) -> inbox.deliver(draft, "1", session, id)));
		} finally { second.close(); }
	}

	@Test void failedFlushKeepsAcceptedInputRecoverableAndDuplicateRetriesTheBarrier() throws Exception {
		class FlushEngine extends Engine {
			boolean failFlush;
			FlushEngine(convex.core.crypto.AKeyPair key) throws java.io.IOException {
				super(Maps.empty(), covia.venue.CoviaApplication.create(key), key);
			}
			@Override public void flush() {
				if (failFlush) throw new IllegalStateException("injected flush failure");
				super.flush();
			}
		}
		var engine = new FlushEngine(convex.core.crypto.AKeyPair.generate());
		engine.start();
		var wakes = new java.util.concurrent.atomic.AtomicInteger();
		try {
			Engine.addDemoAssets(engine);
			engine.registerAdapter(new AgentAdapter() {
				@Override public CompletableFuture<ACell> wakeAgent(convex.core.data.AString owner,
						convex.core.data.AString agent, boolean force) {
					wakes.incrementAndGet();
					return CompletableFuture.completedFuture(null);
				}
			});
			var owner = Strings.create("did:test:flush-intake");
			engine.getVenueState().users().ensure(owner);
			var state = engine.adapterState("messaging-test");
			var sessions = new ConversationSessions(state, "flush/sessions");
			var inbox = new covia.adapter.webhook.WebhookInbox(engine, state, "flush/inbox");
			var router = new ConversationRouter(engine, new Spec("bot", owner.toString(), "flush", null, null), sessions);
			router.runOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "flush", Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat")));
			inbox.accept("1", Strings.create("input"));
			wakes.set(0);
			engine.failFlush = true;
			assertThrows(IllegalStateException.class, () -> router.deliver("chat", Strings.create("input"),
				(draft, sid, jid) -> inbox.deliver(draft, "1", sid, jid)));
			assertEquals(0, wakes.get(), "do not execute before the durability barrier succeeds");
			var jid = convex.core.data.Blob.fromHex(inbox.get("1").get(Fields.JOB_ID).toString());
			assertFalse(engine.jobs().getJob(jid).isFinished(), "a failed flush must not fail accepted input");
			engine.failFlush = false;
			assertNull(router.deliver("chat", Strings.create("input"), (draft, sid, job) -> inbox.deliver(draft, "1", sid, job)));
			assertEquals(1, wakes.get());
			assertEquals(1, engine.getVenueState().users().get(owner).agent("flush")
				.getSessionPending(convex.core.data.Blob.fromHex(sessions.get("chat"))).count());
		} finally { engine.failFlush = false; engine.close(); }
	}

	private record Spec(String name, String userRef, String agent, String operation, ACell reply)
			implements MessagingBotSpec { }

	@Test void ordersTurnsAndResetsDespiteFailureOrObserverCancellation() throws Exception {
		Engine engine = Engine.createTemp(Maps.empty());
		CountDownLatch release = new CountDownLatch(1);
		try {
			var sessions = new ConversationSessions(engine.adapterState("messaging-test"), "config/bot/sessions");
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
			var state = engine.adapterState("slack-test");
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
			var state = engine.adapterState("messaging-test");
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
