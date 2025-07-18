package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.core5.http.Method;
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
import convex.java.HTTPClients;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.client.Covia;
import covia.venue.server.VenueServer;
import covia.venue.storage.AContent;
import covia.venue.storage.BlobContent;

@TestInstance(Lifecycle.PER_CLASS)
public class VenueServerTest {
	
	static final int PORT=TestServer.PORT;
	static final String BASE_URL=TestServer.BASE_URL;
	
	VenueServer venueServer;
	Venue venue;
	Covia covia;
	
	@BeforeAll
	public void setupServer() throws Exception {
		venueServer=TestServer.SERVER;
		venue=TestServer.VENUE;
		covia = TestServer.COVIA;
	}
	
	/**
	 * Test for presence of Covia API docs
	 */
	@Test public void testAPIDoc() throws URISyntaxException, InterruptedException, ExecutionException, TimeoutException {
		SimpleHttpRequest req=SimpleHttpRequest.create(Method.GET, new URI("http://localhost:"+PORT+"/openapi"));
		CompletableFuture<SimpleHttpResponse> future=HTTPClients.execute(req);
		SimpleHttpResponse resp=future.get(10000,TimeUnit.MILLISECONDS);
		assertEquals(200,resp.getCode(),()->"Got error response: "+resp);
	}

		/**
	 * Test for presence of MCP interface
	 */
	@Test public void testMCPWellKnown() throws URISyntaxException, InterruptedException, ExecutionException, TimeoutException {
		SimpleHttpRequest req=SimpleHttpRequest.create(Method.GET, new URI("http://localhost:"+PORT+"/.well-known/mcp"));
		CompletableFuture<SimpleHttpResponse> future=HTTPClients.execute(req);
		SimpleHttpResponse resp=future.get(10000,TimeUnit.MILLISECONDS);
		assertEquals(200,resp.getCode(),()->"Got error response: "+resp);
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
		//Job job=covia.invokeAndWait(TestOps.ORCH,input);
		//assertEquals(Status.FAILED,job.getStatus());
	
	}
	
	@Test
	public void testFailureOperation() throws Exception {
		// Create input for the error operation
		ACell input = Maps.of(
			Fields.MESSAGE, Strings.create("Test error message")
		);
		
		// Invoke the operation via the client
		Job job=covia.invokeAndWait(TestOps.ERROR,input);
		assertEquals(Status.FAILED,job.getStatus());
	
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
			Fields.MESSAGE, Strings.create("Test error message")
		);
		
		// Start the operation via the client. Should start but not complete
		Job job = covia.startJob(TestOps.NEVER, input);
		Thread.sleep(50);
		covia.updateJobStatus(job);
		AString status=job.getStatus();
		assertEquals(Status.PENDING,status);
		assertFalse(job.isFinished());
	}
	
	@Test
	public void testJobLifecycleWithNeverOp() throws Exception {
		// Create input for the never operation
		ACell input = Maps.of(
			Fields.MESSAGE, Strings.create("Test message for never operation")
		);
		
		// Step 1: Invoke the operation using Covia client
		Job job=covia.startJob(TestOps.NEVER, input);
		assertEquals(Status.PENDING,job.getStatus());
		
		// Step 2: Check the status again after a brief pause
		Thread.sleep(50);
		covia.updateJobStatus(job);
	
		AString jobId = job.getID();
		assertNotNull(jobId, "Job ID should be returned");
		String jobIdStr = jobId.toString();
		
		// Step 3: Confirm that the status of the job is PENDING using Covia.getJobStatus
		AMap<AString, ACell> statusMap = covia.getJobStatus(jobIdStr).get(5, TimeUnit.SECONDS);
		assertNotNull(statusMap, "Job status map should not be null");
		AString status = RT.ensureString(statusMap.get(Fields.JOB_STATUS_FIELD));
		assertEquals("PENDING", status.toString(), "Job status should be PENDING");
		
		// Step 4: Cancel the job using the Covia client
		covia.cancelJob(jobIdStr).get(5, TimeUnit.SECONDS);
		
		// Step 5: Confirm that the status is CANCELLED using Covia.getJobStatus
		AMap<AString, ACell> cancelledMap = covia.getJobStatus(jobIdStr).get(5, TimeUnit.SECONDS);
		assertNotNull(cancelledMap, "Cancelled job status map should not be null");
		AString cancelledStatus = RT.ensureString(cancelledMap.get(Fields.JOB_STATUS_FIELD));
		assertEquals("CANCELLED", cancelledStatus.toString(), "Job status should be CANCELLED");
		
		// Step 6: Delete the job using the Covia client
		covia.deleteJob(jobIdStr).get(5, TimeUnit.SECONDS);
		
		// Step 7: Confirm that the job no longer exists using Covia.getJobStatus
		AMap<AString, ACell> deletedMap = covia.getJobStatus(jobIdStr).get(5, TimeUnit.SECONDS);
		assertNull(deletedMap, "Deleted job status map should be null");
	}
	
	@Test
	public void testAssetWithContent() throws Exception {
		// Create test content
		String testContent = "Hello, this is test content for the asset!";
		Blob contentBlob = Blob.wrap(testContent.getBytes());
		Hash contentHash = Hashing.sha256(contentBlob.getBytes());
		
		// Create metadata containing the content hash
		ACell metadata = Maps.of(
			Fields.NAME, Strings.create("test-asset-with-content"),
			Keyword.intern("description"), Strings.create("Test asset with content"),
			Fields.CONTENT, Maps.of(
				Fields.SHA256, Strings.create(contentHash.toHexString())
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
		BlobContent content = new BlobContent(contentBlob);
		
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
}
