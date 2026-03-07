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
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
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

		assertNotNull(result, "Should read agent record");
		assertEquals(AgentState.SLEEPING, RT.getIn(result, AgentState.KEY_STATUS));
	}

	@Test
	public void testReadAgentState() {
		Job job = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "g/test-agent/state"), ALICE);
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(CVMLong.ZERO, RT.getIn(result, "counter"));
	}

	@Test
	public void testReadAgentConfig() {
		Job job = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "g/test-agent/config"), ALICE);
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(Strings.create("gpt-4"), RT.getIn(result, "model"));
	}

	@Test
	public void testReadNonExistentPath() {
		Job job = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "g/no-such-agent"), ALICE);
		ACell result = job.awaitResult(5000);

		assertNull(result);
	}

	@Test
	public void testReadWithVectorPath() {
		Job job = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, Vectors.of(
				Strings.create("g"), Strings.create("test-agent"), Strings.create("status"))),
			ALICE);
		ACell result = job.awaitResult(5000);

		assertEquals(AgentState.SLEEPING, result);
	}

	@Test
	public void testReadEmptyPath() {
		// Empty path returns entire user root
		Job job = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, ""), ALICE);
		ACell result = job.awaitResult(5000);

		assertNotNull(result, "Should return user root");
	}

	// ========== covia:list ==========

	@Test
	public void testListAgents() {
		Job job = engine.jobs().invokeOperation("covia:list",
			Maps.of(Fields.PATH, "g"), ALICE);
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
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
		AVector<ACell> keys = RT.getIn(result, "keys");
		assertNotNull(keys);
		assertTrue(keys.count() > 0, "Agent record should have fields");
		// Should include standard fields like status, config, state, etc.
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

		// List with limit
		Job job = engine.jobs().invokeOperation("covia:list",
			Maps.of(Fields.PATH, "g", Fields.LIMIT, CVMLong.create(3)), ALICE);
		ACell result = job.awaitResult(5000);

		AVector<ACell> keys = RT.getIn(result, "keys");
		CVMLong total = RT.getIn(result, "count");
		assertEquals(3, keys.count(), "Should respect limit");
		assertEquals(6, total.longValue(), "Total should include all agents (5 + test-agent)");
	}

	@Test
	public void testListNonExistentPath() {
		Job job = engine.jobs().invokeOperation("covia:list",
			Maps.of(Fields.PATH, "g/no-such-agent"), ALICE);
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		AVector<ACell> keys = RT.getIn(result, "keys");
		assertEquals(0, keys.count());
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
