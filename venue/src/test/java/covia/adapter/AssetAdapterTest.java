package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import org.junit.jupiter.api.TestInfo;

/**
 * Tests for the AssetAdapter: store, get, and list operations.
 */
public class AssetAdapterTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString ALICE_DID;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
	}

	// ========== asset:store ==========

	@Test
	public void testStoreAsset() {
		ACell metadata = Maps.of(
			Fields.NAME, "Test Document",
			Fields.TYPE, "document",
			Fields.DESCRIPTION, "A test document");

		ACell input = Maps.of(Fields.METADATA, metadata);
		Job job = engine.jobs().invokeOperation("asset:store", input, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.STORED));
		AString id = RT.ensureString(RT.getIn(result, Fields.ID));
		assertNotNull(id, "Should return an asset ID");
		assertTrue(id.count() > 0, "Asset ID should not be empty");
	}

	@Test
	public void testStoreIdempotent() {
		ACell metadata = Maps.of(Fields.NAME, "Idempotent Test", Fields.TYPE, "test");

		// Store twice
		ACell input = Maps.of(Fields.METADATA, metadata);
		Job job1 = engine.jobs().invokeOperation("asset:store", input, RequestContext.of(ALICE_DID));
		AString id1 = RT.ensureString(RT.getIn(job1.awaitResult(5000), Fields.ID));

		Job job2 = engine.jobs().invokeOperation("asset:store", input, RequestContext.of(ALICE_DID));
		AString id2 = RT.ensureString(RT.getIn(job2.awaitResult(5000), Fields.ID));

		assertEquals(id1, id2, "Same metadata should produce same hash");
	}

	@Test
	public void testStoreNonObjectMetadata() {
		// Metadata must be a JSON object, not a string
		ACell input = Maps.of(Fields.METADATA, "not-an-object");
		Job job = engine.jobs().invokeOperation("asset:store", input, RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Should have thrown for non-object metadata");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	@Test
	public void testStoreRejectsJsonArrayMetadata() {
		// Regression: metadata string that parses to a JSON array should be rejected,
		// not silently stored as empty metadata (caused by RT.ensureMap(null) returning Maps.empty()).
		ACell input = Maps.of(Fields.METADATA, "[1, 2, 3]");
		Job job = engine.jobs().invokeOperation("asset:store", input, RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Should have thrown for JSON array metadata");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	@Test
	public void testStoreRejectsJsonNullMetadata() {
		// Regression: metadata string "null" should be rejected, not silently stored as empty.
		ACell input = Maps.of(Fields.METADATA, "null");
		Job job = engine.jobs().invokeOperation("asset:store", input, RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Should have thrown for JSON null metadata");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	// ========== asset:get ==========

	@Test
	public void testGetAsset() {
		// Store an asset first
		ACell metadata = Maps.of(
			Fields.NAME, "Retrievable Doc",
			Fields.TYPE, "document",
			Strings.create("content"), "Hello world");

		ACell storeInput = Maps.of(Fields.METADATA, metadata);
		Job storeJob = engine.jobs().invokeOperation("asset:store", storeInput, RequestContext.of(ALICE_DID));
		AString id = RT.ensureString(RT.getIn(storeJob.awaitResult(5000), Fields.ID));

		// Retrieve it
		ACell getInput = Maps.of(Fields.ID, id);
		Job getJob = engine.jobs().invokeOperation("asset:get", getInput, RequestContext.of(ALICE_DID));
		ACell result = getJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(id, RT.getIn(result, Fields.ID));
		assertEquals(CVMBool.TRUE, RT.getIn(result, Strings.create("exists")));

		// Consistent with covia:read — value contains the metadata
		AMap<AString, ACell> returnedMeta = RT.ensureMap(RT.getIn(result, Fields.VALUE));
		assertNotNull(returnedMeta);
		assertEquals(Strings.create("Retrievable Doc"), returnedMeta.get(Fields.NAME));
		assertEquals(Strings.create("document"), returnedMeta.get(Fields.TYPE));
	}

	@Test
	public void testGetNonExistent() {
		ACell input = Maps.of(Fields.ID, "0000000000000000000000000000000000000000000000000000000000000000");
		Job job = engine.jobs().invokeOperation("asset:get", input, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		// Consistent with covia:read — returns exists: false, not an error
		assertEquals(CVMBool.FALSE, RT.getIn(result, Strings.create("exists")));
		assertNull(RT.getIn(result, Fields.VALUE));
	}

	@Test
	public void testGetInvalidHash() {
		ACell input = Maps.of(Fields.ID, "not-a-hash");
		Job job = engine.jobs().invokeOperation("asset:get", input, RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Should have thrown for invalid hash format");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	// ========== asset:list ==========

	@Test
	public void testListAssets() {
		// The demo assets are already loaded by addDemoAssets
		ACell input = Maps.of(Fields.LIMIT, CVMLong.create(10));
		Job job = engine.jobs().invokeOperation("asset:list", input, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		AVector<?> items = (AVector<?>) RT.getIn(result, Fields.ITEMS);
		assertNotNull(items);
		assertTrue(items.count() > 0, "Should have demo assets");

		CVMLong total = (CVMLong) RT.getIn(result, Fields.TOTAL);
		assertNotNull(total);
		assertTrue(total.longValue() > 0);
	}

	@Test
	public void testListWithTypeFilter() {
		// Store a typed asset
		ACell metadata = Maps.of(Fields.NAME, "Test Invoice", Fields.TYPE, "invoice");
		ACell storeInput = Maps.of(Fields.METADATA, metadata);
		engine.jobs().invokeOperation("asset:store", storeInput, RequestContext.of(ALICE_DID)).awaitResult(5000);

		// List with type filter
		ACell listInput = Maps.of(Fields.TYPE, "invoice");
		Job job = engine.jobs().invokeOperation("asset:list", listInput, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		AVector<?> items = (AVector<?>) RT.getIn(result, Fields.ITEMS);
		assertNotNull(items);
		assertEquals(1, items.count(), "Should find exactly one invoice");

		AMap<AString, ACell> item = RT.ensureMap(items.get(0));
		assertEquals(Strings.create("Test Invoice"), item.get(Fields.NAME));
		assertEquals(Strings.create("invoice"), item.get(Fields.TYPE));
	}

	@Test
	public void testListPagination() {
		// Store 3 assets with a unique type
		for (int i = 0; i < 3; i++) {
			ACell metadata = Maps.of(Fields.NAME, "Page Item " + i, Fields.TYPE, "page-test");
			engine.jobs().invokeOperation("asset:store",
				Maps.of(Fields.METADATA, metadata), RequestContext.of(ALICE_DID)).awaitResult(5000);
		}

		// List with limit=2
		ACell listInput = Maps.of(Fields.TYPE, "page-test", Fields.LIMIT, CVMLong.create(2));
		Job job = engine.jobs().invokeOperation("asset:list", listInput, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		AVector<?> items = (AVector<?>) RT.getIn(result, Fields.ITEMS);
		assertEquals(2, items.count(), "Should return at most 2 items");

		// List with offset=2
		ACell listInput2 = Maps.of(Fields.TYPE, "page-test",
			Fields.OFFSET, CVMLong.create(2), Fields.LIMIT, CVMLong.create(10));
		Job job2 = engine.jobs().invokeOperation("asset:list", listInput2, RequestContext.of(ALICE_DID));
		ACell result2 = job2.awaitResult(5000);

		AVector<?> items2 = (AVector<?>) RT.getIn(result2, Fields.ITEMS);
		assertEquals(1, items2.count(), "Should return 1 remaining item");
	}

	// ========== asset:store with content ==========

	private static final AString K_CONTENT_TEXT = Strings.intern("contentText");

	@Test
	public void testStoreAndRetrieveContent() {
		ACell metadata = Maps.of(
			Fields.NAME, "Invoice with Content",
			Fields.TYPE, "invoice");
		// "Hello" = 0x48656C6C6F — hex Blob via content parameter
		ACell input = Maps.of(Fields.METADATA, metadata,
			Fields.CONTENT, Strings.create("0x48656C6C6F"));
		Job storeJob = engine.jobs().invokeOperation("asset:store", input, RequestContext.of(ALICE_DID));
		AString id = RT.ensureString(RT.getIn(storeJob.awaitResult(5000), Fields.ID));

		// asset:get returns metadata only — no content
		Job getJob = engine.jobs().invokeOperation("asset:get",
			Maps.of(Fields.ID, id), RequestContext.of(ALICE_DID));
		ACell getResult = getJob.awaitResult(5000);
		assertEquals(Strings.create("invoice"), RT.getIn(getResult, Fields.VALUE, Fields.TYPE));

		// Metadata should have content.sha256 auto-injected
		assertNotNull(RT.getIn(getResult, Fields.VALUE, Fields.CONTENT, Fields.SHA256),
			"content.sha256 should be auto-injected");

		// asset:content returns the Blob
		Job contentJob = engine.jobs().invokeOperation("asset:content",
			Maps.of(Fields.ID, id), RequestContext.of(ALICE_DID));
		ACell contentResult = contentJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(contentResult, Strings.create("exists")));

		ACell value = RT.getIn(contentResult, Fields.VALUE);
		assertNotNull(value, "Content value should be present");
		assertTrue(value instanceof ABlob, "Content should be a Blob");
		assertEquals("Hello", new String(((ABlob) value).getBytes(), java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void testStoreWithContentText() {
		ACell metadata = Maps.of(Fields.NAME, "Text Content", Fields.TYPE, "invoice");
		ACell input = Maps.of(Fields.METADATA, metadata,
			K_CONTENT_TEXT, Strings.create("Invoice from Acme Corp"));
		Job storeJob = engine.jobs().invokeOperation("asset:store", input, RequestContext.of(ALICE_DID));
		AString id = RT.ensureString(RT.getIn(storeJob.awaitResult(5000), Fields.ID));

		// Retrieve content — should be UTF-8 encoded Blob
		Job contentJob = engine.jobs().invokeOperation("asset:content",
			Maps.of(Fields.ID, id), RequestContext.of(ALICE_DID));
		ABlob blob = (ABlob) RT.getIn(contentJob.awaitResult(5000), Fields.VALUE);
		assertNotNull(blob);
		assertEquals("Invoice from Acme Corp",
			new String(blob.getBytes(), java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void testStoreRejectsBothContentAndContentText() {
		ACell metadata = Maps.of(Fields.NAME, "Both", Fields.TYPE, "test");
		ACell input = Maps.of(Fields.METADATA, metadata,
			Fields.CONTENT, Strings.create("0xAA"),
			K_CONTENT_TEXT, Strings.create("hello"));
		Job job = engine.jobs().invokeOperation("asset:store", input, RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Should reject both content and contentText");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	@Test
	public void testStoreRejectsNonHexContent() {
		ACell metadata = Maps.of(Fields.NAME, "Bad Content", Fields.TYPE, "test");
		ACell input = Maps.of(Fields.METADATA, metadata,
			Fields.CONTENT, Strings.create("plain text not hex"));
		Job job = engine.jobs().invokeOperation("asset:store", input, RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Should reject non-hex content string");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	@Test
	public void testContentWithoutPayload() {
		ACell metadata = Maps.of(Fields.NAME, "No Content Asset", Fields.TYPE, "test");
		Job storeJob = engine.jobs().invokeOperation("asset:store",
			Maps.of(Fields.METADATA, metadata), RequestContext.of(ALICE_DID));
		AString id = RT.ensureString(RT.getIn(storeJob.awaitResult(5000), Fields.ID));

		Job contentJob = engine.jobs().invokeOperation("asset:content",
			Maps.of(Fields.ID, id), RequestContext.of(ALICE_DID));
		ACell result = contentJob.awaitResult(5000);

		assertEquals(CVMBool.TRUE, RT.getIn(result, Strings.create("exists")));
		assertNull(RT.getIn(result, Fields.VALUE), "No content payload should mean no value");
	}

	@Test
	public void testContentNonExistentAsset() {
		Job job = engine.jobs().invokeOperation("asset:content",
			Maps.of(Fields.ID, "0000000000000000000000000000000000000000000000000000000000000000"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(result, Strings.create("exists")));
	}

	// ========== Content vs metadata identity ==========

	@Test
	public void testContentHashInjectedIntoMetadata() {
		ACell metadata = Maps.of(Fields.NAME, "Auto Hash Test", Fields.TYPE, "invoice");

		Job job = engine.jobs().invokeOperation("asset:store",
			Maps.of(Fields.METADATA, metadata, K_CONTENT_TEXT, Strings.create("Hello")),
			RequestContext.of(ALICE_DID));
		AString id = RT.ensureString(RT.getIn(job.awaitResult(5000), Fields.ID));

		Job getJob = engine.jobs().invokeOperation("asset:get",
			Maps.of(Fields.ID, id), RequestContext.of(ALICE_DID));
		ACell getResult = getJob.awaitResult(5000);

		AMap<AString, ACell> meta = RT.ensureMap(RT.getIn(getResult, Fields.VALUE));
		AString sha = RT.ensureString(RT.getIn(meta, Fields.CONTENT, Fields.SHA256));
		assertNotNull(sha, "content.sha256 should be auto-injected into metadata");
		assertTrue(sha.count() > 0);
	}

	@Test
	public void testDifferentContentProducesDifferentAssetId() {
		ACell meta1 = Maps.of(Fields.NAME, "Doc", Fields.TYPE, "test");
		ACell meta2 = Maps.of(Fields.NAME, "Doc", Fields.TYPE, "test");

		Job job1 = engine.jobs().invokeOperation("asset:store",
			Maps.of(Fields.METADATA, meta1, K_CONTENT_TEXT, Strings.create("version-1")),
			RequestContext.of(ALICE_DID));
		AString id1 = RT.ensureString(RT.getIn(job1.awaitResult(5000), Fields.ID));

		Job job2 = engine.jobs().invokeOperation("asset:store",
			Maps.of(Fields.METADATA, meta2, K_CONTENT_TEXT, Strings.create("version-2")),
			RequestContext.of(ALICE_DID));
		AString id2 = RT.ensureString(RT.getIn(job2.awaitResult(5000), Fields.ID));

		assertNotEquals(id1, id2, "Different content → different content.sha256 → different asset ID");
	}

	// ========== Cross-user visibility ==========

	private static final AString BOB_DID = Strings.create("did:key:z6MkBob");

	@Test
	public void testCrossUserVisibility() {
		// Alice stores an asset
		ACell metadata = Maps.of(Fields.NAME, "Alice's Asset", Fields.TYPE, "shared");
		Job storeJob = engine.jobs().invokeOperation("asset:store",
			Maps.of(Fields.METADATA, metadata), RequestContext.of(ALICE_DID));
		AString id = RT.ensureString(RT.getIn(storeJob.awaitResult(5000), Fields.ID));

		// Alice can get it
		Job aliceGetJob = engine.jobs().invokeOperation("asset:get",
			Maps.of(Fields.ID, id), RequestContext.of(ALICE_DID));
		ACell aliceResult = aliceGetJob.awaitResult(5000);
		assertEquals(Strings.create("Alice's Asset"),
			RT.getIn(aliceResult, Fields.VALUE, Fields.NAME));

		// Bob CANNOT see Alice's user-scoped asset (per-user namespace)
		Job bobGetJob = engine.jobs().invokeOperation("asset:get",
			Maps.of(Fields.ID, id), RequestContext.of(BOB_DID));
		ACell bobResult = bobGetJob.awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(bobResult, "exists"),
			"Bob should not see Alice's user-scoped asset");
	}

	// ========== List edge cases ==========

	@Test
	public void testListNoMatches() {
		ACell input = Maps.of(Fields.TYPE, "nonexistent-type-xyz");
		Job job = engine.jobs().invokeOperation("asset:list", input, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		AVector<?> items = (AVector<?>) RT.getIn(result, Fields.ITEMS);
		assertNotNull(items);
		assertEquals(0, items.count(), "Should find no matching assets");
	}

	@Test
	public void testListItemStructure() {
		ACell metadata = Maps.of(
			Fields.NAME, "Structured Item",
			Fields.TYPE, "structure-test",
			Fields.DESCRIPTION, "Testing list item fields");
		engine.jobs().invokeOperation("asset:store",
			Maps.of(Fields.METADATA, metadata), RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation("asset:list",
			Maps.of(Fields.TYPE, "structure-test"), RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		AVector<?> items = (AVector<?>) RT.getIn(result, Fields.ITEMS);
		assertEquals(1, items.count());

		AMap<AString, ACell> item = RT.ensureMap(items.get(0));
		assertNotNull(item.get(Fields.ID), "Item should have id");
		assertEquals(Strings.create("Structured Item"), item.get(Fields.NAME));
		assertEquals(Strings.create("structure-test"), item.get(Fields.TYPE));
		assertEquals(Strings.create("Testing list item fields"), item.get(Fields.DESCRIPTION));
	}

	// ========== Grid invoke path ==========

	@Test
	public void testStoreViaGridRun() {
		ACell metadata = Maps.of(Fields.NAME, "Grid Stored", Fields.TYPE, "grid-test");

		// Invoke asset:store via grid:run — the federated invocation path
		Job job = engine.jobs().invokeOperation("grid:run", Maps.of(
			Fields.OPERATION, "asset:store",
			Fields.INPUT, Maps.of(Fields.METADATA, metadata)),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.STORED));
		AString id = RT.ensureString(RT.getIn(result, Fields.ID));
		assertNotNull(id);

		// Verify retrievable via direct path
		Job getJob = engine.jobs().invokeOperation("asset:get",
			Maps.of(Fields.ID, id), RequestContext.of(ALICE_DID));
		ACell getResult = getJob.awaitResult(5000);
		assertEquals(Strings.create("Grid Stored"),
			RT.getIn(getResult, Fields.VALUE, Fields.NAME));
	}

	@Test
	public void testGetViaGridRun() {
		// Store directly
		ACell metadata = Maps.of(Fields.NAME, "Grid Get Test", Fields.TYPE, "grid-test");
		Job storeJob = engine.jobs().invokeOperation("asset:store",
			Maps.of(Fields.METADATA, metadata), RequestContext.of(ALICE_DID));
		AString id = RT.ensureString(RT.getIn(storeJob.awaitResult(5000), Fields.ID));

		// Retrieve via grid:run
		Job job = engine.jobs().invokeOperation("grid:run", Maps.of(
			Fields.OPERATION, "asset:get",
			Fields.INPUT, Maps.of(Fields.ID, id)),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertEquals(Strings.create("Grid Get Test"),
			RT.getIn(result, Fields.VALUE, Fields.NAME));
	}

	// ========== Complex nested metadata ==========

	@Test
	public void testComplexNestedMetadataRoundTrip() {
		// Simulate an agent definition with deep nesting (responseFormat schema)
		ACell schema = Maps.of(
			Strings.create("type"), "object",
			Strings.create("properties"), Maps.of(
				Strings.create("vendor_name"), Maps.of(
					Strings.create("type"), "string",
					Fields.DESCRIPTION, "Vendor name"),
				Strings.create("line_items"), Maps.of(
					Strings.create("type"), "array",
					Strings.create("items"), Maps.of(
						Strings.create("type"), "object",
						Strings.create("properties"), Maps.of(
							Fields.DESCRIPTION, Maps.of(Strings.create("type"), "string"),
							Strings.create("amount"), Maps.of(Strings.create("type"), "number"))))),
			Strings.create("required"), convex.core.data.Vectors.of(
				Strings.create("vendor_name"), Strings.create("line_items")));

		ACell metadata = Maps.of(
			Fields.NAME, "Deep Nested Agent",
			Fields.TYPE, "agent-definition",
			Strings.create("agent"), Maps.of(
				Fields.OPERATION, "llmagent:chat",
				Fields.CONFIG, Maps.of(
					Strings.create("model"), "gpt-4o",
					Strings.create("responseFormat"), Maps.of(
						Fields.NAME, "TestSchema",
						Strings.create("schema"), schema))));

		Job storeJob = engine.jobs().invokeOperation("asset:store",
			Maps.of(Fields.METADATA, metadata), RequestContext.of(ALICE_DID));
		AString id = RT.ensureString(RT.getIn(storeJob.awaitResult(5000), Fields.ID));

		// Retrieve and verify deep nesting survived the JSON round-trip
		Job getJob = engine.jobs().invokeOperation("asset:get",
			Maps.of(Fields.ID, id), RequestContext.of(ALICE_DID));
		ACell result = getJob.awaitResult(5000);

		AMap<AString, ACell> meta = RT.ensureMap(RT.getIn(result, Fields.VALUE));
		assertEquals(Strings.create("gpt-4o"),
			RT.getIn(meta, Strings.create("agent"), Fields.CONFIG, Strings.create("model")));
		assertEquals(Strings.create("TestSchema"),
			RT.getIn(meta, Strings.create("agent"), Fields.CONFIG,
				Strings.create("responseFormat"), Fields.NAME));
		assertEquals(Strings.create("object"),
			RT.getIn(meta, Strings.create("agent"), Fields.CONFIG,
				Strings.create("responseFormat"), Strings.create("schema"), Strings.create("type")));
	}

	// ========== Inline config overrides definition ==========

	@Test
	public void testInlineConfigOverridesDefinition() {
		// Store a definition with model=gpt-4o
		ACell metadata = Maps.of(
			Fields.NAME, "Override Test Def",
			Fields.TYPE, "agent-definition",
			Strings.create("agent"), Maps.of(
				Fields.OPERATION, "llmagent:chat",
				Fields.CONFIG, Maps.of(
					Strings.create("llmOperation"), "langchain:openai",
					Strings.create("model"), "gpt-4o")));

		Job storeJob = engine.jobs().invokeOperation("asset:store",
			Maps.of(Fields.METADATA, metadata), RequestContext.of(ALICE_DID));
		AString defHash = RT.ensureString(RT.getIn(storeJob.awaitResult(5000), Fields.ID));

		// Create agent with definition AND explicit config — explicit should win
		ACell createInput = Maps.of(
			Fields.AGENT_ID, "OverrideAgent",
			Fields.DEFINITION, defHash,
			Fields.CONFIG, Maps.of(Fields.OPERATION, "test:echo"));

		Job createJob = engine.jobs().invokeOperation("agent:create", createInput, RequestContext.of(ALICE_DID));
		createJob.awaitResult(5000);

		// Query — config.operation should be test:echo (explicit), not llmagent:chat (definition)
		Job queryJob = engine.jobs().invokeOperation("agent:info",
			Maps.of(Fields.AGENT_ID, "OverrideAgent"), RequestContext.of(ALICE_DID));
		ACell queryResult = queryJob.awaitResult(5000);

		AMap<AString, ACell> config = RT.ensureMap(RT.getIn(queryResult, Fields.CONFIG));
		assertEquals(Strings.create("test:echo"), config.get(Fields.OPERATION),
			"Explicit config should override definition");
	}

	// ========== Agent definition as asset ==========

	@Test
	public void testCreateAgentFromDefinition() {
		// Store an agent definition asset
		ACell agentConfig = Maps.of(
			Strings.create("llmOperation"), "langchain:openai",
			Strings.create("model"), "gpt-4o",
			Strings.create("systemPrompt"), "You are a test agent");

		ACell metadata = Maps.of(
			Fields.NAME, "Test Agent Def",
			Fields.TYPE, "agent-definition",
			Strings.create("agent"), Maps.of(
				Fields.OPERATION, "llmagent:chat",
				Fields.CONFIG, agentConfig));

		Job storeJob = engine.jobs().invokeOperation("asset:store",
			Maps.of(Fields.METADATA, metadata), RequestContext.of(ALICE_DID));
		AString defHash = RT.ensureString(RT.getIn(storeJob.awaitResult(5000), Fields.ID));

		// Create agent from definition
		ACell createInput = Maps.of(
			Fields.AGENT_ID, "DefAgent",
			Fields.DEFINITION, defHash);
		Job createJob = engine.jobs().invokeOperation("agent:create", createInput, RequestContext.of(ALICE_DID));
		ACell createResult = createJob.awaitResult(5000);

		assertEquals(CVMBool.TRUE, RT.getIn(createResult, Fields.CREATED));
		assertEquals(Strings.create("SLEEPING"), RT.getIn(createResult, Fields.STATUS));

		// Query agent and verify config was populated from definition
		Job queryJob = engine.jobs().invokeOperation("agent:info",
			Maps.of(Fields.AGENT_ID, "DefAgent"), RequestContext.of(ALICE_DID));
		ACell queryResult = queryJob.awaitResult(5000);

		// Config should have the operation and the definition hash
		AMap<AString, ACell> config = RT.ensureMap(RT.getIn(queryResult, Fields.CONFIG));
		assertNotNull(config);
		assertEquals(Strings.create("llmagent:chat"), config.get(Fields.OPERATION));
		assertEquals(defHash, config.get(Fields.DEFINITION));

		// stateConfig should have the LLM config from definition
		AMap<AString, ACell> stateConfig = RT.ensureMap(RT.getIn(queryResult, Strings.create("stateConfig")));
		assertNotNull(stateConfig, "stateConfig should be populated from definition");
		assertEquals(Strings.create("gpt-4o"), stateConfig.get(Strings.create("model")));
	}

	@Test
	public void testCreateAgentDefinitionNotFound() {
		ACell createInput = Maps.of(
			Fields.AGENT_ID, "BadDefAgent",
			Fields.DEFINITION, "0000000000000000000000000000000000000000000000000000000000000000");
		Job job = engine.jobs().invokeOperation("agent:create", createInput, RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Should have thrown for non-existent definition");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	@Test
	public void testInlineConfigStillWorks() {
		// Existing inline creation pattern must still work
		ACell input = Maps.of(
			Fields.AGENT_ID, "InlineAgent",
			Fields.CONFIG, Maps.of(Fields.OPERATION, "test:echo"));
		Job job = engine.jobs().invokeOperation("agent:create", input, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.CREATED));
	}

	@Test
	public void testStoreAgentDefinition() {
		ACell agentConfig = Maps.of(
			Strings.create("llmOperation"), "langchain:openai",
			Strings.create("model"), "gpt-4o",
			Strings.create("systemPrompt"), "You are a test agent");

		ACell metadata = Maps.of(
			Fields.NAME, "Test Agent Definition",
			Fields.TYPE, "agent-definition",
			Strings.create("agent"), Maps.of(
				Fields.OPERATION, "llmagent:chat",
				Fields.CONFIG, agentConfig));

		ACell input = Maps.of(Fields.METADATA, metadata);
		Job job = engine.jobs().invokeOperation("asset:store", input, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.STORED));

		// Retrieve and verify structure
		AString id = RT.ensureString(RT.getIn(result, Fields.ID));
		Job getJob = engine.jobs().invokeOperation("asset:get", Maps.of(Fields.ID, id), RequestContext.of(ALICE_DID));
		ACell getResult = getJob.awaitResult(5000);

		AMap<AString, ACell> returnedMeta = RT.ensureMap(RT.getIn(getResult, Fields.VALUE));
		assertEquals(Strings.create("agent-definition"), returnedMeta.get(Fields.TYPE));
		assertEquals(Strings.create("llmagent:chat"),
			RT.getIn(returnedMeta, Strings.create("agent"), Fields.OPERATION));
	}
}
