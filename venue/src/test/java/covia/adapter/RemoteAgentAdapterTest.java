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
import covia.adapter.remote.VenueRemoteAgent;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.UcanTokens;
import covia.lattice.Covia;
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
		await(() -> Status.COMPLETE.equals(proxy.task(next.getID()).join().get(Fields.STATUS)));
		assertEquals(session, proxy.task(next.getID()).join().get(Fields.SESSION_ID));
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
				byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(code, bytes.length);
				try (var out = exchange.getResponseBody()) { out.write(bytes); }
			});
			server.start();
		}
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
				await(() -> Status.INPUT_REQUIRED.equals(mirror.getStatus()));
				assertFalse(mirror.future().isDone());
				assertEquals(Strings.create("v/ops/agent/request"), RT.getIn(peer.submitted, Fields.OPERATION));
				assertEquals(Strings.create("conversation"), mirror.getData().get(Fields.SESSION_ID));
				RemoteAgentAdapter adapter = (RemoteAgentAdapter) engine.getAdapter("remoteagent");
				adapter.suspendJob(mirror);
				int polls = peer.polls.get();
				adapter.recoverJob(mirror);
				await(() -> peer.polls.get() > polls);
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
				await(() -> Status.INPUT_REQUIRED.equals(mirror.getStatus()));
				mirror.cancel(); await(() -> peer.cancels.get() == 1);
				assertEquals(Status.CANCELLED, mirror.getStatus()); assertEquals(1, peer.submits.get());
			} finally { engine.close(); }
		}
	}

	@Test void lostAcknowledgementIsNeverReplayedOnRecovery() throws Exception {
		try (Peer peer = new Peer()) {
			peer.loseAck = true; Engine engine = engine();
			try {
				Job mirror = engine.jobs().invokeOperation(OP, peer.input(), engine.venueContext());
				await(() -> Strings.create("acceptance-unknown").equals(RemoteJobs.descriptor(mirror).get(RemoteJobs.OBSERVATION)));
				engine.getAdapter("remoteagent").recoverJob(mirror);
				assertFalse(mirror.isFinished()); assertEquals(1, peer.submits.get()); assertEquals(0, peer.polls.get());
			} finally { engine.close(); }
		}
	}

	@Test void freshEngineReattachesWithoutResubmitting() throws Exception {
		try (Peer peer = new Peer()) {
			var cursor = convex.lattice.cursor.Cursors.createLattice(Covia.ROOT);
			AKeyPair key = AKeyPair.generate();
			AMap<AString, ACell> config = Maps.of(Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0));
			Engine first = new Engine(config, cursor, key).start();
			Engine.addDemoAssets(first);
			Job original;
			try {
				original = first.jobs().invokeOperation(OP, peer.input(), first.venueContext());
				await(() -> Status.INPUT_REQUIRED.equals(original.getStatus()));
			} finally { first.close(); }
			assertFalse(original.isFinished()); assertEquals(0, peer.cancels.get());
			Engine second = new Engine(config, cursor, key).start();
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
			var cursor = convex.lattice.cursor.Cursors.createLattice(Covia.ROOT);
			AKeyPair key = AKeyPair.generate();
			AMap<AString, ACell> config = Maps.of(Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0));
			Engine first = new Engine(config, cursor, key).start();
			Engine.addDemoAssets(first);
			Job original;
			try {
				first.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "proxy",
					"agent", peer.input().dissoc(Fields.INPUT).assoc(Fields.ADAPTER, Strings.create("remoteagent"))),
					first.venueContext()).awaitResult(5000);
				original = first.jobs().invokeOperation("v/ops/agent/request", Maps.of(Fields.AGENT_ID, "proxy", Fields.INPUT, Maps.empty()), first.venueContext());
				await(() -> Status.INPUT_REQUIRED.equals(original.getStatus()));
			} finally { first.close(); }
			assertFalse(original.isFinished());
			Engine second = new Engine(config, cursor, key).start();
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

	@Test void localRecoveryRefusesToReconstructAuthorityFromIdentity() throws Exception {
		Engine engine = engine();
		try {
			RequestContext ctx = engine.venueContext();
			engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "waiting",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never")), ctx).awaitResult(5000);
			Job mirror = engine.jobs().invokeOperation(OP, Maps.of(Fields.AGENT_ID, "waiting", Fields.INPUT, Maps.of("task", "wait")), ctx);
			await(() -> RemoteJobs.descriptor(mirror).get(RemoteJobs.REMOTE_ID) != null);
			ACell id = RemoteJobs.descriptor(mirror).get(RemoteJobs.REMOTE_ID);
			engine.getAdapter("remoteagent").recoverJob(mirror);
			assertEquals(Status.FAILED, mirror.getStatus());
			assertEquals(RemoteAgentAdapter.LOCAL_INTERRUPTED, mirror.getErrorMessage());
			assertEquals(id, RemoteJobs.descriptor(mirror).get(RemoteJobs.REMOTE_ID));
		} finally { engine.close(); }
	}

	private static void await(BooleanSupplier condition) throws Exception {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
		assertTrue(condition.getAsBoolean());
	}
}
