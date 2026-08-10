package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.client.VenueHTTP;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.TestServer;
import covia.venue.TwoVenueTestServer;
import covia.venue.User;
import covia.venue.api.A2ACodec;

/**
 * Tests the outbound A2A adapter by pointing it at our own server's /a2a
 * endpoint — a full self-loop through the A2A protocol. If either side's
 * wire format drifts from the spec, these tests break.
 */
class A2AAdapterTest {

	// ==================== SSRF parity ====================

	@Test
	void outboundUrlsPassSsrfValidation() throws Exception {
		// Parity with the http/mcp adapters (#234): outbound A2A targets pass
		// the same SSRF checks and operator allow/block lists. A site-local
		// literal is refused without touching the network.
		Job card = TestServer.COVIA.startJob(Strings.create("v/ops/a2a/raw/agent-card"), Maps.of(
			Fields.URL, Strings.create("http://10.0.0.1/agent")));
		assertThrows(Exception.class, () -> card.awaitResult(10000));
		assertEquals(Status.FAILED, card.getStatus());
		assertTrue(card.getErrorMessage().contains("private/internal"), card.getErrorMessage());
	}

	// ==================== normaliseRpcUrl ====================

	@Test
	void normaliseRpcUrl_handlesFrontDoorAndPerAgentEndpoints() {
		// Front door: bare base URL gets /a2a appended (trailing slash stripped).
		assertEquals("http://venue:8080/a2a", A2AAdapter.normaliseRpcUrl("http://venue:8080"));
		assertEquals("http://venue:8080/a2a", A2AAdapter.normaliseRpcUrl("http://venue:8080/"));
		assertEquals("http://venue:8080/a2a", A2AAdapter.normaliseRpcUrl("http://venue:8080/a2a"));
		// Per-agent endpoint (COG-14): used verbatim — appending /a2a would
		// corrupt the agent address and 404 at the remote.
		String agent = "http://venue:8080/a2a/did:key:z6MkTest/g/concierge";
		assertEquals(agent, A2AAdapter.normaliseRpcUrl(agent));
		assertEquals(agent, A2AAdapter.normaliseRpcUrl(agent + "/"));
		// A reverse-proxied venue under a subpath keeps its endpoint too.
		assertEquals("https://host/covia/a2a", A2AAdapter.normaliseRpcUrl("https://host/covia/a2a"));
	}

	// ==================== imported agent Assets ====================

	@Test
	void coviaAgentAsset_supportsLocalShorthand() throws Exception {
		String suffix = Long.toUnsignedString(System.nanoTime(), 36);
		String agentId = "Local" + suffix;
		String alias = "local-" + suffix;

		Job created = TwoVenueTestServer.COVIA_A.invokeSync("v/ops/agent/create", Maps.of(
			Fields.AGENT_ID, Strings.create(agentId),
			Fields.CONFIG, Maps.of(
				Fields.NAME, Strings.create("Local imported agent"),
				Fields.OPERATION, Strings.create("v/test/ops/echo"),
				Fields.A2A, Maps.of(
					Strings.create("public"), CVMBool.TRUE,
					Strings.create("caps"), Strings.create("unrestricted")))));
		assertEquals(Status.COMPLETE, created.getStatus(), created.getErrorMessage());

		Job imported = TwoVenueTestServer.COVIA_A.invokeSync("v/ops/a2a/import-agent", Maps.of(
			Fields.NAME, Strings.create(alias),
			Strings.create("coviaAgent"), Strings.create("g/" + agentId),
			Fields.VENUE, Strings.create(TwoVenueTestServer.BASE_URL_A)));
		assertEquals(Status.COMPLETE, imported.getStatus(), imported.getErrorMessage());
		assertNotNull(RT.getIn(imported.getOutput(), Strings.create("a2aAgentAsset")));

		String path = "w/a2a/agents/" + alias;
		Job cardJob = TwoVenueTestServer.COVIA_A.invokeSync("v/ops/a2a/agent-card", Maps.of(
			Strings.create("agent"), Strings.create(path)));
		assertEquals(Status.COMPLETE, cardJob.getStatus(), cardJob.getErrorMessage());
		assertEquals(Strings.create("Local imported agent"),
			RT.getIn(cardJob.getOutput(), Fields.NAME));
	}

	@Test
	void coviaAgentAsset_supportsRemoteAgentAndAllA2AOperations() throws Exception {
		String suffix = Long.toUnsignedString(System.nanoTime(), 36);
		String agentId = "Remote" + suffix;
		String alias = "remote-" + suffix;

		Job created = TwoVenueTestServer.COVIA_B.invokeSync("v/ops/agent/create", Maps.of(
			Fields.AGENT_ID, Strings.create(agentId),
			Fields.CONFIG, Maps.of(
				Fields.NAME, Strings.create("Remote imported agent"),
				Fields.OPERATION, Strings.create("v/test/ops/echo"),
				Fields.A2A, Maps.of(
					Strings.create("public"), CVMBool.TRUE,
					Strings.create("caps"), Strings.create("unrestricted")))));
		assertEquals(Status.COMPLETE, created.getStatus(), created.getErrorMessage());

		String address = TwoVenueTestServer.DID_B + ":public/g/" + agentId;
		Job imported = TwoVenueTestServer.COVIA_A.invokeSync("v/ops/a2a/import-agent", Maps.of(
			Fields.NAME, Strings.create(alias),
			Strings.create("coviaAgent"), Strings.create(address),
			Fields.VENUE, Strings.create(TwoVenueTestServer.BASE_URL_B)));
		assertEquals(Status.COMPLETE, imported.getStatus(), imported.getErrorMessage());

		String agent = "w/a2a/agents/" + alias;
		Job send = TwoVenueTestServer.COVIA_A.startJob(Strings.create("v/ops/a2a/send"), Maps.of(
			Strings.create("agent"), Strings.create(agent),
			Fields.MESSAGE, coviaMessageRecord("hello remote Covia agent")));
		AString remoteTaskId = awaitRemoteTaskId(TwoVenueTestServer.ENGINE_A, send.getID());
		assertNotNull(remoteTaskId);
		AMap<AString, ACell> mirror = TwoVenueTestServer.ENGINE_A.jobs().getJobData(send.getID());
		assertNotNull(mirror.get(Strings.create("a2aAgentAsset")),
			"the mirror Job records the exact immutable agent profile used");

		Job get = TwoVenueTestServer.COVIA_A.invokeSync("v/ops/a2a/get-task", Maps.of(
			Strings.create("agent"), Strings.create(agent),
			Fields.ID, remoteTaskId));
		assertEquals(Status.COMPLETE, get.getStatus(), get.getErrorMessage());
		assertEquals(remoteTaskId, RT.getIn(get.getOutput(), Fields.ID));

		Job cancel = TwoVenueTestServer.COVIA_A.invokeSync("v/ops/a2a/cancel", Maps.of(
			Strings.create("agent"), Strings.create(agent),
			Fields.ID, remoteTaskId));
		assertTrue(cancel.getStatus() == Status.COMPLETE || cancel.getStatus() == Status.FAILED,
			"completed remote task may be non-cancelable");
		TwoVenueTestServer.COVIA_A.cancelJob(send.getID());
	}

	// ==================== standard HTTP Bearer auth ====================

	@Test
	void bearerToken_isSentAsStandardAuthorizationHeader() {
		HttpRequest request = A2AAdapter.postEnvelope(
				"https://agent.example.com/a2a", Map.of("jsonrpc", "2.0"), "token-value");
		assertEquals("Bearer token-value",
				request.headers().firstValue("Authorization").orElseThrow());
	}

	@Test
	void bearerToken_reachesAgentCardEndpointAndIsRedactedFromJob() throws Exception {
		AtomicReference<String> authorization = new AtomicReference<>();
		HttpServer remote = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		remote.createContext("/.well-known/agent-card.json", exchange -> {
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] body = "{\"name\":\"secured external agent\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		remote.start();
		try {
			Job job = TestServer.COVIA.invokeSync("v/ops/a2a/raw/agent-card", Maps.of(
					Fields.URL, Strings.create("http://localhost:" + remote.getAddress().getPort()),
					Fields.BEARER_TOKEN, Strings.create("one-off-token")));

			assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());
			assertEquals("Bearer one-off-token", authorization.get());
			assertEquals(Fields.HIDDEN,
					RT.getIn(job.getData(), Fields.INPUT, Fields.BEARER_TOKEN));
		} finally {
			remote.stop(0);
		}
	}

	@Test
	void bearerSecret_resolvesFromCallingUsersSecretStore() {
		AString caller = Strings.create("did:test:a2a:bearer:" + System.nanoTime());
		User user = TestServer.ENGINE.getVenueState().users().ensure(caller);
		user.secrets().store("PARTNER_TOKEN", "stored-token",
				SecretStore.deriveKey(TestServer.ENGINE.getKeyPair()));

		A2AAdapter adapter = (A2AAdapter) TestServer.ENGINE.getAdapter("a2a");
		String resolved = adapter.resolveBearer(
				Maps.of(Fields.BEARER_SECRET, Strings.create("s/PARTNER_TOKEN")),
				RequestContext.of(caller));

		assertEquals("stored-token", resolved);
	}

	@Test
	void bearerInputs_areMutuallyExclusive() {
		A2AAdapter adapter = (A2AAdapter) TestServer.ENGINE.getAdapter("a2a");
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> adapter.resolveBearer(Maps.of(
						Fields.BEARER_SECRET, Strings.create("s/PARTNER_TOKEN"),
						Fields.BEARER_TOKEN, Strings.create("literal-token")),
						RequestContext.of(Strings.create("did:test:a2a:conflict"))));
		assertTrue(error.getMessage().contains("only one"), error.getMessage());
	}

	@Test
	void importedAgent_resolvesCardApiKeyFromSecretStore() throws Exception {
		AtomicReference<String> apiKey = new AtomicReference<>();
		HttpServer remote = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		int port = remote.getAddress().getPort();
		String base = "http://localhost:" + port;
		remote.createContext("/.well-known/agent-card.json", exchange -> {
			String body = "{\"name\":\"API-key agent\","
				+ "\"supportedInterfaces\":[{\"url\":\"" + base + "/rpc\"}],"
				+ "\"securitySchemes\":{\"partner\":{\"apiKeySecurityScheme\":{"
				+ "\"location\":\"header\",\"name\":\"X-Partner-Key\"}}}}";
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		remote.createContext("/rpc", exchange -> {
			apiKey.set(exchange.getRequestHeaders().getFirst("X-Partner-Key"));
			byte[] bytes = ("{\"jsonrpc\":\"2.0\",\"id\":\"reply\",\"result\":{"
				+ "\"id\":\"remote-task\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}")
				.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		remote.start();
		try {
			AString caller = Strings.create(TestServer.ENGINE.getDIDString() + ":public");
			User user = TestServer.ENGINE.getVenueState().users().ensure(caller);
			user.secrets().store("A2A_PARTNER_" + port, "key-value",
				SecretStore.deriveKey(TestServer.ENGINE.getKeyPair()));
			String alias = "api-key-" + port;
			String secretRef = "s/A2A_PARTNER_" + port;

			Job imported = TestServer.COVIA.invokeSync("v/ops/a2a/import-agent", Maps.of(
				Fields.NAME, Strings.create(alias),
				Fields.URL, Strings.create(base),
				Strings.create("auth"), Maps.of(
					Strings.create("scheme"), Strings.create("partner"),
					Strings.create("secret"), Strings.create(secretRef))));
			assertEquals(Status.COMPLETE, imported.getStatus(), imported.getErrorMessage());
			assertTrue(!imported.getOutput().toString().contains("key-value"));

			Job get = TestServer.COVIA.invokeSync("v/ops/a2a/get-task", Maps.of(
				Strings.create("agent"), Strings.create("w/a2a/agents/" + alias),
				Fields.ID, Strings.create("remote-task")));
			assertEquals(Status.COMPLETE, get.getStatus(), get.getErrorMessage());
			assertEquals("key-value", apiKey.get());
		} finally {
			remote.stop(0);
		}
	}

	// ==================== send via the internal path ====================

	@Test
	void send_invokableInternally_delegatesToJob() throws Exception {
		// Regression (#85 delegation pattern, caught live by an agent calling
		// a2a_send as a tool): send is implemented only in the Job-aware
		// dispatch, so the transient-Job internal path must delegate to a real,
		// owner-attributed Job — not throw "Unknown a2a sub-operation".
		RequestContext ctx = RequestContext.of(Strings.create("did:test:a2a:internal"));
		var engine = covia.venue.TestServer.ENGINE;
		long before = engine.jobs().getJobs(ctx).count();
		try {
			engine.jobs().invokeInternal("v/ops/a2a/raw/send",
				Maps.of(Fields.URL, Strings.create("http://127.0.0.1:9/a2a"),
					Fields.MESSAGE, coviaMessageRecord("internal hello")),
				ctx).get(30, java.util.concurrent.TimeUnit.SECONDS);
		} catch (java.util.concurrent.ExecutionException e) {
			// Transport failure against the unreachable remote is expected —
			// the regression is the internal path rejecting send outright.
			String msg = String.valueOf(e.getCause().getMessage());
			assertTrue(!msg.contains("Unknown a2a sub-operation"), msg);
		}
		assertTrue(engine.jobs().getJobs(ctx).count() > before,
			"internal a2a:send must persist a mirror Job");
	}

	// ==================== getAgentCard ====================

	@Test
	void getAgentCard_returnsValidCard() throws Exception {
		VenueHTTP covia = TestServer.COVIA;
		Job job = covia.invokeSync("v/ops/a2a/raw/agent-card", Maps.of(
				Fields.URL, Strings.create(TestServer.BASE_URL)));

		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());
		ACell output = job.getOutput();
		assertTrue(output instanceof AMap);
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> card = (AMap<AString, ACell>) output;
		assertNotNull(card.get(Strings.create("name")));
		assertNotNull(card.get(Strings.create("supportedInterfaces")));
		assertNotNull(card.get(Strings.create("version")));
	}

	// ==================== send — mirror to INPUT_REQUIRED ====================

	@Test
	void send_mirrorsInputRequiredFromRemoteChatOp() throws Exception {
		VenueHTTP covia = TestServer.COVIA;
		// Async invoke + server-side poll; INPUT_REQUIRED is non-terminal so
		// invokeSync would block forever.
		Job seed = covia.startJob(Strings.create("v/ops/a2a/raw/send"), Maps.of(
				Fields.URL, Strings.create(TestServer.BASE_URL),
				Fields.MESSAGE, coviaMessageRecord("hello from test")));

		AMap<AString, ACell> data = awaitStable(seed.getID());
		assertEquals(Status.INPUT_REQUIRED, RT.ensureString(data.get(Fields.STATUS)),
				"Remote test:chat op goes INPUT_REQUIRED; adapter must mirror it onto the local Job");

		AString remoteTaskId = RT.ensureString(data.get(Fields.REMOTE_TASK_ID));
		assertNotNull(remoteTaskId, "Adapter must persist remoteTaskId on the local Job");

		ACell output = data.get(Fields.OUTPUT);
		assertTrue(output instanceof AMap);
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> task = (AMap<AString, ACell>) output;
		assertEquals(remoteTaskId, RT.ensureString(task.get(Strings.create("id"))));
	}

	// ==================== getTask — fetch existing remote task ====================

	@Test
	void getTask_roundTripsRemoteTaskViaLocalAdapter() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job seed = covia.startJob(Strings.create("v/ops/a2a/raw/send"), Maps.of(
				Fields.URL, Strings.create(TestServer.BASE_URL),
				Fields.MESSAGE, coviaMessageRecord("seed task")));
		AMap<AString, ACell> data = awaitStable(seed.getID());
		String remoteTaskId = RT.ensureString(data.get(Fields.REMOTE_TASK_ID)).toString();

		// Act: fetch it back via a2a:get-task (this one does reach COMPLETE)
		Job getJob = covia.invokeSync("v/ops/a2a/raw/get-task", Maps.of(
				Fields.URL, Strings.create(TestServer.BASE_URL),
				Fields.ID, Strings.create(remoteTaskId)));

		assertEquals(Status.COMPLETE, getJob.getStatus(), getJob.getErrorMessage());
		ACell output = getJob.getOutput();
		assertTrue(output instanceof AMap);
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> task = (AMap<AString, ACell>) output;
		assertEquals(remoteTaskId, RT.ensureString(task.get(Strings.create("id"))).toString());
	}

	@Test
	void getTask_unknownIdSurfacesRemoteError() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("v/ops/a2a/raw/get-task", Maps.of(
				Fields.URL, Strings.create(TestServer.BASE_URL),
				Fields.ID, Strings.create("000000000000000000000000deadbeef")));

		// Remote returns JSON-RPC error (TaskNotFound); adapter surfaces it as
		// a failed Job with null output.
		assertEquals(Status.FAILED, job.getStatus());
		assertNotNull(job.getErrorMessage());
		assertTrue(job.getErrorMessage().toLowerCase().contains("task"),
				"Error should mention Task: " + job.getErrorMessage());
	}

	// ==================== cancel — terminate remote running task ====================

	@Test
	void cancel_transitionsRemoteToCanceled() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job seed = covia.startJob(Strings.create("v/ops/a2a/raw/send"), Maps.of(
				Fields.URL, Strings.create(TestServer.BASE_URL),
				Fields.MESSAGE, coviaMessageRecord("cancel me")));
		AMap<AString, ACell> data = awaitStable(seed.getID());
		String remoteTaskId = RT.ensureString(data.get(Fields.REMOTE_TASK_ID)).toString();

		// Act: cancel
		Job cancelJob = covia.invokeSync("v/ops/a2a/raw/cancel", Maps.of(
				Fields.URL, Strings.create(TestServer.BASE_URL),
				Fields.ID, Strings.create(remoteTaskId)));

		// INPUT_REQUIRED is an interrupted (non-terminal) state per A2A spec,
		// so cancellation is valid. Either we get the canceled Task or — if the
		// server treats it as terminal — we get a TaskNotCancelable error.
		if (cancelJob.getStatus() == Status.FAILED) {
			assertTrue(cancelJob.getErrorMessage().toLowerCase().contains("cancel"),
					"Expected TaskNotCancelable, got: " + cancelJob.getErrorMessage());
			return;
		}
		assertEquals(Status.COMPLETE, cancelJob.getStatus(), cancelJob.getErrorMessage());
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> task = (AMap<AString, ACell>) cancelJob.getOutput();
		AMap<?, ?> status = (AMap<?, ?>) task.get(Strings.create("status"));
		assertEquals("TASK_STATE_CANCELED",
				RT.ensureString(status.get(Strings.create("state"))).toString());
	}

	// ==================== url validation ====================

	@Test
	void getAgentCard_missingUrlFailsJob() throws Exception {
		VenueHTTP covia = TestServer.COVIA;
		Job job = covia.invokeSync("v/ops/a2a/raw/agent-card", Maps.of());
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains("'url' is required"), job.getErrorMessage());
	}

	@Test
	void getTask_missingIdNamesRemoteTaskId() throws Exception {
		Job job = TestServer.COVIA.invokeSync("v/ops/a2a/raw/get-task", Maps.of(
			Fields.URL, Strings.create(TestServer.BASE_URL)));
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains("remote A2A task ID"), job.getErrorMessage());
	}

	// ==================== helpers ====================

	/**
	 * Build a Covia-shaped message record matching what A2ACodec.toMessageRecord
	 * produces — {role, parts: [{type:"text", text:...}], messageId?}.
	 */
	private static AMap<AString, ACell> coviaMessageRecord(String text) {
		return Maps.of(
				A2ACodec.ROLE, Strings.create("user"),
				A2ACodec.PARTS, Vectors.of(Maps.of(
						Fields.TYPE, Strings.intern("text"),
						Fields.TEXT, Strings.create(text))),
				A2ACodec.MESSAGE_ID, Strings.create("msg-" + System.nanoTime()));
	}

	/**
	 * Poll the local engine directly (bypassing HTTP) until the job leaves the
	 * PENDING/STARTED transient states. Used by tests that expect a stable but
	 * non-terminal outcome (e.g. INPUT_REQUIRED). Up to 5s.
	 */
	private static AMap<AString, ACell> awaitStable(Blob jobId) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		AMap<AString, ACell> data = null;
		while (System.currentTimeMillis() < deadline) {
			// Direct active-cache lookup bypasses ownership — fine for test
			// observation; we're just waiting for status to settle.
			data = TestServer.ENGINE.jobs().getJobData(jobId);
			if (data != null) {
				AString status = RT.ensureString(data.get(Fields.STATUS));
				if (status != null && !Status.PENDING.equals(status) && !Status.STARTED.equals(status)) {
					return data;
				}
			}
			Thread.sleep(50);
		}
		throw new AssertionError("Job never left PENDING/STARTED: "
				+ (data == null ? "null" : data.get(Fields.STATUS)));
	}

	private static AString awaitRemoteTaskId(covia.venue.Engine engine, Blob jobId)
			throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline) {
			AMap<AString, ACell> data = engine.jobs().getJobData(jobId);
			AString taskId = data != null ? RT.ensureString(data.get(Fields.REMOTE_TASK_ID)) : null;
			if (taskId != null) return taskId;
			Thread.sleep(50);
		}
		throw new AssertionError("A2A mirror never recorded its remote task id");
	}
}
