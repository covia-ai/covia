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
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import covia.api.Fields;
import covia.exception.ShuttingDownException;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RemoteJobs;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.TestSignal;

class RemoteDelegationTest {
	/** Gates prove local cancellation completes while remote IO remains blocked. */
	private static final class CancellationPeer implements AutoCloseable {
		final HttpServer server;
		final Engine engine;
		final String protocol;
		/** Requests that hand the peer work or a message: never a status read or a cancel. */
		final AtomicInteger sends = new AtomicInteger();
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
		CancellationPeer(String protocol) throws Exception { this(protocol, null); }
		CancellationPeer(String protocol, AMap<AString, ACell> config) throws Exception {
			this.protocol = protocol;
			engine = Engine.createTemp(config);
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.setExecutor(AAdapter.VIRTUAL_EXECUTOR);
			server.createContext("/", exchange -> {
				String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				String path = exchange.getRequestURI().getPath();
				boolean a2a = protocol.equals("a2a");
				String method = a2a ? convex.core.lang.RT.getIn(convex.core.util.JSON.parse(request), "method").toString() : "";
				boolean submit = a2a ? org.a2aproject.sdk.spec.A2AMethods.SEND_MESSAGE_METHOD.equals(method) : path.endsWith("/invoke");
				boolean cancel = a2a ? org.a2aproject.sdk.spec.A2AMethods.CANCEL_TASK_METHOD.equals(method) : path.endsWith("/cancel");
				if (a2a ? submit : "POST".equals(exchange.getRequestMethod())) sends.incrementAndGet();
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
		Job submit() { return engine.jobs().invokeOperation(operation(), input(), engine.venueContext()); }
		AMap<AString, ACell> operation() {
			String op = protocol.equals("a2a") ? "a2a:rawSend" : protocol.equals("grid") ? "grid:run" : "remoteagent:request";
			return Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, op));
		}
		AMap<AString, ACell> input() {
			ACell message = Maps.of("role", "user", "parts", convex.core.data.Vectors.of(Maps.of("type", "text", "text", "hello")));
			return Maps.of(Fields.VENUE, url(), Fields.URL, url(), Fields.OPERATION, "v/ops/work",
				Fields.AGENT_ID, "g/worker", Fields.INPUT, Maps.of("task", "work"), Fields.MESSAGE, message);
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
			awaitRemoteId(child);
			Job parent = Job.create(Maps.of(Fields.STATUS, Status.STARTED));
			parent.setCancelHook(child::cancel);
			java.util.concurrent.CompletableFuture.runAsync(parent::cancel).get(2, TimeUnit.SECONDS);
			assertEquals(Status.CANCELLED, child.getStatus());
			assertTrue(parent.future().isDone());
			assertTrue(child.future().isDone());
			assertTrue(peer.cancelReceived.await(5, TimeUnit.SECONDS));
			assertEquals(RemoteJobs.OUTCOME_REQUESTED, convex.core.lang.RT.getIn(child.getData(), Fields.CANCELLATION, RemoteJobs.OUTCOME));
			peer.cancelResponse.countDown();
			awaitOutcome(child, RemoteJobs.OUTCOME_ACKNOWLEDGED);
			// The venue's Job record is its durable row, so the row holds the outcome too.
			assertEquals(RemoteJobs.OUTCOME_ACKNOWLEDGED, convex.core.lang.RT.getIn(
				peer.engine.jobs().getJobData(child.getID(), peer.engine.venueContext()), Fields.CANCELLATION, RemoteJobs.OUTCOME));
			child.completeWith(Strings.create("late result"));
			child.cancel();
			assertEquals(Status.CANCELLED, child.getStatus());
			assertEquals(1, peer.cancels.get());
		}
	}

	@Test void gridShutdownSendsNothingNewButCancels() throws Exception { shutdownSendsNothingNewButCancels("grid"); }
	@Test void a2aShutdownSendsNothingNewButCancels() throws Exception { shutdownSendsNothingNewButCancels("a2a"); }
	@Test void remoteagentShutdownSendsNothingNewButCancels() throws Exception { shutdownSendsNothingNewButCancels("remoteagent"); }

	private void shutdownSendsNothingNewButCancels(String protocol) throws Exception {
		// A grace window no test outlasts, held open by the remote job still running
		AMap<AString, ACell> config = Maps.of(Config.SHUTDOWN, Maps.of(Config.GRACE_MS, TimeUnit.DAYS.toMillis(1)));
		try (CancellationPeer peer = new CancellationPeer(protocol, config)) {
			Job running = peer.submit();
			awaitRemoteId(running);
			int sends = peer.sends.get();
			Thread closer = Thread.ofVirtual().start(peer.engine::close);
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (!peer.engine.isClosing()) {
				assertTrue(System.nanoTime() < deadline, "close never began");
				Thread.onSpinWait();
			}
			// Work still running inside the window may not hand the peer anything new
			java.util.concurrent.ExecutionException refused = assertThrows(java.util.concurrent.ExecutionException.class,
				() -> peer.engine.jobs().invokeInternal(peer.operation(), peer.input(), peer.engine.venueContext())
					.get(5, TimeUnit.SECONDS));
			assertInstanceOf(ShuttingDownException.class, refused.getCause());
			assertEquals(RemoteJobs.SENDS_CLOSED, refused.getCause().getMessage());
			if (!protocol.equals("grid")) {
				ShuttingDownException message = assertThrows(ShuttingDownException.class, () -> peer.engine.jobs()
					.deliverMessage(running.getID(), Maps.of(Fields.CONTENT, "more"), peer.engine.venueContext()));
				assertEquals(RemoteJobs.SENDS_CLOSED, message.getMessage());
			}
			assertEquals(sends, peer.sends.get());
			// A cancel still goes out, and its answer is recorded within the window
			running.cancel();
			assertTrue(peer.cancelReceived.await(5, TimeUnit.SECONDS));
			peer.cancelResponse.countDown();
			closer.join();
			assertEquals(RemoteJobs.OUTCOME_ACKNOWLEDGED, convex.core.lang.RT.getIn(running.getData(), Fields.CANCELLATION, RemoteJobs.OUTCOME));
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
			awaitOutcome(job, RemoteJobs.OUTCOME_ACKNOWLEDGED);
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
				awaitRemoteId(job);
				job.cancel(); peer.cancelResponse.countDown();
				awaitOutcome(job, scenario == 0 ? RemoteJobs.OUTCOME_REJECTED
					: scenario == 3 ? RemoteJobs.OUTCOME_FINISHED : RemoteJobs.OUTCOME_UNKNOWN);
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
			awaitRemoteId(job);
			job.cancel(); peer.cancelResponse.countDown();
			awaitOutcome(job, RemoteJobs.OUTCOME_REJECTED);
			assertEquals(convex.core.data.prim.CVMLong.create(-32002), convex.core.lang.RT.getIn(job.getData(), Fields.CANCELLATION, Fields.ERROR_CODE));
			assertFalse(job.getData().toString().contains("private-token"));
		}
	}

	@Test void unspecifiedA2aStateIsNotEvidenceOfRemoteCompletion() throws Exception {
		try (CancellationPeer peer = new CancellationPeer("a2a")) {
			peer.a2aCancelState = "UNSPECIFIED";
			Job job = peer.submit();
			awaitRemoteId(job);
			job.cancel(); peer.cancelResponse.countDown();
			awaitOutcome(job, RemoteJobs.OUTCOME_UNKNOWN);
			assertNull(convex.core.lang.RT.getIn(job.getData(), Fields.CANCELLATION, RemoteJobs.REMOTE_STATUS));
		}
	}

	@Test void cancellingExistingJobObserverDoesNotCancelRemoteWork() throws Exception {
		try (CancellationPeer peer = new CancellationPeer("grid")) {
			Job job = peer.engine.jobs().invokeOperation(Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, "grid:jobResult")),
				Maps.of(Fields.VENUE, peer.url(), Fields.ID, ID), peer.engine.venueContext());
			awaitRemoteId(job);
			// Recovery must preserve the same ownership boundary.
			peer.engine.getAdapter("grid").recoverJob(job);
			job.cancel();
			// Closing the venue waits out its grace window for every remote request it
			// has started, so a cancellation sent for the observer would have arrived.
			peer.cancelResponse.countDown();
			peer.engine.close();
			assertEquals(1, peer.cancelReceived.getCount(), "the remote job was not cancelled");
			assertEquals(0, peer.cancels.get());
			assertNull(job.getData().get(Fields.CANCELLATION));
		}
	}

	@Test void lateCancellationEvidenceDoesNotRecreateDeletedJob() throws Exception {
		try (CancellationPeer peer = new CancellationPeer("grid")) {
			Job job = peer.submit();
			awaitRemoteId(job);
			job.cancel();
			assertTrue(peer.cancelReceived.await(5, TimeUnit.SECONDS));
			assertTrue(peer.engine.jobs().deleteJob(job.getID(), peer.engine.venueContext()));
			CountDownLatch persisted = new CountDownLatch(1);
			job.subscribe(j -> {
				if (RemoteJobs.OUTCOME_ACKNOWLEDGED.equals(convex.core.lang.RT.getIn(j.getData(), Fields.CANCELLATION, RemoteJobs.OUTCOME))) persisted.countDown();
			});
			peer.cancelResponse.countDown();
			assertTrue(persisted.await(5, TimeUnit.SECONDS));
			// The in-memory evidence can change, but the durable row stays deleted.
			assertNull(peer.engine.jobs().getJobData(job.getID(), peer.engine.venueContext()));
		}
	}

	@Test void recoveredGridRunRetainsCancellationOwnership() throws Exception {
		try (CancellationPeer peer = new CancellationPeer("grid")) {
			var cursor = Cursors.root(convex.core.data.Index.<convex.core.data.Keyword, ACell>none());
			AKeyPair key = AKeyPair.generate();
			AMap<AString, ACell> config = Maps.of(Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0));
			Engine first = new Engine(config, covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null), key), key).start();
			Engine.addDemoAssets(first);
			Job job;
			try {
				job = first.jobs().invokeOperation("v/ops/grid/run", Maps.of(Fields.VENUE, peer.url(), Fields.OPERATION, "v/ops/work"), first.venueContext());
				awaitRemoteId(job);
			} finally { first.close(); }
			assertEquals(0, peer.cancels.get());
			Engine second = new Engine(config, covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null), key), key).start();
			try {
				Engine.addDemoAssets(second);
				second.jobs().recoverJobs();
				Job recovered = second.jobs().getJob(job.getID(), second.venueContext());
				recovered.cancel();
				assertTrue(peer.cancelReceived.await(5, TimeUnit.SECONDS));
				peer.cancelResponse.countDown();
				awaitOutcome(recovered, RemoteJobs.OUTCOME_ACKNOWLEDGED);
				assertEquals(1, peer.cancels.get());
			} finally { second.close(); }
		}
	}

	/**
	 * After a restart an A2A job reads its bearer again from its caller's
	 * secrets, so a secret rotated meanwhile is sent as it now stands. A job
	 * whose token the caller passed literally ends instead, naming the remote
	 * task: only the request presented that token. So does a job whose caller
	 * can no longer use its secret: one deleted meanwhile, or another user's
	 * used under a grant, which the job does not keep.
	 */
	@Test void a2aRecoveryReadsTheBearerAgainAndEndsAJobWhoseTokenWasNotKept() throws Exception {
		// Each request's Authorization, as the peer received it
		java.util.List<String> authorizations = new java.util.concurrent.CopyOnWriteArrayList<>();
		TestSignal received = new TestSignal();
		HttpServer peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		peer.createContext("/a2a", exchange -> {
			exchange.getRequestBody().readAllBytes();
			String authorization = exchange.getRequestHeaders().getFirst("Authorization");
			authorizations.add(String.valueOf(authorization));
			// Each job's remote task is the one its token reaches
			String task = switch (String.valueOf(authorization)) {
				case "Bearer literal-token" -> "literal-task";
				case "Bearer granted-token" -> "granted-task";
				case "Bearer deleted-token" -> "deleted-task";
				default -> "secret-task";
			};
			byte[] response = ("{\"jsonrpc\":\"2.0\",\"id\":\"reply\",\"result\":{\"task\":{\"id\":\"" + task
				+ "\",\"contextId\":\"ctx\",\"status\":{\"state\":\"TASK_STATE_WORKING\"}}}}").getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length);
			try (var out = exchange.getResponseBody()) { out.write(response); }
			received.signal();
		});
		peer.start();
		String url = "http://127.0.0.1:" + peer.getAddress().getPort();
		Root<Index<Keyword, ACell>> cursor = Cursors.root(convex.core.data.Index.<convex.core.data.Keyword, ACell>none());
		AKeyPair key = AKeyPair.generate();
		// The caller dials the loopback peer, which the guard allows only when the operator lists it
		AMap<AString, ACell> config = Maps.of(Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ADAPTERS, Maps.of("http", Maps.of("allowedHosts", Vectors.of("127.0.0.1"))));
		RequestContext user = RequestContext.of(Strings.create(covia.grid.auth.UcanTokens.did(AKeyPair.generate())));
		ACell message = Maps.of("role", "user", "parts", Vectors.of(Maps.of("type", "text", "text", "hello")));
		Engine first = new Engine(config, covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null), key), key).start();
		Engine.addDemoAssets(first);
		// Another user's secret, which the caller uses under a grant presented with the request
		AKeyPair ownerKey = AKeyPair.generate();
		AString ownerDID = Strings.create(covia.grid.auth.UcanTokens.did(ownerKey));
		AString granted = Strings.create(ownerDID + "/s/SHARED");
		RequestContext grantee = user.withProofs(Vectors.of(convex.auth.ucan.UCAN.create(ownerKey,
			convex.auth.ucan.UCAN.fromDIDKey(user.getCallerDID()), System.currentTimeMillis() / 1000 + 3600,
			Vectors.of(convex.auth.ucan.Capability.create(granted, covia.api.Abilities.SECRET_USE,
				Maps.of(Strings.create("url"), Strings.create(url)))),
			Vectors.empty()).toMap()));
		Job fromSecret, fromLiteral, fromGrant, fromDeleted;
		try {
			setSecret(first, user, "first-token");
			first.getVenueState().users().ensure(ownerDID).secrets().store("SHARED", "granted-token",
				covia.venue.SecretStore.keys(key));
			first.jobs().invokeOperation("v/ops/secret/set",
				Maps.of(Fields.NAME, "DELETED", Fields.VALUE, "deleted-token"), user).awaitResult(5000);
			fromSecret = first.jobs().invokeOperation("v/ops/a2a/raw/send",
				Maps.of(Fields.URL, url, Fields.BEARER_SECRET, "s/PARTNER", Fields.MESSAGE, message), user);
			fromLiteral = first.jobs().invokeOperation("v/ops/a2a/raw/send",
				Maps.of(Fields.URL, url, Fields.BEARER_TOKEN, "literal-token", Fields.MESSAGE, message), user);
			fromGrant = first.jobs().invokeOperation("v/ops/a2a/raw/send",
				Maps.of(Fields.URL, url, Fields.BEARER_SECRET, granted, Fields.MESSAGE, message), grantee);
			fromDeleted = first.jobs().invokeOperation("v/ops/a2a/raw/send",
				Maps.of(Fields.URL, url, Fields.BEARER_SECRET, "s/DELETED", Fields.MESSAGE, message), user);
			awaitRemoteId(fromSecret);
			awaitRemoteId(fromLiteral);
			awaitRemoteId(fromGrant);
			awaitRemoteId(fromDeleted);
			assertTrue(authorizations.contains("Bearer granted-token"));
			assertTrue(authorizations.contains("Bearer first-token"));
			assertFalse(convex.core.util.JSON.toString(first.remoteJobs().connection(fromSecret)).contains("first-token"),
				"the secret's value is not kept for recovery");
			assertFalse(convex.core.util.JSON.toString(first.remoteJobs().connection(fromLiteral)).contains("literal-token"),
				"the literal token is not kept for recovery");
			setSecret(first, user, "second-token");
			first.jobs().invokeOperation("v/ops/secret/delete", Maps.of(Fields.NAME, "DELETED"), user).awaitResult(5000);
		} finally { first.close(); }
		Engine second = new Engine(config, covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null), key), key).start();
		try {
			Engine.addDemoAssets(second);
			second.jobs().recoverJobs();
			Job ended = second.jobs().getJob(fromLiteral.getID(), user);
			assertEquals(Status.FAILED, ended.getStatus());
			assertEquals(RemoteJobs.CREDENTIAL_NOT_KEPT.formatted("literal-task", url + "/a2a"), ended.getErrorMessage());
			Job ungranted = second.jobs().getJob(fromGrant.getID(), user);
			assertEquals(Status.FAILED, ungranted.getStatus());
			assertEquals(A2AAdapter.SECRET_UNREADABLE.formatted("granted-task", url + "/a2a"), ungranted.getErrorMessage());
			Job deleted = second.jobs().getJob(fromDeleted.getID(), user);
			assertEquals(Status.FAILED, deleted.getStatus());
			assertEquals(A2AAdapter.SECRET_UNREADABLE.formatted("deleted-task", url + "/a2a"), deleted.getErrorMessage());
			received.await("a poll carrying the rotated secret", () -> authorizations.contains("Bearer second-token"));
			assertFalse(second.jobs().getJob(fromSecret.getID(), user).isFinished());
		} finally { second.close(); peer.stop(0); }
	}

	/** Stores, or replaces, the caller's secret {@code PARTNER}. */
	private static void setSecret(Engine engine, RequestContext caller, String value) {
		engine.jobs().invokeOperation("v/ops/secret/set",
			Maps.of(Fields.NAME, "PARTNER", Fields.VALUE, value, "overwrite", true), caller).awaitResult(5000);
	}

	private static void awaitOutcome(Job job, AString outcome) {
		await(job, j -> outcome.equals(convex.core.lang.RT.getIn(j.getData(), Fields.CANCELLATION, RemoteJobs.OUTCOME)));
	}

	private static final String ID = "00112233445566778899aabbccddeeff";
	private static final AMap<AString, ACell> META = Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, "grid:run"));

	private static final class Peer implements AutoCloseable {
		final HttpServer server;
		final AtomicInteger submits = new AtomicInteger();
		final AtomicInteger polls = new AtomicInteger();
		final AtomicBoolean complete = new AtomicBoolean();
		/** Signalled as each request is handled. */
		final TestSignal handled = new TestSignal();
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
				handled.signal();
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
				peer.handled.await("a status poll", () -> peer.polls.get() > 0);
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
				await(job, j -> RT.getIn(j.getData(), RemoteJobs.DELEGATION, RemoteJobs.NOT_FOLLOWED) != null);
				assertEquals(Maps.of(RemoteJobs.NOT_FOLLOWED, RemoteJobs.ACCEPTANCE_UNKNOWN.formatted(engine.remoteJobs().target(job))),
					RemoteJobs.descriptor(job), "the record says why the venue stopped following, and nothing of how it follows");
				assertEquals(Strings.create("acceptance-unknown"), engine.remoteJobs().kept(job).get(RemoteJobs.OBSERVATION));
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
			Root<Index<Keyword, ACell>> cursor = Cursors.root(convex.core.data.Index.<convex.core.data.Keyword, ACell>none());
			AKeyPair key = AKeyPair.generate();
			AMap<AString, ACell> config = Maps.of(Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0));
			Engine first = new Engine(config, covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null), key), key).start();
			Engine.addDemoAssets(first);
			Job job;
			try {
				job = first.jobs().invokeOperation("v/ops/grid/run", peer.input(), first.venueContext());
				awaitRemoteId(job);
			} finally { first.close(); }
			assertFalse(job.isFinished(), "shutdown must not invent a remote cancellation");
			peer.complete.set(true);
			Engine second = new Engine(config, covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null), key), key).start();
			second.jobs().recoverJobs();
			try {
				Job recovered = second.jobs().getJob(job.getID(), second.venueContext());
				assertNotNull(recovered);
				assertEquals(Status.STARTED, recovered.getStatus());
				assertEquals(Strings.create(RemoteJobs.ADAPTER_MISSING.formatted("grid", ID, second.remoteJobs().target(recovered))),
					RT.getIn(recovered.getData(), RemoteJobs.DELEGATION, RemoteJobs.NOT_FOLLOWED));
				Engine.addDemoAssets(second);
				second.getAdapter("grid").recoverJob(recovered);
				assertEquals(Strings.create("remote-output"), recovered.future().get(5, TimeUnit.SECONDS));
				assertEquals(1, peer.submits.get());
				assertEquals(Maps.of(RemoteJobs.REMOTE_ID, ID, RemoteJobs.REMOTE_STATUS, Status.COMPLETE), RemoteJobs.descriptor(recovered),
					"followed again, the record no longer says it is not");
			} finally { second.close(); }
		}
	}

	@Test void recoveryRechecksAUserTargetAgainstTheGuard() throws Exception {
		try (Peer peer = new Peer()) {
			Root<Index<Keyword, ACell>> cursor = Cursors.root(convex.core.data.Index.<convex.core.data.Keyword, ACell>none());
			AKeyPair key = AKeyPair.generate();
			AMap<AString, ACell> unlisted = Maps.of(Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0),
				Config.USERS, Maps.of(Config.AUTO_CREATE, true));
			// The peer listens on loopback, which the guard refuses a user unless the operator allows it
			AMap<AString, ACell> listed = unlisted.assoc(Config.ADAPTERS,
				Maps.of("http", Maps.of("allowedHosts", Vectors.of("127.0.0.1"))));
			RequestContext user = RequestContext.of(Strings.create("did:key:z6MkRecoveryRechecksUser"));
			Engine first = new Engine(listed, covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null), key), key).start();
			Engine.addDemoAssets(first);
			Job job;
			try {
				job = first.jobs().invokeOperation("v/ops/grid/run", peer.input(), user);
				awaitRemoteId(job);
			} finally { first.close(); }
			// The operator has since withdrawn the peer's host from the allow list
			Engine second = new Engine(unlisted, covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null), key), key).start();
			try {
				Engine.addDemoAssets(second);
				second.jobs().recoverJobs();
				Job recovered = second.jobs().getJob(job.getID(), user);
				assertEquals(Strings.create(covia.exception.InvalidInputException.class.getSimpleName()),
					second.remoteJobs().kept(recovered).get(RemoteJobs.ERROR_TYPE), "the guard refuses the reconnection");
				assertNotNull(RT.getIn(recovered.getData(), RemoteJobs.DELEGATION, RemoteJobs.NOT_FOLLOWED),
					"the record says the venue no longer follows the remote job");
				assertFalse(recovered.isFinished());
				assertEquals(1, peer.submits.get());
			} finally { second.close(); }
		}
	}

	/** Waits until {@code job} satisfies {@code condition}, checked as the Job is updated. */
	private static void await(Job job, java.util.function.Predicate<Job> condition) {
		TestEngine.awaitJob(job, condition, 10_000, () -> "job did not reach the expected state: " + job.getData());
	}

	/** Waits until the remote job's id is bound to {@code job}. */
	private static void awaitRemoteId(Job job) {
		await(job, j -> RemoteJobs.descriptor(j) != null && RemoteJobs.descriptor(j).get(RemoteJobs.REMOTE_ID) != null);
	}

	@Test void remoteAdapterUnableToRecoverExecutionFailsTheMirrorWithoutResubmission() throws Exception {
		try (Peer peer = new Peer()) {
			Engine engine = Engine.createTemp(null);
			engine.registerAdapter(new GridAdapter());
			try {
				Job mirror = engine.jobs().invokeOperation(META, peer.input(), engine.venueContext());
				peer.handled.await("a status poll", () -> peer.polls.get() > 0);
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
		// Signalled by each request the peer handles and each update of the Job
		TestSignal progress = new TestSignal();
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
			progress.signal();
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
			job.subscribe(j -> progress.signal());
			try { progress.await(expected + " after two polls", () -> polls.get() >= 2 && expected.equals(job.getStatus())); }
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
