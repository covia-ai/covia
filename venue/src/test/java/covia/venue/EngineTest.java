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
import covia.grid.Asset;
import covia.grid.Assets;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.impl.BlobContent;

public class EngineTest {
	final Engine venue = TestEngine.ENGINE;
	Hash randomOpID;
	AString EMPTY_META = Strings.create("{}");

	Hash randomOpId;
	Hash echoOpId;
	Hash qwenOpId;

	@BeforeEach
	public void setup() throws IOException {
		// Asset stores are content-addressed and idempotent — repeated calls
		// with the same metadata return the same hash, so it's safe to share
		// TestEngine.ENGINE across tests.
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
		AMap<AString, ACell> meta = Maps.of(
			Keyword.intern("name"), "Test Asset",
			Keyword.intern("description"), "A test asset"
		);

		Hash id=venue.storeAsset(JSON.printPretty(meta), null);
		assertNotNull(id);

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
		ACell input=Maps.of("message", "Hello World");
		Job job=venue.jobs().invokeOperation("v/test/ops/echo", input, venue.venueContext());
		AMap<AString, ACell> status = job.getData();

		ACell jobID = RT.getIn(status, "id");
		assertNotNull(jobID, "Job ID should be present in status");

		waitForJobCompletion((Blob)jobID, 5000);

		ACell finalStatus = venue.jobs().getJobData((Blob)jobID, venue.venueContext());
		assertEquals(Status.COMPLETE, RT.getIn(finalStatus, "status"));

		ACell result = RT.getIn(finalStatus, "output");
		assertEquals("Hello World", RT.getIn(result, "message").toString());
	}

	@Test
	public void testAdapterError() {
		ACell input=Maps.of("message", "Test Error");
		Job job=venue.jobs().invokeOperation("v/test/ops/random", input, venue.venueContext());
		AMap<AString, ACell> status = job.getData();

		ACell jobID = RT.getIn(status, "id");
		assertNotNull(jobID, "Job ID should be present in status");

		waitForJobCompletion((Blob)jobID, 5000);

		ACell finalStatus = venue.jobs().getJobData((Blob)jobID, venue.venueContext());
		assertEquals("FAILED", RT.getIn(finalStatus, "status").toString());
	}

	@Test
	public void testRandomOperation() {
		ACell input = Maps.of("length", "32");
		Job job= venue.jobs().invokeOperation(randomOpID.toCVMHexString(), input, venue.venueContext());
		ACell status =job.getData();

		ACell jobID = RT.getIn(status, "id");
		assertNotNull(jobID, "Job ID should be present in status");

		ACell finalStatus = waitForJobCompletion((Blob)jobID, 5000);

		assertEquals(Status.COMPLETE, RT.getIn(finalStatus, "status"));

		ACell result = RT.getIn(finalStatus, "output");
		String bytes = RT.getIn(result, "bytes").toString();

		assertEquals(64, bytes.length(), "Hex string should be twice the byte length");
		assertTrue(bytes.matches("[0-9a-f]{64}"), "Output should be a valid hex string");
	}


	// @Test
	public void testQwen() {
		ACell input = Maps.of("prompt", "What is the capital of France?");
		ACell result = venue.jobs().invokeOperation(qwenOpId.toCVMHexString(), input, venue.venueContext()).awaitResult(5000);
		result=waitForJobCompletion(RT.getIn(result, "id"), 5000);
		if (Status.COMPLETE.equals(RT.getIn(result, "status"))) {
			// OK
		} else {
			// probably ignore?
		}
	}

	private ACell waitForJobCompletion(Object jobID, long timeoutMillis) {
		Blob id=Job.parseID(jobID);
		long startTime = System.currentTimeMillis();
		ACell status = venue.jobs().getJobData(id, venue.venueContext());
		while (System.currentTimeMillis() - startTime < timeoutMillis) {
			status = venue.jobs().getJobData(id, venue.venueContext());
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
		return RT.assocIn(status, Status.FAILED,"status");
	}

	@Test
	public void testAssetWithContentLocal() throws IOException {
		String testContent = "Hello, this is test content for the local venue!";
		Blob contentBlob = Blob.wrap(testContent.getBytes());
		Hash contentHash = Hashing.sha256(contentBlob.getBytes());

		AMap<AString,ACell> metadata = Maps.of(
			Fields.NAME, "test-asset-with-content-local",
			Keyword.intern("description"), "Test asset with content using local venue API",
			Fields.CONTENT, Maps.of(Fields.SHA256, contentHash.toHexString())
		);

		Hash assetId = venue.storeAsset(JSON.printPretty(metadata), null);
		assertNotNull(assetId, "Asset ID should be returned");

		BlobContent content =  BlobContent.of(contentBlob);

		venue.putContent(venue.getMetaValue(assetId), content.getInputStream());

		InputStream retrievedStream = venue.getContentStream(venue.getMetaValue(assetId));
		assertNotNull(retrievedStream, "Retrieved content stream should not be null");

		byte[] retrievedBytes = retrievedStream.readAllBytes();
		String retrievedContent = new String(retrievedBytes);

		assertEquals(testContent, retrievedContent, "Retrieved content should match original content");

		Blob retrievedBlob = Blob.wrap(retrievedBytes);
		Hash retrievedHash = Hashing.sha256(retrievedBlob.getBytes());
		assertEquals(contentHash, retrievedHash, "Retrieved content hash should match original hash");
	}

	@Test
	public void testAwaitResultSuccess() {
		ACell input = Maps.of("message", "Hello");
		Job job = venue.jobs().invokeOperation("v/test/ops/echo", input, venue.venueContext());

		ACell result = job.awaitResult(5000);
		assertNotNull(result);
		assertEquals("Hello", RT.getIn(result, "message").toString());
	}

	@Test
	public void testAwaitResultFailure() {
		ACell input = Maps.of("message", "This should fail");
		Job job = venue.jobs().invokeOperation("v/test/ops/error", input, venue.venueContext());

		assertThrows(JobFailedException.class, () -> job.awaitResult(5000));
	}

	@Test
	public void testAwaitResultAfterCompletion() throws Exception {
		ACell input = Maps.of("message", "Fail fast");
		Job job = venue.jobs().invokeOperation("v/test/ops/error", input, venue.venueContext());

		Thread.sleep(100);

		assertTrue(job.isFinished(), "Job should be finished");

		assertThrows(JobFailedException.class, () -> job.awaitResult(5000));
	}

	@Test
	public void testResolveAssetHashHex() {
		Hash resolved = venue.resolveHash(echoOpId.toCVMHexString());
		assertEquals(echoOpId, resolved);
	}

	@Test
	public void testResolveAssetHashDIDKeyURL() {
		String didUrl = "did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK/a/" + echoOpId.toHexString();
		Hash resolved = venue.resolveHash(didUrl);
		assertEquals(echoOpId, resolved);
	}

	@Test
	public void testResolveAssetHashDIDWebURL() {
		String didUrl = "did:web:venue.example.com/a/" + echoOpId.toHexString();
		Hash resolved = venue.resolveHash(didUrl);
		assertEquals(echoOpId, resolved);
	}

	@Test
	public void testResolveAssetHashOperationName() {
		Hash resolved = venue.resolveHash("v/test/ops/echo");
		assertNotNull(resolved, "test:echo should resolve to an asset hash");
		assertNotNull(venue.getMetaValue(resolved));
	}

	@Test
	public void testResolveAssetHashUnknown() {
		assertNull(venue.resolveHash("nonexistent:op"));
		assertNull(venue.resolveHash("did:key:z6Mk...no-asset-path"));
		assertNull(venue.resolveHash((AString) null));
	}

	@Test
	public void testInvokeViaDIDURL() {
		String didUrl = venue.getDIDString() + "/a/" + echoOpId.toHexString();
		ACell input = Maps.of("message", "Hello via DID");
		Job job = venue.jobs().invokeOperation(didUrl, input, venue.venueContext());

		ACell result = job.awaitResult(5000);
		assertNotNull(result);
		assertEquals("Hello via DID", RT.getIn(result, "message").toString());
	}

	@Test
	public void testInvokeViaOperationName() {
		ACell input = Maps.of("message", "Hello via name");
		Job job = venue.jobs().invokeOperation("v/test/ops/echo", input, venue.venueContext());

		ACell result = job.awaitResult(5000);
		assertNotNull(result);
		assertEquals("Hello via name", RT.getIn(result, "message").toString());
	}

	@Test
	public void testCatalogResolution() {
		// Venue catalog ops resolve via universal lattice navigation.
		Asset echo = venue.resolveAsset(Strings.create("v/test/ops/echo"));
		assertNotNull(echo, "v/test/ops/echo should resolve");

		Asset error = venue.resolveAsset(Strings.create("v/test/ops/error"));
		assertNotNull(error, "v/test/ops/error should resolve");

		// Distinct assets get distinct hashes
		assertNotEquals(echo.getID(), error.getID());
	}

	// ========== Secret resolution ==========
	//
	// Per-test unique DIDs so secret writes from different tests don't
	// collide on the shared TestEngine.ENGINE.
	private final AString ALICE_DID = Strings.create("did:key:z6MkAlice-EngineTest-" + System.nanoTime());
	private final AString BOB_DID = Strings.create("did:key:z6MkBob-EngineTest-" + System.nanoTime());

	@Test
	public void testResolveSecret() {
		User alice = venue.getVenueState().users().ensure(ALICE_DID);
		byte[] encKey = SecretStore.deriveKey(venue.getKeyPair());
		alice.secrets().store("OPENAI_API_KEY", "sk-test-123", encKey);

		RequestContext ctx = RequestContext.of(ALICE_DID);
		String resolved = venue.resolveSecret("OPENAI_API_KEY", ctx);
		assertEquals("sk-test-123", resolved);
	}

	@Test
	public void testResolveSecretWithPrefix() {
		User alice = venue.getVenueState().users().ensure(ALICE_DID);
		byte[] encKey = SecretStore.deriveKey(venue.getKeyPair());
		alice.secrets().store("MY_KEY", "secret-value", encKey);

		RequestContext ctx = RequestContext.of(ALICE_DID);
		String resolved = venue.resolveSecret("/s/MY_KEY", ctx);
		assertEquals("secret-value", resolved);
	}

	@Test
	public void testResolveSecretMissing() {
		venue.getVenueState().users().ensure(ALICE_DID);

		RequestContext ctx = RequestContext.of(ALICE_DID);
		assertNull(venue.resolveSecret("NONEXISTENT", ctx));
		assertNull(venue.resolveSecret("/s/NONEXISTENT", ctx));
	}

	@Test
	public void testResolveSecretAnonymous() {
		assertNull(venue.resolveSecret("OPENAI_API_KEY", RequestContext.ANONYMOUS));
	}

	@Test
	public void testResolveSecretUserIsolation() {
		byte[] encKey = SecretStore.deriveKey(venue.getKeyPair());

		User alice = venue.getVenueState().users().ensure(ALICE_DID);
		alice.secrets().store("MY_KEY", "alice-secret", encKey);

		venue.getVenueState().users().ensure(BOB_DID);
		RequestContext bobCtx = RequestContext.of(BOB_DID);
		assertNull(venue.resolveSecret("MY_KEY", bobCtx));

		RequestContext aliceCtx = RequestContext.of(ALICE_DID);
		assertEquals("alice-secret", venue.resolveSecret("MY_KEY", aliceCtx));
	}

	@Test
	public void testResolveSecretNullInputs() {
		assertNull(venue.resolveSecret(null, RequestContext.of(ALICE_DID)));
		assertNull(venue.resolveSecret("SOME_KEY", null));
		assertNull(venue.resolveSecret("/s/", RequestContext.of(ALICE_DID)));
	}

	// ========== Configured-secret bootstrap ==========

	@Test
	public void testProvisionConfiguredSecrets() {
		AString externalDID = Strings.create("did:key:z6MkExternal-" + System.nanoTime());
		AMap<AString, ACell> cfg = Maps.of(
			Config.SECRETS, Maps.of(
				Strings.create("venue"), Maps.of(
					Strings.create("OPENAI_API_KEY"), Strings.create("sk-venue"),
					Strings.create("ANTHROPIC_API_KEY"), Strings.create("sk-anth")),
				Strings.create("public"), Maps.of(
					Strings.create("OPENAI_API_KEY"), Strings.create("sk-public")),
				externalDID, Maps.of(
					Strings.create("FOO"), Strings.create("bar"))));

		Engine e = Engine.createTemp(cfg);
		int provisioned = e.provisionConfiguredSecrets();
		assertEquals(4, provisioned, "All four secrets should be provisioned");

		AString venueDID = e.getDIDString();
		AString publicDID = Strings.create(venueDID.toString() + ":public");

		// Each user keeps its own value — no cross-user leakage
		assertEquals("sk-venue",
			e.resolveSecret("OPENAI_API_KEY", RequestContext.of(venueDID)));
		assertEquals("sk-anth",
			e.resolveSecret("ANTHROPIC_API_KEY", RequestContext.of(venueDID)));
		assertEquals("sk-public",
			e.resolveSecret("OPENAI_API_KEY", RequestContext.of(publicDID)));
		assertEquals("bar",
			e.resolveSecret("FOO", RequestContext.of(externalDID)));

		// Names not configured for a user remain unset
		assertNull(e.resolveSecret("ANTHROPIC_API_KEY", RequestContext.of(publicDID)));
		assertNull(e.resolveSecret("FOO", RequestContext.of(venueDID)));
	}

	@Test
	public void testProvisionConfiguredSecretsOverwrites() {
		AMap<AString, ACell> cfg = Maps.of(
			Config.SECRETS, Maps.of(
				Strings.create("public"), Maps.of(
					Strings.create("KEY1"), Strings.create("from-config"))));
		Engine e = Engine.createTemp(cfg);
		AString publicDID = Strings.create(e.getDIDString().toString() + ":public");

		// Pre-existing user-set value is overwritten by config
		User pub = e.getVenueState().users().ensure(publicDID);
		byte[] encKey = SecretStore.deriveKey(e.getKeyPair());
		pub.secrets().store("KEY1", "from-runtime", encKey);
		assertEquals("from-runtime", e.resolveSecret("KEY1", RequestContext.of(publicDID)));

		assertEquals(1, e.provisionConfiguredSecrets());
		assertEquals("from-config", e.resolveSecret("KEY1", RequestContext.of(publicDID)));
	}

	@Test
	public void testProvisionConfiguredSecretsAbsent() {
		Engine e = Engine.createTemp(null);
		assertEquals(0, e.provisionConfiguredSecrets(),
			"No secrets configured → returns 0, no error");
	}

	// ========== Secret field redaction ==========

	private AMap<AString, ACell> echoMetaWithSecretFields(String... secrets) {
		AVector<ACell> sf = Vectors.empty();
		for (String s : secrets) sf = sf.append(Strings.create(s));
		return Maps.of(
			Fields.OPERATION, Maps.of(
				Fields.ADAPTER, "test:echo",
				Strings.intern("secretFields"), sf
			)
		);
	}

	@Test
	public void testSecretFieldRedactedInJobRecord() {
		AMap<AString, ACell> meta = echoMetaWithSecretFields("apiKey");
		ACell input = Maps.of("prompt", "hello", "apiKey", "sk-secret-123");

		Job job = venue.jobs().invokeOperation(meta, input, venue.venueContext());
		ACell result = job.awaitResult(5000);
		assertNotNull(result);

		AMap<AString, ACell> record = venue.jobs().getJobData(job.getID(), venue.venueContext());
		ACell storedInput = record.get(Fields.INPUT);
		assertEquals(Fields.HIDDEN, RT.getIn(storedInput, "apiKey"));

		assertEquals("hello", RT.getIn(storedInput, "prompt").toString());
	}

	@Test
	public void testAdapterReceivesUnredactedInput() {
		AMap<AString, ACell> meta = echoMetaWithSecretFields("apiKey");
		ACell input = Maps.of("prompt", "hello", "apiKey", "sk-secret-123");

		Job job = venue.jobs().invokeOperation(meta, input, venue.venueContext());
		ACell result = job.awaitResult(5000);

		assertEquals("sk-secret-123", RT.getIn(result, "apiKey").toString());
	}

	@Test
	public void testNoSecretFieldsPassesInputUnchanged() {
		AMap<AString, ACell> meta = Maps.of(
			Fields.OPERATION, Maps.of(Fields.ADAPTER, "test:echo")
		);
		ACell input = Maps.of("apiKey", "sk-visible-key");

		Job job = venue.jobs().invokeOperation(meta, input, venue.venueContext());
		job.awaitResult(5000);

		AMap<AString, ACell> record = venue.jobs().getJobData(job.getID(), venue.venueContext());
		ACell storedInput = record.get(Fields.INPUT);
		assertEquals("sk-visible-key", RT.getIn(storedInput, "apiKey").toString());
	}

	@Test
	public void testSecretFieldMissingFromInputIsIgnored() {
		AMap<AString, ACell> meta = echoMetaWithSecretFields("apiKey");
		ACell input = Maps.of("prompt", "hello");

		Job job = venue.jobs().invokeOperation(meta, input, venue.venueContext());
		ACell result = job.awaitResult(5000);
		assertNotNull(result);

		AMap<AString, ACell> record = venue.jobs().getJobData(job.getID(), venue.venueContext());
		ACell storedInput = record.get(Fields.INPUT);
		assertEquals("hello", RT.getIn(storedInput, "prompt").toString());
		assertNull(RT.getIn(storedInput, "apiKey"));
	}

	// ========== Secret operations ==========

	@Test
	public void testSecretSetOperation() {
		RequestContext aliceCtx = RequestContext.of(ALICE_DID);
		ACell input = Maps.of("name", "MY_KEY", "value", "my-secret-value");
		Job job = venue.jobs().invokeOperation("v/ops/secret/set", input, aliceCtx);
		ACell result = job.awaitResult(5000);
		assertNotNull(result);
		assertEquals(Strings.create("MY_KEY"), RT.getIn(result, "name"));

		User alice = venue.getVenueState().users().get(ALICE_DID);
		byte[] encKey = SecretStore.deriveKey(venue.getKeyPair());
		assertEquals("my-secret-value", alice.secrets().decrypt("MY_KEY", encKey).toString());

		AMap<AString, ACell> record = venue.jobs().getJobData(job.getID(), aliceCtx);
		assertEquals(Fields.HIDDEN, RT.getIn(record.get(Fields.INPUT), "value"));
	}

	@Test
	public void testSecretExtractAlwaysDenied() {
		User alice = venue.getVenueState().users().ensure(ALICE_DID);
		byte[] encKey = SecretStore.deriveKey(venue.getKeyPair());
		alice.secrets().store("STOLEN_KEY", "do-not-leak", encKey);

		RequestContext aliceCtx = RequestContext.of(ALICE_DID);
		Job job = venue.jobs().invokeOperation(
			"v/ops/secret/extract", Maps.of("name", "STOLEN_KEY"), aliceCtx);

		assertThrows(Exception.class, () -> job.awaitResult(5000));

		AMap<AString, ACell> record = venue.jobs().getJobData(job.getID(), aliceCtx);
		assertNotNull(record);
		assertNull(record.get(Fields.OUTPUT), "Failed job should have no output");
	}

	@Test
	public void testSecretOutputRedactionViaEcho() {
		AMap<AString, ACell> meta = echoMetaWithSecretFields("value");
		ACell input = Maps.of("value", "secret-output-test");

		Job job = venue.jobs().invokeOperation(meta, input, venue.venueContext());
		ACell result = job.awaitResult(5000);

		assertEquals("secret-output-test", RT.getIn(result, "value").toString());

		AMap<AString, ACell> record = venue.jobs().getJobData(job.getID(), venue.venueContext());
		ACell storedOutput = record.get(Fields.OUTPUT);
		assertEquals(Fields.HIDDEN, RT.getIn(storedOutput, "value"));
	}

	@Test
	public void testDLFSStorageConfig() throws IOException {
		AMap<AString, ACell> config = Maps.of(
			Config.STORAGE, Maps.of(Config.CONTENT, Config.STORAGE_TYPE_DLFS)
		);

		Engine dlfsVenue = Engine.createTemp(config);
		assertNotNull(dlfsVenue);

		String testContent = "DLFS storage test content";
		Blob contentBlob = Blob.wrap(testContent.getBytes());
		Hash contentHash = Hashing.sha256(contentBlob.getBytes());

		AMap<AString, ACell> metadata = Maps.of(
			Fields.NAME, "dlfs-test-asset",
			Fields.CONTENT, Maps.of(Fields.SHA256, contentHash.toHexString())
		);

		Hash assetId = dlfsVenue.storeAsset(JSON.printPretty(metadata), null);
		assertNotNull(assetId);

		dlfsVenue.putContent(dlfsVenue.getMetaValue(assetId), new ByteArrayInputStream(testContent.getBytes()));

		InputStream retrievedStream = dlfsVenue.getContentStream(dlfsVenue.getMetaValue(assetId));
		assertNotNull(retrievedStream);

		String retrievedContent = new String(retrievedStream.readAllBytes());
		assertEquals(testContent, retrievedContent);
	}
}
