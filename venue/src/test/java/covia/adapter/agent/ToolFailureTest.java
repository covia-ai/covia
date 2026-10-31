package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.helpers.NOPLogger;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.exception.AuthException;
import covia.venue.TestLogs;

/**
 * How a failed tool call is explained to the model (#644): an expected
 * failure is its own reason, whatever async wrappers it arrives in; anything
 * else is reported as an unexpected venue error, logged at ERROR. Isolated
 * because the unexpected cases silence that ERROR line.
 */
@Isolated
public class ToolFailureTest {

	@Test
	public void testExpectedFailureIsItsOwnReason() {
		String reason = "refs[2] is not a hex hash";
		assertEquals(reason, AbstractLLMAdapter.toolFailure(new IllegalArgumentException(reason), "op"));
		assertEquals(reason, AbstractLLMAdapter.toolFailure(new AuthException(reason), "op"));
	}

	@Test
	public void testAsyncWrappersAreRemoved() throws Exception {
		// A failed future's get() throws ExecutionException, whose own message
		// is the cause's toString — the class name the model must never see.
		String reason = "no such record";
		ExecutionException wrapped = null;
		try {
			CompletableFuture.failedFuture(new IllegalArgumentException(reason)).get();
		} catch (ExecutionException e) {
			wrapped = e;
		}
		assertEquals(reason, AbstractLLMAdapter.toolFailure(wrapped, "op"));
		assertEquals(reason, AbstractLLMAdapter.toolFailure(
			new CompletionException(new ExecutionException(new IllegalStateException(reason))), "op"));
	}

	@Test
	public void testUnexpectedFailureIsReportedAsVenueError() throws Exception {
		TestLogs.quiet(AbstractLLMAdapter.class, () -> {
			String npe = AbstractLLMAdapter.toolFailure(
				new CompletionException(new NullPointerException("x is null")), "op");
			assertTrue(npe.startsWith(AbstractLLMAdapter.UNEXPECTED_TOOL_FAILURE), npe);
			assertTrue(npe.contains(NullPointerException.class.getSimpleName()), npe);

			// An expected type thrown without a reason explains nothing either.
			String blank = AbstractLLMAdapter.toolFailure(new IllegalArgumentException(), "op");
			assertTrue(blank.startsWith(AbstractLLMAdapter.UNEXPECTED_TOOL_FAILURE), blank);
		});
	}

	@Test
	public void testHandlerFailureBecomesToolResult() throws Exception {
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.fallback((call, ctx) -> {
				if ("bug".equals(call.id().toString())) throw new NullPointerException();
				throw new CompletionException(new IllegalArgumentException("bad input"));
			});
		List<AMap<AString, ACell>> messages = new ArrayList<>();
		ToolCycleEngine.BatchSink sink = new ToolCycleEngine.BatchSink() {
			@Override public void append(AMap<AString, ACell> message) { messages.add(message); }
			@Override public void recordCall(ToolCycleEngine.ToolCall call,
				ToolCycleEngine.ToolOutcome outcome, long millis) {}
		};
		TestLogs.quiet(AbstractLLMAdapter.class, () -> ToolCycleEngine.executeBatch(
			Vectors.of(call("input"), call("bug")), 0, registry, new Object(), sink, NOPLogger.NOP_LOGGER));

		assertEquals(2, messages.size());
		String input = content(messages.get(0));
		assertEquals("Error: bad input", input);
		String bug = content(messages.get(1));
		assertTrue(bug.startsWith("Error: " + AbstractLLMAdapter.UNEXPECTED_TOOL_FAILURE), bug);
		assertFalse(input.contains("java."), input);
	}

	private static AMap<AString, ACell> call(String id) {
		return Maps.of(AbstractLLMAdapter.K_ID, Strings.create(id),
			AbstractLLMAdapter.K_NAME, Strings.create("op"),
			AbstractLLMAdapter.K_ARGUMENTS, Maps.empty());
	}

	private static String content(AMap<AString, ACell> message) {
		return message.get(AbstractLLMAdapter.K_CONTENT).toString();
	}
}
