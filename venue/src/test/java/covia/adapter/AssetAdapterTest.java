package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

/**
 * Tests for the AssetAdapter: store, get, and list operations.
 */
public class AssetAdapterTest {

	private Engine engine;
	private static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");

	@BeforeEach
	public void setup() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
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

		AMap<AString, ACell> returnedMeta = RT.ensureMap(RT.getIn(result, Fields.METADATA));
		assertNotNull(returnedMeta);
		assertEquals(Strings.create("Retrievable Doc"), returnedMeta.get(Fields.NAME));
		assertEquals(Strings.create("document"), returnedMeta.get(Fields.TYPE));
	}

	@Test
	public void testGetNonExistent() {
		ACell input = Maps.of(Fields.ID, "0000000000000000000000000000000000000000000000000000000000000000");
		Job job = engine.jobs().invokeOperation("asset:get", input, RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Should have thrown for non-existent asset");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
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

	@Test
	public void testStoreAndGetWithContent() {
		ACell metadata = Maps.of(
			Fields.NAME, "Invoice with Content",
			Fields.TYPE, "invoice");
		ACell content = Maps.of(
			Strings.create("invoice_text"), "Invoice from Acme Corp, total $15,600");

		ACell input = Maps.of(Fields.METADATA, metadata, Fields.CONTENT, content);
		Job storeJob = engine.jobs().invokeOperation("asset:store", input, RequestContext.of(ALICE_DID));
		AString id = RT.ensureString(RT.getIn(storeJob.awaitResult(5000), Fields.ID));

		// Retrieve — should include content
		Job getJob = engine.jobs().invokeOperation("asset:get",
			Maps.of(Fields.ID, id), RequestContext.of(ALICE_DID));
		ACell result = getJob.awaitResult(5000);

		AMap<AString, ACell> returnedMeta = RT.ensureMap(RT.getIn(result, Fields.METADATA));
		assertEquals(Strings.create("invoice"), returnedMeta.get(Fields.TYPE));

		AMap<AString, ACell> returnedContent = RT.ensureMap(RT.getIn(result, Fields.CONTENT));
		assertNotNull(returnedContent, "Content should be returned");
		assertEquals(Strings.create("Invoice from Acme Corp, total $15,600"),
			returnedContent.get(Strings.create("invoice_text")));
	}

	@Test
	public void testStoreWithoutContentReturnsNoContent() {
		ACell metadata = Maps.of(Fields.NAME, "No Content Asset", Fields.TYPE, "test");
		Job storeJob = engine.jobs().invokeOperation("asset:store",
			Maps.of(Fields.METADATA, metadata), RequestContext.of(ALICE_DID));
		AString id = RT.ensureString(RT.getIn(storeJob.awaitResult(5000), Fields.ID));

		Job getJob = engine.jobs().invokeOperation("asset:get",
			Maps.of(Fields.ID, id), RequestContext.of(ALICE_DID));
		ACell result = getJob.awaitResult(5000);

		assertNull(RT.getIn(result, Fields.CONTENT), "Content should be absent when not stored");
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
		Job queryJob = engine.jobs().invokeOperation("agent:query",
			Maps.of(Fields.AGENT_ID, "DefAgent"), RequestContext.of(ALICE_DID));
		ACell queryResult = queryJob.awaitResult(5000);

		// Config should have the operation and the definition hash
		AMap<AString, ACell> config = RT.ensureMap(RT.getIn(queryResult, Fields.CONFIG));
		assertNotNull(config);
		assertEquals(Strings.create("llmagent:chat"), config.get(Fields.OPERATION));
		assertEquals(defHash, config.get(Fields.DEFINITION));

		// State.config should have the LLM config from definition
		AMap<AString, ACell> state = RT.ensureMap(RT.getIn(queryResult, Strings.create("state")));
		assertNotNull(state);
		AMap<AString, ACell> stateConfig = RT.ensureMap(state.get(Strings.create("config")));
		assertNotNull(stateConfig, "state.config should be populated from definition");
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

		AMap<AString, ACell> returnedMeta = RT.ensureMap(RT.getIn(getResult, Fields.METADATA));
		assertEquals(Strings.create("agent-definition"), returnedMeta.get(Fields.TYPE));
		assertEquals(Strings.create("llmagent:chat"),
			RT.getIn(returnedMeta, Strings.create("agent"), Fields.OPERATION));
	}
}
