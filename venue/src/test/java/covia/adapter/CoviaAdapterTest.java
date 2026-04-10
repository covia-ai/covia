package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Tests for the CoviaAdapter: lattice read, write, delete, and list operations.
 */
public class CoviaAdapterTest {

	private Engine engine;
	private static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");
	private static final RequestContext ALICE = RequestContext.of(ALICE_DID);
	private static final RequestContext BOB = RequestContext.of(Strings.create("did:key:z6MkBob"));

	@BeforeEach
	public void setup() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);

		// Create an agent so there's data to read
		Job createJob = engine.jobs().invokeOperation("agent:create",
			Maps.of(Fields.AGENT_ID, "test-agent",
				Fields.CONFIG, Maps.of("model", "gpt-4"),
				AgentState.KEY_STATE, Maps.of("counter", CVMLong.ZERO)),
			ALICE);
		createJob.awaitResult(5000);
	}

	// ========== covia:read ==========

	@Test
	public void testReadAgentRecord() {
		Job job = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "g/test-agent"), ALICE);
		ACell result = job.awaitResult(5000);

		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		ACell value = RT.getIn(result, "value");
		assertNotNull(value, "Should read agent record");
		assertEquals(AgentState.SLEEPING, RT.getIn(value, AgentState.KEY_STATUS));
	}

	@Test
	public void testReadAgentState() {
		Job job = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "g/test-agent/state"), ALICE);
		ACell result = job.awaitResult(5000);

		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		ACell value = RT.getIn(result, "value");
		assertNotNull(value);
		assertEquals(CVMLong.ZERO, RT.getIn(value, "counter"));
	}

	@Test
	public void testReadAgentConfig() {
		Job job = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "g/test-agent/config"), ALICE);
		ACell result = job.awaitResult(5000);

		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		ACell value = RT.getIn(result, "value");
		assertNotNull(value);
		assertEquals(Strings.create("gpt-4"), RT.getIn(value, "model"));
	}

	@Test
	public void testReadNonExistentPath() {
		Job job = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "g/no-such-agent"), ALICE);
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(CVMBool.FALSE, RT.getIn(result, "exists"));
		assertNull(RT.getIn(result, "value"), "Non-existent path should have no value");
	}

	@Test
	public void testReadWithVectorPath() {
		Job job = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, Vectors.of(
				Strings.create("g"), Strings.create("test-agent"), Strings.create("status"))),
			ALICE);
		ACell result = job.awaitResult(5000);

		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(AgentState.SLEEPING, RT.getIn(result, "value"));
	}

	@Test
	public void testReadEmptyPath() {
		// Empty path returns entire user root
		Job job = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, ""), ALICE);
		ACell result = job.awaitResult(5000);

		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertNotNull(RT.getIn(result, "value"), "Should return user root");
	}

	// ========== covia:list ==========

	@Test
	public void testListAgents() {
		Job job = engine.jobs().invokeOperation("covia:list",
			Maps.of(Fields.PATH, "g"), ALICE);
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(Strings.create("Map"), RT.getIn(result, "type"));
		AVector<ACell> keys = RT.getIn(result, "keys");
		assertNotNull(keys);
		assertEquals(1, keys.count());
		assertEquals(Strings.create("test-agent"), keys.get(0));
	}

	@Test
	public void testListAgentFields() {
		Job job = engine.jobs().invokeOperation("covia:list",
			Maps.of(Fields.PATH, "g/test-agent"), ALICE);
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(Strings.create("Map"), RT.getIn(result, "type"));
		AVector<ACell> keys = RT.getIn(result, "keys");
		assertNotNull(keys);
		assertTrue(keys.count() > 0, "Agent record should have fields");
		CVMLong count = RT.getIn(result, "count");
		assertEquals(keys.count(), count.longValue());
	}

	@Test
	public void testListTopLevel() {
		// No path — list top-level namespaces
		Job job = engine.jobs().invokeOperation("covia:list",
			Maps.empty(), ALICE);
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(Strings.create("Map"), RT.getIn(result, "type"));
		AVector<ACell> keys = RT.getIn(result, "keys");
		assertNotNull(keys);
		assertTrue(keys.count() > 0, "Should have at least the 'g' namespace");
	}

	@Test
	public void testListPagination() {
		// Create several agents
		for (int i = 0; i < 5; i++) {
			engine.jobs().invokeOperation("agent:create",
				Maps.of(Fields.AGENT_ID, "agent-" + i), ALICE).awaitResult(5000);
		}

		// List with limit — should include offset in response
		Job job = engine.jobs().invokeOperation("covia:list",
			Maps.of(Fields.PATH, "g", Fields.LIMIT, CVMLong.create(3)), ALICE);
		ACell result = job.awaitResult(5000);

		AVector<ACell> keys = RT.getIn(result, "keys");
		CVMLong total = RT.getIn(result, "count");
		assertEquals(3, keys.count(), "Should respect limit");
		assertEquals(6, total.longValue(), "Total should include all agents (5 + test-agent)");
		// offset present because results are truncated
		assertNotNull(RT.getIn(result, "offset"), "Should include offset when truncated");
	}

	@Test
	public void testListNonExistentPath() {
		Job job = engine.jobs().invokeOperation("covia:list",
			Maps.of(Fields.PATH, "g/no-such-agent"), ALICE);
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(CVMBool.FALSE, RT.getIn(result, "exists"));
	}

	@Test
	public void testListVector() {
		// Agent timeline is a vector — should report type and count, no keys
		// First, deliver a message and trigger to create a timeline entry
		AgentState agent = engine.getVenueState().users().get(ALICE_DID).agent("test-agent");
		assertNotNull(agent);
		AVector<ACell> timeline = agent.getTimeline();
		assertNotNull(timeline);

		Job job = engine.jobs().invokeOperation("covia:list",
			Maps.of(Fields.PATH, "g/test-agent/timeline"), ALICE);
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(Strings.create("Vector"), RT.getIn(result, "type"));
		assertNotNull(RT.getIn(result, "count"));
		assertNull(RT.getIn(result, "keys"), "Vectors should not have keys");
	}

	// ========== Isolation / adversarial ==========

	@Test
	public void testCannotReadOtherUsersData() {
		// Bob should not see Alice's agents
		RequestContext BOB = RequestContext.of(Strings.create("did:key:z6MkBob"));

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "g/test-agent"), BOB);
		ACell result = readJob.awaitResult(5000);

		assertEquals(CVMBool.FALSE, RT.getIn(result, "exists"),
			"Bob should not be able to read Alice's agent");
	}

	@Test
	public void testCannotListOtherUsersAgents() {
		RequestContext BOB = RequestContext.of(Strings.create("did:key:z6MkBob"));

		Job listJob = engine.jobs().invokeOperation("covia:list",
			Maps.of(Fields.PATH, "g"), BOB);
		ACell result = listJob.awaitResult(5000);

		assertEquals(CVMBool.FALSE, RT.getIn(result, "exists"),
			"Bob should not see Alice's agent namespace");
	}

	@Test
	public void testNonExistentUserReturnsNotFound() {
		RequestContext NOBODY = RequestContext.of(Strings.create("did:key:z6MkNobody"));

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "g"), NOBODY);
		ACell readResult = readJob.awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(readResult, "exists"));

		Job listJob = engine.jobs().invokeOperation("covia:list",
			Maps.of(Fields.PATH, "g"), NOBODY);
		ACell listResult = listJob.awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(listResult, "exists"));
	}

	@Test
	public void testUnauthenticatedRequestFails() {
		// Anonymous requests should be rejected as an auth failure
		assertThrows(AuthException.class, () ->
			engine.jobs().invokeOperation("covia:read",
				Maps.of(Fields.PATH, "g"), RequestContext.ANONYMOUS));
	}

	// ========== covia:write ==========

	@Test
	public void testWriteToWorkspace() {
		// Write a string value to /w/
		Job writeJob = engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/notes", Fields.VALUE, Strings.create("hello world")),
			ALICE);
		ACell writeResult = writeJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(writeResult, "written"));

		// Read it back
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/notes"), ALICE);
		ACell readResult = readJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(readResult, "exists"));
		assertEquals(Strings.create("hello world"), RT.getIn(readResult, "value"));
	}

	@Test
	public void testWriteToOperations() {
		// Write to /o/ namespace
		ACell opDef = Maps.of("type", "custom", "handler", "my-handler");
		Job writeJob = engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "o/my-op", Fields.VALUE, opDef),
			ALICE);
		ACell writeResult = writeJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(writeResult, "written"));

		// Read it back
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "o/my-op"), ALICE);
		ACell readResult = readJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(readResult, "exists"));
		assertEquals(Strings.create("custom"), RT.getIn(RT.getIn(readResult, "value"), "type"));
	}

	@Test
	public void testWriteMapValue() {
		ACell mapValue = Maps.of("key1", "val1", "key2", CVMLong.create(42));
		Job writeJob = engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/structured", Fields.VALUE, mapValue),
			ALICE);
		writeJob.awaitResult(5000);

		// Read back and verify structure
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/structured"), ALICE);
		ACell readResult = readJob.awaitResult(5000);
		ACell value = RT.getIn(readResult, "value");
		assertEquals(Strings.create("val1"), RT.getIn(value, "key1"));
		assertEquals(CVMLong.create(42), RT.getIn(value, "key2"));
	}

	@Test
	public void testWriteVectorValue() {
		ACell vecValue = Vectors.of(Strings.create("a"), Strings.create("b"), Strings.create("c"));
		Job writeJob = engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/list", Fields.VALUE, vecValue),
			ALICE);
		writeJob.awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/list"), ALICE);
		ACell readResult = readJob.awaitResult(5000);
		AVector<ACell> value = RT.getIn(readResult, "value");
		assertNotNull(value);
		assertEquals(3, value.count());
		assertEquals(Strings.create("b"), value.get(1));
	}

	@Test
	public void testWriteNumericValue() {
		Job writeJob = engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/counter", Fields.VALUE, CVMLong.create(99)),
			ALICE);
		writeJob.awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/counter"), ALICE);
		ACell readResult = readJob.awaitResult(5000);
		assertEquals(CVMLong.create(99), RT.getIn(readResult, "value"));
	}

	@Test
	public void testWriteOverwritesExistingValue() {
		// Write initial value
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/data", Fields.VALUE, Strings.create("first")),
			ALICE).awaitResult(5000);

		// Overwrite with new value
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/data", Fields.VALUE, Strings.create("second")),
			ALICE).awaitResult(5000);

		// Verify new value
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/data"), ALICE);
		ACell readResult = readJob.awaitResult(5000);
		assertEquals(Strings.create("second"), RT.getIn(readResult, "value"));
	}

	// ========== covia:write — JSON-string coercion ==========
	//
	// LLMs frequently call covia:write with `value` as a JSON-encoded string
	// rather than a structured map/array. The adapter must parse such strings
	// so the audit trail contains queryable structures, not opaque blobs.

	@Test
	public void testWriteCoercesJsonObjectString() {
		String json = "{\"vendor_id\":\"V-1042\",\"status\":\"ACTIVE\",\"score\":0.95}";
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/enrichments/INV-001",
			        Fields.VALUE, Strings.create(json)),
			ALICE).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/enrichments/INV-001"), ALICE);
		ACell readResult = readJob.awaitResult(5000);
		ACell value = RT.getIn(readResult, "value");
		assertNotNull(value, "value should be present");
		// If parsing worked, this is a structured map and we can navigate into it
		assertEquals(Strings.create("V-1042"), RT.getIn(value, "vendor_id"));
		assertEquals(Strings.create("ACTIVE"), RT.getIn(value, "status"));
	}

	@Test
	public void testWriteCoercesJsonArrayString() {
		String json = "[\"a\",\"b\",\"c\"]";
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/tags", Fields.VALUE, Strings.create(json)),
			ALICE).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/tags"), ALICE);
		ACell readResult = readJob.awaitResult(5000);
		AVector<ACell> value = RT.getIn(readResult, "value");
		assertNotNull(value);
		assertEquals(3, value.count());
		assertEquals(Strings.create("b"), value.get(1));
	}

	@Test
	public void testWritePlainStringNotCoerced() {
		// Strings that are not JSON delimiters must be stored as-is.
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/note", Fields.VALUE, Strings.create("just a note")),
			ALICE).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/note"), ALICE);
		assertEquals(Strings.create("just a note"),
			RT.getIn(readJob.awaitResult(5000), "value"));
	}

	@Test
	public void testWriteMalformedJsonStringPreserved() {
		// Looks like JSON but isn't — must not throw, must store original string.
		String malformed = "{not really json}";
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/note2", Fields.VALUE, Strings.create(malformed)),
			ALICE).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/note2"), ALICE);
		assertEquals(Strings.create(malformed),
			RT.getIn(readJob.awaitResult(5000), "value"));
	}

	@Test
	public void testAppendCoercesJsonObjectString() {
		String json = "{\"event\":\"validated\",\"by\":\"Bob\"}";
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/audit", Fields.VALUE, Strings.create(json)),
			ALICE).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/audit"), ALICE);
		AVector<ACell> value = RT.getIn(readJob.awaitResult(5000), "value");
		assertNotNull(value);
		assertEquals(1, value.count());
		// Element must be a parsed map, not the raw string
		assertEquals(Strings.create("validated"), RT.getIn(value.get(0), "event"));
	}

	@Test
	public void testWriteMultipleKeys() {
		// Write several keys to workspace
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/alpha", Fields.VALUE, Strings.create("A")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/beta", Fields.VALUE, Strings.create("B")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/gamma", Fields.VALUE, Strings.create("C")),
			ALICE).awaitResult(5000);

		// List workspace keys
		Job listJob = engine.jobs().invokeOperation("covia:list",
			Maps.of(Fields.PATH, "w"), ALICE);
		ACell listResult = listJob.awaitResult(5000);

		assertEquals(CVMBool.TRUE, RT.getIn(listResult, "exists"));
		assertEquals(Strings.create("Map"), RT.getIn(listResult, "type"));
		CVMLong count = RT.getIn(listResult, "count");
		assertEquals(3, count.longValue());
	}

	@Test
	public void testWriteCreatesUserNamespace() {
		// New user with no prior state can write
		RequestContext CAROL = RequestContext.of(Strings.create("did:key:z6MkCarol"));
		Job writeJob = engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/first-entry", Fields.VALUE, Strings.create("hello")),
			CAROL);
		ACell writeResult = writeJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(writeResult, "written"));

		// Read back confirms it persisted
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/first-entry"), CAROL);
		ACell readResult = readJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(readResult, "exists"));
		assertEquals(Strings.create("hello"), RT.getIn(readResult, "value"));
	}

	// ========== covia:write — namespace restrictions ==========

	@Test
	public void testCannotWriteToAgentsNamespace() {
		Job job = engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "g/fake-agent", Fields.VALUE, Strings.create("hack")),
			ALICE);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
	}

	@Test
	public void testCannotWriteToSecretsNamespace() {
		Job job = engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "s/my-secret", Fields.VALUE, Strings.create("hack")),
			ALICE);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
	}

	@Test
	public void testCannotWriteToJobsNamespace() {
		Job job = engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "j/some-job", Fields.VALUE, Strings.create("hack")),
			ALICE);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
	}

	@Test
	public void testCannotWriteWithPathTooShort() {
		// Just "w" with no key is invalid
		Job job = engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w", Fields.VALUE, Strings.create("no-key")),
			ALICE);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
	}

	@Test
	public void testUnauthenticatedWriteFails() {
		assertThrows(AuthException.class, () ->
			engine.jobs().invokeOperation("covia:write",
				Maps.of(Fields.PATH, "w/test", Fields.VALUE, Strings.create("nope")),
				RequestContext.ANONYMOUS));
	}

	// ========== covia:delete ==========

	@Test
	public void testDeleteFromWorkspace() {
		// Write then delete
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/ephemeral", Fields.VALUE, Strings.create("temporary")),
			ALICE).awaitResult(5000);

		Job deleteJob = engine.jobs().invokeOperation("covia:delete",
			Maps.of(Fields.PATH, "w/ephemeral"), ALICE);
		ACell deleteResult = deleteJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(deleteResult, "deleted"));

		// Verify it's gone
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/ephemeral"), ALICE);
		ACell readResult = readJob.awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(readResult, "exists"));
	}

	@Test
	public void testDeleteFromOperations() {
		// Write then delete in /o/
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "o/temp-op", Fields.VALUE, Maps.of("x", CVMLong.ONE)),
			ALICE).awaitResult(5000);

		engine.jobs().invokeOperation("covia:delete",
			Maps.of(Fields.PATH, "o/temp-op"), ALICE).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "o/temp-op"), ALICE);
		ACell readResult = readJob.awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(readResult, "exists"));
	}

	@Test
	public void testDeleteNonExistentKeyIsIdempotent() {
		// Deleting something that doesn't exist should succeed silently
		Job deleteJob = engine.jobs().invokeOperation("covia:delete",
			Maps.of(Fields.PATH, "w/never-existed"), ALICE);
		ACell deleteResult = deleteJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(deleteResult, "deleted"));
	}

	@Test
	public void testDeleteDoesNotAffectOtherKeys() {
		// Write two keys
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/keep", Fields.VALUE, Strings.create("kept")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/remove", Fields.VALUE, Strings.create("removed")),
			ALICE).awaitResult(5000);

		// Delete one
		engine.jobs().invokeOperation("covia:delete",
			Maps.of(Fields.PATH, "w/remove"), ALICE).awaitResult(5000);

		// Other key still exists
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/keep"), ALICE);
		ACell readResult = readJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(readResult, "exists"));
		assertEquals(Strings.create("kept"), RT.getIn(readResult, "value"));
	}

	@Test
	public void testCannotDeleteFromAgentsNamespace() {
		Job job = engine.jobs().invokeOperation("covia:delete",
			Maps.of(Fields.PATH, "g/test-agent"), ALICE);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
	}

	@Test
	public void testUnauthenticatedDeleteFails() {
		assertThrows(AuthException.class, () ->
			engine.jobs().invokeOperation("covia:delete",
				Maps.of(Fields.PATH, "w/test"), RequestContext.ANONYMOUS));
	}

	// ========== covia:write/read — isolation ==========

	@Test
	public void testWorkspaceIsolationBetweenUsers() {
		RequestContext BOB = RequestContext.of(Strings.create("did:key:z6MkBob"));

		// Alice writes to her workspace
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/secret-plans", Fields.VALUE, Strings.create("alice-only")),
			ALICE).awaitResult(5000);

		// Bob cannot read Alice's workspace
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/secret-plans"), BOB);
		ACell readResult = readJob.awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(readResult, "exists"),
			"Bob should not see Alice's workspace data");

		// Bob can write to his own workspace without affecting Alice
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/secret-plans", Fields.VALUE, Strings.create("bob-only")),
			BOB).awaitResult(5000);

		// Alice still sees her own value
		Job aliceRead = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/secret-plans"), ALICE);
		ACell aliceResult = aliceRead.awaitResult(5000);
		assertEquals(Strings.create("alice-only"), RT.getIn(aliceResult, "value"));

		// Bob sees his own value
		Job bobRead = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/secret-plans"), BOB);
		ACell bobResult = bobRead.awaitResult(5000);
		assertEquals(Strings.create("bob-only"), RT.getIn(bobResult, "value"));
	}

	// ========== Cross-user access — capability grants ==========

	@Test
	public void testCrossUserReadDenied() {
		// Alice writes workspace data
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/public-info", Fields.VALUE, Strings.create("hello from alice")),
			ALICE).awaitResult(5000);

		// Bob tries to read Alice's workspace via DID-prefixed path
		RequestContext BOB = RequestContext.of(Strings.create("did:key:z6MkBob"));
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/public-info"),
			BOB);
		// Should fail with a capability error, not silently return null
		assertThrows(Exception.class, () -> readJob.awaitResult(5000));
	}

	@Test
	public void testCrossUserListDenied() {
		RequestContext BOB = RequestContext.of(Strings.create("did:key:z6MkBob"));
		Job listJob = engine.jobs().invokeOperation("covia:list",
			Maps.of(Fields.PATH, ALICE_DID + "/w"),
			BOB);
		assertThrows(Exception.class, () -> listJob.awaitResult(5000));
	}

	@Test
	public void testCrossUserSliceDenied() {
		// Create a vector for Alice
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/events", Fields.VALUE, Strings.create("ev1")),
			ALICE).awaitResult(5000);

		RequestContext BOB = RequestContext.of(Strings.create("did:key:z6MkBob"));
		Job sliceJob = engine.jobs().invokeOperation("covia:slice",
			Maps.of(Fields.PATH, ALICE_DID + "/w/events"),
			BOB);
		assertThrows(Exception.class, () -> sliceJob.awaitResult(5000));
	}

	@Test
	public void testCrossUserReadNonExistentUser() {
		// Same error as existing user — must not leak user existence
		RequestContext BOB = RequestContext.of(Strings.create("did:key:z6MkBob"));
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "did:key:z6MkNobody/w/anything"),
			BOB);
		assertThrows(Exception.class, () -> readJob.awaitResult(5000));
	}

	@Test
	public void testReadOwnNamespaceWithExplicitDid() {
		// Alice reads her own namespace with DID prefix — should work normally
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/mine", Fields.VALUE, Strings.create("my data")),
			ALICE).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/mine"),
			ALICE);
		ACell result = readJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("my data"), RT.getIn(result, "value"));
	}

	// Cross-user grant tests will be added when UCAN proof presentation
	// is implemented in RequestContext (Phase C1).

	// ========== covia:write — deep paths ==========

	@Test
	public void testDeepWriteCreatesIntermediateMaps() {
		// Write at a 3-segment path: w/data/title
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/data/title", Fields.VALUE, Strings.create("My Document")),
			ALICE).awaitResult(5000);

		// Read the nested value directly
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/data"), ALICE);
		ACell readResult = readJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(readResult, "exists"));
		ACell dataMap = RT.getIn(readResult, "value");
		assertEquals(Strings.create("My Document"), RT.getIn(dataMap, "title"));
	}

	@Test
	public void testDeepWriteMultiLevel() {
		// Write at a 4-segment path: w/projects/alpha/status
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/projects/alpha/status", Fields.VALUE, Strings.create("active")),
			ALICE).awaitResult(5000);

		// Read the top-level entry
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/projects"), ALICE);
		ACell readResult = readJob.awaitResult(5000);
		ACell projects = RT.getIn(readResult, "value");
		assertNotNull(projects);
		assertEquals(Strings.create("active"), RT.getIn(RT.getIn(projects, "alpha"), "status"));
	}

	@Test
	public void testDeepWritePreservesExistingSiblings() {
		// Write two nested fields under the same top-level key
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/config/model", Fields.VALUE, Strings.create("gpt-4")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/config/temperature", Fields.VALUE, CVMLong.create(7)),
			ALICE).awaitResult(5000);

		// Both fields should be present
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/config"), ALICE);
		ACell config = RT.getIn(readJob.awaitResult(5000), "value");
		assertEquals(Strings.create("gpt-4"), RT.getIn(config, "model"));
		assertEquals(CVMLong.create(7), RT.getIn(config, "temperature"));
	}

	@Test
	public void testDeepWriteOverwritesNestedValue() {
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/doc/title", Fields.VALUE, Strings.create("Draft")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/doc/title", Fields.VALUE, Strings.create("Final")),
			ALICE).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/doc"), ALICE);
		ACell doc = RT.getIn(readJob.awaitResult(5000), "value");
		assertEquals(Strings.create("Final"), RT.getIn(doc, "title"));
	}

	@Test
	public void testDeepWriteInOperationsNamespace() {
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "o/pipeline/steps/first", Fields.VALUE, Strings.create("extract")),
			ALICE).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "o/pipeline"), ALICE);
		ACell pipeline = RT.getIn(readJob.awaitResult(5000), "value");
		assertEquals(Strings.create("extract"), RT.getIn(RT.getIn(pipeline, "steps"), "first"));
	}

	// ========== covia:delete — deep paths ==========

	@Test
	public void testDeepDeleteRemovesNestedKey() {
		// Set up nested structure
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/doc/title", Fields.VALUE, Strings.create("Title")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/doc/body", Fields.VALUE, Strings.create("Body")),
			ALICE).awaitResult(5000);

		// Delete just the title
		engine.jobs().invokeOperation("covia:delete",
			Maps.of(Fields.PATH, "w/doc/title"), ALICE).awaitResult(5000);

		// Body still exists, title is gone
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/doc"), ALICE);
		ACell doc = RT.getIn(readJob.awaitResult(5000), "value");
		assertEquals(Strings.create("Body"), RT.getIn(doc, "body"));
		assertNull(RT.getIn(doc, "title"));
	}

	@Test
	public void testDeepDeleteNonExistentNestedKey() {
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/data/x", Fields.VALUE, CVMLong.ONE),
			ALICE).awaitResult(5000);

		// Delete a non-existent sibling — should not error
		Job deleteJob = engine.jobs().invokeOperation("covia:delete",
			Maps.of(Fields.PATH, "w/data/y"), ALICE);
		ACell deleteResult = deleteJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(deleteResult, "deleted"));

		// Original key still intact
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/data"), ALICE);
		ACell data = RT.getIn(readJob.awaitResult(5000), "value");
		assertEquals(CVMLong.ONE, RT.getIn(data, "x"));
	}

	// ========== covia:append ==========

	@Test
	public void testAppendCreatesVector() {
		// Append to non-existent path creates a single-element vector
		Job appendJob = engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/events", Fields.VALUE, Strings.create("event-1")),
			ALICE);
		ACell appendResult = appendJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(appendResult, "appended"));

		// Read back — should be a vector
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/events"), ALICE);
		AVector<ACell> events = RT.getIn(readJob.awaitResult(5000), "value");
		assertNotNull(events);
		assertEquals(1, events.count());
		assertEquals(Strings.create("event-1"), events.get(0));
	}

	@Test
	public void testAppendExtendsVector() {
		// Append three elements
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/log", Fields.VALUE, Strings.create("line-1")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/log", Fields.VALUE, Strings.create("line-2")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/log", Fields.VALUE, Strings.create("line-3")),
			ALICE).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/log"), ALICE);
		AVector<ACell> log = RT.getIn(readJob.awaitResult(5000), "value");
		assertEquals(3, log.count());
		assertEquals(Strings.create("line-1"), log.get(0));
		assertEquals(Strings.create("line-3"), log.get(2));
	}

	@Test
	public void testAppendAtNestedPath() {
		// Append to w/agent/logs — creates intermediate maps + vector
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/agent/logs", Fields.VALUE, Strings.create("started")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/agent/logs", Fields.VALUE, Strings.create("completed")),
			ALICE).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/agent"), ALICE);
		ACell agent = RT.getIn(readJob.awaitResult(5000), "value");
		AVector<ACell> logs = RT.getIn(agent, "logs");
		assertNotNull(logs);
		assertEquals(2, logs.count());
		assertEquals(Strings.create("completed"), logs.get(1));
	}

	@Test
	public void testAppendToNonVectorFails() {
		// Write a string value first
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/scalar", Fields.VALUE, Strings.create("not a vector")),
			ALICE).awaitResult(5000);

		// Append to it should fail
		Job appendJob = engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/scalar", Fields.VALUE, Strings.create("oops")),
			ALICE);
		assertThrows(Exception.class, () -> appendJob.awaitResult(5000));
	}

	@Test
	public void testAppendInOperationsNamespace() {
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "o/pipeline/steps", Fields.VALUE, Strings.create("step-1")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "o/pipeline/steps", Fields.VALUE, Strings.create("step-2")),
			ALICE).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "o/pipeline"), ALICE);
		ACell pipeline = RT.getIn(readJob.awaitResult(5000), "value");
		AVector<ACell> steps = RT.getIn(pipeline, "steps");
		assertEquals(2, steps.count());
	}

	@Test
	public void testAppendMapElements() {
		// Append structured elements (maps) to a vector
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/facts", Fields.VALUE,
				Maps.of("topic", "lattice", "summary", "CRDTs are cool")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/facts", Fields.VALUE,
				Maps.of("topic", "agents", "summary", "Agents use tools")),
			ALICE).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/facts"), ALICE);
		AVector<ACell> facts = RT.getIn(readJob.awaitResult(5000), "value");
		assertEquals(2, facts.count());
		assertEquals(Strings.create("agents"), RT.getIn(facts.get(1), "topic"));
	}

	// ========== Deep path helpers — unit tests ==========

	@Test
	public void testDeepSetCreatesIntermediateMaps() {
		ACell[] keys = CoviaAdapter.parseStringPath("w/a/b/c");
		ACell result = CoviaAdapter.deepSet(null, keys, 2, Strings.create("leaf"));

		// Should create {b: {c: "leaf"}}
		assertNotNull(result);
		assertEquals(Strings.create("leaf"), RT.getIn(RT.getIn(result, "b"), "c"));
	}

	@Test
	public void testDeepDeletePreservesSiblings() {
		ACell root = Maps.of("x", CVMLong.ONE, "y", CVMLong.create(2));
		ACell[] keys = CoviaAdapter.parseStringPath("w/entry/x");
		ACell result = CoviaAdapter.deepDelete(root, keys, 2);

		// y should remain, x should be gone
		assertNotNull(result);
		assertNull(RT.getIn(result, "x"));
		assertEquals(CVMLong.create(2), RT.getIn(result, "y"));
	}

	// ========== covia:read/write — vector index navigation ==========

	@Test
	public void testReadVectorElementByIndex() {
		// Build a vector, then read individual elements by index
		for (int i = 0; i < 3; i++) {
			engine.jobs().invokeOperation("covia:append",
				Maps.of(Fields.PATH, "w/events", Fields.VALUE, Strings.create("event-" + i)),
				ALICE).awaitResult(5000);
		}

		// Read element at index 1
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/events/1"), ALICE);
		ACell result = readJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("event-1"), RT.getIn(result, "value"));
	}

	@Test
	public void testReadVectorElementOutOfBounds() {
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/items", Fields.VALUE, Strings.create("only-one")),
			ALICE).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/items/5"), ALICE);
		ACell result = readJob.awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(result, "exists"));
	}

	@Test
	public void testReadNestedVectorElement() {
		// Write a map containing a vector, then read into the vector by index
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/data/tags", Fields.VALUE, Strings.create("alpha")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/data/tags", Fields.VALUE, Strings.create("beta")),
			ALICE).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/data/tags/0"), ALICE);
		ACell result = readJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("alpha"), RT.getIn(result, "value"));
	}

	@Test
	public void testReadVectorOfMapsFieldAccess() {
		// Append maps to a vector, then navigate into a specific element's field
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/records", Fields.VALUE,
				Maps.of("name", "Alice", "score", CVMLong.create(100))),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/records", Fields.VALUE,
				Maps.of("name", "Bob", "score", CVMLong.create(200))),
			ALICE).awaitResult(5000);

		// Read records/1/name
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/records/1/name"), ALICE);
		ACell result = readJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("Bob"), RT.getIn(result, "value"));
	}

	@Test
	public void testWriteVectorElementByIndex() {
		// Create a vector, then overwrite an element by index
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/list", Fields.VALUE, Strings.create("a")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/list", Fields.VALUE, Strings.create("b")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/list", Fields.VALUE, Strings.create("c")),
			ALICE).awaitResult(5000);

		// Overwrite element 1
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/list/1", Fields.VALUE, Strings.create("B-updated")),
			ALICE).awaitResult(5000);

		// Verify
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/list/1"), ALICE);
		assertEquals(Strings.create("B-updated"), RT.getIn(readJob.awaitResult(5000), "value"));

		// Other elements unchanged
		Job read0 = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/list/0"), ALICE);
		assertEquals(Strings.create("a"), RT.getIn(read0.awaitResult(5000), "value"));
	}

	@Test
	public void testWriteVectorElementOutOfBoundsFails() {
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/tiny", Fields.VALUE, Strings.create("one")),
			ALICE).awaitResult(5000);

		Job writeJob = engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/tiny/5", Fields.VALUE, Strings.create("oops")),
			ALICE);
		assertThrows(Exception.class, () -> writeJob.awaitResult(5000));
	}

	@Test
	public void testListVectorByIndexPath() {
		// List a map nested inside a vector element
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/entries", Fields.VALUE,
				Maps.of("x", CVMLong.ONE, "y", CVMLong.create(2))),
			ALICE).awaitResult(5000);

		Job listJob = engine.jobs().invokeOperation("covia:list",
			Maps.of(Fields.PATH, "w/entries/0"), ALICE);
		ACell result = listJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("Map"), RT.getIn(result, "type"));
		assertEquals(CVMLong.create(2), RT.getIn(result, "count"));
	}

	@Test
	public void testSliceNestedVector() {
		// Slice a vector that's nested inside a map
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/nested/vec", Fields.VALUE, Strings.create("x")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:append",
			Maps.of(Fields.PATH, "w/nested/vec", Fields.VALUE, Strings.create("y")),
			ALICE).awaitResult(5000);

		Job sliceJob = engine.jobs().invokeOperation("covia:slice",
			Maps.of(Fields.PATH, "w/nested/vec"), ALICE);
		ACell result = sliceJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("Vector"), RT.getIn(result, "type"));
		assertEquals(CVMLong.create(2), RT.getIn(result, "count"));
	}

	// ========== covia:read — maxSize ==========

	@Test
	public void testReadMaxSizeTruncatesLargeValue() {
		// Write a value, then read with a very small maxSize
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/big", Fields.VALUE,
				Maps.of("a", Strings.create("some data"), "b", Strings.create("more data"))),
			ALICE).awaitResult(5000);

		// Read with maxSize=0 should always truncate
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/big", Strings.intern("maxSize"), CVMLong.ZERO),
			ALICE);
		ACell result = readJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(CVMBool.TRUE, RT.getIn(result, "truncated"));
		assertNull(RT.getIn(result, "value"), "Truncated response should not include value");
		assertNotNull(RT.getIn(result, "size"), "Truncated response should include size");
	}

	@Test
	public void testReadDefaultMaxSizeAllowsSmallValues() {
		// A small value should be returned without truncation
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/small", Fields.VALUE, Strings.create("tiny")),
			ALICE).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/small"), ALICE);
		ACell result = readJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertNull(RT.getIn(result, "truncated"), "Small value should not be truncated");
		assertEquals(Strings.create("tiny"), RT.getIn(result, "value"));
	}

	// ========== covia:slice — vectors ==========

	@Test
	public void testSliceVector() {
		// Build a vector with 5 elements
		for (int i = 0; i < 5; i++) {
			engine.jobs().invokeOperation("covia:append",
				Maps.of(Fields.PATH, "w/items", Fields.VALUE, Strings.create("item-" + i)),
				ALICE).awaitResult(5000);
		}

		// Slice the middle
		Job sliceJob = engine.jobs().invokeOperation("covia:slice",
			Maps.of(Fields.PATH, "w/items", Fields.OFFSET, CVMLong.create(1), Fields.LIMIT, CVMLong.create(2)),
			ALICE);
		ACell result = sliceJob.awaitResult(5000);

		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("Vector"), RT.getIn(result, "type"));
		assertEquals(CVMLong.create(5), RT.getIn(result, "count"));
		assertEquals(CVMLong.create(1), RT.getIn(result, "offset"));
		AVector<ACell> values = RT.getIn(result, "values");
		assertEquals(2, values.count());
		assertEquals(Strings.create("item-1"), values.get(0));
		assertEquals(Strings.create("item-2"), values.get(1));
	}

	@Test
	public void testSliceVectorDefaults() {
		// Slice with no offset/limit — defaults to offset=0, limit=100
		for (int i = 0; i < 3; i++) {
			engine.jobs().invokeOperation("covia:append",
				Maps.of(Fields.PATH, "w/short", Fields.VALUE, CVMLong.create(i)),
				ALICE).awaitResult(5000);
		}

		Job sliceJob = engine.jobs().invokeOperation("covia:slice",
			Maps.of(Fields.PATH, "w/short"), ALICE);
		ACell result = sliceJob.awaitResult(5000);

		AVector<ACell> values = RT.getIn(result, "values");
		assertEquals(3, values.count());
		assertEquals(CVMLong.create(0), RT.getIn(result, "offset"));
	}

	// ========== covia:slice — maps ==========

	@Test
	public void testSliceMap() {
		// Write a map with several keys
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/config",
				Fields.VALUE, Maps.of("a", CVMLong.ONE, "b", CVMLong.create(2), "c", CVMLong.create(3))),
			ALICE).awaitResult(5000);

		Job sliceJob = engine.jobs().invokeOperation("covia:slice",
			Maps.of(Fields.PATH, "w/config", Fields.LIMIT, CVMLong.create(2)),
			ALICE);
		ACell result = sliceJob.awaitResult(5000);

		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("Map"), RT.getIn(result, "type"));
		assertEquals(CVMLong.create(3), RT.getIn(result, "count"));
		AVector<ACell> values = RT.getIn(result, "values");
		assertEquals(2, values.count());
		// Each entry should have "key" and "value"
		assertNotNull(RT.getIn(values.get(0), "key"));
		assertNotNull(RT.getIn(values.get(0), "value"));
	}

	// ========== covia:slice — errors ==========

	@Test
	public void testSliceNonExistentPath() {
		Job sliceJob = engine.jobs().invokeOperation("covia:slice",
			Maps.of(Fields.PATH, "w/nonexistent"), ALICE);
		ACell result = sliceJob.awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(result, "exists"));
	}

	@Test
	public void testSliceScalarFails() {
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/num", Fields.VALUE, CVMLong.create(42)),
			ALICE).awaitResult(5000);

		Job sliceJob = engine.jobs().invokeOperation("covia:slice",
			Maps.of(Fields.PATH, "w/num"), ALICE);
		assertThrows(Exception.class, () -> sliceJob.awaitResult(5000));
	}

	// ========== Path parsing ==========

	@Test
	public void testParseStringPath() {
		ACell[] keys = CoviaAdapter.parseStringPath("g/my-agent/timeline/3");
		assertEquals(4, keys.length);
		assertEquals(Strings.create("g"), keys[0]);
		assertEquals(Strings.create("my-agent"), keys[1]);
		assertEquals(Strings.create("timeline"), keys[2]);
		// All segments are AString — lattice resolveKey handles type translation
		assertEquals(Strings.create("3"), keys[3]);
	}

	@Test
	public void testParseEmptyPath() {
		assertEquals(0, CoviaAdapter.parseStringPath("").length);
		assertEquals(0, CoviaAdapter.parseStringPath(null).length);
		assertEquals(0, CoviaAdapter.parseStringPath("/").length);
	}

	// ========== Agent workspace (n/ prefix) ==========

	@Test
	public void testAgentWorkspaceWrite() {
		// Create agent-scoped context
		RequestContext agentCtx = ALICE.withAgentId(Strings.create("test-agent"));

		// Write to n/ — should succeed
		Job writeJob = engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("n/notes/vendor-a"),
				Strings.create("value"), Strings.create("23% share, growth slowing")),
			agentCtx);
		ACell result = writeJob.awaitResult(5000);
		assertNotNull(result);
		assertTrue(CVMBool.TRUE.equals(RT.getIn(result, Strings.intern("written"))));
	}

	@Test
	public void testAgentWorkspaceRead() {
		RequestContext agentCtx = ALICE.withAgentId(Strings.create("test-agent"));

		// Write then read via n/ shorthand
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("n/methodology"),
				Strings.create("value"), Strings.create("Compare revenue and margins")),
			agentCtx).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Strings.create("path"), Strings.create("n/methodology")),
			agentCtx);
		ACell result = readJob.awaitResult(5000);
		assertTrue(CVMBool.TRUE.equals(RT.getIn(result, Strings.intern("exists"))));
		assertEquals("Compare revenue and margins",
			RT.ensureString(RT.getIn(result, Strings.intern("value"))).toString());
	}

	@Test
	public void testAgentWorkspaceReadViaFullPath() {
		RequestContext agentCtx = ALICE.withAgentId(Strings.create("test-agent"));

		// Write via n/ shorthand
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("n/data"),
				Strings.create("value"), Strings.create("agent private")),
			agentCtx).awaitResult(5000);

		// Read via full g/ path (no agent scope needed)
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Strings.create("path"), Strings.create("g/test-agent/n/data")),
			ALICE);
		ACell result = readJob.awaitResult(5000);
		assertTrue(CVMBool.TRUE.equals(RT.getIn(result, Strings.intern("exists"))));
		assertEquals("agent private",
			RT.ensureString(RT.getIn(result, Strings.intern("value"))).toString());
	}

	@Test
	public void testAgentWorkspaceDelete() {
		RequestContext agentCtx = ALICE.withAgentId(Strings.create("test-agent"));

		// Write then delete
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("n/temp"),
				Strings.create("value"), Strings.create("ephemeral")),
			agentCtx).awaitResult(5000);

		Job deleteJob = engine.jobs().invokeOperation("covia:delete",
			Maps.of(Strings.create("path"), Strings.create("n/temp")),
			agentCtx);
		ACell result = deleteJob.awaitResult(5000);
		assertTrue(CVMBool.TRUE.equals(RT.getIn(result, Strings.intern("deleted"))));

		// Verify deleted
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Strings.create("path"), Strings.create("n/temp")),
			agentCtx);
		ACell readResult = readJob.awaitResult(5000);
		assertFalse(CVMBool.TRUE.equals(RT.getIn(readResult, Strings.intern("exists"))));
	}

	@Test
	public void testAgentWorkspaceStructuredData() {
		RequestContext agentCtx = ALICE.withAgentId(Strings.create("test-agent"));

		// Write structured map
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("n/analysis"),
				Strings.create("value"), Maps.of(
					Strings.create("vendor"), Strings.create("Acme"),
					Strings.create("share"), 23)),
			agentCtx).awaitResult(5000);

		// Read back
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Strings.create("path"), Strings.create("n/analysis")),
			agentCtx);
		ACell result = readJob.awaitResult(5000);
		assertTrue(CVMBool.TRUE.equals(RT.getIn(result, Strings.intern("exists"))));
		ACell value = RT.getIn(result, Strings.intern("value"));
		assertEquals("Acme", RT.ensureString(RT.getIn(value, Strings.create("vendor"))).toString());
	}

	@Test
	public void testAgentWorkspaceRejectsWithoutScope() {
		// No agentId on context — n/ should fail
		Job writeJob = engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("n/notes/x"),
				Strings.create("value"), Strings.create("should fail")),
			ALICE);
		assertThrows(Exception.class, () -> writeJob.awaitResult(5000),
			"n/ write without agent scope should fail");
	}

	@Test
	public void testAgentWorkspaceList() {
		RequestContext agentCtx = ALICE.withAgentId(Strings.create("test-agent"));

		// Write multiple keys
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("n/a"),
				Strings.create("value"), Strings.create("alpha")),
			agentCtx).awaitResult(5000);
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("n/b"),
				Strings.create("value"), Strings.create("beta")),
			agentCtx).awaitResult(5000);

		// List n/
		Job listJob = engine.jobs().invokeOperation("covia:list",
			Maps.of(Strings.create("path"), Strings.create("n")),
			agentCtx);
		ACell result = listJob.awaitResult(5000);
		assertTrue(CVMBool.TRUE.equals(RT.getIn(result, Strings.intern("exists"))));
		long count = ((CVMLong) RT.getIn(result, Strings.intern("count"))).longValue();
		assertTrue(count >= 2, "Should have at least 2 entries");
	}

	@Test
	public void testAgentNamespaceResolverThrowsWithoutScope() {
		// n/ outside agent scope should throw
		assertThrows(RuntimeException.class, () ->
			engine.jobs().invokeOperation("covia:write",
				Maps.of(Strings.create("path"), Strings.create("n/notes"),
					Strings.create("value"), Strings.create("test")),
				ALICE).awaitResult(5000));
	}

	// ========== t/ temp namespace ==========

	@Test
	public void testTempWriteAndRead() {
		// Create a job to scope t/ against
		Job job = engine.jobs().invokeOperation("test:echo",
			Maps.of(Strings.create("message"), Strings.create("hello")),
			ALICE);
		job.awaitResult(5000);
		Blob jobId = job.getID();
		RequestContext jobCtx = ALICE.withJobId(jobId);

		// Write to t/
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("t/draft"),
				Strings.create("value"), Maps.of(
					Strings.create("interim"), Strings.create("working value"))),
			jobCtx).awaitResult(5000);

		// Read it back
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Strings.create("path"), Strings.create("t/draft")),
			jobCtx);
		ACell result = readJob.awaitResult(5000);
		assertTrue(RT.bool(RT.getIn(result, "exists")), "Should exist");
		assertEquals("working value",
			RT.ensureString(RT.getIn(result, "value", "interim")).toString());
	}

	@Test
	public void testTempDelete() {
		Job job = engine.jobs().invokeOperation("test:echo",
			Maps.of(Strings.create("message"), Strings.create("hello")),
			ALICE);
		job.awaitResult(5000);
		RequestContext jobCtx = ALICE.withJobId(job.getID());

		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("t/ephemeral"),
				Strings.create("value"), Strings.create("temp data")),
			jobCtx).awaitResult(5000);

		engine.jobs().invokeOperation("covia:delete",
			Maps.of(Strings.create("path"), Strings.create("t/ephemeral")),
			jobCtx).awaitResult(5000);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Strings.create("path"), Strings.create("t/ephemeral")),
			jobCtx);
		ACell result = readJob.awaitResult(5000);
		assertFalse(RT.bool(RT.getIn(result, "exists")), "Should not exist after delete");
	}

	@Test
	public void testTempInspect() {
		Job job = engine.jobs().invokeOperation("test:echo",
			Maps.of(Strings.create("message"), Strings.create("hello")),
			ALICE);
		job.awaitResult(5000);
		RequestContext jobCtx = ALICE.withJobId(job.getID());

		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("t/analysis"),
				Strings.create("value"), Maps.of(
					Strings.create("step1"), Strings.create("gathered"),
					Strings.create("step2"), Strings.create("processed"))),
			jobCtx).awaitResult(5000);

		Job inspectJob = engine.jobs().invokeOperation("covia:inspect",
			Maps.of(Strings.create("paths"), Strings.create("t/analysis"),
				Strings.create("budget"), CVMLong.create(1000)),
			jobCtx);
		ACell result = inspectJob.awaitResult(5000);
		String rendered = RT.ensureString(RT.getIn(result, Strings.intern("result"))).toString();
		assertTrue(rendered.contains("gathered"), "Should contain temp data");
	}

	@Test
	public void testTempIsolatedPerJob() {
		// Two different jobs get different t/ spaces
		Job job1 = engine.jobs().invokeOperation("test:echo",
			Maps.of(Strings.create("message"), Strings.create("one")),
			ALICE);
		job1.awaitResult(5000);
		Job job2 = engine.jobs().invokeOperation("test:echo",
			Maps.of(Strings.create("message"), Strings.create("two")),
			ALICE);
		job2.awaitResult(5000);

		RequestContext ctx1 = ALICE.withJobId(job1.getID());
		RequestContext ctx2 = ALICE.withJobId(job2.getID());

		// Write to job1's t/
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("t/data"),
				Strings.create("value"), Strings.create("job1-only")),
			ctx1).awaitResult(5000);

		// job2 should not see it
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Strings.create("path"), Strings.create("t/data")),
			ctx2);
		ACell result = readJob.awaitResult(5000);
		assertFalse(RT.bool(RT.getIn(result, "exists")),
			"t/ should be isolated per job");
	}

	@Test
	public void testTempAutoScopedByJob() {
		// Every operation now auto-gets jobId from JobManager.
		// A bare write to t/ should work — scoped to that operation's own job.
		Job writeJob = engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("t/auto"),
				Strings.create("value"), Strings.create("auto-scoped")),
			ALICE);
		writeJob.awaitResult(5000);

		// Reading from the same jobId should see the data
		RequestContext sameJobCtx = ALICE.withJobId(writeJob.getID());
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Strings.create("path"), Strings.create("t/auto")),
			sameJobCtx);
		ACell result = readJob.awaitResult(5000);
		assertTrue(RT.bool(RT.getIn(result, "exists")),
			"Should find data written to t/ in same job scope");
	}

	// ========== covia:inspect ==========

	@Test
	public void testInspectSinglePath() {
		// Write data to explore
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/vendors/acme"),
				Strings.create("value"), Maps.of(
					Strings.create("name"), Strings.create("Acme Corp"),
					Strings.create("share"), 23,
					Strings.create("growth"), Strings.create("8%"))),
			ALICE).awaitResult(5000);

		Job exploreJob = engine.jobs().invokeOperation("covia:inspect",
			Maps.of(Strings.create("paths"), Strings.create("w/vendors/acme"),
				Strings.create("budget"), CVMLong.create(2000)),
			ALICE);
		ACell result = exploreJob.awaitResult(5000);
		AString rendered = RT.ensureString(RT.getIn(result, Strings.intern("result")));
		assertNotNull(rendered, "Should return rendered result");
		String s = rendered.toString();
		assertTrue(s.contains("Acme Corp"), "Should contain value");
		assertTrue(s.contains("23"), "Should contain numeric value");
	}

	@Test
	public void testInspectMultiplePaths() {
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/a"),
				Strings.create("value"), Strings.create("alpha")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/b"),
				Strings.create("value"), Strings.create("beta")),
			ALICE).awaitResult(5000);

		Job exploreJob = engine.jobs().invokeOperation("covia:inspect",
			Maps.of(Strings.create("paths"), Vectors.of(
				(ACell) Strings.create("w/a"), (ACell) Strings.create("w/b")),
				Strings.create("budget"), CVMLong.create(1000)),
			ALICE);
		ACell result = exploreJob.awaitResult(5000);
		ACell resultMap = RT.getIn(result, Strings.intern("result"));
		assertTrue(resultMap instanceof AMap, "Multiple paths should return a map");
		AString aVal = RT.ensureString(RT.getIn(resultMap, Strings.create("w/a")));
		AString bVal = RT.ensureString(RT.getIn(resultMap, Strings.create("w/b")));
		assertNotNull(aVal);
		assertNotNull(bVal);
		assertTrue(aVal.toString().contains("alpha"));
		assertTrue(bVal.toString().contains("beta"));
	}

	@Test
	public void testInspectBudgetTruncation() {
		// Write a large map
		AMap<AString, ACell> largeMap = Maps.empty();
		for (int i = 0; i < 50; i++) {
			largeMap = largeMap.assoc(Strings.create("key" + i),
				Strings.create("value" + i + " ".repeat(30)));
		}
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/large"),
				Strings.create("value"), largeMap),
			ALICE).awaitResult(5000);

		// Small budget — should truncate
		Job exploreJob = engine.jobs().invokeOperation("covia:inspect",
			Maps.of(Strings.create("paths"), Strings.create("w/large"),
				Strings.create("budget"), CVMLong.create(200)),
			ALICE);
		ACell result = exploreJob.awaitResult(5000);
		String rendered = RT.ensureString(RT.getIn(result, Strings.intern("result"))).toString();
		assertTrue(rendered.contains("/*"), "Should contain truncation annotation");
		assertTrue(rendered.contains("more"), "Should indicate more entries");
	}

	@Test
	public void testInspectDefaultBudget() {
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/small"),
				Strings.create("value"), Strings.create("hello")),
			ALICE).awaitResult(5000);

		// No budget param — should use default (500)
		Job exploreJob = engine.jobs().invokeOperation("covia:inspect",
			Maps.of(Strings.create("paths"), Strings.create("w/small")),
			ALICE);
		ACell result = exploreJob.awaitResult(5000);
		String rendered = RT.ensureString(RT.getIn(result, Strings.intern("result"))).toString();
		assertTrue(rendered.contains("hello"));
	}

	@Test
	public void testInspectMissingPath() {
		Job exploreJob = engine.jobs().invokeOperation("covia:inspect",
			Maps.of(Strings.create("paths"), Strings.create("w/nonexistent")),
			ALICE);
		ACell result = exploreJob.awaitResult(5000);
		String rendered = RT.ensureString(RT.getIn(result, Strings.intern("result"))).toString();
		assertTrue(rendered.contains("null"), "Missing path should render as null");
		assertTrue(rendered.contains("not found"), "Should indicate not found");
	}

	@Test
	public void testInspectAgentWorkspace() {
		RequestContext agentCtx = ALICE.withAgentId(Strings.create("test-agent"));

		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("n/research"),
				Strings.create("value"), Maps.of(
					Strings.create("finding"), Strings.create("23% market share"))),
			agentCtx).awaitResult(5000);

		Job exploreJob = engine.jobs().invokeOperation("covia:inspect",
			Maps.of(Strings.create("paths"), Strings.create("n/research"),
				Strings.create("budget"), CVMLong.create(1000)),
			agentCtx);
		ACell result = exploreJob.awaitResult(5000);
		String rendered = RT.ensureString(RT.getIn(result, Strings.intern("result"))).toString();
		assertTrue(rendered.contains("23% market share"));
	}

	@Test
	public void testInspectNestedStructure() {
		// Write nested data
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/deep"),
				Strings.create("value"), Maps.of(
					Strings.create("level1"), Maps.of(
						Strings.create("level2"), Maps.of(
							Strings.create("value"), Strings.create("deep data"))))),
			ALICE).awaitResult(5000);

		Job exploreJob = engine.jobs().invokeOperation("covia:inspect",
			Maps.of(Strings.create("paths"), Strings.create("w/deep"),
				Strings.create("budget"), CVMLong.create(5000)),
			ALICE);
		ACell result = exploreJob.awaitResult(5000);
		String rendered = RT.ensureString(RT.getIn(result, Strings.intern("result"))).toString();
		assertTrue(rendered.contains("deep data"), "Should render nested values");
	}

}
