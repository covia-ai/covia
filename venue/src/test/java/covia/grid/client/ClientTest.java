package covia.grid.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.AContent;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.impl.BlobContent;
import covia.venue.TestOps;
import covia.venue.TestServer;

@TestInstance(Lifecycle.PER_CLASS)
public class ClientTest {
	
	private CoviaHTTP client;
	
	@BeforeAll
	public void setup() {
		client = TestServer.COVIA;
	}
	
	@Test
	public void testAddAsset() throws InterruptedException, ExecutionException, TimeoutException {
		// Create test metadata
		ACell metadata = Maps.of(
			Keyword.intern("name"), Strings.create("Test Asset"),
			Keyword.intern("description"), Strings.create("A test asset created via client")
		);
		
		// Add the asset
		CompletableFuture<Hash> future = client.addAsset(metadata);
		Hash assetId = future.get(5, TimeUnit.SECONDS);
		
		// Verify the asset was created
		assertNotNull(assetId, "Asset ID should be returned");
		assertTrue(assetId instanceof Hash, "Asset ID should be a Hash");
	}
	
	@Test
	public void testAddAssetWithJsonString() throws InterruptedException, ExecutionException, TimeoutException {
		// Create test metadata as JSON string
		String jsonMetadata = """
			{
				"name": "Test JSON Asset",
				"description": "A test asset created via JSON string"
			}
			""";
		
		// Add the asset
		CompletableFuture<Hash> future = client.addAsset(jsonMetadata);
		Hash assetId = future.get(5, TimeUnit.SECONDS);
		
		// Verify the asset was created
		assertNotNull(assetId, "Asset ID should be returned");
		assertTrue(assetId instanceof Hash, "Asset ID should be a Hash");
	}
	
	@Test
	public void testGetMeta() throws InterruptedException, ExecutionException, TimeoutException {
		// First create an asset
		ACell metadata = Maps.of(
			Keyword.intern("name"), Strings.create("Test Asset for Meta"),
			Keyword.intern("description"), Strings.create("A test asset for metadata retrieval")
		);
		
		Hash assetId = client.addAsset(metadata).get(5, TimeUnit.SECONDS);
		
		// Get the metadata
		CompletableFuture<String> future = client.getMeta(assetId.toHexString());
		String retrievedMeta = future.get(5, TimeUnit.SECONDS);
		
		// Verify the metadata was retrieved
		assertNotNull(retrievedMeta, "Metadata should be returned");
		assertTrue(retrievedMeta.contains("Test Asset for Meta"), "Metadata should contain asset name");
	}
	
	@Test
	public void testInvokeOperation() throws InterruptedException, ExecutionException, TimeoutException {
		// Create input for echo operation
		ACell input = Maps.of(
			"message", Strings.create("Hello from client test!")
		);
		
		// Invoke the operation
		CompletableFuture<Job> future = client.invoke(TestOps.ECHO, input);
		Job job=future.join();
		assertTrue(client.waitForFinish(job));
		
		// Verify the job status
		assertNotNull(job.getID(), "Job should have a valid ID");
		 
		assertEquals(Status.COMPLETE,job.getStatus(),"Job should be COMPLETE");
		
		// Get the result value from the job status
		ACell value = job.getOutput();
		assertNotNull(value, "Job should have an output value");
		
		// Verify the echo response
		ACell message = RT.getIn(value, "message");
		assertNotNull(message, "Result should contain message");
		assertEquals("Hello from client test!", message.toString());
		
	}
	
	@Test
	public void testAddContent() throws InterruptedException, ExecutionException, TimeoutException, IOException {
		// Create test content
		String testContent = "Hello, this is test content for the client!";
		Blob contentBlob = Blob.wrap(testContent.getBytes());
		Hash contentHash = Hashing.sha256(contentBlob.getBytes());
		
		// Create metadata with content hash
		ACell metadata = Maps.of(
			"name", Strings.create("Test Asset with Content"),
			"description", Strings.create("A test asset with content"),
			"content", Maps.of(
				"sha256", Strings.create(contentHash.toHexString())
			)
		);
		
		// Add the asset
		Hash assetId = client.addAsset(metadata).get(5, TimeUnit.SECONDS);
		assertNotNull(assetId, "Asset ID should be returned");
		
		// Create content object
		BlobContent content = BlobContent.of(contentBlob);
		
		// Add the content
		CompletableFuture<Hash> addContentFuture = client.addContent(assetId.toHexString(), content);
		Hash contentHashResult = addContentFuture.get(5, TimeUnit.SECONDS);
		
		// Verify content was added
		assertNotNull(contentHashResult, "Content hash should be returned");
		assertEquals(contentHash, contentHashResult, "Content hash should match");
	}
	
	@Test
	public void testGetContent() throws InterruptedException, ExecutionException, TimeoutException, IOException {
		// Create test content
		String testContent = "Hello, this is test content for retrieval!";
		Blob contentBlob = Blob.wrap(testContent.getBytes());
		Hash contentHash = Hashing.sha256(contentBlob.getBytes());
		
		// Create metadata with content hash
		ACell metadata = Maps.of(
			"name", Strings.create("Test Asset for Content Retrieval"),
			"description", Strings.create("A test asset for content retrieval"),
			"content", Maps.of(
				"sha256", Strings.create(contentHash.toHexString())
			)
		);
		
		// Add the asset
		Hash assetId = client.addAsset(metadata).get(5, TimeUnit.SECONDS);
		
		// Add the content
		BlobContent content =  BlobContent.of(contentBlob);
		client.addContent(assetId.toHexString(), content).get(5, TimeUnit.SECONDS);
		
		// Get the content
		CompletableFuture<AContent> getContentFuture = client.getContent(assetId.toHexString());
		AContent retrievedContent = getContentFuture.get(5, TimeUnit.SECONDS);
		
		// Verify the content
		assertNotNull(retrievedContent, "Retrieved content should not be null");
		assertTrue(retrievedContent instanceof BlobContent, "Retrieved content should be BlobContent");
		
		// Verify the content matches
		convex.core.data.ABlob retrievedBlob = retrievedContent.getBlob();
		assertNotNull(retrievedBlob, "Retrieved blob should not be null");
		
		String retrievedContentString = new String(retrievedBlob.getBytes());
		assertEquals(testContent, retrievedContentString, "Retrieved content should match original content");
	}
	
	@Test
	public void testCompleteAssetWithContentFlow() throws InterruptedException, ExecutionException, TimeoutException, IOException {
		// Create test content
		String testContent = "Complete flow test content!";
		Blob contentBlob = Blob.wrap(testContent.getBytes());
		Hash contentHash = Hashing.sha256(contentBlob.getBytes());

		
		// Create metadata with content hash
		ACell metadata = Maps.of(
			Fields.NAME, Strings.create("Complete Flow Test Asset"),
			Fields.DESCRIPTION, Strings.create("A test asset for complete flow"),
			Fields.CONTENT, Maps.of(
				Fields.SHA256, Strings.create(contentHash.toHexString())
			)
		);
		
		// Add the asset
		Hash assetId = client.addAsset(metadata).get(5, TimeUnit.SECONDS);
		assertNotNull(assetId, "Asset ID should be returned");
		
		
		// Add the content
		BlobContent content =  BlobContent.of(contentBlob);
		Hash storedContentHash = client.addContent(assetId.toHexString(), content).get(5, TimeUnit.SECONDS);
		assertEquals(contentHash, storedContentHash, "Stored content hash should match");
		
		
		// Get the content
		AContent retrievedContent = client.getContent(assetId.toHexString()).get(5, TimeUnit.SECONDS);
		assertNotNull(retrievedContent, "Retrieved content should not be null");
		
		// Verify the content matches
		convex.core.data.ABlob retrievedBlob = retrievedContent.getBlob();
		String retrievedContentString = new String(retrievedBlob.getBytes());
		assertEquals(testContent, retrievedContentString, "Retrieved content should match original content");
		
		// Verify the content hash matches
		Hash retrievedHash = Hashing.sha256(retrievedBlob.getBytes());
		assertEquals(contentHash, retrievedHash, "Retrieved content hash should match original hash");
		
	}
	
	@Test
	public void testInvalidAssetId() {
		// Test with invalid asset ID
		assertThrows(IllegalArgumentException.class, () -> {
			client.getMeta("invalid-asset-id");
		});
		
		assertThrows(IllegalArgumentException.class, () -> {
			client.addContent("invalid-asset-id", BlobContent.of(Blob.EMPTY));
		});
		
		assertThrows(IllegalArgumentException.class, () -> {
			client.getContent("invalid-asset-id");
		});
	}
	
	@Test
	public void testNonExistentAsset() throws InterruptedException, ExecutionException, TimeoutException {
		// Create a valid hash format but non-existent asset
		Hash fakeHash = Hashing.sha256("fake".getBytes());
		
		// Test getMeta for non-existent asset
		CompletableFuture<String> metaFuture = client.getMeta(fakeHash.toHexString());
		String meta = metaFuture.get(5, TimeUnit.SECONDS);
		assertEquals(null, meta, "Non-existent asset should return null metadata");
		
		// Test getContent for non-existent asset
		CompletableFuture<AContent> contentFuture = client.getContent(fakeHash.toHexString());
		assertThrows(ExecutionException.class, () -> {
			contentFuture.get(5, TimeUnit.SECONDS);
		});
	}
} 