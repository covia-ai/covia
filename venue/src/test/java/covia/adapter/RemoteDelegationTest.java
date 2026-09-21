package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
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

	@Test void a2aKeepsObservingInterruptedTasksAcrossTransportFailure() throws Exception {
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
			String state = complete.get() ? "TASK_STATE_COMPLETED" : "TASK_STATE_INPUT_REQUIRED";
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
			try { await(() -> polls.get() >= 2); }
			catch (AssertionError e) { throw new AssertionError("submits=" + submits + ", polls=" + polls + ", job=" + job.getData(), e); }
			assertEquals(Status.INPUT_REQUIRED, job.getStatus());
			assertFalse(job.future().isDone());
			assertTrue(returnImmediately.get());
			complete.set(true);
			job.future().get(5, TimeUnit.SECONDS);
			assertEquals(Status.COMPLETE, RemoteJobs.descriptor(job).get(RemoteJobs.REMOTE_STATUS));
			assertEquals(1, submits.get());
		} finally { engine.close(); peer.stop(0); }
	}
}
