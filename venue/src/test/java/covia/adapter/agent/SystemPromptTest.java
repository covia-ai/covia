package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * {@code config.systemPrompt} as a context entry (AGENT_CONTEXT.md §5.1): a
 * workspace path, a DLFS file, an operation or a job resolve to the identity
 * once per cycle through the same loader as loads; a prompt that does not
 * resolve fails the cycle and warns at create.
 */
public class SystemPromptTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString did;
	private RequestContext ctx;

	@BeforeEach
	public void setup(TestInfo info) {
		did = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(did);
	}

	// ========== helpers ==========

	private ACell call(String op, AMap<AString, ACell> input) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		ACell result = job.awaitResult(15000);
		if (!Status.COMPLETE.equals(job.getStatus())) {
			throw new RuntimeException(op + " " + job.getStatus() + ": " + job.getData().get(Fields.ERROR));
		}
		return result;
	}

	private void write(String path, ACell value) {
		call("v/ops/covia/write", Maps.of(Fields.PATH, Strings.create(path), Fields.VALUE, value));
	}

	private ACell create(String id, ACell prompt, String transitionOp) {
		return call("v/ops/agent/create", Maps.of(
			Fields.AGENT_ID, id,
			Fields.CONFIG, Maps.of(
				Fields.OPERATION, transitionOp,
				"llmOperation", "v/test/ops/llm",
				"systemPrompt", prompt)));
	}

	/** The head of the agent's next prompt — the first message of an inspection. */
	private String head(String agentId) {
		ACell inspected = call("v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, agentId, Fields.MESSAGE, "hello"));
		AVector<ACell> messages = RT.ensureVector(RT.getIn(inspected, Fields.MESSAGES));
		return RT.ensureString(RT.getIn(messages.get(0), "content")).toString();
	}

	// ========== the forms ==========

	@Test
	public void testPromptFromWorkspacePath() {
		write("w/prompts/mina", Strings.create("You are Mina, a careful health-records assistant."));
		ACell created = create("path-prompt-agent", Maps.of("ref", "w/prompts/mina"), "v/ops/llmagent/chat");
		assertNull(RT.getIn(created, Fields.WARNINGS), created.toString());
		String head = head("path-prompt-agent");
		assertTrue(head.startsWith("You are Mina, a careful health-records assistant."), head);
		assertTrue(head.contains("Venue: "), "the identity line still follows: " + head);
		// Live: the next cycle sees an edited prompt.
		write("w/prompts/mina", Strings.create("You are Mina, revised."));
		assertTrue(head("path-prompt-agent").startsWith("You are Mina, revised."));
	}

	@Test
	public void testPromptFromDlfsFile() {
		call("v/ops/dlfs/create-drive", Maps.of(Fields.NAME, Strings.create("prompts")));
		call("v/ops/dlfs/write", Maps.of(
			Strings.create("drive"), Strings.create("prompts"),
			Fields.PATH, Strings.create("mina.md"),
			Fields.CONTENT, Strings.create("# Mina\nYou read the vault before you answer.\n")));
		create("dlfs-prompt-agent", Maps.of("ref", "dlfs/prompts/mina.md"), "v/ops/llmagent/chat");
		String head = head("dlfs-prompt-agent");
		assertTrue(head.startsWith("# Mina\nYou read the vault before you answer."), head);
	}

	@Test
	public void testPromptFromTextEntryAndGoalTree() {
		create("text-prompt-agent", Maps.of("text", "You plan in subgoals."), "v/ops/goaltree/chat");
		assertTrue(head("text-prompt-agent").startsWith("You plan in subgoals."));
	}

	@Test
	public void testPromptFromOperationMustReturnText() {
		// echo returns its input map, not text: the cycle fails with the reason
		// rather than rendering JSON5 as an identity.
		create("op-prompt-agent", Maps.of("op", "v/test/ops/echo",
			"input", Maps.of(Strings.create("x"), Strings.create("y"))), "v/ops/llmagent/chat");
		RuntimeException e = assertThrows(RuntimeException.class, () -> head("op-prompt-agent"));
		assertTrue(e.getMessage().contains("not text"), e.getMessage());
	}

	// ========== failure and validation ==========

	@Test
	public void testUnresolvablePromptWarnsAtCreateAndFailsTheCycle() {
		ACell created = create("missing-prompt-agent", Maps.of("ref", "w/prompts/absent"), "v/ops/llmagent/chat");
		AVector<ACell> warnings = RT.ensureVector(RT.getIn(created, Fields.WARNINGS));
		assertNotNull(warnings, created.toString());
		assertTrue(warnings.toString().contains("systemPrompt does not resolve"), warnings.toString());
		RuntimeException e = assertThrows(RuntimeException.class, () -> head("missing-prompt-agent"));
		assertTrue(e.getMessage().contains("config.systemPrompt did not resolve"), e.getMessage());
		assertTrue(e.getMessage().contains("w/prompts/absent"), e.getMessage());
	}

	@Test
	public void testMalformedPromptEntriesAreRejectedAtCreate() {
		RuntimeException none = assertThrows(RuntimeException.class,
			() -> create("bad-prompt-1", Maps.of("label", "no source"), "v/ops/llmagent/chat"));
		assertTrue(none.getMessage().contains("ref, text, op, job"), none.getMessage());
		RuntimeException two = assertThrows(RuntimeException.class,
			() -> create("bad-prompt-2", Maps.of("ref", "w/x", "text", "y"), "v/ops/llmagent/chat"));
		assertTrue(two.getMessage().contains("at most one of"), two.getMessage());
		// A plain string is still the ordinary form.
		create("string-prompt-agent", Strings.create("Plain identity."), "v/ops/llmagent/chat");
		assertTrue(head("string-prompt-agent").startsWith("Plain identity."));
	}

	@Test
	public void testResolvedOncePerCycleViaEffectiveConfig() {
		// The seam both runtimes use: effectiveModelConfig turns the entry into
		// text, so everything downstream — head, inspection, L3 input — sees a
		// string and a mid-cycle edit cannot move the cached head.
		write("w/prompts/once", Strings.create("Once per cycle."));
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		AMap<AString, ACell> config = Maps.of(
			"llmOperation", "v/test/ops/llm",
			"systemPrompt", Maps.of("ref", "w/prompts/once"));
		AMap<AString, ACell> effective = adapter.effectiveModelConfig(config, ctx);
		assertEquals("Once per cycle.", RT.ensureString(effective.get(AbstractLLMAdapter.K_SYSTEM_PROMPT)).toString());
	}
}
