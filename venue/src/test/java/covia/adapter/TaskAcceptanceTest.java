package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.CoviaPath;
import covia.venue.Agent;
import covia.venue.Engine;
import covia.venue.Models;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.User;

/**
 * A request to an agent that cannot take tasks, because its model cannot call
 * tools, fails: at once when the agent can tell, and otherwise before a cycle
 * takes the task (AGENT_TASKS.md §2).
 */
public class TaskAcceptanceTest {

	private static final Engine ENGINE = TestEngine.ENGINE;

	private static final AMap<AString, ACell> NO_TOOL_CALLING =
		Maps.of("options", Maps.of("toolCalling", CVMBool.FALSE));

	private static void create(RequestContext owner, String agentId, AMap<AString, ACell> config) {
		ENGINE.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, agentId, Fields.CONFIG, config), owner).awaitResult(5000);
	}

	private static Job request(RequestContext owner, String agentId, String input) {
		return ENGINE.jobs().invokeOperation("v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, agentId, Fields.INPUT, input), owner);
	}

	private static Agent agent(AString did, String agentId) {
		return ENGINE.getVenueState().users().user(did).agent(agentId);
	}

	private static void assertRefused(Job request, String agentId) {
		assertThrows(JobFailedException.class, () -> request.awaitResult(5000));
		assertEquals(Status.FAILED, request.getStatus());
		assertEquals(Strings.create(AgentAdapter.TASKS_UNSUPPORTED_CODE),
			RT.getIn(request.getData(), Fields.ERROR_CODE));
		assertEquals(Agent.CANNOT_TAKE_TASKS.formatted(agentId, Agent.MODEL_CANNOT_CALL_TOOLS),
			request.getErrorMessage());
	}

	@Test
	public void testRequestToAgentThatCannotCallToolsIsRefused(TestInfo info) {
		AString did = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(did);
		for (String runtime : new String[] {"llmagent", "goaltree"}) {
			String agentId = "no-tools-" + runtime;
			create(owner, agentId, Maps.of(Fields.OPERATION, "v/ops/" + runtime + "/chat",
				Models.K_LLM_OPERATION, "v/test/ops/llm",
				Models.K_MODEL_PROFILE, NO_TOOL_CALLING));

			assertRefused(request(owner, agentId, "work"), agentId);
			Agent agent = agent(did, agentId);
			assertTrue(agent.getTasks().isEmpty(), runtime + ": nothing is queued");
			assertTrue(agent.getSessions().isEmpty(), runtime + ": no session is made");
			assertTrue(agent.getTimeline().isEmpty(), runtime + ": no cycle runs");

			ACell reply = ENGINE.jobs().invokeOperation("v/ops/agent/chat",
				Maps.of(Fields.AGENT_ID, agentId, Fields.MESSAGE, "hello"), owner).awaitResult(5000);
			assertNotNull(RT.getIn(reply, Fields.RESPONSE), runtime + ": chat still works");
		}
	}

	/**
	 * The agent judges its model as it runs, with its controller's authority:
	 * a model in its owner's namespace that its controller may not read is not
	 * read for it, at intake or before its cycle.
	 */
	@Test
	public void testTheModelIsReadWithTheControllersAuthority(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID(info.getDisplayName() + "-controller");
		User owner = ENGINE.getVenueState().users().ensure(alice);
		ENGINE.getVenueState().users().ensure(bob);
		owner.operations().write(CoviaPath.parse("tiny"), Maps.of(
			"operation", Maps.of("adapter", "test:llm"),
			"model", Maps.of("id", "tiny", "provider", "v/test/ops/llm",
				"options", Maps.of("toolCalling", CVMBool.FALSE))));
		AMap<AString, ACell> config = Maps.of(Fields.OPERATION, "v/test/ops/step", Models.K_LLM_OPERATION, "o/tiny");
		owner.createAgent(Strings.create("alices"), config, null);
		owner.createAgent(Strings.create("bobs"), config, null, null, bob);

		RequestContext asker = RequestContext.of(alice);
		assertRefused(request(asker, "alices", "complete"), "alices");
		assertNotNull(request(asker, "bobs", "complete").awaitResult(5000), "Bob may not read Alice's definition");
	}

	@Test
	public void testTaskFailsBeforeItsCycleWhenTheModelChangesAfterAcceptance(TestInfo info) throws Exception {
		AString did = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(did);
		String agentId = "changes-model";
		create(owner, agentId, Maps.of(Fields.OPERATION, "v/test/ops/step"));
		String gateName = "model-change-" + System.nanoTime();
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Job first = request(owner, agentId, "hold:" + gateName + " complete");
			assertTrue(gate.awaitEntered(10, TimeUnit.SECONDS));
			Job second = request(owner, agentId, "complete");
			ENGINE.jobs().invokeOperation("v/ops/agent/update", Maps.of(Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/step", Models.K_MODEL_PROFILE, NO_TOOL_CALLING)),
				owner).awaitResult(5000);
			gate.release();
			assertNotNull(first.awaitResult(5000), "the task its cycle had taken completes");
			assertRefused(second, agentId);
		}
		assertEquals(1, agent(did, agentId).getTimeline().count(), "the refused task had no cycle");
	}
}
