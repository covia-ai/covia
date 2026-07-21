package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.AContent;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.client.VenueHTTP;
import covia.grid.impl.BlobContent;
import covia.venue.server.VenueServer;

@TestInstance(Lifecycle.PER_CLASS)
public class VenueServerTest {
	
	static final int PORT=TestServer.PORT;
	static final String BASE_URL=TestServer.BASE_URL;
	
	VenueServer venueServer;
	Engine venue;
	VenueHTTP covia;
	
	@BeforeAll
	public void setupServer() throws Exception {
		venueServer=TestServer.SERVER;
		venue=TestServer.ENGINE;
		covia = TestServer.COVIA;
	}
	
	/**
	 * The Private Network Access header must be OFF by default — emitting it
	 * lets a public web origin reach a localhost venue from the browser
	 * (covia#130 / GetMine-ai/demo#133 P0-2). Operator opt-in only.
	 */
	@Test public void testPrivateNetworkHeaderGatedOff() throws Exception {
		HttpClient client = HttpClient.newBuilder().build();
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI("http://localhost:" + PORT + "/api/v1/status"))
			.GET().timeout(Duration.ofSeconds(10)).build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertTrue(resp.headers().firstValue("access-control-allow-private-network").isEmpty(),
			"PNA header must not be emitted by default");

		// Config getter: off by default, on only when explicitly enabled.
		assertFalse(new Config(Maps.empty()).isAllowPrivateNetwork());
		assertTrue(new Config(Maps.of(Config.ALLOW_PRIVATE_NETWORK, CVMBool.TRUE)).isAllowPrivateNetwork());
	}

	@Test public void testCorsOriginListAndPreflight() throws Exception {
		VenueServer server = VenueServer.launch(Maps.of(
			Config.PORT, CVMLong.create(0),
			Config.CORS_ORIGINS, Vectors.of(
				Strings.create("https://app.example"),
				Strings.create("https://admin.example"))));
		try {
			for (String origin : new String[] {"https://app.example", "https://admin.example"}) {
				HttpResponse<String> response = corsGet(server, origin);
				assertEquals(200, response.statusCode(), response.body());
				assertEquals(origin, response.headers()
					.firstValue("access-control-allow-origin").orElse(null));
			}

			HttpResponse<String> denied = corsGet(server, "https://evil.example");
			assertEquals(400, denied.statusCode());
			assertTrue(denied.headers().firstValue("access-control-allow-origin").isEmpty());

			HttpResponse<String> preflight = corsPreflight(server, "https://app.example");
			assertEquals(204, preflight.statusCode());
			assertEquals("https://app.example", preflight.headers()
				.firstValue("access-control-allow-origin").orElse(null));
			assertTrue(preflight.headers().firstValue("vary").orElse("").contains("Origin"));

			// Preflight is global, not REST-only: browser MCP clients need the
			// same policy and headers before their JSON-RPC POST.
			HttpResponse<String> mcpPreflight = corsPreflight(
				server, "https://app.example", "/mcp");
			assertEquals(204, mcpPreflight.statusCode());
			assertEquals("https://app.example", mcpPreflight.headers()
				.firstValue("access-control-allow-origin").orElse(null));
		} finally {
			server.close();
		}
	}

	@Test public void testCorsLoopbackIsLiteralAndAllowsAnyPort() throws Exception {
		VenueServer server = VenueServer.launch(Maps.of(
			Config.PORT, CVMLong.create(0),
			Config.CORS_ORIGINS, Strings.create("loopback")));
		try {
			for (String origin : new String[] {
					"http://localhost:3000", "https://127.0.0.1:9443", "http://[::1]:8080"}) {
				HttpResponse<String> response = corsGet(server, origin);
				assertEquals(200, response.statusCode(), origin);
				assertEquals(origin, response.headers()
					.firstValue("access-control-allow-origin").orElse(null));
			}
			for (String origin : new String[] {
					"http://localhost.evil:3000", "http://127.0.0.2:3000"}) {
				HttpResponse<String> response = corsGet(server, origin);
				assertEquals(400, response.statusCode(), origin);
				assertTrue(response.headers().firstValue("access-control-allow-origin").isEmpty());
			}
		} finally {
			server.close();
		}
	}

	@Test public void testCorsCanBeDisabledEntirely() throws Exception {
		VenueServer server = VenueServer.launch(Maps.of(
			Config.PORT, CVMLong.create(0),
			Config.CORS_ORIGINS, CVMBool.FALSE));
		try {
			HttpResponse<String> response = corsGet(server, "https://app.example");
			assertEquals(200, response.statusCode(), response.body());
			assertTrue(response.headers().firstValue("access-control-allow-origin").isEmpty());
		} finally {
			server.close();
		}
	}

	private static HttpResponse<String> corsGet(VenueServer server, String origin) throws Exception {
		return HttpClient.newHttpClient().send(HttpRequest.newBuilder()
			.uri(new URI("http://localhost:" + server.port() + "/api/v1/status"))
			.header("Origin", origin)
			.GET().timeout(Duration.ofSeconds(10)).build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> corsPreflight(VenueServer server, String origin) throws Exception {
		return corsPreflight(server, origin, "/api/v1/status");
	}

	private static HttpResponse<String> corsPreflight(
			VenueServer server, String origin, String path) throws Exception {
		return HttpClient.newHttpClient().send(HttpRequest.newBuilder()
			.uri(new URI("http://localhost:" + server.port() + path))
			.header("Origin", origin)
			.header("Access-Control-Request-Method", "GET")
			.header("Access-Control-Request-Headers", "authorization")
			.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
			.timeout(Duration.ofSeconds(10)).build(),
			HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * Test for presence of Covia API docs: the OpenAPI document and both UIs.
	 * Pins that info.version tracks the venue build (not a hand-maintained
	 * constant that goes stale) and that the values routes document their
	 * required {@code path} parameter (they were once parameterless stubs).
	 */
	@Test public void testAPIDoc() throws URISyntaxException, InterruptedException, ExecutionException, TimeoutException {
		HttpClient client = HttpClient.newBuilder().build();
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI("http://localhost:"+PORT+"/openapi"))
			.GET()
			.timeout(Duration.ofSeconds(10))
			.build();

		CompletableFuture<HttpResponse<String>> future = client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		HttpResponse<String> resp = future.get(10000, TimeUnit.MILLISECONDS);
		assertEquals(200, resp.statusCode(), ()->"Got error response: "+resp);

		ACell spec = convex.core.util.JSON.parse(resp.body());
		assertEquals(Strings.create(convex.core.util.Utils.getVersion()),
			RT.getIn(spec, "info", "version"), "info.version must track the venue build");
		// values/list documents its parameters, path required (regression: stub annotations).
		ACell params = RT.getIn(spec, "paths", "/api/v1/values/list", "get", "parameters");
		assertTrue(params instanceof convex.core.data.AVector<?> v && v.count() >= 4,
			"values/list should document its query params, got: " + params);

		// Both documentation UIs are served.
		for (String page : new String[] {"/swagger", "/redoc"}) {
			HttpRequest pageReq = HttpRequest.newBuilder()
				.uri(new URI("http://localhost:"+PORT+page))
				.GET().timeout(Duration.ofSeconds(10)).build();
			HttpResponse<String> pageResp = client.sendAsync(pageReq,
				HttpResponse.BodyHandlers.ofString()).get(10000, TimeUnit.MILLISECONDS);
			assertEquals(200, pageResp.statusCode(), ()->page+" should be served: "+pageResp);
		}
	}

		/**
	 * Test for presence of MCP interface
	 */
	@Test public void testMCPWellKnown() throws URISyntaxException, InterruptedException, ExecutionException, TimeoutException {
		HttpClient client = HttpClient.newBuilder().build();
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI("http://localhost:"+PORT+"/.well-known/mcp"))
			.GET()
			.timeout(Duration.ofSeconds(10))
			.build();
		
		CompletableFuture<HttpResponse<String>> future = client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		HttpResponse<String> resp = future.get(10000, TimeUnit.MILLISECONDS);
		assertEquals(200, resp.statusCode(), ()->"Got error response: "+resp);
	}
	
	@Test
	public void testRandomOperation() throws Exception {		
		// Create input for random operation
		ACell input = Maps.of(
			"length", 32L
		);
		
		// Invoke the operation via the client
		String opID = TestOps.RANDOM.toHexString();
		assertEquals(64,opID.length());
		// System.out.println(opID);
		// assertNotNull(covia.getMeta(opID).get());
		Job job = covia.startJob(Strings.create(opID), input);
		boolean success=covia.waitForFinish(job);
		assertTrue(success);
		
		// Wait for job completion with timeout
		AMap<AString, ACell> jobStatus = job.getData();
		assertNotNull(jobStatus, "Should get a job status");
		
		// Get the result value from the job status
		ACell value = jobStatus.get(Fields.OUTPUT);
		assertNotNull(value, "Job should have an output value");
		
		// Verify the result
		ACell bytes = RT.getIn(value, "bytes");
		assertNotNull(bytes, "Result should contain bytes");
		String hexString = RT.ensureString(bytes).toString();
		
		// Verify hex string length (32 bytes = 64 hex chars)
		assertEquals(64, hexString.length(), "Hex string should be 64 characters long");
		
		// Verify hex string format
		assertTrue(hexString.matches("[0-9a-f]{64}"), "Result should be a valid hex string");
	}
	
	@Test
	public void testOrchOperation() throws Exception {
		// Create input for the error operation
		ACell input = Maps.of(
			"length","10"
		);
		assertNotNull(input);
		
		// Invoke the operation via the client
		Job job=covia.invokeAndWait(TestOps.ORCH,input);
		AString ps=JSON.printPretty(job.getData());
		assertEquals(job.getData(),JSON.parse(ps.toString()));
		// System.out.println("testOrchOperation:"+JSONUtils.toJSONPretty(job.getData()));
		assertEquals(Status.COMPLETE,job.getStatus());
		assertEquals(input,RT.getIn(job.getOutput(),"original-input"));
	
	}
	
	@Test
	public void testFailureOperation() throws Exception {
		// Create input for the error operation
		ACell input = Maps.of(
			Fields.MESSAGE, "Test error message"
		);
		
		// Invoke the operation via the client
		Job job=covia.invokeAndWait(TestOps.ERROR,input);
		assertEquals(Status.FAILED,job.getStatus());
	}
	
	@Test
	public void testFictitiousOp() throws Exception {
		// Create input for the error operation
		ACell input = Maps.of(
			Fields.MESSAGE, "Test error message"
		);
		
		// Invoke the operation via the client
		assertThrows(Exception.class,()->covia.invokeAndWait(Hash.get(CVMLong.ONE),input));

	}
	
	@Test public void testGetAllAssets() throws InterruptedException, ExecutionException {
		// Server should return the test assets
		CompletableFuture<List<Hash>> result = covia.getAssets();
		assertTrue(result.get().contains(TestOps.ECHO));
	}
	
	@Test
	public void testStatus() throws InterruptedException, ExecutionException {
		AMap<AString, ACell> status = covia.getStatus().get();
		assertTrue(status.get(Fields.TS) instanceof CVMLong);
		// #139: the status response must report a non-null build version so
		// operators can detect version drift across venues. Running from classes
		// this is "dev"; from the shaded jar it is the Implementation-Version.
		ACell version = status.get(Fields.VERSION);
		assertNotNull(version, "status must include a non-null version");
		assertFalse(version.toString().isEmpty(), "version must not be empty");
	}
	
	@Test
	public void testNeverOperation() throws Exception {
		// Create input for the error operation
		ACell input = Maps.of(
			Fields.MESSAGE, "Test error message"
		);
		
		// Start the operation via the client. Should start but not complete
		Job job = covia.startJob(TestOps.NEVER, input);
		Thread.sleep(50);
		covia.updateJobStatus(job);
		AString status=job.getStatus();
		assertEquals(Status.STARTED,status);
		assertFalse(job.isFinished());
	}
	
	@Test
	public void testJobLifecycleWithNeverOp() throws Exception {
		// Create input for the never operation
		ACell input = Maps.of(
			Fields.MESSAGE,"Test message for never operation"
		); 
		
		// Step 1: Invoke the operation using Covia client
		Job job=covia.startJob(TestOps.NEVER, input);
		assertEquals(Status.STARTED,job.getStatus());
		
		// Step 2: Check the status again after a brief pause
		Thread.sleep(50);
		covia.updateJobStatus(job);
	
		Blob jobId = job.getID();
		assertNotNull(jobId, "Job ID should be returned");
		String jobIdStr = jobId.toHexString();
		
		// Step 3: Confirm that the status of the job is PENDING using Covia.getJobStatus
		AMap<AString, ACell> statusMap = covia.getJobData(jobIdStr).get(5, TimeUnit.SECONDS); 
		assertNotNull(statusMap, "Job status map should not be null");
		AString status = RT.ensureString(statusMap.get(Fields.STATUS));
		assertEquals("STARTED", status.toString(), "Job status should be STARTED");
		
		// Step 4: Cancel the job using the Covia client
		AMap<AString, ACell> cancelledMap=covia.cancelJob(jobIdStr).get(5, TimeUnit.SECONDS);
		
		// Step 5: Confirm that the status is CANCELLED using Covia.getJobStatus
		assertNotNull(cancelledMap, "Cancelled job status map should not be null");
		AString cancelledStatus = RT.ensureString(cancelledMap.get(Fields.STATUS));
		assertEquals("CANCELLED", cancelledStatus.toString(), "Job status should be CANCELLED");
		
		// Step 6: Delete the job using the Covia client
		covia.deleteJob(jobIdStr).get(5, TimeUnit.SECONDS);

		// Step 7: Deletion is permanent — the durable record leaves the
		// owner's job index too (privacy contract: a deleted job must not
		// remain readable). Callers wanting an audit trail simply don't
		// delete; deletion is itself an explicit, user-initiated act.
		AMap<AString, ACell> deletedMap = covia.getJobData(jobIdStr).get(5, TimeUnit.SECONDS);
		assertNull(deletedMap, "Deleted job record should be gone (404)");
	}
	
	@Test
	public void testPauseAndResumeNeverOp() throws Exception {
		// Start a never-completing job
		Job job = covia.startJob(TestOps.NEVER, Maps.of(Fields.MESSAGE, "pause test"));
		Thread.sleep(50);
		covia.updateJobStatus(job);
		assertEquals(Status.STARTED, job.getStatus(), "Job should be STARTED");
		String jobId = job.getID().toHexString();

		// Pause the running job via API
		AMap<AString, ACell> pausedStatus = covia.pauseJob(jobId).get(5, TimeUnit.SECONDS);
		assertNotNull(pausedStatus);
		assertEquals("PAUSED", RT.ensureString(pausedStatus.get(Fields.STATUS)).toString());

		// Verify job is paused via status check
		AMap<AString, ACell> check = covia.getJobData(jobId).get(5, TimeUnit.SECONDS);
		assertEquals("PAUSED", RT.ensureString(check.get(Fields.STATUS)).toString());

		// Resume the job via API
		AMap<AString, ACell> resumedStatus = covia.resumeJob(jobId).get(5, TimeUnit.SECONDS);
		assertNotNull(resumedStatus);
		assertEquals("STARTED", RT.ensureString(resumedStatus.get(Fields.STATUS)).toString());

		// Cancel to clean up
		covia.cancelJob(jobId).get(5, TimeUnit.SECONDS);
	}

	@Test
	public void testPauseOpResumeViaAPI() throws Exception {
		// Start the auto-pausing operation
		Job job = covia.startJob(TestOps.PAUSE, Maps.of(Fields.MESSAGE, "pause op test"));
		Thread.sleep(50);
		covia.updateJobStatus(job);
		assertEquals(Status.PAUSED, job.getStatus(), "Pause op should auto-pause");
		String jobId = job.getID().toHexString();

		// Generic resume must not re-invoke an operation from persisted input:
		// that could duplicate effects. This adapter exposes message-based resume
		// for the pause op instead, so the generic endpoint rejects it.
		assertThrows(Exception.class,
				() -> covia.resumeJob(jobId).get(5, TimeUnit.SECONDS));

		// Cancel to clean up
		covia.cancelJob(jobId).get(5, TimeUnit.SECONDS);
	}

	@Test
	public void testAssetWithContent() throws Exception {
		// Create test content
		String testContent = "Hello, this is test content for the asset!";
		Blob contentBlob = Blob.wrap(testContent.getBytes());
		Hash contentHash = Hashing.sha256(contentBlob.getBytes());
		
		// Create metadata containing the content hash
		ACell metadata = Maps.of(
			Fields.NAME, "test-asset-with-content",
			Keyword.intern("description"), "Test asset with content",
			Fields.CONTENT, Maps.of(
				Fields.SHA256, contentHash.toHexString()
			)
		);
		
		// Add the asset with metadata
		Future<Hash> addAssetFuture = covia.addAsset(metadata);

		// Get the asset ID from the result
		Hash assetId = addAssetFuture.get(5, TimeUnit.SECONDS);
		assertNotNull(assetId, "Asset ID should be returned");
		String assetIdString = assetId.toString();
		assertNotNull(assetIdString, "Asset ID should be a string");
		
		// Create content object for upload
		BlobContent content =  BlobContent.of(contentBlob);
		
		// Add the content to the asset
		Future<Hash> addContentFuture = covia.addContent(assetIdString, content);
	
		Hash returnedHash=addContentFuture.get(5, TimeUnit.SECONDS);
		assertEquals(contentHash,returnedHash);
		
		// Verify the content can be downloaded again
		Future<AContent> getContentFuture = covia.getContent(assetIdString);
		AContent retrievedContent = getContentFuture.get(5, TimeUnit.SECONDS);
		
		assertNotNull(retrievedContent, "Retrieved content should not be null");
		assertTrue(retrievedContent instanceof BlobContent, "Retrieved content should be BlobContent");
		
		// Verify the content matches
		convex.core.data.ABlob retrievedBlob = retrievedContent.getBlob();
		assertNotNull(retrievedBlob, "Retrieved blob should not be null");
		
		String retrievedContentString = new String(retrievedBlob.getBytes());
		assertEquals(testContent, retrievedContentString, "Retrieved content should match original content");
	}

	@Test
	public void testHTTPInvokeAgentCreate() throws Exception {
		// Reproduce: POST /invoke with agent:create — should not hang
		HttpClient client = HttpClient.newBuilder().build();
		String body = "{\"operation\": \"v/ops/agent/create\", \"input\": {\"agentId\": \"HttpTestAgent\"}}";
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI("http://localhost:" + PORT + "/api/v1/invoke"))
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.header("Content-Type", "application/json")
			.timeout(Duration.ofSeconds(10))
			.build();

		CompletableFuture<HttpResponse<String>> future = client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		HttpResponse<String> resp = future.get(10000, TimeUnit.MILLISECONDS);
		assertTrue(resp.statusCode() == 200 || resp.statusCode() == 201,
			"Should return 200 or 201, got " + resp.statusCode() + ": " + resp.body());
		assertTrue(resp.body().contains("COMPLETE") || resp.body().contains("PENDING"),
			"Should contain job status: " + resp.body());
	}

	@Test
	public void testHTTPInvokeSecretSet() throws Exception {
		// Reproduce: POST /invoke with secret:set ��� should not hang
		HttpClient client = HttpClient.newBuilder().build();
		String body = "{\"operation\": \"v/ops/secret/set\", \"input\": {\"name\": \"TEST_SECRET\", \"value\": \"test123\"}}";
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI("http://localhost:" + PORT + "/api/v1/invoke"))
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.header("Content-Type", "application/json")
			.timeout(Duration.ofSeconds(10))
			.build();

		CompletableFuture<HttpResponse<String>> future = client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		HttpResponse<String> resp = future.get(10000, TimeUnit.MILLISECONDS);
		assertTrue(resp.statusCode() == 200 || resp.statusCode() == 201,
			"Should return 200 or 201, got " + resp.statusCode() + ": " + resp.body());
	}

	/**
	 * GET /jobs returns the paged {items, total, offset, limit} envelope
	 * (#229) — same contract as GET /assets — honouring limit.
	 */
	@Test
	public void testJobsListingEnvelope() throws Exception {
		// At least one job for the (anonymous → public) caller.
		covia.invokeAndWait(Strings.create("v/test/ops/echo"),
			Maps.of(Strings.create("x"), CVMLong.create(1)));

		HttpClient client = HttpClient.newHttpClient();
		HttpResponse<String> r = client.send(HttpRequest.newBuilder()
			.uri(new URI(BASE_URL + "/api/v1/jobs?limit=1"))
			.GET().timeout(Duration.ofSeconds(10)).build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(200, r.statusCode(), r.body());
		ACell body = JSON.parse(r.body());
		assertEquals(1L, RT.ensureLong(RT.getIn(body, "limit")).longValue());
		assertTrue(RT.ensureLong(RT.getIn(body, "total")).longValue() >= 1, r.body());
		assertEquals(0L, RT.ensureLong(RT.getIn(body, "offset")).longValue());
		AVector<?> items = (AVector<?>) RT.getIn(body, "items");
		assertEquals(1, items.count(), "limit=1 returns exactly one id");
		assertTrue(items.get(0) instanceof AString, "items are job id strings");
	}

	/** /status stats carry venue-wide and caller job counts (#229). */
	@Test
	public void testStatusStatsIncludeJobCounts() throws Exception {
		covia.invokeAndWait(Strings.create("v/test/ops/echo"),
			Maps.of(Strings.create("x"), CVMLong.create(2)));
		HttpClient client = HttpClient.newHttpClient();
		HttpResponse<String> r = client.send(HttpRequest.newBuilder()
			.uri(new URI(BASE_URL + "/api/v1/status"))
			.GET().timeout(Duration.ofSeconds(10)).build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(200, r.statusCode(), r.body());
		ACell stats = RT.getIn(JSON.parse(r.body()), "stats");
		assertTrue(RT.ensureLong(RT.getIn(stats, "jobs")).longValue() >= 1,
			"stats.jobs counts venue-wide jobs");
		assertTrue(RT.ensureLong(RT.getIn(stats, "userJobs")).longValue() >= 1,
			"stats.userJobs counts the caller's jobs");
	}

	/**
	 * A loopback bind answers on BOTH loopback protocols (#231): browsers
	 * resolving localhost to ::1 must not hang against a 127.0.0.1 venue.
	 */
	@Test
	public void testLoopbackBindServesBothProtocols() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(ipv6LoopbackAvailable(),
			"no IPv6 loopback on this machine");
		VenueServer server = VenueServer.launch(Maps.of(
			Strings.create("port"), CVMLong.create(0),
			Strings.create("bindAddress"), Strings.create("127.0.0.1")));
		try {
			int port = server.port();
			HttpClient client = HttpClient.newHttpClient();
			for (String host : new String[] {"127.0.0.1", "[::1]"}) {
				HttpResponse<String> r = client.send(HttpRequest.newBuilder()
					.uri(new URI("http://" + host + ":" + port + "/api/v1/status"))
					.GET().timeout(Duration.ofSeconds(5)).build(),
					HttpResponse.BodyHandlers.ofString());
				assertEquals(200, r.statusCode(), "status via " + host);
			}
		} finally {
			server.close();
		}
	}

	private static boolean ipv6LoopbackAvailable() {
		try (java.net.ServerSocket s = new java.net.ServerSocket(
				0, 1, java.net.InetAddress.getByName("::1"))) {
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	@Test
	public void testBindAddressConfig() {
		// Unset bindAddress → null, so the connector binds all interfaces
		// (0.0.0.0). Preserves the historical default (issue #129).
		assertNull(new Config(Maps.empty()).getBindAddress(),
			"bindAddress should default to null (wildcard bind)");

		// Explicit bindAddress is returned verbatim for connector.setHost(...)
		Config loopback = new Config(Maps.of(Config.BIND_ADDRESS, Strings.create("127.0.0.1")));
		assertEquals("127.0.0.1", loopback.getBindAddress(),
			"Configured bindAddress should be returned for the connector");
	}

	@Test
	public void testAnonymousInvokeGetsPublicDID() throws Exception {
		// Invoke via HTTP client without auth — should get public DID as caller
		ACell input = Maps.of("message", "anonymous test");
		Job job = covia.invokeAndWait(TestOps.ECHO, input);
		assertEquals(Status.COMPLETE, job.getStatus());

		// Job record should have a :caller field with the venue's public DID
		AString caller = RT.ensureString(job.getData().get(Fields.CALLER));
		assertNotNull(caller, "Anonymous invoke should have a caller DID");
		assertTrue(caller.toString().endsWith(":public"),
			"Anonymous caller DID should end with :public, got: " + caller);
	}

	/**
	 * Catalog operation names contain slashes (e.g. "v/ops/jvm/string-concat")
	 * and are percent-encoded into a single path segment by the client
	 * ({@link VenueHTTP#getOperationId}): GET /api/v1/operations/v%2Fops%2F…
	 * Jetty 12's default UriCompliance rejects %2F as an "ambiguous path
	 * separator" (400) — Jetty 11 / Javalin's own connector allowed it. The
	 * venue connector opts back in ({@code VenueServer.setupJettyServer}); this
	 * test guards that config, so named catalog lookups — and the cross-venue
	 * named references built on them — keep working after the Javalin 7 / Jetty
	 * 12 upgrade. Regression guard for the upgrade; see RemoteAssetFetchTest /
	 * RemoteOperationTest for the end-to-end cross-venue coverage.
	 */
	@Test
	public void testEncodedSlashInCatalogNamePath() throws Exception {
		String name = "v/ops/jvm/string-concat";
		String encoded = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
		HttpClient client = HttpClient.newBuilder().build();
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI("http://localhost:" + PORT + "/api/v1/operations/" + encoded))
			.GET().timeout(Duration.ofSeconds(10)).build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp.statusCode(),
			() -> "Encoded-slash catalog path must not be rejected as ambiguous: "
				+ resp.statusCode() + " " + resp.body());
		// The handler resolves the percent-decoded name to a content-addressed id.
		ACell body = JSON.parse(resp.body());
		assertNotNull(RT.getIn(body, "asset"),
			"operations/{name} must return the resolved asset id for a slashed catalog name");
	}

	/**
	 * #153: the connector allows ONLY the encoded-slash relaxation
	 * (AMBIGUOUS_PATH_SEPARATOR), not AMBIGUOUS_PATH_ENCODING. An encoded dot
	 * (%2e — the `../` traversal surface) must still be rejected as ambiguous,
	 * pinning that the compliance scope is not silently re-widened.
	 */
	@Test public void testEncodedDotPathRejected() throws Exception {
		HttpClient client = HttpClient.newBuilder().build();
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI("http://localhost:" + PORT + "/api/v1/%2e%2e/status"))
			.GET().timeout(Duration.ofSeconds(10)).build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(400, resp.statusCode(),
			() -> "Encoded-dot (%2e) path must be rejected — AMBIGUOUS_PATH_ENCODING is not enabled: "
				+ resp.statusCode() + " " + resp.body());
	}
}
