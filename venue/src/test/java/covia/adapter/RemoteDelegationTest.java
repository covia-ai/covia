package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.RootLatticeCursor;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.Covia;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RemoteJobs;

class RemoteDelegationTest {
	/** Gates prove local cancellation completes while remote IO remains blocked. */
	private static final class CancellationPeer implements AutoCloseable {
		final HttpServer server;
		final Engine engine = Engine.createTemp(null);
		final String protocol;
		final CountDownLatch submitted = new CountDownLatch(1);
		final CountDownLatch cancelReceived = new CountDownLatch(1);
		final CountDownLatch cancelResponse = new CountDownLatch(1);
		final AtomicInteger cancels = new AtomicInteger();
		volatile CountDownLatch acceptance = new CountDownLatch(0);
		volatile int cancelCode = 200;
		volatile String cancelStatus = "CANCELLED";
		volatile boolean rpcReject;
		volatile boolean malformed;
		volatile boolean wrongId;
		volatile String a2aCancelState;
		CancellationPeer(String protocol) throws Exception {
			this.protocol = protocol;
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.setExecutor(AAdapter.VIRTUAL_EXECUTOR);
			server.createContext("/", exchange -> {
				String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				String path = exchange.getRequestURI().getPath();
				boolean a2a = protocol.equals("a2a");
				String method = a2a ? convex.core.lang.RT.getIn(convex.core.util.JSON.parse(request), "method").toString() : "";
				boolean submit = a2a ? org.a2aproject.sdk.spec.A2AMethods.SEND_MESSAGE_METHOD.equals(method) : path.endsWith("/invoke");
				boolean cancel = a2a ? org.a2aproject.sdk.spec.A2AMethods.CANCEL_TASK_METHOD.equals(method) : path.endsWith("/cancel");
				try {
					if (submit) { submitted.countDown(); acceptance.await(); }
					if (cancel) { cancels.incrementAndGet(); cancelReceived.countDown(); cancelResponse.await(); }
				} catch (InterruptedException e) { Thread.currentThread().interrupt(); exchange.close(); return; }
				String state = cancel ? cancelStatus : "STARTED";
				String responseId = cancel && wrongId ? "ffeeddccbbaa99887766554433221100" : ID;
				String body = "{\"id\":\"" + responseId + "\",\"status\":\"" + state + "\"}";
				if (a2a) {
					String taskState = state.equals("STARTED") ? "WORKING" : state.equals("COMPLETE") ? "COMPLETED" : "CANCELED";
					if (cancel && a2aCancelState != null) taskState = a2aCancelState;
					body = "{\"jsonrpc\":\"2.0\",\"id\":\"reply\",\"result\":{\"task\":{\"id\":\"" + responseId
						+ "\",\"contextId\":\"ctx\",\"status\":{\"state\":\"TASK_STATE_" + taskState + "\"}}}}";
					if (cancel && rpcReject) body = "{\"jsonrpc\":\"2.0\",\"id\":\"reply\",\"error\":{\"code\":-32002,\"message\":\"private-token\"}}";
				}
				if (cancel && malformed) body = "invalid private-token response";
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(cancel ? cancelCode : submit && !a2a ? 201 : 200, bytes.length);
				try (var out = exchange.getResponseBody()) { out.write(bytes); }
			});
			server.start();
			engine.registerAdapter(new GridAdapter());
			engine.registerAdapter(new RemoteAgentAdapter());
			engine.registerAdapter(new A2AAdapter());
			HTTPAdapter http = new HTTPAdapter();
			engine.registerAdapter(http);
			http.addAllowedHost("127.0.0.1");
		}
		String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
		Job submit() {
			String op = protocol.equals("a2a") ? "a2a:rawSend" : protocol.equals("grid") ? "grid:run" : "remoteagent:request";
			ACell message = Maps.of("role", "user", "parts", convex.core.data.Vectors.of(Maps.of("type", "text", "text", "hello")));
			return engine.jobs().invokeOperation(Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, op)),
				Maps.of(Fields.VENUE, url(), Fields.URL, url(), Fields.OPERATION, "v/ops/work",
					Fields.AGENT_ID, "g/worker", Fields.INPUT, Maps.of("task", "work"), Fields.MESSAGE, message), engine.venueContext());
		}
		@Override public void close() {
			acceptance.countDown(); cancelResponse.countDown(); engine.close(); server.stop(0);
		}
	}

	@Test void gridCancellationIsImmediate() throws Exception { cancellationPropagatesLocallyBeforeRemoteAcknowledgement("grid"); }
	@Test void a2aCancellationIsImmediate() throws Exception { cancellationPropagatesLocallyBeforeRemoteAcknowledgement("a2a"); }
	@Test void remoteagentCancellationIsImmediate() throws Exception { cancellationPropagatesLocallyBeforeRemoteAcknowledgement("remoteagent"); }

	private void cancellationPropagatesLocallyBeforeRemoteAcknowledgement(String protocol) throws Exception {
		try (CancellationPeer peer = new CancellationPeer(protocol)) {
			Job child = peer.submit();
			await(() -> RemoteJobs.descriptor(child) != null && RemoteJobs.descriptor(child).get(RemoteJobs.REMOTE_ID) != null);
			Job parent = Job.create(Maps.of(Fields.STATUS, Status.STARTED));
			parent.setCancelHook(child::cancel);
			java.util.concurrent.CompletableFuture.runAsync(parent::cancel).get(2, TimeUnit.SECONDS);
			assertEquals(Status.CANCELLED, child.getStatus());
			assertTrue(parent.future().isDone());
			assertTrue(child.future().isDone());
			assertTrue(peer.cancelReceived.await(5, TimeUnit.SECONDS));
			assertEquals(Strings.create("requested"), convex.core.lang.RT.getIn(child.getData(), Fields.CANCELLATION, RemoteJobs.OUTCOME));
			peer.cancelResponse.countDown();
			awaitOutcome(child, "acknowledged");
			await(() -> Strings.create("acknowledged").equals(convex.core.lang.RT.getIn(
				peer.engine.jobs().getJobData(child.getID(), peer.engine.venueContext()), Fields.CANCELLATION, RemoteJobs.OUTCOME)));
			child.completeWith(Strings.create("late result"));
			child.cancel();
			assertEquals(Status.CANCELLED, child.getStatus());
			assertEquals(1, peer.cancels.get());
		}
	}

	@Test void gridLateAcceptance() throws Exception { lateAcceptanceStillCancelsRemoteWork("grid"); }
	@Test void a2aLateAcceptance() throws Exception { lateAcceptanceStillCancelsRemoteWork("a2a"); }
	@Test void remoteagentLateAcceptance() throws Exception { lateAcceptanceStillCancelsRemoteWork("remoteagent"); }

	private void lateAcceptanceStillCancelsRemoteWork(String protocol) throws Exception {
		try (CancellationPeer peer = new CancellationPeer(protocol)) {
			peer.acceptance = new CountDownLatch(1);
			Job job = peer.submit();
			assertTrue(peer.submitted.await(5, TimeUnit.SECONDS));
			job.cancel();
			assertTrue(job.future().isDone());
			peer.acceptance.countDown();
			assertTrue(peer.cancelReceived.await(5, TimeUnit.SECONDS));
			peer.cancelResponse.countDown();
			awaitOutcome(job, "acknowledged");
			assertEquals(Strings.create(ID), convex.core.lang.RT.getIn(job.getData(), Fields.CANCELLATION, RemoteJobs.REMOTE_ID));
			assertEquals(Status.CANCELLED, job.getStatus());
			assertEquals(1, peer.cancels.get());
		}
	}

	@Test void gridCancellationOutcomes() throws Exception { rejectionUncertaintyAndRemoteCompletionDoNotChangeLocalCancellation("grid"); }
	@Test void a2aCancellationOutcomes() throws Exception { rejectionUncertaintyAndRemoteCompletionDoNotChangeLocalCancellation("a2a"); }
	@Test void remoteagentCancellationOutcomes() throws Exception { rejectionUncertaintyAndRemoteCompletionDoNotChangeLocalCancellation("remoteagent"); }

	private void rejectionUncertaintyAndRemoteCompletionDoNotChangeLocalCancellation(String protocol) throws Exception {
		for (int scenario = 0; scenario < 6; scenario++) {
			try (CancellationPeer peer = new CancellationPeer(protocol)) {
				peer.cancelCode = scenario == 0 ? 409 : scenario == 1 ? 503 : 200;
				peer.malformed = scenario == 2;
				peer.cancelStatus = scenario == 3 ? "COMPLETE" : scenario == 4 ? "STARTED" : "CANCELLED";
				peer.wrongId = scenario == 5;
				Job job = peer.submit();
				await(() -> RemoteJobs.descriptor(job) != null && RemoteJobs.descriptor(job).get(RemoteJobs.REMOTE_ID) != null);
				job.cancel(); peer.cancelResponse.countDown();
				awaitOutcome(job, scenario == 0 ? "rejected" : scenario == 3 ? "finished" : "unknown");
				assertEquals(Status.CANCELLED, job.getStatus());
				assertFalse(job.getData().toString().contains("private-token"));
				assertEquals(1, peer.cancels.get());
			}
		}
	}

	@Test void a2aProtocolRejectionIsRetainedWithoutErrorText() throws Exception {
		try (CancellationPeer peer = new CancellationPeer("a2a")) {
			peer.rpcReject = true;
			Job job = peer.submit();
			await(() -> RemoteJobs.descriptor(job) != null && RemoteJobs.descriptor(job).get(RemoteJobs.REMOTE_ID) != null);
			job.cancel(); peer.cancelResponse.countDown();
			awaitOutcome(job, "rejected");
			assertEquals(convex.core.data.prim.CVMLong.create(-32002), convex.core.lang.RT.getIn(job.getData(), Fields.CANCELLATION, Fields.ERROR_CODE));
			assertFalse(job.getData().toString().contains("private-token"));
		}
	}

	@Test void unspecifiedA2aStateIsNotEvidenceOfRemoteCompletion() throws Exception {
		try (CancellationPeer peer = new CancellationPeer("a2a")) {
			peer.a2aCancelState = "UNSPECIFIED";
			Job job = peer.submit();
			await(() -> RemoteJobs.descriptor(job) != null && RemoteJobs.descriptor(job).get(RemoteJobs.REMOTE_ID) != null);
			job.cancel(); peer.cancelResponse.countDown();
			awaitOutcome(job, "unknown");
			assertNull(convex.core.lang.RT.getIn(job.getData(), Fields.CANCELLATION, RemoteJobs.REMOTE_STATUS));
		}
	}

	@Test void cancellingExistingJobObserverDoesNotCancelRemoteWork() throws Exception {
		try (CancellationPeer peer = new CancellationPeer("grid")) {
			Job job = peer.engine.jobs().invokeOperation(Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, "grid:jobResult")),
				Maps.of(Fields.VENUE, peer.url(), Fields.ID, ID), peer.engine.venueContext());
			await(() -> RemoteJobs.descriptor(job) != null && RemoteJobs.descriptor(job).get(RemoteJobs.REMOTE_ID) != null);
			// Recovery must preserve the same ownership boundary.
			peer.engine.getAdapter("grid").recoverJob(job);
			job.cancel();
			assertFalse(peer.cancelReceived.await(150, TimeUnit.MILLISECONDS));
			assertNull(job.getData().get(Fields.CANCELLATION));
		}
	}

	@Test void lateCancellationEvidenceDoesNotRecreateDeletedJob() throws Exception {
		try (CancellationPeer peer = new CancellationPeer("grid")) {
			Job job = peer.submit();
			await(() -> RemoteJobs.descriptor(job) != null && RemoteJobs.descriptor(job).get(RemoteJobs.REMOTE_ID) != null);
			job.cancel();
			assertTrue(peer.cancelReceived.await(5, TimeUnit.SECONDS));
			assertTrue(peer.engine.jobs().deleteJob(job.getID(), peer.engine.venueContext()));
			CountDownLatch persisted = new CountDownLatch(1);
			job.subscribe(j -> {
				if (Strings.create("acknowledged").equals(convex.core.lang.RT.getIn(j.getData(), Fields.CANCELLATION, RemoteJobs.OUTCOME))) persisted.countDown();
			});
			peer.cancelResponse.countDown();
			assertTrue(persisted.await(5, TimeUnit.SECONDS));
			// The in-memory evidence can change, but the durable row stays deleted.
			assertNull(peer.engine.jobs().getJobData(job.getID(), peer.engine.venueContext()));
		}
	}

	@Test void recoveredGridRunRetainsCancellationOwnership() throws Exception {
		try (CancellationPeer peer = new CancellationPeer("grid")) {
			var cursor = Cursors.createLattice(Covia.ROOT);
			AKeyPair key = AKeyPair.generate();
			AMap<AString, ACell> config = Maps.of(Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0));
			Engine first = new Engine(config, cursor, key).start();
			Engine.addDemoAssets(first);
			Job job;
			try {
				job = first.jobs().invokeOperation("v/ops/grid/run", Maps.of(Fields.VENUE, peer.url(), Fields.OPERATION, "v/ops/work"), first.venueContext());
				await(() -> RemoteJobs.descriptor(job) != null && RemoteJobs.descriptor(job).get(RemoteJobs.REMOTE_ID) != null);
			} finally { first.close(); }
			assertEquals(0, peer.cancels.get());
			Engine second = new Engine(config, cursor, key).start();
			try {
				Engine.addDemoAssets(second);
				second.jobs().recoverJobs();
				Job recovered = second.jobs().getJob(job.getID(), second.venueContext());
				recovered.cancel();
				assertTrue(peer.cancelReceived.await(5, TimeUnit.SECONDS));
				peer.cancelResponse.countDown();
				awaitOutcome(recovered, "acknowledged");
				assertEquals(1, peer.cancels.get());
			} finally { second.close(); }
		}
	}

	private static void awaitOutcome(Job job, String outcome) throws Exception {
		await(() -> Strings.create(outcome).equals(convex.core.lang.RT.getIn(job.getData(), Fields.CANCELLATION, RemoteJobs.OUTCOME)));
	}

	private static final String ID = "00112233445566778899aabbccddeeff";
	private static final AMap<AString, ACell> META = Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, "grid:run"));

	private static final class Peer implements AutoCloseable {
		final HttpServer server;
		final AtomicInteger submits = new AtomicInteger();
		final AtomicInteger polls = new AtomicInteger();
		final AtomicBoolean complete = new AtomicBoolean();
		volatile boolean loseAck;
		volatile boolean brokenPoll;
		volatile AMap<AString, ACell> recoveredSnapshot;
		Peer() throws Exception {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/api/v1/", exchange -> {
				exchange.getRequestBody().readAllBytes();
				String path = exchange.getRequestURI().getPath();
				boolean submit = path.endsWith("/invoke");
				int code;
				String body;
				if (submit) {
					submits.incrementAndGet(); code = 201;
					body = loseAck ? "invalid acknowledgement" : snapshot();
				} else if (path.endsWith("/jobs/" + ID)) {
					polls.incrementAndGet(); code = brokenPoll ? 503 : 200; body = snapshot();
				} else { code = 404; body = "unexpected request"; }
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(code, bytes.length);
				try (var out = exchange.getResponseBody()) { out.write(bytes); }
			});
			server.start();
		}
		String snapshot() {
			AMap<AString, ACell> recovered = recoveredSnapshot;
			if (recovered != null) return convex.core.util.JSON.toString(recovered);
			return "{\"id\":\"" + ID + "\",\"status\":\"" + (complete.get() ? "COMPLETE" : "STARTED") + "\",\"output\":\"remote-output\"}";
		}
		String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
		AMap<AString, ACell> input() { return Maps.of(Fields.VENUE, url(), Fields.OPERATION, "v/ops/remote/work", Fields.INPUT, "simple input"); }
		@Override public void close() { server.stop(0); }
	}

	@Test void remoteRunSubmitsWithoutKeyAndUsesJobStatusNotRunEndpoint() throws Exception {
		try (Peer peer = new Peer()) {
			Engine engine = Engine.createTemp(null);
			engine.registerAdapter(new GridAdapter());
			try {
				peer.brokenPoll = true;
				Job job = engine.jobs().invokeOperation(META, peer.input(), engine.venueContext());
				await(() -> peer.polls.get() > 0);
				assertEquals(Status.STARTED, job.getStatus());
				peer.brokenPoll = false;
				peer.complete.set(true);
				assertEquals(Strings.create("remote-output"), job.future().get(5, TimeUnit.SECONDS));
				assertEquals(1, peer.submits.get());
				assertEquals(Strings.create(ID), RemoteJobs.descriptor(job).get(RemoteJobs.REMOTE_ID));
			} finally { engine.close(); }
		}
	}

	@Test void lostAcknowledgementIsNonTerminalAndNeverResubmitted() throws Exception {
		try (Peer peer = new Peer()) {
			peer.loseAck = true;
			Engine engine = Engine.createTemp(null);
			engine.registerAdapter(new GridAdapter());
			try {
				Job job = engine.jobs().invokeOperation(META, peer.input(), engine.venueContext());
				await(() -> Strings.create("acceptance-unknown").equals(RemoteJobs.descriptor(job).get(RemoteJobs.OBSERVATION)));
				assertEquals(Status.STARTED, job.getStatus());
				assertFalse(job.future().isDone());
				assertEquals(1, peer.submits.get());
				assertEquals(0, peer.polls.get());
				engine.getAdapter("grid").recoverJob(job);
				assertEquals(1, peer.submits.get());
			} finally { engine.close(); }
		}
	}

	@Test void observerRestartReattachesToTheSameRemoteJob() throws Exception {
		try (Peer peer = new Peer()) {
			RootLatticeCursor<Index<Keyword, ACell>> cursor = Cursors.createLattice(Covia.ROOT);
			AKeyPair key = AKeyPair.generate();
			AMap<AString, ACell> config = Maps.of(Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0));
			Engine first = new Engine(config, cursor, key).start();
			Engine.addDemoAssets(first);
			Job job;
			try {
				job = first.jobs().invokeOperation("v/ops/grid/run", peer.input(), first.venueContext());
				await(() -> RemoteJobs.descriptor(job) != null && RemoteJobs.descriptor(job).get(RemoteJobs.REMOTE_ID) != null);
			} finally { first.close(); }
			assertFalse(job.isFinished(), "shutdown must not invent a remote cancellation");
			peer.complete.set(true);
			Engine second = new Engine(config, cursor, key).start();
			second.jobs().recoverJobs();
			try {
				Job recovered = second.jobs().getJob(job.getID(), second.venueContext());
				assertNotNull(recovered);
				assertEquals(Status.STARTED, recovered.getStatus());
				assertEquals(Strings.create("adapter-unavailable"), RemoteJobs.descriptor(recovered).get(RemoteJobs.OBSERVATION));
				Engine.addDemoAssets(second);
				second.getAdapter("grid").recoverJob(recovered);
				assertEquals(Strings.create("remote-output"), recovered.future().get(5, TimeUnit.SECONDS));
				assertEquals(1, peer.submits.get());
			} finally { second.close(); }
		}
	}

	private static void await(java.util.function.BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
		assertTrue(condition.getAsBoolean());
	}

	@Test void remoteAdapterUnableToRecoverExecutionFailsTheMirrorWithoutResubmission() throws Exception {
		try (Peer peer = new Peer()) {
			Engine engine = Engine.createTemp(null);
			engine.registerAdapter(new GridAdapter());
			try {
				Job mirror = engine.jobs().invokeOperation(META, peer.input(), engine.venueContext());
				await(() -> peer.polls.get() > 0);
				// Model the executing venue restarting: its ordinary adapter cannot
				// restore the computation, although the Job record survived.
				Job remote = Job.create(Maps.of(Fields.ID, ID, Fields.STATUS, Status.STARTED));
				AAdapter.defaultRecover(remote);
				peer.recoveredSnapshot = remote.getData();
				assertThrows(java.util.concurrent.ExecutionException.class,
					() -> mirror.future().get(5, TimeUnit.SECONDS));
				assertEquals(Status.FAILED, mirror.getStatus());
				assertEquals(AAdapter.RESTARTED_DURING_EXECUTION, mirror.getErrorMessage());
				assertEquals(Status.FAILED, RemoteJobs.descriptor(mirror).get(RemoteJobs.REMOTE_STATUS));
				assertEquals(1, peer.submits.get());
			} finally { engine.close(); }
		}
	}

	@Test void a2aInitialInputRequired() throws Exception { interruptedTask("INPUT_REQUIRED", true); }
	@Test void a2aInitialAuthRequired() throws Exception { interruptedTask("AUTH_REQUIRED", true); }
	@Test void a2aInputRequiredDuringPolling() throws Exception { interruptedTask("INPUT_REQUIRED", false); }
	@Test void a2aAuthRequiredDuringPolling() throws Exception { interruptedTask("AUTH_REQUIRED", false); }

	private void interruptedTask(String interrupted, boolean initiallyInterrupted) throws Exception {
		HttpServer peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicInteger submits = new AtomicInteger();
		AtomicInteger polls = new AtomicInteger();
		AtomicBoolean complete = new AtomicBoolean();
		AtomicBoolean returnImmediately = new AtomicBoolean();
		peer.createContext("/a2a", exchange -> {
			String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			ACell body = convex.core.util.JSON.parse(request);
			boolean send = org.a2aproject.sdk.spec.A2AMethods.SEND_MESSAGE_METHOD.equals(
				convex.core.lang.RT.getIn(body, "method").toString());
			int status = 200;
			if (send) {
				submits.incrementAndGet();
				returnImmediately.set(convex.core.data.prim.CVMBool.TRUE.equals(
					convex.core.lang.RT.getIn(body, "params", "configuration", "returnImmediately")));
			} else if (polls.incrementAndGet() == 1) status = 503;
			String state = complete.get() ? "TASK_STATE_COMPLETED"
				: send && !initiallyInterrupted ? "TASK_STATE_WORKING" : "TASK_STATE_" + interrupted;
			byte[] response = ("{\"jsonrpc\":\"2.0\",\"id\":\"reply\",\"result\":{\"task\":{"
				+ "\"id\":\"a2a-remote\",\"contextId\":\"test-context\",\"status\":{\"state\":\"" + state + "\"}}}}")
				.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, response.length);
			try (var out = exchange.getResponseBody()) { out.write(response); }
		});
		peer.start();
		Engine engine = Engine.createTemp(null);
		engine.registerAdapter(new A2AAdapter());
		HTTPAdapter http = new HTTPAdapter();
		engine.registerAdapter(http);
		http.addAllowedHost("127.0.0.1");
		try {
			AMap<AString, ACell> meta = Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, "a2a:rawSend"));
			ACell message = Maps.of("role", "user", "parts", convex.core.data.Vectors.of(Maps.of("type", "text", "text", "hello")));
			Job job = engine.jobs().invokeOperation(meta, Maps.of(Fields.URL,
				"http://127.0.0.1:" + peer.getAddress().getPort(), Fields.MESSAGE, message), engine.venueContext());
			AString expected = Strings.create(interrupted);
			try { await(() -> polls.get() >= 2 && expected.equals(job.getStatus())); }
			catch (AssertionError e) { throw new AssertionError("submits=" + submits + ", polls=" + polls + ", job=" + job.getData(), e); }
			assertEquals(expected, job.getStatus());
			assertFalse(job.future().isDone());
			assertTrue(returnImmediately.get());
			complete.set(true);
			job.future().get(5, TimeUnit.SECONDS);
			assertEquals(Status.COMPLETE, RemoteJobs.descriptor(job).get(RemoteJobs.REMOTE_STATUS));
			assertEquals(1, submits.get());
		} finally { engine.close(); peer.stop(0); }
	}
}
