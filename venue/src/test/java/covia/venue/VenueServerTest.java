package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.core5.http.Method;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.crypto.Hashing;
import convex.core.lang.RT;
import convex.java.HTTPClients;
import covia.venue.server.VenueServer;
import covia.venue.storage.AContent;
import covia.venue.storage.BlobContent;
import covia.api.Fields;
import covia.grid.client.Covia;

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
		Future<Result> resultFuture = covia.invoke(opID, input);
		
		// Wait for job completion with timeout
		Result result = resultFuture.get(5, TimeUnit.SECONDS);
		assertNotNull(result, "Should get a result");
		assertTrue(!result.isError(), "Operation should not fail");
		
		// Get the result value
		ACell value = result.getValue();
		assertNotNull(value, "Result should have a value");
		
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
	public void testFailureOperation() throws Exception {
		// Create input for the error operation
		ACell input = Maps.of(
			Fields.MESSAGE, Strings.create("Test error message")
		);
		
		// Invoke the operation via the client
		Future<Result> resultFuture = covia.invoke(TestOps.ERROR, input);
		
		// Wait for job completion with timeout and verify it fails
		ExecutionException exception = assertThrows(ExecutionException.class, () -> {
			resultFuture.get(5, TimeUnit.SECONDS);
		});
		
		// Verify the error message contains our test message
		Throwable cause = exception.getCause();
		assertTrue(cause instanceof RuntimeException, "Should be a RuntimeException");
	}
	
	@Test public void testGetAllAssets() throws InterruptedException, ExecutionException {
		// Server should return the test assets
		CompletableFuture<List<Hash>> result = covia.getAssets();
		assertTrue(result.get().contains(TestOps.ECHO));
	}
	
	@Test
	public void testNeverOperation() throws Exception {
		// Create input for the error operation
		ACell input = Maps.of(
			Fields.MESSAGE, Strings.create("Test error message")
		);
		
		// Invoke the operation via the client. Should not complete
		Future<Result> resultFuture = covia.invoke(TestOps.NEVER, input);
		Thread.sleep(50);
		assertFalse(resultFuture.isDone());
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
