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
 * Tests for the CoviaAdapter: lattice read and list operations.
 */
public class CoviaAdapterTest {

	private Engine engine;
	private static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");
	private static final RequestContext ALICE = RequestContext.of(ALICE_DID);

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
}
