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
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
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
	 * Test for presence of Covia API docs
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
		Job job = covia.invoke(opID, input).join();
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
		
		// Step 6: Delete the job using the Covia client (removes from active tracking)
		covia.deleteJob(jobIdStr).get(5, TimeUnit.SECONDS);

		// Step 7: Job record persists in lattice for audit trail after deletion
		AMap<AString, ACell> deletedMap = covia.getJobData(jobIdStr).get(5, TimeUnit.SECONDS);
		assertNotNull(deletedMap, "Deleted job record should still exist in lattice");
		assertEquals("CANCELLED", RT.ensureString(deletedMap.get(Fields.STATUS)).toString(),
				"Persisted record should retain last status");
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

		// Resume via API — adapter re-invoked, completes with original input
		AMap<AString, ACell> resumedStatus = covia.resumeJob(jobId).get(5, TimeUnit.SECONDS);
		assertNotNull(resumedStatus);
		// After resume, adapter is re-invoked — pause op will pause again
		assertEquals("PAUSED", RT.ensureString(resumedStatus.get(Fields.STATUS)).toString(),
				"Pause op re-invocation should pause again");

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
}
