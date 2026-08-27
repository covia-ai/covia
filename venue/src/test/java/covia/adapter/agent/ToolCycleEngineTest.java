package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * The tool batch protocol under concurrency: adjacent operation calls run at
 * the same time, harness tools are ordered barriers, results keep call order,
 * and the terminal fence still holds. Unit tests drive
 * {@link ToolCycleEngine#executeBatch} with a synthetic registry; one test
 * runs a real batch through {@code agent:step}.
 */
public class ToolCycleEngineTest {

	private static final Logger log = LoggerFactory.getLogger(ToolCycleEngineTest.class);
	private static final String COMPLETE = "complete";
	private static final String CONTROL = "context_load";

	private final Engine engine = TestEngine.ENGINE;
	private AString did;

	@BeforeEach
	public void setup(TestInfo info) {
		did = TestEngine.uniqueDID(info);
	}

	// ========== helpers ==========

	/** A recording sink: appended messages in order, plus recorded call ids. */
	private static final class Sink implements ToolCycleEngine.BatchSink {
		final List<AMap<AString, ACell>> messages = new ArrayList<>();
		final List<String> recorded = new ArrayList<>();
		@Override public void append(AMap<AString, ACell> message) { messages.add(message); }
		@Override public void recordCall(ToolCycleEngine.ToolCall call, ToolCycleEngine.ToolOutcome outcome, long millis) {
			recorded.add(call.id().toString());
		}
	}

	private static AMap<AString, ACell> call(String id, String name) {
		return Maps.of(AbstractLLMAdapter.K_ID, Strings.create(id),
			AbstractLLMAdapter.K_NAME, Strings.create(name),
			AbstractLLMAdapter.K_ARGUMENTS, Maps.of(Strings.create("id"), Strings.create(id)));
	}

	private static String id(AMap<AString, ACell> message) {
		return RT.ensureString(message.get(AbstractLLMAdapter.K_ID)).toString();
	}

	private static String content(AMap<AString, ACell> message) {
		ACell c = message.get(AbstractLLMAdapter.K_CONTENT);
		if (c == null) c = RT.getIn(message, "structuredContent");
		return (c != null) ? c.toString() : null;
	}

	private static ToolCycleEngine.ToolOutcome ok(String text) {
		return ToolCycleEngine.ToolOutcome.result(Strings.create(text));
	}

	// ========== unit: the wave model ==========

	@Test
	public void testAdjacentOperationCallsRunConcurrently() throws Exception {
		// Three operation calls each wait at a barrier for the other two. Run
		// one at a time, nobody ever arrives and every call times out; run
		// together, all three pass — the concurrency is the assertion.
		CyclicBarrier barrier = new CyclicBarrier(3);
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.fallback((c, ctx) -> {
				barrier.await(5, TimeUnit.SECONDS);
				return ok("ran " + c.id());
			});
		Sink sink = new Sink();
		long started = System.nanoTime();
		ToolCycleEngine.BatchResult batch = ToolCycleEngine.executeBatch(
			Vectors.of(call("a", "op"), call("b", "op"), call("c", "op")), 0, registry, new Object(), sink, log);
		long millis = (System.nanoTime() - started) / 1_000_000;

		assertFalse(batch.isTerminal());
		assertFalse(batch.isAborted());
		assertEquals(List.of("a", "b", "c"), sink.messages.stream().map(ToolCycleEngineTest::id).toList());
		for (AMap<AString, ACell> m : sink.messages) {
			assertEquals("ran " + id(m), content(m), "every call passed the barrier");
		}
		assertTrue(millis < 4000, "the wave must not have serialised (took " + millis + "ms)");
	}

	@Test
	public void testResultsKeepCallOrderWhateverFinishesFirst() throws Exception {
		// Call a cannot finish until b has: b completes first, yet the
		// provider sees results in call order, each paired with its id.
		CountDownLatch bDone = new CountDownLatch(1);
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.fallback((c, ctx) -> {
				if ("a".equals(c.id().toString())) {
					assertTrue(bDone.await(5, TimeUnit.SECONDS), "b must be able to finish while a waits");
					return ok("a after b");
				}
				bDone.countDown();
				return ok("b first");
			});
		Sink sink = new Sink();
		ToolCycleEngine.executeBatch(Vectors.of(call("a", "op"), call("b", "op")), 0, registry, new Object(), sink, log);

		assertEquals(List.of("a", "b"), sink.messages.stream().map(ToolCycleEngineTest::id).toList());
		assertEquals("a after b", content(sink.messages.get(0)));
		assertEquals("b first", content(sink.messages.get(1)));
		assertEquals(List.of("a", "b"), sink.recorded, "cycle recording follows call order too");
	}

	@Test
	public void testHarnessToolIsAnOrderedBarrier() throws Exception {
		// [op1, op2, control, op3]: the operation wave before the control tool
		// finishes before it runs, and the one after it does not start until
		// it has returned — the control tool keeps its position.
		ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
		Thread caller = Thread.currentThread();
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.register(CONTROL, (c, ctx) -> {
				assertSame(caller, Thread.currentThread(), "harness tools run on the calling thread");
				events.add("control");
				return ok("loaded");
			})
			.fallback((c, ctx) -> {
				events.add(c.id() + ":start");
				Thread.sleep(50);
				events.add(c.id() + ":end");
				return ok("ran");
			});
		Sink sink = new Sink();
		ToolCycleEngine.executeBatch(
			Vectors.of(call("1", "op"), call("2", "op"), call("ctl", CONTROL), call("3", "op")),
			0, registry, new Object(), sink, log);

		List<String> seq = new ArrayList<>(events);
		int control = seq.indexOf("control");
		assertTrue(seq.indexOf("1:end") < control && seq.indexOf("2:end") < control,
			"the wave before the barrier completes first: " + seq);
		assertTrue(control < seq.indexOf("3:start"), "the wave after waits: " + seq);
		assertEquals(List.of("1", "2", "ctl", "3"), sink.messages.stream().map(ToolCycleEngineTest::id).toList());
	}

	@Test
	public void testSingleCallRunsOnTheCallingThread() {
		// The common case pays no thread hop, so a nested harness still sees
		// this thread's CycleRecord.
		Thread caller = Thread.currentThread();
		List<Thread> seen = new ArrayList<>();
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.fallback((c, ctx) -> { seen.add(Thread.currentThread()); return ok("ran"); });
		ToolCycleEngine.executeBatch(Vectors.of(call("only", "op")), 0, registry, new Object(), new Sink(), log);
		assertEquals(1, seen.size());
		assertSame(caller, seen.get(0));
	}

	@Test
	public void testTerminalStillFencesEveryLaterCall() {
		// [op, complete, op, op]: the calls after a successful terminal call
		// are never dispatched and each still gets a result for its id.
		AtomicInteger dispatched = new AtomicInteger();
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.register(COMPLETE, (c, ctx) -> ToolCycleEngine.ToolOutcome.terminal(
				Strings.create("done"), "completed", Strings.create("value")))
			.fallback((c, ctx) -> { dispatched.incrementAndGet(); return ok("ran"); });
		Sink sink = new Sink();
		ToolCycleEngine.BatchResult batch = ToolCycleEngine.executeBatch(
			Vectors.of(call("1", "op"), call("done", COMPLETE), call("2", "op"), call("3", "op")),
			0, registry, new Object(), sink, log);

		assertTrue(batch.isTerminal());
		assertEquals("completed", batch.terminalStatus());
		assertEquals(1, dispatched.get(), "only the call before the terminal ran");
		assertEquals(List.of("1", "done", "2", "3"), sink.messages.stream().map(ToolCycleEngineTest::id).toList());
		assertTrue(content(sink.messages.get(2)).startsWith("Error: not executed"));
		assertTrue(content(sink.messages.get(3)).startsWith("Error: not executed"));
		assertEquals(List.of("1", "done"), sink.recorded, "fenced calls are never recorded as executed");
	}

	@Test
	public void testAbortStopsTheBatch() {
		AtomicInteger dispatched = new AtomicInteger();
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.register(CONTROL, (c, ctx) -> ToolCycleEngine.ToolOutcome.abort())
			.fallback((c, ctx) -> { dispatched.incrementAndGet(); return ok("ran"); });
		Sink sink = new Sink();
		ToolCycleEngine.BatchResult batch = ToolCycleEngine.executeBatch(
			Vectors.of(call("1", "op"), call("stop", CONTROL), call("2", "op")),
			0, registry, new Object(), sink, log);
		assertTrue(batch.isAborted());
		assertEquals(1, dispatched.get());
		assertEquals(List.of("1"), sink.messages.stream().map(ToolCycleEngineTest::id).toList());
	}

	@Test
	public void testFailuresInAWaveStayWithTheirCall() {
		// A malformed argument string, a throwing handler, and a null outcome
		// each cost only their own call an error; the neighbours succeed and
		// order is kept.
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.fallback((c, ctx) -> switch (c.id().toString()) {
				case "throws" -> throw new IllegalStateException("boom");
				case "nothing" -> null;
				default -> ok("ran " + c.id());
			});
		AMap<AString, ACell> malformed = Maps.of(AbstractLLMAdapter.K_ID, Strings.create("bad"),
			AbstractLLMAdapter.K_NAME, Strings.create("op"),
			AbstractLLMAdapter.K_ARGUMENTS, Strings.create("{not json"));
		Sink sink = new Sink();
		ToolCycleEngine.executeBatch(
			Vectors.of(call("a", "op"), malformed, call("throws", "op"), call("nothing", "op"), call("z", "op")),
			0, registry, new Object(), sink, log);

		assertEquals(List.of("a", "bad", "throws", "nothing", "z"),
			sink.messages.stream().map(ToolCycleEngineTest::id).toList());
		assertEquals("ran a", content(sink.messages.get(0)));
		assertTrue(content(sink.messages.get(1)).startsWith("Error:"));
		assertTrue(content(sink.messages.get(2)).contains("boom"));
		assertTrue(content(sink.messages.get(3)).contains("no outcome"));
		assertEquals("ran z", content(sink.messages.get(4)));
	}

	// ========== end to end: a real batch through agent:step ==========

	@Test
	public void testStepRunsAParallelBatchConcurrently() {
		// Two 400ms delays requested in one reply: sequential execution takes
		// at least 800ms, concurrent about 400ms. The per-call ms in the step
		// result still reports each call's own wall clock.
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "parallel-step-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/llm",
					"systemPrompt", "You step.",
					Fields.TOOLS, Vectors.of(Strings.create("v/test/ops/delay")))),
			RequestContext.of(did)).awaitResult(5000);

		AMap<AString, ACell> delayed = Maps.of(
			Fields.OPERATION, Strings.create("v/test/ops/echo"),
			Fields.DELAY, CVMLong.create(400),
			Fields.INPUT, Maps.of(Strings.create("x"), CVMLong.create(1)));
		long started = System.nanoTime();
		AMap<AString, ACell> stepped = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/step",
			Maps.of(Fields.AGENT_ID, "parallel-step-agent", Fields.MESSAGE, "wait twice",
				"assistant", Maps.of("content", "Waiting.", "toolCalls", Vectors.of(
					Maps.of("id", "d1", "name", "test_delay", "arguments", delayed),
					Maps.of("id", "d2", "name", "test_delay", "arguments", delayed)))),
			RequestContext.of(did)).awaitResult(10000));
		long millis = (System.nanoTime() - started) / 1_000_000;

		AVector<ACell> calls = RT.ensureVector(stepped.get(Strings.intern("calls")));
		assertNotNull(calls, stepped.toString());
		assertEquals(2, calls.count());
		for (long i = 0; i < calls.count(); i++) {
			assertNull(RT.getIn(calls.get(i), "isError"), calls.get(i).toString());
			assertEquals(CVMLong.create(1), RT.getIn(calls.get(i), "result", "x"));
			long ms = RT.ensureLong(RT.getIn(calls.get(i), "ms")).longValue();
			assertTrue(ms >= 350, "each call reports its own wall clock: " + ms);
		}
		assertEquals("d1", RT.getIn(calls.get(0), "id").toString());
		assertEquals("d2", RT.getIn(calls.get(1), "id").toString());
		assertTrue(millis < 750, "two 400ms calls must overlap, took " + millis + "ms");
	}
}
