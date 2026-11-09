package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import convex.core.data.prim.CVMLong;
import covia.exception.AuthException;
import covia.exception.FaultException;
import covia.exception.InvalidInputException;
import covia.exception.NotSupportedException;
import covia.venue.TestLogs;

/**
 * How a failed tool call is explained to the model: a failure already reported
 * passes as it is, whatever async wrappers it arrives in; anything else is a
 * fault in the code handling the call, reported as the tool's failure in its
 * owner's code and logged at ERROR. Isolated because the fault cases silence
 * that ERROR line.
 */
@Isolated
public class ToolFailureTest {

	private static final String OWNER = FaultException.VENUE;

	@Test
	public void testExpectedFailureIsItsOwnReason() {
		String reason = "refs[2] is not a hex hash";
		assertEquals(reason, AbstractLLMAdapter.toolFailure(new InvalidInputException(reason), "op", OWNER));
		assertEquals(reason, AbstractLLMAdapter.toolFailure(new AuthException(reason), "op", OWNER));
	}

	@Test
	public void testAFailureIsWholeHoweverLong() {
		String reason = "first line\n\n   " + "x".repeat(ConversationRenderer.MAX_TOOL_FAILURE_CHARS * 5);
		assertEquals(reason, AbstractLLMAdapter.toolFailure(new InvalidInputException(reason), "op", OWNER));
	}

	@Test
	public void testAsyncWrappersAreRemoved() throws Exception {
		// A failed future's get() throws ExecutionException, whose own message
		// is the cause's toString — the class name the model must never see.
		String reason = "no such record";
		ExecutionException wrapped = null;
		try {
			CompletableFuture.failedFuture(new InvalidInputException(reason)).get();
		} catch (ExecutionException e) {
			wrapped = e;
		}
		assertEquals(reason, AbstractLLMAdapter.toolFailure(wrapped, "op", OWNER));
		assertEquals(reason, AbstractLLMAdapter.toolFailure(
			new CompletionException(new ExecutionException(new NotSupportedException(reason))), "op", OWNER));
		assertTrue(covia.adapter.AAdapter.isExpectedFailure(new CompletionException(new AuthException(reason))));
		assertFalse(covia.adapter.AAdapter.isExpectedFailure(new CompletionException(new NullPointerException(reason))));
	}

	/** An operation's fault, reported where it happened, reaches the model as that report. */
	@Test
	public void testAReportedFaultPassesUnchanged() throws Exception {
		TestLogs.quiet(FaultException.class, () -> {
			FaultException reported = (FaultException) FaultException.within("Operation crown:query",
				FaultException.ADAPTER.formatted("crown"), new IllegalArgumentException("crown requires x"));
			assertEquals(reported.getMessage(), AbstractLLMAdapter.toolFailure(reported, "crown", OWNER));
			assertFalse(covia.adapter.AAdapter.isExpectedFailure(reported), "a fault is not the caller's to fix");
		});
	}

	/** A cycle's failure carrier tells a caller what the transition reports:
	 *  an expected failure's reason, or a fault in its adapter, never its text. */
	@Test
	public void testACycleFailureSaysWhatTheTransitionReports() throws Exception {
		String owner = FaultException.ADAPTER.formatted("llmagent");
		CycleRecord.begin();
		InvalidInputException refused = new InvalidInputException("bad input");
		RuntimeException expected = CycleRecord.Failure.of(refused, owner);
		assertEquals("bad input", expected.getMessage());
		assertSame(refused, expected.getCause(), "the run loop reads the failure itself");
		TestLogs.quiet(FaultException.class, () -> {
			CycleRecord.begin();
			IllegalStateException fault = new IllegalStateException("internal detail");
			RuntimeException carried = CycleRecord.Failure.of(fault, owner);
			assertEquals(FaultException.FAILED.formatted(CycleRecord.TRANSITION,
				IllegalStateException.class.getSimpleName(), owner), carried.getMessage());
			FaultException report = assertInstanceOf(FaultException.class, carried.getCause(),
				"the run loop reads the transition's report");
			assertSame(fault, report.getCause());
		});
	}

	@Test
	public void testUnexpectedFailureIsTheToolsFault() throws Exception {
		TestLogs.quiet(FaultException.class, () -> {
			String npe = AbstractLLMAdapter.toolFailure(
				new CompletionException(new NullPointerException("x is null")), "op", OWNER);
			assertEquals(FaultException.FAILED.formatted(AbstractLLMAdapter.TOOL.formatted("op"),
				NullPointerException.class.getSimpleName(), OWNER), npe);
			assertFalse(npe.contains("x is null"), "the model never sees an unexpected failure's text: " + npe);

			// A JDK exception is unexpected whatever its message says.
			String jdk = AbstractLLMAdapter.toolFailure(new IllegalArgumentException("bad ref"), "op", OWNER);
			assertEquals(FaultException.FAILED.formatted(AbstractLLMAdapter.TOOL.formatted("op"),
				IllegalArgumentException.class.getSimpleName(), OWNER), jdk);

			// A Covia exception thrown without a reason explains nothing either.
			String blank = AbstractLLMAdapter.toolFailure(new InvalidInputException((String) null), "op", OWNER);
			assertEquals(FaultException.FAILED.formatted(AbstractLLMAdapter.TOOL.formatted("op"),
				InvalidInputException.class.getSimpleName(), OWNER), blank);
		});
	}

	@Test
	public void testHandlerFailureBecomesToolResult() throws Exception {
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.fallback((call, ctx) -> {
				if ("bug".equals(call.id().toString())) throw new NullPointerException();
				throw new CompletionException(new InvalidInputException("bad input"));
			});
		List<AMap<AString, ACell>> messages = new ArrayList<>();
		ToolCycleEngine.BatchSink sink = new ToolCycleEngine.BatchSink() {
			@Override public void append(AMap<AString, ACell> message) { messages.add(message); }
			@Override public void recordCall(ToolCycleEngine.ToolCall call,
				ToolCycleEngine.ToolOutcome outcome, long millis) {}
		};
		TestLogs.quiet(FaultException.class, () -> ToolCycleEngine.executeBatch(
			Vectors.of(call("input"), call("bug")), 0, registry, new Object(), sink,
			() -> CVMLong.create(0), NOPLogger.NOP_LOGGER));

		assertEquals(2, messages.size());
		String input = content(messages.get(0));
		assertEquals("Error: bad input", input);
		String bug = content(messages.get(1));
		assertEquals("Error: " + FaultException.FAILED.formatted(AbstractLLMAdapter.TOOL.formatted("op"),
			NullPointerException.class.getSimpleName(), FaultException.VENUE), bug);
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
