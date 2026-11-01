package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.TestAdapter;
import covia.api.Fields;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.User;

/**
 * A tool call that outlives its agent's {@code toolCallTimeoutMs} (#607): the
 * model is told it may still be running, and when it ends its outcome reaches
 * the asking session as a message. {@code v/test/ops/hold} finishes only when
 * the test releases its gate, so the one real wait is the 1 s minimum limit.
 */
public class LateToolResultTest {

	private static final String HOLD = "v/test/ops/hold";
	private static final long LIMIT_MS = 1000;

	private final Engine engine = TestEngine.ENGINE;

	private static AMap<AString, ACell> holdCall(String id, String gate, String key, String value) {
		return Maps.of("id", id, "name", HOLD, "arguments", Maps.of("gate", gate, key, value));
	}

	private static String conversation(AgentState agent, Blob sid) {
		return GoalTreeContext.activeConversation(RT.ensureMap(
			RT.getIn(agent.getSession(sid), Fields.FRAMES, CVMLong.ZERO))).toString();
	}

	@Test
	public void aCallPastItsLimitDeliversItsOutcomeToTheSession(TestInfo info) {
		AString did = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(did);
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(
			Fields.AGENT_ID, "late-step",
			Fields.CONFIG, Maps.of(
				Fields.OPERATION, "v/ops/llmagent/chat",
				"llmOperation", "v/test/ops/llm",
				AbstractLLMAdapter.K_TOOL_CALL_TIMEOUT_MS, LIMIT_MS)),
			owner).awaitResult(5000);
		User user = engine.getVenueState().users().get(did);
		AgentState agent = user.agent("late-step");
		Blob sid = Blob.fromHex("1a7e00001a7e00001a7e00001a7e0000");
		agent.ensureSession(sid, did);

		String okGate = "late-ok-" + did;
		String badGate = "late-bad-" + did;
		try (TestAdapter.TestGate ok = TestAdapter.createGate(okGate);
				TestAdapter.TestGate bad = TestAdapter.createGate(badGate)) {
			// Both calls run together, so the step pays the limit once.
			AMap<AString, ACell> stepped = RT.ensureMap(engine.jobs().invokeOperation(
				"v/ops/agent/step", Maps.of(
					Fields.AGENT_ID, "late-step",
					Fields.SESSION_ID, sid.toHexString(),
					"assistant", Maps.of("toolCalls", Vectors.of(
						holdCall("c-ok", okGate, "result", "finished-late"),
						holdCall("c-bad", badGate, "error", "broke-late")))),
				owner).awaitResult(10_000));
			AVector<ACell> calls = RT.ensureVector(stepped.get(Fields.CALLS));
			AString stillRunning = Strings.create(String.format(
				AbstractLLMAdapter.TOOL_STILL_RUNNING, HOLD, LIMIT_MS));
			assertEquals(stillRunning, RT.getIn(calls.get(0), Fields.RESULT),
				"a call past its limit says it may still be running");
			assertEquals(stillRunning, RT.getIn(calls.get(1), Fields.RESULT));
			assertEquals(0, agent.getTimeline() == null ? 0 : agent.getTimeline().count(),
				"nothing reaches the session while the calls are still running");

			ok.release();
			bad.release();
		}

		// Each outcome is a message in the asking session, which wakes the agent.
		TestEngine.awaitCondition(() -> {
			if (agent.getSession(sid) == null) return false;
			String c = conversation(agent, sid);
			return c.contains("finished-late") && c.contains("broke-late");
		}, 10_000, () -> "both late outcomes should reach the session: " + conversation(agent, sid));
		String c = conversation(agent, sid);
		assertTrue(c.contains("COMPLETE") && c.contains("FAILED"),
			"each outcome carries its status: " + c);
		assertTrue(c.contains(HOLD), "each outcome names its operation: " + c);
	}
}
