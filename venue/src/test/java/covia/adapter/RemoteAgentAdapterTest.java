package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.*;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import covia.adapter.remote.VenueRemoteAgent;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.UcanTokens;
import covia.grid.hitl.Hitl;
import covia.venue.*;

class RemoteAgentAdapterTest {
	private static final String OP = "v/ops/remoteagent/request";
	private static final String ID = "00112233445566778899aabbccddeeff";

	@Test void localProxyPreservesAuthorityAndCompletesAgentTasks() throws Exception {
		Engine engine = TestEngine.ENGINE;
		RequestContext ctx = RequestContext.of(Strings.create("did:test:remoteagent:" + System.nanoTime()));
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "worker",
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")), ctx).awaitResult(5000);
		AMap<AString, ACell> params = Maps.of(Fields.AGENT_ID, "g/worker", Fields.INPUT, Maps.of("task", "first"));
		Job first = engine.jobs().invokeOperation(OP, params, ctx);
		ACell result = first.future().get(10, TimeUnit.SECONDS);
		assertEquals(Strings.create("first"), RT.getIn(result, Fields.OUTPUT, "completed", "task"), result.toString());
		ACell session = first.getData().get(Fields.SESSION_ID);
		assertNotNull(session);
		Job second = engine.jobs().invokeOperation(OP, params.assoc(Fields.SESSION_ID, session), ctx);
		second.future().get(10, TimeUnit.SECONDS);
		assertEquals(session, second.getData().get(Fields.SESSION_ID));
		assertNotEquals(RemoteJobs.descriptor(first).get(RemoteJobs.REMOTE_ID), RemoteJobs.descriptor(second).get(RemoteJobs.REMOTE_ID));

		RequestContext scoped = ctx.withCaps(Vectors.of(Capability.create(Strings.create(OP), Strings.create("invoke"))));
		Job denied = engine.jobs().invokeOperation(OP, params, scoped);
		assertThrows(Exception.class, () -> denied.future().get(5, TimeUnit.SECONDS));
		assertEquals(Status.FAILED, denied.getStatus());
	}

	@Test void proxyWorksAcrossTwoRealVenuesAndAsAJavaObject() throws Exception {
		String agent = "Proxy" + Long.toUnsignedString(System.nanoTime(), 36);
		Job created = TwoVenueTestServer.COVIA_B.invokeSync("v/ops/agent/create", Maps.of(Fields.AGENT_ID, agent,
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")));
		assertEquals(Status.COMPLETE, created.getStatus(), created.getErrorMessage());
		AString address = Strings.create(TwoVenueTestServer.DID_B + ":public/g/" + agent);
		Job mirror = TwoVenueTestServer.COVIA_A.invokeAndWait(Strings.create(OP), Maps.of(
			Fields.VENUE, TwoVenueTestServer.BASE_URL_B, Fields.AGENT_ID, address,
			Fields.INPUT, Maps.of("task", "over HTTP")));
		assertEquals(Status.COMPLETE, mirror.getStatus(), mirror.getErrorMessage());
		assertEquals(Strings.create("over HTTP"), RT.getIn(mirror.getOutput(), Fields.OUTPUT, "completed", "task"), mirror.getOutput().toString());
		AString session = RT.ensureString(mirror.getData().get(Fields.SESSION_ID));
		assertNotNull(session);
		VenueRemoteAgent proxy = new VenueRemoteAgent(TwoVenueTestServer.COVIA_B, address);
		Job next = proxy.request(Maps.of(Fields.INPUT, Maps.of("task", "next"), Fields.SESSION_ID, session)).get(5, TimeUnit.SECONDS);
		// The task runs on venue B: wait on its Job there, then read it through the proxy.
		Job served = TwoVenueTestServer.ENGINE_B.jobs().getJob(next.getID());
		if (served != null) await(served, Job::isFinished);
		AMap<AString, ACell> task = proxy.task(next.getID()).join();
		assertEquals(Status.COMPLETE, task.get(Fields.STATUS));
		assertEquals(session, task.get(Fields.SESSION_ID));
	}

	@Test void remotePrivateAgentRequiresTheExplicitCallerCredential() throws Exception {
		AKeyPair key = AKeyPair.generate();
		AString owner = UCAN.toDIDKey(key.getAccountKey());
		RequestContext ctx = RequestContext.of(owner);
		Engine destination = TwoVenueTestServer.ENGINE_B;
		destination.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "private",
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")), ctx).awaitResult(5000);
		AMap<AString, ACell> input = Maps.of(Fields.VENUE, TwoVenueTestServer.BASE_URL_B,
			Fields.AGENT_ID, owner + "/g/private", Fields.INPUT, Maps.of("task", "private request"));
		Job denied = TwoVenueTestServer.ENGINE_A.jobs().invokeOperation(OP, input, ctx);
		assertThrows(Exception.class, () -> denied.future().get(5, TimeUnit.SECONDS));
		assertEquals(Status.FAILED, denied.getStatus());
		String credential = UcanTokens.identityToken(key, TwoVenueTestServer.DID_B, 300);
		RequestContext authenticated = ctx.withRawUcans(Vectors.of(Strings.create(credential)));
		Job accepted = TwoVenueTestServer.ENGINE_A.jobs().invokeOperation(OP,
			input.assoc(Strings.create("authenticateAs"), Strings.create("caller")), authenticated);
		accepted.future().get(5, TimeUnit.SECONDS);
		assertEquals(Status.COMPLETE, accepted.getStatus());
		assertFalse(JSON.toString(RemoteJobs.descriptor(accepted)).contains(credential));
	}

	/** Controlled wire peer tests observation and message/cancel semantics independently of agent inference. */
	private static final class Peer implements AutoCloseable {
		final HttpServer server;
		final AtomicInteger submits = new AtomicInteger(), polls = new AtomicInteger(), deliveries = new AtomicInteger(), cancels = new AtomicInteger();
		volatile boolean loseAck, refuseMessage;
		volatile AString status = Status.INPUT_REQUIRED;
		volatile ACell submitted, delivered;
		Peer() throws Exception {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/api/v1/", exchange -> {
				String path = exchange.getRequestURI().getPath();
				String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				int code = 200;
				String response;
				if (path.endsWith("/invoke")) {
					submits.incrementAndGet(); submitted = JSON.parse(body); code = 201;
					response = loseAck ? "lost acknowledgement" : snapshot();
				} else if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/jobs/" + ID)) {
					deliveries.incrementAndGet(); delivered = JSON.parse(body);
					code = refuseMessage ? 403 : 202;
					if (!refuseMessage) status = Status.COMPLETE;
					response = "{}";
				} else if (path.endsWith("/cancel")) {
					cancels.incrementAndGet(); status = Status.CANCELLED; response = snapshot();
				} else { polls.incrementAndGet(); response = snapshot(); }
				handled();
				byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(code, bytes.length);
				try (var out = exchange.getResponseBody()) { out.write(bytes); }
			});
			server.start();
		}
		/** Signalled as each request is handled. */
		final TestSignal handledSignal = new TestSignal();
		private void handled() { handledSignal.signal(); }
		/** Waits until {@code condition} over the peer's counters holds, checked as each request is handled. */
		void await(BooleanSupplier condition) { handledSignal.await("the peer to be asked", condition); }
		String snapshot() { return JSON.toString(Maps.of(Fields.ID, ID, Fields.STATUS, status,
			Fields.SESSION_ID, "conversation", Fields.OUTPUT, Maps.of("reply", "native"))); }
		AMap<AString, ACell> input() { return Maps.of(Fields.VENUE, "http://127.0.0.1:" + server.getAddress().getPort(),
			Fields.AGENT_ID, "did:test:owner/g/worker", Fields.INPUT, Maps.of("task", "do work")); }
		@Override public void close() { server.stop(0); }
	}
	private static Engine engine() {
		Engine engine = Engine.createTemp(null); Engine.addDemoAssets(engine); return engine;
	}

	@Test void continuationAcknowledgementAndRecoveryKeepTheSameTask() throws Exception {
		try (Peer peer = new Peer()) {
			Engine engine = engine();
			try {
				Job mirror = engine.jobs().invokeOperation(OP, peer.input(), engine.venueContext());
				await(mirror, j -> Status.INPUT_REQUIRED.equals(j.getStatus()));
				assertFalse(mirror.future().isDone());
				assertEquals(Strings.create("v/ops/agent/request"), RT.getIn(peer.submitted, Fields.OPERATION));
				assertEquals(Strings.create("conversation"), mirror.getData().get(Fields.SESSION_ID));
				RemoteAgentAdapter adapter = (RemoteAgentAdapter) engine.getAdapter("remoteagent");
				adapter.suspendJob(mirror);
				int polls = peer.polls.get();
				adapter.recoverJob(mirror);
				peer.await(() -> peer.polls.get() > polls);
				peer.refuseMessage = true;
				assertThrows(RuntimeException.class, () -> engine.jobs().deliverMessage(mirror.getID(), Maps.of("content", "continue"), engine.venueContext()));
				assertEquals(Status.INPUT_REQUIRED, mirror.getStatus());
				peer.refuseMessage = false;
				engine.jobs().deliverMessage(mirror.getID(), Maps.of("content", "continue"), engine.venueContext());
				mirror.future().get(5, TimeUnit.SECONDS);
				assertEquals(1, peer.submits.get()); assertEquals(2, peer.deliveries.get());
				assertEquals(Strings.create("continue"), RT.getIn(peer.delivered, "content"));
			} finally { engine.close(); }
		}
	}

	@Test void cancellationReachesTheAcceptedTask() throws Exception {
		try (Peer peer = new Peer()) {
			Engine engine = engine();
			try {
				Job mirror = engine.jobs().invokeOperation(OP, peer.input(), engine.venueContext());
				await(mirror, j -> Status.INPUT_REQUIRED.equals(j.getStatus()));
				mirror.cancel(); peer.await(() -> peer.cancels.get() == 1);
				assertEquals(Status.CANCELLED, mirror.getStatus()); assertEquals(1, peer.submits.get());
			} finally { engine.close(); }
		}
	}

	@Test void lostAcknowledgementIsNeverReplayedOnRecovery() throws Exception {
		try (Peer peer = new Peer()) {
			peer.loseAck = true; Engine engine = engine();
			try {
				Job mirror = engine.jobs().invokeOperation(OP, peer.input(), engine.venueContext());
				await(mirror, j -> RT.getIn(j.getData(), RemoteJobs.DELEGATION, RemoteJobs.NOT_FOLLOWED) != null);
				assertEquals(Strings.create("acceptance-unknown"), engine.remoteJobs().kept(mirror).get(RemoteJobs.OBSERVATION));
				engine.getAdapter("remoteagent").recoverJob(mirror);
				assertFalse(mirror.isFinished()); assertEquals(1, peer.submits.get()); assertEquals(0, peer.polls.get());
			} finally { engine.close(); }
		}
	}

	@Test void freshEngineReattachesWithoutResubmitting() throws Exception {
		try (Peer peer = new Peer()) {
			var cursor = convex.lattice.cursor.Cursors.root(convex.core.data.Index.<convex.core.data.Keyword, ACell>none());
			AKeyPair key = AKeyPair.generate();
			AMap<AString, ACell> config = Maps.of(Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0));
			Engine first = covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null)).mount(key, config).start();
			Engine.addDemoAssets(first);
			Job original;
			try {
				original = first.jobs().invokeOperation(OP, peer.input(), first.venueContext());
				await(original, j -> Status.INPUT_REQUIRED.equals(j.getStatus()));
			} finally { first.close(); }
			assertFalse(original.isFinished()); assertEquals(0, peer.cancels.get());
			Engine second = covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null)).mount(key, config).start();
			try {
				Engine.addDemoAssets(second);
				second.jobs().recoverJobs();
				Job recovered = second.jobs().getJob(original.getID(), second.venueContext());
				assertNotNull(recovered);
				second.jobs().deliverMessage(recovered.getID(), Maps.of("content", "resume"), second.venueContext());
				recovered.future().get(5, TimeUnit.SECONDS);
				assertEquals(1, peer.submits.get()); assertEquals(1, peer.deliveries.get());
				assertEquals(Strings.create(ID), RemoteJobs.descriptor(recovered).get(RemoteJobs.REMOTE_ID));
			} finally { second.close(); }
		}
	}

	@Test void agentFacadeRecoversParentAndChildWithoutResubmission() throws Exception {
		try (Peer peer = new Peer()) {
			var cursor = convex.lattice.cursor.Cursors.root(convex.core.data.Index.<convex.core.data.Keyword, ACell>none());
			AKeyPair key = AKeyPair.generate();
			// The venue's own agent dials the loopback peer, and agents pass the
			// outbound guard, so the operator allows the peer's host
			AMap<AString, ACell> config = Maps.of(Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0),
				Config.ADAPTERS, Maps.of("http", Maps.of("allowedHosts", Vectors.of("127.0.0.1"))));
			Engine first = covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null)).mount(key, config).start();
			Engine.addDemoAssets(first);
			Job original;
			try {
				first.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "proxy",
					"agent", peer.input().dissoc(Fields.INPUT).assoc(Fields.ADAPTER, Strings.create("remoteagent"))),
					first.venueContext()).awaitResult(5000);
				original = first.jobs().invokeOperation("v/ops/agent/request", Maps.of(Fields.AGENT_ID, "proxy", Fields.INPUT, Maps.empty()), first.venueContext());
				await(original, j -> Status.INPUT_REQUIRED.equals(j.getStatus()));
			} finally { first.close(); }
			assertFalse(original.isFinished());
			Engine second = covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null)).mount(key, config).start();
			try {
				Engine.addDemoAssets(second);
				second.jobs().recoverJobs();
				Job recovered = second.jobs().getJob(original.getID(), second.venueContext());
				assertNotNull(recovered);
				second.jobs().deliverMessage(recovered.getID(), Maps.of("content", "resume"), second.venueContext());
				recovered.future().get(5, TimeUnit.SECONDS);
				assertEquals(1, peer.submits.get());
				assertEquals(1, peer.deliveries.get());
				assertEquals(original.getData().get(Fields.SESSION_ID), recovered.getData().get(Fields.SESSION_ID));
				assertEquals(Strings.create("native"), RT.getIn(recovered.getOutput(), Fields.OUTPUT, "reply"));
			} finally { second.close(); }
		}
	}

	/**
	 * Calls to agents on this venue resume after a restart as their callers,
	 * in the context their jobs kept: each follows the same task to its end,
	 * an external agent served by one answers, and cancelling one reaches its
	 * task. A call whose caller cannot act after the restart ends, naming its
	 * task, which runs on.
	 */
	@Test void localCallsResumeAfterARestartAsTheirCallers() throws Exception {
		Root<Index<Keyword, ACell>> cursor = Cursors.root(Index.<Keyword, ACell>none());
		AKeyPair key = AKeyPair.generate();
		AString user = TestEngine.uniqueDID("RemoteAgentResume");
		RequestContext ctx = RequestContext.of(user);
		Job asked, waited, served, anonymous;
		Engine first = start(cursor, key, true);
		try {
			agent(first, ctx, "asker", "v/test/ops/askhuman");
			agent(first, ctx, "waiter", "v/test/ops/never");
			agent(first, ctx, "helper", "v/test/ops/askhuman");
			first.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "front",
				"agent", Maps.of(Fields.ADAPTER, "remoteagent", Fields.AGENT_ID, "helper")), ctx).awaitResult(5000);
			RequestContext anon = first.context(first.publicPrincipal());
			agent(first, anon, "lobby", "v/test/ops/never");
			try (AgentWatch asker = AgentWatch.open(first, user, "asker");
					AgentWatch helper = AgentWatch.open(first, user, "helper")) {
				asked = first.jobs().invokeOperation(OP, Maps.of(Fields.AGENT_ID, "asker", Fields.INPUT, Maps.of("ask", "decide")), ctx);
				served = first.jobs().invokeOperation("v/ops/agent/request", Maps.of(Fields.AGENT_ID, "front",
					Fields.INPUT, Maps.of("ask", "decide too")), ctx);
				waited = first.jobs().invokeOperation(OP, Maps.of(Fields.AGENT_ID, "waiter", Fields.INPUT, Maps.of("task", "wait")), ctx);
				anonymous = first.jobs().invokeOperation(OP, Maps.of(Fields.AGENT_ID, "lobby", Fields.INPUT, Maps.of("task", "wait")), anon);
				asker.awaitRest(1);
				helper.awaitRest(1);
			}
			for (Job call : new Job[] {asked, waited, anonymous}) await(call, RemoteAgentAdapterTest::following);
		} finally { first.close(); }

		Engine second = start(cursor, key, false);
		try {
			Job resumed = second.jobs().getJob(asked.getID(), ctx);
			assertFalse(resumed.isFinished(), "the call follows its task again");
			assertEquals(RemoteJobs.descriptor(asked).get(RemoteJobs.REMOTE_ID), RemoteJobs.descriptor(resumed).get(RemoteJobs.REMOTE_ID));
			answer(second, ctx, "asker", "carry on");
			assertTrue(resumed.future().get(10, TimeUnit.SECONDS).toString().contains("carry on"),
				"the call finishes with its task's output: " + resumed.getOutput());

			answer(second, ctx, "helper", "go ahead");
			Job answered = second.jobs().getJob(served.getID(), ctx);
			assertTrue(answered.future().get(10, TimeUnit.SECONDS).toString().contains("go ahead"),
				"an external agent the call serves answers: " + answered.getOutput());

			Job cancelled = second.jobs().getJob(waited.getID(), ctx);
			Job waiting = second.jobs().getJob(taskOf(waited), ctx);
			assertFalse(waiting.isFinished());
			assertDoesNotThrow(() -> second.jobs().deliverMessage(cancelled.getID(), Maps.of("content", "still there?"), ctx),
				"a message reaches the task as the call's caller");
			cancelled.cancel();
			assertEquals(Status.CANCELLED, waiting.getStatus(), "cancelling the call reaches its task");

			// Public access is off now, so the anonymous caller cannot act, nor read its job
			AMap<AString, ACell> ended = second.getVenueState().users().user(second.publicPrincipal()).jobs().getRecord(anonymous.getID());
			assertEquals(Status.FAILED, ended.get(Fields.STATUS));
			assertEquals(Strings.create(RemoteAgentAdapter.CALLER_CANNOT_ACT.formatted(Engine.PUBLIC_ACCESS_OFF,
				taskOf(anonymous).toHexString())), ended.get(Fields.ERROR));
			assertFalse(second.jobs().getJob(taskOf(anonymous)).isFinished(), "the task of a call that ended runs on");
		} finally { second.close(); }
	}

	/** A call that had not recorded its task when the venue stopped cannot be followed: it ends, and its task runs on. */
	@Test void localCallWithNoRecordedTaskEndsAtRecovery() throws Exception {
		Engine engine = engine();
		try {
			RequestContext ctx = engine.venueContext();
			agent(engine, ctx, "waiting", "v/test/ops/never");
			Job mirror = engine.jobs().invokeOperation(OP, Maps.of(Fields.AGENT_ID, "waiting", Fields.INPUT, Maps.of("task", "wait")), ctx);
			await(mirror, RemoteAgentAdapterTest::following);
			Job task = engine.jobs().getJob(taskOf(mirror), ctx);
			RemoteAgentAdapter adapter = (RemoteAgentAdapter) engine.getAdapter("remoteagent");
			adapter.suspendJob(mirror);
			// As if the venue had stopped before the agent's task was recorded
			mirror.update(d -> d.assoc(RemoteJobs.DELEGATION, RemoteJobs.descriptor(mirror).dissoc(RemoteJobs.REMOTE_ID)));
			adapter.recoverJob(mirror);
			assertEquals(Status.FAILED, mirror.getStatus());
			assertEquals(RemoteAgentAdapter.LOCAL_INTERRUPTED, mirror.getErrorMessage());
			assertFalse(task.isFinished(), "the agent's task runs on");
		} finally { engine.close(); }
	}

	/** A venue over {@code cursor}, launched as it is at boot, with public access on or off. */
	private static Engine start(Root<Index<Keyword, ACell>> cursor, AKeyPair key, boolean publicAccess) throws Exception {
		AMap<AString, ACell> config = Maps.of(Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, publicAccess, Config.CAPS, "unrestricted")));
		return covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null)).mount(key, config).launch();
	}

	private static void agent(Engine engine, RequestContext ctx, String id, String operation) {
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id,
			Fields.CONFIG, Maps.of(Fields.OPERATION, operation)), ctx).awaitResult(5000);
	}

	/** Answers the one open ask of {@code ctx}'s agent {@code id}. */
	private static void answer(Engine engine, RequestContext ctx, String id, String answer) {
		Agent agent = engine.getVenueState().users().user(ctx.getUserDID()).agent(id);
		assertEquals(1, agent.getPending().count(), "the agent has one open ask");
		String ask = agent.getPending().entrySet().iterator().next().getKey().toHexString();
		engine.jobs().invokeOperation("v/ops/hitl/respond",
			Hitl.answer(ask).answer("next", answer).build(), ctx).awaitResult(5000);
	}

	/** Whether a call has its agent's task and has seen it. */
	private static boolean following(Job call) {
		AMap<AString, ACell> delegation = RemoteJobs.descriptor(call);
		return delegation != null && delegation.get(RemoteJobs.REMOTE_ID) != null && delegation.get(RemoteJobs.REMOTE_STATUS) != null;
	}

	private static Blob taskOf(Job call) {
		return Job.parseID(RemoteJobs.descriptor(call).get(RemoteJobs.REMOTE_ID));
	}

	@Test void localCancellationHasReachedTheChildWhenItReturns() throws Exception {
		Engine engine = engine();
		try {
			RequestContext ctx = engine.venueContext();
			engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "waiting",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never")), ctx).awaitResult(5000);
			Job mirror = engine.jobs().invokeOperation(OP, Maps.of(Fields.AGENT_ID, "waiting", Fields.INPUT, Maps.of("task", "wait")), ctx);
			await(mirror, j -> RemoteJobs.descriptor(j) != null && RemoteJobs.descriptor(j).get(RemoteJobs.REMOTE_STATUS) != null);
			Job child = engine.jobs().getJob(Job.parseID(RemoteJobs.descriptor(mirror).get(RemoteJobs.REMOTE_ID)), ctx);
			mirror.cancel();
			assertEquals(Status.CANCELLED, child.getStatus());
			assertTrue(child.future().isDone());
			assertEquals(Status.CANCELLED, mirror.getStatus());
		} finally { engine.close(); }
	}

	/** Waits until {@code job} satisfies {@code condition}, checked as the Job is updated. */
	private static void await(Job job, java.util.function.Predicate<Job> condition) {
		TestEngine.awaitJob(job, condition, 10_000, () -> "job did not reach the expected state: " + job.getData());
	}
}
