package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * Tool guards on a real agent run loop: a guard sees each operation call an
 * agent is about to make, a refusal reaches the model as the tool result and
 * the call never runs, and guards that throw or take too long refuse. The
 * engine is shared, so each guard acts only on this test's own user.
 */
public class ToolGuardsTest {

	private static final String REFUSAL = "held for review";

	private final Engine engine = TestEngine.ENGINE;
	private AString owner;

	@BeforeEach
	public void setup(TestInfo info) {
		owner = TestEngine.uniqueDID(info);
	}

	@Test
	public void aGuardSeesTheCallAndItsRefusalReachesTheModel() {
		CompletableFuture<ToolGuards.Call> seen = new CompletableFuture<>();
		try (ToolGuards.Registration r = engine.toolGuards().add(call -> {
			if (!owner.equals(call.ownerDID())) return null;
			seen.complete(call);
			return REFUSAL;
		})) {
			String response = chat("guarded-agent", "use your tool", null);
			assertTrue(response.contains("Error: " + REFUSAL), "the refusal is the tool result: " + response);

			ToolGuards.Call call = seen.join();
			assertEquals(Strings.create("guarded-agent"), call.agentId());
			assertEquals(Strings.create("v/test/ops/echo"), call.operation());
			assertEquals(Strings.create("use your tool"), RT.getIn(call.input(), "echo"));
		}
	}

	@Test
	public void aCallAGuardPassesRuns() {
		try (ToolGuards.Registration r = engine.toolGuards().add(call -> null)) {
			String response = chat("passed-agent", "use your tool", null);
			assertTrue(response.contains("Tool returned"), "the call ran: " + response);
			assertFalse(response.contains("Error:"), response);
		}
	}

	@Test
	public void aGuardThatThrowsRefuses() {
		try (ToolGuards.Registration r = engine.toolGuards().add(call -> {
			if (owner.equals(call.ownerDID())) throw new IllegalStateException("broken");
			return null;
		})) {
			String response = chat("throwing-guard-agent", "use your tool", null);
			assertTrue(response.contains("Error: " + ToolGuards.FAILED), "fail closed: " + response);
		}
	}

	@Test
	public void aGuardThatTakesTooLongRefuses() {
		CountDownLatch never = new CountDownLatch(1);
		try (ToolGuards.Registration r = engine.toolGuards().add(call -> {
			if (owner.equals(call.ownerDID())) never.await();
			return null;
		})) {
			String response = chat("slow-guard-agent", "use your tool", 1000L);
			assertTrue(response.contains("Error: " + ToolGuards.TIMED_OUT), "fail closed: " + response);
		} finally {
			never.countDown();
		}
	}

	/** Creates {@code agentId} on the tool-calling test model and returns its reply to {@code message}. */
	private String chat(String agentId, String message, Long toolCallTimeoutMs) {
		var config = Maps.of(
			Fields.OPERATION, "v/ops/llmagent/chat",
			"llmOperation", "v/test/ops/toolllm");
		if (toolCallTimeoutMs != null) {
			config = config.assoc(Strings.create("toolCallTimeoutMs"), CVMLong.create(toolCallTimeoutMs));
		}
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, agentId, Fields.CONFIG, config),
			RequestContext.of(owner)).awaitResult(5000);
		ACell result = engine.jobs().invokeOperation("v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, agentId, Fields.MESSAGE, Strings.create(message)),
			RequestContext.of(owner)).awaitResult(10000);
		return RT.getIn(result, Fields.RESPONSE).toString();
	}
}
