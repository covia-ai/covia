package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Assets;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.impl.BlobContent;
import covia.venue.RequestContext;

public class EngineTest {
	Engine venue;
	Hash randomOpID;
	AString EMPTY_META = Strings.create("{}");

	Hash randomOpId;
	Hash echoOpId;
	Hash qwenOpId;
	
	@BeforeEach
	public void setup() throws IOException {
		venue=Engine.createTemp(null);
		Engine.addDemoAssets(venue);
		randomOpID=venue.storeAsset(Utils.readResourceAsAString("/asset-examples/randomop.json"), null);
		echoOpId=venue.storeAsset(Utils.readResourceAsAString("/asset-examples/echoop.json"), null);
		qwenOpId=venue.storeAsset(Utils.readResourceAsAString("/asset-examples/qwen.json"), null);
	}
	
	@Test
	public void testTempVenue() throws IOException {
		Blob content=Blob.EMPTY;
		Hash id=venue.storeAsset(EMPTY_META,content);
		assertEquals(EMPTY_META,venue.getMetadata(id));
		assertEquals(id,Assets.calcID(EMPTY_META));
		ACell md=venue.getMetadata(id);
		assertEquals(EMPTY_META,md);
		
	}
	
	@Test public void testAddAsset() throws InterruptedException, ExecutionException {
		// Create a test asset
		AMap<AString, ACell> meta = Maps.of(
			Keyword.intern("name"), Strings.create("Test Asset"),
			Keyword.intern("description"), Strings.create("A test asset")
		);
		
		// Add the asset
		Hash id=venue.storeAsset(JSON.printPretty(meta), null);
		assertNotNull(id);
		
		// Verify the asset was added
		AString metaString=venue.getMetadata(id);
		assertNotNull(metaString);
		assertTrue(metaString.toString().contains("Test Asset"));
	}
	
	@Test public void testDemoAssets() throws IOException {
		AString emptyMeta=venue.getMetadata(Assets.calcID(EMPTY_META));
		assertEquals(EMPTY_META,emptyMeta);
	}
	
	@Test public void testOp() throws IOException {
		String ms=Utils.readResourceAsString("/asset-examples/randomop.json");
		assertNotNull(ms);
		
		ACell md=JSON.parseJSON5(ms);
		assertEquals("Random Data Generator",RT.getIn(md, "name").toString());
	}
	
	@Test
	public void testAdapterOperation() {
		// Test echo operation
		ACell input=Maps.of("message",Strings.create("Hello World"));
		Job job=venue.jobs().invokeOperation(Strings.create("test:echo"),input, RequestContext.INTERNAL);
		AMap<AString, ACell> status = job.getData();

		// Get job ID from status
		ACell jobID = RT.getIn(status, "id");
		assertNotNull(jobID, "Job ID should be present in status");

		// Wait for job completion
		waitForJobCompletion((Blob)jobID, 5000);

		// Get final status
		ACell finalStatus = venue.jobs().getJobData((Blob)jobID, RequestContext.INTERNAL);
		assertEquals(Status.COMPLETE, RT.getIn(finalStatus, "status"));

		// Verify result
		ACell result = RT.getIn(finalStatus, "output");
		assertEquals("Hello World", RT.getIn(result, "message").toString());
	}

	@Test
	public void testAdapterError() {
		// Test error operation
		ACell input=Maps.of("message",Strings.create("Test Error"));
		Job job=venue.jobs().invokeOperation(Strings.create("test:random"),input, RequestContext.INTERNAL);
		AMap<AString, ACell> status = job.getData();

		// Get job ID from status
		ACell jobID = RT.getIn(status, "id");
		assertNotNull(jobID, "Job ID should be present in status");

		// Wait for job completion
		waitForJobCompletion((Blob)jobID, 5000);

		// Get final status
		ACell finalStatus = venue.jobs().getJobData((Blob)jobID, RequestContext.INTERNAL);
		assertEquals("FAILED", RT.getIn(finalStatus, "status").toString());
	}
	
	@Test
	public void testRandomOperation() {
		// Test random operation with 32 bytes
		ACell input = Maps.of("length", Strings.create("32"));
		Job job= venue.jobs().invokeOperation(randomOpID.toCVMHexString(), input, RequestContext.INTERNAL);
		ACell status =job.getData();
				
		// Get job ID from status
		ACell jobID = RT.getIn(status, "id");
		assertNotNull(jobID, "Job ID should be present in status");
		
		// Wait for job completion
		ACell finalStatus = waitForJobCompletion((Blob)jobID, 5000);
		
		// Get final status
		assertEquals(Status.COMPLETE, RT.getIn(finalStatus, "status"));
		
		// Verify result
		ACell result = RT.getIn(finalStatus, "output");
		String bytes = RT.getIn(result, "bytes").toString();
		
		// Verify hex string length (2 chars per byte)
		assertEquals(64, bytes.length(), "Hex string should be twice the byte length");
		// Verify hex string format
		assertTrue(bytes.matches("[0-9a-f]{64}"), "Output should be a valid hex string");
	}
	

	// @Test
	public void testQwen() {
		ACell input = Maps.of("prompt", "What is the capital of France?");
		ACell result = venue.jobs().invokeOperation(qwenOpId.toCVMHexString(), input, RequestContext.INTERNAL).awaitResult();
		result=waitForJobCompletion(RT.getIn(result, "id"), 5000);
		if (Status.COMPLETE.equals(RT.getIn(result, "status"))) {
			// OK
		} else {
			// probably ignore?
		}
		// System.err.println(JSONUtils.toString(result));	
	}
	
	private ACell waitForJobCompletion(Object jobID, long timeoutMillis) {
		Blob id=Job.parseID(jobID);
		long startTime = System.currentTimeMillis();
		ACell status = venue.jobs().getJobData(id, RequestContext.INTERNAL);
		while (System.currentTimeMillis() - startTime < timeoutMillis) {
			status = venue.jobs().getJobData(id, RequestContext.INTERNAL);
			String jobStatus = RT.getIn(status, "status").toString();
			if (!jobStatus.equals("PENDING")) {
				return status;
			}
			try {
				TimeUnit.MILLISECONDS.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				fail("Test interrupted while waiting for job completion");
			}
		}
		// fail("Timeout waiting for job completion");
		return RT.assocIn(status, Status.FAILED,"status");
	}
	
	@Test
	public void testAssetWithContentLocal() throws IOException {
		// Create test content
		String testContent = "Hello, this is test content for the local venue!";
		Blob contentBlob = Blob.wrap(testContent.getBytes());
		Hash contentHash = Hashing.sha256(contentBlob.getBytes());
		
		// Create metadata containing the content hash
		AMap<AString,ACell> metadata = Maps.of(
			Fields.NAME, Strings.create("test-asset-with-content-local"),
			Keyword.intern("description"), Strings.create("Test asset with content using local venue API"),
			Fields.CONTENT, Maps.of(
				Fields.SHA256, Strings.create(contentHash.toHexString())
			)
		);
		
		// Add the asset with metadata using local venue API
		Hash assetId = venue.storeAsset(JSON.printPretty(metadata), null);
		assertNotNull(assetId, "Asset ID should be returned");
		
		// Create content object for upload
		BlobContent content =  BlobContent.of(contentBlob);
		
		// Add the content to the asset using local venue API
		venue.putContent(venue.getMetaValue(assetId), content.getInputStream());
		
		// Verify the content can be downloaded again using local venue API
		InputStream retrievedStream = venue.getContentStream(venue.getMetaValue(assetId));
		assertNotNull(retrievedStream, "Retrieved content stream should not be null");
		
		// Read the content from the stream
		byte[] retrievedBytes = retrievedStream.readAllBytes();
		String retrievedContent = new String(retrievedBytes);
		
		// Verify the content matches
		assertEquals(testContent, retrievedContent, "Retrieved content should match original content");
		
		// Verify the content hash matches
		Blob retrievedBlob = Blob.wrap(retrievedBytes);
		Hash retrievedHash = Hashing.sha256(retrievedBlob.getBytes());
		assertEquals(contentHash, retrievedHash, "Retrieved content hash should match original hash");

	}

	/**
	 * Test that awaitResult() returns immediately for a successful echo operation
	 */
	@Test
	public void testAwaitResultSuccess() {
		ACell input = Maps.of("message", Strings.create("Hello"));
		Job job = venue.jobs().invokeOperation(Strings.create("test:echo"), input, RequestContext.INTERNAL);

		// awaitResult should complete and return the result
		ACell result = job.awaitResult();
		assertNotNull(result);
		assertEquals("Hello", RT.getIn(result, "message").toString());
	}

	/**
	 * Test that awaitResult() throws JobFailedException for a failing operation
	 * This tests the fix for the race condition where async failures weren't completing the future
	 */
	@Test
	public void testAwaitResultFailure() {
		ACell input = Maps.of("message", Strings.create("This should fail"));
		Job job = venue.jobs().invokeOperation(Strings.create("test:error"), input, RequestContext.INTERNAL);

		// awaitResult should throw JobFailedException, not hang
		assertThrows(JobFailedException.class, () -> job.awaitResult());
	}

	/**
	 * Test that awaitResult() works when called after the async task has already completed
	 * This specifically tests the race condition fix
	 */
	@Test
	public void testAwaitResultAfterCompletion() throws Exception {
		ACell input = Maps.of("message", Strings.create("Fail fast"));
		Job job = venue.jobs().invokeOperation(Strings.create("test:error"), input, RequestContext.INTERNAL);

		// Wait a bit to ensure the async task has completed
		Thread.sleep(100);

		// The job should be finished now
		assertTrue(job.isFinished(), "Job should be finished");

		// awaitResult should still throw, not hang
		assertThrows(JobFailedException.class, () -> job.awaitResult());
	}

	@Test
	public void testResolveAssetHashHex() {
		// Hex hash should resolve directly
		Hash resolved = venue.resolveHash(echoOpId.toCVMHexString());
		assertEquals(echoOpId, resolved);
	}

	@Test
	public void testResolveAssetHashDIDKeyURL() {
		// did:key:.../a/<hash> should extract the hash
		AString didUrl = Strings.create("did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK/a/" + echoOpId.toHexString());
		Hash resolved = venue.resolveHash(didUrl);
		assertEquals(echoOpId, resolved);
	}

	@Test
	public void testResolveAssetHashDIDWebURL() {
		// did:web:.../a/<hash> should also work
		AString didUrl = Strings.create("did:web:venue.example.com/a/" + echoOpId.toHexString());
		Hash resolved = venue.resolveHash(didUrl);
		assertEquals(echoOpId, resolved);
	}

	@Test
	public void testResolveAssetHashOperationName() {
		// Operation names like test:echo should resolve via registry
		Hash resolved = venue.resolveHash(Strings.create("test:echo"));
		assertNotNull(resolved, "test:echo should resolve to an asset hash");
		// The resolved hash should have valid metadata
		assertNotNull(venue.getMetaValue(resolved));
	}

	@Test
	public void testResolveAssetHashUnknown() {
		// Unknown strings should return null
		assertNull(venue.resolveHash(Strings.create("nonexistent:op")));
		assertNull(venue.resolveHash(Strings.create("did:key:z6Mk...no-asset-path")));
		assertNull(venue.resolveHash(null));
	}

	@Test
	public void testInvokeViaDIDURL() {
		// Invoke using a DID URL should work the same as hex hash
		AString didUrl = Strings.create(venue.getDIDString() + "/a/" + echoOpId.toHexString());
		ACell input = Maps.of("message", Strings.create("Hello via DID"));
		Job job = venue.jobs().invokeOperation(didUrl, input, RequestContext.INTERNAL);

		ACell result = job.awaitResult();
		assertNotNull(result);
		assertEquals("Hello via DID", RT.getIn(result, "message").toString());
	}

	@Test
	public void testInvokeViaOperationName() {
		// Invoke using operation name should resolve and work
		ACell input = Maps.of("message", Strings.create("Hello via name"));
		Job job = venue.jobs().invokeOperation(Strings.create("test:echo"), input, RequestContext.INTERNAL);

		ACell result = job.awaitResult();
		assertNotNull(result);
		assertEquals("Hello via name", RT.getIn(result, "message").toString());
	}

	@Test
	public void testOperationRegistry() {
		// The operation registry should contain operations from all adapters
		var registry = venue.getOperationRegistry();
		assertTrue(registry.count() > 0, "Operation registry should not be empty");

		// test:echo should be in the registry
		Hash echoHash = venue.resolveOperation(Strings.create("test:echo"));
		assertNotNull(echoHash, "test:echo should be registered");

		// test:error should be in the registry
		Hash errorHash = venue.resolveOperation(Strings.create("test:error"));
		assertNotNull(errorHash, "test:error should be registered");
	}

	// ========== Secret resolution ==========

	private static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");
	private static final AString BOB_DID = Strings.create("did:key:z6MkBob");

	@Test
	public void testResolveSecret() {
		// Store a secret for Alice
		User alice = venue.getVenueState().users().ensure(ALICE_DID);
		byte[] encKey = SecretStore.deriveKey(venue.getKeyPair());
		alice.secrets().store(Strings.create("OPENAI_API_KEY"), Strings.create("sk-test-123"), encKey);

		// Resolve by bare name
		RequestContext ctx = RequestContext.of(ALICE_DID);
		String resolved = venue.resolveSecret("OPENAI_API_KEY", ctx);
		assertEquals("sk-test-123", resolved);
	}

	@Test
	public void testResolveSecretWithPrefix() {
		// Store a secret for Alice
		User alice = venue.getVenueState().users().ensure(ALICE_DID);
		byte[] encKey = SecretStore.deriveKey(venue.getKeyPair());
		alice.secrets().store(Strings.create("MY_KEY"), Strings.create("secret-value"), encKey);

		// Resolve using /s/ prefix
		RequestContext ctx = RequestContext.of(ALICE_DID);
		String resolved = venue.resolveSecret("/s/MY_KEY", ctx);
		assertEquals("secret-value", resolved);
	}

	@Test
	public void testResolveSecretMissing() {
		// Ensure user exists but has no secrets
		venue.getVenueState().users().ensure(ALICE_DID);

		RequestContext ctx = RequestContext.of(ALICE_DID);
		assertNull(venue.resolveSecret("NONEXISTENT", ctx));
		assertNull(venue.resolveSecret("/s/NONEXISTENT", ctx));
	}

	@Test
	public void testResolveSecretAnonymous() {
		// Anonymous context should return null
		assertNull(venue.resolveSecret("OPENAI_API_KEY", RequestContext.ANONYMOUS));
	}

	@Test
	public void testResolveSecretUserIsolation() {
		byte[] encKey = SecretStore.deriveKey(venue.getKeyPair());

		// Store secret for Alice
		User alice = venue.getVenueState().users().ensure(ALICE_DID);
		alice.secrets().store(Strings.create("MY_KEY"), Strings.create("alice-secret"), encKey);

		// Bob should not see Alice's secret
		venue.getVenueState().users().ensure(BOB_DID);
		RequestContext bobCtx = RequestContext.of(BOB_DID);
		assertNull(venue.resolveSecret("MY_KEY", bobCtx));

		// Alice should see her own
		RequestContext aliceCtx = RequestContext.of(ALICE_DID);
		assertEquals("alice-secret", venue.resolveSecret("MY_KEY", aliceCtx));
	}

	@Test
	public void testResolveSecretNullInputs() {
		assertNull(venue.resolveSecret(null, RequestContext.of(ALICE_DID)));
		assertNull(venue.resolveSecret("SOME_KEY", null));
		assertNull(venue.resolveSecret("/s/", RequestContext.of(ALICE_DID))); // empty name after prefix
	}

	// ========== Secret field redaction ==========

	/**
	 * Build inline metadata that routes to test:echo but declares secretFields.
	 */
	private AMap<AString, ACell> echoMetaWithSecretFields(String... secrets) {
		AVector<ACell> sf = Vectors.empty();
		for (String s : secrets) sf = sf.append(Strings.create(s));
		return Maps.of(
			Fields.OPERATION, Maps.of(
				Fields.ADAPTER, Strings.create("test:echo"),
				Strings.intern("secretFields"), sf
			)
		);
	}

	@Test
	public void testSecretFieldRedactedInJobRecord() {
		AMap<AString, ACell> meta = echoMetaWithSecretFields("apiKey");
		ACell input = Maps.of(
			"prompt", Strings.create("hello"),
			"apiKey", Strings.create("sk-secret-123")
		);

		Job job = venue.jobs().invokeOperation(meta, input, RequestContext.INTERNAL);
		ACell result = job.awaitResult();
		assertNotNull(result);

		// The stored job record should have the apiKey redacted
		AMap<AString, ACell> record = venue.jobs().getJobData(job.getID(), RequestContext.INTERNAL);
		ACell storedInput = record.get(Fields.INPUT);
		assertEquals(Fields.HIDDEN, RT.getIn(storedInput, "apiKey"));

		// Non-secret fields should be preserved
		assertEquals("hello", RT.getIn(storedInput, "prompt").toString());
	}

	@Test
	public void testAdapterReceivesUnredactedInput() {
		AMap<AString, ACell> meta = echoMetaWithSecretFields("apiKey");
		ACell input = Maps.of(
			"prompt", Strings.create("hello"),
			"apiKey", Strings.create("sk-secret-123")
		);

		Job job = venue.jobs().invokeOperation(meta, input, RequestContext.INTERNAL);
		ACell result = job.awaitResult();

		// Echo adapter returns the input it received — should have the original key
		assertEquals("sk-secret-123", RT.getIn(result, "apiKey").toString());
	}

	@Test
	public void testNoSecretFieldsPassesInputUnchanged() {
		// Metadata without secretFields — input should be stored as-is
		AMap<AString, ACell> meta = Maps.of(
			Fields.OPERATION, Maps.of(
				Fields.ADAPTER, Strings.create("test:echo")
			)
		);
		ACell input = Maps.of(
			"apiKey", Strings.create("sk-visible-key")
		);

		Job job = venue.jobs().invokeOperation(meta, input, RequestContext.INTERNAL);
		job.awaitResult();

		AMap<AString, ACell> record = venue.jobs().getJobData(job.getID(), RequestContext.INTERNAL);
		ACell storedInput = record.get(Fields.INPUT);
		assertEquals("sk-visible-key", RT.getIn(storedInput, "apiKey").toString());
	}

	@Test
	public void testSecretFieldMissingFromInputIsIgnored() {
		// secretFields lists "apiKey" but input doesn't contain it — no error
		AMap<AString, ACell> meta = echoMetaWithSecretFields("apiKey");
		ACell input = Maps.of("prompt", Strings.create("hello"));

		Job job = venue.jobs().invokeOperation(meta, input, RequestContext.INTERNAL);
		ACell result = job.awaitResult();
		assertNotNull(result);

		AMap<AString, ACell> record = venue.jobs().getJobData(job.getID(), RequestContext.INTERNAL);
		ACell storedInput = record.get(Fields.INPUT);
		assertEquals("hello", RT.getIn(storedInput, "prompt").toString());
		assertNull(RT.getIn(storedInput, "apiKey"));
	}

	// ========== Secret operations ==========

	@Test
	public void testSecretSetOperation() {
		RequestContext aliceCtx = RequestContext.of(ALICE_DID);
		ACell input = Maps.of("name", Strings.create("MY_KEY"), "value", Strings.create("my-secret-value"));
		Job job = venue.jobs().invokeOperation(
			Strings.create("secret:set"), input, aliceCtx);
		ACell result = job.awaitResult();
		assertNotNull(result);
		assertEquals(Strings.create("MY_KEY"), RT.getIn(result, "name"));

		// Verify the secret was actually stored
		User alice = venue.getVenueState().users().get(ALICE_DID);
		byte[] encKey = SecretStore.deriveKey(venue.getKeyPair());
		assertEquals("my-secret-value", alice.secrets().decrypt(Strings.create("MY_KEY"), encKey).toString());

		// Verify the stored job record has the value redacted
		AMap<AString, ACell> record = venue.jobs().getJobData(job.getID(), aliceCtx);
		assertEquals(Fields.HIDDEN, RT.getIn(record.get(Fields.INPUT), "value"));
	}

	@Test
	public void testSecretExtractAlwaysDenied() {
		// Store a secret for Alice
		User alice = venue.getVenueState().users().ensure(ALICE_DID);
		byte[] encKey = SecretStore.deriveKey(venue.getKeyPair());
		alice.secrets().store(Strings.create("STOLEN_KEY"), Strings.create("do-not-leak"), encKey);

		// Attempt to extract via operation — should always fail (no capability)
		RequestContext aliceCtx = RequestContext.of(ALICE_DID);
		Job job = venue.jobs().invokeOperation(
			Strings.create("secret:extract"),
			Maps.of("name", Strings.create("STOLEN_KEY")),
			aliceCtx);

		assertThrows(Exception.class, () -> job.awaitResult());

		// Verify the secret value never appears in the job record
		AMap<AString, ACell> record = venue.jobs().getJobData(job.getID(), aliceCtx);
		assertNotNull(record);
		assertNull(record.get(Fields.OUTPUT),
			"Failed job should have no output");
	}

	@Test
	public void testSecretOutputRedactionViaEcho() {
		// Use echo adapter with secretFields to verify output redaction
		AMap<AString, ACell> meta = echoMetaWithSecretFields("value");
		ACell input = Maps.of("value", Strings.create("secret-output-test"));

		Job job = venue.jobs().invokeOperation(meta, input, RequestContext.INTERNAL);
		ACell result = job.awaitResult();

		// Live result should have the plaintext
		assertEquals("secret-output-test", RT.getIn(result, "value").toString());

		// Stored output should be redacted (echo returns input as output)
		AMap<AString, ACell> record = venue.jobs().getJobData(job.getID(), RequestContext.INTERNAL);
		ACell storedOutput = record.get(Fields.OUTPUT);
		assertEquals(Fields.HIDDEN, RT.getIn(storedOutput, "value"));
	}

	@Test
	public void testDLFSStorageConfig() throws IOException {
		// Create config with DLFS storage
		AMap<AString, ACell> config = Maps.of(
			Config.STORAGE, Maps.of(
				Config.CONTENT, Config.STORAGE_TYPE_DLFS
			)
		);

		// Create engine with DLFS storage
		Engine dlfsVenue = Engine.createTemp(config);
		assertNotNull(dlfsVenue);

		// Test storing and retrieving content
		String testContent = "DLFS storage test content";
		Blob contentBlob = Blob.wrap(testContent.getBytes());
		Hash contentHash = Hashing.sha256(contentBlob.getBytes());

		// Create metadata with content hash
		AMap<AString, ACell> metadata = Maps.of(
			Fields.NAME, Strings.create("dlfs-test-asset"),
			Fields.CONTENT, Maps.of(
				Fields.SHA256, Strings.create(contentHash.toHexString())
			)
		);

		// Store asset and content
		Hash assetId = dlfsVenue.storeAsset(JSON.printPretty(metadata), null);
		assertNotNull(assetId);

		dlfsVenue.putContent(dlfsVenue.getMetaValue(assetId), new ByteArrayInputStream(testContent.getBytes()));

		// Retrieve and verify content
		InputStream retrievedStream = dlfsVenue.getContentStream(dlfsVenue.getMetaValue(assetId));
		assertNotNull(retrievedStream);

		String retrievedContent = new String(retrievedStream.readAllBytes());
		assertEquals(testContent, retrievedContent);
	}
}
